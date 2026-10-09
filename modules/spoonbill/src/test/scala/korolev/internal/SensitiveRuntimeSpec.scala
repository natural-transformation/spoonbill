package spoonbill.internal

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.sensitive.*

class SensitiveRuntimeSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter = Reporter.PrintReporter
  private val region = RegionId.parse("mfa").toOption.getOrElse(fail("region"))
  private val purpose = Purpose.parse("recovery-codes").toOption.getOrElse(fail("purpose"))
  private val audience = Audience.fromUuid(UUID.randomUUID())
  private val connection = ConnectionId.fromUuid(UUID.randomUUID())
  private val secret = "synthetic-recovery-never-log"
  private def payload = SensitivePayload.textList(Vector(secret)).toOption.getOrElse(fail("payload"))

  private class Clock extends Frontend.RpcDeadlineScheduler {
    case class Task(delay: FiniteDuration, action: () => Unit, canceled: AtomicBoolean = new AtomicBoolean(false))
    private val tasks = new AtomicReference(Vector.empty[Task])
    def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit = {
      val task = Task(delay, expire)
      tasks.updateAndGet(_ :+ task)
      () => task.canceled.set(true)
    }
    def fire(delay: FiniteDuration): Unit = tasks.get().find(task => task.delay == delay && !task.canceled.get())
      .getOrElse(fail("Missing deadline")).action()
  }

  "SensitiveRuntime" should "detach plaintext at first emission and accept only the bound browser acknowledgement" in {
    val clock = new Clock
    val enqueued = Promise[PresentationId]()
    val runtime = new SensitiveRuntime[Future](connection, clock, (_, _) => Future.unit)
    val result = runtime.present(region, purpose, payload, 1.minute, SensitiveAuthorization(audience, () => Future.unit)) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ = runtime.retainedPayloads shouldBe 1
      frame <- runtime.pull(id)
      _ = frame.getOrElse(fail("No disclosure")).contains(secret) shouldBe true
      _ = runtime.retainedPayloads shouldBe 0
      duplicate <- runtime.pull(id)
      _ = duplicate shouldBe None
      _ <- runtime.acknowledge(id, RegionId.parse("wrong").toOption.getOrElse(fail("region")), processed = false)
      _ = result.isCompleted shouldBe false
      _ = runtime.pending(id) shouldBe true
      _ <- runtime.acknowledge(id, region, processed = true)
      outcome <- result
      _ <- runtime.close()
    } yield outcome shouldBe DisclosureOutcome.BrowserProcessed
  }

  it should "settle lost acknowledgement as uncertain and release all plaintext" in {
    val clock = new Clock
    val enqueued = Promise[PresentationId]()
    val cleared = Promise[Unit]()
    val runtime = new SensitiveRuntime[Future](connection, clock, (_, _) => Future.successful { cleared.trySuccess(()); () })
    val result = runtime.present(region, purpose, payload, 1.minute, SensitiveAuthorization(audience, () => Future.unit)) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ <- runtime.pull(id)
      _ = clock.fire(5.seconds)
      outcome <- result
      _ <- cleared.future
      _ <- runtime.close()
    } yield {
      outcome shouldBe DisclosureOutcome.Uncertain
      runtime.retainedPayloads shouldBe 0
      runtime.pending(id) shouldBe false
    }
  }

  it should "reject authority lost before dequeue without releasing the payload" in {
    val enqueued = Promise[PresentationId]()
    val allowed = new AtomicBoolean(true)
    val runtime = new SensitiveRuntime[Future](connection, new Clock, (_, _) => Future.unit)
    val permit = SensitiveAuthorization[Future](audience, () =>
      if (allowed.get()) Future.unit else Future.failed(new SecurityException("denied")))
    val result = runtime.present(region, purpose, payload, 1.minute, permit) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ = allowed.set(false)
      frame <- runtime.pull(id)
      outcome <- result
      _ <- runtime.close()
    } yield {
      frame shouldBe None
      outcome shouldBe DisclosureOutcome.NotSent
      runtime.retainedPayloads shouldBe 0
    }
  }

  it should "clear acknowledged presentation when purpose authority is revoked" in {
    val clock = new Clock
    val enqueued = Promise[PresentationId]()
    val cleared = Promise[Unit]()
    val allowed = new AtomicBoolean(true)
    val runtime = new SensitiveRuntime[Future](connection, clock, (_, _) => Future.successful { cleared.trySuccess(()); () })
    val permit = SensitiveAuthorization[Future](audience, () =>
      if (allowed.get()) Future.unit else Future.failed(new SecurityException("denied")))
    val result = runtime.present(region, purpose, payload, 1.minute, permit) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ <- runtime.pull(id)
      _ <- runtime.acknowledge(id, region, processed = true)
      _ <- result
      _ = allowed.set(false)
      _ = clock.fire(1.second)
      _ <- cleared.future
      _ <- runtime.close()
    } yield runtime.pending(id) shouldBe false
  }

  it should "drop queued plaintext and settle a pending disclosure on disconnect" in {
    val enqueued = Promise[PresentationId]()
    val runtime = new SensitiveRuntime[Future](connection, new Clock, (_, _) => Future.unit)
    val result = runtime.present(region, purpose, payload, 1.minute, SensitiveAuthorization(audience, () => Future.unit)) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ <- runtime.close()
      outcome <- result
      frame <- runtime.pull(id)
    } yield {
      outcome shouldBe DisclosureOutcome.NotSent
      frame shouldBe None
      runtime.retainedPayloads shouldBe 0
    }
  }

  it should "release pending payloads when deadline registration fails" in {
    val clock = new Clock {
      override def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit =
        throw new IllegalStateException("Scheduler unavailable")
    }
    val runtime = new SensitiveRuntime[Future](connection, clock, (_, _) => Future.unit)
    runtime.present(region, purpose, payload, 1.minute, SensitiveAuthorization(audience, () => Future.unit))(
      _ => Future.failed(new AssertionError("Must not enqueue without deadlines"))).flatMap { outcome =>
      runtime.close().map { _ =>
        outcome shouldBe DisclosureOutcome.NotSent
        runtime.retainedPayloads shouldBe 0
      }
    }
  }

  it should "refuse a queued disclosure after its acknowledgement window even if timer execution is delayed" in {
    val instant = new AtomicReference(Instant.parse("2026-10-06T00:00:00Z"))
    val enqueued = Promise[PresentationId]()
    val runtime = new SensitiveRuntime[Future](connection, new Clock, (_, _) => Future.unit, () => instant.get())
    val result = runtime.present(region, purpose, payload, 1.minute, SensitiveAuthorization(audience, () => Future.unit)) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ = instant.updateAndGet(_.plusSeconds(6))
      frame <- runtime.pull(id)
      outcome <- result
      _ <- runtime.close()
    } yield {
      frame shouldBe None
      outcome shouldBe DisclosureOutcome.NotSent
      runtime.retainedPayloads shouldBe 0
    }
  }

  it should "clear a visible presentation if its ongoing authorization audit cannot be rescheduled" in {
    val unavailable = new AtomicBoolean(false)
    val clock = new Clock {
      override def schedule(delay: FiniteDuration)(expire: () => Unit): () => Unit =
        if (unavailable.get()) throw new IllegalStateException("Scheduler unavailable") else super.schedule(delay)(expire)
    }
    val enqueued = Promise[PresentationId]()
    val cleared = Promise[Unit]()
    val runtime = new SensitiveRuntime[Future](connection, clock, (_, _) => Future.successful { cleared.trySuccess(()); () })
    val result = runtime.present(region, purpose, payload, 1.minute, SensitiveAuthorization(audience, () => Future.unit)) {
      id => Future.successful { enqueued.trySuccess(id); () }
    }
    for {
      id <- enqueued.future
      _ <- runtime.pull(id)
      _ <- runtime.acknowledge(id, region, processed = true)
      _ <- result
      _ = unavailable.set(true)
      _ = clock.fire(1.second)
      _ <- cleared.future
      _ <- runtime.close()
    } yield runtime.pending(id) shouldBe false
  }

  "Frontend sensitive replies" should "progress while the serialized user action waits for the browser acknowledgement" in {
    val incoming = Queue[Future, String]()
    val frontend = new Frontend[Future](incoming.stream, Some(2), connectionId = Some(connection),
      authorize = Some(() => Future.unit), rpcDeadlineScheduler = Some(new Clock),
      captureSensitiveAuthorization = Some(_ => Future.successful(SensitiveAuthorization(audience, () => Future.unit))))
    val result = frontend.runUserAction(frontend.presentSensitive(region, purpose, payload, 1.minute))
    for {
      frame <- frontend.outgoingMessages.pull()
      parts = "\"([^\"]*)\"".r.findAllMatchIn(frame.getOrElse(fail("No sensitive frame"))).map(_.group(1)).toVector
      _ <- incoming.enqueue(s"""[8,"${parts(0)}:${parts(1)}:${parts(2)}:ok"]""")
      outcome <- result
      _ <- frontend.close()
    } yield outcome shouldBe DisclosureOutcome.BrowserProcessed
  }

  it should "deny disclosure when only ordinary page authorization is configured" in {
    val frontend = new Frontend[Future](Queue[Future, String]().stream, None, authorize = Some(() => Future.unit))
    frontend.presentSensitive(region, purpose, payload, 1.minute).failed.flatMap { error =>
      frontend.close().map(_ => error shouldBe a[spoonbill.server.SessionAccessDenied])
    }
  }

  it should "withhold action disclosure capability unless both ordinary and sensitive guards are configured" in {
    import spoonbill.action.*
    val action = new Actions[Future, Int, Unit].public(
      ActionName.parse("show-secret").toOption.getOrElse(fail("action")), InputSchema.empty,
      PublicPolicy.allow[Future, Unit]) { (_, context) =>
      Future.successful(context.presentSensitive[Int](region, purpose, payload, 1.minute)(_ => StateUpdate.unchanged))
    }
    val frontends = Vector(
      new Frontend[Future](Queue[Future, String]().stream, None, authorize = Some(() => Future.unit)),
      new Frontend[Future](Queue[Future, String]().stream, None,
        captureSensitiveAuthorization = Some(_ => Future.successful(SensitiveAuthorization(audience, () => Future.unit))))
    )
    Future.sequence(frontends.map { frontend => for {
      binding <- frontend.newActionBinding()
      error <- ActionDispatcher.public(action, Vector.empty, binding).failed
      _ <- frontend.close()
    } yield error shouldBe a[spoonbill.server.SessionAccessDenied] }).map(_ => succeed)
  }
}
