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
}
