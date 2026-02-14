package spoonbill.server.internal.services

import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.Future
import scala.concurrent.duration._

class MessagingServiceSpec extends AnyFlatSpec with Matchers {

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
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 100.millis
    )
    val qsid = Qsid("device", "orphan-session")
    val result = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    result.isRight shouldBe true
    service.topicExists(qsid) shouldBe true
    Thread.sleep(200)
    service.topicExists(qsid) shouldBe false
  }

  it should "extend orphan cleanup on repeated publish" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 150.millis
    )
    val qsid = Qsid("device", "active-orphan")
    val first = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    first.isRight shouldBe true
    Thread.sleep(80)
    val second = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    second.isRight shouldBe true
    Thread.sleep(120)
    service.topicExists(qsid) shouldBe true
    Thread.sleep(120)
    service.topicExists(qsid) shouldBe false
  }

  it should "cancel orphan cleanup after subscribe" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val reporter = new CountingReporter()
    val service = new MessagingService[Future](
      reporter = reporter,
      // Not used by longPollingPublish in this test.
      commonService = null.asInstanceOf[CommonService[Future]],
      sessionsService = null.asInstanceOf[SessionsService[Future, Unit, Unit]],
      compressionSupport = None,
      orphanTopicTimeout = 100.millis
    )
    val qsid = Qsid("device", "subscribed-session")
    val result = effect.run(service.longPollingPublish(qsid, Stream.empty[Future, Bytes]))
    result.isRight shouldBe true
    service.createTopic(qsid)
    Thread.sleep(200)
    service.topicExists(qsid) shouldBe true
  }
}
