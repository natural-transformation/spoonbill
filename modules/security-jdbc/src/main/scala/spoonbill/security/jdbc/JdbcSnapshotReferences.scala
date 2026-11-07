package spoonbill.security.jdbc

import java.sql.{Connection, PreparedStatement, Timestamp}
import java.time.Instant
import java.util.UUID
import scala.util.Using
import spoonbill.auth.BrowserView
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.security.transaction.{OperationError, OperationProtocolException, TransactionFailure}
import spoonbill.snapshot.{SnapshotIdentity, SnapshotResetReason, ViewSnapshotError}

/**
 * The PostgreSQL implementation of the publication capability. Reservation,
 * publication and read pins run inside the provider's own view transaction,
 * so they share its authority check, owner fence and realm lock with takeover,
 * logout and retention; publish and retire therefore serialize. A published
 * revision keeps its identity and schema metadata in spoonbill_view_snapshot
 * with an empty inline payload and the reference's operation id, which an
 * inline-only reader rejects as malformed instead of reading. Restricted
 * bookkeeping (status, pin release, garbage claims) uses short realm-locked
 * transactions without a view authority check. No payload I/O happens here.
 */
private[jdbc] final class JdbcSnapshotReferences[F[_], P](
  provider: JdbcBrowserPersistence[F, P],
  settings: SnapshotReferenceSettings
)(using F: Effect[F])
    extends SnapshotReferenceSupport[F, P] {
  private val realm     = provider.realm
  private val namespace = provider.namespace

  def bind(view: BrowserView[P], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[F]] =
    SnapshotIdentities.ofView(view).map(identity => new Bound(view, identity, policy))

  val maintenance: SnapshotReferenceMaintenance[F] = new Maintenance

  private def statement[A](connection: Connection, sql: String)(run: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(run)
  private def scoped(query: PreparedStatement, from: Int = 1): Unit = {
    query.setString(from, realm); query.setString(from + 1, namespace)
  }
  private def stamp(time: Instant): Timestamp = Timestamp.from(time)
  private def refuse(error: OperationError = OperationError.StorageFailure): Nothing =
    throw new OperationProtocolException(error)

  private case class Reference(
    operation: UUID,
    viewId: String,
    ref: PayloadRef,
    owner: UUID,
    epoch: Long,
    expected: Long,
    published: Option[Long],
    intent: PublicationIntent,
    state: String,
    schema: SnapshotSchema,
    deadline: Instant
  ) {
    def ticket: PublicationTicket = PublicationTicket(
      PublicationId(operation),
      ref,
      ViewRevision.fromLong(expected).fold(_ => refuse(), identity),
      intent,
      schema,
      ViewOwnershipEpoch.fromLong(epoch).fold(_ => refuse(), identity)
    )
  }

  private def reference(connection: Connection, operation: UUID, lock: Boolean): Option[Reference] =
    statement(
      connection,
      """SELECT view_id,payload_id,payload_digest,payload_length,owner_id,epoch,expected_revision,published_revision,
      intent,state,schema_id,schema_version,reservation_deadline FROM spoonbill_snapshot_reference
      WHERE realm=? AND cookie_namespace=? AND operation_id=?""" + (if (lock) " FOR UPDATE" else "")
    ) { query =>
      scoped(query); query.setObject(3, operation)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) None
        else
          Some(
            Reference(
              operation,
              rows.getString(1),
              PayloadRef(rows.getObject(2, classOf[UUID]), rows.getBytes(3).toVector, rows.getInt(4)),
              rows.getObject(5, classOf[UUID]),
              rows.getLong(6),
              rows.getLong(7),
              Option(rows.getObject(8)).map(_ => rows.getLong(8)),
              if (rows.getString(9) == "reset") PublicationIntent.Reset else PublicationIntent.Save,
              rows.getString(10),
              SnapshotSchema(rows.getString(11), rows.getInt(12)),
              rows.getTimestamp(13).toInstant
            )
          )
      }
    }

  private case class Row(
    revision: Long,
    identity: SnapshotIdentity,
    schema: SnapshotSchema,
    operation: Option[UUID]
  )

  private def snapshotRow(connection: Connection, viewId: String): Option[Row] =
    statement(
      connection,
      """SELECT revision,subject_key,scope_key,session_id,security_generation,slot_generation,schema_id,schema_version,
      payload_operation FROM spoonbill_view_snapshot WHERE realm=? AND cookie_namespace=? AND view_id=?"""
    ) { query =>
      scoped(query); query.setString(3, viewId)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) None
        else {
          val identity = for {
            subject <- spoonbill.snapshot.SnapshotKeys.Subject.parse(rows.getString(2)).toOption
            scope   <- spoonbill.snapshot.SnapshotKeys.Scope.parse(rows.getString(3)).toOption
            security <- spoonbill.security.Versions.SecurityGeneration.fromLong(rows.getLong(5)).toOption
            slot     <- spoonbill.security.Versions.SlotGeneration.fromLong(rows.getLong(6)).toOption
          } yield SnapshotIdentity(subject, scope, rows.getObject(4, classOf[UUID]), security, slot)
          identity.map(value =>
            Row(
              rows.getLong(1),
              value,
              SnapshotSchema(rows.getString(7), rows.getInt(8)),
              Option(rows.getObject(9, classOf[UUID]))
            )
          )
        }
      }
    }

  private def openObligations(connection: Connection): Long = statement(
    connection,
    """SELECT count(*) FROM spoonbill_snapshot_reference
    WHERE realm=? AND cookie_namespace=? AND state='retired' AND deleted_at IS NULL"""
  ) { query =>
    scoped(query)
    Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
  }

  private final class Bound(view: BrowserView[P], identity: SnapshotIdentity, policy: SnapshotSchemaPolicy)
      extends BoundSnapshotReferences[F] {
    val ownerEpoch: ViewOwnershipEpoch = view.fence.epoch
    private val viewId                 = BrowserView.key(view.qsid)
    private val owner                  = view.ownerId
    private val epoch                  = view.epoch

    /** The view's revision under its row lock; absence means another owner holds the view now. */
    private def revision(connection: Connection): Either[ViewSnapshotError, ViewRevision] = statement(
      connection,
      """SELECT snapshot_revision FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=? AND view_id=?
      AND owner_id=? AND epoch=? FOR UPDATE"""
    ) { query =>
      scoped(query); query.setString(3, viewId); query.setObject(4, owner); query.setLong(5, epoch)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) Left(ViewSnapshotError.StaleOwner)
        else ViewRevision.fromLong(rows.getLong(1)).left.map(_ => ViewSnapshotError.MalformedSnapshot)
      }
    }

    /** Current authority and owner fence first, then the body with the view's revision. */
    private def fenced[A](
      body: (Connection, Instant, ViewRevision) => Either[ViewSnapshotError, A]
    ): F[Either[ViewSnapshotError, A]] =
      F.map(provider.transact(view) { tx =>
        val connection = tx.connection
        revision(connection).flatMap(current => body(connection, tx.now, current))
      }) {
        case Right(result) => result
        case Left(
              TransactionFailure.Rejected(OperationError.HostDenied | OperationError.ForeignPermit | OperationError.Expired)
            ) =>
          Left(ViewSnapshotError.AccessDenied)
        // A closed scope or a view row the provider no longer has: this owner is stale.
        case Left(TransactionFailure.Rejected(OperationError.ScopeClosed | OperationError.NotPrepared)) =>
          Left(ViewSnapshotError.StaleOwner)
        case Left(_) => Left(ViewSnapshotError.StorageFailure)
      }

    private def mismatch(row: Row): Option[SnapshotResetReason] =
      if (row.identity != identity) Some(SnapshotResetReason.IdentityChanged)
      else if (!policy.accepts(row.schema)) Some(SnapshotResetReason.SchemaChanged)
      else None

    private def inlineBytes(connection: Connection): Option[SnapshotBytes] = statement(
      connection,
      """SELECT payload FROM spoonbill_view_snapshot WHERE realm=? AND cookie_namespace=? AND view_id=?
      AND octet_length(payload)<=?"""
    ) { query =>
      scoped(query); query.setString(3, viewId); query.setInt(4, SnapshotBytes.MaximumLength)
      Using.resource(query.executeQuery())(rows => if (rows.next()) Some(SnapshotBytes.copyOf(rows.getBytes(1))) else None)
    }

    def acquireRead(): F[ReferenceLoad] = F.map(fenced[ReferenceLoad] { (connection, now, current) =>
      snapshotRow(connection, viewId) match {
        case None if current.toLong == 0L                 => Right(ReferenceLoad.Empty(current))
        case None                                         => Right(ReferenceLoad.Rejected(ViewSnapshotError.MalformedSnapshot))
        case Some(row) if row.revision != current.toLong => Right(ReferenceLoad.Rejected(ViewSnapshotError.MalformedSnapshot))
        case Some(row) =>
          mismatch(row) match {
            case Some(reason) => Right(ReferenceLoad.ResetRequired(current, reason))
            case None =>
              row.operation match {
                case None =>
                  inlineBytes(connection)
                    .map(bytes => ReferenceLoad.Inline(current, row.schema, bytes))
                    .toRight(ViewSnapshotError.MalformedSnapshot)
                case Some(operation) =>
                  reference(connection, operation, lock = false) match {
                    case Some(found)
                        if found.state == "published" && found.published.contains(current.toLong) &&
                          found.viewId == viewId =>
                      val pin = UUID.randomUUID()
                      statement(
                        connection,
                        """INSERT INTO spoonbill_snapshot_read_pin
                        (realm,cookie_namespace,pin_id,operation_id,view_id,owner_id,epoch,revision,expires_at)
                        VALUES (?,?,?,?,?,?,?,?,?)"""
                      ) { query =>
                        scoped(query); query.setObject(3, pin); query.setObject(4, operation); query.setString(5, viewId)
                        query.setObject(6, owner); query.setLong(7, epoch); query.setLong(8, current.toLong)
                        query.setTimestamp(9, stamp(now.plusSeconds(settings.readPinSeconds))); query.executeUpdate()
                      }
                      Right(ReferenceLoad.Pinned(ReadPin(pin, PublicationId(operation), current), found.ref, found.schema))
                    case _ => Right(ReferenceLoad.Rejected(ViewSnapshotError.MalformedSnapshot))
                  }
              }
          }
      }
    })(_.fold(ReferenceLoad.Rejected.apply, load => load))

    def finishRead(pin: ReadPin): F[Either[ViewSnapshotError, Unit]] = fenced { (connection, now, current) =>
      val held = statement(
        connection,
        """DELETE FROM spoonbill_snapshot_read_pin WHERE realm=? AND cookie_namespace=? AND pin_id=? AND owner_id=?
        AND epoch=? RETURNING operation_id,revision,expires_at"""
      ) { query =>
        scoped(query); query.setObject(3, pin.id); query.setObject(4, owner); query.setLong(5, epoch)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) None
          else Some((rows.getObject(1, classOf[UUID]), rows.getLong(2), rows.getTimestamp(3).toInstant))
        }
      }
      held match {
        case Some((operation, revision, expires))
            if operation == pin.operation.value && revision == pin.revision.toLong && now.isBefore(expires) &&
              current == pin.revision && snapshotRow(connection, viewId).exists(row =>
                row.revision == revision && row.operation.contains(operation)
              ) =>
          Right(())
        case _ => Left(ViewSnapshotError.StaleOwner)
      }
    }

    def releaseRead(pin: ReadPin): F[Unit] = F.map(provider.bookkeeping(cleanup = false) { (connection, _) =>
      statement(
        connection,
        "DELETE FROM spoonbill_snapshot_read_pin WHERE realm=? AND cookie_namespace=? AND pin_id=? AND owner_id=? AND epoch=?"
      ) { query =>
        scoped(query); query.setObject(3, pin.id); query.setObject(4, owner); query.setLong(5, epoch); query.executeUpdate()
      }
    })(_ => ())

    def reserve(
      operation: PublicationId,
      expected: ViewRevision,
      ref: PayloadRef,
      schema: SnapshotSchema,
      intent: PublicationIntent
    ): F[ReservationOutcome] = F.map(fenced[PublicationTicket] { (connection, now, current) =>
      if (current != expected) Left(ViewSnapshotError.RevisionConflict)
      else {
        val row        = snapshotRow(connection, viewId)
        val consistent = row.fold(current.toLong == 0L)(_.revision == current.toLong)
        val reason     = row.flatMap(mismatch)
        if (!consistent) Left(ViewSnapshotError.MalformedSnapshot)
        else if (intent == PublicationIntent.Reset && reason.isEmpty) Left(ViewSnapshotError.ResetNotRequired)
        else if (intent == PublicationIntent.Save && reason.nonEmpty) Left(ViewSnapshotError.ResetRequired)
        else if (openObligations(connection) >= settings.maxOpenObligations) Left(ViewSnapshotError.StorageFailure)
        else if (reference(connection, operation.value, lock = false).nonEmpty) Left(ViewSnapshotError.StorageFailure)
        else {
          statement(
            connection,
            """INSERT INTO spoonbill_snapshot_reference (realm,cookie_namespace,operation_id,view_id,payload_id,
            payload_digest,payload_length,owner_id,epoch,expected_revision,intent,state,schema_id,schema_version,
            reserved_at,reservation_deadline) VALUES (?,?,?,?,?,?,?,?,?,?,?,'staged',?,?,?,?)"""
          ) { query =>
            scoped(query); query.setObject(3, operation.value); query.setString(4, viewId); query.setObject(5, ref.id)
            query.setBytes(6, ref.digest.toArray); query.setInt(7, ref.length); query.setObject(8, owner)
            query.setLong(9, epoch); query.setLong(10, expected.toLong)
            query.setString(11, if (intent == PublicationIntent.Reset) "reset" else "save")
            query.setString(12, schema.id); query.setInt(13, schema.version); query.setTimestamp(14, stamp(now))
            query.setTimestamp(15, stamp(now.plusSeconds(settings.reservationSeconds))); query.executeUpdate()
          }
          Right(PublicationTicket(operation, ref, expected, intent, schema, ownerEpoch))
        }
      }
    }) {
      case Right(ticket) => ReservationOutcome.Prepared(ticket)
      case Left(error)   => ReservationOutcome.Rejected(error)
    }

    def publish(ticket: PublicationTicket): F[PublicationOutcome] = F.map(fenced[ViewRevision] {
      (connection, now, current) =>
        reference(connection, ticket.operation.value, lock = true) match {
          case Some(found)
              if found.owner == owner && found.epoch == epoch && found.viewId == viewId && found.state == "staged" &&
                now.isBefore(found.deadline) && found.ticket == ticket =>
            if (current != ticket.expected) Left(ViewSnapshotError.RevisionConflict)
            else
              ticket.expected.next.left.map(_ => ViewSnapshotError.RevisionExhausted).map { next =>
                // Superseded-reference retirement is part of publication, not a later transaction.
                statement(
                  connection,
                  """UPDATE spoonbill_snapshot_reference SET state='retired',retired_at=? WHERE realm=? AND cookie_namespace=?
                  AND view_id=? AND state='published'"""
                ) { query =>
                  query.setTimestamp(1, stamp(now)); scoped(query, 2); query.setString(4, viewId); query.executeUpdate()
                }
                statement(
                  connection,
                  """INSERT INTO spoonbill_view_snapshot
                  (realm, cookie_namespace, view_id, revision, subject_key, scope_key, session_id,
                   security_generation, slot_generation, schema_id, schema_version, payload, payload_operation)
                  VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                  ON CONFLICT (realm, cookie_namespace, view_id) DO UPDATE SET
                    revision = EXCLUDED.revision, subject_key = EXCLUDED.subject_key,
                    scope_key = EXCLUDED.scope_key, session_id = EXCLUDED.session_id,
                    security_generation = EXCLUDED.security_generation, slot_generation = EXCLUDED.slot_generation,
                    schema_id = EXCLUDED.schema_id, schema_version = EXCLUDED.schema_version,
                    payload = EXCLUDED.payload, payload_operation = EXCLUDED.payload_operation"""
                ) { query =>
                  scoped(query); query.setString(3, viewId); query.setLong(4, next.toLong)
                  query.setString(5, identity.subject.value); query.setString(6, identity.scope.value)
                  query.setObject(7, identity.sessionId); query.setLong(8, identity.securityGeneration.toLong)
                  query.setLong(9, identity.slotGeneration.toLong); query.setString(10, ticket.schema.id)
                  query.setInt(11, ticket.schema.version); query.setBytes(12, Array.emptyByteArray)
                  query.setObject(13, ticket.operation.value)
                  if (query.executeUpdate() != 1) refuse()
                }
                statement(
                  connection,
                  """UPDATE spoonbill_browser_view SET snapshot_revision=? WHERE realm=? AND cookie_namespace=? AND view_id=?
                  AND owner_id=? AND epoch=? AND snapshot_revision=?"""
                ) { query =>
                  query.setLong(1, next.toLong); scoped(query, 2); query.setString(4, viewId); query.setObject(5, owner)
                  query.setLong(6, epoch); query.setLong(7, ticket.expected.toLong)
                  if (query.executeUpdate() != 1) refuse()
                }
                statement(
                  connection,
                  """UPDATE spoonbill_snapshot_reference SET state='published',published_revision=? WHERE realm=? AND cookie_namespace=?
                  AND operation_id=?"""
                ) { query =>
                  query.setLong(1, next.toLong); scoped(query, 2); query.setObject(4, ticket.operation.value)
                  if (query.executeUpdate() != 1) refuse()
                }
                next
              }
          case _ => Left(ViewSnapshotError.StorageFailure)
        }
    }) {
      case Right(revision) => PublicationOutcome.Published(revision)
      case Left(error)     => PublicationOutcome.Rejected(error)
    }

    def status(operation: PublicationId): F[PublicationStatus] = F.map(provider.bookkeeping(cleanup = false) {
      (connection, now) =>
        reference(connection, operation.value, lock = false) match {
          case Some(found) if found.owner == owner && found.epoch == epoch =>
            found.state match {
              case "staged" if now.isBefore(found.deadline) => PublicationStatus.Prepared(found.ticket)
              case "staged"                                 => PublicationStatus.NotPublished
              case _ =>
                found.published
                  .flatMap(value => ViewRevision.fromLong(value).toOption)
                  .fold(PublicationStatus.NotPublished)(PublicationStatus.Published.apply)
            }
          case _ => PublicationStatus.Unresolved
        }
    })(_.getOrElse(PublicationStatus.Unresolved))
  }

  private final class Maintenance extends SnapshotReferenceMaintenance[F] {
    def claimGarbage(limit: Int): F[Vector[GarbageClaim]] = F.map(provider.bookkeeping(cleanup = true) {
      (connection, now) =>
        statement(
          connection,
          """UPDATE spoonbill_snapshot_reference SET state='retired',retired_at=? WHERE realm=? AND cookie_namespace=?
          AND state='staged' AND reservation_deadline<=?"""
        ) { query =>
          query.setTimestamp(1, stamp(now)); scoped(query, 2); query.setTimestamp(4, stamp(now)); query.executeUpdate()
        }
        statement(
          connection,
          """WITH eligible AS (SELECT r.operation_id FROM spoonbill_snapshot_reference r
          WHERE r.realm=? AND r.cookie_namespace=? AND r.state='retired' AND r.deleted_at IS NULL
          AND (r.claim_expires_at IS NULL OR r.claim_expires_at<=?) AND (r.retry_after IS NULL OR r.retry_after<=?)
          AND NOT EXISTS (SELECT 1 FROM spoonbill_snapshot_read_pin p WHERE p.realm=r.realm AND p.cookie_namespace=r.cookie_namespace
            AND p.operation_id=r.operation_id AND p.expires_at>?)
          ORDER BY r.retired_at LIMIT ? FOR UPDATE SKIP LOCKED)
          UPDATE spoonbill_snapshot_reference u SET claim_id=gen_random_uuid(),claim_generation=u.claim_generation+1,claim_expires_at=?
          FROM eligible e WHERE u.realm=? AND u.cookie_namespace=? AND u.operation_id=e.operation_id
          RETURNING u.operation_id,u.payload_id,u.payload_digest,u.payload_length,u.claim_id,u.claim_generation,u.claim_expires_at"""
        ) { query =>
          scoped(query); query.setTimestamp(3, stamp(now)); query.setTimestamp(4, stamp(now))
          query.setTimestamp(5, stamp(now)); query.setInt(6, math.max(1, math.min(limit, 1024)))
          query.setTimestamp(7, stamp(now.plusSeconds(settings.claimLeaseSeconds))); scoped(query, 8)
          Using.resource(query.executeQuery()) { rows =>
            val claims = Vector.newBuilder[GarbageClaim]
            while (rows.next())
              claims += GarbageClaim(
                PublicationId(rows.getObject(1, classOf[UUID])),
                PayloadRef(rows.getObject(2, classOf[UUID]), rows.getBytes(3).toVector, rows.getInt(4)),
                rows.getObject(5, classOf[UUID]),
                rows.getLong(6),
                rows.getTimestamp(7).toInstant
              )
            claims.result()
          }
        }
    })(_.getOrElse(Vector.empty))

    /** True when the claim's obligation is already settled, so a repeat is idempotent. */
    private def settled(connection: Connection, claim: GarbageClaim): Boolean = statement(
      connection,
      """SELECT deleted_at IS NOT NULL FROM spoonbill_snapshot_reference WHERE realm=? AND cookie_namespace=?
      AND operation_id=? AND claim_id=? AND claim_generation=?"""
    ) { query =>
      scoped(query); query.setObject(3, claim.operation.value); query.setObject(4, claim.claimId)
      query.setLong(5, claim.generation)
      Using.resource(query.executeQuery())(rows => rows.next() && rows.getBoolean(1))
    }

    def completeDelete(claim: GarbageClaim): F[ClaimUpdate] = F.map(provider.bookkeeping(cleanup = true) {
      (connection, now) =>
        val updated = statement(
          connection,
          """UPDATE spoonbill_snapshot_reference SET deleted_at=? WHERE realm=? AND cookie_namespace=? AND operation_id=?
          AND claim_id=? AND claim_generation=? AND deleted_at IS NULL"""
        ) { query =>
          query.setTimestamp(1, stamp(now)); scoped(query, 2); query.setObject(4, claim.operation.value)
          query.setObject(5, claim.claimId); query.setLong(6, claim.generation); query.executeUpdate()
        }
        if (updated == 1) ClaimUpdate.Applied
        else if (settled(connection, claim)) ClaimUpdate.AlreadyApplied
        else ClaimUpdate.StaleClaim
    })(_.getOrElse(ClaimUpdate.StaleClaim))

    def retryDelete(claim: GarbageClaim): F[ClaimUpdate] = F.map(provider.bookkeeping(cleanup = true) {
      (connection, now) =>
        val updated = statement(
          connection,
          """UPDATE spoonbill_snapshot_reference SET claim_id=NULL,claim_expires_at=NULL,delete_attempts=delete_attempts+1,
          retry_after=? WHERE realm=? AND cookie_namespace=? AND operation_id=? AND claim_id=? AND claim_generation=?
          AND deleted_at IS NULL"""
        ) { query =>
          query.setTimestamp(1, stamp(now.plusSeconds(settings.retryBackoffSeconds))); scoped(query, 2)
          query.setObject(4, claim.operation.value); query.setObject(5, claim.claimId); query.setLong(6, claim.generation)
          query.executeUpdate()
        }
        if (updated == 1) ClaimUpdate.Applied
        else if (settled(connection, claim)) ClaimUpdate.AlreadyApplied
        else ClaimUpdate.StaleClaim
    })(_.getOrElse(ClaimUpdate.StaleClaim))

    def backlog(): F[CleanupBacklog] = F.map(provider.bookkeeping(cleanup = false) { (connection, _) =>
      statement(
        connection,
        """SELECT count(*),count(*) FILTER (WHERE delete_attempts>0),min(retired_at) FROM spoonbill_snapshot_reference
        WHERE realm=? AND cookie_namespace=? AND state='retired' AND deleted_at IS NULL"""
      ) { query =>
        scoped(query)
        Using.resource(query.executeQuery()) { rows =>
          rows.next()
          CleanupBacklog(rows.getLong(1), rows.getLong(2), Option(rows.getTimestamp(3)).map(_.toInstant))
        }
      }
    })(_.getOrElse(CleanupBacklog(0L, 0L, None)))
  }
}

