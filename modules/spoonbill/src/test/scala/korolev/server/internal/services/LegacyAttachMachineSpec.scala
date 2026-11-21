package spoonbill.server.internal.services

import LegacyAttachMachine.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LegacyAttachMachineSpec extends AnyFlatSpec with Matchers {
  private val both = List(false, true)

  "An attach without an owner" should "take the registered view, as a WebSocket attach always did" in {
    for (createdHere <- both; registered <- both; sameOwner <- both)
      decide(checked = false, createdHere, registered, sameOwner) shouldBe Decision.Attach
  }

  "An attach with an owner" should "use the view that it built itself" in {
    for (registered <- both; sameOwner <- both)
      decide(checked = true, createdHere = true, registered, sameOwner) shouldBe Decision.Attach
  }

  it should "use a view that another request of its owner built" in {
    decide(checked = true, createdHere = false, registered = true, sameOwner = true) shouldBe Decision.Attach
  }

  it should "wait for a view of another owner to leave" in {
    decide(checked = true, createdHere = false, registered = true, sameOwner = false) shouldBe Decision.AwaitDeparture
  }

  it should "attach again when the view has left the registry already" in {
    for (sameOwner <- both)
      decide(checked = true, createdHere = false, registered = false, sameOwner) shouldBe Decision.Retry
  }
}
