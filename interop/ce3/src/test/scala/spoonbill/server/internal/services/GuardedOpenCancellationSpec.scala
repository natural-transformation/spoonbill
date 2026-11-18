package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Promise}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Queue, Scheduler}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{
  ApplicationStateInitialization,
  SessionAccessControl,
  SessionAccessDenied,
  SessionGuard,
  SpoonbillServiceConfig,
  StateLoader
}
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class GuardedOpenCancellationSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO]       = new IO3Effect(global)
  private given ExecutionContext = ExecutionContext.global

  "A canceled guarded attach" should "release a guard returned after its caller and teardown have completed" in {
    lateGuard(resume = false, timeout = false)
  }

  it should "release a resumed guard returned after canceled attachment cleanup" in {
    lateGuard(resume = true, timeout = false)
  }

  "An expired guarded attach" should "release its late guard exactly once after timeout cleanup" in {
    lateGuard(resume = false, timeout = true)
  }

  private def lateGuard(resume: Boolean, timeout: Boolean): Unit = {
    val opening   = new CountDownLatch(1)
    val delivered = Promise[SessionGuard[IO, String]]()
    val closed    = Promise[Unit]()
    val closes    = new AtomicInteger()
    val deadline  = new AtomicReference[() => IO[Unit]]()
    val timing = new SessionsService.RequiredTiming[IO] {
      def scheduleOnce(delay: FiniteDuration)(job: => IO[Unit]): IO[Scheduler.JobHandler[IO, Unit]] = IO {
        deadline.set(() => job)
        new Scheduler.JobHandler[IO, Unit] {
          def unsafeCancel(): Unit = ()
          def cancel(): IO[Unit]   = IO.unit
          def result: IO[Unit]     = IO.unit
        }
      }
    }
    val guard = new SessionGuard[IO, String] {
      val applicationStateInitialization: ApplicationStateInitialization[IO] =
        ApplicationStateInitialization.Retained[IO]()
      def authorize(state: String): IO[Unit]   = IO.unit
      def connected(state: String): IO[String] = IO.pure(state)
      def close(): IO[Unit]                    = IO { closes.incrementAndGet(); closed.trySuccess(()); () }
    }
    val control = new SessionAccessControl[IO, String] {
      def authorizeHttp(request: Request.Head, state: String): IO[Unit] = IO.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId): IO[SessionGuard[IO, String]] =
        IO(opening.countDown()) *> IO.fromFuture(IO.pure(delivered.future))
      override def resume(
        qsid: Qsid,
        request: Request.Head,
        connection: ConnectionId
      ): Option[IO[SessionGuard[IO, String]]] =
        Some(open(qsid, request, connection))
    }
    val config = SpoonbillServiceConfig[IO, String, String](
      stateLoader = StateLoader.default("initial"),
      document = state => Html(body(div(state))),
      sessionAccessControl = Some(control)
    )
    val service  = new SessionsService(config, new PageService(config), Some(timing))
    val qsid     = Qsid("late-guard", UUID.randomUUID().toString)
    val request  = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val incoming = Queue[IO, String]()
    if (!resume) service.initAppState(qsid, request).unsafeRunSync()
    val attach = service.createAppIfNeeded(qsid, request, incoming.stream).void
    try {
      if (timeout) {
        val result = attach.attempt.unsafeToFuture()
        opening.await(5, TimeUnit.SECONDS) shouldBe true
        deadline.get()().unsafeRunSync()
        Await.result(result, 5.seconds).left.toOption.get shouldBe a[SessionAccessDenied]
      } else {
        val cancel = attach.unsafeRunCancelable()
        opening.await(5, TimeUnit.SECONDS) shouldBe true
        Await.result(cancel(), 5.seconds)
      }
      closes.get() shouldBe 0
      delivered.success(guard)
      Await.result(closed.future, 2.seconds)
      closes.get() shouldBe 1
      service.getApp(qsid).unsafeRunSync() shouldBe None
    } finally {
      delivered.trySuccess(guard)
      if (closes.get() == 0) guard.close().unsafeRunSync()
      incoming.close().unsafeRunSync()
    }
  }
}
