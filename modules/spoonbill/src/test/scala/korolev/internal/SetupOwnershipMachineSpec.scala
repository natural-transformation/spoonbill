package spoonbill.internal

import SetupOwnershipMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class SetupOwnershipMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Setup ownership" should "define every state/event pair without moving effects into the model" in {
    val expected = Map(
      (State.Waiting, Event.Prepare)   -> (State.Prepared, Vector(Command.RetainPrepared)),
      (State.Prepared, Event.Prepare)  -> (State.Prepared, Vector(Command.ReleaseIncoming)),
      (State.Attached, Event.Prepare)  -> (State.Attached, Vector(Command.ReleaseIncoming)),
      (State.Abandoned, Event.Prepare) -> (State.Abandoned, Vector(Command.ReleaseIncoming)),
      (State.Waiting, Event.Attach)    -> (State.Waiting, Vector(Command.RefuseAttachment)),
      (State.Prepared, Event.Attach)   -> (State.Attached, Vector(Command.Transfer)),
      (State.Attached, Event.Attach)   -> (State.Attached, Vector(Command.RefuseAttachment)),
      (State.Abandoned, Event.Attach)  -> (State.Abandoned, Vector(Command.RefuseAttachment)),
      (State.Waiting, Event.Abandon)   -> (State.Abandoned, Vector(Command.ReleaseInput)),
      (State.Prepared, Event.Abandon)  -> (State.Abandoned, Vector(Command.ReleasePrepared)),
      (State.Attached, Event.Abandon)  -> (State.Attached, Vector.empty),
      (State.Abandoned, Event.Abandon) -> (State.Abandoned, Vector.empty)
    )
    for (state <- State.values; event <- Event.values) step(state, event) shouldBe expected(state -> event)
  }

  it should "assign a prepared resource to exactly one of transfer or release across generated histories" in {
    forAll(Gen.listOf(Gen.oneOf(Event.values.toSeq))) { events =>
      val (_, retained, transferred, released) = events.foldLeft((State.Waiting, 0, 0, 0)) {
        case ((state, retained, transferred, released), event) =>
          val (next, commands) = step(state, event)
          if (state == State.Attached || state == State.Abandoned) next shouldBe state
          val nextRetained    = retained + commands.count(_ == Command.RetainPrepared)
          val nextTransferred = transferred + commands.count(_ == Command.Transfer)
          val nextReleased    = released + commands.count(_ == Command.ReleasePrepared)
          nextRetained should be <= 1
          (nextTransferred + nextReleased) should be <= nextRetained
          if (next == State.Prepared) nextRetained - nextTransferred - nextReleased shouldBe 1
          else nextRetained - nextTransferred - nextReleased shouldBe 0
          (next, nextRetained, nextTransferred, nextReleased)
      }
      (transferred + released) should be <= retained
    }
  }
}
