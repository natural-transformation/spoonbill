package spoonbill.auth

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

final class BrowserViewMachineSpec extends AnyFlatSpec with Matchers {
  import BrowserViewMachine.*

  "Browser view membership" should "retain ownership through preparation, retirement and uncertain release" in {
    owns(initial) shouldBe true
    BrowserViewMachine.live(initial) shouldBe false
    attached(initial) shouldBe false
    val prepared = prepare(initial, reconstructed = true)
    prepared.phase shouldBe Phase.Prepared
    BrowserViewMachine.live(prepared) shouldBe false
    val live = publish(prepared)
    attached(live) shouldBe true
    BrowserViewMachine.live(live) shouldBe true
    retiring(live) shouldBe false
    retainOnGuardClose(live) shouldBe false
    val ready = initialized(live)
    retainOnGuardClose(ready) shouldBe true
    val detached = detach(ready, Retirement.Release)
    attached(detached) shouldBe false
    owns(detached) shouldBe true
    retainOnGuardClose(detached) shouldBe false
    val released = releaseAcknowledged(detached)
    owns(released) shouldBe false
    attached(released) shouldBe false
    prepare(released, reconstructed = false) shouldBe released
    publish(released) shouldBe released
    releaseAcknowledged(live) shouldBe live
  }

  it should "distinguish attached retirement from detachment and schedule it only once" in {
    val live = publish(prepare(initial, reconstructed = false))
    for (reason <- Seq(Retirement.AuthorityDenied, Retirement.ReplacementCompleted)) {
      val (marked, won) = retire(live, reason)
      won shouldBe true
      marked.phase shouldBe Phase.RetiringAttached
      attached(marked) shouldBe true
      retiring(marked) shouldBe true
      BrowserViewMachine.live(marked) shouldBe false
      retire(marked, Retirement.Release) shouldBe (marked -> false)
      val detached = detach(marked, Retirement.Release)
      detached.phase shouldBe Phase.RetiringDetached
      detached.retirement shouldBe Some(reason)
      owns(detached) shouldBe true
    }
    val failedSetup = detach(initial, Retirement.Release)
    failedSetup.phase shouldBe Phase.RetiringDetached
    owns(failedSetup) shouldBe true
  }

  "Guard release" should "retry its execution claim without reopening authority or retained presentation" in {
    val live             = publish(prepare(initial, reconstructed = false))
    val (claimed, first) = claimGuardClose(live)
    first shouldBe true
    claimGuardClose(claimed) shouldBe (claimed -> false)
    retainOnGuardClose(claimed) shouldBe true
    val failed = retryGuardClose(detach(claimed, Retirement.Release))
    failed.guardRelease shouldBe GuardRelease.Available
    failed.phase shouldBe Phase.RetiringDetached
    retainOnGuardClose(failed) shouldBe false
    claimGuardClose(failed)._2 shouldBe true
  }

  "Initialization acknowledgement" should "remain independent of live admission and never reopen a retired view" in {
    val reconstructed = publish(prepare(initial, reconstructed = true))
    attached(reconstructed) shouldBe true
    retiring(reconstructed) shouldBe false
    val late = initialized(releaseAcknowledged(detach(reconstructed, Retirement.Release)))
    late.initialization shouldBe Initialization.Ready
    late.phase shouldBe Phase.Released
    retainOnGuardClose(late) shouldBe false
    initialized(late) shouldBe late
  }

  "Claim admission" should "bound ownership before dispatch without overflowing the combined limit" in {
    canClaim(false, 4, 2, 3) shouldBe true
    canClaim(false, 5, 2, 3) shouldBe false
    canClaim(true, 0, 2, 3) shouldBe false
    canClaim(false, Int.MaxValue, Int.MaxValue, Int.MaxValue) shouldBe true
  }
}
