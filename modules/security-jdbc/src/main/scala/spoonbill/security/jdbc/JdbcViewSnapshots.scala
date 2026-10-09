package spoonbill.security.jdbc

import java.sql.{Connection, PreparedStatement}
import java.time.Instant
import java.util.UUID
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.*

/** Trusted host policy for the identity-bound projection. Validate subject,
  * selected scope and security generation against host data, not stored snapshot
  * metadata. Acquire host locks before framework locks and recheck the same lock
  * set on the second call. Never commit/close this connection or access framework
  * tables from this hook. It complements the existing session/expiry checks.
  */
trait HostSnapshotIdentity {
  def isCurrent(connection: Connection, sessionId: UUID, expected: SnapshotIdentity, now: Instant): Boolean
}

private[jdbc] final class SnapshotStorageException(val error: ViewSnapshotError)
    extends RuntimeException("View snapshot storage rejected the operation")

/** Blocking, connection-bound projection store. Use a host-managed blocking
  * executor to implement ViewSnapshotStore[F,P]. No credential, authority object,
  * component state, DOM or render diff is serialized into the payload.
  */
final class JdbcViewSnapshots[P] private[jdbc] (
  sessions: JdbcBrowserSessions,
  viewId: String,
  bindingHash: Digest256,
  ownerId: UUID,
  val ownerEpoch: ViewOwnershipEpoch,
  tokenHash: Digest256,
  identity: SnapshotIdentity,
  format: SnapshotFormat[P],
  identityCheck: HostSnapshotIdentity
) {
  private case class Metadata(
    revision: Long,
    subject: String,
    scope: String,
    sessionId: UUID,
    securityGeneration: Long,
    slotGeneration: Long,
    schemaId: String,
    schemaVersion: Int
  )

  private def owned[A](run: (Connection, ViewRevision) => Either[ViewSnapshotError, A]): Either[ViewSnapshotError, A] =
    sessions.withSnapshotView(viewId, bindingHash, ownerId, ownerEpoch, tokenHash, identity, identityCheck)(run)

  def load(): Either[ViewSnapshotError, SnapshotLoad[P]] = owned { (connection, revision) =>
    metadata(connection) match {
      case None if revision.toLong == 0L => Right(SnapshotLoad.Empty(revision))
      case None => Left(ViewSnapshotError.MalformedSnapshot)
      case Some(stored) if stored.revision != revision.toLong => Left(ViewSnapshotError.MalformedSnapshot)
      case Some(stored) => mismatch(stored) match {
        case Some(reason) => Right(SnapshotLoad.ResetRequired(revision, reason))
        case None => readPayload(connection, revision).map(value => SnapshotLoad.Restored(revision, value))
      }
    }
  }

  def save(expected: ViewRevision, value: P): Either[ViewSnapshotError, ViewRevision] = write(expected, value, resetting = false)
  def reset(expected: ViewRevision, value: P): Either[ViewSnapshotError, ViewRevision] = write(expected, value, resetting = true)

  private def write(expected: ViewRevision, value: P, resetting: Boolean): Either[ViewSnapshotError, ViewRevision] = {
    val encoded = try format.write(value).flatMap(SnapshotBinaryCodec.encode(_, format.limits))
      .left.map(ViewSnapshotError.InvalidState.apply)
    catch { case NonFatal(_) => Left(ViewSnapshotError.InvalidState(SnapshotError.InvalidValue)) }
    encoded.flatMap { payload => owned { (connection, current) =>
      if (current != expected) Left(ViewSnapshotError.RevisionConflict)
      else {
        val stored = metadata(connection)
        val consistent = stored match {
          case None => current.toLong == 0L
          case Some(record) => record.revision == current.toLong
        }
        if (!consistent) Left(ViewSnapshotError.MalformedSnapshot)
        else if (resetting && !stored.exists(record => mismatch(record).nonEmpty)) Left(ViewSnapshotError.ResetNotRequired)
        else if (!resetting && stored.exists(record => mismatch(record).nonEmpty)) Left(ViewSnapshotError.ResetRequired)
        else current.next.left.map(_ => ViewSnapshotError.RevisionExhausted).map { next =>
          statement(connection, """INSERT INTO spoonbill_view_snapshot
            (realm, cookie_namespace, view_id, revision, subject_key, scope_key, session_id,
             security_generation, slot_generation, schema_id, schema_version, payload)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (realm, cookie_namespace, view_id) DO UPDATE SET
              revision = EXCLUDED.revision, subject_key = EXCLUDED.subject_key,
              scope_key = EXCLUDED.scope_key, session_id = EXCLUDED.session_id,
              security_generation = EXCLUDED.security_generation, slot_generation = EXCLUDED.slot_generation,
              schema_id = EXCLUDED.schema_id, schema_version = EXCLUDED.schema_version, payload = EXCLUDED.payload""") { query =>
            scope(query)
            query.setLong(4, next.toLong)
            query.setString(5, identity.subject.value)
            query.setString(6, identity.scope.value)
            query.setObject(7, identity.sessionId)
            query.setLong(8, identity.securityGeneration.toLong)
            query.setLong(9, identity.slotGeneration.toLong)
            query.setString(10, format.schemaId.value)
            query.setInt(11, format.version.value)
            query.setBytes(12, payload)
            if (query.executeUpdate() != 1) throw new SnapshotStorageException(ViewSnapshotError.StorageFailure)
          }
          statement(connection, """UPDATE spoonbill_browser_view SET snapshot_revision = ?
            WHERE realm = ? AND cookie_namespace = ? AND view_id = ? AND binding_hash = ?
              AND owner_id = ? AND epoch = ? AND snapshot_revision = ?""") { query =>
            query.setLong(1, next.toLong)
            query.setString(2, sessions.snapshotRealm)
            query.setString(3, sessions.snapshotCookieNamespace)
            query.setString(4, viewId)
            query.setBytes(5, bindingHash.bytes)
            query.setObject(6, ownerId)
            query.setLong(7, ownerEpoch.toLong)
            query.setLong(8, expected.toLong)
            if (query.executeUpdate() != 1) throw new SnapshotStorageException(ViewSnapshotError.RevisionConflict)
          }
          next
        }
      }
    }}
  }

  private def mismatch(stored: Metadata): Option[SnapshotResetReason] =
    if (stored.subject != identity.subject.value || stored.scope != identity.scope.value ||
        stored.sessionId != identity.sessionId || stored.securityGeneration != identity.securityGeneration.toLong ||
        stored.slotGeneration != identity.slotGeneration.toLong)
      Some(SnapshotResetReason.IdentityChanged)
    else if (stored.schemaId != format.schemaId.value || stored.schemaVersion != format.version.value)
      Some(SnapshotResetReason.SchemaChanged)
    else None

  private def metadata(connection: Connection): Option[Metadata] =
    statement(connection, """SELECT revision, subject_key, scope_key, session_id, security_generation,
      slot_generation, schema_id, schema_version FROM spoonbill_view_snapshot
      WHERE realm = ? AND cookie_namespace = ? AND view_id = ?""") { query =>
      scope(query)
      Using.resource(query.executeQuery()) { result =>
        if (!result.next()) None
        else Some(Metadata(result.getLong(1), result.getString(2), result.getString(3), result.getObject(4, classOf[UUID]),
          result.getLong(5), result.getLong(6), result.getString(7), result.getInt(8)))
      }
    }

  private def readPayload(connection: Connection, revision: ViewRevision): Either[ViewSnapshotError, P] =
    statement(connection, """SELECT payload FROM spoonbill_view_snapshot
      WHERE realm = ? AND cookie_namespace = ? AND view_id = ? AND revision = ? AND octet_length(payload) <= ?""") { query =>
      scope(query)
      query.setLong(4, revision.toLong)
      // Bound on the server before the JDBC driver materializes a BYTEA value.
      query.setInt(5, SnapshotBinaryCodec.maxEncodedBytes(format.limits))
      Using.resource(query.executeQuery()) { result =>
        if (!result.next()) Left(ViewSnapshotError.MalformedSnapshot)
        else SnapshotBinaryCodec.decode(result.getBytes(1), format.limits).flatMap(format.read)
          .left.map(_ => ViewSnapshotError.MalformedSnapshot)
      }
    }

  private def scope(query: PreparedStatement): Unit = {
    query.setString(1, sessions.snapshotRealm)
    query.setString(2, sessions.snapshotCookieNamespace)
    query.setString(3, viewId)
  }
  private def statement[A](connection: Connection, sql: String)(run: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(run)
}
