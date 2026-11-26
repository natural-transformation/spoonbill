package spoonbill.security.jdbc

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.security.{MessageDigest, SecureRandom}
import java.sql.{Connection, PreparedStatement, SQLException, SQLFeatureNotSupportedException, Timestamp}
import java.time.Instant
import java.util.{Base64, Properties, UUID}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Using
import scala.jdk.CollectionConverters.*
import spoonbill.Qsid
import spoonbill.action.*
import spoonbill.auth.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.security.Versions.{SecurityGeneration, SlotGeneration, ViewOwnershipEpoch, ViewRevision}
import spoonbill.security.transaction.*
import spoonbill.server.*
import spoonbill.snapshot.{ObservedSnapshotWrites, SchemaId, SchemaVersion, SnapshotBinaryCodec, SnapshotFormat, SnapshotIdentity, SnapshotKeys, SnapshotLoad, ViewSnapshotError, ViewSnapshotException, ViewSnapshotSession}
import spoonbill.state.BoundedPresentationStore
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class JdbcBrowserPersistenceSpec extends BrowserReplacementConformance {
  private given Effect[Future]              = Effect.futureEffect
  private given ExecutionContext            = ExecutionContext.global
  private def await[A](value: Future[A]): A = Await.result(value, 20.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private val binding = BrowserPersistenceConformance.binding

  private def applyMigrations(connection: Connection, names: String*): Unit = {
    require(connection.getAutoCommit, "Migration test requires its own transaction")
    connection.setAutoCommit(false)
    try {
      names.foreach { name =>
        val sql = Using.resource(
          scala.io.Source.fromInputStream(getClass.getResourceAsStream(s"/spoonbill/migrations/postgresql/$name"))
        )(_.mkString)
        sql.linesIterator
          .filterNot(_.trim.startsWith("--"))
          .mkString("\n")
          .split(";")
          .filter(_.trim.nonEmpty)
          .foreach(statement => Using.resource(connection.createStatement())(_.executeUpdate(statement)))
      }
      connection.commit()
    } catch { case error: Throwable => connection.rollback(); throw error }
    finally connection.setAutoCommit(true)
  }

  private def frameworkSchema(connection: Connection): Vector[String] = {
    // Compare actual columns/defaults, constraints and indexes, not just the
    // table count. Only the synthetic host table is outside framework DDL.
    val sql = """SELECT 'column|' || c.relname || '|' || a.attnum || '|' || a.attname || '|' ||
      format_type(a.atttypid,a.atttypmod) || '|' || a.attnotnull::text || '|' ||
      COALESCE(pg_get_expr(d.adbin,d.adrelid),'') AS definition
      FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
      JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum>0 AND NOT a.attisdropped
      LEFT JOIN pg_attrdef d ON d.adrelid=c.oid AND d.adnum=a.attnum
      WHERE n.nspname=current_schema() AND c.relkind='r' AND c.relname LIKE 'spoonbill_%'
        AND c.relname<>'spoonbill_auth_test_host'
      UNION ALL
      SELECT 'constraint|' || c.relname || '|' || k.conname || '|' || pg_get_constraintdef(k.oid)
      FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=current_schema() AND c.relname LIKE 'spoonbill_%'
        AND c.relname<>'spoonbill_auth_test_host'
      UNION ALL
      SELECT 'index|' || c.relname || '|' || i.relname || '|' || pg_get_indexdef(x.indexrelid)
      FROM pg_index x JOIN pg_class c ON c.oid=x.indrelid JOIN pg_class i ON i.oid=x.indexrelid
      JOIN pg_namespace n ON n.oid=c.relnamespace
      WHERE n.nspname=current_schema() AND c.relname LIKE 'spoonbill_%'
        AND c.relname<>'spoonbill_auth_test_host'
      ORDER BY definition"""
    Using.resource(connection.createStatement()) { query =>
      Using.resource(query.executeQuery(sql)) { rows =>
        val result = Vector.newBuilder[String]
        while (rows.next()) result += rows.getString(1).replace(connection.getSchema + ".", "")
        result.result()
      }
    }
  }

  private def assertSuccessorIndex(schema: Vector[String]): Unit = {
    val index = schema
      .find(_.startsWith("index|spoonbill_browser_replacement|spoonbill_browser_replacement_successor|"))
      .getOrElse(fail("Missing replacement successor lookup index"))
    index should include("(realm, namespace, successor_session_id) WHERE (successor_session_id IS NOT NULL)")
  }

  /**
   * Minimal lazy outer effect using the existing Future implementation. No
   * extra runtime/dependency is needed to exercise repeated effect evaluation.
   */
  private type Deferred[A] = () => Future[A]
  private class DeferredFutureEffect extends Effect[Deferred] {
    private val underlying                                                        = Effect.futureEffect
    def pure[A](value: A): Deferred[A]                                            = () => Future.successful(value)
    def delay[A](value: => A): Deferred[A]                                        = () => underlying.delay(value)
    def uncancelable[A](task: => Deferred[A]): Deferred[A]                        = () => underlying.uncancelable(task())
    def fail[A](error: Throwable): Deferred[A]                                    = () => Future.failed(error)
    def unit: Deferred[Unit]                                                      = () => Future.unit
    def never[A]: Deferred[A]                                                     = () => Future.never
    def fromTry[A](value: => scala.util.Try[A]): Deferred[A]                      = () => underlying.fromTry(value)
    def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Deferred[A] = () => underlying.promise(register)
    def promiseF[A](register: (Either[Throwable, A] => Unit) => Deferred[Unit]): Deferred[A] =
      () => underlying.promiseF(callback => register(callback)())
    def flatMap[A, B](value: Deferred[A])(next: A => Deferred[B]): Deferred[B] =
      () => underlying.flatMap(value())(item => next(item)())
    def map[A, B](value: Deferred[A])(next: A => B): Deferred[B] = () => underlying.map(value())(next)
    def recover[A, AA >: A](value: Deferred[A])(recover: PartialFunction[Throwable, AA]): Deferred[AA] =
      () => underlying.recover(value())(recover)
    def recoverF[A, AA >: A](value: Deferred[A])(recover: PartialFunction[Throwable, Deferred[AA]]): Deferred[AA] =
      () => underlying.recoverF(value())(recover.andThen(result => result()))
    def start[A](
      create: => Deferred[A]
    )(using executionContext: ExecutionContext): Deferred[Effect.Fiber[Deferred, A]] =
      () =>
        underlying.map(underlying.start(create())) { fiber =>
          new Effect.Fiber[Deferred, A] { def join(): Deferred[A] = () => fiber.join() }
        }
    def blocking[A](value: => A)(using ExecutionContext): Deferred[A]       = () => underlying.blocking(value)
    def fork[A](value: => Deferred[A])(using ExecutionContext): Deferred[A] = () => underlying.fork(value())
    def sequence[A](values: List[Deferred[A]]): Deferred[List[A]]           = () => underlying.sequence(values.map(_()))
    def runAsync[A](value: Deferred[A])(callback: Either[Throwable, A] => Unit): Unit =
      underlying.runAsync(value())(callback)
    def run[A](value: Deferred[A]): Either[Throwable, A] = underlying.run(value())
    def toFuture[A](value: Deferred[A]): Future[A]       = value()
  }

  /**
   * held selects a host that holds its resolution (JdbcHeldHostSessions) over
   * the same table; refreshSeconds makes its accepted hook extend idle expiry.
   */
  private class Fixture(
    exclusive: Boolean = true,
    isolatedSchema: Boolean = false,
    hostExpiryNanos: Int = 0,
    held: Boolean = false,
    refreshSeconds: Long = 0
  ) extends BrowserReplacementConformance.Fixture {
    private val url =
      sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Use the owned disposable PostgreSQL test database"))
    val clock = new AtomicReference(BrowserPersistenceConformance.start)
    val realm = "provider-" + UUID.randomUUID().toString
    private val schemaName =
      if (isolatedSchema) Some("replacement_" + UUID.randomUUID().toString.replace("-", "")) else None
    private val schemaCreated = new AtomicBoolean()
    val loseAcknowledgement   = new AtomicBoolean()
    val openedConnections     = new AtomicInteger()
    val domainTransactions    = new AtomicInteger()
    val hostResolutions       = new AtomicInteger()
    val replacementCallbacks  = new AtomicInteger()
    val recordSql = new AtomicBoolean()
    val statements = new ConcurrentLinkedQueue[String]()
    // Committed transactions that touched the durable clock, and a lost acknowledgment of the next one.
    val clockCommits             = new AtomicInteger()
    val loseClockAcknowledgement = new AtomicBoolean()
    // Held host calls: lockResolution calls, accepted hooks, a pause before the next host lock.
    val hostHolds    = new AtomicInteger()
    val hostAccepted = new AtomicInteger()
    val failAccepted = new AtomicBoolean()
    val holdPause    = new AtomicReference(Option.empty[(CountDownLatch, CountDownLatch)])
    // When set, the accepted hook first locks this other host session's row.
    val hookLocks    = new AtomicReference(Option.empty[UUID])
    val source: DataSource = new DataSource {
      // sbt can reload the test classloader after DriverManager's discovery.
      // Use this fixture's explicit driver instead of that ambient registry.
      private val driver = new org.postgresql.Driver()
      def getConnection: Connection = {
        val properties = new Properties()
        sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
        sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
        properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=12000")
        val connection =
          Option(driver.connect(url, properties)).getOrElse(throw new SQLException("Unsupported PostgreSQL test URL"))
        schemaName.foreach { name =>
          if (schemaCreated.compareAndSet(false, true))
            Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA $name"))
          connection.setSchema(name)
        }
        openedConnections.incrementAndGet()
        Proxy.newProxyInstance(getClass.getClassLoader, Array(classOf[Connection]), new InvocationHandler {
          // Whether the open transaction on this connection touched the durable clock.
          private var clockTransaction = false
          def invoke(proxy: Object, method: Method, args: Array[Object]): Object = {
            val arguments = Option(args).getOrElse(Array.empty[Object])
            if (method.getName == "prepareStatement" && arguments.nonEmpty) {
              val text = arguments(0).toString
              if (recordSql.get()) statements.add(text.replaceAll("\\s+", " ").trim)
              if (text.contains("spoonbill_auth_clock")) clockTransaction = true
            }
            val result =
              try method.invoke(connection, arguments*)
              catch { case error: InvocationTargetException => throw error.getCause }
            method.getName match {
              case "commit" if clockTransaction =>
                clockTransaction = false
                clockCommits.incrementAndGet()
                if (loseClockAcknowledgement.getAndSet(false))
                  throw new SQLException("Synthetic lost clock commit acknowledgment")
              case "rollback" => clockTransaction = false
              case _          => ()
            }
            result
          }
        }).asInstanceOf[Connection]
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
    val failNextSeal       = new AtomicBoolean()
    private val encryption = JdbcCredentialVault.aesGcm(key)
    private val vault = new JdbcCredentialVault {
      def seal(plaintext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        if (failNextSeal.compareAndSet(true, false))
          throw new IllegalStateException("Synthetic credential seal failure")
        encryption.seal(plaintext, associatedData)
      }
      def open(ciphertext: Array[Byte], associatedData: Array[Byte]): Array[Byte] =
        encryption.open(ciphertext, associatedData)
    }
    private val host = new JdbcHostSessions[String] {
      def resolve(connection: Connection, sessionId: UUID, time: Instant): Option[HostPrincipal[String]] = {
        hostResolutions.incrementAndGet()
        Using.resource(
          connection.prepareStatement(
            "SELECT subject,version,expires_at,enabled,idle_until FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE"
          )
        ) { query =>
          query.setString(1, realm); query.setObject(2, sessionId)
          Using.resource(query.executeQuery()) { rows =>
            if (
              !rows.next() || !rows
                .getBoolean(4) || !time.isBefore(rows.getTimestamp(3).toInstant.plusNanos(hostExpiryNanos.toLong)) ||
              Option(rows.getTimestamp(5)).exists(idle => !time.isBefore(idle.toInstant))
            ) None
            else
              Some(
                HostPrincipal(
                  rows.getString(1),
                  rows.getString(1),
                  rows.getLong(2),
                  rows.getLong(2),
                  rows.getTimestamp(3).toInstant.plusNanos(hostExpiryNanos.toLong)
                )
              )
          }
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
    /**
     * The same host policy, holding its resolution: it locks and reads the row
     * without deciding by time. Its cutoff is the earlier of absolute and idle
     * expiry; its accepted hook optionally extends idle expiry from the
     * decision instant on the row it locked.
     */
    private val heldHost = new JdbcHeldHostSessions[String] {
      def resolve(connection: Connection, sessionId: UUID, time: Instant): Option[HostPrincipal[String]] =
        host.resolve(connection, sessionId, time)
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit =
        host.acknowledge(connection, completionId, sessionId, time)
      def lockResolution(connection: Connection, sessionId: UUID): Option[JdbcHeldResolution[String]] = {
        hostHolds.incrementAndGet()
        holdPause.getAndSet(None).foreach { case (entered, resume) =>
          entered.countDown(); resume.await(15, TimeUnit.SECONDS)
        }
        Using.resource(
          connection.prepareStatement(
            "SELECT subject,version,expires_at,enabled,idle_until FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE"
          )
        ) { query =>
          query.setString(1, realm); query.setObject(2, sessionId)
          Using.resource(query.executeQuery()) { rows =>
            if (!rows.next() || !rows.getBoolean(4)) None
            else {
              val absolute = rows.getTimestamp(3).toInstant.plusNanos(hostExpiryNanos.toLong)
              val idle     = Option(rows.getTimestamp(5)).map(_.toInstant)
              val stamp    = HostPrincipal(rows.getString(1), rows.getString(1), rows.getLong(2), rows.getLong(2), absolute)
              Some(new JdbcHeldResolution[String] {
                val principal: HostPrincipal[String] = stamp
                val validUntilExclusive: Instant     = idle.filter(_.isBefore(absolute)).getOrElse(absolute)
                override def onAcceptedAt(now: Instant): Unit = {
                  hostAccepted.incrementAndGet()
                  if (failAccepted.getAndSet(false)) throw new IllegalStateException("Synthetic accepted-hook failure")
                  hookLocks.getAndSet(None).foreach { other =>
                    Using.resource(
                      connection.prepareStatement("SELECT 1 FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE")
                    ) { lock =>
                      lock.setString(1, realm); lock.setObject(2, other); Using.resource(lock.executeQuery())(_.next()); ()
                    }
                  }
                  if (refreshSeconds > 0 && idle.nonEmpty)
                    Using.resource(
                      connection.prepareStatement(
                        "UPDATE spoonbill_auth_test_host SET idle_until=? WHERE realm=? AND id=? AND idle_until<?"
                      )
                    ) { update =>
                      val next = Timestamp.from(now.plusSeconds(refreshSeconds))
                      update.setTimestamp(1, next); update.setString(2, realm); update.setObject(3, sessionId)
                      update.setTimestamp(4, next); update.executeUpdate(); ()
                    }
                }
              })
            }
          }
        }
      }
    }
    private def selectedHost: JdbcHostSessions[String] = if (held) heldHost else host
    /** The held host and the vault, for a provider of another effect over the same realm. */
    def heldHostSessions: JdbcHeldHostSessions[String] = heldHost
    def credentialVault: JdbcCredentialVault           = vault
    private val outer = new JdbcTransactionExecutor[Future](source, ExecutionContext.global)
    private val observed = new TransactionExecutor[Future, Direct, Connection] {
      val program     = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = {
        domainTransactions.incrementAndGet()
        outer.transact(body).map {
          case Right(_) if loseAcknowledgement.getAndSet(false) => Left(TransactionFailure.CommitUnknown)
          case result                                           => result
        }
      }
    }
    def fresh(): JdbcBrowserPersistence[Future, String] = withRunner(observed)
    def deferred()(using Effect[Deferred]): JdbcBrowserPersistence[Deferred, String] =
      new JdbcBrowserPersistence(
        source,
        ExecutionContext.global,
        realm,
        "session",
        () => clock.get(),
        selectedHost,
        vault,
        ownership = if (exclusive) BrowserOwnership.ExclusiveScope else BrowserOwnership.SharedViews
      )
    def withRunner(
      runner: TransactionExecutor[Future, Direct, Connection],
      limits: BrowserPersistenceLimits = BrowserPersistenceLimits(),
      newId: () => UUID = () => UUID.randomUUID(),
      concurrent: Option[JdbcConcurrentChecks] = None,
      time: () => Instant = () => clock.get()
    ): JdbcBrowserPersistence[Future, String] =
      new JdbcBrowserPersistence(
        source,
        ExecutionContext.global,
        realm,
        "session",
        time,
        selectedHost,
        vault,
        ownership = if (exclusive) BrowserOwnership.ExclusiveScope else BrowserOwnership.SharedViews,
        limits = limits,
        existingRunner = Some(runner),
        newId = newId,
        concurrentChecks = concurrent
      )
    def withConcurrentChecks(
      cap: Int,
      ownership: BrowserOwnership = BrowserOwnership.ExclusiveScope
    ): JdbcBrowserPersistence[Future, String] =
      new JdbcBrowserPersistence(
        source,
        ExecutionContext.global,
        realm,
        "session",
        () => clock.get(),
        selectedHost,
        vault,
        ownership = ownership,
        existingRunner = Some(observed),
        concurrentChecks = Some(JdbcConcurrentChecks(source, cap))
      )
    /** A SharedViews node whose eligible checks run concurrently; requires held = true. */
    def sharedConcurrent(cap: Int, clockSource: DataSource = source): JdbcBrowserPersistence[Future, String] =
      JdbcBrowserPersistence.sharedViewsWithConcurrentChecks[Future, String](
        source,
        ExecutionContext.global,
        realm,
        "session",
        () => clock.get(),
        heldHost,
        vault,
        JdbcConcurrentChecks(clockSource, cap),
        existingRunner = Some(observed)
      )
    /** Another node over the same realm with its own wall clock. */
    def withClock(time: () => Instant): JdbcBrowserPersistence[Future, String] = withRunner(observed, time = time)
    def withViewLimit(value: Int): JdbcBrowserPersistence[Future, String] =
      withRunner(observed, BrowserPersistenceLimits(retainedViews = value))
    def withLimits(limits: BrowserPersistenceLimits, newId: () => UUID = () => UUID.randomUUID()): JdbcBrowserPersistence[Future, String] =
      withRunner(observed, limits, newId)
    val persistence: JdbcBrowserPersistence[Future, String]               = fresh()
    def replacementPersistence: BrowserReplacementSupport[Future, String] = persistence
    Using.resource(source.getConnection) { connection =>
      persistence.initialize(connection)
      Using.resource(connection.createStatement())(
        _.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_auth_test_host (
        realm TEXT NOT NULL,id UUID NOT NULL,completion UUID NOT NULL,subject TEXT NOT NULL,
        version BIGINT NOT NULL,expires_at TIMESTAMP WITH TIME ZONE NOT NULL,enabled BOOLEAN NOT NULL,
        acknowledged BOOLEAN NOT NULL DEFAULT FALSE,PRIMARY KEY(realm,id))""")
      )
      // Idle expiry for the held host's cutoff; NULL means none.
      Using.resource(connection.createStatement())(
        _.executeUpdate("ALTER TABLE spoonbill_auth_test_host ADD COLUMN IF NOT EXISTS idle_until TIMESTAMP WITH TIME ZONE")
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
      unknown: Boolean = false,
      revokePrevious: Option[UUID] = None,
      sessionSeconds: Long = 600,
      rawCredential: Option[String] = None
    ): Future[Either[TransactionFailure, CommittedAuthentication]] = provider.prepare(ceremony) { tx =>
      val connection = tx.connection
      revokePrevious.foreach { previous =>
        Using.resource(
          connection.prepareStatement(
            "UPDATE spoonbill_auth_test_host SET enabled=FALSE WHERE realm=? AND id=?"
          )
        ) { query =>
          query.setString(1, realm); query.setObject(2, previous)
          query.executeUpdate() shouldBe 1
        }
      }
      val session    = UUID.randomUUID()
      val completion = UUID.randomUUID()
      val expiry     = clock.get().plusSeconds(sessionSeconds)
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
        BrowserSessionToken.fromString(rawCredential.getOrElse(Base64.getUrlEncoder.withoutPadding().encodeToString(bytes))).toOption.get
      )
    }
    def prepareReplacement(
      ceremony: BrowserReplacementCeremony,
      invocation: BrowserReplacementInvocation,
      predecessorSessionId: UUID,
      fail: Boolean = false
    ): Future[Either[TransactionFailure, CommittedReplacement]] =
      replaceWith(persistence, ceremony, invocation, predecessorSessionId, fail = fail)

    def replaceWith(
      provider: JdbcBrowserPersistence[Future, String],
      ceremony: BrowserReplacementCeremony,
      invocation: BrowserReplacementInvocation,
      predecessorSessionId: UUID,
      fail: Boolean = false,
      unknown: Boolean = false,
      revoke: Boolean = true,
      subject: String = "subject",
      version: Long = 1L,
      afterHost: () => Unit = () => (),
      sessionSeconds: Long = 600
    ): Future[Either[TransactionFailure, CommittedReplacement]] = provider.prepareReplacement(ceremony, invocation) {
      tx =>
        replacementCallbacks.incrementAndGet()
        if (revoke)
          Using.resource(
            tx.connection.prepareStatement("UPDATE spoonbill_auth_test_host SET enabled=FALSE WHERE realm=? AND id=?")
          ) { query =>
            query.setString(1, realm); query.setObject(2, predecessorSessionId); query.executeUpdate() shouldBe 1
          }
        val session    = UUID.randomUUID()
        val completion = UUID.randomUUID()
        val expiry     = clock.get().plusSeconds(sessionSeconds)
        Using.resource(
          tx.connection.prepareStatement(
            "INSERT INTO spoonbill_auth_test_host(realm,id,completion,subject,version,expires_at,enabled) VALUES (?,?,?,?,?,?,TRUE)"
          )
        ) { query =>
          query.setString(1, realm); query.setObject(2, session); query.setObject(3, completion)
          query.setString(4, subject); query.setLong(5, version); query.setTimestamp(6, Timestamp.from(expiry));
          query.executeUpdate()
        }
        afterHost()
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
    def successorCompletion(
      view: BrowserView[String]
    ): Future[Either[TransactionFailure, VerifiedSuccessorCompletion]] =
      persistence.withSuccessorCompletion(view)((_, completion) => completion)
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
      require(
        Set(
          "spoonbill_browser_view",
          "spoonbill_auth_material",
          "spoonbill_browser_session",
          "spoonbill_browser_replacement",
          "spoonbill_auth_ceremony",
          "spoonbill_browser_slot",
          "spoonbill_browser_retention"
        ).contains(table)
      )
      require(Set("TRUE", "owner_id IS NOT NULL", "successor_session_id IS NOT NULL").contains(condition))
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
    def setIdle(session: UUID, until: Instant): Unit = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement("UPDATE spoonbill_auth_test_host SET idle_until=? WHERE realm=? AND id=?")) {
        query =>
          query.setTimestamp(1, Timestamp.from(until)); query.setString(2, realm); query.setObject(3, session)
          query.executeUpdate() shouldBe 1
      }
    }
    def idleOf(session: UUID): Instant = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement("SELECT idle_until FROM spoonbill_auth_test_host WHERE realm=? AND id=?")) {
        query =>
          query.setString(1, realm); query.setObject(2, session)
          Using.resource(query.executeQuery()) { rows => rows.next() shouldBe true; rows.getTimestamp(1).toInstant }
      }
    }
    /** The stored snapshot revision of a view. */
    def viewRevision(id: Qsid = qsid): Long = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT snapshot_revision FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace='session' AND view_id=?"
      )) { query =>
        query.setString(1, realm); query.setString(2, BrowserView.key(id))
        Using.resource(query.executeQuery()) { rows => rows.next() shouldBe true; rows.getLong(1) }
      }
    }
    /** The realm's durable clock floor. */
    def clockFloor: Instant = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT epoch_second,nano FROM spoonbill_auth_clock WHERE realm=? AND namespace='session'"
      )) { query =>
        query.setString(1, realm)
        Using.resource(query.executeQuery()) { rows =>
          rows.next() shouldBe true; Instant.ofEpochSecond(rows.getLong(1), rows.getInt(2).toLong)
        }
      }
    }
    /** Row locks taken in a separate open transaction, until release. */
    final class HeldLock(sql: String, bind: PreparedStatement => Unit) {
      private val connection = source.getConnection
      connection.setAutoCommit(false)
      Using.resource(connection.prepareStatement(sql)) { query =>
        bind(query); Using.resource(query.executeQuery())(_.next()) shouldBe true
      }
      def update(text: String)(set: PreparedStatement => Unit): Unit =
        Using.resource(connection.prepareStatement(text)) { query => set(query); query.executeUpdate() shouldBe 1 }
      /**
       * PostgreSQL ends a deadlock by aborting the backend whose wait first
       * outlasts deadlock_timeout. A long timeout here makes the other side of
       * a test's deadlock the one that is aborted.
       */
      def deferDeadlockDetection(): Unit =
        Using.resource(connection.createStatement())(_.execute("SET LOCAL deadlock_timeout = '60s'"))
      /** One more locking read in this transaction; it waits for the rows, then reports whether it found any. */
      def lock(text: String)(set: PreparedStatement => Unit): Boolean =
        Using.resource(connection.prepareStatement(text)) { query => set(query); Using.resource(query.executeQuery())(_.next()) }
      private val released = new AtomicBoolean()
      /** Settles and closes the transaction once; later calls do nothing. */
      def release(commit: Boolean = false): Unit =
        if (released.compareAndSet(false, true))
          try { if (commit) connection.commit() else connection.rollback() } finally connection.close()
      /**
       * Runs the steps that need the rows held and releases them afterwards,
       * also when an assertion fails, so that the checks waiting for them end.
       */
      def holding[A](body: => A): A = try body finally release()
    }
    /** Wait until `count` other backends wait for a lock in statements containing `fragment`. */
    def awaitLockWait(fragment: String, count: Int = 1): Unit = Using.resource(source.getConnection) { observer =>
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      def waiting: Int = Using.resource(observer.prepareStatement(
        "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' AND pid<>pg_backend_pid() AND query LIKE ?"
      )) { query =>
        query.setString(1, s"%$fragment%")
        Using.resource(query.executeQuery()) { rows => rows.next(); rows.getInt(1) }
      }
      while (waiting < count && System.nanoTime() < deadline) Thread.sleep(5)
      withClue(s"fewer than $count backends wait for a lock in $fragment: ")(waiting should be >= count)
    }
    /**
     * A runner that holds its next transaction open after its body, before it
     * settles, once armed: skip transactions pass first. The transaction keeps
     * its realm lock and staged writes while paused.
     */
    final class PausingRunner {
      private val armed   = new AtomicReference(Option.empty[(Int, CountDownLatch, CountDownLatch)])
      def arm(skip: Int = 0): (CountDownLatch, CountDownLatch) = {
        val latches = (new CountDownLatch(1), new CountDownLatch(1))
        armed.set(Some((skip, latches._1, latches._2))); latches
      }
      val runner: TransactionExecutor[Future, Direct, Connection] = new TransactionExecutor[Future, Direct, Connection] {
        val program     = TransactionProgram.direct
        val scopePolicy = TransactionScopePolicy.ThreadConfined
        def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact { connection =>
          val value = body(connection)
          armed.getAndUpdate(_.map { case (skip, entered, resume) => (skip - 1, entered, resume) }) match {
            case Some((0, entered, resume)) =>
              armed.set(None); entered.countDown(); resume.await(15, TimeUnit.SECONDS)
            case _ => ()
          }
          value
        }
      }
    }
    def close(): Unit = {
      await(persistence.close())
      schemaName.foreach(name =>
        Using.resource(source.getConnection) { connection =>
          Using.resource(connection.createStatement())(_.executeUpdate(s"DROP SCHEMA $name CASCADE"))
        }
      )
    }
  }
  protected def fixture(): BrowserReplacementConformance.Fixture = new Fixture
  private val qsid                                               = Qsid("provider-device", "provider-view")

  private val tinyRetention = BrowserPersistenceLimits(
    retainedBindings = 2, retainedViews = 2, retainedCeremonies = 2, ceremoniesPerBinding = 2,
    anonymousBindings = 1, ceremonySeconds = 3, deliverySeconds = 2,
    retention = BrowserRetentionPolicy(idleBindingSeconds = 5, idleViewSeconds = 5, historySeconds = 2, maxSessionSeconds = 1000)
  )

  private def retentionLogin(db: Fixture, provider: JdbcBrowserPersistence[Future, String], rawBinding: String,
    id: Qsid, seconds: Long, credential: Option[String] = None): (BrowserView[String], BrowserSessionToken, CommittedAuthentication) = {
    val anonymous = withClue("anonymous capture: ")(accepted(await(provider.capture(rawBinding, None, activate = false))))
    val initial = withClue("initial claim: ")(accepted(await(provider.claim(anonymous, id, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false))))
    val ceremony = withClue("begin login: ")(accepted(await(provider.begin(initial, "login"))))
    val receipt = withClue("prepare login: ")(accepted(await(db.prepareWith(provider, ceremony, sessionSeconds = seconds, rawCredential = credential))))
    val token = await(provider.deliver(receipt.completionId, rawBinding)).get
    val identity = withClue("activation: ")(accepted(await(provider.capture(rawBinding, Some(token.value), activate = true))))
    val view = withClue("authenticated claim: ")(accepted(await(provider.claim(identity, id, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))))
    (view, token, receipt)
  }

  private def retentionCounters(db: Fixture): (Long, Long, Long, Long) = Using.resource(db.source.getConnection) { connection =>
    Using.resource(connection.prepareStatement(
      "SELECT id_prefix,id_counter,binding_floor,view_floor FROM spoonbill_browser_retention WHERE realm=? AND namespace='session'"
    )) { query =>
      query.setString(1, db.realm)
      Using.resource(query.executeQuery()) { rows =>
        rows.next() shouldBe true
        (rows.getLong(1), rows.getLong(2), rows.getLong(3), rows.getLong(4))
      }
    }
  }

  private def retainedCeremonyIds(db: Fixture): Set[UUID] = Using.resource(db.source.getConnection) { connection =>
    Using.resource(connection.prepareStatement("SELECT id,attempt FROM spoonbill_auth_ceremony WHERE realm=? AND namespace='session'")) { query =>
      query.setString(1, db.realm)
      Using.resource(query.executeQuery()) { rows =>
        val result = Set.newBuilder[UUID]
        while (rows.next()) { result += rows.getObject(1, classOf[UUID]); result += rows.getObject(2, classOf[UUID]) }
        result.result()
      }
    }
  }

  "JDBC retention" should "sustain more login cycles than every tiny capacity while preserving an active keeper" in Using.resource(new Fixture(exclusive = false)) { db =>
    val provider = db.withLimits(tinyRetention)
    try {
      val (keeper, _, _) = retentionLogin(db, provider, "k" * 43, Qsid("keeper", "view"), 600)
      for (index <- 0 until 8) {
        val rawBinding = (('a' + index).toChar.toString * 43)
        val (view, token, _) = retentionLogin(db, provider, rawBinding, Qsid("cycling", s"view-$index"), 1)
        accepted(await(provider.logout(rawBinding, Some(token.value))))
        await(provider.release(view))
        for (_ <- 0 until 2) {
          db.clock.set(db.clock.get().plusSeconds(3))
          accepted(await(provider.authorize(keeper))).principal.map(_.subject) shouldBe Some("subject")
        }
        await(provider.maintenance())
        val usage = accepted(await(provider.usage()))
        usage.retainedBindings should be <= 2L
        usage.retainedViews should be <= 2L
        usage.retainedCeremonies should be <= 2L
      }
      db.committedSessions shouldBe 9
      accepted(await(provider.authorize(keeper))).principal.map(_.subject) shouldBe Some("subject")
    } finally await(provider.close())
  }

  it should "reject old same-binding identities views ceremonies delivery and late release after reclamation" in Using.resource(new Fixture) { db =>
    val repeated = UUID.fromString("00000000-0000-4000-8000-000000000001")
    val provider = db.withLimits(tinyRetention.copy(retainedCeremonies = 4), () => repeated)
    try {
      val oldIdentity = accepted(await(provider.capture(binding, None, activate = false)))
      val oldView = accepted(await(provider.claim(oldIdentity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), false)))
      val oldCeremony = accepted(await(provider.begin(oldView, "unused")))
      val login = accepted(await(provider.begin(oldView, "login")))
      val receipt = accepted(await(db.prepareWith(provider, login, sessionSeconds = 1)))
      val oldToken = await(provider.deliver(receipt.completionId, binding)).get
      accepted(await(provider.capture(binding, Some(oldToken.value), activate = true)))
      accepted(await(provider.logout(binding, Some(oldToken.value))))
      await(provider.release(oldView))
      db.clock.set(db.clock.get().plusSeconds(8))
      await(provider.maintenance())
      val freshIdentity = accepted(await(provider.capture(binding, None, activate = false)))
      val freshView = accepted(await(provider.claim(freshIdentity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), false)))
      freshIdentity.generation should be > oldIdentity.generation
      freshView.epoch should be > oldView.epoch
      await(provider.claim(oldIdentity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)).isLeft shouldBe true
      await(provider.authorize(oldView)).isLeft shouldBe true
      await(provider.release(oldView))
      await(provider.authorize(freshView)).isRight shouldBe true
      val callbacks = new AtomicInteger()
      await(provider.prepare(oldCeremony) { _ => callbacks.incrementAndGet(); throw new AssertionError("Stale proof ran") }).isLeft shouldBe true
      callbacks.get() shouldBe 0
      await(provider.deliver(receipt.completionId, binding)) shouldBe None
      await(provider.capture(binding, Some(oldToken.value), activate = true)).isLeft shouldBe true
    } finally await(provider.close())
  }

  it should "persist issuance floors and derive distinct credentials across restart clock rollback and repeated entropy" in Using.resource(new Fixture(exclusive = false)) { db =>
    val repeated = () => UUID.fromString("00000000-0000-4000-8000-000000000002")
    val first = db.withLimits(tinyRetention, repeated)
    val initialTime = db.clock.get()
    val (view, token, receipt) = retentionLogin(db, first, binding, qsid, 1, Some("r" * 43))
    val originalIds = retainedCeremonyIds(db)
    originalIds.size shouldBe 2
    val before = retentionCounters(db)
    accepted(await(first.logout(binding, Some(token.value))))
    await(first.release(view))
    db.clock.set(initialTime.plusSeconds(8))
    await(first.maintenance())
    val reclaimed = retentionCounters(db)
    reclaimed._3 should be > before._3
    reclaimed._4 should be > before._4
    await(first.close())
    db.clock.set(initialTime)
    val second = db.withLimits(tinyRetention, repeated)
    try {
      val (fresh, freshToken, freshReceipt) = retentionLogin(db, second, binding, qsid, 100, Some("r" * 43))
      val after = retentionCounters(db)
      after._1 shouldBe before._1
      after._2 should be > before._2
      after._3 should be >= reclaimed._3
      after._4 should be >= reclaimed._4
      fresh.identity.generation should be >= reclaimed._3
      fresh.epoch should be >= reclaimed._4
      freshReceipt.completionId should not be receipt.completionId
      retainedCeremonyIds(db).intersect(originalIds) shouldBe empty
      freshToken.value should not be token.value
      freshToken.value should not be ("r" * 43)
      await(second.capture(binding, Some(token.value), activate = true)).isLeft shouldBe true
      await(second.deliver(receipt.completionId, binding)) shouldBe None
    } finally await(second.close())
  }

  it should "recover anonymous quota and expire abandoned ownership without reviving an old lease" in Using.resource(new Fixture(exclusive = false)) { db =>
    val provider = db.withLimits(tinyRetention)
    try {
      val identity = accepted(await(provider.capture(binding, None, false)))
      val old = accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), false)))
      await(provider.capture("z" * 43, None, false)) shouldBe Left(BrowserFailure.Capacity)
      db.clock.set(db.clock.get().plusSeconds(6))
      await(provider.maintenance())
      val next = accepted(await(provider.capture("z" * 43, None, false)))
      val view = accepted(await(provider.claim(next, qsid, ConnectionId.fromUuid(UUID.randomUUID()), false)))
      await(provider.authorize(old)).isLeft shouldBe true
      await(provider.release(old))
      await(provider.authorize(view)).isRight shouldBe true
      accepted(await(provider.usage())).anonymousBindings shouldBe 1L
      db.retained("spoonbill_browser_retention") shouldBe 1
    } finally await(provider.close())
  }

  it should "refuse a delayed admitted stage after maintenance removes its expired proof fence" in Using.resource(new Fixture(exclusive = false)) { db =>
    val pause = new AtomicBoolean()
    val admitted = Promise[Unit]()
    val release = Promise[Unit]()
    val outer = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact(body).flatMap { result =>
        if (result == Right(()) && pause.compareAndSet(true, false)) {
          admitted.trySuccess(())
          release.future.map(_ => result)
        } else Future.successful(result)
      }
    }
    val provider = db.withRunner(runner, tinyRetention)
    val other = db.withLimits(tinyRetention)
    try {
      val ceremony = accepted(await(provider.begin(open(provider), "login")))
      val callbacks = new AtomicInteger()
      pause.set(true)
      val pending = provider.prepare(ceremony) { _ => callbacks.incrementAndGet(); throw new AssertionError("Expired proof ran") }
      await(admitted.future)
      db.clock.set(db.clock.get().plusSeconds(8))
      await(other.maintenance())
      db.retained("spoonbill_auth_ceremony") shouldBe 0
      release.trySuccess(())
      await(pending).isLeft shouldBe true
      callbacks.get() shouldBe 0
      db.committedSessions shouldBe 0
    } finally { release.trySuccess(()); await(provider.close()); await(other.close()) }
  }

  it should "make maintenance wait for a held host transaction before reclaiming its history" in Using.resource(new Fixture(exclusive = false)) { db =>
    val provider = db.withLimits(tinyRetention)
    val other = db.withLimits(tinyRetention)
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    try {
      val ceremony = accepted(await(provider.begin(open(provider), "login")))
      val callbacks = new AtomicInteger()
      val pending = provider.prepare(ceremony) { _ =>
        callbacks.incrementAndGet(); entered.countDown()
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Host barrier timed out")
        throw new OperationProtocolException(OperationError.HostDenied)
      }
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      db.clock.set(db.clock.get().plusSeconds(8))
      val maintenance = other.maintenance()
      maintenance.isCompleted shouldBe false
      release.countDown()
      await(pending).isLeft shouldBe true
      await(maintenance)
      callbacks.get() shouldBe 1
      db.retained("spoonbill_auth_ceremony") shouldBe 0
      await(other.capture("z" * 43, None, false)).isRight shouldBe true
    } finally { release.countDown(); await(provider.close()); await(other.close()) }
  }

  it should "serialize the final anonymous slot across nodes and admit again after its lease is reclaimed" in Using.resource(new Fixture(exclusive = false)) { db =>
    val first = db.withLimits(tinyRetention)
    val second = db.withLimits(tinyRetention)
    try {
      val attempts = await(Future.sequence(Vector(first.capture(binding, None, false), second.capture("z" * 43, None, false))))
      attempts.count(_.isRight) shouldBe 1
      attempts.count(_ == Left(BrowserFailure.Capacity)) shouldBe 1
      accepted(await(first.usage())).anonymousBindings shouldBe 1L
      db.clock.set(db.clock.get().plusSeconds(6))
      await(second.maintenance())
      await(first.capture("y" * 43, None, false)).isRight shouldBe true
      accepted(await(second.usage())).anonymousBindings shouldBe 1L
    } finally { await(first.close()); await(second.close()) }
  }

  it should "retain live replacement linkage until session expiry and never revive an expired successor's predecessor" in {
    Seq(false, true).foreach { successorLivesLonger =>
      Using.resource(new Fixture(exclusive = false)) { db =>
        val provider = db.withLimits(tinyRetention.copy(retainedCeremonies = 4))
        try {
          val predecessorSeconds = if (successorLivesLonger) 1L else 100L
          val successorSeconds = if (successorLivesLonger) 100L else 1L
          val (view, oldToken, _) = retentionLogin(db, provider, binding, qsid, predecessorSeconds)
          val ceremony = accepted(await(provider.beginReplacement(view, "replace")))
          accepted(await(db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get,
            sessionSeconds = successorSeconds)))
          val request = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id))
          val delivery = accepted(await(provider.recoverReplacement(request))) match {
            case BrowserReplacementRecovery.Deliver(value) => value
            case result => fail(s"Expected replacement delivery: $result")
          }
          val successor = accepted(await(provider.deliverReplacement(request, delivery)))
          if (successorLivesLonger) accepted(await(provider.capture(binding, Some(successor.value), true)))
          db.clock.set(db.clock.get().plusSeconds(8))
          await(provider.maintenance())
          db.retained("spoonbill_browser_replacement") shouldBe 1
          await(provider.capture(binding, Some(oldToken.value), true)).isLeft shouldBe true
          if (successorLivesLonger) {
            accepted(await(provider.capture(binding, Some(successor.value), true))).principal.map(_.subject) shouldBe Some("subject")
          } else await(provider.capture(binding, Some(successor.value), true)).isLeft shouldBe true
          db.clock.set(db.clock.get().plusSeconds(100))
          await(provider.maintenance())
          db.retained("spoonbill_browser_replacement") shouldBe 0
          db.retained("spoonbill_auth_ceremony") shouldBe 0
          await(provider.capture(binding, Some(oldToken.value), true)).isLeft shouldBe true
          await(provider.capture(binding, Some(successor.value), true)).isLeft shouldBe true
          await(provider.capture(binding, None, false)).isRight shouldBe true
        } finally await(provider.close())
      }
    }
  }

  it should "exclude low-level creation while a scope is being enrolled for managed reclamation" in Using.resource(new Fixture(exclusive = false)) { db =>
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val pause = new AtomicBoolean(true)
    val outer = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact { connection =>
        val result = body(connection)
        if (pause.compareAndSet(true, false)) {
          entered.countDown()
          if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Enrollment barrier timed out")
        }
        result
      }
    }
    val provider = db.withRunner(runner, tinyRetention)
    val lower = new JdbcBrowserSessions(db.source, db.realm, "session", () => db.clock.get(), new HostSessionHooks {
      def isCurrent(connection: Connection, sessionId: UUID, time: Instant): Boolean = true
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit = ()
    })
    def digest(value: String): Digest256 = Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))).toOption.get
    try {
      val enrollment = provider.capture(binding, None, false)
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      val attempting = new CountDownLatch(1)
      val lowWrite = Future { attempting.countDown(); lower.openSlot(digest("z" * 43)) }
      attempting.await(5, TimeUnit.SECONDS) shouldBe true
      intercept[scala.concurrent.TimeoutException](Await.result(lowWrite, 150.millis))
      release.countDown()
      await(enrollment).isRight shouldBe true
      await(lowWrite) shouldBe Left(JdbcAuthError.HostDenied)
      lower.claimView("external-view", digest(binding), UUID.randomUUID()) shouldBe Left(JdbcAuthError.HostDenied)
      Using.resource(db.source.getConnection) { connection =>
        connection.setAutoCommit(false)
        try {
          val prepared = PreparedBrowserSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            digest(binding), digest("q" * 43), 0L, db.clock.get().plusSeconds(100), db.clock.get().plusSeconds(10))
          intercept[JdbcAuthException](lower.prepareNew(connection, prepared)).error shouldBe JdbcAuthError.HostDenied
        } finally connection.rollback()
      }
      accepted(await(provider.usage())).retainedBindings shouldBe 1L
      accepted(await(provider.usage())).retainedViews shouldBe 0L
    } finally { release.countDown(); await(provider.close()) }
  }

  it should "reject overlong host sessions atomically and accept the configured lifetime boundary" in Using.resource(new Fixture) { db =>
    val provider = db.withLimits(tinyRetention)
    try {
      val view = open(provider)
      val overlong = accepted(await(provider.begin(view, "overlong")))
      await(db.prepareWith(provider, overlong, sessionSeconds = 1001)).isLeft shouldBe true
      db.committedSessions shouldBe 0
      db.retained("spoonbill_browser_session") shouldBe 0
      val boundary = accepted(await(provider.begin(view, "boundary")))
      val receipt = accepted(await(db.prepareWith(provider, boundary, sessionSeconds = 1000)))
      await(provider.deliver(receipt.completionId, binding)).isDefined shouldBe true
      db.committedSessions shouldBe 1
    } finally await(provider.close())
  }

  it should "retain close ownership when an operation rolls back staged reclamation" in Using.resource(new Fixture) { db =>
    val provider = db.withLimits(tinyRetention)
    try {
      val old = open(provider)
      db.clock.set(db.clock.get().plusSeconds(6))
      // claimState stages expiry/deletion, then rejects this now-missing identity.
      // The transaction rolls back, so close still owes the original ownership.
      await(provider.claim(old.identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)).isLeft shouldBe true
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
      await(provider.close())
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
    } finally await(provider.close())
  }

  it should "clamp legacy browser lifetimes on enrollment and deny low-level authority and snapshots afterwards" in Using.resource(new Fixture(exclusive = false)) { db =>
    def digest(value: String): Digest256 = accepted(Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"))))
    val hooks = new HostSessionHooks {
      def isCurrent(connection: Connection, sessionId: UUID, time: Instant): Boolean = true
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, time: Instant): Unit = ()
    }
    val lower = new JdbcBrowserSessions(db.source, db.realm, "session", () => db.clock.get(), hooks)
    val tokenHash = digest("r" * 43)
    val bindingHash = digest(binding)
    val session = UUID.randomUUID()
    val completion = UUID.randomUUID()
    val hostCompletion = UUID.randomUUID()
    val started = db.clock.get()
    val legacyExpiry = started.plusSeconds(10000)
    val generation = accepted(lower.openSlot(bindingHash))
    Using.resource(db.source.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        Using.resource(connection.prepareStatement(
          "INSERT INTO spoonbill_auth_test_host(realm,id,completion,subject,version,expires_at,enabled) VALUES (?,?,?,'subject',1,?,TRUE)"
        )) { query =>
          query.setString(1, db.realm); query.setObject(2, session); query.setObject(3, hostCompletion)
          query.setTimestamp(4, Timestamp.from(legacyExpiry)); query.executeUpdate()
        }
        lower.prepareNew(connection, PreparedBrowserSession(completion, hostCompletion, session, bindingHash,
          tokenHash, generation, legacyExpiry, legacyExpiry))
        connection.commit()
      } catch { case error: Throwable => connection.rollback(); throw error }
    }
    val active = accepted(lower.activate(tokenHash, bindingHash))
    val owner = UUID.randomUUID()
    val viewId = "legacy-view"
    val epoch = accepted(ViewOwnershipEpoch.fromLong(accepted(lower.claimView(viewId, bindingHash, owner))))
    val snapshotIdentity = SnapshotIdentity(accepted(SnapshotKeys.Subject.parse("subject")),
      accepted(SnapshotKeys.Scope.parse("legacy")), session, SecurityGeneration.initial,
      accepted(SlotGeneration.fromLong(active.generation)))
    val identityCheck = new HostSnapshotIdentity {
      def isCurrent(connection: Connection, sessionId: UUID, expected: SnapshotIdentity, now: Instant): Boolean = true
    }
    def snapshot(): Either[ViewSnapshotError, String] = lower.withSnapshotView(viewId, bindingHash, owner,
      epoch, tokenHash, snapshotIdentity, identityCheck)((_, _) => Right("owned"))
    snapshot() shouldBe Right("owned")
    lower.validate(tokenHash, bindingHash).isRight shouldBe true
    val provider = db.withLimits(tinyRetention)
    try {
      await(provider.maintenance())
      Using.resource(db.source.getConnection) { connection =>
        Using.resource(connection.prepareStatement(
          "SELECT b.expires_at,h.expires_at FROM spoonbill_browser_session b JOIN spoonbill_auth_test_host h ON h.realm=b.realm AND h.id=b.host_session_id WHERE b.realm=?"
        )) { query =>
          query.setString(1, db.realm)
          Using.resource(query.executeQuery()) { rows =>
            rows.next() shouldBe true
            rows.getTimestamp(1).toInstant shouldBe started.plusSeconds(1000)
            rows.getTimestamp(2).toInstant shouldBe legacyExpiry
          }
        }
      }
      lower.validate(tokenHash, bindingHash) shouldBe Left(JdbcAuthError.HostDenied)
      lower.activate(tokenHash, bindingHash) shouldBe Left(JdbcAuthError.HostDenied)
      lower.delivery(completion, bindingHash) shouldBe Left(JdbcAuthError.HostDenied)
      lower.validateView(viewId, bindingHash, owner, epoch.toLong) shouldBe Left(JdbcAuthError.HostDenied)
      val authority = new HostSessionAuthorization[String] {
        def authorize(connection: Connection, sessionId: UUID, now: Instant): Option[String] = Some("subject")
      }
      lower.authorize(bindingHash, Some(tokenHash), authority) shouldBe Left(JdbcAuthError.HostDenied)
      snapshot().isLeft shouldBe true
      accepted(await(provider.capture(binding, Some("r" * 43), true))).principal.map(_.expiresAt) shouldBe Some(legacyExpiry)
      db.clock.set(started.plusSeconds(1001))
      await(provider.capture(binding, Some("r" * 43), true)).isLeft shouldBe true
    } finally await(provider.close())
  }

  "JDBC anonymous HTTP admission" should "deny cookie-free presentation without allocating in either ownership mode" in {
    Vector(true, false).foreach { exclusive =>
      Using.resource(new Fixture(exclusive = exclusive)) { db =>
        val provider = db.withLimits(BrowserPersistenceLimits(retainedBindings = 1, retainedViews = 1))
        val browser = new BrowserAuthentication[Future, Int, String](
          provider,
          BrowserPresentation(() => 0, (_, _) => false, (state, _, _) => state),
          BrowserConfiguration(provider.scope, Set("https://app.example"))
        )
        try {
          val service = spoonbillService(
            browser.configure(
              SpoonbillServiceConfig[Future, Int, Any](
                stateLoader = StateLoader.default(0),
                document = _ => Html(body(div("denied")))
              )
            )
          )
          (1 to 50).foreach { _ =>
            val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, Stream.empty[Future, Bytes], "")
            intercept[SessionAccessDenied](await(service.http(request)))
          }
          val usage = accepted(await(provider.usage()))
          usage.retainedBindings shouldBe 0
          usage.retainedViews shouldBe 0
        } finally await(browser.close())
      }
    }
  }

  it should "serialize the last retained binding admission across shared providers" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val limits = BrowserPersistenceLimits(retainedBindings = 1)
    val first  = db.withLimits(limits)
    val peer   = db.withLimits(limits)
    try {
      val attempts = Vector(
        first.capture("a" * 43, None, activate = true),
        peer.capture("b" * 43, None, activate = true)
      )
      val outcomes = await(Future.sequence(attempts))
      outcomes.count(_.isRight) shouldBe 1
      outcomes.count(_ == Left(BrowserFailure.Capacity)) shouldBe 1
      accepted(await(first.usage())).retainedBindings shouldBe 1
      accepted(await(peer.usage())).retainedBindings shouldBe 1
    } finally { await(first.close()); await(peer.close()) }
  }

  "JDBC HTTP principal observation" should "avoid retained admissions across ownership modes and provider restart" in {
    Vector(true, false).foreach { exclusive =>
      Using.resource(new Fixture(exclusive = exclusive)) { db =>
        val limits   = BrowserPersistenceLimits(retainedBindings = 1)
        val provider = db.withLimits(limits)
        try {
          (1 to 100).foreach { index =>
            accepted(await(provider.inspectPrincipal(f"$index%043d", None))) shouldBe None
          }
          accepted(await(provider.usage())).retainedBindings shouldBe 0
          val (view, token) = signedIn(db, provider)
          accepted(await(provider.inspectPrincipal(binding, Some(token.value)))).isDefined shouldBe true
          await(provider.inspectPrincipal(binding, None)).isLeft shouldBe true
          await(provider.inspectPrincipal("bad", None)).isLeft shouldBe true
          await(provider.inspectPrincipal("x" * 43, Some("t" * 43))).isLeft shouldBe true
          accepted(await(provider.inspectPrincipal("x" * 43, None))) shouldBe None
          await(provider.capture("x" * 43, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
          accepted(await(provider.authorize(view))).principal.isDefined shouldBe true
          accepted(await(provider.usage())).retainedBindings shouldBe 1
        } finally await(provider.close())
        val restarted = db.withLimits(limits)
        try {
          accepted(await(restarted.inspectPrincipal("y" * 43, None))) shouldBe None
          await(restarted.capture("y" * 43, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
          val usage = accepted(await(restarted.usage()))
          usage.retainedBindings shouldBe 1
          usage.retainedCeremonies shouldBe 1
          usage.warnings().find(_.resource == "retainedBindings").get.exhausted shouldBe true
        } finally await(restarted.close())
      }
    }
  }
  private def open(provider: BrowserPersistence[Future, String], existing: Boolean = false): BrowserView[String] = {
    val identity = accepted(await(provider.capture(binding, None, activate = false)))
    accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existing)))
  }
  private def signedIn(
    db: Fixture,
    provider: JdbcBrowserPersistence[Future, String]
  ): (BrowserView[String], BrowserSessionToken) = {
    val anonymous = open(provider)
    val login     = accepted(await(db.prepareWith(provider, accepted(await(provider.begin(anonymous, "login"))))))
    val token     = await(provider.deliver(login.completionId, binding)).get
    val identity  = accepted(await(provider.capture(binding, Some(token.value), activate = true)))
    val view = accepted(
      await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
    )
    (view, token)
  }
  private def replacementInvocation(view: BrowserView[String]): BrowserReplacementInvocation =
    BrowserReplacementInvocation.capture(
      view,
      new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), view.connectionId)
    )

  "The JDBC exclusive browser coordinator" should "retain ordinary reconnects and require an authorized baseline after credential delivery" in Using
    .resource(new Fixture) { db =>
      val origin     = "https://app.example"
      val freshPages = new AtomicInteger()
      def coordinator(provider: JdbcBrowserPersistence[Future, String]) =
        new BrowserAuthentication[Future, String, String](
          provider,
          BrowserPresentation(
            () => { freshPages.incrementAndGet(); "fresh" },
            (page, principal) => page != "recovery codes" || principal.nonEmpty,
            (page, principal, _) => if (principal.nonEmpty) "recovery codes" else page
          ),
          BrowserConfiguration(provider.scope, Set(origin)),
          clock = () => db.clock.get()
        )
      val browser = coordinator(db.persistence)
      val base = SpoonbillServiceConfig[Future, String, Any](
        stateLoader = StateLoader.default("fresh"),
        document = page => Html(body(div(page)))
      )
      val configured            = browser.configure(base)
      val access                = configured.sessionAccessControl.get
      def owner(): ConnectionId = ConnectionId.fromUuid(UUID.randomUUID())
      def request(token: Option[String] = None): HttpRequest[Future] = {
        val cookies = Seq(browser.configuration.bindingCookieName + "=" + binding) ++
          token.toSeq.map(browser.configuration.sessionCookieName + "=" + _)
        Request(
          Request.Method.Get,
          PathAndQuery.Root,
          Seq("Origin" -> origin),
          None,
          Stream.empty[Future, Bytes],
          cookies.mkString("; ")
        )
      }
      def manager = await(configured.stateStorage.get(qsid.deviceId, qsid.sessionId))
      def invocation(connection: ConnectionId): InvocationBinding =
        new InvocationBinding(
          InvocationId.fromUuid(UUID.randomUUID()),
          connection,
          expectedAuthority = Some(browser.authority)
        )
      def context(connection: ConnectionId): PublicActionContext = {
        val captured = Promise[PublicActionContext]()
        val action = new Actions[Future, String, String].public(
          accepted(ActionName.parse("test.jdbc-reconnect")),
          InputSchema.empty,
          PublicPolicy.allow[Future, Unit]
        ) { (_, context) =>
          captured.success(context)
          Future.successful(UiOutcome.noUpdate[String])
        }
        await(ActionDispatcher.public(action, Vector.empty, invocation(connection))) match {
          case InvocationResult.Completed(StateUpdate.NoUpdate()) => ()
          case other                                              => fail(s"The public action did not dispatch: $other")
        }
        await(captured.future)
      }
      try {
        db.persistence.capabilities.ownership shouldBe BrowserOwnership.ExclusiveScope
        await(configured.stateStorage.create(qsid.deviceId, qsid.sessionId, "fresh"))
        val first = await(access.open(qsid, request(), owner()))
        first.ownershipEpoch shouldBe None
        val anonymous = manager
        val child     = Id("1_2")
        await(anonymous.write(Id.TopLevel, "anonymous setup with an extra error banner"))
        await(anonymous.write(child, "anonymous component"))
        await(first.close())
        val anonymousOwner = owner()
        val sameAnonymous  = await(access.resume(qsid, request(), anonymousOwner).get)
        sameAnonymous.ownershipEpoch shouldBe None
        await(manager.read[String](Id.TopLevel)) shouldBe Some("anonymous setup with an extra error banner")
        await(manager.read[String](child)) shouldBe Some("anonymous component")
        freshPages.get() shouldBe 0

        val ceremony = accepted(await(browser.begin(context(anonymousOwner))))
        val receipt  = accepted(await(db.prepare(ceremony)))
        browser.complete(receipt) shouldBe UiOutcome.CommitAuthentication(receipt.completionId)
        val credential = await(configured.authenticationCompletion.get.deliver(receipt.completionId, binding)).get
        await(browser.authority.resolve(invocation(anonymousOwner))) shouldBe Left(AccessDenied.Unauthenticated)
        val staleManager = manager
        await(sameAnonymous.close())
        val authenticatedOwner = owner()
        val authenticated      = await(access.resume(qsid, request(Some(credential.value)), authenticatedOwner).get)
        val activeView         = accepted(await(browser.currentView(context(authenticatedOwner))))
        authenticated.ownershipEpoch shouldBe Some(activeView.fence.epoch)
        authenticated.viewSnapshots shouldBe None
        await(manager.read[String](Id.TopLevel)) shouldBe Some("fresh")
        await(manager.read[String](child)) shouldBe None
        await(authenticated.connected("fresh")) shouldBe "recovery codes"
        await(authenticated.authorize("recovery codes"))
        await(browser.authority.resolve(invocation(authenticatedOwner))) shouldBe Right("subject")
        intercept[BoundedPresentationStore.Rejected](await(staleManager.write(child, "late anonymous component")))
        freshPages.get() shouldBe 1

        // Adopt the authorized server baseline as SessionsService does. No
        // client DOM acknowledgment has arrived before this socket closes.
        await(manager.write(Id.TopLevel, "recovery codes"))
        await(manager.write(child, "authenticated component"))
        authenticated.applicationStateInitialization match {
          case ApplicationStateInitialization.Reconstructed(acknowledge) => await(acknowledge())
          case ApplicationStateInitialization.Retained()                 => fail("Credential delivery must reconstruct presentation")
        }
        await(authenticated.close())
        val resumed = await(access.resume(qsid, request(Some(credential.value)), owner()).get)
        resumed.ownershipEpoch should not be empty
        resumed.ownershipEpoch.get.toLong should be > authenticated.ownershipEpoch.get.toLong
        await(manager.read[String](Id.TopLevel)) shouldBe Some("recovery codes")
        await(manager.read[String](child)) shouldBe Some("authenticated component")
        await(resumed.authorize("recovery codes"))
        freshPages.get() shouldBe 1
        await(resumed.close())

        await(browser.close())
        val restarted = coordinator(db.fresh())
        try {
          val nextConfig = restarted.configure(base)
          val nextAccess = nextConfig.sessionAccessControl.get
          val next       = await(nextAccess.resume(qsid, request(Some(credential.value)), owner()).get)
          next.ownershipEpoch should not be empty
          next.ownershipEpoch.get.toLong should be > resumed.ownershipEpoch.get.toLong
          val nextManager = await(nextConfig.stateStorage.get(qsid.deviceId, qsid.sessionId))
          await(nextManager.read[String](Id.TopLevel)) shouldBe Some("fresh")
          await(nextManager.read[String](child)) shouldBe None
          await(next.connected("fresh")) shouldBe "recovery codes"
          await(next.authorize("recovery codes"))
          freshPages.get() shouldBe 2
          await(next.close())
        } finally await(restarted.close())
      } finally await(browser.close())
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

  it should "fence an overlapping provider's writes, output and late cleanup after shared takeover" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val first = open(db.persistence)
    db.persistence.capabilities.ownership shouldBe BrowserOwnership.SharedViews
    accepted(await(db.persistence.admitOutput(first, "before takeover"))) shouldBe "before takeover"
    val second = db.fresh()
    try {
      val next = open(second, existing = true)
      next.epoch should be > first.epoch
      await(db.persistence.authorize(first)).isLeft shouldBe true
      await(db.persistence.admitOutput(first, "stale frame")).isLeft shouldBe true
      var ran = false
      await(db.persistence.transact(first)(_ => ran = true)).isLeft shouldBe true
      ran shouldBe false
      await(db.persistence.release(first))
      await(db.persistence.close())
      accepted(await(second.admitOutput(next, "current frame"))) shouldBe "current frame"
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
    } finally await(second.close())
  }

  it should "resume an HTTP bootstrap on another node without allocating arbitrary reconnect identifiers" in Using
    .resource(
      new Fixture(exclusive = false)
    ) { db =>
      val identity = accepted(await(db.persistence.capture(binding, None, activate = false)))
      accepted(await(db.persistence.registerView(identity, qsid)))
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
      val second = db.fresh()
      try {
        val next = open(second, existing = true)
        accepted(await(db.persistence.registerView(identity, qsid)))
        await(second.authorize(next)).isRight shouldBe true
        await(
          second.claim(
            next.identity,
            Qsid("unregistered", "view"),
            ConnectionId.fromUuid(UUID.randomUUID()),
            existingOnly = true
          )
        ) shouldBe Left(BrowserFailure.Missing)
        db.retained("spoonbill_browser_view") shouldBe 1
      } finally await(second.close())
    }

  it should "reject a paused shared acquisition when a newer owner wins after admission" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val armed   = new AtomicBoolean()
    val calls   = new AtomicInteger()
    val outer   = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program     = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] =
        if (armed.get() && calls.incrementAndGet() == 2)
          Future {
            entered.countDown()
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Acquisition barrier timed out")
          }.flatMap(_ => outer.transact(body))
        else outer.transact(body)
    }
    val paused = db.withRunner(runner)
    try {
      val original = open(db.persistence)
      val captured = accepted(await(paused.capture(binding, None, activate = false)))
      armed.set(true)
      val pending = paused.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)
      entered.await(5, TimeUnit.SECONDS) shouldBe true
      val next = accepted(
        await(
          db.persistence.claim(
            original.identity,
            qsid,
            ConnectionId.fromUuid(UUID.randomUUID()),
            existingOnly = true
          )
        )
      )
      release.countDown()
      await(pending) shouldBe Left(BrowserFailure.Denied)
      await(paused.close())
      await(db.persistence.authorize(next)).isRight shouldBe true
    } finally { release.countDown(); await(paused.close()) }
  }

  it should "withhold shared output on unknown settlement and reject another node's logout" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val view = open(db.persistence)
    db.loseAcknowledgement.set(true)
    await(db.persistence.admitOutput(view, "unacknowledged frame")) shouldBe
      Left(BrowserFailure.Settlement(TransactionFailure.CommitUnknown))
    val second = db.fresh()
    try {
      accepted(await(second.logout(binding, None)))
      await(db.persistence.admitOutput(view, "revoked frame")).isLeft shouldBe true
    } finally await(second.close())
  }

  it should "fail closed during a database partition and reject its old fence after connectivity returns" in Using
    .resource(
      new Fixture(exclusive = false)
    ) { db =>
      val partitioned = new AtomicBoolean()
      val outer       = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
      val runner = new TransactionExecutor[Future, Direct, Connection] {
        val program     = TransactionProgram.direct
        val scopePolicy = TransactionScopePolicy.ThreadConfined
        def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] =
          if (partitioned.get()) Future.successful(Left(TransactionFailure.StorageFailure))
          else outer.transact(body)
      }
      val isolated = db.withRunner(runner)
      try {
        val first = open(isolated)
        partitioned.set(true)
        var ran = false
        await(isolated.transact(first)(_ => ran = true)) shouldBe Left(TransactionFailure.StorageFailure)
        ran shouldBe false
        await(isolated.admitOutput(first, "partitioned frame")) shouldBe
          Left(BrowserFailure.Settlement(TransactionFailure.StorageFailure))
        val next = open(db.persistence, existing = true)
        partitioned.set(false)
        await(isolated.transact(first)(_ => ran = true)).isLeft shouldBe true
        ran shouldBe false
        await(isolated.admitOutput(first, "stale recovered frame")).isLeft shouldBe true
        await(isolated.close())
        accepted(await(db.persistence.admitOutput(next, "healthy frame"))) shouldBe "healthy frame"
      } finally {
        partitioned.set(false)
        await(isolated.close())
      }
    }

  it should "recover a committed ceremony on another live node without replaying proof" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val first    = open(db.persistence)
    val ceremony = accepted(await(db.persistence.begin(first, "login")))
    await(db.prepareWith(db.persistence, ceremony, unknown = true)) shouldBe Left(TransactionFailure.CommitUnknown)
    val second = db.fresh()
    try {
      val next = open(second, existing = true)
      val receipt = accepted(await(second.recover(next, ceremony.id))) match {
        case BrowserRecovery.Committed(value) => value
        case _                                => fail("Expected committed recovery on the replacement node")
      }
      val credential = await(second.deliver(receipt.completionId, binding)).get
      accepted(await(second.capture(binding, Some(credential.value), activate = true))).principal
        .map(_.subject) shouldBe
        Some("subject")
      await(db.prepare(ceremony)).isLeft shouldBe true
      db.committedSessions shouldBe 1
    } finally await(second.close())
  }

  it should "observe another live node's expiry checkpoint even after its local clock moves backward" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val first      = open(db.persistence)
    val receipt    = accepted(await(db.prepare(accepted(await(db.persistence.begin(first, "login"))))))
    val credential = await(db.persistence.deliver(receipt.completionId, binding)).get
    val active     = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
    val second     = db.fresh()
    try {
      db.clock.set(active.principal.get.expiresAt)
      await(second.capture(binding, Some(credential.value), activate = false)).isLeft shouldBe true
      db.clock.set(BrowserPersistenceConformance.start)
      await(db.persistence.capture(binding, Some(credential.value), activate = false)).isLeft shouldBe true
    } finally await(second.close())
  }

  it should "restore only the selected acknowledged draft through the shared provider snapshot adapter" in Using
    .resource(
      new Fixture(exclusive = false)
    ) { db =>
      case class Screen(draft: String, freshAuthority: String)
      val format = new SnapshotFormat[String](
        accepted(SchemaId.parse("shared-draft")),
        accepted(SchemaVersion.fromInt(1))
      )
      val anonymous  = open(db.persistence)
      val receipt    = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
      val credential = await(db.persistence.deliver(receipt.completionId, binding)).get
      val identity   = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
      val first      = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
      val oldSnapshot = db.persistence
        .projectedSnapshots[Screen, String](
          format,
          _.draft,
          (fresh, draft) => fresh.copy(draft = draft)
        )(first)
        .get
      await(oldSnapshot.initialize(Screen("", "node A"))) shouldBe Screen("", "node A")
      await(oldSnapshot.commit(Screen("acknowledged draft", "node A")))
      val second = db.fresh()
      try {
        val captured = accepted(await(second.capture(binding, Some(credential.value), activate = true)))
        val next     = accepted(await(second.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
        val restored = second
          .projectedSnapshots[Screen, String](
            format,
            _.draft,
            (fresh, draft) => fresh.copy(draft = draft)
          )(next)
          .get
        await(restored.initialize(Screen("", "node B"))) shouldBe Screen("acknowledged draft", "node B")
        intercept[ViewSnapshotException](await(oldSnapshot.commit(Screen("stale overwrite", "node A"))))
        await(db.persistence.close())
        await(restored.commit(Screen("next draft", "node B")))
      } finally await(second.close())
    }

  it should "evaluate output presentation policy against current authority in one transaction" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val view  = open(db.persistence)
    val calls = new AtomicInteger()
    accepted(await(db.persistence.admitOutputIf(view, "allowed") { current =>
      calls.incrementAndGet()
      current.sameAuthority(view.identity)
    })) shouldBe "allowed"
    calls.get() shouldBe 1
    await(db.persistence.admitOutputIf(view, "denied")(_ => false)) shouldBe Left(BrowserFailure.Denied)
    val next =
      accepted(await(db.persistence.claim(view.identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    await(db.persistence.admitOutputIf(view, "stale") { _ =>
      calls.incrementAndGet(); true
    }).isLeft shouldBe true
    calls.get() shouldBe 1
    accepted(await(db.persistence.admitOutputIf(next, "current")(_ => true))) shouldBe "current"
  }

  it should "reduce ordinary protected frame admission from five provider transactions to one" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val anonymous   = open(db.persistence)
    val receipt     = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
    val token       = await(db.persistence.deliver(receipt.completionId, binding)).get
    val identity    = accepted(await(db.persistence.capture(binding, Some(token.value), activate = true)))
    val view        = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    val output      = "immutable protected frame"
    val policyCalls = new AtomicInteger()
    def permitted(current: BrowserIdentity[String]): Boolean = {
      policyCalls.incrementAndGet()
      current.sameAuthority(view.identity) && current.principal.exists(_.subject == "subject")
    }
    // Reproduce the preceding ordinary-frame protocol against this same
    // provider: current + producer checks at enqueue and pull, then admission.
    // This is not a run of a fixed historical library binary.
    def earlierChecks(remaining: Int): Future[Either[BrowserFailure, Unit]] =
      if (remaining == 0) Future.successful(Right(()))
      else
        db.persistence.authorize(view).flatMap {
          case Left(error)                          => Future.successful(Left(error))
          case Right(current) if permitted(current) => earlierChecks(remaining - 1)
          case Right(_)                             => Future.successful(Left(BrowserFailure.Denied))
        }
    def earlier(): Future[Either[BrowserFailure, String]] = earlierChecks(4).flatMap {
      case Left(error) => Future.successful(Left(error))
      case Right(_)    => db.persistence.admitOutput(view, output)
    }
    def fused(): Future[Either[BrowserFailure, String]] = db.persistence.admitOutputIf(view, output)(permitted)
    def counts(operation: => Future[Either[BrowserFailure, String]]): (Int, Int) = {
      val transactions = db.domainTransactions.get()
      val connections  = db.openedConnections.get()
      accepted(await(operation)) shouldBe output
      val domains = db.domainTransactions.get() - transactions
      // The fixture opens one connection per domain transaction; remaining
      // successful opens belong to independent durable clock checkpoints.
      domains -> (db.openedConnections.get() - connections - domains)
    }
    val earlierCounts = counts(earlier())
    val fusedCounts   = counts(fused())
    earlierCounts._1 shouldBe 5
    fusedCounts._1 shouldBe 1
    fusedCounts._2 should be > 0
    earlierCounts._2 shouldBe fusedCounts._2 * 5
    info(
      s"Ordinary-frame protocol comparison: provider transactions ${earlierCounts._1} -> ${fusedCounts._1}, clock connections ${earlierCounts._2} -> ${fusedCounts._2}"
    )

    if (sys.env.get("SPOONBILL_OUTPUT_ADMISSION_SMOKE").contains("1")) {
      for (_ <- 0 until 10) {
        accepted(await(earlier())) shouldBe output
        accepted(await(fused())) shouldBe output
      }
      val oldSamples = Vector.newBuilder[Long]
      val newSamples = Vector.newBuilder[Long]
      def sample(operation: => Future[Either[BrowserFailure, String]]): Long = {
        val start = System.nanoTime()
        accepted(await(operation)) shouldBe output
        System.nanoTime() - start
      }
      for (index <- 0 until 50)
        if (index % 2 == 0) {
          oldSamples += sample(earlier()); newSamples += sample(fused())
        } else {
          newSamples += sample(fused()); oldSamples += sample(earlier())
        }
      def summary(samples: Vector[Long]): String = {
        val sorted                            = samples.sorted
        def percentile(value: Double): Double = sorted(math.ceil(value * sorted.size).toInt - 1) / 1000000d
        f"p50=${percentile(0.50)}%.3fms p95=${percentile(0.95)}%.3fms p99=${percentile(0.99)}%.3fms"
      }
      info(
        s"Within-current-provider protocol smoke; 10 warmups, 50 alternating samples each; earlier ${summary(
          oldSamples.result()
        )}; fused ${summary(newSamples.result())}. No fixed-binary, allocation, retained-heap or full performance acceptance claim."
      )
    }

    val successor = db.fresh()
    try {
      val captured = accepted(await(successor.capture(binding, Some(token.value), activate = true)))
      val next     = accepted(await(successor.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
      val before   = policyCalls.get()
      await(earlier()).isLeft shouldBe true
      await(fused()).isLeft shouldBe true
      policyCalls.get() shouldBe before
      accepted(await(successor.admitOutputIf(next, output)(_ => true))) shouldBe output
    } finally await(successor.close())
  }

  it should "report bounded ownership pages and capacity without discarding retained history" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val first = open(db.persistence)
    val other = Qsid("provider-device", "other-view")
    val second =
      accepted(await(db.persistence.claim(first.identity, other, ConnectionId.fromUuid(UUID.randomUUID()), false)))
    accepted(await(db.persistence.begin(first, "login")))
    val usage = accepted(await(db.persistence.usage()))
    usage.retainedBindings shouldBe 1
    usage.retainedViews shouldBe 2
    usage.activeViews shouldBe 2
    usage.retainedCeremonies shouldBe 1
    usage.maxActiveViewsPerBinding shouldBe 2
    usage.maxCeremoniesPerBinding shouldBe 1
    usage.deliveryMaterials shouldBe 0
    val page = accepted(await(db.persistence.ownedViewPage(limit = 1)))
    page.views.size shouldBe 1
    page.nextAfter.isDefined shouldBe true
    val last = accepted(await(db.persistence.ownedViewPage(page.nextAfter, 1)))
    last.views.size shouldBe 1
    last.nextAfter shouldBe None
    (page.views ++ last.views).map(_.ownerId).toSet shouldBe Set(first.ownerId, second.ownerId)
    await(db.persistence.ownedViewPage(limit = 1001)) shouldBe Left(
      TransactionFailure.Rejected(OperationError.InvalidDefinition)
    )
    await(db.persistence.release(first))
    val after = accepted(await(db.persistence.usage()))
    after.activeViews shouldBe 1
    after.retainedViews shouldBe 2
    after.retainedCeremonies shouldBe 1
  }

  it should "retire only an authorized exact owner while retaining its recoverable snapshot" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val anonymous = open(db.persistence)
    val receipt   = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
    val token     = await(db.persistence.deliver(receipt.completionId, binding)).get
    val identity  = accepted(await(db.persistence.capture(binding, Some(token.value), activate = true)))
    val first     = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    val format =
      new SnapshotFormat[String](accepted(SchemaId.parse("retired-draft")), accepted(SchemaVersion.fromInt(1)))
    val snapshot =
      db.persistence.projectedSnapshots[String, String](format, value => value, (_, saved) => saved)(first).get
    await(snapshot.initialize("initial"))
    await(snapshot.commit("acknowledged"))
    val target = accepted(await(db.persistence.ownedViewPage())).views.head
    await(db.persistence.retireView(target) { tx =>
      Using.resource(
        tx.connection.prepareStatement("UPDATE spoonbill_auth_test_host SET enabled=FALSE WHERE realm=?")
      ) { query =>
        query.setString(1, db.realm); query.executeUpdate()
      }
      false
    }) shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
    await(db.persistence.authorize(first)).isRight shouldBe true
    val authorizations = new AtomicInteger()
    accepted(await(db.persistence.retireView(target) { tx =>
      authorizations.incrementAndGet()
      tx.connection.getAutoCommit shouldBe false
      Using.resource(
        tx.connection.prepareStatement("SELECT enabled FROM spoonbill_auth_test_host WHERE realm=? FOR UPDATE")
      ) { query =>
        query.setString(1, db.realm)
        Using.resource(query.executeQuery())(rows => rows.next() && rows.getBoolean(1))
      }
    })) shouldBe true
    authorizations.get() shouldBe 1
    await(db.persistence.admitOutput(first, "retired")).isLeft shouldBe true
    intercept[ViewSnapshotException](await(snapshot.commit("stale")))
    val usage = accepted(await(db.persistence.usage()))
    usage.activeViews shouldBe 0
    usage.retainedViews shouldBe 1
    val next = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    next.epoch should be > target.epoch.toLong
    val restored =
      db.persistence.projectedSnapshots[String, String](format, value => value, (_, saved) => saved)(next).get
    await(restored.initialize("fresh")) shouldBe "acknowledged"
    accepted(await(db.persistence.retireView(target)(_ => true))) shouldBe false
    await(db.persistence.authorize(next)).isRight shouldBe true
    await(
      db.persistence.retireView(target.copy(scope = BrowserProviderScope("foreign", "session")))(_ => true)
    ) shouldBe
      Left(TransactionFailure.Rejected(OperationError.InvalidDefinition))
  }

  it should "free active capacity through explicit retirement and expose uncertain settlement" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val provider = db.withLimits(BrowserPersistenceLimits(activeViewsPerBinding = 1))
    try {
      val first   = open(provider)
      val another = Qsid("capacity-device", "capacity-view")
      await(provider.claim(first.identity, another, ConnectionId.fromUuid(UUID.randomUUID()), false)) shouldBe Left(
        BrowserFailure.Capacity
      )
      val target = accepted(await(provider.ownedViewPage())).views.head
      db.loseAcknowledgement.set(true)
      await(provider.retireView(target)(_ => true)) shouldBe Left(TransactionFailure.CommitUnknown)
      accepted(await(provider.ownedViewPage())).views shouldBe empty
      val next =
        accepted(await(provider.claim(first.identity, another, ConnectionId.fromUuid(UUID.randomUUID()), false)))
      val usage = accepted(await(provider.usage()))
      usage.activeViews shouldBe 1
      usage.retainedViews shouldBe 2
      usage.limits.activeViewsPerBinding shouldBe 1
      await(provider.release(first))
      await(provider.authorize(next)).isRight shouldBe true
    } finally await(provider.close())
  }

  it should "retain the original regression as ordinary preparation rejects predecessor revocation atomically" in Using
    .resource(
      new Fixture(exclusive = false)
    ) { db =>
      val anonymous  = open(db.persistence)
      val login      = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
      val credential = await(db.persistence.deliver(login.completionId, binding)).get
      val identity   = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
      val authenticated = accepted(
        await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
      )
      val ceremony = accepted(await(db.persistence.begin(authenticated, "replace-factor")))

      // Generic host security mutation: replacement session and old-session
      // revocation must share the provider's outer transaction.
      val outcome                    = await(db.prepareWith(db.persistence, ceremony, revokePrevious = identity.sessionId))
      val predecessorStillAuthorized = await(db.persistence.authorize(authenticated)).isRight
      val committedHostSessions      = db.committedSessions
      withClue(
        s"Preparation outcome=$outcome; predecessor remains authorized=$predecessorStillAuthorized; host rows=$committedHostSessions. "
      ) {
        outcome shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
      }
      predecessorStillAuthorized shouldBe true
      committedHostSessions shouldBe 1
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

  "Authenticated JDBC replacement" should "commit once, fence predecessor authority, and resolve the activated successor's host completion" in Using
    .resource(new Fixture) { db =>
      val provider         = db.persistence
      val (view, oldToken) = signedIn(db, provider)
      val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
      val invocation       = replacementInvocation(view)
      val handoff =
        accepted(await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get, version = 2L)))
      await(provider.authorize(view)).isLeft shouldBe true
      db.committedSessions shouldBe 2
      accepted(
        await(provider.admitReplacementCompletion(handoff, invocation))
      ).completionId shouldBe handoff.completionId
      await(provider.admitReplacementCompletion(handoff, invocation)).isLeft shouldBe true
      val request = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id))
      accepted(await(provider.replacementPending(request))) shouldBe true
      val delivery = accepted(await(provider.recoverReplacement(request))) match {
        case BrowserReplacementRecovery.Deliver(value) => value
        case other                                     => fail(s"Expected delivery, got $other")
      }
      val successorToken = accepted(await(provider.deliverReplacement(request, delivery)))
      await(provider.deliverReplacement(request, delivery)).isLeft shouldBe true
      await(provider.capture(binding, Some(successorToken.value), activate = false)).isLeft shouldBe true
      val successor = accepted(await(provider.capture(binding, Some(successorToken.value), activate = true)))
      val successorView =
        accepted(await(provider.claim(successor, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)))
      val metadata = accepted(await(provider.withSuccessorCompletion(successorView)((_, value) => value)))
      metadata.successorSessionId shouldBe successor.sessionId.get
      metadata.hostCompletionId should not be handoff.completionId
      await(provider.withSuccessorCompletion(view)((_, value) => value)).isLeft shouldBe true
      accepted(
        await(
          provider.replacementPending(
            new BrowserReplacementRequest(binding, Some(successorToken.value), Some(ceremony.id))
          )
        )
      ) shouldBe false
    }

  it should "account for replacement transactions, durable-clock connections, host lookups and one-use callbacks" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      val provider            = db.persistence
      val (view, oldToken)    = signedIn(db, provider)
      val completionCallbacks = new AtomicInteger()
      def callbacks: Int      = db.replacementCallbacks.get() + completionCallbacks.get()
      def measure[A](phase: String, expectedTransactions: Int, expectedCallbacks: Int = 0)(
        operation: => Future[A]
      ): A = {
        val transactionsBefore = db.domainTransactions.get()
        val connectionsBefore  = db.openedConnections.get()
        val resolutionsBefore  = db.hostResolutions.get()
        val callbacksBefore    = callbacks
        val value              = await(operation)
        val transactions       = db.domainTransactions.get() - transactionsBefore
        val connections        = db.openedConnections.get() - connectionsBefore
        val clockConnections   = connections - transactions
        val resolutions        = db.hostResolutions.get() - resolutionsBefore
        val invoked            = callbacks - callbacksBefore
        withClue(phase + ": ") {
          transactions shouldBe expectedTransactions
          clockConnections should be >= 0
          invoked shouldBe expectedCallbacks
        }
        info(
          s"Replacement accounting [$phase, shared-views]: domainTransactions=$transactions, independentClockConnections=$clockConnections, hostLookups=$resolutions, hostCallbacks=$invoked"
        )
        value
      }
      val ceremony = accepted(measure("begin", 1)(provider.beginReplacement(view, "replace-factor")))
      val bound = accepted(
        measure("bind", 1)(
          provider.bindReplacementChallenge(ceremony, BrowserChallenge("subject", 1L, 1L, "accounting-proof"))
        )
      )
      val invocation = replacementInvocation(view)
      val handoff =
        accepted(measure("prepare", 2, 1)(db.replaceWith(provider, bound, invocation, view.identity.sessionId.get)))
      accepted(measure("frame-admission", 1)(provider.admitReplacementCompletion(handoff, invocation)))
      val request = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(bound.id))
      val delivery = accepted(measure("recover", 1)(provider.recoverReplacement(request))) match {
        case BrowserReplacementRecovery.Deliver(value) => value
        case other                                     => fail(s"Expected delivery: $other")
      }
      val token     = accepted(measure("delivery", 1)(provider.deliverReplacement(request, delivery)))
      val successor = accepted(measure("activation", 1)(provider.capture(binding, Some(token.value), activate = true)))
      val successorView = accepted(
        measure("successor-claim", 2)(
          provider.claim(successor, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)
        )
      )
      val completion = accepted(measure("successor-completion", 1, 1)(provider.withSuccessorCompletion(successorView) {
        (_, value) =>
          completionCallbacks.incrementAndGet()
          value
      }))
      completion.successorSessionId shouldBe successor.sessionId.get
      measure("denied-prepare-replay", 0)(
        db.replaceWith(provider, bound, invocation, view.identity.sessionId.get)
      ).isLeft shouldBe true
      accepted(
        measure("activated-recovery", 1)(
          provider.recoverReplacement(new BrowserReplacementRequest(binding, Some(token.value), Some(bound.id)))
        )
      ) shouldBe BrowserReplacementRecovery.ActivateExistingCookie
      measure("maintenance", 1)(provider.maintenance())
      db.replacementCallbacks.get() shouldBe 1
      completionCallbacks.get() shouldBe 1
      db.committedSessions shouldBe 2
    }

  it should "invoke a successor-completion callback only once when the same lazy outer effect is evaluated twice" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      given Effect[Deferred] = new DeferredFutureEffect
      val (view, _)          = signedIn(db, db.persistence)
      val ceremony           = accepted(await(db.persistence.beginReplacement(view, "replace-factor")))
      val handoff = accepted(
        await(db.replaceWith(db.persistence, ceremony, replacementInvocation(view), view.identity.sessionId.get))
      )
      val token    = await(db.persistence.deliver(handoff.completionId, binding)).get
      val provider = db.deferred()
      try {
        val identity = accepted(await(provider.capture(binding, Some(token.value), activate = true)()))
        val successor = accepted(
          await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)())
        )
        val callbacks = new AtomicInteger()
        val operation = provider.withSuccessorCompletion(successor) { (_, completion) =>
          callbacks.incrementAndGet()
          completion
        }
        callbacks.get() shouldBe 0
        accepted(await(operation())).successorSessionId shouldBe identity.sessionId.get
        await(operation()) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
        callbacks.get() shouldBe 1
      } finally {
        val closing: Deferred[Unit] = provider.close()
        await(closing())
      }
    }

  it should "recover a committed unknown outcome on another node without old ordinary authority or another callback" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      val provider         = db.persistence
      val (view, oldToken) = signedIn(db, provider)
      val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
      await(
        db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get, unknown = true)
      ) shouldBe
        Left(TransactionFailure.CommitUnknown)
      await(provider.authorize(view)).isLeft shouldBe true
      await(provider.close())
      val next = db.fresh()
      try {
        val request = new BrowserReplacementRequest(binding, Some(oldToken.value), None)
        val delivery = accepted(await(next.recoverReplacement(request))) match {
          case BrowserReplacementRecovery.Deliver(value) => value
          case other                                     => fail(s"Expected recovered delivery, got $other")
        }
        val token = accepted(await(next.deliverReplacement(request, delivery)))
        accepted(
          await(next.capture(binding, Some(token.value), activate = true))
        ).sessionId should not be view.identity.sessionId
        db.committedSessions shouldBe 2
      } finally await(next.close())
    }

  it should "discover chained replacements on a peer with retained lineage and an exhausted final delivery budget" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      val limits   = BrowserPersistenceLimits(maxDeliveries = 1)
      val original = db.withLimits(limits)
      val peer     = db.withLimits(limits)
      try {
        val (initial, initialToken) = signedIn(db, original)
        var view                    = initial
        var token                   = initialToken
        var previousToken           = Option.empty[BrowserSessionToken]
        for (index <- 1 to 3) {
          val predecessor = view
          val ceremony    = accepted(await(original.beginReplacement(view, "replace-factor")))
          val handoff = accepted(
            await(db.replaceWith(original, ceremony, replacementInvocation(view), view.identity.sessionId.get))
          )
          db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe index
          db.committedSessions shouldBe index + 1
          db.replacementCallbacks.get() shouldBe index
          val request = new BrowserReplacementRequest(binding, Some(token.value), None)
          accepted(await(peer.replacementPending(request))) shouldBe true
          accepted(await(peer.replacementPending(request))) shouldBe true
          await(peer.capture(binding, Some(token.value), activate = true)).isLeft shouldBe true
          await(
            peer.replacementPending(new BrowserReplacementRequest(binding, Some("z" * 43), None))
          ).isLeft shouldBe true
          previousToken.foreach { stale =>
            await(
              peer.recoverReplacement(new BrowserReplacementRequest(binding, Some(stale.value), None))
            ).isLeft shouldBe true
          }
          val delivery = accepted(await(peer.recoverReplacement(request))) match {
            case BrowserReplacementRecovery.Deliver(value) => value
            case other                                     => fail(s"Expected peer replacement delivery: $other")
          }
          delivery.completionId shouldBe handoff.completionId
          previousToken = Some(token)
          token = accepted(await(peer.deliverReplacement(request, delivery)))
          await(original.deliver(handoff.completionId, binding)) shouldBe None
          accepted(await(peer.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.CommittedUnavailable
          val held = new BrowserReplacementRequest(binding, Some(token.value), None)
          accepted(await(peer.replacementPending(held))) shouldBe true
          accepted(await(peer.recoverReplacement(held))) shouldBe BrowserReplacementRecovery.ActivateExistingCookie
          val active = accepted(await(peer.capture(binding, Some(token.value), activate = true)))
          active.sessionId should not be predecessor.identity.sessionId
          val local = accepted(await(original.capture(binding, Some(token.value), activate = false)))
          local.sessionId shouldBe active.sessionId
          view = accepted(
            await(original.claim(local, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
          )
          accepted(
            await(original.withSuccessorCompletion(view)((_, completion) => completion))
          ).successorSessionId shouldBe active.sessionId.get
          accepted(await(peer.replacementPending(held))) shouldBe false
          accepted(await(peer.recoverReplacement(held))) shouldBe BrowserReplacementRecovery.ActivateExistingCookie
          await(original.release(predecessor))
          await(original.authorize(view)).isRight shouldBe true
          db.committedSessions shouldBe index + 1
          db.replacementCallbacks.get() shouldBe index
          db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe index
        }
      } finally {
        await(original.close())
        await(peer.close())
      }
    }

  it should "preserve exact nanosecond host stamps through preparation, restart, activation and successor lookup" in Using
    .resource(new Fixture(exclusive = false, hostExpiryNanos = 123)) { db =>
      val provider         = db.persistence
      val (view, oldToken) = signedIn(db, provider)
      view.identity.host.get.expiresAt.getNano shouldBe 123
      val ceremony = accepted(await(provider.beginReplacement(view, "replace-factor")))
      val bound =
        accepted(await(provider.bindReplacementChallenge(ceremony, BrowserChallenge("subject", 1L, 1L, "nano-proof"))))
      accepted(await(db.replaceWith(provider, bound, replacementInvocation(view), view.identity.sessionId.get)))
      await(provider.close())
      val restarted = db.fresh()
      try {
        val request = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(bound.id))
        val delivery = accepted(await(restarted.recoverReplacement(request))) match {
          case BrowserReplacementRecovery.Deliver(value) => value
          case other                                     => fail(s"Expected durable delivery: $other")
        }
        val token    = accepted(await(restarted.deliverReplacement(request, delivery)))
        val identity = accepted(await(restarted.capture(binding, Some(token.value), activate = true)))
        identity.host.get.expiresAt.getNano shouldBe 123
        val successor = accepted(
          await(restarted.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
        )
        accepted(
          await(restarted.withSuccessorCompletion(successor)((_, value) => value))
        ).successorSessionId shouldBe identity.sessionId.get
        db.committedSessions shouldBe 2
      } finally await(restarted.close())
    }

  it should "activate a held final-delivery cookie without material while retaining committed-unavailable recovery" in Using
    .resource(new Fixture) { db =>
      val provider = db.withLimits(BrowserPersistenceLimits(maxDeliveries = 1))
      try {
        val (view, oldToken) = signedIn(db, provider)
        val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
        val handoff =
          accepted(await(db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get)))
        val token = await(provider.deliver(handoff.completionId, binding)).get
        await(provider.deliver(handoff.completionId, binding)) shouldBe None
        val absent = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id))
        accepted(await(provider.recoverReplacement(absent))) shouldBe BrowserReplacementRecovery.CommittedUnavailable
        accepted(await(provider.replacementPending(absent))) shouldBe false
        val held = new BrowserReplacementRequest(binding, Some(token.value), Some(ceremony.id))
        accepted(await(provider.replacementPending(held))) shouldBe true
        accepted(await(provider.recoverReplacement(held))) shouldBe BrowserReplacementRecovery.ActivateExistingCookie
        accepted(await(provider.capture(binding, Some(token.value), activate = true))).principal.nonEmpty shouldBe true
      } finally await(provider.close())
    }

  it should "retire crashed predecessor owners atomically so a fresh recovery page can claim at the active-view limit" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      val limits    = BrowserPersistenceLimits(activeViewsPerBinding = 1)
      val original  = db.withLimits(limits)
      val recovered = db.withLimits(limits)
      try {
        val (view, _) = signedIn(db, original)
        val ceremony  = accepted(await(original.beginReplacement(view, "replace-factor")))
        val handoff =
          accepted(await(db.replaceWith(original, ceremony, replacementInvocation(view), view.identity.sessionId.get)))
        val token = await(original.deliver(handoff.completionId, binding)).get
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
        // No release/close from the predecessor node: its durable owner remains.
        val identity = accepted(await(recovered.capture(binding, Some(token.value), activate = true)))
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
        val freshPage = Qsid(qsid.deviceId, "replacement-recovery-fresh-page")
        accepted(await(recovered.registerView(identity, freshPage)))
        val successor = accepted(
          await(recovered.claim(identity, freshPage, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
        )
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
        db.retained("spoonbill_browser_view") shouldBe 2
        await(original.release(view))
        await(recovered.authorize(successor)).isRight shouldBe true
        // A lost activation response may retry after the new owner has attached.
        accepted(await(recovered.capture(binding, Some(token.value), activate = true)))
          .sameAuthority(identity) shouldBe true
        await(recovered.authorize(successor)).isRight shouldBe true
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
        // Activation can win before another node's logout. That successor owner
        // is obsolete too once the authoritative slot is reset.
        accepted(await(original.logout(binding, Some(token.value))))
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
        val anonymous = accepted(await(original.capture(binding, None, activate = false)))
        val resetPage = Qsid(qsid.deviceId, "replacement-active-reset-page")
        accepted(await(original.registerView(anonymous, resetPage)))
        val resetView = accepted(
          await(original.claim(anonymous, resetPage, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
        )
        await(recovered.release(successor))
        accepted(await(original.authorize(resetView))).principal shouldBe None
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
      } finally { await(original.close()); await(recovered.close()) }
    }

  it should "free expired replacement owners on logout so a fresh login can claim at the active-view limit" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      val limits    = BrowserPersistenceLimits(activeViewsPerBinding = 1)
      val original  = db.withLimits(limits)
      val recovered = db.withLimits(limits)
      try {
        val (view, oldToken) = signedIn(db, original)
        val ceremony         = accepted(await(original.beginReplacement(view, "replace-factor")))
        accepted(await(db.replaceWith(original, ceremony, replacementInvocation(view), view.identity.sessionId.get)))
        db.clock.set(db.clock.get().plusSeconds(limits.deliverySeconds))
        await(recovered.maintenance())
        val request = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id))
        accepted(await(recovered.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.CommittedUnavailable
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
        accepted(await(recovered.logout(binding, Some(oldToken.value))))
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
        val anonymous = accepted(await(recovered.capture(binding, None, activate = false)))
        anonymous.principal shouldBe None
        val freshPage = Qsid(qsid.deviceId, "replacement-reset-fresh-page")
        accepted(await(recovered.registerView(anonymous, freshPage)))
        val freshView = accepted(
          await(recovered.claim(anonymous, freshPage, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
        )
        await(original.release(view))
        accepted(await(recovered.authorize(freshView))).principal shouldBe None
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
        db.retained("spoonbill_browser_view") shouldBe 2
        accepted(await(recovered.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.CommittedUnavailable
      } finally { await(original.close()); await(recovered.close()) }
    }

  it should "keep an expired reservation fenced against competing completions until explicit logout" in Using.resource(
    new Fixture
  ) { db =>
    val provider         = db.persistence
    val (view, oldToken) = signedIn(db, provider)
    val competing        = accepted(await(db.prepareWith(provider, accepted(await(provider.begin(view, "ordinary-login"))))))
    val competingToken   = await(provider.deliver(competing.completionId, binding)).get
    val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
    val handoff =
      accepted(await(db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get)))
    val token = await(provider.deliver(handoff.completionId, binding)).get
    await(provider.deliver(competing.completionId, binding)) shouldBe None
    await(provider.capture(binding, Some(competingToken.value), activate = true)).isLeft shouldBe true
    db.clock.set(db.clock.get().plusSeconds(60))
    await(provider.maintenance())
    val request = new BrowserReplacementRequest(binding, Some(token.value), Some(ceremony.id))
    accepted(await(provider.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.CommittedUnavailable
    accepted(await(provider.replacementPending(request))) shouldBe false
    await(provider.capture(binding, Some(token.value), activate = true)).isLeft shouldBe true
    await(provider.capture(binding, Some(competingToken.value), activate = true)).isLeft shouldBe true
    await(provider.capture(binding, Some(oldToken.value), activate = true)).isLeft shouldBe true
    accepted(await(provider.logout(binding, Some(oldToken.value))))
    accepted(await(provider.capture(binding, None, activate = false))).principal shouldBe None
    await(provider.capture(binding, Some(competingToken.value), activate = true)).isLeft shouldBe true
  }

  it should "roll back a switched subject or a successor whose predecessor remains host-authorized" in {
    Vector("subject" -> false, "other" -> true).foreach { case (subject, revoke) =>
      Using.resource(new Fixture) { db =>
        val (view, _) = signedIn(db, db.persistence)
        val ceremony  = accepted(await(db.persistence.beginReplacement(view, "replace-factor")))
        await(
          db.replaceWith(
            db.persistence,
            ceremony,
            replacementInvocation(view),
            view.identity.sessionId.get,
            revoke = revoke,
            subject = subject
          )
        ).isLeft shouldBe true
        await(db.persistence.authorize(view)).isRight shouldBe true
        db.committedSessions shouldBe 1
        val request = new BrowserReplacementRequest(binding, view.identity.token, Some(ceremony.id))
        accepted(await(db.persistence.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.NotCommitted
      }
    }
  }

  it should "reject stale-generation negative recovery after logout without changing its admitted fence" in Using
    .resource(new Fixture) { db =>
      val provider         = db.persistence
      val (view, oldToken) = signedIn(db, provider)
      val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
      await(
        db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get, fail = true)
      ).isLeft shouldBe true
      accepted(await(provider.logout(binding, Some(oldToken.value))))
      val request = new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id))
      await(provider.recoverReplacement(request)) shouldBe Left(BrowserFailure.Denied)
      Using.resource(db.source.getConnection) { connection =>
        Using.resource(
          connection.prepareStatement(
            "SELECT phase FROM spoonbill_auth_ceremony WHERE realm=? AND namespace='session' AND id=?"
          )
        ) { query =>
          query.setString(1, db.realm); query.setObject(2, ceremony.id.value)
          Using.resource(query.executeQuery()) { rows =>
            rows.next() shouldBe true
            rows.getString(1) shouldBe "Admitted"
          }
        }
      }
      db.replacementCallbacks.get() shouldBe 1
      db.committedSessions shouldBe 1
    }

  it should "deny foreign, unrelated-cookie and revoked-successor recovery without granting anonymous access" in Using
    .resource(new Fixture) { db =>
      val provider         = db.persistence
      val (view, oldToken) = signedIn(db, provider)
      val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
      val handoff =
        accepted(await(db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get)))
      val token = await(provider.deliver(handoff.completionId, binding)).get
      await(
        provider.recoverReplacement(new BrowserReplacementRequest("z" * 43, Some(oldToken.value), Some(ceremony.id)))
      ).isLeft shouldBe true
      await(
        provider.recoverReplacement(new BrowserReplacementRequest(binding, Some("z" * 43), Some(ceremony.id)))
      ).isLeft shouldBe true
      await(
        provider.recoverReplacement(new BrowserReplacementRequest(binding, Some("malformed"), Some(ceremony.id)))
      ).isLeft shouldBe true
      db.disable()
      accepted(
        await(provider.recoverReplacement(new BrowserReplacementRequest(binding, Some(token.value), Some(ceremony.id))))
      ) shouldBe
        BrowserReplacementRecovery.CommittedUnavailable
      await(provider.capture(binding, Some(token.value), activate = true)).isLeft shouldBe true
    }

  it should "reject activation after the pending successor stamp changes and deny Boolean-only adapter bypass" in {
    Vector("version=version+1", "expires_at=expires_at + interval '1 second'", "subject='changed-subject'").foreach {
      change =>
        Using.resource(new Fixture) { db =>
          val provider  = db.persistence
          val (view, _) = signedIn(db, provider)
          val ceremony  = accepted(await(provider.beginReplacement(view, "replace-factor")))
          val handoff = accepted(
            await(db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get))
          )
          val token            = await(provider.deliver(handoff.completionId, binding)).get
          val acknowledgements = new AtomicInteger()
          val lower = new JdbcBrowserSessions(
            db.source,
            db.realm,
            "session",
            () => db.clock.get(),
            new HostSessionHooks {
              def isCurrent(connection: Connection, sessionId: UUID, now: Instant): Boolean = true
              def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = {
                acknowledgements.incrementAndGet(); ()
              }
            }
          )
          def digest(value: String): Digest256 = Digest256
            .fromBytes(
              MessageDigest
                .getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
            )
            .toOption
            .get
          // Legacy Boolean hooks cannot authorize a replacement even before a host change.
          lower.activate(digest(token.value), digest(binding)) shouldBe Left(JdbcAuthError.HostDenied)
          Using.resource(db.source.getConnection) { connection =>
            Using.resource(
              connection.prepareStatement(s"UPDATE spoonbill_auth_test_host SET $change WHERE realm=? AND enabled=TRUE")
            ) { query =>
              query.setString(1, db.realm); query.executeUpdate() shouldBe 1
            }
          }
          val request = new BrowserReplacementRequest(binding, Some(token.value), Some(ceremony.id))
          accepted(await(provider.recoverReplacement(request))) shouldBe BrowserReplacementRecovery.CommittedUnavailable
          await(provider.capture(binding, Some(token.value), activate = true)).isLeft shouldBe true
          lower.activate(digest(token.value), digest(binding)) shouldBe Left(JdbcAuthError.HostDenied)
          acknowledgements.get() shouldBe 0
          await(provider.capture(binding, None, activate = false)).isLeft shouldBe true
          db.committedSessions shouldBe 2
        }
    }
  }

  Vector("host revocation" -> "enabled=FALSE", "host version change" -> "version=version+1").foreach {
    case (change, mutation) =>
      it should s"recheck independent $change after durable admission before invoking replacement work" in Using
        .resource(new Fixture) { db =>
          val pause    = new AtomicBoolean()
          val admitted = Promise[Unit]()
          val release  = Promise[Unit]()
          val calls    = new AtomicInteger()
          val outer    = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
          val runner = new TransactionExecutor[Future, Direct, Connection] {
            val program     = TransactionProgram.direct
            val scopePolicy = TransactionScopePolicy.ThreadConfined
            def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] =
              outer.transact(body).flatMap { result =>
                if (result == Right(()) && pause.compareAndSet(true, false)) {
                  admitted.success(())
                  release.future.map(_ => result)
                } else Future.successful(result)
              }
          }
          val provider = db.withRunner(runner)
          try {
            val (view, _) = signedIn(db, provider)
            val ceremony  = accepted(await(provider.beginReplacement(view, "replace-factor")))
            pause.set(true)
            val operation = provider.prepareReplacement(ceremony, replacementInvocation(view)) { _ =>
              calls.incrementAndGet()
              throw new IllegalStateException("Changed predecessor must not reach host work")
            }
            await(admitted.future)
            Using.resource(db.source.getConnection) { connection =>
              Using.resource(
                connection.prepareStatement(
                  s"UPDATE spoonbill_auth_test_host SET $mutation WHERE realm=? AND enabled=TRUE"
                )
              ) { query =>
                query.setString(1, db.realm); query.executeUpdate() shouldBe 1
              }
            }
            release.success(())
            await(operation) shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
            calls.get() shouldBe 0
            db.committedSessions shouldBe 1
            db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 0
          } finally {
            release.trySuccess(())
            await(provider.close())
          }
        }
  }

  it should "exclude an admitted writer before returning a negative recovery outcome" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val provider         = db.persistence
    val (view, oldToken) = signedIn(db, provider)
    val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
    val entered          = new CountDownLatch(1)
    val release          = new CountDownLatch(1)
    val calls            = new AtomicInteger()
    val operation = provider.prepareReplacement(ceremony, replacementInvocation(view)) { _ =>
      calls.incrementAndGet(); entered.countDown()
      if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writer barrier timed out")
      throw new OperationProtocolException(OperationError.HostDenied)
    }
    entered.await(10, TimeUnit.SECONDS) shouldBe true
    val other = db.fresh()
    try {
      val recovery =
        other.recoverReplacement(new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id)))
      // The reader cannot finish while the mutation owns the realm/ceremony fence.
      recovery.isCompleted shouldBe false
      release.countDown()
      await(operation).isLeft shouldBe true
      accepted(await(recovery)) shouldBe BrowserReplacementRecovery.NotCommitted
      calls.get() shouldBe 1
      db.committedSessions shouldBe 1
    } finally { release.countDown(); await(other.close()) }
  }

  it should "roll back replacement host and browser effects when credential sealing fails" in Using.resource(
    new Fixture
  ) { db =>
    val provider         = db.persistence
    val (view, oldToken) = signedIn(db, provider)
    val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
    val invocation       = replacementInvocation(view)
    db.failNextSeal.set(true)
    await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get)) shouldBe Left(
      TransactionFailure.RolledBack
    )
    db.failNextSeal.get() shouldBe false
    db.replacementCallbacks.get() shouldBe 1
    db.committedSessions shouldBe 1
    db.retained("spoonbill_browser_session") shouldBe 1
    db.retained("spoonbill_auth_material") shouldBe 0
    db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 0
    await(provider.authorize(view)).isRight shouldBe true
    accepted(
      await(
        provider.recoverReplacement(new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id)))
      )
    ) shouldBe BrowserReplacementRecovery.NotCommitted
    await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get)).isLeft shouldBe true
    db.replacementCallbacks.get() shouldBe 1
  }

  it should "roll back a replacement at material capacity while preserving an existing ordinary completion" in Using
    .resource(new Fixture) { db =>
      val provider = db.withLimits(BrowserPersistenceLimits(deliveryMaterials = 1))
      try {
        val (view, oldToken) = signedIn(db, provider)
        val competing =
          accepted(await(db.prepareWith(provider, accepted(await(provider.begin(view, "ordinary-login"))))))
        db.retained("spoonbill_auth_material") shouldBe 1
        val ceremony   = accepted(await(provider.beginReplacement(view, "replace-factor")))
        val invocation = replacementInvocation(view)
        await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get)) shouldBe
          Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
        // JDBC observes this capacity after host-row acquisition and staging;
        // one callback may run, but none of its effects may commit.
        db.replacementCallbacks.get() shouldBe 1
        db.committedSessions shouldBe 2
        db.retained("spoonbill_browser_session") shouldBe 2
        db.retained("spoonbill_auth_material") shouldBe 1
        db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 0
        await(provider.authorize(view)).isRight shouldBe true
        accepted(
          await(
            provider.recoverReplacement(new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id)))
          )
        ) shouldBe BrowserReplacementRecovery.NotCommitted
        await(provider.deliver(competing.completionId, binding)).nonEmpty shouldBe true
        await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get)).isLeft shouldBe true
        db.replacementCallbacks.get() shouldBe 1
      } finally await(provider.close())
    }

  it should "roll back replacement expiry during its callback without consuming the predecessor or replaying work" in Using
    .resource(new Fixture) { db =>
      val provider         = db.persistence
      val (view, oldToken) = signedIn(db, provider)
      val ceremony         = accepted(await(provider.beginReplacement(view, "replace-factor")))
      val invocation       = replacementInvocation(view)
      await(
        db.replaceWith(
          provider,
          ceremony,
          invocation,
          view.identity.sessionId.get,
          afterHost = () => db.clock.set(ceremony.expiresAt)
        )
      ) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
      db.replacementCallbacks.get() shouldBe 1
      db.committedSessions shouldBe 1
      db.retained("spoonbill_browser_session") shouldBe 1
      db.retained("spoonbill_auth_material") shouldBe 0
      db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 0
      await(provider.authorize(view)).isRight shouldBe true
      accepted(
        await(
          provider.recoverReplacement(new BrowserReplacementRequest(binding, Some(oldToken.value), Some(ceremony.id)))
        )
      ) shouldBe BrowserReplacementRecovery.NotCommitted
      await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get)).isLeft shouldBe true
      db.replacementCallbacks.get() shouldBe 1
    }

  it should "serialize two distinct admitted replacements across shared nodes with one host callback and successor" in Using
    .resource(new Fixture(exclusive = false)) { db =>
      val armed      = new AtomicBoolean()
      val admissions = new AtomicInteger()
      val release    = Promise[Unit]()
      val outer      = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
      val runner = new TransactionExecutor[Future, Direct, Connection] {
        val program     = TransactionProgram.direct
        val scopePolicy = TransactionScopePolicy.ThreadConfined
        def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] =
          outer.transact(body).flatMap { result =>
            if (armed.get() && result == Right(())) {
              if (admissions.incrementAndGet() == 2) release.trySuccess(())
              release.future.map(_ => result)
            } else Future.successful(result)
          }
      }
      val first  = db.withRunner(runner)
      val second = db.withRunner(runner)
      try {
        val (firstView, oldToken) = signedIn(db, first)
        val identity              = accepted(await(second.capture(binding, Some(oldToken.value), activate = true)))
        val secondView = accepted(
          await(
            second.claim(
              identity,
              Qsid(qsid.deviceId, "parallel-replacement"),
              ConnectionId.fromUuid(UUID.randomUUID()),
              existingOnly = false
            )
          )
        )
        val firstCeremony  = accepted(await(first.beginReplacement(firstView, "replace-factor")))
        val secondCeremony = accepted(await(second.beginReplacement(secondView, "replace-factor")))
        armed.set(true)
        val results = await(
          Future.sequence(
            List(
              db.replaceWith(first, firstCeremony, replacementInvocation(firstView), identity.sessionId.get),
              db.replaceWith(second, secondCeremony, replacementInvocation(secondView), identity.sessionId.get)
            )
          )
        )
        admissions.get() shouldBe 2
        results.count(_.isRight) shouldBe 1
        results.count(_.isLeft) shouldBe 1
        db.replacementCallbacks.get() shouldBe 1
        db.committedSessions shouldBe 2
        db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 1
        await(first.authorize(firstView)).isLeft shouldBe true
        await(second.authorize(secondView)).isLeft shouldBe true
        val (winner, handoff, loser, failedCeremony) = results match {
          case List(Right(receipt), Left(_)) => (first, receipt, second, secondCeremony)
          case List(Left(_), Right(receipt)) => (second, receipt, first, firstCeremony)
          case _                             => fail("Exactly one replacement must commit")
        }
        accepted(
          await(
            loser.recoverReplacement(
              new BrowserReplacementRequest(binding, Some(oldToken.value), Some(failedCeremony.id))
            )
          )
        ) shouldBe BrowserReplacementRecovery.NotCommitted
        val token = await(winner.deliver(handoff.completionId, binding)).get
        accepted(
          await(winner.capture(binding, Some(token.value), activate = true))
        ).sessionId should not be identity.sessionId
        db.replacementCallbacks.get() shouldBe 1
      } finally { release.trySuccess(()); await(first.close()); await(second.close()) }
    }

  it should "upgrade populated pre-replacement storage without changing ordinary ciphertext or completion behavior" in Using
    .resource(new Fixture(isolatedSchema = true)) { db =>
      val provider = db.persistence
      val view     = open(provider)
      val ceremony = accepted(await(provider.begin(view, "login")))
      val receipt  = accepted(await(db.prepareWith(provider, ceremony)))
      Using.resource(db.source.getConnection) { connection =>
        def ciphertext(): Vector[Byte] = Using.resource(
          connection.prepareStatement(
            "SELECT payload FROM spoonbill_auth_material WHERE realm=? AND namespace='session' AND attempt=?"
          )
        ) { query =>
          query.setString(1, db.realm); query.setObject(2, receipt.completionId)
          Using.resource(query.executeQuery()) { rows =>
            rows.next() shouldBe true
            rows.getBytes(1).toVector
          }
        }
        val before = ciphertext()
        Using.resource(connection.createStatement())(_.executeUpdate("DROP TABLE spoonbill_browser_replacement"))
        applyMigrations(connection, "V5__authenticated_session_replacement.sql")
        ciphertext() shouldBe before
        assertSuccessorIndex(frameworkSchema(connection))
      }
      val token = await(provider.deliver(receipt.completionId, binding)).get
      accepted(await(provider.capture(binding, Some(token.value), activate = true))).principal
        .map(_.subject) shouldBe Some("subject")
      db.committedSessions shouldBe 1
    }

  it should "install frozen V4 through V7 with the same schema as runtime initialization" in Using
    .resource(new Fixture(isolatedSchema = true)) { db =>
      Using.resource(db.source.getConnection) { connection =>
        val runtimeSchema = connection.getSchema
        val expected      = frameworkSchema(connection)
        val freshSchema   = "migration_" + UUID.randomUUID().toString.replace("-", "")
        Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA $freshSchema"))
        try {
          connection.setSchema(freshSchema)
          applyMigrations(connection, "V4__browser_auth.sql", "V5__authenticated_session_replacement.sql", "V6__browser_retention.sql", "V7__snapshot_references.sql")
          val installed = frameworkSchema(connection)
          assertSuccessorIndex(installed)
          installed shouldBe expected
        } finally {
          connection.setSchema(runtimeSchema)
          Using.resource(connection.createStatement())(_.executeUpdate(s"DROP SCHEMA $freshSchema CASCADE"))
        }
      }
    }

  it should "preserve a populated V6 inline snapshot through V7 and fence its next inline revision" in Using
    .resource(new Fixture(isolatedSchema = true)) { db =>
      Using.resource(db.source.getConnection) { connection =>
        val runtimeSchema = connection.getSchema
        val freshSchema   = "migration_" + UUID.randomUUID().toString.replace("-", "")
        Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA $freshSchema"))
        try {
          connection.setSchema(freshSchema)
          applyMigrations(connection, "V4__browser_auth.sql", "V5__authenticated_session_replacement.sql", "V6__browser_retention.sql")
          val namespace   = "session"
          val viewId      = "legacy-inline-view"
          val bindingHash = accepted(Digest256.fromBytes(Array.fill[Byte](32)(7)))
          val ownerId     = UUID.randomUUID()
          val ownerEpoch  = accepted(ViewOwnershipEpoch.fromLong(2))
          val revision    = accepted(ViewRevision.fromLong(7))
          val identity = SnapshotIdentity(
            accepted(SnapshotKeys.Subject.parse("legacy-subject")),
            accepted(SnapshotKeys.Scope.parse("legacy-scope")),
            UUID.randomUUID(),
            accepted(SecurityGeneration.fromLong(3)),
            accepted(SlotGeneration.fromLong(4))
          )
          val format = new SnapshotFormat[String](accepted(SchemaId.parse("legacy-draft")), accepted(SchemaVersion.fromInt(1)))
          val payload = accepted(format.write("saved before V7").flatMap(SnapshotBinaryCodec.encode(_, format.limits)))
          def insert(sql: String)(bind: java.sql.PreparedStatement => Unit): Unit =
            Using.resource(connection.prepareStatement(sql)) { statement =>
              bind(statement)
              statement.executeUpdate() shouldBe 1
            }
          insert("""INSERT INTO spoonbill_browser_slot
            (realm,cookie_namespace,binding_hash,generation,current_session_id) VALUES (?,?,?,?,?)""") { statement =>
            statement.setString(1, db.realm); statement.setString(2, namespace); statement.setBytes(3, bindingHash.bytes)
            statement.setLong(4, identity.slotGeneration.toLong); statement.setObject(5, identity.sessionId)
          }
          insert("""INSERT INTO spoonbill_browser_view
            (realm,cookie_namespace,view_id,binding_hash,owner_id,epoch,snapshot_revision) VALUES (?,?,?,?,?,?,?)""") { statement =>
            statement.setString(1, db.realm); statement.setString(2, namespace); statement.setString(3, viewId)
            statement.setBytes(4, bindingHash.bytes); statement.setObject(5, ownerId)
            statement.setLong(6, ownerEpoch.toLong); statement.setLong(7, revision.toLong)
          }
          insert("""INSERT INTO spoonbill_view_snapshot
            (realm,cookie_namespace,view_id,revision,subject_key,scope_key,session_id,
             security_generation,slot_generation,schema_id,schema_version,payload) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""") { statement =>
            statement.setString(1, db.realm); statement.setString(2, namespace); statement.setString(3, viewId)
            statement.setLong(4, revision.toLong); statement.setString(5, identity.subject.value)
            statement.setString(6, identity.scope.value); statement.setObject(7, identity.sessionId)
            statement.setLong(8, identity.securityGeneration.toLong); statement.setLong(9, identity.slotGeneration.toLong)
            statement.setString(10, format.schemaId.value); statement.setInt(11, format.version.value); statement.setBytes(12, payload)
          }
          // JSON includes every legacy column, including bytea, identity and activity timestamps.
          def legacyRows(): Vector[String] =
            Vector("spoonbill_browser_slot", "spoonbill_browser_view", "spoonbill_view_snapshot").map { table =>
              Using.resource(connection.createStatement()) { statement =>
                Using.resource(statement.executeQuery(s"SELECT (to_jsonb(legacy) - 'payload_operation')::text FROM $table legacy")) { rows =>
                  rows.next() shouldBe true
                  val result = rows.getString(1)
                  rows.next() shouldBe false
                  result
                }
              }
            }
          val before = legacyRows()
          applyMigrations(connection, "V7__snapshot_references.sql")
          legacyRows() shouldBe before
          def assertInline(): Unit = Using.resource(connection.createStatement()) { statement =>
            Using.resource(statement.executeQuery("SELECT payload_operation IS NULL FROM spoonbill_view_snapshot")) { rows =>
              rows.next() shouldBe true
              rows.getBoolean(1) shouldBe true
              rows.next() shouldBe false
            }
          }
          assertInline()
          val data = new JdbcViewSnapshotData(db.realm, namespace, viewId, bindingHash, ownerId, ownerEpoch, identity, format)
          data.revision(connection) shouldBe Right(revision)
          data.load(connection, revision) shouldBe Right(SnapshotLoad.Restored(revision, "saved before V7"))
          connection.setAutoCommit(false)
          try {
            val next = accepted(data.write(connection, accepted(data.revision(connection)), revision,
              accepted(data.encode("saved after V7")), resetting = false))
            next.toLong shouldBe 8L
            connection.commit()
            data.revision(connection) shouldBe Right(next)
            data.load(connection, next) shouldBe Right(SnapshotLoad.Restored(next, "saved after V7"))
            data.write(connection, accepted(data.revision(connection)), revision,
              accepted(data.encode("stale overwrite")), resetting = false) shouldBe Left(ViewSnapshotError.RevisionConflict)
            data.load(connection, next) shouldBe Right(SnapshotLoad.Restored(next, "saved after V7"))
            assertInline()
          } finally {
            connection.rollback()
            connection.setAutoCommit(true)
          }
        } finally {
          connection.setSchema(runtimeSchema)
          Using.resource(connection.createStatement())(_.executeUpdate(s"DROP SCHEMA $freshSchema CASCADE"))
        }
      }
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

  "Local ownership reconciliation" should "recover bookkeeping capacity even when a peer wins every full sweep" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val limits = BrowserPersistenceLimits(retainedViews = 1, pendingJobs = 1,
      retention = BrowserRetentionPolicy(workIntervalSeconds = 1))
    val provider = db.withLimits(limits)
    val peer = db.withLimits(limits)
    try {
      val identity = accepted(await(provider.capture(binding, None, activate = false)))
      accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)))
      for (_ <- 1 to 8) {
        db.clock.set(db.clock.get().plusSeconds(1))
        await(peer.maintenance())
        accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)))
        provider.ownedViewCount should be <= (limits.retainedViews + limits.pendingJobs)
        db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
      }
      await(provider.close())
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
    } finally { await(provider.close()); await(peer.close()) }
  }

  it should "recover after lost claim acknowledgements without depending on winning a peer's sweep reservation" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val limits = BrowserPersistenceLimits(retainedViews = 1, pendingJobs = 1,
      retention = BrowserRetentionPolicy(workIntervalSeconds = 1))
    val loseClaimAcknowledgement = new AtomicBoolean(true)
    val outer = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact(body).map {
        case Right(_: BrowserView[?]) if loseClaimAcknowledgement.get() => Left(TransactionFailure.CommitUnknown)
        case result => result
      }
    }
    val provider = db.withRunner(runner, limits)
    val peer = db.withLimits(limits)
    try {
      val identity = accepted(await(provider.capture(binding, None, activate = false)))
      for (_ <- 1 to 2) {
        db.clock.set(db.clock.get().plusSeconds(1))
        await(peer.maintenance())
        await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)) shouldBe
          Left(BrowserFailure.Settlement(TransactionFailure.CommitUnknown))
      }
      provider.ownedViewCount shouldBe 2
      loseClaimAcknowledgement.set(false)
      for (_ <- 1 to 5) {
        db.clock.set(db.clock.get().plusSeconds(1))
        await(peer.maintenance())
        accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)))
        provider.ownedViewCount should be <= 2
      }
      db.retained("spoonbill_browser_view") shouldBe 1
    } finally { await(provider.close()); await(peer.close()) }
  }

  it should "reconcile on local maintenance without forgetting a still-current uncertain owner" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val limits = BrowserPersistenceLimits(retainedViews = 1, pendingJobs = 1,
      retention = BrowserRetentionPolicy(workIntervalSeconds = 1))
    val outer = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact(body).map {
        case Right(_: BrowserView[?]) => Left(TransactionFailure.CommitUnknown)
        case result => result
      }
    }
    val provider = db.withRunner(runner, limits)
    val peer = db.withLimits(limits)
    try {
      val identity = accepted(await(provider.capture(binding, None, activate = false)))
      (1 to 2).foreach { _ =>
        await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)) shouldBe
          Left(BrowserFailure.Settlement(TransactionFailure.CommitUnknown))
      }
      provider.ownedViewCount shouldBe 2
      db.clock.set(db.clock.get().plusSeconds(1))
      await(peer.maintenance())
      db.statements.clear()
      db.recordSql.set(true)
      await(provider.maintenance())
      db.recordSql.set(false)
      db.statements.asScala.count(_.contains("owner_id=ANY(?)")) shouldBe 1
      db.statements.asScala.count(_.startsWith("UPDATE spoonbill_browser_slot b SET generation=b.generation+1")) shouldBe 0
      provider.ownedViewCount shouldBe 1
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
      await(provider.close())
      provider.ownedViewCount shouldBe 0
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
    } finally { db.recordSql.set(false); await(provider.close()); await(peer.close()) }
  }

  it should "retain shutdown ownership when capacity reconciliation precedes an expiry that rolls back" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val limits = BrowserPersistenceLimits(retainedViews = 1, pendingJobs = 1,
      retention = BrowserRetentionPolicy(idleBindingSeconds = 1, idleViewSeconds = 1, workIntervalSeconds = 1))
    val provider = db.withLimits(limits)
    try {
      val identity = accepted(await(provider.capture(binding, None, activate = false)))
      (1 to 2).foreach { _ =>
        accepted(await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)))
      }
      provider.ownedViewCount shouldBe 2
      db.clock.set(db.clock.get().plusSeconds(2))
      // This transaction stages expiry/deletion, then its stale identity check
      // rejects. Reconciliation must have retained the last committed owner.
      await(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)).isLeft shouldBe true
      provider.ownedViewCount shouldBe 1
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
      await(provider.close())
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 0
    } finally await(provider.close())
  }

  "Shared maintenance cadence" should "skip sampled capacity before the realm lock across peers, restart, and backward clocks" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val first = db.fresh()
    val peer = db.fresh()
    try {
      accepted(await(first.capacity)).isDefined shouldBe true
      accepted(await(peer.capacity)) shouldBe None
      accepted(await(first.capture(binding, None, activate = false)))
      accepted(await(peer.usage())).retainedBindings shouldBe 1L
      val restarted = db.fresh()
      try Using.resource(db.source.getConnection) { held =>
        held.setAutoCommit(false)
        Using.resource(held.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) { query =>
          query.setString(1, s"spoonbill-auth:${db.realm.length}:${db.realm}session")
          Using.resource(query.executeQuery())(_.next())
        }
        try {
          db.clock.set(BrowserPersistenceConformance.start.minusSeconds(60))
          accepted(Await.result(restarted.capacity, 2.seconds)) shouldBe None
        } finally held.rollback()
      } finally await(restarted.close())
      db.clock.set(BrowserPersistenceConformance.start.plusSeconds(30))
      accepted(await(peer.capacity)).get.retainedBindings shouldBe 1L
      accepted(await(first.capacity)) shouldBe None
    } finally { await(first.close()); await(peer.close()) }
  }

  it should "retain a sweep reservation after denied admission and recover pressure capacity without waiting for the normal interval" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val provider = db.withLimits(BrowserPersistenceLimits(retainedBindings = 1, anonymousBindings = 1))
    try {
      val old = accepted(await(provider.capture(binding, None, activate = false)))
      db.clock.set(BrowserPersistenceConformance.start.plusSeconds(31))
      db.recordSql.set(true)
      (1 to 20).foreach { _ =>
        await(provider.capture("z" * 43, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
      }
      db.recordSql.set(false)
      db.statements.asScala.count(_.startsWith("UPDATE spoonbill_browser_slot b SET generation=b.generation+1")) shouldBe 1
      db.retained("spoonbill_browser_slot") shouldBe 1
      // Sweep just before eligibility, then hit pressure one second later.
      db.clock.set(BrowserPersistenceConformance.start.plusSeconds(599))
      await(provider.maintenance())
      db.clock.set(BrowserPersistenceConformance.start.plusSeconds(600))
      val fresh = accepted(await(provider.capture("z" * 43, None, activate = false)))
      fresh.generation should be > old.generation
      db.retained("spoonbill_browser_slot") shouldBe 1
      await(provider.claim(old, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)).isLeft shouldBe true
    } finally { db.recordSql.set(false); await(provider.close()) }
  }

  it should "bound full sweep and monitoring work for public GETs over a realistic retained working set" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val rows = 8192
    val limits = BrowserPersistenceLimits(anonymousBindings = 16384)
    val first = db.withLimits(limits)
    val second = db.withLimits(limits)
    def coordinator(provider: JdbcBrowserPersistence[Future, String]) = new BrowserAuthentication[Future, Int, String](
      provider, BrowserPresentation(() => 0, (_, _) => true, (state, _, _) => state),
      BrowserConfiguration(provider.scope, Set("https://app.example")), clock = () => db.clock.get())
    val browsers = Vector(coordinator(first), coordinator(second))
    val services = browsers.map(browser => spoonbillService(browser.configure(SpoonbillServiceConfig[Future, Int, Any](
      stateLoader = StateLoader.default(0), document = _ => Html(body(div("public")))))))
    def get(index: Int): Double = {
      val started = System.nanoTime()
      val response = await(services(index % services.size).http(Request(Request.Method.Get, PathAndQuery.Root,
        Nil, None, Stream.empty[Future, Bytes])))
      response.status.code shouldBe 200
      await(response.body.cancel())
      (System.nanoTime() - started).toDouble / 1000000.0
    }
    try {
      accepted(await(first.inspectPrincipal(binding, None)))
      Using.resource(db.source.getConnection) { connection =>
        def seed(statement: String, deadline: Instant): Unit = Using.resource(connection.prepareStatement(statement)) { query =>
          query.setString(1, db.realm); query.setTimestamp(2, Timestamp.from(deadline)); query.setInt(3, rows)
          query.executeUpdate() shouldBe rows
        }
        seed("""INSERT INTO spoonbill_browser_slot(realm,cookie_namespace,binding_hash,generation,last_seen)
          SELECT ?,'session',decode(repeat(md5(i::text),2),'hex'),0,? FROM generate_series(1,?) ids(i)""", db.clock.get())
        seed("""INSERT INTO spoonbill_browser_view(realm,cookie_namespace,view_id,binding_hash,owner_id,epoch,last_seen)
          SELECT ?,'session','seed-'||i,decode(repeat(md5(i::text),2),'hex'),NULL,1,? FROM generate_series(1,?) ids(i)""", db.clock.get())
        seed("""INSERT INTO spoonbill_auth_ceremony(realm,namespace,id,attempt,binding_hash,generation,purpose,phase,expires_at)
          SELECT ?,'session',md5('ceremony-'||i)::uuid,md5('attempt-'||i)::uuid,
          decode(repeat(md5(i::text),2),'hex'),0,'login','Begun',? FROM generate_series(1,?) ids(i)""", db.clock.get().plusSeconds(7200))
      }
      // Keep real local ownership so this also detects a reconciliation scan
      // accidentally added to every public bootstrap or shared sweep.
      open(first)
      first.ownedViewCount shouldBe 1
      db.clock.set(db.clock.get().plusSeconds(limits.retention.workIntervalSeconds))
      db.recordSql.set(true)
      val warm = Vector(get(0), get(1))
      db.statements.asScala.count(_.startsWith("UPDATE spoonbill_browser_slot b SET generation=b.generation+1")) shouldBe 1
      db.statements.asScala.count(_.startsWith("SELECT COALESCE(MAX(amount),0)")) shouldBe 2
      db.statements.asScala.count(_.contains("owner_id=ANY(?)")) shouldBe 0
      db.statements.clear()
      val elapsed = (0 until 100).map(get).sorted
      val statements = db.statements.asScala.toVector
      db.recordSql.set(false)
      statements.count(_.startsWith("UPDATE spoonbill_browser_slot b SET generation=b.generation+1")) shouldBe 0
      statements.count(_.startsWith("SELECT COALESCE(MAX(amount),0)")) shouldBe 0
      statements.count(_.contains("owner_id=ANY(?)")) shouldBe 0
      // Admission counts and authority reads remain on each request; unrelated
      // cleanup/monitoring queries must not grow with the retained row count.
      statements.size should be <= 8000
      accepted(await(first.usage())).retainedBindings shouldBe rows + 103L
      info(f"Public GET cadence fixture: peers=2 rowsPerTable=$rows requests=100 SQL=${statements.size} " +
        f"sweeps=0 aggregateScans=0 p50Ms=${elapsed(49)}%.2f p95Ms=${elapsed(94)}%.2f maxMs=${elapsed.last}%.2f " +
        f"initialSampleMs=${warm.head}%.2f; instrumented disposable PostgreSQL, not a production throughput claim")
      db.statements.clear()
      db.clock.set(db.clock.get().plusSeconds(limits.retention.workIntervalSeconds))
      db.recordSql.set(true)
      get(0)
      get(1)
      db.recordSql.set(false)
      db.statements.asScala.count(_.startsWith("UPDATE spoonbill_browser_slot b SET generation=b.generation+1")) shouldBe 1
      db.statements.asScala.count(_.startsWith("SELECT COALESCE(MAX(amount),0)")) shouldBe 2
      db.statements.asScala.count(_.contains("owner_id=ANY(?)")) shouldBe 0
      info("Next shared interval: two public GETs on different peers performed one full sweep and one capacity sample")
    } finally { db.recordSql.set(false); browsers.foreach(browser => await(browser.close())) }
  }

  "Replacement owner leases" should "reject completion after lease expiry without a maintenance sweep" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val provider = db.withLimits(BrowserPersistenceLimits(retention = BrowserRetentionPolicy(idleViewSeconds = 1)))
    try {
      val (view, _, _) = retentionLogin(db, provider, binding, qsid, 600)
      val ceremony = accepted(await(provider.beginReplacement(view, "replace")))
      val invocation = replacementInvocation(view)
      val receipt = accepted(await(db.replaceWith(provider, ceremony, invocation, view.identity.sessionId.get)))
      db.clock.set(db.clock.get().plusSeconds(2))
      await(provider.admitReplacementCompletion(receipt, invocation)).isLeft shouldBe true
      db.replacementCallbacks.get() shouldBe 1
      db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 1
    } finally await(provider.close())
  }

  it should "reject a delayed admitted replacement before invoking its host callback" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val pause = new AtomicBoolean()
    val admitted = Promise[Unit]()
    val release = Promise[Unit]()
    val outer = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = outer.transact(body).flatMap { result =>
        if (result == Right(()) && pause.compareAndSet(true, false)) {
          admitted.trySuccess(())
          release.future.map(_ => result)
        } else Future.successful(result)
      }
    }
    val provider = db.withRunner(runner, BrowserPersistenceLimits(retention = BrowserRetentionPolicy(idleViewSeconds = 1)))
    try {
      val (view, _, _) = retentionLogin(db, provider, binding, qsid, 600)
      val ceremony = accepted(await(provider.beginReplacement(view, "replace")))
      pause.set(true)
      val pending = db.replaceWith(provider, ceremony, replacementInvocation(view), view.identity.sessionId.get)
      await(admitted.future)
      db.clock.set(db.clock.get().plusSeconds(2))
      release.trySuccess(())
      await(pending) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
      db.replacementCallbacks.get() shouldBe 0
      db.committedSessions shouldBe 1
      db.retained("spoonbill_browser_replacement", "successor_session_id IS NOT NULL") shouldBe 0
    } finally { release.trySuccess(()); await(provider.close()) }
  }

  "JDBC admission lanes" should "preserve independent lane budgets and drain every lane before repeated close" in Using.resource(new Fixture) { db =>
    val pending = new ConcurrentLinkedQueue[() => Unit]()
    val runner = new TransactionExecutor[Future, Direct, Connection] {
      val program = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = {
        val result = Promise[Either[TransactionFailure, A]]()
        pending.add(() => { result.trySuccess(Left(TransactionFailure.RolledBack)); () })
        result.future
      }
    }
    val view = open(db.persistence)
    val provider = db.withRunner(runner, BrowserPersistenceLimits(pendingJobs = 1),
      concurrent = Some(JdbcConcurrentChecks(db.source, 1)))
    def releaseOne(): Unit = Option(pending.poll()).getOrElse(fail("No admitted transaction"))()
    try {
      val ordinary = provider.capture(binding, None, activate = false)
      val cleanup = provider.release(view)
      val firstCheck = provider.authorize(view)
      val secondCheck = provider.authorize(view)
      pending.size() shouldBe 4 // one ordinary, one cleanup, cap + maintenance slot for checks
      await(provider.capture(binding, None, activate = false)) shouldBe Left(BrowserFailure.Capacity)
      intercept[JdbcAuthException](await(provider.release(view)))
      await(provider.authorize(view)) shouldBe Left(BrowserFailure.Capacity)
      pending.size() shouldBe 4

      val closing = provider.close()
      val joining = provider.close()
      closing.isCompleted shouldBe false
      joining.isCompleted shouldBe false
      await(provider.capture(binding, None, activate = false)) shouldBe Left(BrowserFailure.Closed)
      await(provider.authorize(view)) shouldBe Left(BrowserFailure.Closed)
      await(provider.release(view)) // release after close remains an idempotent no-op
      pending.size() shouldBe 4

      releaseOne()
      await(ordinary).isLeft shouldBe true
      closing.isCompleted shouldBe false
      releaseOne()
      intercept[JdbcAuthException](await(cleanup))
      closing.isCompleted shouldBe false
      releaseOne()
      await(firstCheck).isLeft shouldBe true
      closing.isCompleted shouldBe false
      releaseOne()
      await(secondCheck).isLeft shouldBe true
      await(closing)
      await(joining)
      await(provider.close())
      await(provider.authorize(view)) shouldBe Left(BrowserFailure.Closed)
    } finally {
      while (!pending.isEmpty) releaseOne()
      await(provider.close())
    }
  }

  "Concurrent checks" should "be an explicit opt-in that refuses shared views" in Using.resource(new Fixture) { db =>
    db.persistence.checkConcurrency shouldBe None
    val provider = db.withConcurrentChecks(4)
    try provider.checkConcurrency shouldBe Some(BrowserCheckConcurrency(4))
    finally await(provider.close())
    intercept[IllegalArgumentException](db.withConcurrentChecks(4, BrowserOwnership.SharedViews))
  }

  it should "mark enrollment only after observing the retention row" in Using.resource(new Fixture) { db =>
    val provider = db.withConcurrentChecks(2)
    try {
      provider.isEnrolled shouldBe false
      // A rejected first transaction rolls its enrollment back and leaves the flag unset.
      await(provider.inspectPrincipal("!" * 43, None)).isLeft shouldBe true
      provider.isEnrolled shouldBe false
      // The first accepted transaction inserts the row; the flag is never set on insert.
      accepted(await(provider.inspectPrincipal(binding, None))) shouldBe None
      provider.isEnrolled shouldBe false
      accepted(await(provider.inspectPrincipal(binding, None))) shouldBe None
      provider.isEnrolled shouldBe true
    } finally await(provider.close())
  }

  it should "take the realm lock in shared mode for checks and exclusively for writers" in Using.resource(
    new Fixture
  ) { db =>
    val provider = db.withConcurrentChecks(2)
    try {
      val view = open(provider)
      provider.isEnrolled shouldBe true
      Using.resource(db.source.getConnection) { held =>
        def advisory(function: String): Unit =
          Using.resource(held.prepareStatement(s"SELECT $function(hashtextextended(?,0))")) { query =>
            query.setString(1, s"spoonbill-auth:${db.realm.length}:${db.realm}session")
            Using.resource(query.executeQuery())(_.next()); ()
          }
        advisory("pg_advisory_lock_shared")
        try {
          // A shared holder admits a check and blocks a writer until it unlocks.
          accepted(Await.result(provider.authorize(view), 3.seconds))
          val writer = provider.capture("z" * 43, None, activate = false)
          intercept[TimeoutException](Await.result(writer, 500.millis))
          advisory("pg_advisory_unlock_shared")
          accepted(await(writer))
        } finally advisory("pg_advisory_unlock_shared")
      }
    } finally await(provider.close())
  }

  it should "never move a lease backward" in Using.resource(new Fixture) { db =>
    val provider = db.withConcurrentChecks(2)
    try {
      val view   = open(provider)
      val future = BrowserPersistenceConformance.start.plusSeconds(3600)
      Using.resource(db.source.getConnection) { connection =>
        Using.resource(
          connection.prepareStatement("UPDATE spoonbill_browser_view SET last_seen=? WHERE realm=? AND view_id=?")
        ) { update =>
          update.setTimestamp(1, Timestamp.from(future)); update.setString(2, db.realm)
          update.setString(3, BrowserView.key(qsid))
          update.executeUpdate() shouldBe 1
        }
      }
      accepted(await(provider.authorize(view)))
      Using.resource(db.source.getConnection) { connection =>
        Using.resource(connection.prepareStatement("SELECT last_seen FROM spoonbill_browser_view WHERE realm=? AND view_id=?")) {
          query =>
            query.setString(1, db.realm); query.setString(2, BrowserView.key(qsid))
            Using.resource(query.executeQuery()) { rows =>
              rows.next() shouldBe true
              rows.getTimestamp(1).toInstant shouldBe future
            }
        }
      }
    } finally await(provider.close())
  }

  it should "retry a check once after a deadlock abort" in Using.resource(new Fixture) { db =>
    val provider = db.withConcurrentChecks(2)
    try {
      val view = open(provider)
      provider.isEnrolled shouldBe true
      val before = db.domainTransactions.get()
      Using.resource(db.source.getConnection) { held =>
        held.setAutoCommit(false)
        // Lock the view row first, the reverse of a check's slot-then-view order.
        Using.resource(held.prepareStatement(
          "SELECT 1 FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=? AND view_id=? FOR UPDATE"
        )) { query =>
          query.setString(1, db.realm); query.setString(2, "session"); query.setString(3, BrowserView.key(qsid))
          Using.resource(query.executeQuery())(_.next()) shouldBe true
        }
        val checked = provider.authorize(view)
        // Wait until the check holds the slot row and waits for the view row.
        def waiting: Boolean =
          Using.resource(held.prepareStatement("SELECT count(*) FROM pg_locks WHERE NOT granted")) { query =>
            Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) > 0 }
          }
        val deadline = System.nanoTime() + 5000000000L
        while (!waiting && System.nanoTime() < deadline) Thread.sleep(20)
        waiting shouldBe true
        // Waiting for the slot row now closes the cycle; PostgreSQL aborts the check, which retries.
        Using.resource(held.prepareStatement(
          "SELECT 1 FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=? FOR UPDATE"
        )) { query =>
          query.setString(1, db.realm); query.setString(2, "session")
          query.setBytes(3, MessageDigest.getInstance("SHA-256").digest(binding.getBytes("UTF-8")))
          Using.resource(query.executeQuery())(_.next()) shouldBe true
        }
        held.rollback()
        accepted(await(checked))
      }
      db.domainTransactions.get() - before shouldBe 2
    } finally await(provider.close())
  }

  it should "renew the clock lease in the background while checks are active and stop on close" in Using.resource(
    new Fixture
  ) { db =>
    val provider = db.withConcurrentChecks(2)
    provider.renewingInBackground shouldBe true
    try {
      val view = open(provider)
      accepted(await(provider.authorize(view)))
      // Observations set demand; the task then commits a renewal without any further call.
      val before   = db.openedConnections.get()
      val deadline = System.nanoTime() + 3000000000L
      while (db.openedConnections.get() == before && System.nanoTime() < deadline) Thread.sleep(50)
      db.openedConnections.get() should be > before
    } finally await(provider.close())
    provider.renewingInBackground shouldBe false
  }

  /** What one operation adds: held host locks, host resolutions, clock commits and provider transactions. */
  private def heldCounts(db: Fixture)(operation: => Unit): (Int, Int, Int, Int) = {
    val before = (db.hostHolds.get(), db.hostResolutions.get(), db.clockCommits.get(), db.domainTransactions.get())
    operation
    (
      db.hostHolds.get() - before._1,
      db.hostResolutions.get() - before._2,
      db.clockCommits.get() - before._3,
      db.domainTransactions.get() - before._4
    )
  }
  private def hostRow(db: Fixture, session: UUID): PreparedStatement => Unit = query => {
    query.setString(1, db.realm); query.setObject(2, session)
  }

  "Held shared-view checks" should "resolve the host once and commit one clock checkpoint per authenticated check" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val (view, _) = signedIn(db, db.persistence)
      heldCounts(db)(accepted(await(db.persistence.authorize(view)))) shouldBe (1, 0, 1, 1)
      heldCounts(db)(accepted(await(db.persistence.admitOutputIf(view, "frame")(_ => true)))) shouldBe (1, 0, 1, 1)
      heldCounts(db)(accepted(await(db.persistence.transact(view)(_ => ())))) shouldBe (1, 0, 1, 1)
      db.hostAccepted.get() shouldBe 3
    }

  it should "keep two resolutions and the ordinary clock for a legacy host, an exclusive scope and an anonymous view" in {
    Using.resource(new Fixture(exclusive = false)) { db =>
      val (view, _) = signedIn(db, db.persistence)
      heldCounts(db)(accepted(await(db.persistence.authorize(view)))) shouldBe (0, 2, 5, 1)
    }
    Using.resource(new Fixture(held = true)) { db =>
      val (view, _)                          = signedIn(db, db.persistence)
      val (holds, resolutions, _, checks) = heldCounts(db)(accepted(await(db.persistence.authorize(view))))
      (holds, resolutions, checks) shouldBe (0, 2, 1)
    }
    Using.resource(new Fixture(exclusive = false, held = true)) { db =>
      val anonymous                    = open(db.persistence)
      val (holds, resolutions, _, _) = heldCounts(db)(accepted(await(db.persistence.authorize(anonymous))))
      (holds, resolutions) shouldBe (0, 0)
    }
  }

  it should "judge session expiry, the host cutoff and the idle view cutoff at the checkpoint, with equality failing" in {
    val start = BrowserPersistenceConformance.start
    Using.resource(new Fixture(exclusive = false, held = true, hostExpiryNanos = 1)) { db =>
      val (view, _) = signedIn(db, db.persistence)
      // The host's absolute expiry is one nanosecond after the framework session expiry.
      val expiry = view.identity.principal.get.expiresAt.minusNanos(1)
      db.clock.set(expiry.minusNanos(1)); accepted(await(db.persistence.authorize(view)))
      db.clock.set(expiry); await(db.persistence.authorize(view)) shouldBe Left(BrowserFailure.Expired)
      var ran = false
      await(db.persistence.transact(view)(_ => ran = true)).isLeft shouldBe true
      ran shouldBe false
    }
    Using.resource(new Fixture(exclusive = false, held = true)) { db =>
      val (view, _) = signedIn(db, db.persistence)
      val idle      = start.plusSeconds(100)
      db.setIdle(view.identity.sessionId.get, idle)
      db.clock.set(idle.minusNanos(1)); accepted(await(db.persistence.authorize(view)))
      db.clock.set(idle); await(db.persistence.authorize(view)) shouldBe Left(BrowserFailure.Denied)
    }
    Using.resource(new Fixture(exclusive = false, held = true)) { db =>
      val provider = db.withLimits(BrowserPersistenceLimits(retention = BrowserRetentionPolicy(idleViewSeconds = 60)))
      try {
        val (view, _) = signedIn(db, provider)
        // Timestamp columns keep microseconds, so the renewal instant is whole microseconds.
        val renewed = start.plusSeconds(60).minusNanos(1000)
        db.clock.set(renewed); accepted(await(provider.authorize(view)))
        db.clock.set(renewed.plusSeconds(60)); await(provider.authorize(view)) shouldBe Left(BrowserFailure.Expired)
      } finally await(provider.close())
    }
  }

  it should "extend idle expiry only from an accepted check, so a due refresh cannot revive an expired session" in Using
    .resource(new Fixture(exclusive = false, held = true, refreshSeconds = 30)) { db =>
      val start     = BrowserPersistenceConformance.start
      val (view, _) = signedIn(db, db.persistence)
      val session   = view.identity.sessionId.get
      db.setIdle(session, start.plusSeconds(10))
      db.clock.set(start.plusSeconds(5)); accepted(await(db.persistence.authorize(view)))
      db.idleOf(session) shouldBe start.plusSeconds(35)
      db.clock.set(start.plusSeconds(35)); await(db.persistence.authorize(view)) shouldBe Left(BrowserFailure.Denied)
      db.idleOf(session) shouldBe start.plusSeconds(35)
      db.hostAccepted.get() shouldBe 1
    }

  it should "sample the decision instant after waiting for the host row, the slot row and the clock row" in {
    for (table <- Vector("spoonbill_auth_test_host", "spoonbill_browser_slot", "spoonbill_auth_clock"))
      withClue(s"waiting for $table: ") {
        Using.resource(new Fixture(exclusive = false, held = true)) { db =>
          val (view, _) = signedIn(db, db.persistence)
          val lock = table match {
            case "spoonbill_auth_test_host" =>
              new db.HeldLock(
                "SELECT 1 FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE",
                hostRow(db, view.identity.sessionId.get)
              )
            case "spoonbill_browser_slot" =>
              new db.HeldLock(
                "SELECT 1 FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace='session' FOR UPDATE",
                _.setString(1, db.realm)
              )
            case _ =>
              new db.HeldLock(
                "SELECT 1 FROM spoonbill_auth_clock WHERE realm=? AND namespace='session' FOR UPDATE",
                _.setString(1, db.realm)
              )
          }
          val checked = lock.holding {
            val checked = db.persistence.authorize(view)
            db.awaitLockWait(table)
            // The session deadline passes while the check waits.
            db.clock.set(view.identity.principal.get.expiresAt)
            checked
          }
          await(checked) shouldBe Left(BrowserFailure.Expired)
        }
      }
  }

  it should "observe a later checkpoint that a peer commits while the check waits, also through the capacity path" in {
    for (peer <- Vector("clock", "capacity")) withClue(s"peer $peer: ") {
      Using.resource(new Fixture(exclusive = false, held = true)) { db =>
        val (view, _)         = signedIn(db, db.persistence)
        val ahead             = view.identity.principal.get.expiresAt.plusSeconds(1)
        val (entered, resume) = (new CountDownLatch(1), new CountDownLatch(1))
        db.holdPause.set(Some(entered -> resume))
        val checked = db.persistence.authorize(view)
        entered.await(10, TimeUnit.SECONDS) shouldBe true
        // A node whose wall clock is past the deadline commits a checkpoint meanwhile.
        val node = Option.when(peer == "capacity")(db.withClock(() => ahead))
        val usage = node match {
          case Some(provider) => Some(provider.capacity)
          case None           => new JdbcBrowserClock(db.source, db.realm, "session", () => ahead).now(); None
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (db.clockFloor.isBefore(ahead) && System.nanoTime() < deadline) Thread.sleep(5)
        db.clockFloor shouldBe ahead
        resume.countDown()
        await(checked) shouldBe Left(BrowserFailure.Expired)
        usage.foreach(await(_))
        node.foreach(provider => await(provider.close()))
      }
    }
  }

  it should "keep every committed decision instant after a rollback, a backward clock and a restart" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val start         = BrowserPersistenceConformance.start
    val (view, token) = signedIn(db, db.persistence)
    val expiry        = view.identity.principal.get.expiresAt
    // An accepted check whose transaction then fails keeps its committed checkpoint.
    db.clock.set(start.plusSeconds(1)); db.failAccepted.set(true)
    await(db.persistence.authorize(view)).isLeft shouldBe true
    db.clockFloor shouldBe start.plusSeconds(1)
    // A denial at the deadline holds after the wall clock moves back, and after a restart.
    db.clock.set(expiry)
    await(db.persistence.authorize(view)) shouldBe Left(BrowserFailure.Expired)
    db.clockFloor shouldBe expiry
    db.clock.set(start)
    await(db.persistence.authorize(view)) shouldBe Left(BrowserFailure.Expired)
    val restarted = db.fresh()
    try await(restarted.capture(binding, Some(token.value), activate = false)) shouldBe Left(BrowserFailure.Expired)
    finally await(restarted.close())
  }

  it should "fail closed on an uncertain clock commit, before the accepted hook and the protected body" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (view, _)      = signedIn(db, db.persistence)
    val acceptedBefore = db.hostAccepted.get()
    db.loseClockAcknowledgement.set(true)
    var ran = false
    await(db.persistence.transact(view)(_ => ran = true)).isLeft shouldBe true
    ran shouldBe false
    db.hostAccepted.get() shouldBe acceptedBefore
    accepted(await(db.persistence.authorize(view)))
  }

  it should "deny a waiting check once a revocation, logout, takeover or replacement commits" in {
    for (change <- Vector("revocation", "logout", "takeover", "replacement")) withClue(s"$change: ") {
      Using.resource(new Fixture(exclusive = false, held = true)) { db =>
        val (view, token) = signedIn(db, db.persistence)
        val session       = view.identity.sessionId.get
        if (change == "revocation") {
          // The host revokes the session while the check waits for the host row.
          val lock    = new db.HeldLock("SELECT 1 FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE", hostRow(db, session))
          val checked = db.persistence.authorize(view)
          lock.holding {
            db.awaitLockWait("spoonbill_auth_test_host")
            lock.update("UPDATE spoonbill_auth_test_host SET enabled=FALSE WHERE realm=? AND id=?")(hostRow(db, session))
            lock.release(commit = true)
          }
          await(checked) shouldBe Left(BrowserFailure.Denied)
        } else {
          // The writer holds the realm lock with its changes staged while the check waits for that lock.
          val pausing = new db.PausingRunner
          val writer  = db.withRunner(pausing.runner)
          try {
            val (entered, resume, written) = change match {
              case "logout" =>
                val (entered, resume) = pausing.arm()
                (entered, resume, writer.logout(binding, Some(token.value)).map(_.isRight))
              case "takeover" =>
                val identity          = accepted(await(writer.capture(binding, Some(token.value), activate = false)))
                // A shared claim reads its admission first and writes in its second transaction.
                val (entered, resume) = pausing.arm(skip = 1)
                val claimed           = writer.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)
                (entered, resume, claimed.map(_.isRight))
              case _ =>
                val ceremony          = accepted(await(db.persistence.beginReplacement(view, "replace-factor")))
                val (entered, resume) = (new CountDownLatch(1), new CountDownLatch(1))
                val replaced = db.replaceWith(
                  db.persistence, ceremony, replacementInvocation(view), session, version = 2L,
                  afterHost = () => { entered.countDown(); resume.await(15, TimeUnit.SECONDS); () }
                )
                (entered, resume, replaced.map(_.isRight))
            }
            entered.await(10, TimeUnit.SECONDS) shouldBe true
            val checked = db.persistence.authorize(view)
            db.awaitLockWait("pg_advisory_xact_lock")
            resume.countDown()
            await(written) shouldBe true
            await(checked) shouldBe Left(BrowserFailure.Denied)
          } finally await(writer.close())
        }
      }
    }
  }

  it should "keep resolve and the ordinary clock for ceremony and replacement flows of an authenticated view" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val (view, _) = signedIn(db, db.persistence)
      // Each of these runs the two-sample path: resolve twice, no held host lock and no accepted hook.
      def legacy(name: String)(operation: => Any): Unit = withClue(s"$name: ") {
        val (holds, resolutions, _, _) = heldCounts(db)(operation)
        holds shouldBe 0
        resolutions should be >= 2
      }
      legacy("pending")(accepted(await(db.persistence.pending(view))))
      legacy("begin")(accepted(await(db.persistence.begin(view, "login"))))
      var ceremony = Option.empty[BrowserReplacementCeremony]
      legacy("beginReplacement") { ceremony = Some(accepted(await(db.persistence.beginReplacement(view, "replace-factor")))) }
      legacy("bindReplacementChallenge")(
        accepted(await(db.persistence.bindReplacementChallenge(ceremony.get, BrowserChallenge("subject", 1L, 1L, "factor"))))
      )
      legacy("withSuccessorCompletion")(await(db.persistence.withSuccessorCompletion(view)((_, completion) => completion)))
      db.hostAccepted.get() shouldBe 0
      // The checks and protected host writes take the held path.
      heldCounts(db)(accepted(await(db.persistence.transact(view)(_ => ())))) shouldBe (1, 0, 1, 1)
      db.hostAccepted.get() shouldBe 1
    }

  it should "refuse a stable host denial without waiting for the slot and view locks" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (view, _) = signedIn(db, db.persistence)
    db.disable()
    val slot = new db.HeldLock(
      "SELECT 1 FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace='session' FOR UPDATE",
      _.setString(1, db.realm)
    )
    try Await.result(db.persistence.authorize(view), 5.seconds) shouldBe Left(BrowserFailure.Denied)
    finally slot.release()
    // Session expiry still outranks the host's denial.
    db.clock.set(view.identity.principal.get.expiresAt)
    await(db.persistence.authorize(view)) shouldBe Left(BrowserFailure.Expired)
  }

  private val draftFormat =
    new SnapshotFormat[String](accepted(SchemaId.parse("guarded-draft")), accepted(SchemaVersion.fromInt(1)))
  private case class Page(draft: String, other: String)
  private def ifChanged(provider: JdbcBrowserPersistence[Future, String], view: BrowserView[String]): ViewSnapshotSession[Future, Page] =
    provider.projectedSnapshotsIfChanged[Page, String](draftFormat, _.draft, (fresh, draft) => fresh.copy(draft = draft))(view).get
  private def paired(session: ViewSnapshotSession[Future, Page]): ObservedSnapshotWrites[Future, Page] = session match {
    case observed: ObservedSnapshotWrites[Future, Page] @unchecked => observed
    case _                                                         => fail("The factory's session cannot skip an unchanged projection")
  }
  private def observation(provider: JdbcBrowserPersistence[Future, String], view: BrowserView[String]): BrowserWriteObservation[String] =
    accepted(await(provider.guardedWriteObservations.get.observeWrite(view)))
  /** One queued top-level write as the coordinator pairs it: an observation, then the session's write. */
  private def guardedWrite(
    provider: JdbcBrowserPersistence[Future, String],
    view: BrowserView[String],
    session: ViewSnapshotSession[Future, Page],
    candidate: Page
  ): Unit = await(paired(session).commitObserved(observation(provider, view).receipt, candidate))
  private def snapshotError(operation: => Any): ViewSnapshotError = intercept[ViewSnapshotException](operation).error

  "Guarded inline snapshots" should "save nothing for an unchanged projection and once for a changed one, which a reconnect restores" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val (view, token) = signedIn(db, db.persistence)
      val session       = ifChanged(db.persistence, view)
      await(session.initialize(Page("", "a"))) shouldBe Page("", "a")
      val seeded = db.viewRevision()
      val before = db.domainTransactions.get()
      guardedWrite(db.persistence, view, session, Page("", "b"))
      (db.domainTransactions.get() - before, db.viewRevision()) shouldBe (1, seeded)
      guardedWrite(db.persistence, view, session, Page("typed", "b"))
      (db.domainTransactions.get() - before, db.viewRevision()) shouldBe (3, seeded + 1)
      guardedWrite(db.persistence, view, session, Page("typed", "c"))
      (db.domainTransactions.get() - before, db.viewRevision()) shouldBe (4, seeded + 1)
      val second = db.fresh()
      try {
        val captured = accepted(await(second.capture(binding, Some(token.value), activate = true)))
        val next     = accepted(await(second.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
        await(ifChanged(second, next).initialize(Page("", "node B"))) shouldBe Page("typed", "node B")
      } finally await(second.close())
    }

  it should "return the locked view's revision from the two-sample path too" in Using.resource(new Fixture(exclusive = false)) { db =>
    val (view, _) = signedIn(db, db.persistence)
    val session   = ifChanged(db.persistence, view)
    await(session.initialize(Page("", "a")))
    val seeded = db.viewRevision()
    guardedWrite(db.persistence, view, session, Page("", "b"))
    guardedWrite(db.persistence, view, session, Page("typed", "b"))
    db.viewRevision() shouldBe seeded + 1
  }

  it should "fence a takeover before the observation and the save after it, and keep public commits fenced" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val (view, token) = signedIn(db, db.persistence)
      val session       = ifChanged(db.persistence, view)
      await(session.initialize(Page("", "a")))
      val unchanged = observation(db.persistence, view)
      val changed   = observation(db.persistence, view)
      val takeover  = db.fresh()
      try {
        val captured = accepted(await(takeover.capture(binding, Some(token.value), activate = true)))
        accepted(await(takeover.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
        // Before an observation, the takeover denies it.
        await(db.persistence.guardedWriteObservations.get.observeWrite(view)).isLeft shouldBe true
        // An unchanged write was admitted at its earlier observation; a changed one must save and is fenced.
        await(paired(session).commitObserved(unchanged.receipt, Page("", "b")))
        snapshotError(await(paired(session).commitObserved(changed.receipt, Page("typed", "b")))) shouldBe
          ViewSnapshotError.AccessDenied
        // The public commit always saves, so the old owner is refused for equal and changed projections.
        snapshotError(await(session.commit(Page("", "c")))) shouldBe ViewSnapshotError.AccessDenied
        snapshotError(await(session.commit(Page("other", "c")))) shouldBe ViewSnapshotError.AccessDenied
      } finally await(takeover.close())
    }

  it should "fail on a revision changed by the same owner in between, and never adopt it" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (view, _) = signedIn(db, db.persistence)
    val session   = ifChanged(db.persistence, view)
    await(session.initialize(Page("", "a")))
    // A second session of the same view and owner saves meanwhile.
    val other = db.persistence.projectedSnapshots[Page, String](draftFormat, _.draft, (fresh, draft) => fresh.copy(draft = draft))(view).get
    await(other.initialize(Page("", "a")))
    await(other.commit(Page("elsewhere", "a")))
    snapshotError(guardedWrite(db.persistence, view, session, Page("", "b"))) shouldBe ViewSnapshotError.RevisionConflict
    snapshotError(guardedWrite(db.persistence, view, session, Page("elsewhere", "b"))) shouldBe ViewSnapshotError.RevisionConflict
  }

  it should "fail closed after an unknown save outcome without advancing or adopting a revision" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (view, _) = signedIn(db, db.persistence)
    val session   = ifChanged(db.persistence, view)
    await(session.initialize(Page("", "a")))
    val seeded = db.viewRevision()
    val pending = observation(db.persistence, view)
    // The save commits, but its acknowledgment is lost.
    db.loseAcknowledgement.set(true)
    intercept[ViewSnapshotException](await(paired(session).commitObserved(pending.receipt, Page("typed", "a"))))
    db.viewRevision() shouldBe seeded + 1
    snapshotError(guardedWrite(db.persistence, view, session, Page("typed", "a"))) shouldBe ViewSnapshotError.RevisionConflict
  }

  it should "save the first write after restoring an older schema, then skip an unchanged one" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (view, _) = signedIn(db, db.persistence)
    val older     = db.persistence.projectedSnapshots[Page, String](draftFormat, _.draft, (fresh, draft) => fresh.copy(draft = draft))(view).get
    await(older.initialize(Page("", "a")))
    await(older.commit(Page("kept", "a")))
    val current = new SnapshotFormat[String](accepted(SchemaId.parse("guarded-draft")), accepted(SchemaVersion.fromInt(2)))
      .reading(draftFormat)(Right(_))
    val session = db.persistence.projectedSnapshotsIfChanged[Page, String](current, _.draft, (fresh, draft) => fresh.copy(draft = draft))(view).get
    await(session.initialize(Page("", "b"))) shouldBe Page("kept", "b")
    val restored = db.viewRevision()
    guardedWrite(db.persistence, view, session, Page("kept", "b"))
    db.viewRevision() shouldBe restored + 1
    guardedWrite(db.persistence, view, session, Page("kept", "c"))
    db.viewRevision() shouldBe restored + 1
  }

  it should "refuse a receipt of another view and a receipt used twice" in Using.resource(new Fixture(exclusive = false, held = true)) {
    db =>
      val (view, token) = signedIn(db, db.persistence)
      val session       = ifChanged(db.persistence, view)
      await(session.initialize(Page("", "a")))
      val identity  = accepted(await(db.persistence.capture(binding, Some(token.value), activate = false)))
      val otherView = accepted(await(db.persistence.claim(identity, Qsid("provider-device", "other-view"), ConnectionId.fromUuid(UUID.randomUUID()), false)))
      snapshotError(await(paired(session).commitObserved(observation(db.persistence, otherView).receipt, Page("", "a")))) shouldBe
        ViewSnapshotError.AccessDenied
      val once = observation(db.persistence, view)
      await(paired(session).commitObserved(once.receipt, Page("", "b")))
      snapshotError(await(paired(session).commitObserved(once.receipt, Page("", "b")))) shouldBe ViewSnapshotError.AccessDenied
  }

  it should "compare encoded bytes, not application equality" in Using.resource(new Fixture(exclusive = false, held = true)) { db =>
    val (view, _) = signedIn(db, db.persistence)
    val format    = new SnapshotFormat[LooseDraft](accepted(SchemaId.parse("loose-draft")), accepted(SchemaVersion.fromInt(1)))
    val session = db.persistence
      .projectedSnapshotsIfChanged[Page, LooseDraft](format, page => LooseDraft(page.draft, page.other), (fresh, saved) => fresh.copy(draft = saved.kept))(view)
      .get
    await(session.initialize(Page("same", "x")))
    LooseDraft("same", "x") shouldBe LooseDraft("same", "y")
    val seeded = db.viewRevision()
    guardedWrite(db.persistence, view, session, Page("same", "y"))
    db.viewRevision() shouldBe seeded + 1
  }

  it should "pair the observation only with its own session and a provider or decorator that offers it" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (_, token) = signedIn(db, db.persistence)
    val origin     = "https://app.example"
    def factory(provider: JdbcBrowserPersistence[Future, String]): BrowserView[String] => Option[ViewSnapshotSession[Future, String]] =
      provider.projectedSnapshotsIfChanged[String, String](draftFormat, page => page, (_, draft) => draft)
    // A session wrapped by application code no longer offers the capability.
    def wrapped(inner: BrowserView[String] => Option[ViewSnapshotSession[Future, String]]) =
      (view: BrowserView[String]) =>
        inner(view).map { session =>
          new ViewSnapshotSession[Future, String] {
            def ownerEpoch: ViewOwnershipEpoch          = session.ownerEpoch
            def initialize(fresh: String): Future[String] = session.initialize(fresh)
            def commit(candidate: String): Future[Unit]   = session.commit(candidate)
          }
        }
    def open(
      provider: BrowserPersistence[Future, String],
      snapshots: BrowserView[String] => Option[ViewSnapshotSession[Future, String]]
    ): (SessionGuard[Future, String], BrowserAuthentication[Future, String, String]) = {
      val browser = new BrowserAuthentication[Future, String, String](
        provider,
        BrowserPresentation(() => "fresh", (page, _) => page != "secret", (page, _, _) => page),
        BrowserConfiguration(provider.scope, Set(origin)),
        clock = () => db.clock.get(),
        snapshots = snapshots
      )
      val configured = browser.configure(
        SpoonbillServiceConfig[Future, String, Any](stateLoader = StateLoader.default("fresh"), document = page => Html(body(div(page))))
      )
      val cookies = Seq(browser.configuration.bindingCookieName + "=" + binding, browser.configuration.sessionCookieName + "=" + token.value)
      val request = Request(Request.Method.Get, PathAndQuery.Root, Seq("Origin" -> origin), None, Stream.empty[Future, Bytes], cookies.mkString("; "))
      await(configured.sessionAccessControl.get.open(qsid, request, ConnectionId.fromUuid(UUID.randomUUID()))) -> browser
    }
    def offered(provider: JdbcBrowserPersistence[Future, String], snapshots: BrowserView[String] => Option[ViewSnapshotSession[Future, String]],
      decorated: Option[Boolean] = None): Boolean = {
      val (guard, browser) = open(decorated.fold[BrowserPersistence[Future, String]](provider)(new Forwarding(provider, _)), snapshots)
      try guard.observedRootWrite.nonEmpty
      finally await(browser.close())
    }
    val own = db.fresh()
    offered(own, factory(own)) shouldBe true
    val ordinary = db.fresh()
    offered(ordinary, ordinary.projectedSnapshots[String, String](draftFormat, page => page, (_, draft) => draft)) shouldBe false
    val plain = db.fresh()
    offered(plain, wrapped(factory(plain))) shouldBe false
    val forwarding = db.fresh()
    offered(forwarding, factory(forwarding), decorated = Some(true)) shouldBe true
    val hiding = db.fresh()
    offered(hiding, factory(hiding), decorated = Some(false)) shouldBe false

    // Through the coordinator: one observation admits both presentations; an unchanged candidate saves nothing.
    val provider         = db.fresh()
    val (guard, browser) = open(provider, factory(provider))
    try {
      val write = guard.observedRootWrite.get
      await(guard.viewSnapshots.get.initialize("draft")) shouldBe "draft"
      val seeded = db.viewRevision()
      val before = db.domainTransactions.get()
      await(write.observe("draft", "draft").flatMap(receipt => write.commit(receipt, "draft")))
      (db.domainTransactions.get() - before, db.viewRevision()) shouldBe (1, seeded)
      // A disallowed candidate is denied like any check, and the view is detached.
      intercept[SessionAccessDenied](await(write.observe("draft", "secret")))
      intercept[SessionAccessDenied](await(guard.authorize("draft")))
    } finally await(browser.close())
  }

  it should "publish a write acknowledged while a second commit of the session waits, so the next write proceeds" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val pausing  = new db.PausingRunner
      val provider = db.withRunner(pausing.runner)
      try {
        val (view, _) = signedIn(db, provider)
        val session   = ifChanged(provider, view)
        await(session.initialize(Page("", "a")))
        val seeded = db.viewRevision()
        // The first save holds its transaction open before commit; the second waits for the realm lock.
        val (entered, resume) = pausing.arm()
        val first = session.commit(Page("one", "a"))
        entered.await(10, TimeUnit.SECONDS) shouldBe true
        val second = session.commit(Page("two", "a"))
        db.awaitLockWait("pg_advisory_xact_lock")
        resume.countDown()
        await(first)
        snapshotError(await(second)) shouldBe ViewSnapshotError.RevisionConflict
        db.viewRevision() shouldBe seeded + 1
        // The first write's acknowledged revision is what the session expects now.
        await(session.commit(Page("three", "a")))
        db.viewRevision() shouldBe seeded + 2
      } finally await(provider.close())
    }

  it should "let a cancelled initialization be retried, and commit after it, on the same session" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    import cats.effect.IO
    import cats.effect.kernel.Outcome
    import cats.effect.unsafe.implicits.global as runtime
    given Effect[IO] = new spoonbill.cats.IO3Effect(runtime)
    val (_, token) = signedIn(db, db.persistence)
    // A runner that, once armed, holds a settled transaction's result before handing it back.
    val armed    = new AtomicBoolean()
    val settled  = new CountDownLatch(1)
    val delivery = Promise[Unit]()
    val inner    = new JdbcTransactionExecutor[IO](db.source, ExecutionContext.global)
    val runner = new TransactionExecutor[IO, Direct, Connection] {
      val program     = TransactionProgram.direct
      val scopePolicy = TransactionScopePolicy.ThreadConfined
      def transact[A](body: Connection => A): IO[Either[TransactionFailure, A]] =
        inner.transact(body).flatMap { result =>
          if (armed.compareAndSet(true, false)) IO(settled.countDown()) *> IO.fromFuture(IO(delivery.future)).as(result)
          else IO.pure(result)
        }
    }
    val provider = JdbcBrowserPersistence.sharedViewsWithConcurrentChecks[IO, String](
      db.source, ExecutionContext.global, db.realm, "session", () => db.clock.get(), db.heldHostSessions, db.credentialVault,
      JdbcConcurrentChecks(db.source, 4), existingRunner = Some(runner)
    )
    try {
      val identity = accepted(provider.capture(binding, Some(token.value), activate = true).unsafeRunSync())
      val view     = accepted(provider.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true).unsafeRunSync())
      val session = provider
        .projectedSnapshotsIfChanged[Page, String](draftFormat, _.draft, (fresh, draft) => fresh.copy(draft = draft))(view)
        .get
      armed.set(true)
      val fiber = session.initialize(Page("", "draft")).start.unsafeRunSync()
      settled.await(10, TimeUnit.SECONDS) shouldBe true
      // The load settled; its result waits. Cancel the initialization, then let the result arrive.
      val cancelled = fiber.cancel.unsafeToFuture()
      delivery.success(())
      await(cancelled)
      fiber.join.unsafeRunSync() shouldBe Outcome.Canceled()
      session.initialize(Page("", "draft")).unsafeRunSync() shouldBe Page("", "draft")
      session.commit(Page("typed", "draft")).unsafeRunSync()
      db.viewRevision() should be > 0L
    } finally provider.close().unsafeRunSync()
  }

  private val secondBinding = "B" * 43
  private val secondView    = Qsid("provider-device", "second-view")
  private def realmKey(db: Fixture): String = s"spoonbill-auth:${db.realm.length}:${db.realm}session"

  "Shared-view concurrent checks" should "be a named opt-in for a held host, with no background clock renewal" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      intercept[IllegalArgumentException](db.withConcurrentChecks(4, BrowserOwnership.SharedViews))
      val provider = db.sharedConcurrent(4)
      try {
        provider.checkConcurrency shouldBe Some(BrowserCheckConcurrency(4))
        provider.renewingInBackground shouldBe false
      } finally await(provider.close())
    }

  it should "take the shared realm lock only for checks of authenticated views, and keep the rest exclusive" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val provider = db.sharedConcurrent(4)
      try {
        val (view, _) = signedIn(db, provider)
        val anonymous = accepted(await(provider.claim(
          accepted(await(provider.capture(secondBinding, None, activate = false))), secondView,
          ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false
        )))
        provider.isEnrolled shouldBe true
        def locks(operation: => Any): Vector[String] = {
          db.statements.clear(); db.recordSql.set(true)
          try operation finally db.recordSql.set(false)
          db.statements.asScala.toVector.filter(_.contains("pg_advisory_xact_lock"))
            .map(sql => if (sql.contains("lock_shared")) "shared" else "exclusive")
        }
        locks(accepted(await(provider.authorize(view)))) shouldBe Vector("shared")
        locks(accepted(await(provider.admitOutputIf(view, "frame")(_ => true)))) shouldBe Vector("shared")
        locks(accepted(await(provider.guardedWriteObservations.get.observeWrite(view)))) shouldBe Vector("shared")
        locks(accepted(await(provider.authorize(anonymous)))) shouldBe Vector("exclusive")
        locks(accepted(await(provider.pending(view)))) shouldBe Vector("exclusive")
        locks(accepted(await(provider.transact(view)(_ => ())))) shouldBe Vector("exclusive")
      } finally await(provider.close())
    }

  it should "let checks of different bindings on two nodes overlap until the clock row" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (first, second) = (db.sharedConcurrent(4), db.sharedConcurrent(4))
    try {
      val (one, _, _) = retentionLogin(db, first, binding, qsid, 600)
      val (two, _, _) = retentionLogin(db, second, secondBinding, secondView, 600)
      val clockRow    = new db.HeldLock("SELECT 1 FROM spoonbill_auth_clock WHERE realm=? AND namespace='session' FOR UPDATE", _.setString(1, db.realm))
      val checks = clockRow.holding {
        val checks = Vector(first.authorize(one), second.authorize(two))
        // Both hold the shared realm lock and their host, slot and view rows, and wait for the clock row together.
        db.awaitLockWait("spoonbill_auth_clock", count = 2)
        checks
      }
      checks.foreach(check => accepted(await(check)))
    } finally { await(first.close()); await(second.close()) }
  }

  it should "admit a queued writer and an older exclusive check after running checks, and later checks after them" in Using
    .resource(new Fixture(exclusive = false, held = true)) { db =>
      val (first, second) = (db.sharedConcurrent(4), db.sharedConcurrent(4))
      // An older node takes the exclusive realm lock for every check.
      val older = db.fresh()
      try {
        val (one, token, _) = retentionLogin(db, first, binding, qsid, 600)
        val (two, _, _)     = retentionLogin(db, second, secondBinding, secondView, 600)
        val olderView = accepted(await(older.claim(
          accepted(await(older.capture("C" * 43, None, activate = false))), Qsid("provider-device", "older-view"),
          ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false
        )))
        val clockRow = new db.HeldLock("SELECT 1 FROM spoonbill_auth_clock WHERE realm=? AND namespace='session' FOR UPDATE", _.setString(1, db.realm))
        val (running, logout, exclusive, later) = clockRow.holding {
          val running = first.authorize(one)
          db.awaitLockWait("spoonbill_auth_clock")
          // A writer and an older exclusive check queue behind the running check; a later check queues behind them.
          val logout = second.logout(binding, Some(token.value))
          db.awaitLockWait("pg_advisory_xact_lock")
          val exclusive = older.authorize(olderView)
          db.awaitLockWait("pg_advisory_xact_lock", count = 2)
          val later = first.authorize(one)
          db.awaitLockWait("pg_advisory_xact_lock_shared")
          (logout.isCompleted, exclusive.isCompleted, later.isCompleted) shouldBe (false, false, false)
          (running, logout, exclusive, later)
        }
        accepted(await(running))
        accepted(await(logout))
        accepted(await(exclusive))
        // The later check ran after the logout it queued behind.
        await(later) shouldBe Left(BrowserFailure.Denied)
        accepted(await(second.authorize(two)))
      } finally { await(first.close()); await(second.close()); await(older.close()) }
    }

  it should "deny the old owner's shared checks after a takeover on another node" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val (first, second) = (db.sharedConcurrent(4), db.sharedConcurrent(4))
    try {
      val (view, token, _) = retentionLogin(db, first, binding, qsid, 600)
      val captured = accepted(await(second.capture(binding, Some(token.value), activate = true)))
      val next     = accepted(await(second.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
      await(first.authorize(view)) shouldBe Left(BrowserFailure.Denied)
      await(first.admitOutputIf(view, "frame")(_ => true)) shouldBe Left(BrowserFailure.Denied)
      accepted(await(second.authorize(next)))
    } finally { await(first.close()); await(second.close()) }
  }

  it should "retry a deadlocked check once before its accepted hook, and never once the hook started" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val provider = db.sharedConcurrent(4)
    try {
      val (view, _, _)  = retentionLogin(db, provider, binding, qsid, 600)
      val (other, _, _) = retentionLogin(db, provider, secondBinding, secondView, 600)
      val viewKey       = BrowserView.key(qsid)
      // Before the hook: the check holds its slot and waits for the view row, which the test holds.
      val before = (db.domainTransactions.get(), db.hostAccepted.get())
      val viewRow = new db.HeldLock(
        "SELECT 1 FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace='session' AND view_id=? FOR UPDATE",
        query => { query.setString(1, db.realm); query.setString(2, viewKey) }
      )
      val retried = viewRow.holding {
        viewRow.deferDeadlockDetection()
        val retried = provider.authorize(view)
        db.awaitLockWait("spoonbill_browser_view")
        // Waiting for the check's slot closes the cycle; PostgreSQL aborts the check, which runs once more.
        viewRow.lock("SELECT 1 FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace='session' FOR UPDATE")(
          _.setString(1, db.realm)
        ) shouldBe true
        retried
      }
      accepted(await(retried))
      (db.domainTransactions.get() - before._1, db.hostAccepted.get() - before._2) shouldBe (2, 1)
      // In the hook: it waits for another session's host row, held by a test transaction that then waits for this one's.
      val after = (db.domainTransactions.get(), db.hostAccepted.get())
      db.hookLocks.set(Some(other.identity.sessionId.get))
      val otherRow  = new db.HeldLock("SELECT 1 FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE", hostRow(db, other.identity.sessionId.get))
      otherRow.holding {
        otherRow.deferDeadlockDetection()
        val abandoned = provider.authorize(view)
        db.awaitLockWait("spoonbill_auth_test_host")
        // The test transaction closes the cycle by waiting for this session's row; PostgreSQL aborts the check.
        val closing = Future(
          otherRow.lock("SELECT 1 FROM spoonbill_auth_test_host WHERE realm=? AND id=? FOR UPDATE")(
            hostRow(db, view.identity.sessionId.get)
          )
        )
        await(abandoned).isLeft shouldBe true
        await(closing) shouldBe true
      }
      (db.domainTransactions.get() - after._1, db.hostAccepted.get() - after._2) shouldBe (1, 1)
    } finally await(provider.close())
  }

  it should "fail closed within the pool's bound when the reserved clock pool is exhausted" in Using.resource(
    new Fixture(exclusive = false, held = true)
  ) { db =>
    val exhausted = new AtomicBoolean()
    // A clock pool that, once exhausted, refuses acquisition after its own timeout.
    val clockPool = new DataSource {
      def getConnection: Connection = {
        if (exhausted.get()) { Thread.sleep(200); throw new SQLException("Synthetic clock pool acquisition timeout") }
        db.source.getConnection
      }
      def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
      def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
      def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
      def getLoginTimeout: Int                                      = 0
      def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
      def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.jdbc.clock.pool.test")
      def isWrapperFor(kind: Class[?]): Boolean                     = false
      def unwrap[T](kind: Class[T]): T                              = throw new SQLException("Not a wrapper")
    }
    val provider = db.sharedConcurrent(4, clockSource = clockPool)
    try {
      val (view, _, _) = retentionLogin(db, provider, binding, qsid, 600)
      val accepted0    = db.hostAccepted.get()
      exhausted.set(true)
      val started = System.nanoTime()
      var ran     = false
      await(provider.authorize(view)).isLeft shouldBe true
      await(provider.transact(view)(_ => ran = true)).isLeft shouldBe true
      (System.nanoTime() - started) should be < TimeUnit.SECONDS.toNanos(5)
      (ran, db.hostAccepted.get() - accepted0) shouldBe (false, 0)
      exhausted.set(false)
      accepted(await(provider.authorize(view)))
    } finally await(provider.close())
  }
  it should "run checks of two bindings at once through an application decorator only when it forwards the setting" in {
    for (forward <- Vector(true, false)) withClue(s"forwarding $forward: ") {
      Using.resource(new Fixture(exclusive = false, held = true)) { db =>
        val origin    = "https://app.example"
        val provider  = db.sharedConcurrent(4)
        val decorated = new example.forwarding.ConcurrencyForwarding(provider, forward)
        val browser = new BrowserAuthentication[Future, String, String](
          decorated,
          BrowserPresentation(() => "fresh", (_, _) => true, (page, _, _) => page),
          BrowserConfiguration(decorated.scope, Set(origin)),
          clock = () => db.clock.get()
        )
        try {
          val configured = browser.configure(
            SpoonbillServiceConfig[Future, String, Any](stateLoader = StateLoader.default("fresh"), document = page => Html(body(div(page))))
          )
          def guard(rawBinding: String, id: Qsid): SessionGuard[Future, String] = {
            val (_, token, _) = retentionLogin(db, provider, rawBinding, id, 600)
            val cookies = Seq(browser.configuration.bindingCookieName + "=" + rawBinding, browser.configuration.sessionCookieName + "=" + token.value)
            val request = Request(Request.Method.Get, PathAndQuery.Root, Seq("Origin" -> origin), None, Stream.empty[Future, Bytes], cookies.mkString("; "))
            await(configured.sessionAccessControl.get.open(id, request, ConnectionId.fromUuid(UUID.randomUUID())))
          }
          val (one, two) = (guard(binding, qsid), guard(secondBinding, secondView))
          val clockRow   = new db.HeldLock("SELECT 1 FROM spoonbill_auth_clock WHERE realm=? AND namespace='session' FOR UPDATE", _.setString(1, db.realm))
          val checks = clockRow.holding {
            val checks = Vector(one.authorize("fresh"), two.authorize("fresh"))
            if (forward) {
              // Both checks run in read turns and wait for the clock row together.
              db.awaitLockWait("spoonbill_auth_clock", count = 2)
              browser.coordinatorStatistics.runningReads shouldBe 2
            } else {
              // Without the setting every check is an exclusive turn: the second one waits in the coordinator.
              db.awaitLockWait("spoonbill_auth_clock")
              val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
              while (browser.coordinatorStatistics.queuedWrites < 1 && System.nanoTime() < deadline) Thread.sleep(5)
              browser.coordinatorStatistics.queuedWrites shouldBe 1
              browser.coordinatorStatistics.runningReads shouldBe 0
            }
            checks
          }
          checks.foreach(check => await(check))
        } finally await(browser.close())
      }
    }
  }
}

/** A provider decorator that forwards, or hides, the inner provider's guarded write observations. */
private final class Forwarding(inner: JdbcBrowserPersistence[Future, String], forward: Boolean)(using Effect[Future])
    extends BrowserPersistence[Future, String] {
  def scope: BrowserProviderScope                                    = inner.scope
  def capabilities: BrowserProviderCapabilities                      = inner.capabilities
  def accepts(receipt: CommittedAuthentication): Boolean             = inner.accepts(receipt)
  def capture(binding: String, token: Option[String], activate: Boolean) = inner.capture(binding, token, activate)
  override def inspectPrincipal(binding: String, token: Option[String])(using Effect[Future]) =
    inner.inspectPrincipal(binding, token)
  override def registerView(identity: BrowserIdentity[String], qsid: Qsid)(using Effect[Future]) =
    inner.registerView(identity, qsid)
  def claim(identity: BrowserIdentity[String], qsid: Qsid, connectionId: ConnectionId, existingOnly: Boolean) =
    inner.claim(identity, qsid, connectionId, existingOnly)
  def authorize(view: BrowserView[String])                          = inner.authorize(view)
  override def admitOutputIf[A](view: BrowserView[String], output: A)(allowed: BrowserIdentity[String] => Boolean)(using
    Effect[Future]
  ) = inner.admitOutputIf(view, output)(allowed)
  override def guardedWriteObservations: Option[BrowserWriteObservations[Future, String]] =
    if (forward) inner.guardedWriteObservations else None
  def release(view: BrowserView[String])                            = inner.release(view)
  def begin(view: BrowserView[String], purpose: String)             = inner.begin(view, purpose)
  def bindChallenge(ceremony: BrowserCeremony, challenge: BrowserChallenge) = inner.bindChallenge(ceremony, challenge)
  def resume(view: BrowserView[String], id: CeremonyId, purpose: String) = inner.resume(view, id, purpose)
  def pending(view: BrowserView[String])                            = inner.pending(view)
  def recover(view: BrowserView[String], id: CeremonyId)            = inner.recover(view, id)
  def deliver(completionId: UUID, binding: String)                  = inner.deliver(completionId, binding)
  def logout(binding: String, token: Option[String])                = inner.logout(binding, token)
  def maintenance(): Future[Unit]                                   = inner.maintenance()
  def close(): Future[Unit]                                         = inner.close()
}

/** A projection whose application equality ignores a field that it serializes. */
final case class LooseDraft(kept: String, ignored: String) derives spoonbill.snapshot.StateSchema {
  override def equals(other: Any): Boolean = other match {
    case that: LooseDraft => kept == that.kept
    case _                => false
  }
  override def hashCode: Int = kept.hashCode
}
