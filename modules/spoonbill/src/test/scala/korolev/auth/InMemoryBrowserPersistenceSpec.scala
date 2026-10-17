package spoonbill.auth

import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.action.InvocationBinding
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.security.transaction.{OperationError, OperationProtocolException, TransactionFailure}
import spoonbill.server.BrowserSessionToken

class InMemoryBrowserPersistenceSpec extends BrowserReplacementConformance {
  private case class HostState(
    sessions: Map[UUID, HostPrincipal[String]] = Map.empty,
    revoked: Set[UUID] = Set.empty,
    proofs: Int = 0,
    audits: Int = 0,
    acknowledgments: Int = 0,
    disabled: Boolean = false
  )
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private class MemoryFixture(limits: BrowserPersistenceLimits = BrowserPersistenceLimits())
      extends BrowserReplacementConformance.Fixture {
    val clock          = new AtomicReference(BrowserPersistenceConformance.start)
    private val ids    = new AtomicLong()
    def nextId(): UUID = new UUID(0L, ids.incrementAndGet())
    val persistence: InMemoryBrowserPersistence[Future, HostState, String] =
      new InMemoryBrowserPersistence[Future, HostState, String](
        HostState(),
        new InMemoryBrowserPersistence.Host[HostState, String] {
          def resolve(state: HostState, id: UUID, now: Instant): Option[HostPrincipal[String]] =
            if (state.disabled || state.revoked.contains(id)) None else state.sessions.get(id)
          override def acknowledge(state: HostState, completion: UUID, session: UUID, now: Instant): HostState =
            state.copy(acknowledgments = state.acknowledgments + 1)
        },
        () => clock.get(),
        () => nextId(),
        limits
      )
    def stage(tx: InMemoryBrowserPersistence.Transaction[HostState]): HostSessionPrepared = {
      val id        = nextId()
      val deadline  = clock.get().plusSeconds(300)
      val principal = HostPrincipal("name", "subject", 1L, 1L, deadline)
      tx.modify(s => s.copy(sessions = s.sessions.updated(id, principal), proofs = s.proofs + 1, audits = s.audits + 1))
      val bytes = Array.tabulate[Byte](32)(index => (index + id.getLeastSignificantBits).toByte)
      val credential =
        BrowserSessionToken.fromString(Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)).toOption.get
      new HostSessionPrepared(id, nextId(), deadline, credential)
    }
    def prepare(
      ceremony: BrowserCeremony,
      fail: Boolean = false
    ): Future[Either[TransactionFailure, CommittedAuthentication]] = persistence.prepare(ceremony) { tx =>
      val prepared = stage(tx)
      if (fail) throw new IllegalStateException("Synthetic host failure after staging")
      prepared
    }
    def replacementPersistence: BrowserReplacementSupport[Future, String] = persistence
    def prepareReplacement(
      ceremony: BrowserReplacementCeremony,
      invocation: BrowserReplacementInvocation,
      predecessorSessionId: UUID,
      fail: Boolean = false
    ): Future[Either[TransactionFailure, CommittedReplacement]] =
      persistence.prepareReplacement(ceremony, invocation) { tx =>
        val prepared = stage(tx)
        tx.modify(s => s.copy(revoked = s.revoked + predecessorSessionId))
        if (fail) throw new IllegalStateException("Synthetic replacement host failure after staging")
        prepared
      }
    def successorCompletion(
      view: BrowserView[String]
    ): Future[Either[TransactionFailure, VerifiedSuccessorCompletion]] =
      persistence.withSuccessorCompletion(view)((_, completion) => completion)
    def hostState: HostState   = accepted(await(persistence.inspectHost))
    def committedSessions: Int = hostState.sessions.size
    def open(connection: Long = 1L): BrowserView[String] = {
      val identity = accepted(await(persistence.capture(BrowserPersistenceConformance.binding, None, activate = false)))
      accepted(
        await(
          persistence.claim(
            identity,
            Qsid("device", "view"),
            ConnectionId.fromUuid(new UUID(0L, connection)),
            existingOnly = false
          )
        )
      )
    }
    def close(): Unit = await(persistence.close())
    def login(): BrowserView[String] = {
      val anonymous = open()
      val receipt   = accepted(await(prepare(accepted(await(persistence.begin(anonymous, "login"))))))
      val token     = await(persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
      val identity = accepted(
        await(persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
      )
      accepted(await(persistence.claim(identity, anonymous.qsid, ConnectionId.fromUuid(nextId()), existingOnly = true)))
    }
    def invocation(view: BrowserView[String]): BrowserReplacementInvocation =
      BrowserReplacementInvocation.capture(
        view,
        new InvocationBinding(InvocationId.fromUuid(nextId()), view.connectionId)
      )
    def replacement(view: BrowserView[String]): BrowserReplacementCeremony = {
      val ceremony = accepted(await(persistence.beginReplacement(view, "rotate-factor")))
      accepted(await(persistence.bindReplacementChallenge(ceremony, BrowserChallenge("subject", 1L, 1L, "proof"))))
    }
    def replace(
      tx: InMemoryBrowserPersistence.Transaction[HostState],
      view: BrowserView[String]
    ): HostSessionPrepared = {
      val prepared = stage(tx)
      tx.modify(s => s.copy(revoked = s.revoked + view.identity.sessionId.get))
      prepared
    }
    def request(
      view: BrowserView[String],
      ceremony: BrowserReplacementCeremony,
      token: Option[String] = None
    ): BrowserReplacementRequest =
      new BrowserReplacementRequest(
        BrowserPersistenceConformance.binding,
        token.orElse(view.identity.token),
        Some(ceremony.id)
      )
  }
  protected def fixture(): BrowserReplacementConformance.Fixture = new MemoryFixture

  "Memory replacement retention" should "store ownership metadata without retaining credential-bearing view capabilities" in
    Using.resource(new MemoryFixture) { db =>
      val records = db.persistence.getClass.getDeclaredClasses.filter(kind =>
        kind.getSimpleName == "Replacement" || kind.getSimpleName == "ReplacementView"
      )
      records.length shouldBe 2
      records.foreach { kind =>
        val fields = kind.getDeclaredFields.map(_.getType).toSet
        fields should not contain classOf[BrowserView[?]]
        fields should not contain classOf[BrowserIdentity[?]]
      }
    }

  "Memory replacement" should "commit revocation and successor atomically and disclose only through an activated successor" in
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(maxDeliveries = 1))) { db =>
      val view                 = db.login()
      val ceremony             = db.replacement(view)
      val invocation           = db.invocation(view)
      var hostCompletion: UUID = null
      val handoff = accepted(await(db.persistence.prepareReplacement(ceremony, invocation) { tx =>
        val prepared = db.replace(tx, view)
        hostCompletion = prepared.completionId
        prepared
      }))
      db.committedSessions shouldBe 2
      db.hostState.proofs shouldBe 2
      db.hostState.audits shouldBe 2
      await(db.persistence.authorize(view)).isLeft shouldBe true
      await(db.persistence.capture(BrowserPersistenceConformance.binding, None, activate = false)).isLeft shouldBe true
      await(db.persistence.withSuccessorCompletion(view)((_, _) => fail("predecessor callback"))).isLeft shouldBe true
      accepted(await(db.persistence.admitReplacementCompletion(handoff, invocation)))
      await(db.persistence.admitReplacementCompletion(handoff, invocation)).isLeft shouldBe true
      val request = db.request(view, ceremony)
      val delivery = accepted(await(db.persistence.recoverReplacement(request))) match {
        case BrowserReplacementRecovery.Deliver(value) => value
        case other                                     => fail(s"Unexpected recovery $other")
      }
      await(db.persistence.deliverReplacement(db.request(view, ceremony), delivery)).isLeft shouldBe true
      val token = accepted(await(db.persistence.deliverReplacement(request, delivery)))
      await(db.persistence.deliverReplacement(request, delivery)).isLeft shouldBe true
      await(db.persistence.resources).deliveryMaterials shouldBe 0
      await(
        db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = false)
      ).isLeft shouldBe true
      accepted(
        await(db.persistence.recoverReplacement(request))
      ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
      val held = db.request(view, ceremony, Some(token.value))
      accepted(await(db.persistence.replacementPending(held))) shouldBe true
      accepted(
        await(db.persistence.recoverReplacement(held))
      ) shouldBe BrowserReplacementRecovery.ActivateExistingCookie
      val activated = accepted(
        await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
      )
      activated.generation shouldBe view.identity.generation + 1
      accepted(await(db.persistence.replacementPending(held))) shouldBe false
      val successor = accepted(
        await(db.persistence.claim(activated, view.qsid, ConnectionId.fromUuid(db.nextId()), existingOnly = true))
      )
      var escaped: InMemoryBrowserPersistence.Transaction[HostState] = null
      accepted(await(db.persistence.withSuccessorCompletion(successor) { (tx, completion) =>
        escaped = tx
        completion.hostCompletionId shouldBe hostCompletion
        completion.hostCompletionId should not be handoff.completionId
        completion.successorSessionId shouldBe activated.sessionId.get
        tx.modify(s => s.copy(audits = s.audits + 1))
        "consumed"
      })) shouldBe "consumed"
      intercept[OperationProtocolException](escaped.hostState)
      db.hostState.audits shouldBe 3
      await(db.persistence.authorize(view)).isLeft shouldBe true
    }

  it should "reject replacement through ordinary preparation and roll back every staged host effect" in
    Using.resource(new MemoryFixture) { db =>
      val view     = db.login()
      val original = db.hostState
      val ordinary = accepted(await(db.persistence.begin(view, "rotate-factor")))
      await(db.persistence.prepare(ordinary)(tx => db.replace(tx, view))).isLeft shouldBe true
      db.hostState shouldBe original
      await(db.persistence.authorize(view)).isRight shouldBe true
    }

  it should "reject invalid successors and retain a negative replay fence without repeating host work" in {
    List("subject", "security", "policy", "unrevoked", "session", "credential").foreach { invalid =>
      Using.resource(new MemoryFixture) { db =>
        val view       = db.login()
        val original   = db.hostState
        val ceremony   = db.replacement(view)
        val invocation = db.invocation(view)
        var calls      = 0
        val result = await(db.persistence.prepareReplacement(ceremony, invocation) { tx =>
          calls += 1
          val prepared = if (invalid == "unrevoked") db.stage(tx) else db.replace(tx, view)
          tx.modify { s =>
            val current = s.sessions(prepared.sessionId)
            val changed = invalid match {
              case "subject"  => current.copy(subject = "other")
              case "security" => current.copy(securityVersion = 0L)
              case "policy"   => current.copy(policyVersion = 0L)
              case _          => current
            }
            s.copy(sessions = s.sessions.updated(prepared.sessionId, changed))
          }
          if (invalid == "session")
            new HostSessionPrepared(
              view.identity.sessionId.get,
              prepared.completionId,
              prepared.expiresAt,
              prepared.credential
            )
          else if (invalid == "credential")
            new HostSessionPrepared(
              prepared.sessionId,
              prepared.completionId,
              prepared.expiresAt,
              BrowserSessionToken.fromString(view.identity.token.get).toOption.get
            )
          else prepared
        })
        withClue(invalid)(result.isLeft shouldBe true)
        db.hostState shouldBe original
        accepted(
          await(db.persistence.recoverReplacement(db.request(view, ceremony)))
        ) shouldBe BrowserReplacementRecovery.NotCommitted
        await(db.persistence.prepareReplacement(ceremony, invocation) { tx =>
          calls += 1; db.replace(tx, view)
        }).isLeft shouldBe true
        calls shouldBe 1
      }
    }
  }

  it should "reject a changed but still-authorized predecessor stamp before replacement host work" in {
    val changes: Vector[(String, HostPrincipal[String] => HostPrincipal[String])] = Vector(
      "security" -> (principal => principal.copy(securityVersion = principal.securityVersion + 1L)),
      "policy"   -> (principal => principal.copy(policyVersion = principal.policyVersion + 1L)),
      "subject"  -> (principal => principal.copy(subject = "changed-subject")),
      "expiry"   -> (principal => principal.copy(expiresAt = principal.expiresAt.plusSeconds(60)))
    )
    changes.foreach { case (change, update) =>
      Using.resource(new MemoryFixture) { db =>
        val view       = db.login()
        val ceremony   = db.replacement(view)
        val invocation = db.invocation(view)
        val sessionId  = view.identity.sessionId.get
        val original   = db.hostState
        val changed    = update(original.sessions(sessionId))
        accepted(await(db.persistence.transact(view) { tx =>
          tx.modify(state => state.copy(sessions = state.sessions.updated(sessionId, changed)))
        }))
        // Fresh resolution still succeeds; the captured replacement must reject
        // the changed stamp rather than relying only on host revocation.
        val fresh = accepted(
          await(
            db.persistence.capture(
              BrowserPersistenceConformance.binding,
              view.identity.token,
              activate = false
            )
          )
        )
        fresh.host.map(_.stamp) shouldBe Some(changed.stamp)
        val beforeReplacement = db.hostState
        var calls             = 0
        val result = await(db.persistence.prepareReplacement(ceremony, invocation) { tx =>
          calls += 1
          db.replace(tx, view)
        })
        withClue(change) {
          result shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
          calls shouldBe 0
          db.hostState shouldBe beforeReplacement
          db.committedSessions shouldBe original.sessions.size
          db.hostState.proofs shouldBe original.proofs
          db.hostState.audits shouldBe original.audits
        }
      }
    }
  }

  it should "retain committed disposition and the pending fence after expiry and permit only explicit logout reset" in
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(deliverySeconds = 1, activeViewsPerBinding = 1))) { db =>
      val view     = db.login()
      val ceremony = db.replacement(view)
      accepted(await(db.persistence.prepareReplacement(ceremony, db.invocation(view))(tx => db.replace(tx, view))))
      db.clock.set(db.clock.get().plusSeconds(2))
      await(db.persistence.maintenance())
      await(db.persistence.resources).deliveryMaterials shouldBe 0
      await(db.persistence.resources).ownedViews shouldBe 1
      accepted(
        await(db.persistence.recoverReplacement(db.request(view, ceremony)))
      ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
      await(db.persistence.capture(BrowserPersistenceConformance.binding, None, activate = false)).isLeft shouldBe true
      accepted(await(db.persistence.logout(BrowserPersistenceConformance.binding, view.identity.token)))
      await(db.persistence.resources).ownedViews shouldBe 0
      val anonymous =
        accepted(await(db.persistence.capture(BrowserPersistenceConformance.binding, None, activate = false)))
      anonymous.principal shouldBe None
      val fresh = accepted(
        await(
          db.persistence.claim(
            anonymous,
            Qsid("device", "fresh-login-page"),
            ConnectionId.fromUuid(db.nextId()),
            existingOnly = false
          )
        )
      )
      await(db.persistence.release(view))
      await(db.persistence.authorize(fresh)).isRight shouldBe true
      await(db.persistence.resources).ownedViews shouldBe 1
      await(db.persistence.release(fresh))
      val reclaimed = accepted(
        await(db.persistence.claim(anonymous, view.qsid, ConnectionId.fromUuid(db.nextId()), existingOnly = true))
      )
      reclaimed.epoch shouldBe view.epoch + 1L
      await(db.persistence.release(view))
      await(db.persistence.authorize(reclaimed)).isRight shouldBe true
      accepted(
        await(db.persistence.recoverReplacement(db.request(view, ceremony)))
      ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
      db.committedSessions shouldBe 2
    }

  it should "deny negative recovery after logout or another activation changes the captured slot" in {
    Vector("logout", "activation").foreach { change =>
      Using.resource(new MemoryFixture) { db =>
        val predecessor = db.login()
        val ceremony    = db.replacement(predecessor)
        var callbacks   = 0
        await(db.persistence.prepareReplacement(ceremony, db.invocation(predecessor)) { tx =>
          callbacks += 1
          db.replace(tx, predecessor)
          throw new IllegalStateException("Synthetic rollback before publication")
        }).isLeft shouldBe true
        val request = db.request(predecessor, ceremony)
        accepted(await(db.persistence.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.NotCommitted
        if (change == "logout")
          accepted(await(db.persistence.logout(BrowserPersistenceConformance.binding, predecessor.identity.token)))
        else {
          val ordinary = accepted(await(db.persistence.begin(predecessor, "ordinary-login")))
          val receipt  = accepted(await(db.prepare(ordinary)))
          val token    = await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
          accepted(
            await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
          )
        }
        await(db.persistence.recoverReplacement(request)).isLeft shouldBe true
        callbacks shouldBe 1
      }
    }
  }

  it should "release crashed predecessor ownership at replacement activation without releasing later successor owners" in
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(activeViewsPerBinding = 1))) { db =>
      val predecessor = db.login()
      val ceremony    = db.replacement(predecessor)
      val handoff = accepted(
        await(
          db.persistence.prepareReplacement(ceremony, db.invocation(predecessor))(tx => db.replace(tx, predecessor))
        )
      )
      val token = await(db.persistence.deliver(handoff.completionId, BrowserPersistenceConformance.binding)).get
      await(db.persistence.resources).ownedViews shouldBe 1
      // The old process/page never released its physical owner.
      val active = accepted(
        await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
      )
      await(db.persistence.resources).ownedViews shouldBe 0
      val fresh = accepted(
        await(
          db.persistence
            .claim(active, Qsid("device", "fresh-page"), ConnectionId.fromUuid(db.nextId()), existingOnly = false)
        )
      )
      await(db.persistence.release(predecessor))
      await(db.persistence.authorize(fresh)).isRight shouldBe true
      // An idempotent activation must not clear owners of the already-active
      // successor generation.
      accepted(await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true)))
      await(db.persistence.resources).ownedViews shouldBe 1
      await(db.persistence.authorize(fresh)).isRight shouldBe true
      await(db.persistence.release(fresh))
      val reused = accepted(
        await(db.persistence.claim(active, predecessor.qsid, ConnectionId.fromUuid(db.nextId()), existingOnly = true))
      )
      reused.epoch shouldBe predecessor.epoch + 1L
      await(db.persistence.release(predecessor))
      await(db.persistence.authorize(reused)).isRight shouldBe true
      await(db.persistence.resources).ownedViews shouldBe 1
    }

  it should "leave ordinary activation and logout ownership capacity unchanged" in
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(activeViewsPerBinding = 1))) { db =>
      val predecessor = db.login()
      val ordinary    = accepted(await(db.persistence.begin(predecessor, "ordinary-login")))
      val receipt     = accepted(await(db.prepare(ordinary)))
      val token       = await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
      val active = accepted(
        await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
      )
      await(db.persistence.resources).ownedViews shouldBe 1
      await(
        db.persistence
          .claim(active, Qsid("device", "fresh-page"), ConnectionId.fromUuid(db.nextId()), existingOnly = false)
      ) shouldBe Left(BrowserFailure.Capacity)
      accepted(await(db.persistence.logout(BrowserPersistenceConformance.binding, Some(token.value))))
      await(db.persistence.resources).ownedViews shouldBe 1
      val anonymous =
        accepted(await(db.persistence.capture(BrowserPersistenceConformance.binding, None, activate = false)))
      await(
        db.persistence.claim(
          anonymous,
          Qsid("device", "fresh-login-page"),
          ConnectionId.fromUuid(db.nextId()),
          existingOnly = false
        )
      ) shouldBe Left(BrowserFailure.Capacity)
    }

  it should "free replacement successor ownership when activation wins before logout" in
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(activeViewsPerBinding = 1))) { db =>
      val predecessor = db.login()
      val ceremony    = db.replacement(predecessor)
      val receipt = accepted(
        await(
          db.persistence.prepareReplacement(ceremony, db.invocation(predecessor))(tx => db.replace(tx, predecessor))
        )
      )
      val token = await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
      val active = accepted(
        await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
      )
      val successor = accepted(
        await(db.persistence.claim(active, predecessor.qsid, ConnectionId.fromUuid(db.nextId()), existingOnly = true))
      )
      await(db.persistence.resources).ownedViews shouldBe 1
      // No coordinator release occurs before the provider-level logout.
      accepted(await(db.persistence.logout(BrowserPersistenceConformance.binding, Some(token.value))))
      await(db.persistence.resources).ownedViews shouldBe 0
      val anonymous =
        accepted(await(db.persistence.capture(BrowserPersistenceConformance.binding, None, activate = false)))
      val fresh = accepted(
        await(
          db.persistence.claim(
            anonymous,
            Qsid("device", "fresh-after-successor-logout"),
            ConnectionId.fromUuid(db.nextId()),
            existingOnly = false
          )
        )
      )
      await(db.persistence.release(successor))
      await(db.persistence.authorize(fresh)).isRight shouldBe true
      accepted(
        await(db.persistence.recoverReplacement(db.request(predecessor, ceremony)))
      ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
    }

  it should "fence competing completions and preserve proof metadata across an explicit owner resume" in
    Using.resource(new MemoryFixture) { db =>
      val view      = db.login()
      val ordinary  = accepted(await(db.persistence.begin(view, "other-login")))
      val competing = accepted(await(db.prepare(ordinary)))
      val competingToken =
        await(db.persistence.deliver(competing.completionId, BrowserPersistenceConformance.binding)).get
      val ceremony = db.replacement(view)
      val taken = accepted(
        await(db.persistence.claim(view.identity, view.qsid, ConnectionId.fromUuid(db.nextId()), existingOnly = true))
      )
      await(
        db.persistence.prepareReplacement(ceremony, db.invocation(view))(tx => db.replace(tx, view))
      ).isLeft shouldBe true
      val resumed = accepted(await(db.persistence.resumeReplacement(taken, ceremony.id, ceremony.purpose)))
      resumed.challenge shouldBe ceremony.challenge
      resumed.expiresAt shouldBe ceremony.expiresAt
      accepted(await(db.persistence.prepareReplacement(resumed, db.invocation(taken))(tx => db.replace(tx, taken))))
      await(db.persistence.deliver(competing.completionId, BrowserPersistenceConformance.binding)) shouldBe None
      await(
        db.persistence.capture(BrowserPersistenceConformance.binding, Some(competingToken.value), activate = true)
      ).isLeft shouldBe true
    }

  it should "roll back expiry during the callback and close the retained transaction handle" in
    Using.resource(new MemoryFixture) { db =>
      val view                                                       = db.login()
      val ceremony                                                   = db.replacement(view)
      val original                                                   = db.hostState
      var escaped: InMemoryBrowserPersistence.Transaction[HostState] = null
      await(db.persistence.prepareReplacement(ceremony, db.invocation(view)) { tx =>
        escaped = tx
        val prepared = db.replace(tx, view)
        db.clock.set(ceremony.expiresAt)
        prepared
      }).isLeft shouldBe true
      db.hostState shouldBe original
      intercept[OperationProtocolException](escaped.hostState)
      accepted(
        await(db.persistence.recoverReplacement(db.request(view, ceremony)))
      ) shouldBe BrowserReplacementRecovery.NotCommitted
      // Moving the clock backward cannot revive the admitted ceremony.
      db.clock.set(BrowserPersistenceConformance.start)
      await(db.persistence.resumeReplacement(view, ceremony.id, ceremony.purpose)).isLeft shouldBe true
    }

  it should "commit only one of two distinct replacement ceremonies competing for the same predecessor" in {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    Using.resource(new MemoryFixture) { db =>
      val view          = db.login()
      val original      = db.hostState
      val first         = db.replacement(view)
      val second        = db.replacement(view)
      val firstCall     = db.invocation(view)
      val secondCall    = db.invocation(view)
      val firstCalls    = new AtomicLong()
      val secondCalls   = new AtomicLong()
      val entered       = new CountDownLatch(1)
      val secondStarted = new CountDownLatch(1)
      val release       = new CountDownLatch(1)
      first.id should not be second.id
      val preparing = Future {
        await(db.persistence.prepareReplacement(first, firstCall) { tx =>
          firstCalls.incrementAndGet()
          val prepared = db.replace(tx, view)
          entered.countDown()
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Missing replacement release")
          prepared
        })
      }
      try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        val competing = Future {
          secondStarted.countDown()
          await(db.persistence.prepareReplacement(second, secondCall) { tx =>
            secondCalls.incrementAndGet()
            db.replace(tx, view)
          })
        }
        secondStarted.await(5, TimeUnit.SECONDS) shouldBe true
        competing.isCompleted shouldBe false
        release.countDown()
        val winner = accepted(await(preparing))
        await(competing) shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
        firstCalls.get() shouldBe 1L
        secondCalls.get() shouldBe 0L
        db.committedSessions shouldBe original.sessions.size + 1
        db.hostState.proofs shouldBe original.proofs + 1
        db.hostState.audits shouldBe original.audits + 1
        await(db.persistence.authorize(view)).isLeft shouldBe true
        accepted(await(db.persistence.recoverReplacement(db.request(view, second)))) shouldBe
          BrowserReplacementRecovery.NotCommitted
        val token = await(db.persistence.deliver(winner.completionId, BrowserPersistenceConformance.binding)).get
        val successor = accepted(
          await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
        )
        successor.sessionId should not be view.identity.sessionId
        successor.principal.map(_.subject) shouldBe Some("subject")
        db.hostState.proofs shouldBe original.proofs + 1
        db.hostState.audits shouldBe original.audits + 1
        secondCalls.get() shouldBe 0L
      } finally release.countDown()
    }
  }

  it should "reject exhausted material capacity before host mutation and keep the predecessor current" in
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(deliveryMaterials = 1))) { db =>
      val view      = db.login()
      val competing = accepted(await(db.persistence.begin(view, "another-login")))
      accepted(await(db.prepare(competing)))
      val original = db.hostState
      val ceremony = db.replacement(view)
      var calls    = 0
      await(db.persistence.prepareReplacement(ceremony, db.invocation(view)) { tx =>
        calls += 1
        db.replace(tx, view)
      }) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
      calls shouldBe 0
      db.hostState shouldBe original
      await(db.persistence.authorize(view)).isRight shouldBe true
      accepted(
        await(db.persistence.recoverReplacement(db.request(view, ceremony)))
      ) shouldBe BrowserReplacementRecovery.NotCommitted
    }

  "Memory transaction types" should "reject a transaction from another provider instance" in {
    scala.compiletime.testing.typeChecks("""
      import spoonbill.auth.*
      import scala.concurrent.Future
      import java.time.Instant
      import java.util.UUID
      val host = new InMemoryBrowserPersistence.Host[Int, String] {
        def resolve(state: Int, session: UUID, now: Instant): Option[HostPrincipal[String]] = None
      }
      val first = new InMemoryBrowserPersistence[Future, Int, String](0, host, () => Instant.EPOCH, () => new UUID(0L, 1L))
      val second = new InMemoryBrowserPersistence[Future, Int, String](0, host, () => Instant.EPOCH, () => new UUID(0L, 2L))
      def accept(tx: first.Tx): Unit = ()
      def cross(tx: second.Tx): Unit = accept(tx)
    """) shouldBe false
  }

  "The memory transaction" should "publish host proof, session and audit together and close escaped transaction handles" in
    Using.resource(new MemoryFixture) { db =>
      val view                                                       = db.open()
      val ceremony                                                   = accepted(await(db.persistence.begin(view, "login")))
      var escaped: InMemoryBrowserPersistence.Transaction[HostState] = null
      val receipt = accepted(await(db.persistence.prepare(ceremony) { tx =>
        escaped = tx
        db.stage(tx)
      }))
      db.hostState.proofs shouldBe 1
      db.hostState.audits shouldBe 1
      intercept[OperationProtocolException](escaped.hostState)
      intercept[OperationProtocolException](escaped.modify(identity))
      val token = await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
      accepted(await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true)))
      accepted(await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true)))
      db.hostState.acknowledgments shouldBe 1
    }

  it should "discard host changes on failure while retaining the admitted attempt for negative reconciliation" in
    Using.resource(new MemoryFixture) { db =>
      val view     = db.open()
      val ceremony = accepted(await(db.persistence.begin(view, "login")))
      await(db.prepare(ceremony, fail = true)) shouldBe Left(TransactionFailure.RolledBack)
      db.hostState shouldBe HostState()
      accepted(await(db.persistence.pending(view))).map(_.preparationPending) shouldBe Some(true)
      accepted(await(db.persistence.recover(view, ceremony.id))) shouldBe BrowserRecovery.NotCommitted
      await(db.persistence.pending(view)) shouldBe Right(None)
      await(db.persistence.resources).deliveryMaterials shouldBe 0
    }

  it should "recheck host authority for protected mutation and invalidate a policy changed by that mutation" in
    Using.resource(new MemoryFixture) { db =>
      val view    = db.open()
      val receipt = accepted(await(db.prepare(accepted(await(db.persistence.begin(view, "login"))))))
      val token   = await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
      val identity = accepted(
        await(db.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true))
      )
      val active = accepted(
        await(db.persistence.claim(identity, view.qsid, ConnectionId.fromUuid(new UUID(0L, 2L)), existingOnly = true))
      )
      await(db.persistence.transact(active)(_.modify(_.copy(disabled = true)))).isRight shouldBe true
      await(db.persistence.authorize(active)).isLeft shouldBe true
      await(db.persistence.transact(active)(_.modify(_.copy(disabled = false)))).isLeft shouldBe true
      db.hostState.disabled shouldBe true
    }

  it should "fail closed on restart and reject another issuer's capabilities and receipts" in
    Using.resource(new MemoryFixture) { first =>
      Using.resource(new MemoryFixture) { second =>
        val view    = first.open()
        val receipt = accepted(await(first.prepare(accepted(await(first.persistence.begin(view, "login"))))))
        val token   = await(first.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).get
        second.persistence.accepts(receipt) shouldBe false
        await(second.persistence.authorize(view)).isLeft shouldBe true
        await(
          second.persistence.capture(BrowserPersistenceConformance.binding, Some(token.value), activate = true)
        ).isLeft shouldBe true
        first.close()
        first.persistence.accepts(receipt) shouldBe false
        await(first.persistence.resources) shouldBe InMemoryBrowserPersistence.Resources(0, 0, 0, 0, 0, 0)
      }
    }

  it should "bound replay records and delivery retries without deleting identity history" in
    Using.resource(
      new MemoryFixture(
        BrowserPersistenceLimits(
          retainedCeremonies = 1,
          ceremoniesPerBinding = 1,
          retainedBindings = 1,
          retainedViews = 1,
          deliveryMaterials = 1,
          maxDeliveries = 1
        )
      )
    ) { db =>
      val view     = db.open()
      val ceremony = accepted(await(db.persistence.begin(view, "login")))
      await(db.persistence.begin(view, "login")) shouldBe Left(BrowserFailure.Capacity)
      val receipt = accepted(await(db.prepare(ceremony)))
      await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)).isDefined shouldBe true
      await(db.persistence.deliver(receipt.completionId, BrowserPersistenceConformance.binding)) shouldBe None
      db.clock.set(ceremony.expiresAt)
      await(db.persistence.maintenance())
      await(db.persistence.resources).deliveryMaterials shouldBe 0
      await(db.persistence.resources).ceremonies shouldBe 1
      await(db.persistence.capture("x" * 43, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
    }

  it should "clear a stale restart cookie through idempotent absent-binding logout before a fresh login" in
    Using.resource(new MemoryFixture) { first =>
      val view     = first.open()
      val ceremony = accepted(await(first.persistence.begin(view, "login")))
      val receipt  = accepted(await(first.prepare(ceremony)))
      val binding  = BrowserPersistenceConformance.binding
      val token    = await(first.persistence.deliver(receipt.completionId, binding)).get
      accepted(await(first.persistence.capture(binding, Some(token.value), activate = true)))
      first.close()
      Using.resource(new MemoryFixture) { restarted =>
        await(restarted.persistence.capture(binding, Some(token.value), activate = true)).isLeft shouldBe true
        await(restarted.persistence.resources).bindings shouldBe 0
        await(restarted.persistence.logout(binding, Some(token.value))) shouldBe Right(())
        await(restarted.persistence.logout(binding, Some(token.value))) shouldBe Right(())
        await(restarted.persistence.logout("invalid", Some(token.value))) shouldBe Left(BrowserFailure.Denied)
        await(restarted.persistence.resources) shouldBe InMemoryBrowserPersistence.Resources(0, 0, 0, 0, 0, 0)
        val anonymous = accepted(await(restarted.persistence.capture(binding, None, activate = false)))
        anonymous.principal shouldBe None
        await(restarted.prepare(ceremony)).isLeft shouldBe true
        await(restarted.persistence.resources).sessions shouldBe 0
      }
    }

  it should "wait for its synchronous admitted transaction before closing and release all browser state" in {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    Using.resource(new MemoryFixture) { db =>
      val ceremony = accepted(await(db.persistence.begin(db.open(), "login")))
      val entered  = new CountDownLatch(1)
      val release  = new CountDownLatch(1)
      val preparing = Future {
        await(db.persistence.prepare(ceremony) { tx =>
          val prepared = db.stage(tx)
          entered.countDown()
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Missing release")
          prepared
        })
      }
      try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        val closing = Future(await(db.persistence.close()))
        closing.isCompleted shouldBe false
        release.countDown()
        await(preparing).isRight shouldBe true
        await(closing)
        await(db.persistence.resources) shouldBe InMemoryBrowserPersistence.Resources(0, 0, 0, 0, 0, 0)
      } finally release.countDown()
    }
  }

  it should "reject excess callbacks before monitor entry without consuming their ceremony" in {
    implicit val executionContext: ExecutionContext = ExecutionContext.global
    Using.resource(new MemoryFixture(BrowserPersistenceLimits(pendingJobs = 1))) { db =>
      val view    = db.open()
      val first   = accepted(await(db.persistence.begin(view, "login")))
      val second  = accepted(await(db.persistence.begin(view, "login")))
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val preparing = Future {
        await(db.persistence.prepare(first) { tx =>
          val prepared = db.stage(tx)
          entered.countDown()
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Missing release")
          prepared
        })
      }
      try {
        entered.await(5, TimeUnit.SECONDS) shouldBe true
        await(db.prepare(second)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
        release.countDown()
        await(preparing).isRight shouldBe true
        await(db.prepare(second)).isRight shouldBe true
        db.committedSessions shouldBe 2
      } finally release.countDown()
    }
  }
}
