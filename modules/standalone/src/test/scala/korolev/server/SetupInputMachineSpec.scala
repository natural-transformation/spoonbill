package spoonbill.server

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SetupInputMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import SetupInputMachine.*

  "Input handoff decisions" should "name all pull, coupling and cancellation rules" in {
    pullAction(State.Setup) shouldBe PullAction.Wait
    pullAction(State.Coupled) shouldBe PullAction.Read
    pullAction(State.Released) shouldBe PullAction.End
    coupleAction(State.Setup) shouldBe CoupleAction.ForwardWaiters
    coupleAction(State.Coupled) shouldBe CoupleAction.AlreadyCoupled
    coupleAction(State.Released) shouldBe CoupleAction.CloseTransport
    State.values.foreach { state =>
      readDirect(state) shouldBe (pullAction(state) == PullAction.Read)
      notifyOnCancel(state) shouldBe (state == State.Coupled)
      canceled(state) shouldBe State.Released
    }
  }

  it should "never recouple released input or request a second end notification" in {
    forAll(Gen.listOf(Gen.oneOf(false, true))) { events =>
      events.foldLeft((initial, 0)) { case ((before, notifications), cancel) =>
        val count = notifications + (if (cancel && notifyOnCancel(before)) 1 else 0)
        val after = if (cancel) canceled(before) else coupled(before)
        if (before == State.Released) after shouldBe State.Released
        count should be <= 1
        (after, count)
      }
    }
  }
}
