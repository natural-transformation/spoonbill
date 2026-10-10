package spoonbill.security.jdbc.baseline

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, Timestamp}
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
import spoonbill.effect.Effect
import spoonbill.security.jdbc.*
import spoonbill.security.transaction.*

/**
 * v3 baseline consumer integration, not a proposed framework API. All code in
 * this file counts as consumer production integration, including SQL and
 * crypto. The fixture uses synthetic SHA-256 password/factor proofs;
 * applications supply their password verifier and rate-limit policy. No real
 * identities are used.
 *
 * Atomic domain: one PostgreSQL connection covers ceremony proof consumption,
 * account policy, host session, browser preparation, encrypted completion and
 * audit. Lock order is ceremony -> account -> host session -> browser slot.
 * Delivery's host hooks take account -> host session before the browser slot.
 * No transaction spans proof computation or browser interaction.
 *
 * v3 needs an explicit admission transaction before dispatch. Its retained
 * ceremony state prevents reissuing a proof after rollback/restart. That extra
 * transaction is part of the baseline, not hidden from the code/SQL inventory.
 */
final class JdbcReferenceHost(
  source: DataSource,
  blockingContext: ExecutionContext,
  val realm: String,
  val namespace: String,
  clock: () => Instant,
  randomBytes: Int => Array[Byte],
  newId: () => UUID,
  material: JdbcReferenceHost.MaterialCipher,
  verifyPassword: (String, Array[Byte]) => Boolean = JdbcReferenceHost.syntheticVerify,
  admitProof: () => Boolean = () => true,
  maxCeremonies: Int = 1024,
  existingRunner: Option[TransactionExecutor[Future, Direct, Connection]] = None,
  maxAudits: Int = 1024,
  maxCeremoniesPerBinding: Int = 16,
  sharedClock: Option[JdbcReferenceClock] = None
)(using effect: Effect[Future]) {
  import JdbcReferenceHost.*
  require(maxCeremonies > 0, "Ceremony capacity must be positive")
  require(maxAudits > 0, "Audit capacity must be positive")
  require(maxCeremoniesPerBinding > 0, "Per-binding ceremony capacity must be positive")
  private given ExecutionContext = blockingContext
  // Trusted infrastructure supplies actual outermost settlement, not a Boolean
  // asserted by an action handler. The selected runner owns this transaction;
  // browser preparation only uses its connection and never starts another one.
  // Existing runners without joined execution/settlement are unsupported.
  private val runner         = existingRunner.getOrElse(new JdbcTransactionExecutor[Future](source, blockingContext))
  private val durableClock   = sharedClock.getOrElse(new JdbcReferenceClock(source, realm, namespace, clock))
  private def now(): Instant = durableClock.now()

  private def reject(error: OperationError = OperationError.HostDenied): Nothing =
    throw new OperationProtocolException(error)
  private def statement[A](connection: Connection, sql: String)(run: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(sql))(run)
  private def digest(bytes: Array[Byte]): Digest256 =
    Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(bytes)).fold(_ => reject(), identity)

  val hooks: HostSessionHooks = new HostSessionHooks {
    def isCurrent(connection: Connection, sessionId: UUID, time: Instant): Boolean = {
      // Discover the immutable account ID first, then lock in the documented order.
      val subject = statement(connection, "SELECT subject FROM baseline_session WHERE id=?") { query =>
        query.setObject(1, sessionId)
        Using.resource(query.executeQuery())(rows => if (rows.next()) Some(rows.getObject(1, classOf[UUID])) else None)
      }
      subject.exists { id =>
        val account = readAccount(connection, id, lock = true)
        statement(connection, "SELECT version, valid, expires_at FROM baseline_session WHERE id=? FOR UPDATE") {
          query =>
            query.setObject(1, sessionId)
            Using.resource(query.executeQuery()) { rows =>
              rows.next() && account.enabled && rows.getBoolean(2) && rows.getLong(1) == account.version &&
              time.isBefore(rows.getTimestamp(3).toInstant)
            }
        }
      }
    }
    def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit = {
      statement(connection, "UPDATE baseline_session SET acknowledged=TRUE WHERE id=? AND attempt=?") { query =>
        query.setObject(1, sessionId); query.setObject(2, completionId)
        if (query.executeUpdate() != 1) reject()
      }
      // Activation is the terminal acknowledgment: future redelivery is denied.
      statement(connection, "DELETE FROM baseline_material WHERE attempt=?") { query =>
        query.setObject(1, completionId); query.executeUpdate(); ()
      }
    }
  }
  val browser = new JdbcBrowserSessions(source, realm, namespace, () => now(), hooks)

  /** Explicit deployment/bootstrap, never called on a request path. */
  def initialize(connection: Connection): Unit = {
    durableClock.initialize(connection)
    browser.initialize(connection)
    schema.foreach(sql => Using.resource(connection.createStatement())(_.executeUpdate(sql)))
  }

  private case class Account(version: Long, enabled: Boolean, password: Array[Byte], factor: Option[Array[Byte]])
  private def readAccount(connection: Connection, subject: UUID, lock: Boolean): Account =
    statement(
      connection,
      "SELECT version, enabled, password_hash, factor_hash FROM baseline_account WHERE id=?" +
        (if (lock) " FOR UPDATE" else "")
    ) { query =>
      query.setObject(1, subject)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject()
        Account(rows.getLong(1), rows.getBoolean(2), rows.getBytes(3), Option(rows.getBytes(4)))
      }
    }

  private case class Ceremony(
    id: UUID,
    attempt: UUID,
    binding: Digest256,
    generation: Long,
    expires: Instant,
    subject: Option[UUID],
    version: Long,
    challenge: Option[UUID],
    state: String
  )
  private def readCeremony(connection: Connection, id: UUID, binding: Digest256): Ceremony =
    statement(
      connection,
      "SELECT attempt, binding, generation, expires_at, subject, version, challenge, state FROM baseline_ceremony WHERE id=? FOR UPDATE"
    ) { query =>
      query.setObject(1, id)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject()
        val storedBinding = Digest256.fromBytes(rows.getBytes(2)).fold(_ => reject(), identity)
        if (storedBinding != binding) reject()
        Ceremony(
          id,
          rows.getObject(1, classOf[UUID]),
          storedBinding,
          rows.getLong(3),
          rows.getTimestamp(4).toInstant,
          Option(rows.getObject(5, classOf[UUID])),
          rows.getLong(6),
          Option(rows.getObject(7, classOf[UUID])),
          rows.getString(8)
        )
      }
    }
  private def fresh(ceremony: Ceremony): Unit =
    if (!now().isBefore(ceremony.expires)) reject(OperationError.Expired)
  private def state(connection: Connection, ceremony: UUID, value: String): Unit =
    statement(connection, "UPDATE baseline_ceremony SET state=? WHERE id=?") { query =>
      query.setString(1, value); query.setObject(2, ceremony); query.executeUpdate(); ()
    }

  /**
   * The binding is resolved by trusted transport code from its cookie, never
   * accepted from UI state. Bootstrap must already have called
   * browser.openSlot.
   */
  def begin(
    binding: Digest256,
    checkConnection: Connection => Unit = _ => ()
  ): Future[Either[TransactionFailure, UUID]] = runner.transact { connection =>
    // Admission's table lock must precede the slot lock: completion updates the
    // ceremony before locking its slot, so reversing these would deadlock.
    Using.resource(connection.createStatement())(_.execute("LOCK TABLE baseline_ceremony IN SHARE ROW EXCLUSIVE MODE"))
    checkConnection(connection)
    // Slot locking also serializes the bound admission/capacity observation.
    val generation = statement(
      connection,
      "SELECT generation FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=? FOR UPDATE"
    ) { query =>
      query.setString(1, realm); query.setString(2, namespace); query.setBytes(3, binding.bytes)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject(); rows.getLong(1)
      }
    }
    // One fresh aggregate under the existing locks preserves both retained
    // bounds without another frontend execution. Challenges share their row.
    val (count, bindingCount) =
      statement(connection, "SELECT count(*),count(*) FILTER (WHERE binding=?) FROM baseline_ceremony") { query =>
        query.setBytes(1, binding.bytes)
        Using.resource(query.executeQuery()) { rows =>
          rows.next(); rows.getLong(1) -> rows.getLong(2)
        }
      }
    if (count >= maxCeremonies || bindingCount >= maxCeremoniesPerBinding) reject(OperationError.CapacityExceeded)
    val id = newId()
    statement(
      connection,
      "INSERT INTO baseline_ceremony(id,attempt,binding,generation,expires_at,state) VALUES (?,?,?,?,?,'begun')"
    ) { query =>
      query.setObject(1, id); query.setObject(2, newId()); query.setBytes(3, binding.bytes)
      query.setLong(4, generation); query.setTimestamp(5, Timestamp.from(now().plusSeconds(60))); query.executeUpdate()
    }
    id
  }

  final class Proof private[JdbcReferenceHost] (
    private[JdbcReferenceHost] val ceremony: UUID,
    private[JdbcReferenceHost] val binding: Digest256
  ) {
    private[JdbcReferenceHost] val issuer  = JdbcReferenceHost.this
    private[JdbcReferenceHost] val claimed = new AtomicBoolean(false)
    override def toString: String          = "ReferenceProof(<redacted>)"
  }
  enum PasswordResult {
    case Ready(proof: Proof)
    case Challenge(ceremony: UUID, challenge: UUID, subject: UUID)
  }

  def password(
    ceremonyId: UUID,
    binding: Digest256,
    subject: UUID,
    password: String,
    checkConnection: Connection => Unit = _ => ()
  ): Future[Either[TransactionFailure, PasswordResult]] =
    if (!admitProof()) Future.successful(Left(TransactionFailure.Rejected(OperationError.CapacityExceeded)))
    else
      runner.transact { connection =>
        checkConnection(connection)
        readAccount(connection, subject, lock = false)
      }.flatMap {
        case Left(error)     => Future.successful(Left(error))
        case Right(observed) =>
          // Hashing finishes outside the authoritative transaction, on the worker.
          val verified = verifyPassword(password, observed.password)
          runner.transact { connection =>
            val ceremony = readCeremony(connection, ceremonyId, binding)
            fresh(ceremony)
            if (ceremony.state != "begun" || !verified) reject()
            val current = readAccount(connection, subject, lock = true)
            checkConnection(connection)
            fresh(ceremony)
            if (!current.enabled || current.version != observed.version) reject()
            val challenge = current.factor.map(_ => newId())
            statement(connection, "UPDATE baseline_ceremony SET subject=?,version=?,challenge=?,state=? WHERE id=?") {
              query =>
                query.setObject(1, subject); query.setLong(2, current.version); query.setObject(3, challenge.orNull)
                query.setString(4, if (challenge.isDefined) "challenged" else "admitted")
                query.setObject(5, ceremonyId); query.executeUpdate()
            }
            challenge.fold[PasswordResult](PasswordResult.Ready(new Proof(ceremonyId, binding))) { id =>
              PasswordResult.Challenge(ceremonyId, id, subject)
            }
          }
      }

  def factor(
    ceremonyId: UUID,
    binding: Digest256,
    subject: UUID,
    challenge: UUID,
    value: String,
    checkConnection: Connection => Unit = _ => ()
  ): Future[Either[TransactionFailure, Proof]] =
    verifyFactor(ceremonyId, binding, Some(subject), challenge, value, checkConnection)

  /**
   * Browser handlers resume the stored association, never a UI subject field.
   */
  def factorForCeremony(
    ceremonyId: UUID,
    binding: Digest256,
    challenge: UUID,
    value: String,
    checkConnection: Connection => Unit
  ): Future[Either[TransactionFailure, Proof]] =
    verifyFactor(ceremonyId, binding, None, challenge, value, checkConnection)

  private def verifyFactor(
    ceremonyId: UUID,
    binding: Digest256,
    expectedSubject: Option[UUID],
    challenge: UUID,
    value: String,
    checkConnection: Connection => Unit
  ): Future[Either[TransactionFailure, Proof]] =
    if (!admitProof()) Future.successful(Left(TransactionFailure.Rejected(OperationError.CapacityExceeded)))
    else {
      val submitted = syntheticHash(value)
      runner.transact { connection =>
        val ceremony = readCeremony(connection, ceremonyId, binding)
        fresh(ceremony)
        val subject = ceremony.subject.getOrElse(reject())
        if (
          ceremony.state != "challenged" || expectedSubject
            .exists(_ != subject) || !ceremony.challenge.contains(challenge)
        ) reject()
        val account = readAccount(connection, subject, lock = true)
        checkConnection(connection)
        fresh(ceremony)
        if (
          !account.enabled || account.version != ceremony.version ||
          !account.factor.exists(MessageDigest.isEqual(_, submitted))
        ) reject()
        state(connection, ceremonyId, "admitted")
        new Proof(ceremonyId, binding)
      }
    }

  /**
   * No transaction-local receipt escapes: Right follows actual outer commit.
   * afterStage is a test-only failure/barrier port and is excluded from
   * latency.
   */
  def complete(
    proof: Proof,
    afterStage: String => Unit = _ => (),
    checkConnection: Connection => Unit = _ => ()
  ): Future[Either[TransactionFailure, UUID]] =
    if (!(proof.issuer eq this) || !proof.claimed.compareAndSet(false, true))
      Future.successful(Left(TransactionFailure.Rejected(OperationError.PermitUsed)))
    else
      runner.transact { connection =>
        val ceremony = readCeremony(connection, proof.ceremony, proof.binding)
        fresh(ceremony)
        if (ceremony.state != "admitted") reject(OperationError.NotPrepared)
        admitAudit(connection, maxAudits)
        val subject = ceremony.subject.getOrElse(reject())
        val account = readAccount(connection, subject, lock = true)
        checkConnection(connection)
        fresh(ceremony)
        if (!account.enabled || account.version != ceremony.version) reject()
        state(connection, ceremony.id, "consumed")
        afterStage("proof")
        val session    = newId()
        val credential = randomBytes(32)
        try {
          require(credential.length == 32, "A transport credential requires 32 entropy bytes")
          val prepared = PreparedBrowserSession(
            ceremony.attempt,
            ceremony.attempt,
            session,
            ceremony.binding,
            digest(Base64.getUrlEncoder.withoutPadding().encode(credential)),
            ceremony.generation,
            now().plusSeconds(600),
            ceremony.expires
          )
          statement(
            connection,
            "INSERT INTO baseline_session(id,attempt,subject,version,expires_at,valid) VALUES (?,?,?,?,?,TRUE)"
          ) { query =>
            query.setObject(1, session); query.setObject(2, ceremony.attempt); query.setObject(3, subject)
            query.setLong(4, account.version); query.setTimestamp(5, Timestamp.from(prepared.sessionExpiresAt));
            query.executeUpdate()
          }
          afterStage("session")
          try browser.prepareNew(connection, prepared)
          catch {
            case error: JdbcAuthException =>
              reject(if (error.error == JdbcAuthError.Expired) OperationError.Expired else OperationError.HostDenied)
          }
          afterStage("browser")
          val encrypted = material.seal(credential, associated(ceremony.attempt, ceremony.binding))
          statement(connection, "INSERT INTO baseline_material(attempt,payload) VALUES (?,?)") { query =>
            query.setObject(1, ceremony.attempt); query.setBytes(2, encrypted); query.executeUpdate()
          }
          afterStage("material")
          statement(connection, "INSERT INTO baseline_audit(attempt,subject) VALUES (?,?)") { query =>
            query.setObject(1, ceremony.attempt); query.setObject(2, subject); query.executeUpdate()
          }
          afterStage("audit")
          fresh(ceremony)
          state(connection, ceremony.id, "committed")
          ceremony.attempt
        } finally java.util.Arrays.fill(credential, 0.toByte)
      }

  /**
   * Explicit bounded maintenance. Retire ciphertext only; ceremony and outcome
   * fences remain retained. Never reset generation or reuse an old attempt ID.
   */
  def retireExpiredMaterial(): Future[Either[TransactionFailure, Int]] =
    runner.transact(connection => retireExpiredMaterial(connection))
  private[baseline] def retireExpiredMaterial(connection: Connection): Int =
    statement(
      connection,
      "DELETE FROM baseline_material m USING baseline_ceremony c WHERE m.attempt=c.attempt AND c.expires_at<=?"
    ) { query =>
      query.setTimestamp(1, Timestamp.from(now())); query.executeUpdate()
    }

  /**
   * Locks the same durable row as complete. An absent result never means
   * rollback until this transaction excludes the writer and commits its fence.
   * The returned UUID is status-only lookup metadata, even after expiry or
   * revocation; it is never a restricted delivery receipt. deliver separately
   * checks binding, current host policy, generation and expiry before
   * retrieval.
   */
  def recover(
    ceremonyId: UUID,
    binding: Digest256,
    checkConnection: Connection => Unit = _ => ()
  ): Future[Either[TransactionFailure, Recovery]] =
    runner.transact { connection =>
      val ceremony = readCeremony(connection, ceremonyId, binding)
      checkConnection(connection)
      if (ceremony.state == "committed") Recovery.Committed(ceremony.attempt)
      else {
        state(connection, ceremonyId, "not_committed")
        Recovery.NotCommitted
      }
    }

  /**
   * Trusted bootstrap lookup. Lookup IDs are display metadata, never proof or a
   * delivery receipt. The original ceremony lock excludes an earlier writer
   * before status is observed; an admitted record remains explicitly
   * unresolved. The callback validates the current browser generation under its
   * slot lock.
   */
  private[baseline] def bootstrapRecovery(
    connection: Connection,
    binding: Digest256,
    generation: Long,
    validateGeneration: Long => Unit
  ): Option[RecoveryMetadata] = {
    val candidate = statement(
      connection,
      """SELECT id FROM baseline_ceremony
      WHERE binding=? AND generation=? AND expires_at>? AND state IN ('challenged','admitted','committed')
      ORDER BY expires_at DESC,id DESC LIMIT 1"""
    ) { query =>
      query.setBytes(1, binding.bytes); query.setLong(2, generation); query.setTimestamp(3, Timestamp.from(now()))
      Using.resource(query.executeQuery())(rows => if (rows.next()) Some(rows.getObject(1, classOf[UUID])) else None)
    }
    candidate.flatMap { id =>
      val ceremony = readCeremony(connection, id, binding)
      if (!now().isBefore(ceremony.expires) || !Set("challenged", "admitted", "committed").contains(ceremony.state))
        None
      else {
        val subject = ceremony.subject.getOrElse(reject())
        val account = readAccount(connection, subject, lock = true)
        val sessionCurrent = if (ceremony.state == "committed") {
          val session = statement(connection, "SELECT id FROM baseline_session WHERE attempt=?") { query =>
            query.setObject(1, ceremony.attempt)
            Using.resource(query.executeQuery()) { rows =>
              if (!rows.next()) reject(); rows.getObject(1, classOf[UUID])
            }
          }
          hooks.isCurrent(connection, session, now())
        } else true
        if (!account.enabled || account.version != ceremony.version || !sessionCurrent) None
        else {
          validateGeneration(ceremony.generation)
          if (!now().isBefore(ceremony.expires)) None
          else Some(RecoveryMetadata(ceremony.id, subject, ceremony.challenge, ceremony.state != "challenged"))
        }
      }
    }
  }

  private def associated(attempt: UUID, binding: Digest256): Array[Byte] =
    (realm + ":" + namespace + ":login:" + attempt.toString + ":").getBytes(UTF_8) ++ binding.bytes

  /**
   * Trusted same-origin HTTP completion handler only. The caller must perform
   * exact Origin checking before invoking this method; no raw UI result cache.
   * A logout racing the response may produce a stale cookie: activation denies
   * it.
   */
  def deliver(attempt: UUID, binding: Digest256): Either[JdbcAuthError, CookieCredential] =
    browser.delivery(attempt, binding).flatMap { permit =>
      try
        Using.resource(source.getConnection) { connection =>
          statement(
            connection,
            "UPDATE baseline_material SET deliveries=deliveries+1 WHERE attempt=? AND deliveries<3 RETURNING payload"
          ) { query =>
            query.setObject(1, permit.hostCompletionId)
            Using.resource(query.executeQuery()) { rows =>
              if (!rows.next()) Left(JdbcAuthError.NotFound)
              else {
                val bytes = material.open(rows.getBytes(1), associated(attempt, binding))
                try Right(new CookieCredential(Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)))
                finally java.util.Arrays.fill(bytes, 0.toByte)
              }
            }
          }
        }
      catch { case scala.util.control.NonFatal(_) => Left(JdbcAuthError.StorageFailure) }
    }
}

