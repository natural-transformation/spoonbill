package spoonbill.security.transaction

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.Identifiers.{InvocationId, OperationAuthorizationId}
import spoonbill.security.store.GrantDefinition

/** Status lookup metadata only. Possessing, copying or reconstructing this value
  * never authorizes execution or evidence issuance.
  */
final case class OperationReference(operation: PreparedOperation) {
  override def toString: String = "OperationReference(<redacted>)"
}

private[transaction] final class AuthorityLease(val expiresAt: Instant) {
  val expired = new AtomicBoolean(false)
}

sealed abstract class OneUseEvidence private[transaction] (
  private[transaction] val issuer: OneUseAuthorityScope,
  private[transaction] val operation: PreparedOperation,
  private[transaction] val lease: AuthorityLease
) {
  private val redeemed = new AtomicBoolean(false)
  private[transaction] final def redeem(): Boolean = redeemed.compareAndSet(false, true)
  final def reference: OperationReference = OperationReference(operation)
  override final def toString: String = "OneUseEvidence(<redacted>)"
}

final class VerifiedEvidence private[transaction] (
  issuer: OneUseAuthorityScope, operation: PreparedOperation, lease: AuthorityLease
) extends OneUseEvidence(issuer, operation, lease)

final class ConditionalEvidence private[transaction] (
  issuer: OneUseAuthorityScope, operation: PreparedOperation, lease: AuthorityLease
) extends OneUseEvidence(issuer, operation, lease)

/** Live, nonserializable capability. Every alias shares the same claim cell. */
final class ExecutionAuthority private[transaction] (
  private[transaction] val issuer: OneUseAuthorityScope,
  private[transaction] val operation: PreparedOperation,
  private[transaction] val lease: AuthorityLease
) {
  private val claimed = new AtomicBoolean(false)
  private[transaction] def claim(): Boolean = claimed.compareAndSet(false, true)
  def reference: OperationReference = OperationReference(operation)
  override def toString: String = "ExecutionAuthority(<redacted>)"
}

/** Trusted provider port, not a browser or status-recovery API. verified may be
  * called only after the provider establishes fresh policy evidence. conditional
  * admits one proof attempt whose verification/consumption happens in the host
  * mutation transaction. Providers must prevent replay of the same proof from
  * minting fresh receipts; a reference, definition or prior status is not proof.
  *
  * IDs are generated here. A canonical receipt can issue only one authority,
  * regardless of aliases. Receipts and unclaimed authorities share a bounded
  * lease list, pruned on issuance and released on admission. No identity registry
  * or reconstruction API exists. Unused authority dies with this issuer instance.
  *
  * close linearizes against admission and rejects unadmitted authorities. It
  * cannot cancel admitted work or revoke a partitioned issuer on another node.
  * Hosts MUST fence current session/generation/issuer epoch after their row locks.
  */
final class OneUseAuthorityScope(clock: () => Instant, maxOutstanding: Int = 1024) extends AutoCloseable {
  require(maxOutstanding > 0, "Authority capacity must be positive")
  private val active = new AtomicBoolean(true)
  private val leases = new AtomicReference(List.empty[AuthorityLease])

  def verified(binding: OperationBinding, digest: RequestDigest, expiresAt: Instant)
    : Either[OperationError, VerifiedEvidence] = synchronized {
    allocate(binding, digest, expiresAt, PreparationKind.Verified)
      .map { case (operation, lease) => new VerifiedEvidence(this, operation, lease) }
  }

  def conditional(binding: OperationBinding, digest: RequestDigest, expiresAt: Instant)
    : Either[OperationError, ConditionalEvidence] = synchronized {
    allocate(binding, digest, expiresAt, PreparationKind.Conditional)
      .map { case (operation, lease) => new ConditionalEvidence(this, operation, lease) }
  }

  def issue(evidence: OneUseEvidence): Either[OperationError, ExecutionAuthority] = synchronized {
    if (!(evidence.issuer eq this)) Left(OperationError.ForeignPermit)
    else if (!active.get()) Left(OperationError.ScopeClosed)
    else if (evidence.lease.expired.get() || !clock().isBefore(evidence.lease.expiresAt)) {
      evidence.lease.expired.set(true)
      release(evidence.lease)
      Left(OperationError.Expired)
    } else if (!evidence.redeem()) Left(OperationError.EvidenceUsed)
    else Right(new ExecutionAuthority(this, evidence.operation, evidence.lease))
  }

