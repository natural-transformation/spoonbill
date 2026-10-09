package spoonbill.security.transaction

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.store.{GrantDefinition, InvocationStatus}

enum OperationError {
  case InvalidDefinition, InvocationConflict, CapacityExceeded, Expired, HostDenied
  case NotPrepared, PermitUsed, ForeignPermit, ScopeClosed, InvalidRegistration
  case EvidenceUsed, TransactionRequired, IsolationRequired, CapacityOrConflict, StorageFailure
}

final class OperationProtocolException(val error: OperationError)
    extends RuntimeException(s"Operation protocol rejected: $error")

enum TransactionFailure {
  case Rejected(error: OperationError)
  // CommitUnknown means uncertain final disposition, including failed rollback.
  // Neither it nor StorageFailure establishes a durable negative outcome.
  case RolledBack, CommitUnknown, StorageFailure
}

/** Trusted infrastructure port, never a commit assertion supplied by a use case.
  * Construct and evaluate body once, joining the entire G program in one native
  * transaction. Right is legal only after the OUTERMOST commit is acknowledged.
  * Failures roll back the entire transaction; uncertain disposition, including
  * failed rollback, returns CommitUnknown. Actual native cancellation of G must
  * finalize its program and safely release/roll back the transaction. Cancelling
  * observation in outer F need not cancel G: an uncancellable Direct worker keeps
  * ownership of its transaction and scope until it terminates, and may commit.
  * Never return its result to a cancelled observer or release its live resources
  * early. Neither form of cancellation proves a durable negative outcome, and
  * neither need return a value in F. No automatic retries.
  * The protocol rechecks expiry after joined host/store work. A later commit
  * acknowledgment can arrive after expiry and must still report the actual
  * disposition; its delay is not permission to re-admit work or infer rollback.
  *
  * G=Direct supports synchronous JDBC or a Slick SimpleDBIO callback without
  * scheduling overhead; G=DBIO or a native async effect supports joined async
  * work. The executor owns resource safety and its declared scope policy.
  */
trait TransactionExecutor[F[_], G[_], Tx] {
  def program: TransactionProgram[G]
  def scopePolicy: TransactionScopePolicy
  def transact[A](body: Tx => G[A]): F[Either[TransactionFailure, A]]
}

enum PreparationKind {
  case Verified, Conditional
}

/** Trusted exact-intent metadata, not evidence supplied by a browser. */
final case class PreparedOperation(
  definition: GrantDefinition,
  invocation: OperationInvocation,
  kind: PreparationKind
) {
  override def toString: String = "PreparedOperation(<redacted>)"
}

final case class StoredOperation(operation: PreparedOperation, status: InvocationStatus) {
  override def toString: String = s"StoredOperation($status)"
}

/** Host-native durable storage: challenge/completion rows may implement this
  * port without a second ledger. Every method uses only the supplied transaction.
  * readForDecision protects the observed identity, including absence, until
  * outer commit/rollback. Pessimistic adapters acquire consistent ordered fences
  * before host locks and read after waiting out preceding owners. Optimistic
  * adapters may instead validate equivalent serializable/conditional exclusion
  * at commit, aborting conflicting transactions WITHOUT replaying the program.
  * ALL identity/outcome writers and reconcilers must use the same protocol,
  * including absent-record, alias and capacity decisions. A coarser host fence
  * is sufficient when it covers all these decisions. Changing fence protocols
  * requires draining incompatible writers/reconcilers first.
  * Conflicting grant/invocation aliases must fail with InvocationConflict, even when
  * lookup by the requested invocation alone would return None. Inserts enforce
  * uniqueness and bounded retention; no eviction of replay/negative fences.
  * recordOutcome accepts only Committed/NotCommitted, creating the exact record
  * when absent. A missing NotCommitted record creates a retained negative fence
  * excluding any late preparation or issuer-bound execution.
  * Committed must share the host mutation's atomic transaction. An optimistic
  * negative outcome may commit only if any earlier uncommitted writer will be
  * excluded from committing. Separate databases or independent status writes do
  * not meet this contract. No results are stored.
  */
trait DurableOperationStore[G[_], Tx] {
  def readForDecision(tx: Tx, invocation: OperationInvocation): G[Option[StoredOperation]]
  def insertPrepared(tx: Tx, operation: PreparedOperation): G[Unit]
  def recordOutcome(tx: Tx, operation: PreparedOperation, status: InvocationStatus): G[Unit]
}

/** Raw access is a trusted adapter escape hatch: never commit, close, await,
  * detach work, or retain the underlying transaction beyond its joined program.
  * Async work must be composed in G and obey the executor's serialization policy.
  */
