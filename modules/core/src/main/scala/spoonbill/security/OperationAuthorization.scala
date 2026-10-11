package spoonbill.security

import Identifiers.*
import Versions.*
import java.time.Instant

final case class OperationBinding(
  subjectId: SubjectId,
  realmId: RealmId,
  sessionId: AuthSessionId,
  securityGeneration: SecurityGeneration,
  purpose: OperationPurpose,
  resourceScope: ResourceScope
)

enum ReservationPolicy {
  case ReleaseAfterDefiniteFailure, ConsumeOnAttempt
}

enum GrantInvalidation {
  case Expired, Revoked, Burned
}

enum OperationAuthorizationState {
  case Available
  case Reserved(invocationId: InvocationId, invalidated: Option[GrantInvalidation])
  case Unknown(invocationId: InvocationId, invalidated: Option[GrantInvalidation])
  case Committed(invocationId: InvocationId)
  case Invalidated(reason: GrantInvalidation)
}

enum OperationAuthorizationError {
  case BindingMismatch, Expired, Revoked, Burned, CompetingInvocation, NotReserved, AlreadyCommitted
  case ReconciliationRequired
}

/**
 * Only Acquired admits new execution. All retry outcomes require status
 * recovery, not replay.
 */
enum ReservationDecision {
  case Acquired, InProgress, Unknown, Committed
}

final case class ReservationResult(authorization: OperationAuthorization, decision: ReservationDecision)

/**
 * Pure reservation/consumption decisions. The host must atomically apply these
 * decisions against the current grant, and coordinate consumption, the domain
 * mutation, and durable invocation deduplication in one transaction or a
 * specified recovery protocol. No method here performs a mutation, verifies a
 * principal, or provides persistence/linearizability. Committed state records
 * status only: it never stores/replays a sensitive operation result.
 */
final case class OperationAuthorization(
  id: OperationAuthorizationId,
  binding: OperationBinding,
  expiresAt: Instant,
  policy: ReservationPolicy,
  state: OperationAuthorizationState
) {
  import OperationAuthorizationState.*
  import spoonbill.security.OperationAuthorizationError as Error

  def reserve(
    presented: OperationBinding,
    invocationId: InvocationId,
    now: Instant
  ): Either[Error, ReservationResult] =
    if (binding != presented) Left(Error.BindingMismatch)
    else
      state match {
        case Available =>
          if (!now.isBefore(expiresAt)) Left(Error.Expired)
          else Right(ReservationResult(copy(state = Reserved(invocationId, None)), ReservationDecision.Acquired))
        case Reserved(owner, _)  => retry(owner, invocationId, ReservationDecision.InProgress)
        case Unknown(owner, _)   => retry(owner, invocationId, ReservationDecision.Unknown)
        case Committed(owner)    => retry(owner, invocationId, ReservationDecision.Committed)
        case Invalidated(reason) => Left(invalidationError(reason))
      }

  /**
   * Records an already established commit outcome, including after
   * expiry/revocation. It must not be used to admit a new mutation. Recording
   * late completion is necessary because invalidation cannot undo an in-flight
   * business transaction.
   */
  def commit(invocationId: InvocationId): Either[Error, OperationAuthorization] = state match {
    case Reserved(owner, _)  => owned(owner, invocationId).map(_ => copy(state = Committed(owner)))
    case Unknown(owner, _)   => owned(owner, invocationId).map(_ => copy(state = Committed(owner)))
    case Committed(owner)    => owned(owner, invocationId).map(_ => this)
    case Available           => Left(Error.NotReserved)
    case Invalidated(reason) => Left(invalidationError(reason))
  }

  def markUnknown(invocationId: InvocationId): Either[Error, OperationAuthorization] = state match {
    case Reserved(owner, invalidated) => owned(owner, invocationId).map(_ => copy(state = Unknown(owner, invalidated)))
    case Unknown(owner, _)            => owned(owner, invocationId).map(_ => this)
    case Committed(_)                 => Left(Error.AlreadyCommitted)
    case Available                    => Left(Error.NotReserved)
    case Invalidated(reason)          => Left(invalidationError(reason))
  }

  /**
   * Caller must prove that no mutation occurred and no outstanding effect can
   * still commit.
   */
  def release(invocationId: InvocationId, now: Instant): Either[Error, OperationAuthorization] = state match {
    case Reserved(owner, invalidated) => owned(owner, invocationId).map(_ => afterDefiniteFailure(invalidated, now))
    case Unknown(_, _)                => Left(Error.ReconciliationRequired)
    case Committed(_)                 => Left(Error.AlreadyCommitted)
    case Available                    => Left(Error.NotReserved)
    case Invalidated(reason)          => Left(invalidationError(reason))
  }

  /**
   * Explicit reconciliation evidence is required; elapsed time or cancellation
   * is insufficient.
   */
  def reconcileNotCommitted(invocationId: InvocationId, now: Instant): Either[Error, OperationAuthorization] =
    state match {
      case Unknown(owner, invalidated) => owned(owner, invocationId).map(_ => afterDefiniteFailure(invalidated, now))
      case Reserved(_, _)              => Left(Error.ReconciliationRequired)
      case Committed(_)                => Left(Error.AlreadyCommitted)
      case Available                   => Left(Error.NotReserved)
      case Invalidated(reason)         => Left(invalidationError(reason))
    }

  /**
   * Retain in-flight identity so a later authoritative commit outcome can be
   * reconciled.
   */
  def revoke: OperationAuthorization = state match {
    case Available                     => copy(state = Invalidated(GrantInvalidation.Revoked))
    case Reserved(owner, _)            => copy(state = Reserved(owner, Some(GrantInvalidation.Revoked)))
    case Unknown(owner, _)             => copy(state = Unknown(owner, Some(GrantInvalidation.Revoked)))
    case Committed(_) | Invalidated(_) => this
  }

  private def afterDefiniteFailure(invalidated: Option[GrantInvalidation], now: Instant): OperationAuthorization = {
    val reason = invalidated
      .orElse(Option.when(!now.isBefore(expiresAt))(GrantInvalidation.Expired))
      .orElse(Option.when(policy == ReservationPolicy.ConsumeOnAttempt)(GrantInvalidation.Burned))
    copy(state = reason.fold[OperationAuthorizationState](Available)(Invalidated.apply))
  }

  private def retry(
    owner: InvocationId,
    invocationId: InvocationId,
    decision: ReservationDecision
  ): Either[Error, ReservationResult] =
    owned(owner, invocationId).map(_ => ReservationResult(this, decision))

  private def owned(owner: InvocationId, invocationId: InvocationId): Either[Error, Unit] =
    if (owner == invocationId) Right(()) else Left(Error.CompetingInvocation)

  private def invalidationError(reason: GrantInvalidation): Error = reason match {
    case GrantInvalidation.Expired => Error.Expired
    case GrantInvalidation.Revoked => Error.Revoked
    case GrantInvalidation.Burned  => Error.Burned
  }
}
