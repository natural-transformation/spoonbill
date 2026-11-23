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
  private type R = State[String, Long, String]
  private val lifetime = 10L
  private def renderedAt(keys: (String, Long)*): R =
    keys.foldLeft(State[String, Long, String]()) { case (state, (key, at)) => rendered(state, key, at) }
  private def record(state: R, key: String): Option[FreshPage] = state.slots.get(key).flatMap(_.fresh)

  "A fresh-page record" should "be fresh up to its lifetime and expired after it, by the time of the claim" in {
    val state = renderedAt("p" -> 100L)
    claimFresh(state, "p", 110L, lifetime, takeExpired = false) shouldBe (State[String, Long, String](), FreshClaim.Fresh)
    claimFresh(state, "p", 111L, lifetime, takeExpired = false) shouldBe (state, FreshClaim.Expired)
    claimFresh(State[String, Long, String](), "p", 100L, lifetime, takeExpired = false)._2 shouldBe FreshClaim.Absent
  }

  it should "be left in place by a poll that finds it expired, and taken by a WebSocket" in {
    val state = renderedAt("p" -> 100L)
    claimFresh(state, "p", 200L, lifetime, takeExpired = false)._1 shouldBe state
    val (taken, claim) = claimFresh(state, "p", 200L, lifetime, takeExpired = true)
    claim shouldBe FreshClaim.Expired
    taken shouldBe State[String, Long, String]()
  }

  it should "be ended by exactly one of a claim and an expiry, in both orders" in {
    val state = renderedAt("p" -> 100L)
    // The claim first: the expiry finds nothing. A claimed record cannot expire.
    val (claimed, _) = claimFresh(state, "p", 105L, lifetime, takeExpired = true)
    expireFresh(claimed, 500L, lifetime, 100, removes = true) shouldBe (claimed, Vector.empty)
    // The expiry first: the claim finds the record releasing, and the storage is not read.
    val (releasing, out) = expireFresh(state, 500L, lifetime, 100, removes = true)
    out shouldBe Vector("p")
    claimFresh(releasing, "p", 500L, lifetime, takeExpired = true) shouldBe (releasing, FreshClaim.Releasing)
  }

  it should "be given out for its removal once, and end when the removal has returned" in {
    val (releasing, out) = expireFresh(renderedAt("p" -> 100L), 500L, lifetime, 100, removes = true)
    out shouldBe Vector("p")
    record(releasing, "p") shouldBe Some(FreshPage.Releasing(100L))
    expireFresh(releasing, 900L, lifetime, 100, removes = true) shouldBe (releasing, Vector.empty)
    // A render cannot make the record waiting again while its state is removed.
    rendered(releasing, "p", 600L) shouldBe releasing
    freshReleased(releasing, "p") shouldBe State[String, Long, String]()
    // Only a releasing record is ended this way.
    val waiting = renderedAt("q" -> 100L)
    freshReleased(waiting, "q") shouldBe waiting
  }

  it should "come back when its removal failed, so that a later expiry gives it out again" in {
    val (releasing, _) = expireFresh(renderedAt("p" -> 100L), 500L, lifetime, 100, removes = true)
    val back           = freshRemovalFailed(releasing, "p")
    record(back, "p") shouldBe Some(FreshPage.Waiting(100L))
    expireFresh(back, 600L, lifetime, 100, removes = true)._2 shouldBe Vector("p")
    freshRemovalFailed(renderedAt("q" -> 1L), "q") shouldBe renderedAt("q" -> 1L)
  }

  it should "expire the oldest first, at most the limit, and none within its lifetime" in {
    val state = renderedAt("a" -> 100L, "b" -> 101L, "c" -> 102L, "d" -> 150L)
    val (first, out1) = expireFresh(state, 120L, lifetime, 2, removes = true)
    out1 shouldBe Vector("a", "b")
    val (second, out2) = expireFresh(first, 120L, lifetime, 2, removes = true)
    out2 shouldBe Vector("c")
    record(second, "d") shouldBe Some(FreshPage.Waiting(150L))
    expireFresh(second, 120L, lifetime, 2, removes = true)._2 shouldBe empty
  }

  it should "just end without a removal when the storage is not local" in {
    val state            = renderedAt("a" -> 100L, "b" -> 150L)
    val (ended, removed) = expireFresh(state, 120L, lifetime, 100, removes = false)
    removed shouldBe empty
    record(ended, "a") shouldBe None
    record(ended, "b") shouldBe Some(FreshPage.Waiting(150L))
    claimFresh(ended, "a", 120L, lifetime, takeExpired = true)._2 shouldBe FreshClaim.Absent
  }

  it should "be reserved while its state is stored: no expiry takes it, a claim finds none, and only a stored state makes it a record" in {
    // The review at fe313e1a, finding 2.
    val reserved = storing(renderedAt("old" -> 100L), "p")
    record(reserved, "p") shouldBe Some(FreshPage.Storing)
    reserved.freshOrder.keys.toVector shouldBe Vector("old")
    // An expiry takes the old record and never the reservation, however late.
    expireFresh(reserved, 10000L, lifetime, 100, removes = true)._2 shouldBe Vector("old")
    // A claim finds no record, and leaves the reservation.
    claimFresh(reserved, "p", 101L, lifetime, takeExpired = true) shouldBe (reserved, FreshClaim.Absent)
    // The state is stored: the record and its lifetime start then.
    val made = rendered(reserved, "p", 5000L)
    record(made, "p") shouldBe Some(FreshPage.Waiting(5000L))
    made.freshOrder.keys.toVector shouldBe Vector("old", "p")
    storeEnded(made, "p") shouldBe (made, false)
    // The store failed or was cancelled: the reservation ends, and says so once.
    val (ended, wasReserved) = storeEnded(reserved, "p")
    (record(ended, "p"), wasReserved) shouldBe (None, true)
    storeEnded(ended, "p") shouldBe (ended, false)
    // A record whose state is being removed is not reserved again.
    val (releasing, _) = expireFresh(renderedAt("p" -> 100L), 500L, lifetime, 100, removes = true)
    storing(releasing, "p") shouldBe releasing
  }

  it should "not be pushed out by any number of other records" in {
    val many = (1 to 20000).foldLeft(renderedAt("first" -> 0L)) { (state, n) => rendered(state, s"p$n", n.toLong) }
    claimFresh(many, "first", 5L, lifetime, takeExpired = false)._2 shouldBe FreshClaim.Fresh
  }

  it should "leave the other fields of its session alone" in {
    val owned = claim(renderedAt("p" -> 100L), "p", 1L)._1
    val taken = claimFresh(owned, "p", 105L, lifetime, takeExpired = false)._1
    taken.slots("p").owner shouldBe Some(1L)
    release(taken, "p", 1L) shouldBe State[String, Long, String]()
  }

  // The review of PR 42 measured a VectorMap here: it kept a slot for every
  // key that it had ever held while it was not empty, one for each render.
  "The order of the fresh-page records" should "keep nothing for a record that was claimed or expired" in {
    val lifetime = 60L * 1000000000L
    var state    = rendered(State[String, Long, String](), "page-0", 0L)
    var now      = 0L
    for (render <- 1 to 100000) {
      now += 1000L
      state = rendered(state, s"page-$render", now)
      state = claimFresh(state, s"page-${render - 1}", now, lifetime, takeExpired = true)._1
    }
    state.freshOrder.size shouldBe 1
    state.freshOrder.held shouldBe 1
    // One render a second, each followed by the cleanup: the records of one lifetime.
    state = State[String, Long, String]()
    now = 0L
    for (render <- 1 to 20000) {
      now += 1000000000L
      state = rendered(state, s"page-$render", now)
      state = expireFresh(state, now, lifetime, limit = 100, removes = false)._1
    }
    state.freshOrder.size shouldBe 61
    state.freshOrder.held shouldBe 61
    // A render of a page that has a record moves it to the end, and keeps one entry.
    val again = rendered(rendered(rendered(State[String, Long, String](), "a", 1L), "b", 2L), "a", 3L)
    again.freshOrder.keys.toVector shouldBe Vector("b", "a")
    again.freshOrder.held shouldBe 2
  }

  "The order of the guarded records" should "keep nothing for a record that was taken" in {
    var state = remember(State[String, Long, String](), "page-0", 0L, "/", 5000)
    for (render <- 1 to 100000) {
      state = remember(state, s"page-$render", render.toLong, "/", 5000)
      state = take(state, s"page-${render - 1}")._1
    }
    state.bootstrapOrder.size shouldBe 1
    state.bootstrapOrder.held shouldBe 1
  }
}
