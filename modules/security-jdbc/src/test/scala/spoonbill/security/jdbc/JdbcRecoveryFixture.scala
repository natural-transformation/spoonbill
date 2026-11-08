package spoonbill.security.jdbc

import java.io.PrintWriter
import java.security.SecureRandom
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException, Timestamp}
import java.time.Instant
import java.util.{Base64, Properties, UUID}
import java.util.concurrent.atomic.{AtomicInteger, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.recovery.testkit.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.{Direct, TransactionExecutor, TransactionFailure, TransactionProgram, TransactionScopePolicy}
import spoonbill.server.BrowserSessionToken
import spoonbill.snapshot.{SchemaId, SchemaVersion, SnapshotFormat, StateSchema, ViewSnapshotStore}

final case class RecoveryCounter(value: Int) derives StateSchema

object JdbcRecoveryFixture {
  val format: SnapshotFormat[RecoveryCounter] =
    new SnapshotFormat[RecoveryCounter](SchemaId.parse("recovery").toOption.get, SchemaVersion.fromInt(1).toOption.get)
  val incompatible: SnapshotFormat[RecoveryCounter] =
    new SnapshotFormat[RecoveryCounter](SchemaId.parse("recovery").toOption.get, SchemaVersion.fromInt(2).toOption.get)
  val start: Instant = Instant.parse("2026-11-07T00:00:00Z")
}

/**
 * The real PostgreSQL law host: the JDBC provider with its publication
 * capability, a synthetic host session table, the given payload adapter and
 * gated bindings. Retention, pins, claims and reservations are real rows; the
 * clock is the fixture's, as in the other JDBC suites.
 */
final class JdbcRecoveryFixture(
  val payloads: SnapshotPayloadStore[Future],
  val referenceSettings: SnapshotReferenceSettings = SnapshotReferenceSettings(),
  retention: BrowserRetentionPolicy = BrowserRetentionPolicy()
)(using Effect[Future])
    extends RecoveryFixture[Future, RecoveryCounter] {
  import JdbcRecoveryFixture.*
  private given ExecutionContext = ExecutionContext.global

  final class View private[JdbcRecoveryFixture] (
    private[JdbcRecoveryFixture] val binding: String,
    private[JdbcRecoveryFixture] val token: String,
    val view: BrowserView[String]
  )

  private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", throw new IllegalStateException("Use the disposable PostgreSQL test database"))
  val realm              = "recovery-" + UUID.randomUUID().toString
  val clock              = new AtomicReference(start)
  val transactions       = new AtomicInteger()
  // The index (1-based) of the provider transaction whose acknowledgement is lost after it committed.
  val loseAcknowledgementAt = new AtomicInteger(0)
  private val random     = new SecureRandom()
  val source: DataSource = new DataSource {
    private val driver = new org.postgresql.Driver()
    def getConnection: Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=12000")
      Option(driver.connect(url, properties)).getOrElse(throw new SQLException("Unsupported PostgreSQL test URL"))
    }
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int                                      = 0
    def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.jdbc.recovery.test")
    def isWrapperFor(kind: Class[?]): Boolean                     = false
    def unwrap[T](kind: Class[T]): T                              = throw new SQLException("Not a wrapper")
  }
  private val host = new JdbcHostSessions[String] {
    def resolve(connection: Connection, sessionId: UUID, time: Instant): Option[HostPrincipal[String]] =
      Using.resource(
        connection.prepareStatement(
          "SELECT subject,version,expires_at,enabled FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE"
        )
      ) { query =>
        query.setString(1, realm); query.setObject(2, sessionId)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next() || !rows.getBoolean(4) || !time.isBefore(rows.getTimestamp(3).toInstant)) None
          else
            Some(HostPrincipal(rows.getString(1), rows.getString(1), rows.getLong(2), rows.getLong(2), rows.getTimestamp(3).toInstant))
        }
      }
    def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit =
      Using.resource(
        connection.prepareStatement("UPDATE spoonbill_auth_test_host SET acknowledged=TRUE WHERE realm=? AND id=? AND completion=?")
      ) { query =>
        query.setString(1, realm); query.setObject(2, sessionId); query.setObject(3, completionId); query.executeUpdate(); ()
      }
  }
  private val key = new Array[Byte](32)
  random.nextBytes(key)
  private val outer = new JdbcTransactionExecutor[Future](source, ExecutionContext.global)
  private val counted = new TransactionExecutor[Future, Direct, Connection] {
    val program     = TransactionProgram.direct
    val scopePolicy = TransactionScopePolicy.ThreadConfined
    def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = {
      val index = transactions.incrementAndGet()
      outer.transact(body).map {
        case Right(_) if loseAcknowledgementAt.get() == index => Left(TransactionFailure.CommitUnknown)
        case result                                           => result
      }
    }
  }
  val persistence: JdbcBrowserPersistence[Future, String] = new JdbcBrowserPersistence(
    source,
    ExecutionContext.global,
    realm,
    "session",
    () => clock.get(),
    host,
    JdbcCredentialVault.aesGcm(key),
    ownership = BrowserOwnership.ExclusiveScope,
    limits = BrowserPersistenceLimits(retention = retention),
    existingRunner = Some(counted)
  )
  Using.resource(source.getConnection) { connection =>
    persistence.initialize(connection)
    Using.resource(connection.createStatement())(
      _.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_auth_test_host (
        realm TEXT NOT NULL,id UUID NOT NULL,completion UUID NOT NULL,subject TEXT NOT NULL,
        version BIGINT NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,enabled BOOLEAN NOT NULL,
        acknowledged BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(realm,id))""")
    )
  }
  val references: SnapshotReferenceSupport[Future, String] = persistence.snapshotReferences(referenceSettings)
  val harness = new RecoveryHarness[Future, String, RecoveryCounter](references, payloads, format)

  def payloadBasis: CleanupBasis = payloads.cleanupBasis

  private def required[E, A](value: Future[Either[E, A]]): Future[A] = value.flatMap {
    case Right(result) => Future.successful(result)
    case Left(error)   => Future.failed(new IllegalStateException(s"Fixture operation rejected: $error"))
  }
  private def secret(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
  private def connection(): ConnectionId = ConnectionId.fromUuid(UUID.randomUUID())

  private def prepare(ceremony: BrowserCeremony): Future[Either[TransactionFailure, CommittedAuthentication]] =
    persistence.prepare(ceremony) { tx =>
      val session    = UUID.randomUUID()
      val completion = UUID.randomUUID()
      val expiry     = clock.get().plusSeconds(86000)
      Using.resource(
        tx.connection.prepareStatement(
          "INSERT INTO spoonbill_auth_test_host(realm,id,completion,subject,version,expires_at,enabled) VALUES (?,?,?,'subject',1,?,TRUE)"
        )
      ) { query =>
        query.setString(1, realm); query.setObject(2, session); query.setObject(3, completion)
        query.setTimestamp(4, Timestamp.from(expiry)); query.executeUpdate()
      }
      new HostSessionPrepared(session, completion, expiry, BrowserSessionToken.fromString(secret()).toOption.get)
    }

  private def login(binding: String, qsid: Qsid): Future[View] =
    for {
      identity  <- required(persistence.capture(binding, None, activate = false))
      anonymous <- required(persistence.claim(identity, qsid, connection(), existingOnly = false))
      ceremony  <- required(persistence.begin(anonymous, "login"))
      receipt   <- required(prepare(ceremony))
      token     <- persistence.deliver(receipt.completionId, binding).map(_.getOrElse(throw new IllegalStateException("No cookie")))
      active    <- required(persistence.capture(binding, Some(token.value), activate = true))
      view      <- required(persistence.claim(active, qsid, connection(), existingOnly = true))
    } yield new View(binding, token.value, view)

  def open(): Future[View] = login(secret(), Qsid("device", UUID.randomUUID().toString))

  def takeOver(previous: View): Future[View] =
    for {
      active <- required(persistence.capture(previous.binding, Some(previous.token), activate = true))
      view   <- required(persistence.claim(active, previous.view.qsid, connection(), existingOnly = true))
    } yield new View(previous.binding, previous.token, view)

  def revoke(view: View): Future[Unit] = required(persistence.logout(view.binding, Some(view.token))).map(_ => ())
  def rotateIdentity(view: View): Future[View] = revoke(view).flatMap(_ => login(view.binding, view.view.qsid))
  def advanceClock(by: FiniteDuration): Future[Unit] = Future.successful { clock.updateAndGet(_.plusMillis(by.toMillis)); () }
  def reclaimView(view: View): Future[Unit] =
    persistence
      .release(view.view)
      .flatMap(_ => advanceClock((retention.idleViewSeconds + 1).seconds))
      .flatMap(_ => persistence.maintenance())

  def store(view: View): ViewSnapshotStore[Future, RecoveryCounter] =
    harness.store(view.view).getOrElse(throw new IllegalStateException("The fixture view is not authenticated"))
  def rebind(view: View, writer: SnapshotFormat[RecoveryCounter]): ViewSnapshotStore[Future, RecoveryCounter] =
    harness.store(view.view, writer).getOrElse(throw new IllegalStateException("The fixture view is not authenticated"))
  def gate(point: RecoveryFault): Future[RecoveryGate[Future]] = Future.successful(harness.gates.arm(point))
  def maintenance(worker: Int): Future[Unit]                   = harness.binding(worker).maintenance()
  def statistics(worker: Int): RecoveryStatistics             = harness.binding(worker).statistics

  private def sql[A](text: String)(run: java.sql.PreparedStatement => A): A =
    Using.resource(source.getConnection)(connection => Using.resource(connection.prepareStatement(text))(run))
  private def count(text: String, bind: java.sql.PreparedStatement => Unit = _ => ()): Long = sql(text) { query =>
    query.setString(1, realm); bind(query)
    Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
  }
  private def currentRef(view: View): Option[PayloadRef] = sql(
    """SELECT r.payload_id,r.payload_digest,r.payload_length FROM spoonbill_view_snapshot s
    JOIN spoonbill_snapshot_reference r ON r.realm=s.realm AND r.cookie_namespace=s.cookie_namespace AND r.operation_id=s.payload_operation
    WHERE s.realm=? AND s.cookie_namespace='session' AND s.view_id=?"""
  ) { query =>
    query.setString(1, realm); query.setString(2, BrowserView.key(view.view.qsid))
    Using.resource(query.executeQuery()) { rows =>
      if (rows.next()) Some(PayloadRef(rows.getObject(1, classOf[UUID]), rows.getBytes(2).toVector, rows.getInt(3))) else None
    }
  }

  def dropPayload(view: View): Future[Unit] =
    currentRef(view).fold(Future.unit)(ref => payloads.delete(ref).map(_ => ()))

  def observe(view: View): Future[RecoveryObservation] = {
    val viewId = BrowserView.key(view.view.qsid)
    // A reclaimed view has no row any more; observe it as revision 0.
    val revision = count("SELECT COALESCE((SELECT snapshot_revision FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace='session' AND view_id=?),0)", _.setString(2, viewId))
    val published = count("SELECT count(*) FROM spoonbill_snapshot_reference WHERE realm=? AND view_id=? AND state='published'", _.setString(2, viewId))
    val staged  = count("SELECT count(*) FROM spoonbill_snapshot_reference WHERE realm=? AND state='staged'")
    val retired = count("SELECT count(*) FROM spoonbill_snapshot_reference WHERE realm=? AND state='retired'")
    val deleted = count("SELECT count(*) FROM spoonbill_snapshot_reference WHERE realm=? AND deleted_at IS NOT NULL")
    val pins = count("SELECT count(*) FROM spoonbill_snapshot_read_pin WHERE realm=? AND expires_at>?", _.setTimestamp(2, Timestamp.from(clock.get())))
    val open = count("SELECT count(*) FROM spoonbill_snapshot_reference WHERE realm=? AND state='retired' AND deleted_at IS NULL")
    val uncertain = count("SELECT count(*) FROM spoonbill_snapshot_reference WHERE realm=? AND state='retired' AND deleted_at IS NULL AND delete_attempts>0")
    val present = currentRef(view).fold(Future.successful(Option.empty[Boolean])) { ref =>
      payloads.get(ref).map {
        case PayloadRead.Found(_) => Some(true)
        case _                    => Some(false)
      }
    }
    present.map { payloadPresent =>
      RecoveryObservation(revision, published.toInt, staged.toInt, retired.toInt, deleted.toInt, pins.toInt, open, uncertain, payloadPresent, harness.operations)
    }
  }

  def close(): Future[Unit] =
    harness.binding(0).close().flatMap(_ => harness.binding(1).close()).flatMap(_ => persistence.close())
}
