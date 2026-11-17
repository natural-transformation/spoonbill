package spoonbill.internal

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Hub, Queue, Reporter, Stream, StreamSortOverflowException}
import spoonbill.effect.syntax.*

final class FrontendRoutingOverflowSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private given ExecutionContext            = ExecutionContext.parasitic
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private final class Fixture(failFrontendCancel: Boolean) {
    val unknown = Promise[Throwable]()
    private given Reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit = cause match {
        case _: Frontend.UnknownCallbackException => unknown.trySuccess(cause); ()
        case _                                    => ()
      }
      def error(message: String): Unit                                  = ()
      def warning(message: String, cause: Throwable): Unit              = ()
      def warning(message: String): Unit                                = ()
      def info(message: String): Unit                                   = ()
      def debug(message: String): Unit                                  = ()
      def debug(message: String, arg1: Any): Unit                       = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }
    val incoming         = Queue[Future, String]()
    val sourceCanceled   = new AtomicInteger()
    private val canceled = new AtomicBoolean()
    val source = new Stream[Future, String] {
      def pull(): Future[Option[String]] = incoming.stream.pull()
      def cancel(): Future[Unit] =
        if (canceled.compareAndSet(false, true)) {
          sourceCanceled.incrementAndGet()
          incoming.stream.cancel()
        } else Future.unit
    }
    val (consumed, managed)  = source.handleConsumed
    private val hub          = Hub(managed)
    private val frontendLane = hub.newStreamUnsafe()
    private val frontendInput = new Stream[Future, String] {
      def pull(): Future[Option[String]] = frontendLane.pull()
      def cancel(): Future[Unit] = frontendLane.cancel().flatMap { _ =>
        if (failFrontendCancel) Future.failed(new IllegalStateException("Synthetic frontend lane cleanup failure"))
        else Future.unit
      }
    }
    private val activityInput = hub.newStreamUnsafe()
    val frontend              = new Frontend[Future](frontendInput, None)
    val activity              = new AtomicInteger()
    val watching              = activityInput.foreach(_ => Future.successful { activity.incrementAndGet(); () })
    val output                = frontend.outgoingMessages.pull()
    // This is the legacy transport release boundary: output EOF releases the
    // raw input, whose consumed signal owns subsequent application retirement.
    val released = output.flatMap {
      case None    => source.cancel()
      case Some(_) => Future.failed(new AssertionError("Unexpected output frame"))
    }
    val retired = new AtomicInteger()
    val retirement = consumed.map { _ =>
      retired.incrementAndGet(); ()
    }
    def overflowDomRack(): Future[Option[Frontend.BrowserHistoryMessage]] = {
      val history = frontend.browserHistoryMessages.pull()
      for (_ <- 0 until 129) await(incoming.enqueue("[0,\"0:1:click\"]"))
      history
    }
    def close(): Unit = {
      await(source.cancel())
      await(frontend.close())
      await(watching)
    }
  }

  for ((controlConsumerAlreadyFailed, failFrontendCancel) <- Seq(false -> false, true -> false, false -> true)) {
    "Legacy routing overflow" should s"close output and retire despite a separate activity watcher (control failed=$controlConsumerAlreadyFailed, cleanup failed=$failFrontendCancel)" in {
      val fixture = new Fixture(failFrontendCancel)
      try {
        if (controlConsumerAlreadyFailed) {
          await(fixture.incoming.enqueue("[99]"))
          await(fixture.unknown.future)
          fixture.frontend.interactionIsLive shouldBe true
          fixture.output.isCompleted shouldBe false
        }
        val history = fixture.overflowDomRack()
        val overflow = await(history.failed)
        overflow shouldBe StreamSortOverflowException(0, 128)
        await(fixture.frontend.domEventMessages.pull().failed) should be theSameInstanceAs overflow
        await(fixture.output) shouldBe None
        await(fixture.released)
        await(fixture.retirement)
        fixture.frontend.interactionIsLive shouldBe false
        fixture.sourceCanceled.get() shouldBe 1
        fixture.retired.get() shouldBe 1
        fixture.activity.get() should be >= 128
      } finally fixture.close()
    }
  }
}
