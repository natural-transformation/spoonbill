package spoonbill.auth

import java.time.Instant
import java.util.UUID
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class BrowserCeremonyRecordSpec extends AnyFlatSpec with Matchers {
  private val time    = Instant.parse("2026-10-11T00:00:00Z")
  private val binding = Vector.fill[Byte](32)(1)
  private val record =
    BrowserCeremonyRecord(CeremonyId(new UUID(0L, 1L)), new UUID(0L, 2L), binding, 3L, "login", time.plusSeconds(60))
  private val challenge = BrowserChallenge("subject", 7L, 2L, "factor")
  private def accepted[A](value: Either[BrowserCeremonyError, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)

  "Pure ceremony decisions" should "validate exact authority and reject the expiry boundary deterministically" in {
    (1 to 10).foreach { _ =>
      record.validate(binding, 3L, "login", time) shouldBe Right(())
      record.validate(binding.updated(0, 2.toByte), 3L, "login", time) shouldBe Left(BrowserCeremonyError.Denied)
      record.validate(binding, 4L, "login", time) shouldBe Left(BrowserCeremonyError.Denied)
      record.validate(binding, 3L, "reset", time) shouldBe Left(BrowserCeremonyError.Denied)
      record.validate(binding, 3L, "login", record.expiresAt) shouldBe Left(BrowserCeremonyError.Expired)
    }
    record.phase shouldBe BrowserCeremonyPhase.Begun
    record.challenge shouldBe None
  }

  it should "bind a challenge once without modifying the original or refreshing captured facts" in {
    val bound = accepted(record.bind(challenge))
    bound.id shouldBe record.id
    bound.generation shouldBe record.generation
    bound.expiresAt shouldBe record.expiresAt
    bound.challenge shouldBe Some(challenge)
    bound.bind(challenge) shouldBe Left(BrowserCeremonyError.Used)
    bound.bind(challenge.copy(subject = "other")) shouldBe Left(BrowserCeremonyError.Used)
    record.challenge shouldBe None
  }

  it should "require admission before commit and preserve terminal positive and negative fences" in {
    record.committed shouldBe Left(BrowserCeremonyError.Used)
    val admitted = accepted(accepted(record.bind(challenge)).admit)
    admitted.admit shouldBe Left(BrowserCeremonyError.Used)
    val committed = accepted(admitted.committed)
    committed.phase shouldBe BrowserCeremonyPhase.Committed
    committed.reconciled shouldBe committed
    committed.admit shouldBe Left(BrowserCeremonyError.Used)
    Vector(record, admitted, record.reconciled).foreach { value =>
      val negative = value.reconciled
      negative.phase shouldBe BrowserCeremonyPhase.NotCommitted
      negative.admit shouldBe Left(BrowserCeremonyError.Used)
      negative.committed shouldBe Left(BrowserCeremonyError.Used)
      negative.reconciled shouldBe negative
    }
  }
}
