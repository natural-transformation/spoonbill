package spoonbill.security.store

import java.time.Instant
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*

enum RecordKind {
  case Slot, View, Grant, Invocation
}

enum StoreError {
  case Missing(kind: RecordKind)
  case CapacityExceeded(kind: RecordKind)
  case ConflictingDefinition(kind: RecordKind)
  case SlotRejected(error: SlotError)
  case OwnershipRejected(error: OwnershipError)
  case GrantRejected(error: OperationAuthorizationError)
  case RevisionMismatch, RevisionExhausted, InvocationConflict, OutcomeConflict
}

enum StoreConfigurationError {
  case NonPositiveCapacity
}

/**
 * No automatic eviction: removing security or invocation records would discard
 * generations or replay protection. Exhaustion fails closed.
 */
final class StoreLimits private (
  val maxSlots: Int,
  val maxViews: Int,
  val maxGrants: Int,
  val maxInvocations: Int
)

object StoreLimits {
  val default: StoreLimits = new StoreLimits(1024, 4096, 4096, 8192)

  def create(
    maxSlots: Int = 1024,
    maxViews: Int = 4096,
    maxGrants: Int = 4096,
    maxInvocations: Int = 8192
  ): Either[StoreConfigurationError, StoreLimits] =
    if (List(maxSlots, maxViews, maxGrants, maxInvocations).exists(_ <= 0))
      Left(StoreConfigurationError.NonPositiveCapacity)
    else Right(new StoreLimits(maxSlots, maxViews, maxGrants, maxInvocations))
}

/**
 * Trusted input describing a newly issued grant, never caller-supplied state.
 */
final case class GrantDefinition(
  id: OperationAuthorizationId,
  binding: OperationBinding,
  expiresAt: Instant,
  policy: ReservationPolicy
)

/**
 * Only Acquired permits the caller to start new execution. In particular,
 * NotCommitted is a terminal invocation outcome, not permission to retry it.
 */
enum ReservationOutcome {
  case Acquired, InProgress, Unknown, Committed, NotCommitted
}

enum InvocationStatus {
  case InProgress, Unknown, Committed, NotCommitted
}

/**
 * Status only: never a stored password, recovery code, API token or result
 * body. The retained binding identifies the original invocation for
 * reconciliation; it is not evidence that the actor is still authorized.
 */
final case class InvocationRecord(
  invocationId: InvocationId,
  grantId: OperationAuthorizationId,
  binding: OperationBinding,
  status: InvocationStatus
)

final case class ViewRecord(ownership: ViewOwnership, revision: ViewRevision)

/**
 * Atomic slot decisions over already verified domain inputs. This interface
 * does not verify credentials or browser proofs. A production implementation
 * must also coordinate session activation/completion acknowledgement at its
 * documented transaction boundary; a slot record alone is not authentication.
 * Every supplied Instant comes from a trusted server clock, never browser
 * input.
 */
trait BrowserSessionSlotStore[F[_]] {
  def createSlot(binding: SlotBinding): F[Either[StoreError, BrowserSessionSlot]]
  def readSlot(id: BrowserSessionSlotId): F[Either[StoreError, BrowserSessionSlot]]
  def activate(pending: PendingActivation, now: Instant): F[Either[StoreError, BrowserSessionSlot]]
  def logout(binding: SlotBinding): F[Either[StoreError, BrowserSessionSlot]]
  def validateCurrent(
    binding: SlotBinding,
    sessionId: AuthSessionId,
    generation: SlotGeneration
  ): F[Either[StoreError, Unit]]
}

trait ViewOwnershipStore[F[_]] {
  def createView(id: ViewSessionId): F[Either[StoreError, ViewRecord]]
  def readView(id: ViewSessionId): F[Either[StoreError, ViewRecord]]
  def acquireView(
    id: ViewSessionId,
    expectedEpoch: ViewOwnershipEpoch,
    owner: ViewOwnerId
  ): F[Either[StoreError, ViewFence]]
  def releaseView(fence: ViewFence): F[Either[StoreError, ViewRecord]]
  def validateFence(fence: ViewFence): F[Either[StoreError, Unit]]

  /**
   * Atomically checks the owner and advances this store's revision. This is a
   * receiver-side fence demonstration, not snapshot persistence. A successful
   * check cannot fence a later write to a separate database or socket.
   */
  def advanceRevision(fence: ViewFence, expected: ViewRevision): F[Either[StoreError, ViewRevision]]
}

/**
 * Trusted use-case/reconciliation port, not a browser endpoint. An adapter must
 * only call recordCommitted with an established business outcome, and only call
 * failure/reconciliation methods with proof no in-flight effect can still
 * commit. No callback/business effect is executed by the store. The supplied
 * Instant values are trusted server time, not client timestamps.
 */
trait OperationAuthorizationStore[F[_]] {
  def issueGrant(definition: GrantDefinition): F[Either[StoreError, OperationAuthorization]]
  def readGrant(id: OperationAuthorizationId): F[Either[StoreError, OperationAuthorization]]
  def reserve(
    id: OperationAuthorizationId,
    binding: OperationBinding,
    invocationId: InvocationId,
    now: Instant
  ): F[Either[StoreError, ReservationOutcome]]
  def readInvocation(id: InvocationId): F[Either[StoreError, InvocationRecord]]
  def markUnknown(id: InvocationId): F[Either[StoreError, InvocationRecord]]

  /**
   * Status can be reconciled after revocation/expiry without acquiring new
   * authority. This records an existing commit; it never admits a mutation.
   */
  def recordCommitted(id: InvocationId): F[Either[StoreError, InvocationRecord]]
  def releaseAfterDefiniteFailure(id: InvocationId, now: Instant): F[Either[StoreError, InvocationRecord]]
  def reconcileNotCommitted(id: InvocationId, now: Instant): F[Either[StoreError, InvocationRecord]]
  def revokeGrant(id: OperationAuthorizationId): F[Either[StoreError, OperationAuthorization]]
}
