package spoonbill.server.internal.services

import LegacyAttachMachine.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LegacyAttachMachineSpec extends AnyFlatSpec with Matchers {
  private val both = List(false, true)

  "An attach without an owner" should "take the registered view, as a WebSocket attach always did" in {
    for (superseded <- both; createdHere <- both; registered <- both; sameOwner <- both)
      decide(checked = false, superseded, createdHere, registered, sameOwner) shouldBe Decision.Attach
  }

  "An attach with an owner" should "use the view that it built itself" in {
    for (registered <- both; sameOwner <- both)
      decide(checked = true, superseded = false, createdHere = true, registered, sameOwner) shouldBe Decision.Attach
  }

  it should "use a view that another request of its owner built" in {
    decide(checked = true, superseded = false, createdHere = false, registered = true, sameOwner = true) shouldBe
      Decision.Attach
  }

  it should "wait for a view of another owner to leave" in {
    decide(checked = true, superseded = false, createdHere = false, registered = true, sameOwner = false) shouldBe
      Decision.AwaitDeparture
  }

  it should "attach again when the view has left the registry already" in {
    for (sameOwner <- both)
      decide(checked = true, superseded = false, createdHere = false, registered = false, sameOwner) shouldBe Decision.Retry
  }

  "An attach whose owner was replaced" should "get no view, whatever the registry gave it" in {
    // Not a view to wait for, not a view of its own owner, and not the view that it has just built.
    for (createdHere <- both; registered <- both; sameOwner <- both)
      decide(checked = true, superseded = true, createdHere, registered, sameOwner) shouldBe Decision.Superseded
  }

  "An attach" should "fill the registry unless its owner was replaced" in {
    mayFill(checked = true, superseded = true) shouldBe false
    mayFill(checked = true, superseded = false) shouldBe true
    // Without an owner nothing can be replaced.
    for (superseded <- both) mayFill(checked = false, superseded) shouldBe true
  }

  "An attach whose view was rolled back" should "fail, also when the page goes on" in {
    for (checked <- both; pageGoesOn <- both)
      startsAgain(checked, ranFill = true, pageGoesOn) shouldBe false
  }

  "An attach that waited for a view which was rolled back" should "start again when it has an owner and the page goes on" in {
    startsAgain(checked = true, ranFill = false, pageGoesOn = true) shouldBe true
  }

  it should "fail with that view otherwise" in {
    // Without an owner it behaves as a WebSocket attach always did.
    startsAgain(checked = false, ranFill = false, pageGoesOn = true) shouldBe false
    // Nothing says that the failure belongs to a transport that is over.
    for (checked <- both)
      startsAgain(checked, ranFill = false, pageGoesOn = false) shouldBe false
  }
}
