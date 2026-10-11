package spoonbill.security.jdbc

import java.io.PrintWriter
import java.security.SecureRandom
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException, Timestamp}
import java.time.Instant
import java.util.{Base64, Properties, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.*
import spoonbill.server.BrowserSessionToken

class JdbcBrowserPersistenceSpec extends BrowserPersistenceConformance {
  private given Effect[Future]              = new Effect.FutureEffect
  private given ExecutionContext            = ExecutionContext.global
  private def await[A](value: Future[A]): A = Await.result(value, 20.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private val binding = BrowserPersistenceConformance.binding

  private class Fixture extends BrowserPersistenceConformance.Fixture {
    private val url =
      sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Use the owned disposable PostgreSQL test database"))
    val clock               = new AtomicReference(BrowserPersistenceConformance.start)
    val realm               = "provider-" + UUID.randomUUID().toString
    val loseAcknowledgement = new AtomicBoolean()
    val source: DataSource = new DataSource {
      // sbt can reload the test classloader after DriverManager's discovery.
      // Use this fixture's explicit driver instead of that ambient registry.
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
      def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.jdbc.provider.test")
      def isWrapperFor(kind: Class[?]): Boolean                     = false
      def unwrap[T](kind: Class[T]): T                              = throw new SQLException("Not a wrapper")
    }
    private val key = new Array[Byte](32)
    new SecureRandom().nextBytes(key)
    private val vault = JdbcCredentialVault.aesGcm(key)
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
              Some(
                HostPrincipal(
                  rows.getString(1),
                  rows.getString(1),
                  rows.getLong(2),
                  rows.getLong(2),
                  rows.getTimestamp(3).toInstant
                )
              )
          }
        }
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit =
        Using.resource(
          connection.prepareStatement(
            "UPDATE spoonbill_auth_test_host SET acknowledged=TRUE WHERE realm=? AND id=? AND completion=?"
          )
        ) { query =>
          query.setString(1, realm); query.setObject(2, sessionId); query.setObject(3, completionId)
          if (query.executeUpdate() != 1) throw new OperationProtocolException(OperationError.HostDenied)
        }
    }
    private val outer = new JdbcTransactionExecutor[Future](source, ExecutionContext.global)
    private val observed = new TransactionExecutor[Future, Direct, Connection] {
      val program     = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] =
        outer.transact(body).map {
          case Right(_) if loseAcknowledgement.getAndSet(false) => Left(TransactionFailure.CommitUnknown)
          case result                                           => result
        }
    }
    def fresh(): JdbcBrowserPersistence[Future, String] = withRunner(observed)
    def withRunner(
      runner: TransactionExecutor[Future, Direct, Connection],
      limits: BrowserPersistenceLimits = BrowserPersistenceLimits()
    ): JdbcBrowserPersistence[Future, String] =
      new JdbcBrowserPersistence(
        source,
        ExecutionContext.global,
        realm,
        "session",
        () => clock.get(),
        host,
        vault,
        exclusiveAuthorityWriters = true,
        limits = limits,
        existingRunner = Some(runner)
      )
    def withViewLimit(value: Int): JdbcBrowserPersistence[Future, String] =
      withRunner(observed, BrowserPersistenceLimits(retainedViews = value))
    val persistence: JdbcBrowserPersistence[Future, String] = fresh()
    Using.resource(source.getConnection) { connection =>
      persistence.initialize(connection)
      Using.resource(connection.createStatement())(
        _.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_auth_test_host (
        realm TEXT NOT NULL,id UUID NOT NULL,completion UUID NOT NULL,subject TEXT NOT NULL,
        version BIGINT NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,enabled BOOLEAN NOT NULL,
        acknowledged BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(realm,id))""")
      )
    }
    def prepare(
      ceremony: BrowserCeremony,
      fail: Boolean = false
    ): Future[Either[TransactionFailure, CommittedAuthentication]] =
      prepareWith(persistence, ceremony, fail)
    def prepareWith(
      provider: JdbcBrowserPersistence[Future, String],
      ceremony: BrowserCeremony,
      fail: Boolean = false,
      unknown: Boolean = false
    ): Future[Either[TransactionFailure, CommittedAuthentication]] = provider.prepare(ceremony) { tx =>
      val connection = tx.connection
      val session    = UUID.randomUUID()
      val completion = UUID.randomUUID()
      val expiry     = clock.get().plusSeconds(600)
      Using.resource(
        connection.prepareStatement(
          "INSERT INTO spoonbill_auth_test_host(realm,id,completion,subject,version,expires_at,enabled) VALUES (?,?,?,'subject',1,?,TRUE)"
        )
      ) { query =>
        query.setString(1, realm); query.setObject(2, session); query.setObject(3, completion);
        query.setTimestamp(4, Timestamp.from(expiry)); query.executeUpdate()
      }
      if (fail) throw new OperationProtocolException(OperationError.HostDenied)
      if (unknown) loseAcknowledgement.set(true)
      val bytes = new Array[Byte](32)
      new SecureRandom().nextBytes(bytes)
      new HostSessionPrepared(
        session,
        completion,
        expiry,
        BrowserSessionToken.fromString(Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)).toOption.get
      )
    }
    def committedSessions: Int = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement("SELECT count(*) FROM spoonbill_auth_test_host WHERE realm=?")) {
        query =>
          query.setString(1, realm)
          Using.resource(query.executeQuery()) { rows =>
            rows.next(); rows.getInt(1)
          }
      }
    }
    def retained(table: String, condition: String = "TRUE"): Int = {
      require(Set("spoonbill_browser_view", "spoonbill_auth_material").contains(table))
      require(Set("TRUE", "owner_id IS NOT NULL").contains(condition))
      Using.resource(source.getConnection) { connection =>
        Using.resource(connection.prepareStatement(s"SELECT count(*) FROM $table WHERE realm=? AND $condition")) {
          query =>
            query.setString(1, realm)
            Using.resource(query.executeQuery()) { rows =>
              rows.next(); rows.getInt(1)
            }
        }
      }
    }
    def disable(): Unit = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement("UPDATE spoonbill_auth_test_host SET enabled=FALSE WHERE realm=?")) {
        query =>
          query.setString(1, realm); query.executeUpdate(); ()
      }
    }
    def close(): Unit = await(persistence.close())
  }
  protected def fixture(): BrowserPersistenceConformance.Fixture = new Fixture
  private val qsid                                               = Qsid("provider-device", "provider-view")
  private def open(provider: BrowserPersistence[Future, String], existing: Boolean = false): BrowserView[String] = {
    val identity = accepted(await(provider.capture(binding, None, activate = false)))
    accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existing)))
  }

  "The JDBC browser provider" should "recover a committed attempt after restart without creating another host session" in Using
    .resource(new Fixture) { db =>
      val oldView  = open(db.persistence)
      val ceremony = accepted(await(db.persistence.begin(oldView, "login")))
      val receipt  = accepted(await(db.prepare(ceremony)))
      await(db.persistence.close())
      val restarted = db.fresh()
      try {
        restarted.accepts(receipt) shouldBe false
        val next         = open(restarted, existing = true)
        val continuation = accepted(await(restarted.pending(next))).get
        continuation.id shouldBe ceremony.id
        continuation.preparationPending shouldBe true
        val recovered = accepted(await(restarted.recover(next, ceremony.id))) match {
          case BrowserRecovery.Committed(value) => value
          case _                                => fail("Expected original committed attempt")
        }
        recovered.completionId shouldBe receipt.completionId
        restarted.accepts(recovered) shouldBe true
        val credential = await(restarted.deliver(recovered.completionId, binding)).get
        accepted(await(restarted.capture(binding, Some(credential.value), activate = true))).principal
          .map(_.subject) shouldBe Some("subject")
        db.committedSessions shouldBe 1
      } finally await(restarted.close())
    }

  it should "recover unknown outer commit through the original durable ceremony without proof replay" in Using.resource(
    new Fixture
  ) { db =>
    val view     = open(db.persistence)
    val ceremony = accepted(await(db.persistence.begin(view, "login")))
    await(db.prepareWith(db.persistence, ceremony, unknown = true)) shouldBe Left(TransactionFailure.CommitUnknown)
    db.committedSessions shouldBe 1
    val recovered = accepted(await(db.persistence.recover(view, ceremony.id))) match {
      case BrowserRecovery.Committed(value) => value
      case _                                => fail("Expected committed recovery")
    }
    await(db.persistence.deliver(recovered.completionId, binding)).isDefined shouldBe true
    await(db.prepare(ceremony)).isLeft shouldBe true
    db.committedSessions shouldBe 1
  }

  it should "persist rejected expiry independently of rollback and a backward clock after restart" in Using.resource(
    new Fixture
  ) { db =>
    val view     = open(db.persistence)
    val ceremony = accepted(await(db.persistence.begin(view, "login")))
    db.clock.set(ceremony.expiresAt)
    await(db.persistence.resume(view, ceremony.id, "login")).isLeft shouldBe true
    await(db.persistence.close())
    db.clock.set(BrowserPersistenceConformance.start)
    val restarted = db.fresh()
    try {
      val next = open(restarted, existing = true)
      await(restarted.resume(next, ceremony.id, "login")).isLeft shouldBe true
      accepted(await(restarted.pending(next))) shouldBe None
      db.committedSessions shouldBe 0
    } finally await(restarted.close())
  }

  it should "bind encrypted credential material to its associated browser and attempt" in {
    val key        = Array.fill[Byte](32)(42)
    val vault      = JdbcCredentialVault.aesGcm(key)
    val plain      = "synthetic credential".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val associated = "browser A, attempt A".getBytes(java.nio.charset.StandardCharsets.UTF_8)
    val envelope   = vault.seal(plain, associated)
    vault.open(envelope, associated).toVector shouldBe plain.toVector
    intercept[javax.crypto.AEADBadTagException](
      vault.open(envelope, "browser B".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    )
  }

  it should "retain observed active-session expiry across restart with an earlier clock" in Using.resource(
    new Fixture
  ) { db =>
    val view       = open(db.persistence)
    val receipt    = accepted(await(db.prepare(accepted(await(db.persistence.begin(view, "login"))))))
    val credential = await(db.persistence.deliver(receipt.completionId, binding)).get
    val active     = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
    db.clock.set(active.principal.get.expiresAt)
    await(db.persistence.capture(binding, Some(credential.value), activate = false)).isLeft shouldBe true
    await(db.persistence.close())
    db.clock.set(BrowserPersistenceConformance.start)
    val restarted = db.fresh()
    try await(restarted.capture(binding, Some(credential.value), activate = true)).isLeft shouldBe true
    finally await(restarted.close())
  }

  it should "refuse revoked recovery and retire its encrypted material without erasing outcome history" in Using
    .resource(new Fixture) { db =>
      val view     = open(db.persistence)
      val ceremony = accepted(await(db.persistence.begin(view, "login")))
      val receipt  = accepted(await(db.prepare(ceremony)))
      db.retained("spoonbill_auth_material") shouldBe 1
      db.disable()
      await(db.persistence.recover(view, ceremony.id)).isLeft shouldBe true
      await(db.persistence.deliver(receipt.completionId, binding)) shouldBe None
      await(db.persistence.maintenance())
      db.retained("spoonbill_auth_material") shouldBe 0
      db.committedSessions shouldBe 1
      await(db.prepare(ceremony)).isLeft shouldBe true
    }

  it should "release only its current owned view fences on idempotent close" in Using.resource(new Fixture) { db =>
    val first = open(db.persistence)
    val second = accepted(
      await(db.persistence.claim(first.identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
    )
    await(db.persistence.release(first))
    await(db.persistence.authorize(second)).isRight shouldBe true
    db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
    await(db.persistence.close())
    await(db.persistence.close())
    db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
    db.retained("spoonbill_browser_view") shouldBe 1
  }

  it should "retire predecessor bookkeeping across repeated same-view takeover at a small capacity" in Using.resource(
    new Fixture
  ) { db =>
    val provider = db.withViewLimit(2)
    try {
      var previous = open(provider)
      for (_ <- 1 to 12) {
        val next = accepted(
          await(provider.claim(previous.identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
        )
        await(provider.release(previous))
        await(provider.authorize(next)).isRight shouldBe true
        await(provider.authorize(previous)).isLeft shouldBe true
        db.retained("spoonbill_browser_view") shouldBe 1
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
        previous = next
      }
      await(provider.release(previous))
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
    } finally await(provider.close())
  }

  it should "reject transaction handles from another provider at compile time" in {
    assertDoesNotCompile("""
      import scala.concurrent.Future
      import spoonbill.security.jdbc.JdbcBrowserPersistence
      val first: JdbcBrowserPersistence[Future,String] = ???
      val second: JdbcBrowserPersistence[Future,String] = ???
      def transfer(value: first.Tx): second.Tx = value
    """)
  }

  it should "close host transaction handles after preparation even when retained by the callback" in Using.resource(
    new Fixture
  ) { db =>
    val view     = open(db.persistence)
    val ceremony = accepted(await(db.persistence.begin(view, "login")))
    val retained = new AtomicReference[db.persistence.Tx]()
    await(db.persistence.prepare(ceremony) { tx =>
      retained.set(tx)
      throw new OperationProtocolException(OperationError.HostDenied)
    }).isLeft shouldBe true
    intercept[OperationProtocolException](retained.get().connection).error shouldBe OperationError.ScopeClosed
    db.committedSessions shouldBe 0
  }

  it should "withhold receipts and host visibility until the supplied outer runner commits" in Using.resource(
    new Fixture
  ) { db =>
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val outer   = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program     = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact { connection =>
        val result = body(connection)
        // The final preparation program returns its provider completion UUID;
        // admission and the setup programs return other types.
        if (result.isInstanceOf[UUID]) {
          entered.countDown()
          if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Outer commit barrier timed out")
        }
        result
      }
    }
    val provider = db.withRunner(runner)
    try {
      val view     = open(provider)
      val ceremony = accepted(await(provider.begin(view, "login")))
      val pending  = db.prepareWith(provider, ceremony)
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      pending.isCompleted shouldBe false
      db.committedSessions shouldBe 0
      release.countDown()
      val receipt = accepted(await(pending))
      provider.accepts(receipt) shouldBe true
      db.committedSessions shouldBe 1
    } finally { release.countDown(); await(provider.close()) }
  }
}
