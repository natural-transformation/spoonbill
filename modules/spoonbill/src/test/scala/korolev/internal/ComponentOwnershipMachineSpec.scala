package spoonbill.internal

import ComponentOwnershipMachine.*
import avocet.Id
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ComponentOwnershipMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Component registry decisions" should "preserve successor ownership, failed fences and explicit baseline versions" in {
    val address             = Id("1_2")
    val version             = new Version
    val initial             = Registry(version)
    val (first, assignment) = bind(initial, address)
    assignment shouldBe Binding.Assigned(1L, None)
    val (second, successor) = bind(first, address)
    successor shouldBe Binding.Assigned(2L, Some(1L))
    val nextVersion = new Version
    val finished    = retired(second, address, 1L, successful = true, deleted = true, nextVersion)
    finished.tails shouldBe Map(address -> 2L)
    finished.live shouldBe Map(2L -> address)
    finished.version should be theSameInstanceAs nextVersion
    retired(finished, address, 2L, successful = false, deleted = true, new Version) shouldBe finished
    retired(finished, Id("1_3"), 2L, successful = true, deleted = true, new Version) shouldBe finished
    val closedRegistry = close(finished)
    bind(closedRegistry, Id("1_3")) shouldBe (closedRegistry, Binding.Closed)
    val exhausted = initial.copy(serial = Long.MaxValue)
    bind(exhausted, address) shouldBe (exhausted, Binding.Exhausted)
  }

  "Component owner decisions" should "wait for predecessor and admitted work before selecting cleanup once" in {
    val gated = OwnerState()
    active(gated) shouldBe true
    canAdmit(gated, false) shouldBe false
    val opened = predecessorCompleted(gated, successful = true, reused = false)
    canAdmit(opened, false) shouldBe true
    canAdmit(opened, true) shouldBe false
    val draining = retire(admitted(opened))
    active(draining) shouldBe false
    beginCleanup(draining) shouldBe (draining, false)
    val (cleaning, start) = beginCleanup(released(draining))
    start shouldBe true
    beginCleanup(cleaning) shouldBe (cleaning, false)
    cleanup(cleaning, registryClosed = false, deleteOnRetire = true) shouldBe Cleanup.DeleteNode
    cleanup(cleaning, registryClosed = true, deleteOnRetire = true) shouldBe Cleanup.SkipDelete
    cleanup(cleaning, registryClosed = false, deleteOnRetire = false) shouldBe Cleanup.SkipDelete
    phase(completed(cleaning, successful = true)) shouldBe Phase.Cleaned
    phase(completed(cleaning, successful = false)) shouldBe Phase.CleanupFailed

    val retiredBeforeOpen = retire(gated)
    beginCleanup(retiredBeforeOpen) shouldBe (retiredBeforeOpen, false)
    val failed                   = predecessorCompleted(retiredBeforeOpen, successful = false, reused = true)
    val (failedCleanup, claimed) = beginCleanup(failed)
    claimed shouldBe true
    cleanup(failedCleanup, registryClosed = true, deleteOnRetire = false) shouldBe Cleanup.PropagateFailure
  }

  it should "validate per-owner baselines using the structurally bounded zero, one and two revisions" in {
    val opened = predecessorCompleted(OwnerState(), successful = true, reused = false)
    acceptsSnapshot(opened, 1L, false, false, None, sameVersion = true) shouldBe true
    acceptsSnapshot(opened, 1L, false, false, None, sameVersion = false) shouldBe false
    acceptsSnapshot(opened, 1L, false, false, Some(Baseline(1L, 0L)), sameVersion = false) shouldBe true
    acceptsSnapshot(opened, 1L, false, false, Some(Baseline(2L, 0L)), sameVersion = true) shouldBe false
    val established = establishBaseline(opened)
    baselineRevision(established) shouldBe 1L
    establishBaseline(established) shouldBe established
    acceptsSnapshot(established, 1L, false, false, Some(Baseline(1L, 0L)), sameVersion = true) shouldBe false
    val reused = predecessorCompleted(OwnerState(), successful = true, reused = true)
    baselineRevision(reused) shouldBe 1L
    acceptsSnapshot(reused, 2L, false, true, None, sameVersion = true) shouldBe false
    val inheritedBaseline = establishBaseline(reused)
    baselineRevision(inheritedBaseline) shouldBe 2L
    establishBaseline(inheritedBaseline) shouldBe inheritedBaseline
    baselineRevision(predecessorCompleted(inheritedBaseline, successful = true, reused = true)) shouldBe 1L
  }

  it should "preserve every signed counter bit without corrupting phase or baseline" in {
    val opened = establishBaseline(predecessorCompleted(OwnerState(), successful = true, reused = true))
    for (value <- Vector(Int.MinValue, Int.MinValue + 1, -1, 0, 1, Int.MaxValue - 1, Int.MaxValue)) {
      val state = withAdmittedCount(opened, value)
      admittedCount(state) shouldBe value
      admittedCount(admitted(state)) shouldBe (value + 1)
      reserve(state, false) shouldBe admitted(state)
      changed(state, reserve(state, false)) shouldBe true
      reserve(state, true) shouldBe state
      admittedCount(released(state)) shouldBe (value - 1)
      phase(admitted(state)) shouldBe Phase.Open
      phase(released(state)) shouldBe Phase.Open
      baselineRevision(admitted(state)) shouldBe 2L
      baselineRevision(released(state)) shouldBe 2L
    }
    reserve(OwnerState(), false) shouldBe OwnerState()
    val retired = retire(opened)
    reserve(retired, false) shouldBe retired
  }

  it should "keep retirement terminal and cleanup once-only across generated lifecycle histories" in {
    forAll(Gen.listOf(Gen.choose(0, 7))) { events =>
      events.foldLeft((OwnerState(), false, 0)) { case ((state, wasRetired, cleanups), event) =>
        val (next, claimed) = event match {
          case 0                             => (predecessorCompleted(state, successful = true, reused = false), false)
          case 1                             => (predecessorCompleted(state, successful = false, reused = false), false)
          case 2                             => (admitted(state), false)
          case 3 if admittedCount(state) > 0 => (released(state), false)
          case 3                             => (state, false) // No unspent interpreter lease exists to release.
          case 4                             => (retire(state), false)
          case 5                             => beginCleanup(state)
          case 6                             => (completed(state, successful = true), false)
          case _                             => (establishBaseline(state), false)
        }
        val retiredNow = wasRetired || event == 4
        if (retiredNow) active(next) shouldBe false
        admittedCount(next) should be >= 0
        baselineRevision(next) should (be >= 0L and be <= 2L)
        val started = cleanups + (if (claimed) 1 else 0)
        started should be <= 1
        (next, retiredNow, started)
      }
    }
  }
}
