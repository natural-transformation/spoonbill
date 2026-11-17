package spoonbill.security

import java.time.Instant
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class InvocationDecisionMachineSpec extends AnyFlatSpec with Matchers {
  import InvocationDecisionMachine.*

  "Exact-attempt decisions" should "permit prepared execution only for an in-progress record" in {
    RecordState.values.foreach { state =>
      if (state == RecordState.InProgress) executePrepared(state) shouldBe None
      else executePrepared(state) shouldBe Some(Rejection.NotPrepared)
    }
    // A negative outcome is a retained replay fence, never permission to retry.
    executePrepared(RecordState.NotCommitted) shouldBe Some(Rejection.NotPrepared)
  }

  it should "keep terminal outcomes and settle every unresolved or absent record negatively" in {
    Vector(RecordState.Committed, RecordState.NotCommitted).foreach { state =>
      settle(state) shouldBe Settlement.KeepOutcome
    }
    Vector(RecordState.Missing, RecordState.InProgress, RecordState.Unknown).foreach { state =>
      settle(state) shouldBe Settlement.RecordNotCommitted
    }
  }

  it should "require matching intent and consume-on-attempt policy independently" in {
    definition(true, ReservationPolicy.ConsumeOnAttempt) shouldBe None
    definition(false, ReservationPolicy.ConsumeOnAttempt) shouldBe Some(Rejection.InvalidDefinition)
    definition(true, ReservationPolicy.ReleaseAfterDefiniteFailure) shouldBe Some(Rejection.InvalidDefinition)
    definition(false, ReservationPolicy.ReleaseAfterDefiniteFailure) shouldBe Some(Rejection.InvalidDefinition)
    exact(true) shouldBe None
    exact(false) shouldBe Some(Rejection.InvocationConflict)
  }

  it should "expire at equality while allowing a separate terminal status decision after expiry" in {
    val expiry = Instant.parse("2026-10-08T12:00:00Z")
    fresh(expiry, expiry.minusNanos(1)) shouldBe None
    fresh(expiry, expiry) shouldBe Some(Rejection.Expired)
    fresh(expiry, expiry.plusNanos(1)) shouldBe Some(Rejection.Expired)
    settle(RecordState.Committed) shouldBe Settlement.KeepOutcome
    settle(RecordState.NotCommitted) shouldBe Settlement.KeepOutcome
  }
}
