package spoonbill.effect

import LateHandleMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class LateHandleMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Late handle ownership" should "cancel an installed handle at finish or an incoming handle after finish" in {
    step(State.Waiting, Event.Install) shouldBe (State.Installed, Command.StoreHandle)
    step(State.Installed, Event.Install) shouldBe (State.Installed, Command.StoreHandle)
    step(State.Waiting, Event.Finish) shouldBe (State.Finished, Command.Finish)
    step(State.Installed, Event.Finish) shouldBe (State.Finished, Command.CancelAndFinish)
    step(State.Finished, Event.Install) shouldBe (State.Finished, Command.CancelIncoming)
    step(State.Finished, Event.Finish) shouldBe (State.Finished, Command.Ignore)
  }

  it should "finish once and never retain an incoming handle after termination across generated histories" in {
    forAll(Gen.listOf(Gen.oneOf(Event.Install, Event.Finish))) { events =>
      events.foldLeft((State.Waiting, 0)) { case ((state, completions), event) =>
        val (next, command) = step(state, event)
        if (state == State.Finished) {
          next shouldBe State.Finished
          command should not be Command.StoreHandle
        }
        if (command == Command.CancelAndFinish) state shouldBe State.Installed
        if (command == Command.CancelIncoming) state shouldBe State.Finished
        val count = completions + (if (command == Command.Finish || command == Command.CancelAndFinish) 1 else 0)
        count should be <= 1
        (next, count)
      }
    }
  }
}
