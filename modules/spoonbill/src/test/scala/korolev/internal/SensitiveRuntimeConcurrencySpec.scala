package spoonbill.internal

import java.time.Instant
import java.util.UUID
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.util.Success
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.sensitive.*

class SensitiveRuntimeConcurrencySpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = new Effect.FutureEffect
  private def accepted[A](value: Either[SensitiveError, A]): A = value.fold(error => fail(error.toString), identity)

  private def withoutTimers = new Frontend.RpcDeadlineScheduler {
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = () => ()
  }

  "Sensitive acknowledgement" should "allow another thread to clear and close while an inline continuation is blocked" in {
    val executor = Executors.newFixedThreadPool(2)
    val continuationEntered = new CountDownLatch(1)
    val releaseContinuation = new CountDownLatch(1)
    val acknowledgementDone = new CountDownLatch(1)
    val cleanupDone = new CountDownLatch(1)
    val continuationDone = new CountDownLatch(1)
    val acknowledgementResult = new AtomicReference(Option.empty[Either[Throwable, Unit]])
    val cleanupResult = new AtomicReference(Option.empty[Either[Throwable, Unit]])
    val continuationResult = new AtomicReference(Option.empty[Either[Throwable, Unit]])
    val firstId = new AtomicReference(Option.empty[PresentationId])
    val secondId = new AtomicReference(Option.empty[PresentationId])
    val firstRegion = accepted(RegionId.parse("first"))
    val secondRegion = accepted(RegionId.parse("second"))
    val purpose = accepted(Purpose.parse("mfa.recovery"))
    val clock = Instant.parse("2026-10-06T10:00:00Z")
    val deadlines = new Frontend.RpcDeadlineScheduler {
      def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = () => ()
    }
    val runtime = new SensitiveRuntime[Future](ConnectionId.fromUuid(UUID.randomUUID()), deadlines,
      (_, _) => Future.unit, () => clock)
    val permit = SensitiveAuthorization[Future](Audience.fromUuid(UUID.randomUUID()), () => Future.unit)
    def payload = accepted(SensitivePayload.textList(Vector("synthetic-code")))

    try {
      val first = runtime.present(firstRegion, purpose, payload, 1.minute, permit) { id =>
        firstId.set(Some(id)); Future.unit
      }
      val second = runtime.present(secondRegion, purpose, payload, 1.minute, permit) { id =>
        secondId.set(Some(id)); Future.unit
      }
      val emittedId = firstId.get().getOrElse(fail("First presentation was not enqueued"))
      val queuedId = secondId.get().getOrElse(fail("Second presentation was not enqueued"))
      val emitted = new CountDownLatch(1)
      effect.runAsync(runtime.pull(emittedId)) {
        case Right(Some(_)) => emitted.countDown()
        case _ => ()
      }
      emitted.await(5, TimeUnit.SECONDS) shouldBe true

      // Use Effect.flatMap deliberately: FutureEffect runs these continuations
      // on the completing thread, so this detects a callback under its monitor.
      val continuation = effect.flatMap(first) { _ =>
        continuationEntered.countDown()
        if (!releaseContinuation.await(15, TimeUnit.SECONDS))
          throw new IllegalStateException("Test continuation was not released")
        Future.unit
      }
      effect.runAsync(continuation) { result =>
        continuationResult.set(Some(result)); continuationDone.countDown()
      }
      executor.execute(() => effect.runAsync(runtime.acknowledge(emittedId, firstRegion, processed = true)) { result =>
        acknowledgementResult.set(Some(result)); acknowledgementDone.countDown()
      })
      continuationEntered.await(5, TimeUnit.SECONDS) shouldBe true

      executor.execute(() => effect.runAsync(effect.flatMap(runtime.clearRegion(secondRegion))(_ => runtime.close())) { result =>
        cleanupResult.set(Some(result)); cleanupDone.countDown()
      })
      // An implementation completing the first promise under entries.synchronized
      // cannot satisfy this until the continuation is released in finally.
      cleanupDone.await(5, TimeUnit.SECONDS) shouldBe true
      cleanupResult.get() shouldBe Some(Right(()))
      runtime.pending(queuedId) shouldBe false
      runtime.pending(emittedId) shouldBe false
      runtime.retainedPayloads shouldBe 0
      second.value shouldBe Some(Success(DisclosureOutcome.NotSent))

      releaseContinuation.countDown()
      acknowledgementDone.await(5, TimeUnit.SECONDS) shouldBe true
      continuationDone.await(5, TimeUnit.SECONDS) shouldBe true
      acknowledgementResult.get() shouldBe Some(Right(()))
      continuationResult.get() shouldBe Some(Right(()))
    } finally {
      releaseContinuation.countDown()
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
      effect.runAsync(runtime.close())(_ => ())
    }
  }

  "Sensitive retirement" should "enqueue its mandatory clear before running an inline result continuation" in {
    val executor = Executors.newSingleThreadExecutor()
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val clearEnqueued = new CountDownLatch(1)
    val finished = new CountDownLatch(1)
    val id = new AtomicReference(Option.empty[PresentationId])
    val region = accepted(RegionId.parse("recovery"))
    val runtime = new SensitiveRuntime[Future](ConnectionId.fromUuid(UUID.randomUUID()), withoutTimers,
      (_, _) => { clearEnqueued.countDown(); Future.unit }, () => Instant.parse("2026-10-06T10:00:00Z"))
    try {
      val disclosure = runtime.present(region, accepted(Purpose.parse("mfa.recovery")),
        accepted(SensitivePayload.textList(Vector("synthetic-code"))), 1.minute,
        SensitiveAuthorization(Audience.fromUuid(UUID.randomUUID()), () => Future.unit)) { value =>
        id.set(Some(value)); Future.unit
      }
      val presentation = id.get().getOrElse(fail("Presentation was not enqueued"))
      runtime.pull(presentation).value.exists(_.toOption.flatten.nonEmpty) shouldBe true
      val continuation = effect.flatMap(disclosure) { outcome =>
        outcome shouldBe DisclosureOutcome.Uncertain
        entered.countDown()
        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Test continuation was not released")
        Future.unit
      }
      effect.runAsync(continuation)(_ => finished.countDown())
      executor.execute(() => effect.runAsync(runtime.clearRegion(region))(_ => ()))
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      clearEnqueued.getCount shouldBe 0L
      release.countDown()
      finished.await(5, TimeUnit.SECONDS) shouldBe true
    } finally {
      release.countDown()
      executor.shutdownNow()
      executor.awaitTermination(5, TimeUnit.SECONDS)
      effect.runAsync(runtime.close())(_ => ())
    }
  }

  it should "settle its waiter even if clear enqueue fails" in {
    val id = new AtomicReference(Option.empty[PresentationId])
    val failure = new IllegalStateException("Synthetic clear enqueue failure")
    val region = accepted(RegionId.parse("recovery"))
    val runtime = new SensitiveRuntime[Future](ConnectionId.fromUuid(UUID.randomUUID()), withoutTimers,
      (_, _) => Future.failed(failure), () => Instant.parse("2026-10-06T10:00:00Z"))
    val disclosure = runtime.present(region, accepted(Purpose.parse("mfa.recovery")),
      accepted(SensitivePayload.textList(Vector("synthetic-code"))), 1.minute,
      SensitiveAuthorization(Audience.fromUuid(UUID.randomUUID()), () => Future.unit)) { value =>
      id.set(Some(value)); Future.unit
    }
    try {
      val presentation = id.get().getOrElse(fail("Presentation was not enqueued"))
      runtime.pull(presentation).value.exists(_.toOption.flatten.nonEmpty) shouldBe true
      runtime.clearRegion(region).value shouldBe Some(scala.util.Failure(failure))
      disclosure.value shouldBe Some(Success(DisclosureOutcome.Uncertain))
      runtime.retainedPayloads shouldBe 0
      runtime.pending(presentation) shouldBe false
    } finally effect.runAsync(runtime.close())(_ => ())
  }
}
