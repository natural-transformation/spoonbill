package spoonbill.state

import BoundedPresentationStore.{Error, Limits}
import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.Qsid
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.ViewFence

class PresentationMachineSpec extends AnyFlatSpec with Matchers {
  import PresentationMachine.*
  private val now     = Instant.parse("2026-10-11T00:00:00Z")
  private val viewKey = Qsid("device", "view")
  private def fence(epoch: Long, view: Long = 1): ViewFence = ViewFence(
    ViewSessionId.fromUuid(new UUID(0, view)),
    ViewOwnerId.fromUuid(new UUID(0, epoch)),
    ViewOwnershipEpoch.fromLong(epoch).toOption.get
  )

  "Presentation ownership" should "detach old manager tokens while retaining the lease's invalidation identity" in {
    val firstToken        = new Object
    val leaseToken        = new Object
    val disconnectedToken = new Object
    val boot              = bootstrap(firstToken, now.plusSeconds(10), Map("node" -> "ordinary"), true)
    val pending           = claim(boot, leaseToken, fence(1), now.plusSeconds(5))
    access(pending, firstToken, now) shouldBe Access.Stale
    val active = publish(pending)
    access(active, leaseToken, Instant.MAX) shouldBe Access.Current
    val held = disconnect(active, disconnectedToken, now.plusSeconds(5))
    access(held, leaseToken, now) shouldBe Access.Stale
    access(held, disconnectedToken, now) shouldBe Access.Stale
    held.leaseToken shouldBe Some(leaseToken)
    held.fence shouldBe Some(fence(1))
    held.nodes shouldBe boot.nodes
    held.resetBrowser shouldBe true
  }

  it should "distinguish stale tokens from expired current tokens at equality" in {
    val token = new Object
    val entry = bootstrap(token, now, (), false)
    access(entry, new Object, now) shouldBe Access.Stale
    access(entry, token, now.minusNanos(1)) shouldBe Access.Current
    access(entry, token, now) shouldBe Access.Expired
    observe(now, now.minusSeconds(1)) shouldBe now
  }

  "Presentation history" should "preserve rejection precedence and never reopen after close" in {
    val held = remember(initial, viewKey, fence(2))
    claimFenceError(held, viewKey, fence(2, view = 2), 1) shouldBe Some(Error.WrongView)
    claimFenceError(held, viewKey, fence(2), 1) shouldBe Some(Error.StaleOwner)
    claimFenceError(held, viewKey, fence(3), 1) shouldBe None
    claimFenceError(held, Qsid("device", "other"), fence(0), 1) shouldBe Some(Error.Capacity)
    val stopped = close(held)
    history(stopped) shouldBe empty
    remember(stopped, viewKey, fence(3)) shouldBe stopped
    reclaim(stopped, Set(viewKey)) shouldBe stopped
    claimFenceError(stopped, viewKey, fence(3), 1) shouldBe Some(Error.Closed)
  }

  it should "charge pending replacements only for capacity they add" in {
    val limits = Limits(active = 2, pending = 1)
    claimCapacityError(Phase.Pending, 1, 1, limits) shouldBe None
    claimCapacityError(Phase.Active, 1, 1, limits) shouldBe Some(Error.Capacity)
    claimCapacityError(Phase.Bootstrap, 0, 2, limits) shouldBe Some(Error.Capacity)
    claimCapacityError(Phase.Disconnected, 0, 1, limits) shouldBe None
    canWrite(true, 2, 2) shouldBe true
    canWrite(false, 2, 2) shouldBe false
    bootstrapError(true, 1, 1) shouldBe Some(Error.AlreadyExists)
  }
}
