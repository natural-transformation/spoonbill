package spoonbill.security.transaction

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OneUseLeaseMachineSpec extends AnyFlatSpec with Matchers {
  import OneUseLeaseMachine.*

  "A canonical authority lease" should "allow one issue followed by one admission" in {
    admit(State.Fresh) shouldBe State.Fresh
    val issued = issue(State.Fresh)
    issued shouldBe State.Issued
    issue(issued) shouldBe issued
    val admitted = admit(issued)
    admitted shouldBe State.Admitted
    issue(admitted) shouldBe admitted
    admit(admitted) shouldBe admitted
  }

  it should "make expiration terminal from every phase" in {
    State.values.foreach { state =>
      val expired = expire(state)
      expired shouldBe State.Expired
      issue(expired) shouldBe State.Expired
      admit(expired) shouldBe State.Expired
      expire(expired) shouldBe State.Expired
    }
  }
}
