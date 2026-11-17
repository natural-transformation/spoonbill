package spoonbill.auth

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.Qsid
import spoonbill.security.Identifiers.ConnectionId

class MemoryBrowserRecordMachineSpec extends AnyFlatSpec with Matchers {
  import MemoryBrowserRecordMachine.*
  private val start      = Instant.parse("2026-01-01T00:00:00Z")
  private val bindingKey = Vector[Byte](1)
  private val qsid       = Qsid("device", "view")
  private val owner      = new UUID(1L, 1L)
  private val connection = ConnectionId.fromUuid(new UUID(2L, 2L))
  private val limits = BrowserPersistenceLimits(retention =
    BrowserRetentionPolicy(idleBindingSeconds = 3, idleViewSeconds = 4, historySeconds = 2, maxSessionSeconds = 600)
  )
  private val original = Records[Int, String](
    0,
    slots = Map(bindingKey -> BrowserReplacementSlot(4L, None)),
    views = Map(qsid -> View(bindingKey, Some(owner), 7L, connection, start)),
    bindingActivity = Map(bindingKey -> start)
  )

  "Memory reclamation" should "retain a live owner and advance fences when its idle history is removed" in {
    reap(original, start.plusSeconds(3), limits, 0, 1).toOption.get.records shouldBe original
    val retired = reap(original, start.plusSeconds(4), limits, 0, 1).toOption.get
    retired.records.views shouldBe empty
    retired.records.slots shouldBe empty
    retired.bindingFloor shouldBe 5L
    retired.viewFloor shouldBe 8L
    retired.removedViews shouldBe Set(qsid)
    original.views(qsid).owner shouldBe Some(owner)
  }

  it should "retire expired authority before reclaiming its retained session history" in {
    val id           = new UUID(3L, 3L)
    val completionId = new UUID(4L, 4L)
    val credential   = Vector[Byte](2)
    val authenticated = original.copy(
      slots = Map(bindingKey -> BrowserReplacementSlot(4L, Some(id))),
      sessions = Map(id -> Session(id, bindingKey, credential, 4L, start, completionId, Some(4L))),
      credentials = Map(credential -> id),
      completions = Map(completionId -> Completion(id, completionId, start, Some("delivery")))
    )
    val expired = reap(authenticated, start.plusSeconds(1), limits, 0, 1).toOption.get.records
    expired.slots(bindingKey) shouldBe BrowserReplacementSlot(5L, None)
    expired.views(qsid).owner shouldBe None
    expired.sessions.keySet shouldBe Set(id)
    expired.completions(completionId).material shouldBe None
    expired.credentials shouldBe authenticated.credentials
    val later = reap(expired, start.plusSeconds(2), limits, 0, 1).toOption.get.records
    later.sessions shouldBe empty
    later.completions shouldBe empty
    later.credentials shouldBe empty
  }

  it should "refuse exhausted binding and view fences without producing a partial state" in {
    val bindingExhausted = original.copy(slots = Map(bindingKey -> BrowserReplacementSlot(Long.MaxValue, None)))
    val viewExhausted    = original.copy(views = Map(qsid -> original.views(qsid).copy(epoch = Long.MaxValue)))
    for (records <- List(bindingExhausted, viewExhausted))
      reap(records, start.plusSeconds(4), limits, 0, 1) shouldBe Left(BrowserFailure.Capacity)
  }

  "Binding admission" should "retain existing slots and refuse new ones before exceeding capacity" in {
    val bounded = limits.copy(retainedBindings = 1, anonymousBindings = 1)
    captureBinding(original, bindingKey, true, bounded, 10) shouldBe Right(original)
    captureBinding(original, Vector[Byte](2), true, bounded, 10) shouldBe Left(BrowserFailure.Denied)
    captureBinding(original, Vector[Byte](2), false, bounded, 10) shouldBe Left(BrowserFailure.Capacity)
  }

  "View claiming" should "allow a fresh owner at the same bounded slot but never reuse a live connection" in {
    val bounded = limits.copy(activeViewsPerBinding = 1)
    claimEpoch(original, qsid, bindingKey, connection, false, bounded, 1) shouldBe Left(BrowserFailure.Denied)
    val fresh = ConnectionId.fromUuid(new UUID(5L, 5L))
    claimEpoch(original, qsid, bindingKey, fresh, true, bounded, 1) shouldBe Right(8L)
    claimEpoch(original, Qsid("other", "view"), bindingKey, fresh, true, bounded, 1) shouldBe Left(
      BrowserFailure.Missing
    )
    claimEpoch(original, Qsid("other", "view"), bindingKey, fresh, false, bounded, 1) shouldBe Left(
      BrowserFailure.Capacity
    )
  }

  "Completion delivery" should "consume replacement material at its limit while preserving ordinary delivery policy" in {
    val completion = Completion(new UUID(3L, 3L), new UUID(4L, 4L), start, Some("delivery"), 2)
    delivered(completion, true, 3).material shouldBe None
    delivered(completion, false, 3).material shouldBe Some("delivery")
    delivered(completion, true, 3).deliveries shouldBe 3
    completion.deliveries shouldBe 2
  }

  "Ordinary logout" should "advance authority without eagerly changing its retained view ownership" in {
    val loggedOut = logout(original, bindingKey).toOption.flatten.get
    loggedOut.slots(bindingKey) shouldBe BrowserReplacementSlot(5L, None)
    loggedOut.views shouldBe original.views
    logout(original, Vector[Byte](2)) shouldBe Right(None)
    logout(original.copy(slots = Map(bindingKey -> BrowserReplacementSlot(Long.MaxValue, None))), bindingKey) shouldBe
      Left(BrowserFailure.Capacity)
  }
}
