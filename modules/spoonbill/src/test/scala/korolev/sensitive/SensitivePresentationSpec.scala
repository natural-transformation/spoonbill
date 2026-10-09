package spoonbill.sensitive

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.security.Identifiers.ConnectionId

class SensitivePresentationSpec extends AnyFlatSpec with Matchers {
  private def accepted[A](result: Either[SensitiveError, A]): A = result.fold(error => fail(error.toString), identity)
  private val now = Instant.parse("2026-10-06T10:00:00Z")
  private val deadline = now.plusSeconds(60)
  private val purpose = accepted(Purpose.parse("mfa.recovery-codes"))
  private def binding = PresentationBinding(ConnectionId.fromUuid(UUID.randomUUID()),
    accepted(RegionId.parse("mfa-output")), PresentationId.fromUuid(UUID.randomUUID()), Audience.fromUuid(UUID.randomUUID()))
  private def prepared = accepted(SensitivePresentation.prepare(binding, purpose, now, deadline))

  "Sensitive presentation lifecycle" should "acknowledge only an emitted exact presentation without claiming human disclosure" in {
    val initial = prepared
    initial.acknowledge(initial.binding, now) shouldBe Left(SensitiveError.InvalidTransition)
    val emitted = accepted(initial.emitted(initial.binding, now))
    emitted.emitted(initial.binding, now) shouldBe Left(SensitiveError.InvalidTransition)
    val acknowledged = accepted(emitted.acknowledge(initial.binding, now.plusSeconds(1)))
    acknowledged.phase shouldBe PresentationPhase.BrowserAcknowledged
    accepted(acknowledged.acknowledge(initial.binding, now.plusSeconds(2))) shouldBe acknowledged
    acknowledged.clear(ClearReason.Requested).phase shouldBe
      PresentationPhase.Closed(ClearReason.Requested, DisclosureOutcome.BrowserProcessed)
  }

  it should "reject another connection, region, presentation or freshly changed audience" in {
    val initial = prepared
    val emitted = accepted(initial.emitted(initial.binding, now))
    val owner = initial.binding
    Vector(owner.copy(connectionId = binding.connectionId),
      owner.copy(regionId = accepted(RegionId.parse("another-region"))),
      owner.copy(presentationId = binding.presentationId), owner.copy(audience = binding.audience)).foreach { wrong =>
      initial.emitted(wrong, now) shouldBe Left(SensitiveError.WrongBinding)
      emitted.acknowledge(wrong, now) shouldBe Left(SensitiveError.WrongBinding)
    }
  }

  it should "preserve uncertain disclosure across disconnect, revocation, navigation and replacement" in {
    val initial = prepared
    val emitted = accepted(initial.emitted(initial.binding, now))
    Vector(ClearReason.Disconnected, ClearReason.Revoked, ClearReason.Navigation, ClearReason.Replaced,
      ClearReason.DeliveryFailed).foreach { reason =>
      initial.clear(reason).phase shouldBe PresentationPhase.Closed(reason, DisclosureOutcome.NotSent)
      val closed = emitted.clear(reason)
      closed.phase shouldBe PresentationPhase.Closed(reason, DisclosureOutcome.Uncertain)
      closed.acknowledge(initial.binding, now) shouldBe Left(SensitiveError.Closed)
      closed.emitted(initial.binding, now) shouldBe Left(SensitiveError.Closed)
      closed.clear(ClearReason.Requested) shouldBe closed
    }
  }

  it should "enforce the deadline without replaying expired or replaced presentations" in {
    val initial = prepared
    initial.emitted(initial.binding, now.minusNanos(1)) shouldBe Left(SensitiveError.DeadlineExceeded)
    initial.emitted(initial.binding, deadline) shouldBe Left(SensitiveError.DeadlineExceeded)
    initial.expire(deadline.minusNanos(1)) shouldBe Left(SensitiveError.NotExpired)
    accepted(initial.expire(deadline)).phase shouldBe PresentationPhase.Closed(ClearReason.Expired, DisclosureOutcome.NotSent)
    val emitted = accepted(initial.emitted(initial.binding, now))
    emitted.acknowledge(initial.binding, deadline) shouldBe Left(SensitiveError.DeadlineExceeded)
    accepted(emitted.expire(deadline)).phase shouldBe PresentationPhase.Closed(ClearReason.Expired, DisclosureOutcome.Uncertain)
    val replacement = prepared
    replacement.acknowledge(initial.binding, now) shouldBe Left(SensitiveError.WrongBinding)
  }

  it should "require a positive bounded lifetime and bounded non-secret names" in {
    SensitivePresentation.prepare(binding, purpose, now, now) shouldBe Left(SensitiveError.InvalidDeadline)
    SensitivePresentation.prepare(binding, purpose, now, now.plusSeconds(301)) shouldBe Left(SensitiveError.InvalidDeadline)
    SensitivePresentation.prepare(binding, purpose, now, now.plusSeconds(300)).isRight shouldBe true
    RegionId.parse("bad/name") shouldBe Left(SensitiveError.InvalidName)
    Purpose.parse("x" * 65) shouldBe Left(SensitiveError.InvalidName)
    prepared.toString shouldBe "SensitivePresentation(<metadata>)"
  }
}
