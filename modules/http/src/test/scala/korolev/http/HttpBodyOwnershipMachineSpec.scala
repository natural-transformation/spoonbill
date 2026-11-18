package spoonbill.http

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class HttpBodyOwnershipMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import HttpBodyOwnershipMachine.*

  "HTTP body ownership" should "keep abandonment sticky and reject old-owner cancellation" in {
    val first = new Owner
    val second = new Owner
    HttpBodyOwnershipMachine.pending(initial) shouldBe false
    val reading = started(initial, first)
    owner(reading) shouldBe Some(first)
    HttpBodyOwnershipMachine.pending(reading) shouldBe true
    completed(reading) shouldBe initial
    val next = started(completed(reading), second)
    canceled(next, first) shouldBe next
    val abandoned = canceled(next, second)
    HttpBodyOwnershipMachine.pending(abandoned) shouldBe true
    val ended = completed(abandoned)
    owner(ended) shouldBe None
    HttpBodyOwnershipMachine.pending(ended) shouldBe true
    HttpBodyOwnershipMachine.pending(started(ended, first)) shouldBe true
  }

  it should "match the existing owner and abandonment rules over generated histories" in {
    val owners = Vector(new Owner, new Owner)
    forAll(Gen.listOf(Gen.choose(0, 4))) { events =>
      events.foldLeft((initial, Option.empty[Owner], false)) { case ((state, current, abandoned), event) =>
        val (next, expectedOwner, expectedAbandoned) = event match {
          case 0 | 1 => (started(state, owners(event)), Some(owners(event)), abandoned)
          case 2 => (completed(state), None, abandoned)
          case index =>
            val incoming = owners(index - 3)
            (canceled(state, incoming), current, abandoned || current.exists(_ eq incoming))
        }
        owner(next) shouldBe expectedOwner
        HttpBodyOwnershipMachine.pending(next) shouldBe (expectedOwner.nonEmpty || expectedAbandoned)
        (next, expectedOwner, expectedAbandoned)
      }
    }
  }
}
