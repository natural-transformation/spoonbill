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
    completed(cleaning, successful = true).phase shouldBe Phase.Cleaned
    completed(cleaning, successful = false).phase shouldBe Phase.CleanupFailed

    val retiredBeforeOpen = retire(gated)
    beginCleanup(retiredBeforeOpen) shouldBe (retiredBeforeOpen, false)
    val failed                   = predecessorCompleted(retiredBeforeOpen, successful = false, reused = true)
    val (failedCleanup, claimed) = beginCleanup(failed)
    claimed shouldBe true
    cleanup(failedCleanup, registryClosed = true, deleteOnRetire = false) shouldBe Cleanup.PropagateFailure
  }

  it should "validate per-owner baselines and make overflow an explicit admission-retiring outcome" in {
    val opened = predecessorCompleted(OwnerState(), successful = true, reused = false)
    acceptsSnapshot(opened, 1L, false, false, None, sameVersion = true) shouldBe true
    acceptsSnapshot(opened, 1L, false, false, None, sameVersion = false) shouldBe false
    acceptsSnapshot(opened, 1L, false, false, Some(Baseline(1L, 0L)), sameVersion = false) shouldBe true
    acceptsSnapshot(opened, 1L, false, false, Some(Baseline(2L, 0L)), sameVersion = true) shouldBe false
    val (established, overflow) = establishBaseline(opened)
    overflow shouldBe false
    established.baseline.revision shouldBe 1L
    establishBaseline(established) shouldBe (established, false)
    acceptsSnapshot(established, 1L, false, false, Some(Baseline(1L, 0L)), sameVersion = true) shouldBe false
    val reused = predecessorCompleted(OwnerState(), successful = true, reused = true)
    reused.baseline.revision shouldBe 1L
    acceptsSnapshot(reused, 2L, false, true, None, sameVersion = true) shouldBe false
    val (exhausted, failed) = establishBaseline(opened.copy(baseline = BaselineState.Unestablished(Long.MaxValue)))
    failed shouldBe true
    active(exhausted) shouldBe false
  }

  it should "keep retirement terminal and cleanup once-only across generated lifecycle histories" in {
    forAll(Gen.listOf(Gen.choose(0, 7))) { events =>
      events.foldLeft((OwnerState(), false, 0)) { case ((state, wasRetired, cleanups), event) =>
        val (next, claimed) = event match {
          case 0 => (predecessorCompleted(state, successful = true, reused = false), false)
          case 1 => (predecessorCompleted(state, successful = false, reused = false), false)
          case 2 => (admitted(state), false)
          case 3 => (released(state), false)
          case 4 => (retire(state), false)
          case 5 => beginCleanup(state)
          case 6 => (completed(state, successful = true), false)
          case _ => (establishBaseline(state)._1, false)
        }
        val retiredNow = wasRetired || event == 4
        if (retiredNow) active(next) shouldBe false
        next.admitted should be >= 0
        val started = cleanups + (if (claimed) 1 else 0)
        started should be <= 1
        (next, retiredNow, started)
      }
    }
  }
}
