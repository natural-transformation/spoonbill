package spoonbill.auth

import java.util.UUID
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.action.InvocationBinding
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.security.transaction.TransactionFailure

object BrowserReplacementConformance {
  trait Fixture extends BrowserPersistenceConformance.Fixture {
    def replacementPersistence: BrowserReplacementSupport[Future, String]
    def prepareReplacement(
      ceremony: BrowserReplacementCeremony,
      invocation: BrowserReplacementInvocation,
      predecessorSessionId: UUID,
      fail: Boolean = false
    ): Future[Either[TransactionFailure, CommittedReplacement]]
    def successorCompletion(view: BrowserView[String]): Future[Either[TransactionFailure, VerifiedSuccessorCompletion]]
  }
}

/**
 * Identical replacement laws run against staged-memory and real PostgreSQL
 * fixtures. No fixture can infer replacement from an ordinary purpose string.
 */
trait BrowserReplacementConformance extends BrowserPersistenceConformance {
  import BrowserPersistenceConformance.binding
  import BrowserReplacementConformance.Fixture
  override protected def fixture(): Fixture
  private given ExecutionContext            = ExecutionContext.parasitic
  private given Effect[Future]              = new Effect.FutureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 10.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected replacement rejection: $error"), identity)
  private def uuid(n: Long): UUID               = new UUID(7L, n)
  private def connection(n: Long): ConnectionId = ConnectionId.fromUuid(uuid(n))
  private val viewId                            = Qsid("replacement-device", "replacement-view")
  private def invocation(view: BrowserView[String], id: Long = 100L): BrowserReplacementInvocation =
    BrowserReplacementInvocation.capture(
      view,
      new InvocationBinding(InvocationId.fromUuid(uuid(id)), view.connectionId)
    )

  private def login(db: Fixture): (BrowserView[String], String) = {
    val identity  = accepted(await(db.persistence.capture(binding, None, activate = false)))
    val anonymous = accepted(await(db.persistence.claim(identity, viewId, connection(1), existingOnly = false)))
    val receipt   = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
    val token     = await(db.persistence.deliver(receipt.completionId, binding)).get.value
    val active    = accepted(await(db.persistence.capture(binding, Some(token), activate = true)))
    accepted(await(db.persistence.claim(active, viewId, connection(2), existingOnly = true))) -> token
  }

  private def prepare(
    db: Fixture,
    view: BrowserView[String]
  ): (BrowserReplacementCeremony, BrowserReplacementInvocation, CommittedReplacement) = {
    val ceremony = accepted(await(db.replacementPersistence.beginReplacement(view, "regenerate-recovery")))
    val call     = invocation(view)
    val handoff  = accepted(await(db.prepareReplacement(ceremony, call, view.identity.sessionId.get)))
    (ceremony, call, handoff)
  }

  "An authenticated replacement provider" should "commit one successor while immediately fencing all ordinary predecessor authority" in
    Using.resource(fixture()) { db =>
      val (view, oldToken)          = login(db)
      val (ceremony, call, handoff) = prepare(db, view)
      db.committedSessions shouldBe 2
      await(db.persistence.authorize(view)).isLeft shouldBe true
      await(db.persistence.capture(binding, Some(oldToken), activate = true)).isLeft shouldBe true
      await(db.persistence.capture(binding, None, activate = false)).isLeft shouldBe true
      await(db.persistence.admitOutput(view, "ordinary output")).isLeft shouldBe true
      await(db.prepareReplacement(ceremony, call, view.identity.sessionId.get)).isLeft shouldBe true
      await(db.replacementPersistence.admitReplacementCompletion(handoff, invocation(view))).isLeft shouldBe true
      await(db.replacementPersistence.admitReplacementCompletion(handoff, call)).isRight shouldBe true
      await(db.replacementPersistence.admitReplacementCompletion(handoff, call)).isLeft shouldBe true
      val token = await(db.persistence.deliver(handoff.completionId, binding)).get.value
      await(db.persistence.capture(binding, Some(token), activate = false)).isLeft shouldBe true
      val active = accepted(await(db.persistence.capture(binding, Some(token), activate = true)))
      active.principal.map(_.subject) shouldBe Some("subject")
      active.sessionId should not be view.identity.sessionId
      accepted(await(db.persistence.capture(binding, Some(token), activate = true))).sameAuthority(active) shouldBe true
      val successor  = accepted(await(db.persistence.claim(active, viewId, connection(3), existingOnly = true)))
      val completion = accepted(await(db.successorCompletion(successor)))
      completion.successorSessionId shouldBe active.sessionId.get
      completion.hostCompletionId should not be handoff.completionId
      await(db.successorCompletion(view)).isLeft shouldBe true
      await(db.persistence.release(view))
      await(db.persistence.authorize(successor)).isRight shouldBe true
      db.committedSessions shouldBe 2
    }

