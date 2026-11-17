package spoonbill.server.internal.services

import avocet.dsl.*
import avocet.dsl.html.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.collection.mutable
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.{Extension, Qsid}
import spoonbill.effect.{Effect, Queue, Scheduler}
import spoonbill.internal.TimerTestSupport.Reports
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{
  ApplicationStateInitialization,
  SessionAccessControl,
  SessionGuard,
  SpoonbillServiceConfig,
  StateLoader
}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.defaultExecutor
import spoonbill.web.{PathAndQuery, Request}

private[services] object SessionIdleTestSupport {
  given Effect[Future]              = Effect.futureEffect
  def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  final class Timing extends SessionsService.IdleTiming[Future] {
    val jobs                   = mutable.ArrayBuffer.empty[Job]
    var events                 = Vector.empty[String]
    var onCreated: Job => Unit = _ => ()
    final class Job(val id: Int, val delay: FiniteDuration, body: () => Future[Unit])
        extends Scheduler.JobHandler[Future, Unit] {
      var cancellations          = 0
      private val outcome        = Promise[Unit]()
      def unsafeCancel(): Unit   = { cancellations += 1; events :+= s"cancel:$id" }
      def cancel(): Future[Unit] = Effect[Future].delay(unsafeCancel())
      def result: Future[Unit]   = outcome.future
      // This also models a callback already dispatched when cancellation arrives.
      def fire(): Future[Unit] = {
        events :+= s"fire:$id"
        val fired = Effect[Future].delayAsync(body())
        fired.onComplete(outcome.tryComplete)
        fired
      }
    }
    def unsafeScheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Job = {
      val next = new Job(jobs.size, delay, () => job)
      events :+= s"schedule:${next.id}"
      jobs += next
      onCreated(next)
      next
    }
    def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] =
      Effect[Future].delay(unsafeScheduleOnce(delay)(job))
  }

  final class Fixture(val guarded: Boolean) {
    val timing       = new Timing
    val reports      = new Reports
    var destroyed    = 0
    var guardsClosed = 0
    val control = new SessionAccessControl[Future, Int] {
      def authorizeHttp(request: Request.Head, state: Int): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, connection: ConnectionId): Future[SessionGuard[Future, Int]] =
        Future.successful(new SessionGuard[Future, Int] {
          val applicationStateInitialization: ApplicationStateInitialization[Future] =
            ApplicationStateInitialization.Retained[Future]()
          def authorize(state: Int): Future[Unit] = Future.unit
          def connected(state: Int): Future[Int]  = Future.successful(state)
          def close(): Future[Unit]               = { guardsClosed += 1; Future.unit }
        })
    }
    val config = SpoonbillServiceConfig[Future, Int, Unit](
      stateLoader = StateLoader.default(0),
      stateStorage = StateStorage.ephemeral[Future, Int](),
      document = value => Html(body(div(value.toString))),
      reporter = reports,
      sessionIdleTimeout = 5.seconds,
      sessionAccessControl = Option.when(guarded)(control),
      extensions = List(
        Extension.pure[Future, Int, Unit]("lifetime")(_ =>
          Extension.Handlers[Future, Int, Unit](onDestroy = _ => { destroyed += 1; Future.unit })
        )
      )
    )
    val service = new SessionsService(config, new PageService(config), idleTiming = Some(timing))
    val qsid    = Qsid("idle-timing", if (guarded) "guarded" else "legacy")
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    def attach(incoming: Queue[Future, String]): SessionStart.Attached[Future] = {
      await(service.initAppState(qsid, request))
      await(service.createAppIfNeeded(qsid, request, incoming.stream)).asInstanceOf[SessionStart.Attached[Future]]
    }
  }
}

class SessionIdleTimingSpec extends AnyFlatSpec with Matchers {
  import SessionIdleTestSupport.*
  import SessionIdleTestSupport.given

  for (guarded <- List(true, false)) {
    val mode = if (guarded) "Guarded" else "Legacy"

    s"$mode idle rearming" should "preserve its schedule-versus-cancel order on observed activity" in {
      val f        = new Fixture(guarded)
      val incoming = Queue[Future, String]()
      val attached = f.attach(incoming)
      try {
        f.timing.jobs.size shouldBe 1
        f.timing.jobs.head.delay shouldBe 5.seconds
        f.timing.events = Vector.empty
        await(incoming.enqueue("[6]"))
        f.timing.jobs.size shouldBe 2
        f.timing.events shouldBe (if (guarded) Vector("schedule:1", "cancel:0") else Vector("cancel:0", "schedule:1"))
        f.timing.jobs.head.cancellations shouldBe 1
        f.timing.jobs(1).cancellations shouldBe 0
      } finally await(attached.release())
    }

    it should "allow an already-dispatched canceled job to win closure once after rearming" in {
      val f        = new Fixture(guarded)
      val incoming = Queue[Future, String]()
      val attached = f.attach(incoming)
      try {
        await(incoming.enqueue("[6]"))
        await(f.timing.jobs.head.fire())
        await(f.service.getApp(f.qsid)) shouldBe None
        f.destroyed shouldBe 1
        f.timing.jobs(1).cancellations shouldBe (if (guarded) 1 else 0)
        await(f.timing.jobs(1).fire())
        await(attached.release())
        f.destroyed shouldBe 1
        f.guardsClosed shouldBe (if (guarded) 1 else 0)
      } finally await(attached.release())
    }

    it should "keep a later attachment alive when the old terminal job is delivered again" in {
      val f     = new Fixture(guarded)
      val first = f.attach(Queue[Future, String]())
      val old   = f.timing.jobs.head
      await(first.release())
      old.cancellations shouldBe (if (guarded) 1 else 0)
      val second = f.attach(Queue[Future, String]())
      try {
        val current = await(f.service.getApp(f.qsid)).get
        await(old.fire())
        await(f.service.getApp(f.qsid)).get should be theSameInstanceAs current
        f.destroyed shouldBe 1
      } finally await(second.release())
      f.destroyed shouldBe 2
    }
  }
}
