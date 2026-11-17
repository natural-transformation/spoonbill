package spoonbill.internal

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StreamPublisherMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import StreamPublisherMachine.*

  "Publisher decisions" should "claim exactly one terminal outcome" in {
    step(initial, Event.Cancel) shouldBe (State.Canceled, Command.CancelSource)
    step(initial, Event.Complete) shouldBe (State.Completed, Command.Complete)
    step(initial, Event.Fail) shouldBe (State.Failed, Command.Fail)
    forAll(Gen.listOf(Gen.oneOf(Event.values.toSeq))) { events =>
      val (last, claims) = events.foldLeft((initial, 0)) { case ((before, claims), event) =>
        val (after, command) = step(before, event)
        if (!live(before)) {
          after shouldBe before
          command shouldBe Command.None
        }
        (after, claims + (if (command == Command.None) 0 else 1))
      }
      claims shouldBe (if (events.isEmpty) 0 else 1)
      live(last) shouldBe events.isEmpty
    }
  }

  it should "saturate positive demand without changing ignored inputs" in {
    forAll(Gen.choose(0L, Long.MaxValue), Gen.choose(Long.MinValue, Long.MaxValue)) { (current, incoming) =>
      val expected = if (incoming <= 0) current
      else (BigInt(current) + BigInt(incoming)).min(BigInt(Long.MaxValue)).toLong
      addDemand(current, incoming) shouldBe expected
      if (current > 0) consumed(current) shouldBe (if (current == Long.MaxValue) current else current - 1)
    }
    addDemand(Long.MaxValue - 1, 2) shouldBe Long.MaxValue
    addDemand(Long.MaxValue, Long.MaxValue) shouldBe Long.MaxValue
    consumed(Long.MaxValue) shouldBe Long.MaxValue
  }
}
