package spoonbill.security

import java.time.Instant

/** Exact-attempt decisions only. Store fencing and transaction effects remain with the host. */
private[spoonbill] object InvocationDecisionMachine {
  enum RecordState { case Missing, InProgress, Unknown, Committed, NotCommitted }
  enum Rejection { case InvalidDefinition, InvocationConflict, Expired, NotPrepared }
  enum Settlement { case KeepOutcome, RecordNotCommitted }

  def definition(intentMatches: Boolean, policy: ReservationPolicy): Option[Rejection] =
    Option.when(!intentMatches || policy != ReservationPolicy.ConsumeOnAttempt)(Rejection.InvalidDefinition)

  def exact(identityMatches: Boolean): Option[Rejection] =
    Option.when(!identityMatches)(Rejection.InvocationConflict)

  def fresh(expiresAt: Instant, now: Instant): Option[Rejection] =
    Option.when(!now.isBefore(expiresAt))(Rejection.Expired)

  def executePrepared(state: RecordState): Option[Rejection] = state match {
    case RecordState.InProgress => None
    case _                      => Some(Rejection.NotPrepared)
  }

  def settle(state: RecordState): Settlement = state match {
    case RecordState.Committed | RecordState.NotCommitted => Settlement.KeepOutcome
    case _                                               => Settlement.RecordNotCommitted
  }
}
