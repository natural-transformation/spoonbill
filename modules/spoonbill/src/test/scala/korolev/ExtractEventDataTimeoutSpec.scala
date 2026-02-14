package spoonbill

import java.util.concurrent.atomic.AtomicReference

import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.internal.Frontend
import spoonbill.internal.Frontend.DomEventMessage
import spoonbill.testExecution.defaultExecutor
import avocet.Id
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class ExtractEventDataTimeoutSpec extends AnyFlatSpec with Matchers {

  "extractEventData" should "fail when client does not respond" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    implicit val reporter: Reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit = ()
      def error(message: String): Unit                   = ()
      def warning(message: String, cause: Throwable): Unit = ()
      def warning(message: String): Unit                   = ()
      def info(message: String): Unit                      = ()
      def debug(message: String): Unit                     = ()
      def debug(message: String, arg1: Any): Unit          = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }

    SystemPropertyFixture.withSystemProperty("spoonbill.extractEventDataTimeoutMillis", "50") {
      val frontend = new Frontend[Future](Stream.endless[Future, String], heartbeatLimit = None)
      val future = effect.toFuture(frontend.extractEventData(DomEventMessage(0, Id("1"), "input")))
      val error  = intercept[Throwable](Await.result(future, 1.second))
      error shouldBe a[Frontend.ClientSideException]
      error.getMessage should include("ExtractEventData timed out")
    }
  }

  it should "ignore late client responses after timeout" in {
    implicit val effect: Effect[Future] = Effect.futureEffect
    val errorRef                        = new AtomicReference[Option[Throwable]](None)
    val descriptorRef                   = new AtomicReference[Option[String]](None)
    implicit val reporter: Reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit = errorRef.set(Some(cause))
      def error(message: String): Unit                   = errorRef.set(Some(new RuntimeException(message)))
      def warning(message: String, cause: Throwable): Unit = ()
      def warning(message: String): Unit                   = ()
      def info(message: String): Unit                      = ()
      def debug(message: String): Unit = {
        if (message.startsWith("ExtractEventData request:")) {
          val idx = message.indexOf("descriptor=")
          if (idx >= 0) {
            val descriptor = message.substring(idx + "descriptor=".length).trim
            if (descriptor.nonEmpty) {
              descriptorRef.set(Some(descriptor))
            }
          }
        }
      }
      def debug(message: String, arg1: Any): Unit          = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }

    SystemPropertyFixture.withSystemProperty("spoonbill.extractEventDataTimeoutMillis", "10") {
      val incomingQueue = Queue[Future, String]()
      val frontend      = new Frontend[Future](incomingQueue.stream, heartbeatLimit = None)
      val future        = effect.toFuture(frontend.extractEventData(DomEventMessage(0, Id("1"), "input")))
      val error         = intercept[Throwable](Await.result(future, 1.second))
      error shouldBe a[Frontend.ClientSideException]
      error.getMessage should include("ExtractEventData timed out")

      val deadlineNanos = System.nanoTime() + 1.second.toNanos
      while (descriptorRef.get().isEmpty && System.nanoTime() < deadlineNanos) {
        Thread.sleep(5)
      }
      val descriptor = descriptorRef.get().getOrElse(fail("Descriptor was not captured"))
      val message =
        s"[${Frontend.CallbackType.ExtractEventDataResponse.code},\"$descriptor:{}\"]"
      Await.result(effect.toFuture(incomingQueue.enqueue(message)), 1.second)
      Thread.sleep(25)

      errorRef.get() shouldBe None
    }
  }
}