  private[transaction] def admit(authority: ExecutionAuthority): Either[OperationError, PreparedOperation] = synchronized {
    if (!(authority.issuer eq this)) Left(OperationError.ForeignPermit)
    else if (!active.get()) Left(OperationError.ScopeClosed)
    else if (authority.lease.expired.get() || !clock().isBefore(authority.lease.expiresAt)) {
      authority.lease.expired.set(true)
      release(authority.lease)
      Left(OperationError.Expired)
    } else if (!authority.claim()) Left(OperationError.PermitUsed)
    else {
      release(authority.lease)
      Right(authority.operation)
    }
  }

  override def close(): Unit = synchronized {
    active.set(false)
    leases.set(Nil)
  }

  private def release(lease: AuthorityLease): Unit =
    leases.set(leases.get().filterNot(_ eq lease))

  private def allocate(binding: OperationBinding, digest: RequestDigest, expiresAt: Instant, kind: PreparationKind)
    : Either[OperationError, (PreparedOperation, AuthorityLease)] = {
    val now = clock()
    val retained = leases.get().filter { lease =>
      if (!now.isBefore(lease.expiresAt)) lease.expired.set(true)
      !lease.expired.get()
    }
    leases.set(retained)
    if (!active.get()) Left(OperationError.ScopeClosed)
    else if (!now.isBefore(expiresAt)) Left(OperationError.Expired)
    else if (retained.size >= maxOutstanding) Left(OperationError.CapacityExceeded)
    else {
      val grantId = OperationAuthorizationId.fromUuid(UUID.randomUUID())
      val definition = GrantDefinition(grantId, binding, expiresAt, ReservationPolicy.ConsumeOnAttempt)
      val invocation = OperationInvocation(InvocationId.fromUuid(UUID.randomUUID()), grantId, binding, digest)
      val lease = new AuthorityLease(expiresAt)
      leases.set(lease :: retained)
      Right(PreparedOperation(definition, invocation, kind) -> lease)
    }
  }
}

/** Distinct from durable preparation: admission consumes the issuer's live
  * capability BEFORE transaction dispatch, including failure/cancellation. It
  * never creates a reusable durable grant. The durable store contains outcomes;
  * absence means unresolved until a fenced negative outcome commits.
  *
  * Restart loses unused authorities; it does not prove an old transaction cannot
  * still commit. Recovery uses the same protected decision and never issues a new
  * authority. Executors and hosts must not retry an admitted transaction body.
  */
final class OneUseOperationProtocol[F[_], G[_], Tx](
  executor: TransactionExecutor[F, G, Tx],
  store: DurableOperationStore[G, Tx],
  issuer: OneUseAuthorityScope,
  clock: () => Instant
)(using effect: Effect[F]) {
  private val program = executor.program

  def executeIssued[A](authority: ExecutionAuthority)(body: (TransactionScope[Tx], Instant) => G[A])
    : F[Either[TransactionFailure, A]] =
    effect.flatMap(effect.delay(issuer.admit(authority))) {
      case Left(error) => effect.pure(Left(TransactionFailure.Rejected(error)))
      case Right(operation) =>
        OperationProtocol.transactOnce(executor) { tx =>
          program.flatMap(store.readForDecision(tx, operation.invocation)) {
            case Some(record) =>
              OperationProtocol.requireExact(record, operation)
              throw new OperationProtocolException(OperationError.NotPrepared)
            case None => OperationProtocol.runMutation(tx, store, operation, clock, program, executor.scopePolicy, body)
          }
        }
    }

  /** Construct a fresh call for each status observation; replaying the same lazy
    * transaction program is rejected even though reconciliation admits no work.
    */
  def reconcile(reference: OperationReference): F[Either[TransactionFailure, StoredOperation]] =
    OperationProtocol.transactOnce(executor)(tx => OperationProtocol.settle(tx, store, reference.operation, program))
}