private[jdbc] object JdbcSnapshotReferences {
  def initialize(connection: Connection): Unit =
    schema.foreach(text => Using.resource(connection.createStatement())(_.executeUpdate(text)))

  private val schema = Vector(
    """CREATE TABLE IF NOT EXISTS spoonbill_snapshot_reference (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL, operation_id UUID NOT NULL,
      view_id VARCHAR(192) NOT NULL, payload_id UUID NOT NULL,
      payload_digest BYTEA NOT NULL CHECK (octet_length(payload_digest) = 32),
      payload_length INTEGER NOT NULL CHECK (payload_length >= 0),
      owner_id UUID NOT NULL, epoch BIGINT NOT NULL CHECK (epoch > 0),
      expected_revision BIGINT NOT NULL CHECK (expected_revision >= 0),
      published_revision BIGINT CHECK (published_revision > 0),
      intent VARCHAR(8) NOT NULL CHECK (intent IN ('save', 'reset')),
      state VARCHAR(16) NOT NULL CHECK (state IN ('staged', 'published', 'retired')),
      schema_id VARCHAR(96) NOT NULL, schema_version INTEGER NOT NULL CHECK (schema_version > 0),
      reserved_at TIMESTAMP WITH TIME ZONE NOT NULL, reservation_deadline TIMESTAMP WITH TIME ZONE NOT NULL,
      retired_at TIMESTAMP WITH TIME ZONE, deleted_at TIMESTAMP WITH TIME ZONE,
      claim_id UUID, claim_generation BIGINT NOT NULL DEFAULT 0 CHECK (claim_generation >= 0),
      claim_expires_at TIMESTAMP WITH TIME ZONE,
      delete_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delete_attempts >= 0), retry_after TIMESTAMP WITH TIME ZONE,
      PRIMARY KEY (realm, cookie_namespace, operation_id), UNIQUE (realm, cookie_namespace, payload_id))""",
    """CREATE INDEX IF NOT EXISTS spoonbill_snapshot_reference_view
      ON spoonbill_snapshot_reference (realm, cookie_namespace, view_id)""",
    """CREATE INDEX IF NOT EXISTS spoonbill_snapshot_reference_garbage
      ON spoonbill_snapshot_reference (realm, cookie_namespace, retired_at) WHERE state = 'retired' AND deleted_at IS NULL""",
    """CREATE TABLE IF NOT EXISTS spoonbill_snapshot_read_pin (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL, pin_id UUID NOT NULL,
      operation_id UUID NOT NULL, view_id VARCHAR(192) NOT NULL, owner_id UUID NOT NULL,
      epoch BIGINT NOT NULL CHECK (epoch > 0), revision BIGINT NOT NULL CHECK (revision > 0),
      expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
      PRIMARY KEY (realm, cookie_namespace, pin_id))""",
    """CREATE INDEX IF NOT EXISTS spoonbill_snapshot_read_pin_reference
      ON spoonbill_snapshot_read_pin (realm, cookie_namespace, operation_id)""",
    """ALTER TABLE spoonbill_view_snapshot ADD COLUMN IF NOT EXISTS payload_operation UUID"""
  )
}
