package spoonbill.auth

import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.{OperationError, OperationProtocolException, TransactionFailure}
import spoonbill.server.BrowserSessionToken

class InMemoryBrowserPersistenceSpec extends BrowserPersistenceConformance {
  private case class HostState(
    sessions: Map[UUID, HostPrincipal[String]] = Map.empty,
    proofs: Int = 0,
    audits: Int = 0,
    acknowledgments: Int = 0,
    disabled: Boolean = false
  )
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private class MemoryFixture(limits: BrowserPersistenceLimits = BrowserPersistenceLimits())
      extends BrowserPersistenceConformance.Fixture {
    val clock          = new AtomicReference(BrowserPersistenceConformance.start)
    private val ids    = new AtomicLong()
    def nextId(): UUID = new UUID(0L, ids.incrementAndGet())
    val persistence: InMemoryBrowserPersistence[Future, HostState, String] =
      new InMemoryBrowserPersistence[Future, HostState, String](
        HostState(),
        new InMemoryBrowserPersistence.Host[HostState, String] {
          def resolve(state: HostState, id: UUID, now: Instant): Option[HostPrincipal[String]] =
            if (state.disabled) None else state.sessions.get(id)
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
  }
  protected def fixture(): BrowserPersistenceConformance.Fixture = new MemoryFixture

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
