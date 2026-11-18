package spoonbill.effect

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class QueueMachineSpec extends AnyFlatSpec with Matchers {
  import QueueMachine.*

  "Queue stop decisions" should "release producer obligations independently of retained data and preserve other lifecycle facts" in {
    val failure = new IllegalStateException("read failure")
    for {
      admission    <- Admission.values.toVector
      error        <- Vector(None, Some(failure))
      cancellation <- CompletionLatchMachine.State.values.toVector
      buffered     <- Vector(false, true)
    } {
      val before           = State(admission, error, cancellation)
      val (after, command) = stop(before, buffered)
      mayOffer(after) shouldBe false
      isClosed(after) shouldBe isClosed(before)
      after.error shouldBe before.error
      after.cancellation shouldBe before.cancellation
      // Neither command may leave a capacity waiter depending on a future pull.
      Set(StopCommand.ReleaseProducers, StopCommand.ReleaseProducersAndEndPulls) should contain(command)
      (command == StopCommand.ReleaseProducersAndEndPulls) shouldBe !buffered
      stop(after, buffered) shouldBe (after, command)
      if (error.nonEmpty) pull(after, buffered) shouldBe Pull.Failed(failure)
      else if (buffered && !isClosed(before)) pull(after, buffered) shouldBe Pull.Buffered
      else pull(after, buffered) shouldBe Pull.End
    }
  }

  it should "preserve the accepted prefix through repeated stop while ending pulls only after drain" in {
    val (stopped, beforeDrain) = stop(initial, buffered = true)
    beforeDrain shouldBe StopCommand.ReleaseProducers
    pull(stopped, buffered = true) shouldBe Pull.Buffered
    mayOffer(stopped) shouldBe false
    val (drained, afterDrain) = stop(stopped, buffered = false)
    drained shouldBe stopped
    afterDrain shouldBe StopCommand.ReleaseProducersAndEndPulls
    pull(drained, buffered = false) shouldBe Pull.End
    cancellationReplays(drained) shouldBe false
    val (canceled, publishCancellation) = QueueMachine.cancel(drained)
    publishCancellation shouldBe true
    cancellationReplays(canceled) shouldBe true
    stop(canceled, buffered = false)._1 shouldBe canceled
  }
}
