package spoonbill.security.jdbc

import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID

enum JdbcAuthError {
  case InvalidDigest, InvalidConfiguration, TransactionRequired, NotFound, BindingMismatch
  case Expired, Revoked, StaleGeneration, AlreadyAcknowledged, Conflict, HostDenied
  case GenerationExhausted, StorageFailure
  case InvalidViewId, StaleViewFence, ViewCapacityExceeded
}

/** A hash of a host-issued high-entropy credential or browser-binding secret.
  * Length validation does not establish entropy or authority. Never log hashes.
  */
final class Digest256 private (private val value: Array[Byte]) {
  def bytes: Array[Byte] = value.clone()
  override def toString: String = "Digest256(<redacted>)"
  override def equals(other: Any): Boolean = other match {
    case that: Digest256 => MessageDigest.isEqual(value, that.value)
    case _ => false
  }
  override def hashCode(): Int = java.util.Arrays.hashCode(value)
}

object Digest256 {
  def fromBytes(value: Array[Byte]): Either[JdbcAuthError, Digest256] =
    if (value.length == 32) Right(new Digest256(value.clone())) else Left(JdbcAuthError.InvalidDigest)
}

final case class PreparedBrowserSession(
  completionId: UUID,
  hostCompletionId: UUID,
  hostSessionId: UUID,
  bindingHash: Digest256,
  tokenHash: Digest256,
  expectedSlotGeneration: Long,
  sessionExpiresAt: Instant,
  completionExpiresAt: Instant
) {
  override def toString: String = "PreparedBrowserSession(<redacted>)"
}

final case class PreparedReceipt(completionId: UUID, hostSessionId: UUID, expectedSlotGeneration: Long)
final case class DeliveryPermit(completionId: UUID, hostCompletionId: UUID, hostSessionId: UUID)
final case class ActiveBrowserSession(hostSessionId: UUID, generation: Long, expiresAt: Instant)

/** Safe exception used by the host's shared preparation transaction. It must
  * escape that transaction so rejected preparation also rolls back factor
  * consumption, host session/completion rows and audit writes.
  */
final class JdbcAuthException(val error: JdbcAuthError)
    extends RuntimeException(s"Browser session operation rejected: $error")

/** Trusted host integration. These are blocking SQL hooks, not browser input.
  * isCurrent must validate the real host session/account policy on every call.
  * It runs before the framework slot lock; acquire necessary host row locks here
  * in the same order used by host preparation. acknowledge must be idempotent.
  * isCurrent is repeated after the slot lock with a fresh time, so those same
  * host locks must be reentrant; do not acquire a different lock set on retry.
  * Use only the supplied connection: never commit, roll back, close, await async
  * work or perform network IO. Host policy remains authoritative.
  */
trait HostSessionHooks {
  def isCurrent(connection: Connection, hostSessionId: UUID, now: Instant): Boolean
  def acknowledge(connection: Connection, hostCompletionId: UUID, hostSessionId: UUID, now: Instant): Unit
}
