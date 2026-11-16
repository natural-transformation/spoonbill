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
    finished(done) shouldBe done
    finished(initial) shouldBe initial
    drained(close(initial)) shouldBe true
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
          case 1 => (finished(state), math.max(0, outstanding - 1), closed)
          case _ => (close(state), outstanding, true)
        }
        count(next) shouldBe expected
        drained(next) shouldBe (closing && expected == 0)
        (next, expected, closing)
      }
    }
  }
}
