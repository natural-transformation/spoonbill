package spoonbill.action

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

class ReplacementDispatchSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext            = ExecutionContext.global
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)
  private def id(): UUID                    = UUID.randomUUID()
  private def name                          = ActionName.parse("replace.factor").toOption.get
  private class Fixture {
    val issuer     = new BrowserCapabilityScope[String]
    val connection = ConnectionId.fromUuid(id())
    val binding    = new InvocationBinding(InvocationId.fromUuid(id()), connection)
    val view = issuer.view(
      issuer.identity(
        "binding",
        Some("credential"),
        1L,
        Some(HostPrincipal("user", "subject", 1L, 1L, Instant.MAX)),
        Some(id())
      ),
      Qsid("device", "view"),
      connection,
      id(),
      1L
    )
    val current = new AtomicBoolean(true)
    def handoff(owner: InvocationBinding): CommittedReplacement = {
      val ceremony   = issuer.replacementCeremony(view, CeremonyId(id()), "replace", Instant.MAX, None)
      val invocation = BrowserReplacementInvocation.capture(view, owner)
      issuer.admitReplacement(ceremony, invocation) shouldBe true
      issuer.committedReplacement(ceremony, invocation, id())
    }
    val authority = new SessionAuthority[Future, String] {
      def resolve(binding: InvocationBinding) = Future.successful(Right("user"))
      def revalidate(binding: InvocationBinding, principal: String) = Future.successful(
        if (current.get()) AccessDecision.Allowed else AccessDecision.Denied(AccessDenied.StaleAuthority)
      )
      override private[spoonbill] val replacement = Some((owner: InvocationBinding, value: CommittedReplacement) =>
        Future.successful(
          if (issuer.owns(value) && (value.invocationKey eq owner.replacementIdentity))
            AccessDecision.Allowed
          else AccessDecision.Denied(AccessDenied.StaleAuthority)
        )
      )
    }
  }

  "Replacement dispatch" should "admit its invocation handoff after predecessor revocation but still suppress ordinary output" in {
    val f       = new Fixture
    val actions = new Actions[Future, Int, String]
    val action = actions.authenticated(
      name,
      InputSchema.empty,
      ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, context) =>
      val handoff = f.handoff(context.invocationBinding)
      f.current.set(false)
      Future.successful(UiOutcome.CommitReplacement(handoff))
    }
    await(ActionDispatcher.authenticated(action, Vector.empty, f.binding, f.authority)) match {
      case InvocationResult.Completed(UiOutcome.CommitReplacement(_)) => succeed
      case other                                                      => fail(s"Unexpected replacement result: $other")
    }
    f.current.set(true)
    val ordinary = actions.authenticated(
      name,
      InputSchema.empty,
      ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, _) =>
      f.current.set(false)
      Future.successful(UiOutcome.RecoverReplacement(CeremonyId(id())))
    }
    await(ActionDispatcher.authenticated(ordinary, Vector.empty, f.binding, f.authority)) shouldBe
      InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority)
  }

  it should "reject a copied invocation and every public-action replacement outcome" in {
    val f              = new Fixture
    val copied         = new InvocationBinding(f.binding.invocationId, f.binding.connectionId)
    val foreignHandoff = f.handoff(copied)
    val actions        = new Actions[Future, Int, String]
    val authenticated = actions.authenticated(
      name,
      InputSchema.empty,
      ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, _) =>
      Future.successful(UiOutcome.CommitReplacement(foreignHandoff))
    }
    await(ActionDispatcher.authenticated(authenticated, Vector.empty, f.binding, f.authority)) shouldBe
      InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority)
    val public = actions.public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, _) =>
      Future.successful(UiOutcome.CommitReplacement(f.handoff(f.binding)))
    }
    await(ActionDispatcher.public(public, Vector.empty, f.binding)) shouldBe
      InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority)
  }

  it should "leave rolled-back predecessor authority valid and never replay an uncertain observer" in {
    val f     = new Fixture
    val calls = new java.util.concurrent.atomic.AtomicInteger()
    val action = new Actions[Future, Int, String].authenticated(
      name,
      InputSchema.empty,
      ActionPolicy[Future, Unit, String]((_, _) => Future.successful(AccessDecision.Allowed))
    ) { (_, context) =>
      context.invocationBinding.replacementAttempted.set(true)
      calls.incrementAndGet()
      // The observer reports uncertainty but the transaction did not commit.
      // Ordinary post-validation still succeeds; the marker grants no bypass.
      Future.successful(UiOutcome.RecoverReplacement(CeremonyId(id())))
    }
    await(ActionDispatcher.authenticated(action, Vector.empty, f.binding, f.authority)) match {
      case InvocationResult.Completed(UiOutcome.RecoverReplacement(_)) => succeed
      case other                                                       => fail(s"Unexpected unresolved result: $other")
    }
    calls.get() shouldBe 1
    await(f.authority.revalidate(f.binding, "user")) shouldBe AccessDecision.Allowed
  }
}
