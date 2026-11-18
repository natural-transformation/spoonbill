package spoonbill.effect

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class PoolRetirementMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  import PoolRetirementMachine.*

  "Retirement ownership" should "allow retries only after failed startup or actual attempt settlement" in {
    val first    = new Attempt
    val next     = new Attempt
    val starting = claim(State.Ready, first)
    claim(starting, next) shouldBe starting
    enter(starting, next) shouldBe starting
    settled(starting, first) shouldBe State.Ready // child exit before entry
    val running = enter(starting, first)
    abandon(running, first) shouldBe running
    settled(running, next) shouldBe running
    val retry = claim(settled(running, first), next)
    enter(retry, first) shouldBe retry
    abandon(retry, first) shouldBe retry
    settled(retry, first) shouldBe retry
    enter(retry, next) shouldBe State.Running(next)
    enter(abandon(starting, first), first) shouldBe State.Ready
  }

  it should "agree with an independent ownership ledger across stale and competing actions" in {
    forAll(Gen.listOf(Gen.choose(0, 15))) { history =>
      val owners       = Vector.fill(4)(new Attempt)
      var state: State = State.Ready
      // None is unclaimed; Boolean distinguishes dispatch reservation from entry.
      var ledger       = Option.empty[(Int, Boolean)]
      var reservations = 0
      var entries      = 0
      for (action <- history) {
        val owner     = action % 4
        val operation = action / 4
        val prior     = state
        state = operation match {
          case 0 => claim(state, owners(owner))
          case 1 => enter(state, owners(owner))
          case 2 => abandon(state, owners(owner))
          case 3 => settled(state, owners(owner))
        }
        operation match {
          case 0 if ledger.isEmpty                  => ledger = Some(owner -> false); reservations += 1
          case 1 if ledger.contains(owner -> false) => ledger = Some(owner -> true); entries += 1
          case 2 if ledger.contains(owner -> false) => ledger = None
          case 3 if ledger.exists(_._1 == owner)    => ledger = None
          case _                                    => state shouldBe prior
        }
        val expected = ledger match {
          case None              => State.Ready
          case Some((id, false)) => State.Starting(owners(id))
          case Some((id, true))  => State.Running(owners(id))
        }
        state shouldBe expected
        entries should be <= reservations
      }
    }
  }
}
