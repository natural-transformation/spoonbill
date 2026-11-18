package spoonbill.effect

import CompletionLatchMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class CompletionLatchMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "A completion latch" should "register before completion, publish once, and replay after completion" in {
    step(State.Pending, Event.Observe) shouldBe (State.Pending, Command.Register)
    step(State.Pending, Event.Complete) shouldBe (State.Completed, Command.Publish)
    step(State.Completed, Event.Observe) shouldBe (State.Completed, Command.Replay)
    step(State.Completed, Event.Complete) shouldBe (State.Completed, Command.Ignore)
  }

  it should "complete every earlier observer once and replay without replacing the result across generated histories" in {
    forAll(Gen.listOf(Gen.oneOf(Event.Observe, Event.Complete))) { events =>
      val (state, pending, notified, observers, publications) = events.foldLeft((State.Pending, 0, 0, 0, 0)) {
        case ((state, pending, notified, observers, publications), event) =>
          val (next, command) = step(state, event)
          val result = command match {
            case Command.Register => (next, pending + 1, notified, observers + 1, publications)
            case Command.Replay   => (next, pending, notified + 1, observers + 1, publications)
            case Command.Publish  => (next, 0, notified + pending, observers, publications + 1)
            case Command.Ignore   => (next, pending, notified, observers, publications)
          }
          if (state == State.Completed) next shouldBe State.Completed
          result._5 should be <= 1
          result._2 + result._3 shouldBe result._4
          result
      }
      pending + notified shouldBe observers
      if (state == State.Completed) {
        pending shouldBe 0
        publications shouldBe 1
      } else publications shouldBe 0
    }
  }
}
