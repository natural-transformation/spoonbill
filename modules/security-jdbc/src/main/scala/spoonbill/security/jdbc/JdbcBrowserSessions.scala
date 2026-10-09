package spoonbill.security.jdbc

import java.sql.{Connection, PreparedStatement, SQLException, Timestamp}
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import javax.sql.DataSource
import scala.util.Using
import scala.util.control.NonFatal
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.{SnapshotFormat, SnapshotIdentity, ViewSnapshotError}

/** PostgreSQL-backed, blocking JDBC adapter. Main sources depend only on JDBC.
  * Schedule calls on a host-managed blocking executor. Preparation uses the
  * caller's host transaction; delivery, activation and later validation use
  * separate short transactions. No transaction spans browser interaction.
  *
  * One existing host opaque token is used throughout. Only its SHA-256 hash is
  * stored here. A completion UUID is not a bearer credential: delivery also
  * requires the current high-entropy browser-binding proof, supplied as a hash.
  */
final class JdbcBrowserSessions(
  dataSource: DataSource,
  realm: String,
  cookieNamespace: String,
  clock: () => Instant,
  host: HostSessionHooks,
  maxActiveViews: Int = 16
) {
  require(realm.matches("[A-Za-z0-9_.-]{1,96}"), "Invalid browser authentication realm")
  require(cookieNamespace.matches("[A-Za-z0-9_.-]{1,96}"), "Invalid browser authentication namespace")
  require(maxActiveViews > 0, "Active view limit must be positive")

  private case class Slot(generation: Long, current: Option[UUID])
  private case class Row(prepared: PreparedBrowserSession, activeGeneration: Option[Long], revoked: Boolean, acknowledged: Boolean)
  private case class View(binding: Digest256, owner: Option[UUID], epoch: Long)

  private[jdbc] def snapshotRealm: String = realm
  private[jdbc] def snapshotCookieNamespace: String = cookieNamespace

  /** Bind to an already claimed view; this factory never claims another owner.
    * Credential hashes remain in this connection adapter, not in the snapshot.
    */
  def snapshots[P](viewId: String, bindingHash: Digest256, ownerId: UUID, ownerEpoch: ViewOwnershipEpoch,
    tokenHash: Digest256, identity: SnapshotIdentity, format: SnapshotFormat[P], identityCheck: HostSnapshotIdentity): JdbcViewSnapshots[P] =
    new JdbcViewSnapshots(this, viewId, bindingHash, ownerId, ownerEpoch, tokenHash, identity, format, identityCheck)

  private[jdbc] def withSnapshotView[A](viewId: String, bindingHash: Digest256, ownerId: UUID,
    ownerEpoch: ViewOwnershipEpoch, tokenHash: Digest256, identity: SnapshotIdentity, identityCheck: HostSnapshotIdentity)(
    run: (Connection, ViewRevision) => Either[ViewSnapshotError, A]
  ): Either[ViewSnapshotError, A] = {
    if (!validViewId(viewId) || ownerEpoch.toLong <= 0) Left(ViewSnapshotError.StaleOwner)
    else JdbcTransactions.run[ViewSnapshotError, A](dataSource, ViewSnapshotError.StorageFailure, ViewSnapshotError.StorageFailure) { connection =>
      try {
        def refuse(error: ViewSnapshotError): Nothing = throw new SnapshotStorageException(error)
        def verify(row: Row, now: Instant): Unit = {
          checkHost(connection, row, bindingHash, now).fold(_ => refuse(ViewSnapshotError.AccessDenied), _ => ())
          if (row.prepared.hostSessionId != identity.sessionId ||
              !identityCheck.isCurrent(connection, row.prepared.hostSessionId, identity, now))
            refuse(ViewSnapshotError.AccessDenied)
        }
        val before = rowByToken(connection, tokenHash).getOrElse(refuse(ViewSnapshotError.AccessDenied))
        // Keep existing host -> slot -> view lock ordering. Both host hooks must
        // acquire required host locks before waiting on framework ownership.
        verify(before, clock())
        val currentSlot = slot(connection, bindingHash).getOrElse(refuse(ViewSnapshotError.AccessDenied))
        val currentView = view(connection, viewId).getOrElse(refuse(ViewSnapshotError.StaleOwner))
        checkView(currentView, bindingHash, ownerId, ownerEpoch.toLong)
          .fold(_ => refuse(ViewSnapshotError.StaleOwner), _ => ())
        val current = rowByToken(connection, tokenHash).getOrElse(refuse(ViewSnapshotError.AccessDenied))
        verify(current, clock())
        val authenticated = active(currentSlot, current).fold(_ => refuse(ViewSnapshotError.AccessDenied), identity => identity)
        if (authenticated.generation != identity.slotGeneration.toLong) refuse(ViewSnapshotError.AccessDenied)
        val revision = statement(connection, """SELECT snapshot_revision FROM spoonbill_browser_view
          WHERE realm = ? AND cookie_namespace = ? AND view_id = ?""") { query =>
          scope(query); query.setString(3, viewId)
          Using.resource(query.executeQuery()) { result =>
            if (!result.next()) refuse(ViewSnapshotError.StaleOwner)
            ViewRevision.fromLong(result.getLong(1)).fold(_ => refuse(ViewSnapshotError.MalformedSnapshot), value => value)
          }
        }
        run(connection, revision)
      } catch {
        case error: SnapshotStorageException => Left(error.error)
        case NonFatal(_) => Left(ViewSnapshotError.StorageFailure)
      }
    }
  }

  /** Explicit migration/bootstrap step. Never called implicitly by requests. */
  def initialize(connection: Connection): Unit = {
    try schema.foreach(sql => Using.resource(connection.createStatement())(_.executeUpdate(sql)))
    catch { case _: SQLException => throw new JdbcAuthException(JdbcAuthError.StorageFailure) }
  }

  /** Capture this generation when the ceremony begins and retain it server-side.
    * Do not reopen/refresh it after factor verification to evade a racing logout.
    * Binding-proof rotation must preserve the recognized browser lineage; this
    * initial adapter expects the stable host binding hash for that lineage.
    */
  def openSlot(bindingHash: Digest256): Either[JdbcAuthError, Long] = transaction { connection =>
    statement(connection, """INSERT INTO spoonbill_browser_slot
      (realm, cookie_namespace, binding_hash, generation) VALUES (?, ?, ?, 0)
      ON CONFLICT (realm, cookie_namespace, binding_hash) DO NOTHING""") { query =>
      scope(query); query.setBytes(3, bindingHash.bytes); query.executeUpdate()
    }
    slot(connection, bindingHash).map(_.generation).toRight(JdbcAuthError.NotFound)
  }

  /** Observe an existing authenticated browser's generation when its ceremony
    * begins, BEFORE factor verification. This read creates no slot, takes no row
    * lock and grants no authority. Retain the result server-side: never refresh
    * or retry it after verification to evade a racing logout. prepare MUST still
    * lock the slot and check expectedSlotGeneration in the host transaction.
    */
  def captureExistingSlotGeneration(bindingHash: Digest256): Either[JdbcAuthError, Long] =
    try {
      val connection = dataSource.getConnection
      // ALLOW-VAR: synchronous owner-thread observation used only for safe disposal.
      var autoCommit = false
      try {
        autoCommit = connection.getAutoCommit
        // A borrowed non-autocommit connection could carry unrelated work.
        // Reject and abort it; ordinary pool return could commit that work.
        if (!autoCommit) Left(JdbcAuthError.StorageFailure)
        else statement(connection, """SELECT generation FROM spoonbill_browser_slot
          WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ?""") { query =>
          scope(query); query.setBytes(3, bindingHash.bytes)
          Using.resource(query.executeQuery()) { rows =>
            if (rows.next()) Right(rows.getLong(1)) else Left(JdbcAuthError.NotFound)
          }
        }
      } finally {
        if (autoCommit) connection.close()
        else JdbcTransactions.dispose(connection, settled = false)
      }
    } catch { case NonFatal(_) => Left(JdbcAuthError.StorageFailure) }

  /** Join the SAME transaction which consumes factors and prepares the host
    * session/completion. This method never commits, rolls back or closes it.
    * A refusal throws and MUST roll back the entire host transaction.
    */
  def prepare(connection: Connection, prepared: PreparedBrowserSession): PreparedReceipt = {
    try {
      if (connection.getAutoCommit) throw new JdbcAuthException(JdbcAuthError.TransactionRequired)
      val normalized = prepared.copy(sessionExpiresAt = micros(prepared.sessionExpiresAt),
        completionExpiresAt = micros(prepared.completionExpiresAt))
      val current = slot(connection, normalized.bindingHash).getOrElse(throw new JdbcAuthException(JdbcAuthError.NotFound))
      rowByCompletion(connection, normalized.completionId) match {
        case Some(existing) if existing.prepared == normalized => receipt(normalized)
        case Some(_) => throw new JdbcAuthException(JdbcAuthError.Conflict)
        case None => insertNewPreparation(connection, normalized, current)
      }
    } catch {
      case error: JdbcAuthException => throw error
      case error: SQLException =>
        throw new JdbcAuthException(if (error.getSQLState == "23505") JdbcAuthError.Conflict else JdbcAuthError.StorageFailure)
    }
  }

  /** Join the caller's host transaction and create new browser records. Unlike
    * prepare, even an exact duplicate fails; no existing receipt is returned.
    * Slot ownership and expiry are checked under the slot lock, and database
    * uniqueness rejects conflicting identities. No freshness assertion from the
    * caller is trusted. A refusal MUST roll back the whole host transaction;
    * this method never commits, rolls back, closes or retries that transaction.
    */
  def prepareNew(connection: Connection, prepared: PreparedBrowserSession): PreparedReceipt = {
    try {
      if (connection.getAutoCommit) throw new JdbcAuthException(JdbcAuthError.TransactionRequired)
      val normalized = prepared.copy(sessionExpiresAt = micros(prepared.sessionExpiresAt),
        completionExpiresAt = micros(prepared.completionExpiresAt))
      val current = slot(connection, normalized.bindingHash).getOrElse(throw new JdbcAuthException(JdbcAuthError.NotFound))
      insertNewPreparation(connection, normalized, current)
    } catch {
      case error: JdbcAuthException => throw error
      case error: SQLException =>
        throw new JdbcAuthException(if (error.getSQLState == "23505") JdbcAuthError.Conflict else JdbcAuthError.StorageFailure)
    }
  }

  private def checkPreparationExpiry(prepared: PreparedBrowserSession): Unit = {
    val now = clock()
    if (!now.isBefore(prepared.sessionExpiresAt) || !now.isBefore(prepared.completionExpiresAt) ||
        prepared.completionExpiresAt.isAfter(prepared.sessionExpiresAt))
      throw new JdbcAuthException(JdbcAuthError.Expired)
  }

  private def insertNewPreparation(connection: Connection, prepared: PreparedBrowserSession, current: Slot): PreparedReceipt = {
    if (prepared.expectedSlotGeneration < 0 || current.generation != prepared.expectedSlotGeneration)
      throw new JdbcAuthException(JdbcAuthError.StaleGeneration)
    checkPreparationExpiry(prepared)
    statement(connection, """WITH inserted_session AS (
      INSERT INTO spoonbill_browser_session
        (realm, cookie_namespace, token_hash, host_session_id, binding_hash, origin_generation, expires_at, revoked)
        VALUES (?, ?, ?, ?, ?, ?, ?, FALSE)
        RETURNING realm, cookie_namespace, token_hash)
      INSERT INTO spoonbill_browser_completion
        (realm, cookie_namespace, completion_id, host_completion_id, token_hash, expires_at)
        SELECT realm, cookie_namespace, ?, ?, token_hash, ? FROM inserted_session""") { query =>
      scope(query); query.setBytes(3, prepared.tokenHash.bytes); query.setObject(4, prepared.hostSessionId)
      query.setBytes(5, prepared.bindingHash.bytes); query.setLong(6, prepared.expectedSlotGeneration)
      query.setTimestamp(7, Timestamp.from(prepared.sessionExpiresAt))
      query.setObject(8, prepared.completionId); query.setObject(9, prepared.hostCompletionId)
      query.setTimestamp(10, Timestamp.from(prepared.completionExpiresAt))
      if (query.executeUpdate() != 1) throw new JdbcAuthException(JdbcAuthError.StorageFailure)
    }
    // A conflicting unique-key insert on another slot can wait and then roll
    // back. Its wait must not let newly inserted preparation outlive admission.
    checkPreparationExpiry(prepared)
    receipt(prepared)
  }

  /** Authorizes host token delivery, without activating a session. The host then
    * decrypts its existing completion and emits Set-Cookie in the HTTP response.
    * A race after this decision can install a stale cookie; activation rejects it.
    */
  def delivery(completionId: UUID, bindingHash: Digest256): Either[JdbcAuthError, DeliveryPermit] = transaction { connection =>
    val now = clock()
    rowByCompletion(connection, completionId).toRight(JdbcAuthError.NotFound).flatMap { row =>
      checkHost(connection, row, bindingHash, now).flatMap { _ =>
        slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { current =>
          val latest = rowByCompletion(connection, completionId).getOrElse(throw new JdbcAuthException(JdbcAuthError.NotFound))
          val decisionTime = clock()
          checkHost(connection, latest, bindingHash, decisionTime).flatMap { _ =>
            pending(current, latest, decisionTime).map(_ => DeliveryPermit(completionId, latest.prepared.hostCompletionId, latest.prepared.hostSessionId))
          }
        }
      }
    }
  }

  /** The first successful slot activation CAS wins. An active-cookie retry
    * validates the existing session even after completion delivery has expired.
    */
  def activate(tokenHash: Digest256, bindingHash: Digest256): Either[JdbcAuthError, ActiveBrowserSession] = transaction { connection =>
    val now = clock()
    rowByToken(connection, tokenHash).toRight(JdbcAuthError.NotFound).flatMap { row =>
      checkHost(connection, row, bindingHash, now).flatMap { _ =>
        slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { current =>
          val latest = rowByToken(connection, tokenHash).getOrElse(throw new JdbcAuthException(JdbcAuthError.NotFound))
          val decisionTime = clock()
          checkHost(connection, latest, bindingHash, decisionTime).flatMap { _ =>
            latest.activeGeneration match {
              case Some(_) => active(current, latest)
              case None => pending(current, latest, decisionTime).flatMap { _ =>
                nextGeneration(current).map { next =>
                  statement(connection, """UPDATE spoonbill_browser_slot SET generation = ?, current_session_id = ?
                    WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ? AND generation = ?""") { query =>
                    query.setLong(1, next); query.setObject(2, latest.prepared.hostSessionId)
                    query.setString(3, realm); query.setString(4, cookieNamespace); query.setBytes(5, bindingHash.bytes)
                    query.setLong(6, current.generation)
                    if (query.executeUpdate() != 1) throw new JdbcAuthException(JdbcAuthError.StaleGeneration)
                  }
                  statement(connection, """UPDATE spoonbill_browser_session SET active_generation = ?
                    WHERE realm = ? AND cookie_namespace = ? AND token_hash = ?""") { query =>
                    query.setLong(1, next); query.setString(2, realm); query.setString(3, cookieNamespace)
                    query.setBytes(4, tokenHash.bytes)
                    if (query.executeUpdate() != 1) throw new JdbcAuthException(JdbcAuthError.NotFound)
                  }
                  statement(connection, """UPDATE spoonbill_browser_completion SET acknowledged_at = ?
                    WHERE realm = ? AND cookie_namespace = ? AND completion_id = ?""") { query =>
                    query.setTimestamp(1, Timestamp.from(decisionTime)); query.setString(2, realm); query.setString(3, cookieNamespace)
                    query.setObject(4, latest.prepared.completionId)
                    if (query.executeUpdate() != 1) throw new JdbcAuthException(JdbcAuthError.NotFound)
                  }
                  host.acknowledge(connection, latest.prepared.hostCompletionId, latest.prepared.hostSessionId, decisionTime)
                  ActiveBrowserSession(latest.prepared.hostSessionId, next, latest.prepared.sessionExpiresAt)
                }
              }
            }
          }
        }
      }
    }
  }

  def validate(tokenHash: Digest256, bindingHash: Digest256): Either[JdbcAuthError, ActiveBrowserSession] = transaction { connection =>
    val now = clock()
    rowByToken(connection, tokenHash).toRight(JdbcAuthError.NotFound).flatMap { row =>
      checkHost(connection, row, bindingHash, now).flatMap { _ =>
        slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { current =>
          val latest = rowByToken(connection, tokenHash).getOrElse(throw new JdbcAuthException(JdbcAuthError.NotFound))
          checkHost(connection, latest, bindingHash, clock()).flatMap(_ => active(current, latest))
        }
      }
    }
  }

  /** Caller has validated the binding cookie and same-origin/CSRF contract.
    * Logout targets the slot, including all pending outcomes at its generation.
    */
  def logout(bindingHash: Digest256): Either[JdbcAuthError, Long] = transaction { connection =>
    slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { current =>
      nextGeneration(current).map { next =>
        statement(connection, """UPDATE spoonbill_browser_slot SET generation = ?, current_session_id = NULL
          WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ?""") { query =>
          query.setLong(1, next); query.setString(2, realm); query.setString(3, cookieNamespace)
          query.setBytes(4, bindingHash.bytes); query.executeUpdate()
        }
        next
      }
    }
  }

  def revoke(hostSessionId: UUID): Either[JdbcAuthError, Unit] = transaction { connection =>
    row(connection, "s.host_session_id = ?")(_.setObject(3, hostSessionId)).toRight(JdbcAuthError.NotFound).flatMap { found =>
      slot(connection, found.prepared.bindingHash).toRight(JdbcAuthError.NotFound).map { _ =>
        statement(connection, """UPDATE spoonbill_browser_session SET revoked = TRUE
          WHERE realm = ? AND cookie_namespace = ? AND host_session_id = ?""") { query =>
          scope(query); query.setObject(3, hostSessionId); query.executeUpdate(); ()
        }
      }
    }
  }

  /** Server-owned connection identity fences a view; neither view ID nor owner
    * UUID authenticates its user. Session validation is independently required.
    * Reconnecting the same view preserves its binding and advances the epoch.
    * Inactive rows are retained as tombstones; retention/cleanup is a separate
    * operational policy, not a silent reset of replay/fencing history.
    */
  def claimView(viewId: String, bindingHash: Digest256, ownerId: UUID): Either[JdbcAuthError, Long] =
    claim(viewId, bindingHash, ownerId, existingOnly = false)

  /** Resume an existing durable view without allocating arbitrary client IDs. */
  def claimExistingView(viewId: String, bindingHash: Digest256, ownerId: UUID): Either[JdbcAuthError, Long] =
    claim(viewId, bindingHash, ownerId, existingOnly = true)

  private def claim(viewId: String, bindingHash: Digest256, ownerId: UUID, existingOnly: Boolean): Either[JdbcAuthError, Long] =
    if (!validViewId(viewId)) Left(JdbcAuthError.InvalidViewId)
    else transaction { connection =>
      slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { _ =>
        val existing = view(connection, viewId)
        if (existingOnly && existing.isEmpty) Left(JdbcAuthError.NotFound)
        else if (existing.exists(_.binding != bindingHash)) Left(JdbcAuthError.BindingMismatch)
        else if (existing.exists(_.epoch == Long.MaxValue)) Left(JdbcAuthError.GenerationExhausted)
        else if (!existing.exists(_.owner.isDefined) && activeViewCount(connection, bindingHash) >= maxActiveViews)
          Left(JdbcAuthError.ViewCapacityExceeded)
        else {
          val epoch = existing.fold(1L)(_.epoch + 1L)
          existing match {
            case None => statement(connection, """INSERT INTO spoonbill_browser_view
              (realm, cookie_namespace, view_id, binding_hash, owner_id, epoch) VALUES (?, ?, ?, ?, ?, ?)""") { query =>
              scope(query); query.setString(3, viewId); query.setBytes(4, bindingHash.bytes)
              query.setObject(5, ownerId); query.setLong(6, epoch); query.executeUpdate()
            }
            case Some(_) => statement(connection, """UPDATE spoonbill_browser_view SET owner_id = ?, epoch = ?
              WHERE realm = ? AND cookie_namespace = ? AND view_id = ?""") { query =>
              query.setObject(1, ownerId); query.setLong(2, epoch); query.setString(3, realm)
              query.setString(4, cookieNamespace); query.setString(5, viewId); query.executeUpdate()
            }
          }
          Right(epoch)
        }
      }
    }

  /** Instantaneous receiver-side validation, not a lease for later external IO.
    * Runtime state/output receivers must check the epoch when applying results;
    * in-flight domain writes require their own transaction/idempotency contract.
    */
  def validateView(viewId: String, bindingHash: Digest256, ownerId: UUID, epoch: Long): Either[JdbcAuthError, Unit] =
    if (!validViewId(viewId)) Left(JdbcAuthError.InvalidViewId)
    else transaction { connection =>
      slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { _ =>
        view(connection, viewId).toRight(JdbcAuthError.NotFound).flatMap(current => checkView(current, bindingHash, ownerId, epoch))
      }
    }

  def releaseView(viewId: String, bindingHash: Digest256, ownerId: UUID, epoch: Long): Either[JdbcAuthError, Unit] =
    if (!validViewId(viewId)) Left(JdbcAuthError.InvalidViewId)
    else transaction { connection =>
      slot(connection, bindingHash).toRight(JdbcAuthError.NotFound).flatMap { _ =>
        view(connection, viewId).toRight(JdbcAuthError.NotFound).flatMap { current =>
          checkView(current, bindingHash, ownerId, epoch).flatMap { _ =>
            if (current.epoch == Long.MaxValue) Left(JdbcAuthError.GenerationExhausted)
            else {
              statement(connection, """UPDATE spoonbill_browser_view SET owner_id = NULL, epoch = ?
                WHERE realm = ? AND cookie_namespace = ? AND view_id = ?""") { query =>
                query.setLong(1, epoch + 1L); query.setString(2, realm); query.setString(3, cookieNamespace)
                query.setString(4, viewId); query.executeUpdate()
              }
              Right(())
            }
          }
        }
      }
    }

  private def validViewId(value: String): Boolean =
    value.nonEmpty && value.length <= 192 && value.forall(character => character >= ' ' && character <= '~')

  private def checkView(value: View, binding: Digest256, owner: UUID, epoch: Long): Either[JdbcAuthError, Unit] =
    if (value.binding != binding) Left(JdbcAuthError.BindingMismatch)
    else if (value.epoch != epoch || !value.owner.contains(owner)) Left(JdbcAuthError.StaleViewFence)
    else Right(())

  private def view(connection: Connection, id: String): Option[View] =
    statement(connection, """SELECT binding_hash, owner_id, epoch FROM spoonbill_browser_view
      WHERE realm = ? AND cookie_namespace = ? AND view_id = ? FOR UPDATE""") { query =>
      scope(query); query.setString(3, id)
      Using.resource(query.executeQuery()) { result =>
        if (!result.next()) None
        else {
          val binding = Digest256.fromBytes(result.getBytes(1)).fold(_ => throw new JdbcAuthException(JdbcAuthError.StorageFailure), identity)
          Some(View(binding, Option(result.getObject(2, classOf[UUID])), result.getLong(3)))
        }
      }
    }

  private def activeViewCount(connection: Connection, binding: Digest256): Long =
    statement(connection, """SELECT COUNT(*) FROM spoonbill_browser_view
      WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ? AND owner_id IS NOT NULL""") { query =>
      scope(query); query.setBytes(3, binding.bytes)
      Using.resource(query.executeQuery()) { result =>
        if (result.next()) result.getLong(1) else throw new JdbcAuthException(JdbcAuthError.StorageFailure)
      }
    }

  private def checkHost(connection: Connection, row: Row, binding: Digest256, now: Instant): Either[JdbcAuthError, Unit] =
    if (row.prepared.bindingHash != binding) Left(JdbcAuthError.BindingMismatch)
    else checkSession(row, now).flatMap { _ =>
      if (host.isCurrent(connection, row.prepared.hostSessionId, now)) Right(()) else Left(JdbcAuthError.HostDenied)
    }

  private def checkSession(row: Row, now: Instant): Either[JdbcAuthError, Unit] =
    if (row.revoked) Left(JdbcAuthError.Revoked)
    else if (!now.isBefore(row.prepared.sessionExpiresAt)) Left(JdbcAuthError.Expired)
    else Right(())

  private def pending(current: Slot, row: Row, now: Instant): Either[JdbcAuthError, Unit] =
    checkSession(row, now).flatMap { _ =>
      if (current.generation != row.prepared.expectedSlotGeneration) Left(JdbcAuthError.StaleGeneration)
      else if (row.acknowledged || row.activeGeneration.isDefined) Left(JdbcAuthError.AlreadyAcknowledged)
      else if (!now.isBefore(row.prepared.completionExpiresAt)) Left(JdbcAuthError.Expired)
      else Right(())
    }

  private def active(current: Slot, row: Row): Either[JdbcAuthError, ActiveBrowserSession] =
    if (row.activeGeneration.contains(current.generation) && current.current.contains(row.prepared.hostSessionId) && row.acknowledged)
      Right(ActiveBrowserSession(row.prepared.hostSessionId, current.generation, row.prepared.sessionExpiresAt))
    else Left(JdbcAuthError.StaleGeneration)

  private def nextGeneration(slot: Slot): Either[JdbcAuthError, Long] =
    if (slot.generation == Long.MaxValue) Left(JdbcAuthError.GenerationExhausted) else Right(slot.generation + 1L)

  private def receipt(value: PreparedBrowserSession): PreparedReceipt =
    PreparedReceipt(value.completionId, value.hostSessionId, value.expectedSlotGeneration)

  private def scope(query: PreparedStatement): Unit = { query.setString(1, realm); query.setString(2, cookieNamespace) }
  private def micros(value: Instant): Instant = value.truncatedTo(ChronoUnit.MICROS)

  private def slot(connection: Connection, binding: Digest256): Option[Slot] =
    statement(connection, """SELECT generation, current_session_id FROM spoonbill_browser_slot
      WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ? FOR UPDATE""") { query =>
      scope(query); query.setBytes(3, binding.bytes)
      Using.resource(query.executeQuery()) { result =>
        if (result.next()) Some(Slot(result.getLong(1), Option(result.getObject(2, classOf[UUID])))) else None
      }
    }

  private def rowByCompletion(connection: Connection, id: UUID): Option[Row] =
    row(connection, "c.completion_id = ?")(_.setObject(3, id))
  private def rowByToken(connection: Connection, hash: Digest256): Option[Row] =
    row(connection, "s.token_hash = ?")(_.setBytes(3, hash.bytes))

  private def row(connection: Connection, selector: String)(bind: PreparedStatement => Unit): Option[Row] =
    statement(connection, s"""SELECT c.completion_id, c.host_completion_id, s.host_session_id,
      s.binding_hash, s.token_hash, s.origin_generation, s.expires_at AS session_expiry,
      c.expires_at AS completion_expiry, s.active_generation, s.revoked, c.acknowledged_at
      FROM spoonbill_browser_completion c JOIN spoonbill_browser_session s
      ON s.realm = c.realm AND s.cookie_namespace = c.cookie_namespace AND s.token_hash = c.token_hash
      WHERE c.realm = ? AND c.cookie_namespace = ? AND $selector""") { query =>
      scope(query); bind(query)
      Using.resource(query.executeQuery()) { result =>
        if (!result.next()) None
        else {
          def digest(column: String): Digest256 = Digest256.fromBytes(result.getBytes(column))
            .fold(_ => throw new JdbcAuthException(JdbcAuthError.StorageFailure), identity)
          val prepared = PreparedBrowserSession(result.getObject("completion_id", classOf[UUID]),
            result.getObject("host_completion_id", classOf[UUID]), result.getObject("host_session_id", classOf[UUID]),
            digest("binding_hash"), digest("token_hash"), result.getLong("origin_generation"),
            result.getTimestamp("session_expiry").toInstant, result.getTimestamp("completion_expiry").toInstant)
          Some(Row(prepared, Option(result.getObject("active_generation", classOf[java.lang.Long])).map(_.longValue),
            result.getBoolean("revoked"), Option(result.getTimestamp("acknowledged_at")).isDefined))
        }
      }
    }

  private def statement[A](connection: Connection, sql: String)(run: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(run)

  private def transaction[A](run: Connection => Either[JdbcAuthError, A]): Either[JdbcAuthError, A] = {
    JdbcTransactions.run[JdbcAuthError, A](dataSource, JdbcAuthError.StorageFailure, JdbcAuthError.StorageFailure) { connection =>
      try run(connection) catch {
        case error: JdbcAuthException => Left(error.error)
        case NonFatal(_) => Left(JdbcAuthError.StorageFailure)
      }
    }
  }

  private val schema = Vector(
    """CREATE TABLE IF NOT EXISTS spoonbill_browser_slot (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL,
      binding_hash BYTEA NOT NULL CHECK (octet_length(binding_hash) = 32),
      generation BIGINT NOT NULL CHECK (generation >= 0), current_session_id UUID,
      PRIMARY KEY (realm, cookie_namespace, binding_hash))""",
    """CREATE TABLE IF NOT EXISTS spoonbill_browser_session (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL,
      token_hash BYTEA NOT NULL CHECK (octet_length(token_hash) = 32), host_session_id UUID NOT NULL,
      binding_hash BYTEA NOT NULL, origin_generation BIGINT NOT NULL CHECK (origin_generation >= 0),
      active_generation BIGINT CHECK (active_generation > origin_generation),
      expires_at TIMESTAMP WITH TIME ZONE NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE,
      PRIMARY KEY (realm, cookie_namespace, token_hash), UNIQUE (realm, cookie_namespace, host_session_id),
      FOREIGN KEY (realm, cookie_namespace, binding_hash) REFERENCES spoonbill_browser_slot (realm, cookie_namespace, binding_hash))""",
    """CREATE TABLE IF NOT EXISTS spoonbill_browser_completion (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL,
      completion_id UUID NOT NULL, host_completion_id UUID NOT NULL, token_hash BYTEA NOT NULL,
      expires_at TIMESTAMP WITH TIME ZONE NOT NULL, acknowledged_at TIMESTAMP WITH TIME ZONE,
      PRIMARY KEY (realm, cookie_namespace, completion_id), UNIQUE (realm, cookie_namespace, token_hash),
      FOREIGN KEY (realm, cookie_namespace, token_hash) REFERENCES spoonbill_browser_session (realm, cookie_namespace, token_hash))""",
    """CREATE TABLE IF NOT EXISTS spoonbill_browser_view (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL, view_id VARCHAR(192) NOT NULL,
      binding_hash BYTEA NOT NULL, owner_id UUID, epoch BIGINT NOT NULL CHECK (epoch > 0),
      PRIMARY KEY (realm, cookie_namespace, view_id),
      FOREIGN KEY (realm, cookie_namespace, binding_hash) REFERENCES spoonbill_browser_slot (realm, cookie_namespace, binding_hash))""",
    """CREATE INDEX IF NOT EXISTS spoonbill_browser_view_active_binding
      ON spoonbill_browser_view (realm, cookie_namespace, binding_hash) WHERE owner_id IS NOT NULL""",
    """ALTER TABLE spoonbill_browser_view ADD COLUMN IF NOT EXISTS snapshot_revision
      BIGINT NOT NULL DEFAULT 0 CHECK (snapshot_revision >= 0)""",
    """CREATE TABLE IF NOT EXISTS spoonbill_view_snapshot (
      realm VARCHAR(96) NOT NULL, cookie_namespace VARCHAR(96) NOT NULL, view_id VARCHAR(192) NOT NULL,
      revision BIGINT NOT NULL CHECK (revision > 0), subject_key VARCHAR(256) NOT NULL, scope_key VARCHAR(256) NOT NULL,
      session_id UUID NOT NULL, security_generation BIGINT NOT NULL CHECK (security_generation >= 0),
      slot_generation BIGINT NOT NULL CHECK (slot_generation >= 0), schema_id VARCHAR(96) NOT NULL,
      schema_version INTEGER NOT NULL CHECK (schema_version > 0), payload BYTEA NOT NULL,
      FOREIGN KEY (realm, cookie_namespace, view_id) REFERENCES spoonbill_browser_view (realm, cookie_namespace, view_id),
      PRIMARY KEY (realm, cookie_namespace, view_id))"""
  )
}
