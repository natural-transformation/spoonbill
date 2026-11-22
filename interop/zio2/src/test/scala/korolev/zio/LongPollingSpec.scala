package spoonbill.zio

import _root_.zio.{Duration, Runtime, Task, Unsafe}
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.ExecutionContext
import spoonbill.effect.Effect
import spoonbill.server.internal.services.LongPollingSessionChecks

/** The long-polling checks that need cancellation, under ZIO 2. */
class LongPollingSpec extends AnyFlatSpec {
  private given Effect[Task]     = new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private given ExecutionContext = ExecutionContext.global

  /** Starts an effect and returns the function that interrupts it and waits for that. */
  private def interruptible(effect: Task[Unit]): () => Unit =
    Unsafe.unsafe { implicit unsafe =>
      val fiber = Runtime.default.unsafe.fork(effect)
      // A bound, so an interruption that never returns fails the test.
      val interrupt = fiber.interrupt
        .timeoutFail(new IllegalStateException("The interruption did not return"))(Duration.fromSeconds(30))
      () => { Runtime.default.unsafe.run(interrupt).getOrThrowFiberFailure(); () }
    }

  "A long poll" should "give a joined poll the one view when the first poll is interrupted during the attach" in {
    LongPollingSessionChecks.cancelledFirstPoll[Task](interruptible)
  }

  it should "neither skip nor repeat the frame of a poll that was interrupted after its pull" in {
    LongPollingSessionChecks.cancelledAfterPull[Task](interruptible)
  }

  it should "not hang after a render that was interrupted between its record and its state" in {
    LongPollingSessionChecks.cancelledRender[Task](interruptible)
  }
}
