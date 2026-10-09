package spoonbill.internal

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.action.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.sensitive.*
import spoonbill.server.SessionAccessDenied

class SensitiveDisclosureScopeSpec extends AsyncFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private def accepted[A](result: Either[SensitiveError, A]): A = result.fold(error => fail(error.toString), identity)
  private def payload = accepted(SensitivePayload.textList(Vector("synthetic-code")))
  private val region = accepted(RegionId.parse("recovery"))
  private val purpose = accepted(Purpose.parse("mfa.recovery"))
  private val name = ActionName.parse("mfa.disclose").fold(error => fail(error.toString), identity)
  private val owner = new SensitiveDisclosure.Owner

  private class Deadlines extends Frontend.RpcDeadlineScheduler {
    val expirations = new ConcurrentLinkedQueue[() => Unit]()
    val cancellations = new AtomicInteger(0)
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = {
      delay shouldBe 10.seconds
      expirations.add(expire)
      () => { cancellations.incrementAndGet(); () }
    }
    def expireAll(): Unit = expirations.iterator().asScala.toList.foreach(_.apply())
  }

  private class StalledAction {
    val deadlines = new Deadlines
    val scope = new SensitiveDisclosureScope(deadlines)
    val binding = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), ConnectionId.fromUuid(UUID.randomUUID()), Some(scope))
    val policyEntered = Promise[Unit]()
    val policyResult = Promise[AccessDecision]()
    val cachedHandlerResult = Promise[UiOutcome[Int]]()
    private val checks = new AtomicInteger(0)
    val action = new Actions[Future, Int, Unit].public(name, InputSchema.empty, PublicPolicy[Future, Unit] { _ =>
      if (checks.incrementAndGet() == 1) Future.successful(AccessDecision.Allowed)
      else { policyEntered.trySuccess(()); policyResult.future }
    }) { (_, context) =>
      val result = context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => StateUpdate.unchanged)
      cachedHandlerResult.trySuccess(result)
      cachedHandlerResult.future
    }
    val running = ActionDispatcher.public(action, Vector.empty, binding)
    def disclosure(result: UiOutcome[Int]): SensitiveDisclosure = result match {
      case UiOutcome.PresentSensitive(_, _, value, _, _) => value
      case _ => fail("Missing disclosure")
    }
  }

  "Pending sensitive outcomes" should "expire even while the final policy never completes" in {
    val fixture = new StalledAction
    for {
      _ <- fixture.policyEntered.future
      cached <- fixture.cachedHandlerResult.future
      disclosure = fixture.disclosure(cached)
      _ = disclosure.hasPayload shouldBe true
      _ = fixture.deadlines.expireAll()
      _ = disclosure.hasPayload shouldBe false
      _ = fixture.scope.pendingCount shouldBe 0
      _ = fixture.running.isCompleted shouldBe false
      _ = fixture.policyResult.success(AccessDecision.Allowed)
      _ <- fixture.running
      repeated <- disclosure.consume[Future](fixture.binding.sensitiveOwner)(_ => Future.failed(new AssertionError("Expired payload replayed")))
    } yield repeated shouldBe DisclosureOutcome.NotSent
  }

  it should "discard cached results on connection close while final policy is still pending" in {
    val fixture = new StalledAction
    for {
      _ <- fixture.policyEntered.future
      cached <- fixture.cachedHandlerResult.future
      _ = fixture.scope.close()
      _ = fixture.scope.close()
      _ = fixture.disclosure(cached).hasPayload shouldBe false
      _ = fixture.scope.pendingCount shouldBe 0
      _ = fixture.policyResult.success(AccessDecision.Allowed)
      _ <- fixture.running
    } yield fixture.deadlines.cancellations.get() shouldBe 1
  }

  it should "bound pending holders and free a slot at handoff before browser acknowledgment" in {
    val deadlines = new Deadlines
    val scope = new SensitiveDisclosureScope(deadlines)
    val holders = Vector.fill(4)(scope.create(payload, owner))
    intercept[SessionAccessDenied](scope.create(payload, owner))
    val entered = Promise[Unit]()
    val delivery = Promise[DisclosureOutcome]()
    val transferring = holders.head.consume[Future](owner) { _ => entered.trySuccess(()); delivery.future }
    for {
      _ <- entered.future
      _ = scope.pendingCount shouldBe 3
      next = scope.create(payload, owner)
      _ = scope.pendingCount shouldBe 4
      _ = scope.close()
      _ = delivery.success(DisclosureOutcome.BrowserProcessed)
      result <- transferring
    } yield {
      result shouldBe DisclosureOutcome.BrowserProcessed
      holders.foreach(_.hasPayload shouldBe false)
      next.hasPayload shouldBe false
      scope.pendingCount shouldBe 0
    }
  }

  it should "cancel a timer installed after a racing connection close" in {
    val scopeRef = new AtomicReference(Option.empty[SensitiveDisclosureScope])
    val cancelled = new AtomicInteger(0)
    val deadlines = new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = {
        scopeRef.get().foreach(_.close())
        () => { cancelled.incrementAndGet(); () }
      }
    }
    val scope = new SensitiveDisclosureScope(deadlines)
    scopeRef.set(Some(scope))
    val disclosure = scope.create(payload, owner)
    disclosure.hasPayload shouldBe false
    scope.pendingCount shouldBe 0
    cancelled.get() shouldBe 1
    intercept[SessionAccessDenied](scope.create(payload, owner))
    succeed
  }

  it should "clean up registration when the scheduler fails" in {
    val deadlines = new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = throw new IllegalStateException("Synthetic scheduler failure")
    }
    val scope = new SensitiveDisclosureScope(deadlines)
    intercept[IllegalStateException](scope.create(payload, owner))
    scope.pendingCount shouldBe 0
    succeed
  }

  it should "reject sensitive results from a context without a connection-owned scope" in {
    val binding = new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), ConnectionId.fromUuid(UUID.randomUUID()))
    val action = new Actions[Future, Int, Unit].public(name, InputSchema.empty, PublicPolicy.allow[Future, Unit]) { (_, context) =>
      Future.successful(context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => StateUpdate.unchanged))
    }
    ActionDispatcher.public(action, Vector.empty, binding).failed.map(_ shouldBe a[SessionAccessDenied])
  }
}
