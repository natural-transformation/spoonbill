package spoonbill.zio

import _root_.zio.{Duration, Runtime, Task, Unsafe}
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.ExecutionContext
import spoonbill.effect.Effect
import spoonbill.server.internal.services.LongPollingSessionChecks

/** The shared long-polling checks under an effect that can be interrupted. */
class LongPollingCancellationZioSpec extends AnyFlatSpec {
  private given Effect[Task]     = new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private given ExecutionContext = ExecutionContext.global

  /** Starts a request and returns the function that interrupts it and waits for that. */
  private def interruptible(request: Task[Unit]): () => Unit =
    Unsafe.unsafe { implicit unsafe =>
      val fiber = Runtime.default.unsafe.fork(request)
      // A bound, so an interruption that never returns fails the test.
      val interrupt = fiber.interrupt
        .timeoutFail(new IllegalStateException("The interruption did not return"))(Duration.fromSeconds(30))
      () => { Runtime.default.unsafe.run(interrupt).getOrThrowFiberFailure(); () }
    }

  "A long-polling request of the next opening that is interrupted" should "still end the view of the opening before" in {
    LongPollingSessionChecks.cancelledReopening[Task](interruptible)
  }
}