class TransactionScope[Tx] private[transaction] (underlying: Tx, policy: TransactionScopePolicy) {
  private val active = new AtomicBoolean(true)
  private val ownerThread = Thread.currentThread()
  final def transaction: Tx = { requireActive(); underlying }
  private[transaction] final def requireActive(): Unit =
    if (!active.get() || (policy == TransactionScopePolicy.ThreadConfined && Thread.currentThread() != ownerThread))
      throw new OperationProtocolException(OperationError.ScopeClosed)
  private[transaction] final def close(): Unit = active.set(false)
}

/** Opaque and deliberately non-executable before the runner observes commit. */
final class PendingPreparation[+A] private[transaction] (
  private[transaction] val owner: AnyRef,
  private[transaction] val result: Either[StoredOperation, (PreparedOperation, A)]
)

final class PreparationScope[G[_], Tx] private[transaction] (
  tx: Tx,
  store: DurableOperationStore[G, Tx],
  clock: () => Instant,
  program: TransactionProgram[G],
  policy: TransactionScopePolicy
) extends TransactionScope[Tx](tx, policy) {
  private val registered = new AtomicBoolean(false)

  /** Protect the identity decision, run host preparation, then attach immutable
    * metadata in the SAME transaction. Known identities never rerun the host.
    * Host failures must fail G; returning a denial as A means normal completion.
    */
  def stage[A](operation: PreparedOperation)(prepareHost: Tx => G[A]): G[PendingPreparation[A]] = program.defer {
    requireActive()
    if (!registered.compareAndSet(false, true))
      throw new OperationProtocolException(OperationError.InvalidRegistration)
    OperationProtocol.validateDefinition(operation)
    program.flatMap(store.readForDecision(transaction, operation.invocation)) {
      case Some(record) =>
        requireActive()
        OperationProtocol.requireExact(record, operation)
        program.pure(new PendingPreparation[A](this, Left(record)))
      case None =>
        requireActive()
        OperationProtocol.requireFresh(operation, clock())
        program.flatMap(prepareHost(transaction)) { value =>
          requireActive()
          OperationProtocol.requireFresh(operation, clock())
          program.map(store.insertPrepared(transaction, operation)) { _ =>
            requireActive()
            OperationProtocol.requireFresh(operation, clock())
            new PendingPreparation[A](this, Right(operation -> value))
          }
        }
    }
  }
}

final class ExecutionPermit private[transaction] (
  private[transaction] val issuer: AnyRef,
  private[transaction] val operation: PreparedOperation
) {
  private val claimed = new AtomicBoolean(false)
  private[transaction] def claim(): Boolean = claimed.compareAndSet(false, true)
  override def toString: String = "ExecutionPermit(<redacted>)"
}

enum Prepared[+A] {
  case Ready(value: A, permit: ExecutionPermit)
  case Known(record: StoredOperation)
  override def toString: String = this match {
    case Ready(_, _) => "Prepared.Ready(<redacted>)"
    case Known(record) => s"Prepared.Known(${record.status})"
  }
}

/** Exact-attempt protocol. It does not issue reusable grants or revive a grant
  * for a different invocation; those remain the existing authorization store's
  * responsibility. Consumption survives a crash after durable admission; no
  * storage protocol can retain an attempt whose first write never committed.
  */
