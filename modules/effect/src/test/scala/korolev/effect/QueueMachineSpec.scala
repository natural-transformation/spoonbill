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

  private val failure = new IllegalStateException("read failure")
  private val ending  = new IllegalStateException("failure at the end")
  private val states = for {
    admission    <- Admission.values.toVector
    error        <- Vector(None, Some(failure))
    cancellation <- CompletionLatchMachine.State.values.toVector
    endFailure   <- Vector(None, Some(ending))
  } yield State(admission, error, cancellation, endFailure)

  // Every lifecycle event, with both buffer answers for stop.
  private val events: Vector[State => State] = Vector(
    stop(_, buffered = false)._1,
    stop(_, buffered = true)._1,
    stop(_, buffered = false, Some(ending))._1,
    stop(_, buffered = true, Some(ending))._1,
    close(_),
    QueueMachine.fail(_, failure),
    QueueMachine.cancel(_)._1
  )

  "A stop with a failure" should "be decided by the stop that ends admission, and keep the other lifecycle facts" in {
    val later = new IllegalStateException("a later stop")
    for {
      before   <- states
      buffered <- Vector(false, true)
      asked    <- Vector(None, Some(later))
    } {
      val (after, _) = stop(before, buffered, asked)
      mayOffer(after) shouldBe false
      isClosed(after) shouldBe isClosed(before)
      after.error shouldBe before.error
      after.cancellation shouldBe before.cancellation
      // Consumers may have been told the first end already, so it stays.
      if (mayOffer(before)) after.endFailure shouldBe asked else after shouldBe before
    }
  }

  it should "give the accepted values first and fail every pull afterwards" in {
    val (stopped, whileBuffered) = stop(initial, buffered = true, Some(ending))
    whileBuffered shouldBe StopCommand.ReleaseProducers
    pull(stopped, buffered = true) shouldBe Pull.Buffered
    pull(stopped, buffered = false) shouldBe Pull.Failed(ending)
    // The same stop on an empty queue tells the waiting pulls at once.
    stop(initial, buffered = false, Some(ending)) shouldBe (stopped, StopCommand.ReleaseProducersAndFailPulls(ending))
    // A stop without a failure ends as before.
    pull(stop(initial, buffered = false)._1, buffered = false) shouldBe Pull.End
  }

  it should "give way to a failure of every pull, to close and to cancel" in {
    states.filter(_.endFailure.nonEmpty).foreach { state =>
      val expected = Vector(true, false).map { buffered =>
        if (state.error.nonEmpty) Pull.Failed(failure)
        else if (isClosed(state)) Pull.End
        else if (buffered) Pull.Buffered
        // Admission is still open only in states that no stop produced.
        else if (mayOffer(state)) Pull.Wait
        else Pull.Failed(ending)
      }
      Vector(true, false).map(pull(state, _)) shouldBe expected
      Vector(true, false).foreach { buffered =>
        pull(close(state), buffered) shouldBe (if (state.error.nonEmpty) Pull.Failed(failure) else Pull.End)
        pull(QueueMachine.cancel(state)._1, buffered) shouldBe
          (if (state.error.nonEmpty) Pull.Failed(failure) else Pull.End)
      }
    }
  }

  it should "tell waiting pulls what a new pull is told" in {
    for {
      before <- states if before.error.isEmpty
      asked  <- Vector(None, Some(ending))
    } {
      val (after, command) = stop(before, buffered = false, asked)
      // No pull waits on a queue whose pulls fail at once, so those states are left out.
      pull(after, buffered = false) match {
        case Pull.Failed(error) => command shouldBe StopCommand.ReleaseProducersAndFailPulls(error)
        case _                  => command shouldBe StopCommand.ReleaseProducersAndEndPulls
      }
    }
  }

  "Queue producer waiting" should "last only while admission is open and no pull has failed" in {
    states.foreach { state =>
      mayAwaitCapacity(state) shouldBe (state.admission == Admission.Open && state.error.isEmpty)
      // Waiting is the narrower permission: it never outlives admission.
      if (mayAwaitCapacity(state)) mayOffer(state) shouldBe true
    }
  }

  it should "end at failure while admission and the other lifecycle facts stay as they are" in {
    states.foreach { before =>
      val after = QueueMachine.fail(before, failure)
      mayAwaitCapacity(after) shouldBe false
      mayOffer(after) shouldBe mayOffer(before)
      isClosed(after) shouldBe isClosed(before)
      after.cancellation shouldBe before.cancellation
      pull(after, buffered = true) shouldBe Pull.Failed(failure)
      pull(after, buffered = false) shouldBe Pull.Failed(failure)
    }
  }

  it should "end at stop, close and cancel, and never return after any event" in {
    mayAwaitCapacity(initial) shouldBe true
    events.foreach(event => mayAwaitCapacity(event(initial)) shouldBe false)
    // A released producer stays released: no event reopens admission or clears the error.
    for {
      state <- states if !mayAwaitCapacity(state)
      event <- events
    } mayAwaitCapacity(event(state)) shouldBe false
  }

  "A producer that had to wait" should "commit by the rule that let it wait, in every state" in {
    states.foreach { state =>
      // Never laxer: a released producer must not use room that a drain made.
      if (mayCommitAfterWait(state)) mayAwaitCapacity(state) shouldBe true
      // Never stricter: a producer that may wait but not commit would be woken
      // by free room again and again.
      if (mayAwaitCapacity(state)) mayCommitAfterWait(state) shouldBe true
    }
  }

  it should "stop committing at a failure, while a new offer is admitted as before" in {
    mayCommitAfterWait(initial) shouldBe true
    states.foreach { before =>
      val after = QueueMachine.fail(before, failure)
      mayCommitAfterWait(after) shouldBe false
      mayOffer(after) shouldBe mayOffer(before)
    }
  }
}
