package spoonbill.server.internal.services

import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Scheduler, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._

class MessagingServiceSpec extends AnyFlatSpec with Matchers {

  "WebSocket protocol selection" should "prefer plain JSON by default even when deflate is offered" in {
    MessagingService.selectedProtocol(Seq("json", "json-deflate"), compressionEnabled = false) shouldBe "json"
  }

  it should "negotiate deflate only when explicitly enabled" in {
    MessagingService.selectedProtocol(Seq("json", "json-deflate"), compressionEnabled = true) shouldBe "json-deflate"
  }

  private final class CountingReporter extends Reporter {
    var debugCount: Int = 0
    def error(message: String, cause: Throwable): Unit = ()
    def error(message: String): Unit = ()
    def warning(message: String, cause: Throwable): Unit = ()
    def warning(message: String): Unit = ()
    def info(message: String): Unit = ()
    def debug(message: String): Unit = debugCount += 1
    def debug(message: String, arg1: Any): Unit = debugCount += 1
    def debug(message: String, arg1: Any, arg2: Any): Unit = debugCount += 1
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = debugCount += 1
  }

  private final class ManualOrphanTiming(implicit effect: Effect[Future])
      extends MessagingService.OrphanCleanupTiming[Future] {
    private final class Task(val deadline: Long, run: () => Future[Unit]) extends Scheduler.JobHandler[Future, Unit] {
      val canceled = new AtomicBoolean(false)
      private val fired = new AtomicBoolean(false)
      private val completed = Promise[Unit]()

      def unsafeCancel(): Unit = canceled.set(true)
      def cancel(): Future[Unit] = effect.delay(unsafeCancel())
      def result: Future[Unit] = completed.future
      def fire(forceCanceled: Boolean = false): Unit = {
        if ((forceCanceled || !canceled.get()) && fired.compareAndSet(false, true)) {
          val outcome = effect.run(effect.delayAsync(run()))
          outcome match {
            case Right(_) => completed.trySuccess(()); ()
            case Left(error) => completed.tryFailure(error); throw error
          }
        }
      }
    }

    private val clock = new AtomicLong(0L)
    private val tasks = new AtomicReference(Vector.empty[Task])
    def nowMillis(): Long = clock.get()
    def scheduleOnce(delay: FiniteDuration)(job: => Future[Unit]): Future[Scheduler.JobHandler[Future, Unit]] = effect.delay {
      val task = new Task(nowMillis() + delay.toMillis, () => job)
      tasks.updateAndGet(_ :+ task)
      task
    }
    def isCanceled(index: Int): Boolean = tasks.get()(index).canceled.get()
    def advanceTo(millis: Long): Unit = {
      require(millis >= nowMillis(), "Manual clock must not move backwards")
      clock.set(millis)
      tasks.get().filter(_.deadline <= millis).sortBy(_.deadline).foreach(_.fire())
    }
    def fireCanceled(index: Int): Unit = {
      val task = tasks.get()(index)
      require(task.canceled.get() && task.deadline <= nowMillis(), "Expected a canceled, due callback")
      task.fire(forceCanceled = true)
    }
  }

  "createTopic" should "reuse existing topic and log once" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by createTopic in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 1.second
    )
    val qsid = Qsid("device", "session")
    service.createTopic(qsid)
    service.createTopic(qsid)
    reporter.debugCount shouldBe 1
  }

  it should "allow publish before subscribe" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 1.second
    )
    val qsid = Qsid("device", "session")
    val result = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    result.isRight shouldBe true
  }

  it should "cleanup orphan topics after timeout" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val timing = new ManualOrphanTiming
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 100.millis,
      orphanCleanupTiming = Some(timing)
    )
    val qsid = Qsid("device", "orphan-session")
    val result = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    result.isRight shouldBe true
    service.topicExists(qsid) shouldBe true
    timing.advanceTo(99)
    service.topicExists(qsid) shouldBe true
    timing.advanceTo(100)
    service.topicExists(qsid) shouldBe false
  }

  it should "extend orphan cleanup on repeated publish" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val timing = new ManualOrphanTiming
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 150.millis,
      orphanCleanupTiming = Some(timing)
    )
    val qsid = Qsid("device", "active-orphan")
    val first = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    first.isRight shouldBe true
    timing.advanceTo(80)
    val second = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    second.isRight shouldBe true
    timing.isCanceled(0) shouldBe true
    timing.advanceTo(150)
    // Cancellation may race a callback that was already dispatched. Its stale
    // deadline must still respect the later publish's last-activity timestamp.
    timing.fireCanceled(0)
    service.topicExists(qsid) shouldBe true
    timing.advanceTo(229)
    service.topicExists(qsid) shouldBe true
    timing.advanceTo(230)
    service.topicExists(qsid) shouldBe false
  }

  it should "cancel orphan cleanup after subscribe" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val timing = new ManualOrphanTiming
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 100.millis,
      orphanCleanupTiming = Some(timing)
    )
    val qsid = Qsid("device", "subscribed-session")
    val result = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    result.isRight shouldBe true
    service.createTopic(qsid)
    timing.isCanceled(0) shouldBe true
    timing.advanceTo(100)
    timing.fireCanceled(0)
    service.topicExists(qsid) shouldBe true
  }
}
