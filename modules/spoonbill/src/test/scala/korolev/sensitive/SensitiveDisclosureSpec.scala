package spoonbill.sensitive

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}

class SensitiveDisclosureSpec extends AsyncFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private def accepted[A](result: Either[SensitiveError, A]): A = result.fold(error => fail(error.toString), identity)
  private val region = accepted(RegionId.parse("recovery"))
  private val purpose = accepted(Purpose.parse("mfa.recovery"))
  private val binding = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), ConnectionId.fromUuid(UUID.randomUUID()))
  private val name = ActionName.parse("mfa.disclose").fold(error => fail(error.toString), identity)

  private def outcome: UiOutcome[Int] = UiOutcome.PresentSensitive(region, purpose,
    SensitiveDisclosure.once(accepted(SensitivePayload.textList(Vector("synthetic-code"))), binding.sensitiveOwner),
    1.minute, _ => StateUpdate.unchanged)
  private def holder(value: UiOutcome[Int]): SensitiveDisclosure = value match {
    case UiOutcome.PresentSensitive(_, _, disclosure, _, _) => disclosure
    case _ => fail("Expected sensitive disclosure")
  }

  "Sensitive disclosure handoff" should "empty a cached action result before awaiting browser processing" in {
    val cached = Future.successful(outcome)
    val entered = Promise[Unit]()
    val acknowledged = Promise[DisclosureOutcome]()
    for {
      value <- cached
      disclosure = holder(value)
      first = disclosure.consume[Future](binding.sensitiveOwner) { _ => entered.trySuccess(()); acknowledged.future }
      _ <- entered.future
      _ = disclosure.hasPayload shouldBe false
      repeated <- disclosure.consume[Future](binding.sensitiveOwner)(_ => Future.failed(new AssertionError("Repeated handoff replayed payload")))
      _ = acknowledged.success(DisclosureOutcome.BrowserProcessed)
      processed <- first
      retained <- cached
    } yield {
      repeated shouldBe DisclosureOutcome.NotSent
      processed shouldBe DisclosureOutcome.BrowserProcessed
      holder(retained).hasPayload shouldBe false
    }
  }

  it should "transfer to exactly one concurrent publisher" in {
    val disclosure = holder(outcome)
    val publishers = new AtomicInteger(0)
    def deliver = disclosure.consume[Future](binding.sensitiveOwner) { _ =>
      publishers.incrementAndGet()
      Future.successful(DisclosureOutcome.BrowserProcessed)
    }
    Future.sequence(Vector(deliver, deliver)).map { results =>
      results.count(_ == DisclosureOutcome.BrowserProcessed) shouldBe 1
      results.count(_ == DisclosureOutcome.NotSent) shouldBe 1
      publishers.get() shouldBe 1
      disclosure.hasPayload shouldBe false
    }
  }

  it should "not retain or retry a payload when the receiving resource fails" in {
    val disclosure = holder(outcome)
    val failure = new IllegalStateException("Synthetic delivery failure")
    for {
      observed <- disclosure.consume[Future](binding.sensitiveOwner)(_ => throw failure).failed
      repeated <- disclosure.consume[Future](binding.sensitiveOwner)(_ => Future.failed(new AssertionError("Retried failed delivery")))
    } yield {
      observed shouldBe failure
      repeated shouldBe DisclosureOutcome.NotSent
      disclosure.hasPayload shouldBe false
    }
  }

  "Sensitive action dispatch" should "discard cached handler output when its final public policy denies" in {
    val checks = new AtomicInteger(0)
    val cached = Future.successful(outcome)
    val action = new Actions[Future, Int, Unit].public(name, InputSchema.empty,
      PublicPolicy[Future, Unit](_ => Future.successful(if (checks.incrementAndGet() == 1) AccessDecision.Allowed
        else AccessDecision.Denied(AccessDenied.Forbidden))))((_, _) => cached)
    for {
      result <- ActionDispatcher.public(action, Vector.empty, binding)
      retained <- cached
    } yield {
      result shouldBe InvocationResult.OutputSuppressed(AccessDenied.Forbidden)
      holder(retained).hasPayload shouldBe false
    }
  }

  it should "discard cached handler output when its final public policy throws" in {
    val checks = new AtomicInteger(0)
    val cached = Future.successful(outcome)
    val failure = new IllegalStateException("Synthetic policy failure")
    val action = new Actions[Future, Int, Unit].public(name, InputSchema.empty,
      PublicPolicy[Future, Unit](_ => if (checks.incrementAndGet() == 1) Future.successful(AccessDecision.Allowed)
        else throw failure))((_, _) => cached)
    for {
      observed <- ActionDispatcher.public(action, Vector.empty, binding).failed
      retained <- cached
    } yield {
      observed shouldBe failure
      holder(retained).hasPayload shouldBe false
    }
  }

  it should "discard cached handler output when final authenticated authority fails" in {
    val checks = new AtomicInteger(0)
    val cached = Future.successful(outcome)
    val failure = new IllegalStateException("Synthetic authority failure")
    val authority = new SessionAuthority[Future, Unit] {
      def resolve(binding: InvocationBinding) = Future.successful(Right(()))
      def revalidate(binding: InvocationBinding, principal: Unit) =
        if (checks.incrementAndGet() == 1) Future.successful(AccessDecision.Allowed) else Future.failed(failure)
    }
    val action = new Actions[Future, Int, Unit].authenticated(name, InputSchema.empty,
      ActionPolicy[Future, Unit, Unit]((_, _) => Future.successful(AccessDecision.Allowed)))((_, _) => cached)
    for {
      observed <- ActionDispatcher.authenticated(action, Vector.empty, binding, authority).failed
      retained <- cached
    } yield {
      observed shouldBe failure
      holder(retained).hasPayload shouldBe false
    }
  }
}