  it should "recover and consume delivery only for the original browser and the exact HTTP request" in Using.resource(
    fixture()
  ) { db =>
    val (view, oldToken)       = login(db)
    val (ceremony, _, handoff) = prepare(db, view)
    val request                = new BrowserReplacementRequest(binding, Some(oldToken), Some(ceremony.id))
    await(
      db.replacementPersistence.recoverReplacement(new BrowserReplacementRequest("x" * 43, None, Some(ceremony.id)))
    ).isLeft shouldBe true
    await(
      db.replacementPersistence.recoverReplacement(
        new BrowserReplacementRequest(binding, Some("x" * 43), Some(ceremony.id))
      )
    ).isLeft shouldBe true
    val delivery = accepted(await(db.replacementPersistence.recoverReplacement(request))) match {
      case BrowserReplacementRecovery.Deliver(value) => value
      case other                                     => fail(s"Expected original replacement delivery: $other")
    }
    delivery.completionId shouldBe handoff.completionId
    val copied = new BrowserReplacementRequest(binding, Some(oldToken), Some(ceremony.id))
    await(db.replacementPersistence.deliverReplacement(copied, delivery)).isLeft shouldBe true
    val token = accepted(await(db.replacementPersistence.deliverReplacement(request, delivery)))
    await(db.replacementPersistence.deliverReplacement(request, delivery)).isLeft shouldBe true
    accepted(
      await(db.persistence.capture(binding, Some(token.value), activate = true))
    ).principal.nonEmpty shouldBe true
    db.committedSessions shouldBe 2
  }

