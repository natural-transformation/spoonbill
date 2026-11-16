package spoonbill.internal

import PendingRegistryMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class PendingRegistryMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Pending ownership" should "separate duplicate, capacity and closed rejection without replacing an admitted owner" in {
    val first            = new Token
    val duplicate        = new Token
    val (full, accepted) = register(initial[String], "key", first, 1)
    accepted shouldBe Admission.Accepted
    register(full, "key", duplicate, 1) shouldBe (full, Admission.Duplicate)
    register(full, "other", duplicate, 1) shouldBe (full, Admission.AtCapacity)
    register(seal(full), "key", duplicate, 1)._2 shouldBe Admission.Closed
    register(full, "key", duplicate, 1, DuplicatePolicy.Replace)._2 shouldBe Admission.AtCapacity
    val (replaced, result) = register(full, "key", duplicate, 2, DuplicatePolicy.Replace)
    result shouldBe Admission.Accepted
    contains(replaced, "key", duplicate) shouldBe true
    take(replaced, "key", Some(first)) shouldBe (replaced, None)
  }

  it should "preserve sealed entries until disposal and distinguish a stale expiry after key reuse" in {
    val first            = new Token
    val next             = new Token
    val held             = register(initial[String], "key", first, 2)._1
    val (empty, retired) = take(held, "key", None)
    retired shouldBe Some(first)
    val reused = register(empty, "key", next, 2)._1
    take(reused, "key", Some(first)) shouldBe (reused, None)
    val sealedState = seal(reused)
    contains(sealedState, "key", next) shouldBe true
    seal(sealedState) shouldBe sealedState
    val (drained, taken) = take(sealedState, "key", Some(next))
    taken shouldBe Some(next)
    PendingRegistryMachine.size(drained) shouldBe 0
    register(drained, "late", first, 2)._2 shouldBe Admission.Closed
    take(closed[String], "key", None)._2 shouldBe None
  }

  it should "bound admitted keys and keep closure permanent across generated histories" in {
    forAll(Gen.listOf(Gen.zip(Gen.choose(0, 3), Gen.choose(0, 4)))) { events =>
      events.foldLeft((initial[Int], Set.empty[Int], false)) { case ((state, owned, sealedState), (event, key)) =>
        val (next, expected, closedNow) = event match {
          case 0 =>
            val (next, decision) = register(state, key, new Token, 3)
            if (sealedState) decision shouldBe Admission.Closed
            else if (owned(key)) decision shouldBe Admission.Duplicate
            else if (owned.size == 3) decision shouldBe Admission.AtCapacity
            else decision shouldBe Admission.Accepted
            (next, if (decision == Admission.Accepted) owned + key else owned, sealedState)
          case 1 =>
            val (next, taken) = take(state, key, None)
            taken.nonEmpty shouldBe owned(key)
            (next, owned - key, sealedState)
          case 2 => (seal(state), owned, true)
          case _ => (closed[Int], Set.empty[Int], true)
        }
        PendingRegistryMachine.size(next) shouldBe expected.size
        PendingRegistryMachine.size(next) should be <= 3
        (next, expected, closedNow)
      }
    }
  }
}