final class OperationProtocol[F[_], G[_], Tx](
  executor: TransactionExecutor[F, G, Tx],
  store: DurableOperationStore[G, Tx],
  clock: () => Instant
)(using effect: Effect[F]) {
  private val issuer = new Object()
  private val program = executor.program

  def prepare[A](body: PreparationScope[G, Tx] => G[PendingPreparation[A]])
    : F[Either[TransactionFailure, Prepared[A]]] =
    effect.map(OperationProtocol.transactOnce(executor) { tx =>
      val scope = new PreparationScope(tx, store, clock, program, executor.scopePolicy)
      program.guarantee {
        program.map(body(scope)) { pending =>
          scope.requireActive()
          if (!(pending.owner eq scope))
            throw new OperationProtocolException(OperationError.InvalidRegistration)
          pending.result.foreach { case (operation, _) =>
            OperationProtocol.requireFresh(operation, clock())
          }
          pending.result
        }
      }(scope.close())
    }) {
      _.map {
        case Left(record) => Prepared.Known(record)
        case Right((operation, value)) => Prepared.Ready(value, new ExecutionPermit(issuer, operation))
      }
    }

  def execute[A](permit: ExecutionPermit)(body: (TransactionScope[Tx], Instant) => G[A])
    : F[Either[TransactionFailure, A]] = OperationProtocol.transactOnce(executor) { tx =>
    if (!(permit.issuer eq issuer))
      throw new OperationProtocolException(OperationError.ForeignPermit)
    if (!permit.claim()) throw new OperationProtocolException(OperationError.PermitUsed)
    val operation = permit.operation
    program.flatMap(store.readForDecision(tx, operation.invocation)) { existing =>
      val record = existing.getOrElse(throw new OperationProtocolException(OperationError.NotPrepared))
      OperationProtocol.requireExact(record, operation)
      if (record.status != InvocationStatus.InProgress)
        throw new OperationProtocolException(OperationError.NotPrepared)
      OperationProtocol.runMutation(tx, store, operation, clock, program, executor.scopePolicy, body)
    }
  }

  /** Fences execution before recording a definite negative outcome. Recovery
    * returns only status; it never reconstructs a permit or sensitive result.
    * For another status observation call reconcile again to construct a fresh
    * transaction; re-evaluating the same lazy transaction program is a replay.
    */
  def reconcile(operation: PreparedOperation): F[Either[TransactionFailure, StoredOperation]] =
    OperationProtocol.transactOnce(executor) { tx =>
      OperationProtocol.settle(tx, store, operation, program)
    }
}

object OperationProtocol {
  /** The cell is shared across callback construction AND lazy program evaluation.
    * Guarding callback construction alone would allow an executor to replay G.
    * A returned lazy transaction program is single-evaluation, including status
    * reads. Call the protocol again when requesting a new status observation.
    */
  private[transaction] def transactOnce[F[_], G[_], Tx, A](executor: TransactionExecutor[F, G, Tx])
    (body: Tx => G[A]): F[Either[TransactionFailure, A]] = {
    val entered = new AtomicBoolean(false)
    executor.transact { tx =>
      executor.program.defer {
        if (!entered.compareAndSet(false, true))
          throw new OperationProtocolException(OperationError.PermitUsed)
        body(tx)
      }
    }
  }

  private[security] def validateDefinition(operation: PreparedOperation): Unit = {
    val definition = operation.definition
    if (definition.id != operation.invocation.grantId || definition.binding != operation.invocation.binding ||
        definition.policy != ReservationPolicy.ConsumeOnAttempt)
      throw new OperationProtocolException(OperationError.InvalidDefinition)
  }

  private[transaction] def requireExact(record: StoredOperation, operation: PreparedOperation): Unit =
    if (record.operation != operation)
      throw new OperationProtocolException(OperationError.InvocationConflict)

  private[transaction] def requireFresh(operation: PreparedOperation, now: Instant): Unit =
    if (!now.isBefore(operation.definition.expiresAt))
      throw new OperationProtocolException(OperationError.Expired)

  private[transaction] def runMutation[G[_], Tx, A](tx: Tx, store: DurableOperationStore[G, Tx], operation: PreparedOperation,
    clock: () => Instant, program: TransactionProgram[G], policy: TransactionScopePolicy,
    body: (TransactionScope[Tx], Instant) => G[A]): G[A] = program.defer {
    val now = clock()
    requireFresh(operation, now)
    val scope = new TransactionScope(tx, policy)
    program.guarantee {
      program.flatMap(body(scope, now)) { value =>
        scope.requireActive()
        requireFresh(operation, clock())
        program.map(store.recordOutcome(tx, operation, InvocationStatus.Committed)) { _ =>
          scope.requireActive()
          requireFresh(operation, clock())
          value
        }
      }
    }(scope.close())
  }

  private[transaction] def settle[G[_], Tx](tx: Tx, store: DurableOperationStore[G, Tx], operation: PreparedOperation,
    program: TransactionProgram[G]): G[StoredOperation] = program.defer {
    validateDefinition(operation)
    program.flatMap(store.readForDecision(tx, operation.invocation)) {
      case Some(record) =>
        requireExact(record, operation)
        record.status match {
          case InvocationStatus.Committed | InvocationStatus.NotCommitted => program.pure(record)
          case InvocationStatus.InProgress | InvocationStatus.Unknown =>
            program.map(store.recordOutcome(tx, operation, InvocationStatus.NotCommitted)) { _ =>
              StoredOperation(operation, InvocationStatus.NotCommitted)
            }
        }
      case None =>
        program.map(store.recordOutcome(tx, operation, InvocationStatus.NotCommitted)) { _ =>
          StoredOperation(operation, InvocationStatus.NotCommitted)
        }
    }
  }
}