  it should "bootstrap a held successor after the final delivery without issuing another credential" in Using.resource(
    fixture()
  ) { db =>
    val (view, oldToken)       = login(db)
    val (ceremony, _, handoff) = prepare(db, view)
    val token                  = await(db.persistence.deliver(handoff.completionId, binding)).get.value
    var deliveries             = 1
    while (deliveries <= 16 && await(db.persistence.deliver(handoff.completionId, binding)).nonEmpty) deliveries += 1
    deliveries should be <= 16
    val held = new BrowserReplacementRequest(binding, Some(token), None)
    accepted(await(db.replacementPersistence.replacementPending(held))) shouldBe true
    accepted(
      await(db.replacementPersistence.recoverReplacement(held))
    ) shouldBe BrowserReplacementRecovery.ActivateExistingCookie
    await(db.persistence.deliver(handoff.completionId, binding)) shouldBe None
    accepted(
      await(
        db.replacementPersistence.recoverReplacement(
          new BrowserReplacementRequest(binding, Some(oldToken), Some(ceremony.id))
        )
      )
    ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
    val active = accepted(await(db.persistence.capture(binding, Some(token), activate = true)))
    active.principal.nonEmpty shouldBe true
    accepted(await(db.replacementPersistence.replacementPending(held))) shouldBe false
    db.committedSessions shouldBe 2
  }

  it should "discover consecutive replacements without a handle while retaining the same committed successor" in Using
    .resource(fixture()) { db =>
      val (initial, initialToken) = login(db)
      var view                    = initial
      var token                   = initialToken
      for (index <- 1 to 3) {
        val predecessor = view
        val ceremony    = accepted(await(db.replacementPersistence.beginReplacement(view, "replace-factor")))
        val handoff = accepted(
          await(db.prepareReplacement(ceremony, invocation(view, 100L + index), view.identity.sessionId.get))
        )
        val request = new BrowserReplacementRequest(binding, Some(token), None)
        // Discovery must remain read-only, and prior activated lineage must not
        // turn the current reservation into an ambiguous recovery request.
        accepted(await(db.replacementPersistence.replacementPending(request))) shouldBe true
        accepted(await(db.replacementPersistence.replacementPending(request))) shouldBe true
        db.committedSessions shouldBe index + 1
        await(db.persistence.authorize(predecessor)).isLeft shouldBe true
        val delivery = accepted(await(db.replacementPersistence.recoverReplacement(request))) match {
          case BrowserReplacementRecovery.Deliver(value) => value
          case other                                     => fail(s"Expected chained replacement delivery: $other")
        }
        delivery.completionId shouldBe handoff.completionId
        token = accepted(await(db.replacementPersistence.deliverReplacement(request, delivery))).value
        val held = new BrowserReplacementRequest(binding, Some(token), None)
        if (index == 3) {
          var deliveries = 1
          while (deliveries <= 16 && await(db.persistence.deliver(handoff.completionId, binding)).nonEmpty)
            deliveries += 1
          deliveries should be <= 16
          await(db.persistence.deliver(handoff.completionId, binding)) shouldBe None
          accepted(await(db.replacementPersistence.recoverReplacement(request))) shouldBe
            BrowserReplacementRecovery.CommittedUnavailable
        }
        accepted(await(db.replacementPersistence.replacementPending(held))) shouldBe true
        accepted(await(db.replacementPersistence.recoverReplacement(held))) shouldBe
          BrowserReplacementRecovery.ActivateExistingCookie
        val active = accepted(await(db.persistence.capture(binding, Some(token), activate = true)))
        active.sessionId should not be predecessor.identity.sessionId
        view = accepted(await(db.persistence.claim(active, viewId, connection(10L + index), existingOnly = true)))
        accepted(await(db.successorCompletion(view))).successorSessionId shouldBe active.sessionId.get
        accepted(await(db.replacementPersistence.replacementPending(held))) shouldBe false
        accepted(await(db.persistence.capture(binding, Some(token), activate = true)))
          .sameAuthority(active) shouldBe true
        await(db.persistence.release(predecessor))
        await(db.persistence.authorize(view)).isRight shouldBe true
        db.committedSessions shouldBe index + 1
      }
    }

  it should "retain committed disposition after expiry or logout without reviving old prepared completions" in Using
    .resource(fixture()) { db =>
      val (view, oldToken) = login(db)
      val competing        = accepted(await(db.prepare(accepted(await(db.persistence.begin(view, "ordinary-login"))))))
      val (_, _, handoff)  = prepare(db, view)
      await(db.persistence.deliver(competing.completionId, binding)) shouldBe None
      val request = new BrowserReplacementRequest(binding, Some(oldToken), Some(handoff.ceremonyId))
      db.clock.set(handoff.expiresAt)
      await(db.persistence.maintenance())
      accepted(
        await(db.replacementPersistence.recoverReplacement(request))
      ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
      await(db.persistence.deliver(competing.completionId, binding)) shouldBe None
      await(db.persistence.capture(binding, None, activate = false)).isLeft shouldBe true
      accepted(await(db.persistence.logout(binding, Some(oldToken))))
      accepted(
        await(db.replacementPersistence.recoverReplacement(request))
      ) shouldBe BrowserReplacementRecovery.CommittedUnavailable
      await(db.persistence.capture(binding, Some(oldToken), activate = true)).isLeft shouldBe true
      accepted(await(db.persistence.capture(binding, None, activate = false))).principal shouldBe None
    }

  it should "roll back replacement effects and reconcile failure without replaying its proof" in Using.resource(
    fixture()
  ) { db =>
    val (view, oldToken) = login(db)
    val ceremony         = accepted(await(db.replacementPersistence.beginReplacement(view, "replace-factor")))
    val call             = invocation(view)
    await(db.prepareReplacement(ceremony, call, view.identity.sessionId.get, fail = true)).isLeft shouldBe true
    db.committedSessions shouldBe 1
    await(db.persistence.authorize(view)).isRight shouldBe true
    val request = new BrowserReplacementRequest(binding, Some(oldToken), Some(ceremony.id))
    accepted(
      await(db.replacementPersistence.recoverReplacement(request))
    ) shouldBe BrowserReplacementRecovery.NotCommitted
    await(db.prepareReplacement(ceremony, call, view.identity.sessionId.get)).isLeft shouldBe true
    db.committedSessions shouldBe 1
  }

  it should "resume an uncommitted ceremony only under the current physical owner without refreshing proof facts" in Using
    .resource(fixture()) { db =>
      val (view, _) = login(db)
      val ceremony  = accepted(await(db.replacementPersistence.beginReplacement(view, "replace-factor")))
      val challenge = BrowserChallenge("subject", 7L, 1L, "original-proof")
      val bound     = accepted(await(db.replacementPersistence.bindReplacementChallenge(ceremony, challenge)))
      val next      = accepted(await(db.persistence.claim(view.identity, viewId, connection(3), existingOnly = true)))
      val resumed   = accepted(await(db.replacementPersistence.resumeReplacement(next, ceremony.id, ceremony.purpose)))
      resumed.expiresAt shouldBe bound.expiresAt
      resumed.challenge shouldBe bound.challenge
      await(db.prepareReplacement(bound, invocation(view), view.identity.sessionId.get)).isLeft shouldBe true
      await(db.prepareReplacement(resumed, invocation(next), next.identity.sessionId.get)).isRight shouldBe true
      db.committedSessions shouldBe 2
    }
}
