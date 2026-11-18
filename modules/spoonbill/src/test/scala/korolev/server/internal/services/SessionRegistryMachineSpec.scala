package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SessionRegistryMachineSpec extends AnyFlatSpec with Matchers {
  import SessionRegistryMachine.*

  "Session metadata" should "retain failed removal across attachment release and fence stale owner cleanup" in {
    val empty             = State[String, Long, String]()
    val (owned, admitted) = claim(empty, "view", 1L)
    admitted shouldBe true
    claim(owned, "view", 2L) shouldBe (owned, false)
    val failed   = removalFailed(owned, "view")
    val released = release(failed, "view", 1L)
    needsRemoval(released, "view") shouldBe true
    val next = claim(released, "view", 2L)._1
    release(next, "view", 1L) shouldBe next
    val repaired = removalSucceeded(next, "view")
    needsRemoval(repaired, "view") shouldBe false
    claim(repaired, "view", 3L)._2 shouldBe false
    release(repaired, "view", 2L) shouldBe empty
  }

  it should "bound read-once bootstrap paths in insertion order without evicting ownership or removal retry" in {
    val first     = remember(State[String, Long, String](), "a", 0L, "/a", 2)
    val second    = remember(first, "b", 1L, "/b", 2)
    val refreshed = remember(second, "a", 2L, "/new-a", 2)
    val owned     = claim(refreshed, "a", 1L)._1
    val failed    = removalFailed(owned, "a")
    val third     = remember(failed, "c", 3L, "/c", 2)
    third.bootstrapOrder.keys.toVector shouldBe Vector("b", "c")
    take(third, "a") shouldBe (third, None)
    needsRemoval(third, "a") shouldBe true
    claim(third, "a", 2L)._2 shouldBe false
    val (consumed, path) = take(third, "b")
    path shouldBe Some(1L -> "/b")
    take(consumed, "b") shouldBe (consumed, None)
    val reused = remember(consumed, "b", 4L, "/fresh-b", 2)
    reused.bootstrapOrder.keys.toVector shouldBe Vector("c", "b")
  }

  it should "consume paths before separate expiry validation, preserving the inclusive duration boundary" in {
    val remembered       = remember(State[String, Long, String](), "a", Long.MaxValue - 5L, "/a", 1)
    val (consumed, path) = take(remembered, "a")
    fresh(path.get._1, Long.MinValue + 4L, 10L) shouldBe true
    fresh(path.get._1, Long.MinValue + 5L, 10L) shouldBe false
    consumed.slots shouldBe empty
    take(consumed, "a")._2 shouldBe None
    fresh(100L, 90L, 10L) shouldBe true
  }
}