object JdbcReferenceHost {
  final case class RecoveryMetadata(ceremony: UUID, subject: UUID, challenge: Option[UUID], preparationPending: Boolean)

  /**
   * This host audit is distinct from the framework outcome fence. Lock then
   * count with a fresh statement so competing writers cannot exceed capacity.
   */
  private[baseline] def admitAudit(connection: Connection, capacity: Int): Unit = {
    Using.resource(connection.createStatement())(_.execute("LOCK TABLE baseline_audit IN SHARE ROW EXCLUSIVE MODE"))
    val count = Using.resource(connection.prepareStatement("SELECT count(*) FROM baseline_audit")) { query =>
      Using.resource(query.executeQuery()) { rows =>
        rows.next(); rows.getLong(1)
      }
    }
    if (count >= capacity) throw new OperationProtocolException(OperationError.CapacityExceeded)
  }
  enum Recovery {
    case Committed(attempt: UUID)
    case NotCommitted
  }
  final class CookieCredential private[baseline] (val transportValue: String) {
    def hash: Digest256           = Digest256.fromBytes(syntheticHash(transportValue)).toOption.get
    override def toString: String = "CookieCredential(<redacted>)"
  }
  def syntheticHash(value: String): Array[Byte] = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8))
  def syntheticVerify(value: String, expected: Array[Byte]): Boolean =
    MessageDigest.isEqual(syntheticHash(value), expected)

  /**
   * Explicit infrastructure adapter. Persist key identity with deployment
   * configuration; the fixture's one key must be retained across host restart.
   * Fresh 96-bit nonces are required for each seal under a key. No key in SQL.
   */
  final class MaterialCipher(keyBytes: Array[Byte], nonce: () => Array[Byte]) {
    private val key = new SecretKeySpec(keyBytes.clone(), "AES")
    def seal(plain: Array[Byte], aad: Array[Byte]): Array[Byte] = {
      val iv = nonce()
      require(iv.length == 12, "AES-GCM requires the configured 96-bit nonce")
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv))
      cipher.updateAAD(aad)
      iv ++ cipher.doFinal(plain)
    }
    def open(payload: Array[Byte], aad: Array[Byte]): Array[Byte] = {
      require(payload.length >= 28, "Invalid protected material")
      val cipher = Cipher.getInstance("AES/GCM/NoPadding")
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, payload.take(12)))
      cipher.updateAAD(aad)
      cipher.doFinal(payload.drop(12))
    }
  }

  val schema: Vector[String] = Vector(
    """CREATE TABLE baseline_account(id UUID PRIMARY KEY, version BIGINT NOT NULL,
      enabled BOOLEAN NOT NULL, password_hash BYTEA NOT NULL, factor_hash BYTEA)""",
    """CREATE TABLE baseline_ceremony(id UUID PRIMARY KEY, attempt UUID UNIQUE NOT NULL,
      binding BYTEA NOT NULL, generation BIGINT NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
      subject UUID REFERENCES baseline_account(id), version BIGINT, challenge UUID, state VARCHAR(24) NOT NULL)""",
    """CREATE TABLE baseline_session(id UUID PRIMARY KEY, attempt UUID UNIQUE NOT NULL,
      subject UUID NOT NULL REFERENCES baseline_account(id), version BIGINT NOT NULL,
      expires_at TIMESTAMPTZ NOT NULL, valid BOOLEAN NOT NULL, acknowledged BOOLEAN NOT NULL DEFAULT FALSE)""",
    "CREATE TABLE baseline_material(attempt UUID PRIMARY KEY, payload BYTEA NOT NULL, deliveries INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE baseline_audit(attempt UUID PRIMARY KEY, subject UUID NOT NULL)",
    "CREATE INDEX baseline_ceremony_binding_expiry ON baseline_ceremony(binding,generation,expires_at DESC,id DESC)"
  )
}
