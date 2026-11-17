package spoonbill.auth

import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemoryBrowserStoreMachineSpec extends AnyFlatSpec with Matchers {
  "Record publication" should "retain frontiers advanced after a record snapshot was staged" in {
    val initial   = MemoryBrowserStoreMachine.initial("old records")
    val observed  = MemoryBrowserStoreMachine.observe(initial, Instant.parse("2026-01-01T00:00:00Z"))
    val issued    = MemoryBrowserStoreMachine.issue(observed, 3).get
    val reclaimed = MemoryBrowserStoreMachine.reclaim(issued, 7, 9)
    val published = MemoryBrowserStoreMachine.publish(reclaimed, "staged records")
    published.records shouldBe Some("staged records")
    published.copy(records = initial.records) shouldBe reclaimed
    MemoryBrowserStoreMachine.observe(published, Instant.MIN) shouldBe published
    MemoryBrowserStoreMachine.close(published).copy(records = published.records) shouldBe published
    initial.records shouldBe Some("old records")
    initial.observed shouldBe Instant.MIN
  }

  "Issuance" should "refuse exhaustion before incrementing or wrapping" in {
    val exhausted = MemoryBrowserStoreMachine.State(Some(()), issuanceSequence = Long.MaxValue)
    MemoryBrowserStoreMachine.issue(exhausted, Long.MaxValue) shouldBe None
    val before = exhausted.copy(issuanceSequence = Long.MaxValue - 1)
    MemoryBrowserStoreMachine.issue(before, Long.MaxValue) shouldBe Some(exhausted)
  }
}
