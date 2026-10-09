package spoonbill.snapshot

import java.util.UUID
import spoonbill.security.Versions.{SecurityGeneration, SlotGeneration, ViewOwnershipEpoch, ViewRevision}

object SnapshotKeys {
  opaque type Subject = String
  object Subject {
    def parse(value: String): Either[ViewSnapshotError, Subject] = checked(value)
    extension (key: Subject) def value: String = key
  }

  opaque type Scope = String
  object Scope {
    def parse(value: String): Either[ViewSnapshotError, Scope] = checked(value)
    extension (key: Scope) def value: String = key
  }

  private def checked(value: String): Either[ViewSnapshotError, String] =
    if (value.nonEmpty && value.length <= 256 && value.forall(c => c >= ' ' && c <= '~')) Right(value)
    else Left(ViewSnapshotError.InvalidIdentity)
}

/** Non-secret binding metadata, not authentication evidence. A storage adapter
  * must verify it against current host authority; never reconstruct authority
  * from the stored copy. Realm/browser lineage are part of the adapter's key.
  */
final case class SnapshotIdentity(
  subject: SnapshotKeys.Subject,
  scope: SnapshotKeys.Scope,
  sessionId: UUID,
  securityGeneration: SecurityGeneration,
  slotGeneration: SlotGeneration
) {
  override def toString: String = "SnapshotIdentity(<redacted>)"
}

enum SnapshotResetReason {
  case IdentityChanged, SchemaChanged
}

enum ViewSnapshotError {
  case AccessDenied, StaleOwner, RevisionConflict, RevisionExhausted, MalformedSnapshot
  case StorageFailure, ResetRequired, ResetNotRequired, InvalidIdentity
  case InvalidState(error: SnapshotError)
}

enum SnapshotLoad[P] {
  case Empty(revision: ViewRevision)
  case Restored(revision: ViewRevision, value: P)
  case ResetRequired(revision: ViewRevision, reason: SnapshotResetReason)

  override def toString: String = this match {
    case Empty(_) => "SnapshotLoad.Empty"
    case Restored(_, _) => "SnapshotLoad.Restored(<redacted>)"
    case ResetRequired(_, reason) => s"SnapshotLoad.ResetRequired($reason)"
  }
}

/** A connection-bound store for an explicitly non-secret presentation projection.
  * The implementation owns trusted identity, view key and owner epoch; public
  * methods accept neither credentials nor authority supplied by a browser.
  *
  * Every operation checks current authority and ownership at its storage
  * boundary. Writes atomically compare expected revision and owner epoch with
  * the payload commit. Revision never resets when identity/schema/owner changes.
  * Failed CAS must not be retried by overwriting with the returned current state.
  */
trait ViewSnapshotStore[F[_], P] {
  def ownerEpoch: ViewOwnershipEpoch
  def load(): F[Either[ViewSnapshotError, SnapshotLoad[P]]]
  def save(expected: ViewRevision, value: P): F[Either[ViewSnapshotError, ViewRevision]]

  /** Explicit safe reset after load reports incompatible identity or schema.
    * Compatible malformed bytes fail closed and cannot be reset through this API.
    */
  def reset(expected: ViewRevision, value: P): F[Either[ViewSnapshotError, ViewRevision]]
}
