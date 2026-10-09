package spoonbill.action

import java.util.UUID
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.internal.{Frontend, SensitiveDisclosureScope}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.sensitive.*
import spoonbill.server.SessionAccessDenied

class SensitiveInvocationOwnershipSpec extends AsyncFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private def accepted[A](result: Either[SensitiveError, A]): A = result.fold(error => fail(error.toString), identity)

  "An invocation-owned disclosure" should "reject cached-result reuse on another connection or a later invocation" in {
    val scheduler = new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = () => ()
    }
    val firstScope = new SensitiveDisclosureScope(scheduler)
    val otherScope = new SensitiveDisclosureScope(scheduler)
    val first = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), ConnectionId.fromUuid(UUID.randomUUID()), Some(firstScope))
    val later = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), first.connectionId, Some(firstScope))
    val other = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), ConnectionId.fromUuid(UUID.randomUUID()), Some(otherScope))
    // Copying public IDs is not possession of the original invocation token.
    val copiedIds = new InvocationBinding(first.invocationId, first.connectionId, Some(firstScope))
    val region = accepted(RegionId.parse("recovery"))
    val purpose = accepted(Purpose.parse("mfa.recovery"))
    val cached = new AtomicReference(Option.empty[UiOutcome[Int]])
    val published = new AtomicInteger(0)
    val action = new Actions[Future, Int, Unit].public(
      ActionName.parse("mfa.disclose").fold(error => fail(error.toString), identity),
      InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, context) =>
      val result = cached.get().getOrElse {
        val created = context.presentSensitive[Int](region, purpose,
          accepted(SensitivePayload.textList(Vector("synthetic-first-invocation-code"))), 1.minute)(_ => StateUpdate.unchanged)
        cached.set(Some(created))
        created
      }
      Future.successful(result)
    }
    for {
      created <- ActionDispatcher.public(action, Vector.empty, first)
      foreign <- Future.sequence(Vector(later, other, copiedIds).map(binding => ActionDispatcher.public(action, Vector.empty, binding)))
      disclosure = created match {
        case InvocationResult.Completed(UiOutcome.PresentSensitive(_, _, value, _, _)) => value
        case _ => fail("Original invocation did not produce its disclosure")
      }
      _ = foreign.foreach(_ shouldBe InvocationResult.OutputSuppressed(AccessDenied.StaleAuthority))
      _ = disclosure.hasPayload shouldBe true
      rejected <- disclosure.consume[Future](later.sensitiveOwner) { _ =>
        published.incrementAndGet(); Future.successful(DisclosureOutcome.BrowserProcessed)
      }.failed
      _ = disclosure.hasPayload shouldBe true
      original <- disclosure.consume[Future](first.sensitiveOwner) { _ =>
        published.incrementAndGet(); Future.successful(DisclosureOutcome.BrowserProcessed)
      }
      _ = firstScope.close()
      _ = otherScope.close()
    } yield {
      rejected shouldBe a[SessionAccessDenied]
      original shouldBe DisclosureOutcome.BrowserProcessed
      published.get() shouldBe 1
      disclosure.hasPayload shouldBe false
    }
  }
}
