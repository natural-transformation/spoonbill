package spoonbill.internal

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.sensitive.*

final class SensitiveRuntimeMachineSpec extends AnyFlatSpec with Matchers {
  import SensitiveRuntimeMachine.*
  private val issued     = Instant.parse("2026-01-01T00:00:00Z")
  private val connection = ConnectionId.fromUuid(UUID.randomUUID())
  private val audience   = Audience.fromUuid(UUID.randomUUID())
  private val purpose    = Purpose.parse("fixture").toOption.get
  private def prepared(region: String = "one", lifetime: Long = 60): Entry = {
    val binding = PresentationBinding(
      connection,
      RegionId.parse(region).toOption.get,
      PresentationId.fromUuid(UUID.randomUUID()),
      audience
    )
    Entry(SensitivePresentation.prepare(binding, purpose, issued, issued.plusSeconds(lifetime)).toOption.get)
  }

  "Sensitive delivery metadata" should "share the strict acknowledgement window across timer, emission and acknowledgement" in {
    acknowledgementWindowSeconds shouldBe 5L
    val entry = prepared()
    emit(entry, issued.plusSeconds(5)) shouldBe Emission.TooLate
    val Emission.Emit(emitted) = emit(entry, issued.plusSeconds(4)): @unchecked
    acknowledge(emitted, true, issued.plusSeconds(5)) shouldBe Acknowledgement.Retire
    acknowledge(emitted, false, issued.plusSeconds(4)) shouldBe Acknowledgement.Retire
    val Acknowledgement.Accept(accepted) = acknowledge(emitted, true, issued.plusSeconds(4)): @unchecked
    acknowledge(accepted, true, issued.plusSeconds(6)) shouldBe a[Acknowledgement.Accept]
    acknowledge(accepted, true, issued.plusSeconds(60)) shouldBe Acknowledgement.Retire
    emit(prepared(lifetime = 2), issued.plusSeconds(2)) shouldBe Emission.TooLate
  }

  it should "keep emission, retirement, and once-only outcome completion distinct" in {
    val entry = prepared()
    acknowledge(entry, true, issued.plusSeconds(1)) shouldBe Acknowledgement.Ignore
    val Emission.Emit(emitted) = emit(entry, issued.plusSeconds(1)): @unchecked
    emit(emitted, issued.plusSeconds(2)) shouldBe Emission.Ignore
    val retired = clear(emitted, ClearReason.Disconnected)
    retired.presentation.phase shouldBe PresentationPhase.Closed(ClearReason.Disconnected, DisclosureOutcome.Uncertain)
    retired.waiter shouldBe Waiter.Pending
    finish(retired).waiter shouldBe Waiter.Completed
    clear(entry, ClearReason.Disconnected).presentation.phase shouldBe
      PresentationPhase.Closed(ClearReason.Disconnected, DisclosureOutcome.NotSent)
    entry.presentation.phase shouldBe PresentationPhase.Prepared
  }

  "Sensitive registry metadata" should "bound distinct regions, retain retirement ownership after close and reject late admission" in {
    val entries = (1 to 5).map(index => prepared(s"region$index"))
    val full = entries.take(4).foldLeft(initial) { (state, entry) =>
      register(state, entry.presentation.binding, new PendingRegistryMachine.Token).get
    }
    register(full, entries(4).presentation.binding, new PendingRegistryMachine.Token) shouldBe None
    val first           = entries.head.presentation.binding
    val duplicateRegion = first.copy(presentationId = PresentationId.fromUuid(UUID.randomUUID()))
    register(full, duplicateRegion, new PendingRegistryMachine.Token) shouldBe None
    val drainedOne = remove(full, first.presentationId)
    register(drainedOne, entries(4).presentation.binding, new PendingRegistryMachine.Token).nonEmpty shouldBe true
    val closed = close(full)
    closed.regions shouldBe full.regions
    register(
      remove(closed, first.presentationId),
      entries(4).presentation.binding,
      new PendingRegistryMachine.Token
    ) shouldBe None
    remove(closed, first.presentationId).regions.contains(first.regionId) shouldBe false
  }
}
