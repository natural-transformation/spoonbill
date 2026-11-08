package spoonbill.recovery

import spoonbill.effect.Effect

/**
 * The points of the publication, read and cleanup protocol where a process
 * can fail or lose a reply. Conformance harnesses gate these points; a
 * production binding passes RecoveryProbe.none and never pauses.
 */
enum RecoveryFault {
  case AfterReservationBeforeReply, BeforePayloadDispatch, BeforePayloadDurability
  case AfterPayloadDurabilityBeforePublish, AfterPublishBeforeReply, AfterPayloadFetchBeforeAdoption
  case BeforeRetirement, AfterRetirementBeforeDelete, AfterGarbageClaimBeforeDelete, AfterDeleteBeforeComplete
}

/**
 * Proceed normally, fail as if the process died before the step committed, or
 * let the step commit and lose its reply. A gate rejects a decision that is
 * meaningless at its point.
 */
enum FaultDecision {
  case Proceed, FailBeforeCommit, LoseReplyAfterCommit
}

/** Fault-injection seam of the binding; see RecoveryFault. */
trait RecoveryProbe[F[_]] {
  def at(point: RecoveryFault): F[FaultDecision]
}

object RecoveryProbe {
  def none[F[_]](using F: Effect[F]): RecoveryProbe[F] = new RecoveryProbe[F] {
    private val proceed                            = F.pure(FaultDecision.Proceed)
    def at(point: RecoveryFault): F[FaultDecision] = proceed
  }
}
