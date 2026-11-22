package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LegacySessionMachineSpec extends AnyFlatSpec with Matchers {
  import LegacySessionMachine.*

  "Legacy retirement" should "preserve the first cleanup cause when rollback races a disconnected watcher" in {
    for (first <- Cause.values; second <- Cause.values) {
      val (started, command) = step(State(), Event.Retire(first))
      command shouldBe Command.Run(Stage.StopExtensions)
      step(started, Event.Retire(second)) shouldBe (started, Command.None)
      val closed = step(started, Event.ConnectionClosed)._1
      connectionClosed(closed) shouldBe true
      closed.retirement shouldBe started.retirement
      step(closed, Event.Finished(Stage.SettleRegistry)) shouldBe (closed, Command.None)
    }
  }

  it should "settle the registry last, after stopping delivery, closing the view, restoring and removing storage" in {
    val expected = Vector(
      Stage.StopExtensions,
      Stage.CancelInput,
      Stage.CloseFrontend,
      Stage.DestroyApplication,
      Stage.DestroyExtensions,
      Stage.Restore,
      Stage.RemoveStorage,
      Stage.SettleRegistry
    )
    val initial = step(State(), Event.Retire(Cause.Abandoned))
    val finalState = expected.foldLeft(initial) { case ((state, command), stage) =>
      command shouldBe Command.Run(stage)
      step(state, Event.Retire(Cause.Disconnected)) shouldBe (state, Command.None)
      step(state, Event.Finished(stage))
    }
    finalState shouldBe (State(retirement = Retirement.Done(Cause.Abandoned)), Command.None)
    step(finalState._1, Event.Retire(Cause.Disconnected)) shouldBe finalState
  }

  it should "fence setup only after connection loss and never retry a failed retirement" in {
    val started = step(State(), Event.Retire(Cause.Abandoned))._1
    connectionClosed(started) shouldBe false
    val closed = step(started, Event.ConnectionClosed)._1
    connectionClosed(closed) shouldBe true
    val failed = step(closed, Event.Failed)._1
    failed.retirement shouldBe Retirement.Failed(Cause.Abandoned)
    step(failed, Event.Retire(Cause.Disconnected)) shouldBe (failed, Command.None)
    for (stage <- Stage.values) step(failed, Event.Finished(stage)) shouldBe (failed, Command.None)
  }

  /** A retirement of `cause` that has run every stage before `stage`. */
  private def at(cause: Cause, stage: Stage): State =
    Stage.values.takeWhile(_ != stage).foldLeft(step(State(), Event.Retire(cause))._1) { (state, done) =>
      step(state, Event.Finished(done))._1
    }
  private def retired(cause: Cause): State = step(at(cause, Stage.SettleRegistry), Event.Finished(Stage.SettleRegistry))._1

  "A rollback" should "take its failure out of the registry once, after the registry has published it" in {
    val done = retired(Cause.Abandoned)
    rolledBack(done) shouldBe true
    val (forgotten, command) = step(done, Event.Published)
    command shouldBe Command.Forget
    forgotten.retirement shouldBe Retirement.Forgotten
    rolledBack(forgotten) shouldBe true
    step(forgotten, Event.Published) shouldBe (forgotten, Command.None)
  }

  it should "also be one when the attach failed before it had a view" in {
    val (done, command) = step(State(), Event.NothingBuilt)
    command shouldBe Command.None
    done.retirement shouldBe Retirement.Done(Cause.Abandoned)
    step(done, Event.Published)._2 shouldBe Command.Forget
    // With a view there is a retirement to run, and this event changes nothing.
    val running = step(State(), Event.Retire(Cause.Abandoned))._1
    step(running, Event.NothingBuilt) shouldBe (running, Command.None)
  }

  "An attach that did not roll back" should "leave the registry alone when a failure is published" in {
    val states = List(
      State(),                                                  // it only waited for another attach
      at(Cause.Abandoned, Stage.Restore),                       // its rollback still runs
      retired(Cause.Disconnected),                              // its view was retired after a disconnect
      step(at(Cause.Abandoned, Stage.Restore), Event.Failed)._1 // its rollback failed
    )
    for (state <- states) {
      rolledBack(state) shouldBe false
      step(state, Event.Published) shouldBe (state, Command.None)
    }
  }
}
