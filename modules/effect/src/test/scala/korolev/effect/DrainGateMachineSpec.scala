package spoonbill.effect

import DrainGateMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class DrainGateMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Drain admission" should "distinguish capacity refusal from permanent close and drain only admitted work" in {
    admission(initial, Some(1)) shouldBe Admission.Accepted
    val active = entered(initial)
    admission(active, Some(1)) shouldBe Admission.AtCapacity
    admission(active, None) shouldBe Admission.Accepted
    drained(finished(active)) shouldBe false
    val closing = close(active)
    admission(closing, None) shouldBe Admission.Closed
    entered(closing) shouldBe closing
    drained(closing) shouldBe false
    close(closing) shouldBe closing
    val done = finished(closing)
    drained(done) shouldBe true
    admission(done, Some(0)) shouldBe Admission.Closed
    close(done) shouldBe done
    drained(close(initial)) shouldBe true
  }

  it should "preserve signed Int arithmetic and closure across the complete counter representation" in {
    for (
      value   <- Vector(Int.MinValue, Int.MinValue + 1, -1, 0, 1, Int.MaxValue - 1, Int.MaxValue);
      closing <- Vector(false, true)
    ) {
      val state = withCount(if (closing) close(initial) else initial, value)
      count(state) shouldBe value
      count(finished(state)) shouldBe (value - 1)
      if (closing) {
        entered(state) shouldBe state
        changed(state, entered(state)) shouldBe false
        admission(finished(state), None) shouldBe Admission.Closed
      } else {
        count(entered(state)) shouldBe (value + 1)
        changed(state, entered(state)) shouldBe true
        admission(finished(state), None) shouldBe Admission.Accepted
      }
    }
  }

  it should "conserve outstanding permits and make close permanent across generated histories" in {
    forAll(Gen.choose(1, 30), Gen.listOf(Gen.choose(0, 2))) { (capacity, events) =>
      events.foldLeft((initial, 0, false)) { case ((state, outstanding, closed), event) =>
        val (next, expected, closing) = event match {
          case 0 =>
            val decision = admission(state, Some(capacity))
            if (closed) decision shouldBe Admission.Closed
            else if (outstanding == capacity) decision shouldBe Admission.AtCapacity
            else decision shouldBe Admission.Accepted
            if (decision == Admission.Accepted) (entered(state), outstanding + 1, closed)
            else (state, outstanding, closed)
          // A finish is supplied only by an owned, once-only release in each interpreter.
          case 1 if outstanding > 0 => (finished(state), outstanding - 1, closed)
          case 1                    => (state, outstanding, closed)
          case _                    => (close(state), outstanding, true)
        }
        count(next) shouldBe expected
        drained(next) shouldBe (closing && expected == 0)
        (next, expected, closing)
      }
    }
  }
}
