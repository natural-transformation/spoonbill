package spoonbill.security.jdbc

import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.security.*

enum JdbcOperationError {
  case InvalidConfiguration, TransactionRequired, NotFound, BindingMismatch
  case DefinitionConflict, InvocationConflict, CapacityExceeded, HostDenied
  case Expired, Revoked, Burned, CompetingInvocation, PermitUsed, NotReserved
  // CommitUnknown includes failed rollback: final transaction disposition is unresolved.
  case StorageFailure, CommitUnknown
}

/** Intentionally carries neither input data nor an underlying SQL exception. */
final class JdbcOperationException(val error: JdbcOperationError)
    extends RuntimeException(s"Operation authorization rejected: $error")

/** Not serializable and not reconstructible by a host/browser. The database
  * reservation, not this in-process object, is the authoritative execution fence.
  */
final class ExecutionPermit private[jdbc] (private[jdbc] val invocation: OperationInvocation) {
  private val claimed = new AtomicBoolean(false)
  private[jdbc] def claim(): Boolean = claimed.compareAndSet(false, true)
  override def toString: String = "ExecutionPermit(<redacted>)"
}

enum OperationPreparation {
  case Acquired(permit: ExecutionPermit)
  case Known(record: DurableInvocationRecord)
}
