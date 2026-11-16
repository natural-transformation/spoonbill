package spoonbill.internal

import LegacyDelayMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class LegacyDelayMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Legacy delay decisions" should "preserve an inline fire and keep cancellation distinct from completion" in {
    step(State.Uninstalled, Event.Cancel) shouldBe (State.Uninstalled, Command.Ignore)
    val fired = step(State.Uninstalled, Event.Fired)
    fired shouldBe (State.FinishedUninstalled, Command.RunCallback)
    step(fired._1, Event.Installed) shouldBe (State.FinishedArmed, Command.RetainHandle)
    step(State.Armed, Event.Cancel) shouldBe (State.Armed, Command.CancelHandle)
    finished(State.Armed) shouldBe false
    step(State.FinishedArmed, Event.Cancel) shouldBe (State.FinishedArmed, Command.CancelHandle)
    step(State.Uninstalled, Event.ScheduleFailed) shouldBe (State.FinishedUninstalled, Command.ReportFailure)
  }

  it should "keep completion monotonic and request cancellation only for an installed handle" in {
    forAll(Gen.listOf(Gen.oneOf(Event.values.toSeq))) { events =>
      events.foldLeft((State.Uninstalled, false, false)) { case ((state, installed, completed), event) =>
        val (next, command) = step(state, event)
        if (event == Event.Cancel) command shouldBe (if (installed) Command.CancelHandle else Command.Ignore)
        val hasHandle   = installed || event == Event.Installed
        val hasFinished = completed || event == Event.Fired || event == Event.ScheduleFailed
        finished(next) shouldBe hasFinished
        if (event == Event.Installed) command shouldBe Command.RetainHandle
        if (event == Event.Fired) command shouldBe Command.RunCallback
        if (event == Event.ScheduleFailed) command shouldBe Command.ReportFailure
        (next, hasHandle, hasFinished)
      }
    }
  }
}
