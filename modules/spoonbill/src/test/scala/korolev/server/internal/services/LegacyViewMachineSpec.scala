package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LegacyViewMachineSpec extends AnyFlatSpec with Matchers {
  import LegacyViewMachine.*

  "A registered view" should "be awaited from the first attach that waits for it" in {
    awaited(initial) shouldBe false
    val (waited, command) = step(initial, Event.Waited)
    command shouldBe Command.Ignore
    awaited(waited) shouldBe true
    // A second attach that waits changes nothing.
    step(waited, Event.Waited) shouldBe (waited, Command.Ignore)
  }

  it should "tell the attaches that wait when it has left, and when its retirement failed" in {
    for (start <- List(initial, State.Registered(awaited = true))) {
      step(start, Event.Departed) shouldBe (State.Left, Command.Publish)
      step(start, Event.Failed) shouldBe (State.Stuck, Command.Publish)
    }
  }

  "A view that has left or is stuck" should "keep its first end and tell nobody again" in {
    for (ended <- List(State.Left, State.Stuck); event <- Event.values) {
      step(ended, event) shouldBe (ended, Command.Ignore)
      awaited(ended) shouldBe false
    }
  }
}
