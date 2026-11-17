package spoonbill.effect

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/**
 * Which interpreter serves an effect, and what a pull and its read call of that
 * effect.
 */
class StreamRoutingSelectionSpec extends AnyFlatSpec with Matchers {
  import StreamRoutingMachine.*
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)

  /** Counts up from one. An operator takes its effect from its source. */
  private def counting(using Effect[Future]): Stream[Future, Int] = new Stream[Future, Int] {
    private var next                = 0
    def pull(): Future[Option[Int]] = { next += 1; Future.successful(Some(next)) }
    def cancel(): Future[Unit]      = Future.unit
  }
  private def routing(using Effect[Future]): StreamRoutingRuntime[Future, Int] =
    StreamRoutingRuntime(counting, Mode.Buffered, 1, Vector(new Lane))

  "Built-in Future instances" should "be served by the callback pump with either instrumentation choice" in {
    routing(using Effect.futureEffect) shouldBe a[StreamRoutingRuntime.CallbackPump[?]]
    routing(using Effect.future(Effect.FutureInstrumentation())) shouldBe a[StreamRoutingRuntime.CallbackPump[?]]
    routing(using Effect.future(Effect.FutureInstrumentation(continuation = Some(() => ())))) shouldBe
      a[StreamRoutingRuntime.CallbackPump[?]]
  }

  "Another effect of Future" should "be served by a reader fiber and its own promise" in {
    val calls        = new AtomicInteger()
    val observations = new AtomicInteger()
    given Effect[Future] = new ReaderFiberFutureEffect {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] = {
        calls.incrementAndGet(); super.promise(register)
      }
      override def runAsync[A](value: Future[A])(callback: Either[Throwable, A] => Unit): Unit = {
        observations.incrementAndGet(); super.runAsync(value)(callback)
      }
    }
    routing shouldBe a[StreamRoutingRuntime.ReaderFiber[?, ?]]
    val racks = counting.sort(2)(_ % 2)
    await(racks(1).pull()) shouldBe Some(1)
    await(racks(0).pull()) shouldBe Some(2)
    // The reader awaits each of its turns by a promise, and so does each pull.
    calls.get() should be >= 4
    observations.get() should be > 0
  }
}
