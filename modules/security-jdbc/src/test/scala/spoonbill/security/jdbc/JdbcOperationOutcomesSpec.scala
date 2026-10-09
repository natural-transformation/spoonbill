package spoonbill.security.jdbc

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.net.URI
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, PreparedStatement, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.effect.Effect
import spoonbill.performance.PerformanceProbe
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.InvocationStatus
import spoonbill.security.transaction.*
import spoonbill.snapshot.{SnapshotIdentity, SnapshotKeys, ViewSnapshotError}

/** Explicit disposable loopback PostgreSQL only. These tests exercise physical
  * fences, fresh snapshots, host transaction atomicity and frontend call counts.
  */
class JdbcOperationOutcomesSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private val initialTime = Instant.parse("2026-10-08T12:00:00.123456789Z")
  private val waitSeconds = 10L
  private def accepted[E, A](value: Either[E, A]): A = value.fold(error => fail(error.toString), identity)
  private def result[A](value: Future[A]): A = Await.result(value, waitSeconds.seconds)
  private def await(latch: CountDownLatch): Unit = latch.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
  private def digest(text: String = "v1:change-preference:compact"): RequestDigest = accepted(
    RequestDigest.fromBytes(MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))))

  private abstract class TestDataSource extends DataSource {
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int = 0
    def setLoginTimeout(seconds: Int): Unit = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger = Logger.getLogger("spoonbill.outcome.test")
    def isWrapperFor(kind: Class[?]): Boolean = false
    def unwrap[T](kind: Class[T]): T = throw new SQLException("Not a wrapper")
  }

  private class Fixture extends AutoCloseable {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL",
      cancel("Set SPOONBILL_JDBC_TEST_URL using scripts/with-test-postgres.sh"))
    require(url.startsWith("jdbc:postgresql://"), "Tests require an explicit loopback PostgreSQL URL")
    require(Set("127.0.0.1", "localhost", "[::1]", "::1").contains(URI.create(url.stripPrefix("jdbc:")).getHost),
      "Tests require loopback PostgreSQL")
    private val schema = "outcome_test_" + UUID.randomUUID().toString.replace("-", "")
    private val workers = Executors.newFixedThreadPool(4)
    val blockingContext: ExecutionContext = ExecutionContext.fromExecutor(workers)
    private def raw(): Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      DriverManager.getConnection(url, properties)
    }
    Using.resource(raw())(connection => Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA $schema")))
    val dataSource: DataSource = new TestDataSource {
      def getConnection: Connection = {
        val connection = raw()
        try {
          Using.resource(connection.createStatement()) { statement =>
            statement.execute(s"SET search_path TO $schema")
            statement.execute("SET statement_timeout TO '15s'")
            statement.execute("SET lock_timeout TO '12s'")
          }
          connection
        } catch { case error: Throwable => connection.close(); throw error }
      }
    }
    val clock = new AtomicReference(initialTime)
    val calls = new AtomicInteger()
    val store = new JdbcOperationOutcomes()
    val issuer = new OneUseAuthorityScope(() => clock.get())
    val binding = OperationBinding(SubjectId.fromUuid(UUID.randomUUID()), RealmId.fromUuid(UUID.randomUUID()),
      AuthSessionId.fromUuid(UUID.randomUUID()), SecurityGeneration.initial,
      OperationPurpose.fromUuid(UUID.randomUUID()), ResourceScope.fromUuid(UUID.randomUUID()))
    try Using.resource(dataSource.getConnection) { connection =>
      store.initialize(connection)
      Using.resource(connection.createStatement()) { statement =>
        statement.executeUpdate("CREATE TABLE test_authority (singleton BOOLEAN PRIMARY KEY, valid BOOLEAN NOT NULL)")
        statement.executeUpdate("INSERT INTO test_authority VALUES (TRUE, TRUE)")
        statement.executeUpdate("CREATE TABLE test_domain (singleton BOOLEAN PRIMARY KEY, mutations INTEGER NOT NULL)")
        statement.executeUpdate("INSERT INTO test_domain VALUES (TRUE, 0)")
      }
    } catch { case error: Throwable => close(); throw error }

    def authority(): ExecutionAuthority = accepted(issuer.issue(accepted(
      issuer.verified(binding, digest(), initialTime.plusSeconds(60)))))
    def executor(source: DataSource = dataSource): JdbcTransactionExecutor[Future] =
      new JdbcTransactionExecutor[Future](source, blockingContext)
    def protocol(source: DataSource = dataSource, adapter: DurableOperationStore[Direct, Connection] = store)
      : OneUseOperationProtocol[Future, Direct, Connection] =
      new OneUseOperationProtocol[Future, Direct, Connection](executor(source), adapter, issuer, () => clock.get())
    def query[A](connection: Connection, sql: String)(body: PreparedStatement => A): A =
      Using.resource(connection.prepareStatement(sql))(body)
    def transaction[A](body: Connection => A): A = Using.resource(dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED)
      try { val value = body(connection); connection.commit(); value }
      catch { case error: Throwable => connection.rollback(); throw error }
    }
    def mutate(connection: Connection): String = {
      calls.incrementAndGet()
      val valid = query(connection, "SELECT valid FROM test_authority WHERE singleton=TRUE FOR UPDATE") { statement =>
        Using.resource(statement.executeQuery())(rows => rows.next() && rows.getBoolean(1))
      }
      if (!valid) throw new OperationProtocolException(OperationError.HostDenied)
      query(connection, "UPDATE test_domain SET mutations=mutations+1 WHERE singleton=TRUE")(_.executeUpdate()) shouldBe 1
      "synthetic-once-only-result"
    }
    def mutations: Int = Using.resource(dataSource.getConnection) { connection =>
      query(connection, "SELECT mutations FROM test_domain") { statement =>
        Using.resource(statement.executeQuery()) { rows => rows.next() shouldBe true; rows.getInt(1) }
      }
    }
    def read(operation: PreparedOperation): Option[StoredOperation] =
      transaction(connection => store.readForDecision(connection, operation.invocation))
    def pid(connection: Connection): Int = query(connection, "SELECT pg_backend_pid()") { statement =>
      Using.resource(statement.executeQuery()) { rows => rows.next() shouldBe true; rows.getInt(1) }
    }
    def captureConnection(pidResult: AtomicInteger, connected: CountDownLatch): DataSource = new TestDataSource {
      def getConnection: Connection = {
        val connection = dataSource.getConnection
        pidResult.set(pid(connection))
        connected.countDown()
        connection
      }
    }
    def advisory(connection: Connection, key: String): Unit =
      query(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?,0))") { statement =>
        statement.setString(1, key)
        Using.resource(statement.executeQuery())(_ => ())
      }
    def awaitBlocked(waiter: Int, blocker: Option[Int] = None): Unit = Using.resource(dataSource.getConnection) { observer =>
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
      def blocked: Boolean = query(observer, blocker.fold("SELECT cardinality(pg_blocking_pids(?))>0")(_ =>
        "SELECT ?=ANY(pg_blocking_pids(?))")) { statement =>
        blocker match {
          case Some(value) => statement.setInt(1, value); statement.setInt(2, waiter)
          case None => statement.setInt(1, waiter)
        }
        Using.resource(statement.executeQuery())(rows => rows.next() && rows.getBoolean(1))
      }
      Iterator.continually(blocked).takeWhile(_ => System.nanoTime() < deadline).find(identity) shouldBe Some(true)
    }
    def close(): Unit = {
      issuer.close()
      workers.shutdownNow()
      workers.awaitTermination(waitSeconds, TimeUnit.SECONDS) shouldBe true
      Using.resource(raw())(connection => Using.resource(connection.createStatement())(_.executeUpdate(s"DROP SCHEMA $schema CASCADE")))
    }
  }

  private def withDb[A](body: Fixture => A): A = Using.resource(new Fixture)(body)

  /** Real PostgreSQL underneath a pool-like facade. Returning an active
    * connection would commit it, so abort ordering is observable as persisted
    * host writes rather than just a mock invocation count.
    */
  private class LifecycleFaults(db: Fixture, rollbackAfter: Option[Boolean] = None,
    commitAfter: Option[Boolean] = None, abortFails: Boolean = false,
    closeFails: Boolean = false, setupFails: Boolean = false, borrowWithWork: Boolean = false) extends AutoCloseable {
    val aborts = new AtomicInteger()
    val closes = new AtomicInteger()
    val poolCommits = new AtomicInteger()
    val preparedStatements = new AtomicInteger()
    val physical = new AtomicReference(Option.empty[Connection])
    val source: DataSource = new TestDataSource {
      def getConnection: Connection = {
        val connection = db.dataSource.getConnection
        physical.set(Some(connection))
        if (borrowWithWork) {
          connection.setAutoCommit(false)
          db.mutate(connection)
        }
        Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), new InvocationHandler {
          def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
            val args = Option(arguments).getOrElse(Array.empty[Object])
            method.getName match {
              case "rollback" if rollbackAfter.isDefined =>
                if (rollbackAfter.contains(true)) connection.rollback()
                throw new SQLException("Synthetic rollback acknowledgment failure")
              case "commit" if commitAfter.isDefined =>
                if (commitAfter.contains(true)) connection.commit()
                throw new SQLException("Synthetic commit acknowledgment failure")
              case "setTransactionIsolation" if setupFails =>
                throw new SQLException("Synthetic transaction setup failure")
              case "abort" =>
                aborts.incrementAndGet()
                if (abortFails) throw new SQLException("Synthetic abort failure")
                // Exercise the actual PostgreSQL driver's abort with the
                // helper-supplied executor, not a test replacement for abort.
                connection.abort(args(0).asInstanceOf[java.util.concurrent.Executor])
                null
              case "close" =>
                closes.incrementAndGet()
                if (!connection.isClosed && !connection.getAutoCommit) {
                  poolCommits.incrementAndGet()
                  connection.commit()
                }
                connection.close()
                if (closeFails) throw new SQLException("Synthetic pool return failure")
                null
              case _ =>
                if (method.getName == "prepareStatement") preparedStatements.incrementAndGet()
                try method.invoke(connection, args*)
                catch { case error: InvocationTargetException => throw error.getCause }
            }
          }
        }).asInstanceOf[Connection]
      }
    }

    // Test-only quarantine recovery; never invoke the pool-like facade here.
    def close(): Unit = physical.get().foreach { connection =>
      if (!connection.isClosed) {
        try connection.rollback() finally connection.close()
      }
    }
  }

  private def lostCommit(connection: Connection, committedOnServer: Boolean): Connection = {
    val once = new AtomicBoolean(true)
    Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), new InvocationHandler {
      def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
        val args = Option(arguments).getOrElse(Array.empty[Object])
        val failCommit = method.getName == "commit" && once.getAndSet(false)
        if (failCommit && !committedOnServer) throw new SQLException("Synthetic pre-commit failure")
        val value = try method.invoke(connection, args*)
        catch { case error: InvocationTargetException => throw error.getCause }
        if (failCommit) throw new SQLException("Synthetic lost acknowledgment")
        value
      }
    }).asInstanceOf[Connection]
  }

  "JDBC operation outcomes" should "serialize independent adapters and refresh the snapshot after waiting for the winning writer" in withDb { db =>
    val operation = db.authority().reference.operation
    val entered = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val connected = new CountDownLatch(1)
    val secondPid = new AtomicInteger()
    val first = db.executor().transact { connection =>
      new JdbcOperationOutcomes().readForDecision(connection, operation.invocation) shouldBe None
      db.mutate(connection)
      db.store.recordOutcome(connection, operation, InvocationStatus.Committed)
      entered.countDown()
      await(release)
    }
    try {
      await(entered)
      val second = db.executor(db.captureConnection(secondPid, connected)).transact { connection =>
        val stored = new JdbcOperationOutcomes().readForDecision(connection, operation.invocation)
        stored shouldBe Some(StoredOperation(operation, InvocationStatus.Committed))
        stored
      }
      await(connected)
      db.awaitBlocked(secondPid.get())
      second.isCompleted shouldBe false
      release.countDown()
      accepted(result(first))
      accepted(result(second)) shouldBe Some(StoredOperation(operation, InvocationStatus.Committed))
      db.mutations shouldBe 1
      db.calls.get() shouldBe 1
    } finally release.countDown()
  }

  it should "reject grant invocation binding and digest aliases while retaining exact nanosecond metadata" in withDb { db =>
    val operation = db.authority().reference.operation
    db.transaction { connection =>
      db.store.readForDecision(connection, operation.invocation) shouldBe None
      db.store.recordOutcome(connection, operation, InvocationStatus.NotCommitted)
    }
    db.read(operation) shouldBe Some(StoredOperation(operation, InvocationStatus.NotCommitted))
    val aliases = Vector(
      operation.invocation.copy(invocationId = InvocationId.fromUuid(UUID.randomUUID())),
      operation.invocation.copy(grantId = OperationAuthorizationId.fromUuid(UUID.randomUUID())),
      operation.invocation.copy(binding = db.binding.copy(sessionId = AuthSessionId.fromUuid(UUID.randomUUID()))),
      operation.invocation.copy(requestDigest = digest("different-intent")))
    aliases.foreach { alias =>
      intercept[OperationProtocolException](db.transaction(db.store.readForDecision(_, alias))).error shouldBe
        OperationError.InvocationConflict
    }
    val changed = operation.copy(definition = operation.definition.copy(expiresAt = initialTime.plusSeconds(30)))
    intercept[OperationProtocolException](db.transaction { connection =>
      db.store.readForDecision(connection, changed.invocation)
      db.store.recordOutcome(connection, changed, InvocationStatus.NotCommitted)
    }).error shouldBe OperationError.CapacityOrConflict
    db.read(operation) shouldBe Some(StoredOperation(operation, InvocationStatus.NotCommitted))
  }

  it should "support durable preparation with the same immutable metadata and atomic terminal transition" in withDb { db =>
    val operation = db.authority().reference.operation
    val protocol = new OperationProtocol[Future, Direct, Connection](db.executor(), db.store, () => db.clock.get())
    val prepared = accepted(result(protocol.prepare(scope => scope.stage(operation)(_ => "context"))))
    db.read(operation) shouldBe Some(StoredOperation(operation, InvocationStatus.InProgress))
    prepared match {
      case Prepared.Ready("context", permit) =>
        accepted(result(protocol.execute(permit)((scope, _) => db.mutate(scope.transaction)))) shouldBe "synthetic-once-only-result"
      case other => fail(s"Expected prepared permit, got $other")
    }
    db.read(operation) shouldBe Some(StoredOperation(operation, InvocationStatus.Committed))
    db.mutations shouldBe 1
  }

  it should "roll back host writes and outcomes while consuming a failed issuer-bound attempt" in withDb { db =>
    val authority = db.authority()
    val protocol = db.protocol()
    result(protocol.executeIssued(authority) { (scope, _) =>
      db.mutate(scope.transaction)
      throw new OperationProtocolException(OperationError.HostDenied)
    }) shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
    db.mutations shouldBe 0
    db.read(authority.reference.operation) shouldBe None
    result(protocol.executeIssued(authority)((scope, _) => db.mutate(scope.transaction))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    accepted(result(protocol.reconcile(authority.reference))).status shouldBe InvocationStatus.NotCommitted
  }

  for (afterRollback <- Vector(false, true); typed <- Vector(false, true)) {
    it should s"abort before pool return when ${if (typed) "typed rejection" else "callback failure"} rollback fails ${if (afterRollback) "after" else "before"} delegation" in withDb { db =>
      Using.resource(new LifecycleFaults(db, rollbackAfter = Some(afterRollback))) { fault =>
        val authority = db.authority()
        val protocol = db.protocol(fault.source)
        result(protocol.executeIssued(authority) { (scope, _) =>
          db.mutate(scope.transaction)
          if (typed) throw new OperationProtocolException(OperationError.HostDenied)
          else throw new IllegalStateException("Synthetic host failure")
        }) shouldBe Left(TransactionFailure.CommitUnknown)
        fault.aborts.get() shouldBe 1
        fault.closes.get() shouldBe 1
        fault.poolCommits.get() shouldBe 0
        fault.physical.get().exists(_.isClosed) shouldBe true
        db.mutations shouldBe 0
        result(protocol.executeIssued(authority)((_, _) => fail("Consumed authority must not replay"))) shouldBe
          Left(TransactionFailure.Rejected(OperationError.PermitUsed))
        accepted(result(db.protocol().reconcile(authority.reference))).status shouldBe InvocationStatus.NotCommitted
      }
    }
  }

  it should "quarantine a connection whose rollback and abort both fail without returning it to the pool" in withDb { db =>
    val authority = db.authority()
    Using.resource(new LifecycleFaults(db, rollbackAfter = Some(false), abortFails = true)) { fault =>
      result(db.protocol(fault.source).executeIssued(authority) { (scope, _) =>
        db.mutate(scope.transaction)
        throw new OperationProtocolException(OperationError.HostDenied)
      }) shouldBe Left(TransactionFailure.CommitUnknown)
      fault.aborts.get() shouldBe 1
      fault.closes.get() shouldBe 0
      fault.poolCommits.get() shouldBe 0
      fault.physical.get().exists(connection => !connection.isClosed) shouldBe true
      // MVCC reads observe no committed host mutation while the failed
      // connection remains quarantined with its transaction and fences held.
      db.mutations shouldBe 0
      result(db.protocol().executeIssued(authority)((_, _) => fail("Must not replay"))) shouldBe
        Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    }
    // The test administrator has now rolled back and disposed the quarantined
    // physical connection; only fenced recovery establishes a negative result.
    accepted(result(db.protocol().reconcile(authority.reference))).status shouldBe InvocationStatus.NotCommitted
  }

  for (committed <- Vector(false, true)) {
    it should s"abort an uncertain commit before pool return when server commit is $committed" in withDb { db =>
      Using.resource(new LifecycleFaults(db, commitAfter = Some(committed))) { fault =>
        val authority = db.authority()
        result(db.protocol(fault.source).executeIssued(authority)((scope, _) => db.mutate(scope.transaction))) shouldBe
          Left(TransactionFailure.CommitUnknown)
        fault.aborts.get() shouldBe 1
        fault.closes.get() shouldBe 1
        fault.poolCommits.get() shouldBe 0
        fault.physical.get().exists(_.isClosed) shouldBe true
        db.mutations shouldBe (if (committed) 1 else 0)
        accepted(result(db.protocol().reconcile(authority.reference))).status shouldBe
          (if (committed) InvocationStatus.Committed else InvocationStatus.NotCommitted)
      }
    }
  }

  for (reject <- Vector(false, true)) {
    it should s"preserve acknowledged ${if (reject) "typed rollback" else "commit"} despite pool close failure" in withDb { db =>
      Using.resource(new LifecycleFaults(db, closeFails = true)) { fault =>
        val authority = db.authority()
        val observed = result(db.protocol(fault.source).executeIssued(authority) { (scope, _) =>
          val value = db.mutate(scope.transaction)
          if (reject) throw new OperationProtocolException(OperationError.HostDenied)
          value
        })
        observed shouldBe (if (reject) Left(TransactionFailure.Rejected(OperationError.HostDenied))
          else Right("synthetic-once-only-result"))
        fault.aborts.get() shouldBe 0
        fault.closes.get() shouldBe 1
        db.mutations shouldBe (if (reject) 0 else 1)
      }
    }
  }

  it should "abort an unsettled connection after transaction setup failure without entering the host" in withDb { db =>
    Using.resource(new LifecycleFaults(db, setupFails = true)) { fault =>
      val authority = db.authority()
      result(db.protocol(fault.source).executeIssued(authority)((_, _) => fail("Setup failed"))) shouldBe
        Left(TransactionFailure.StorageFailure)
      fault.aborts.get() shouldBe 1
      fault.closes.get() shouldBe 1
      fault.poolCommits.get() shouldBe 0
      fault.physical.get().exists(_.isClosed) shouldBe true
      result(db.protocol().executeIssued(authority)((_, _) => fail("Must not replay"))) shouldBe
        Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      accepted(result(db.protocol().reconcile(authority.reference))).status shouldBe InvocationStatus.NotCommitted
    }
  }

  it should "apply unresolved rollback disposal to the durable operation wrapper" in withDb { db =>
    val ordinary = new JdbcOperationAuthorizations(db.dataSource, () => db.clock.get())
    Using.resource(db.dataSource.getConnection)(ordinary.initialize)
    val definition = db.authority().reference.operation.definition
    accepted(ordinary.issue(definition))
    val conflicting = definition.copy(expiresAt = initialTime.minusSeconds(1))
    Using.resource(new LifecycleFaults(db, rollbackAfter = Some(false))) { fault =>
      new JdbcOperationAuthorizations(fault.source, () => db.clock.get()).issue(conflicting) shouldBe
        Left(JdbcOperationError.CommitUnknown)
      fault.aborts.get() shouldBe 1
      fault.poolCommits.get() shouldBe 0
      fault.physical.get().exists(_.isClosed) shouldBe true
    }
  }

  it should "apply unresolved rollback disposal to browser session transactions" in withDb { db =>
    val hooks = new HostSessionHooks {
      def isCurrent(connection: Connection, sessionId: UUID, now: Instant): Boolean = true
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
    }
    def browser(source: DataSource) = new JdbcBrowserSessions(source, "lifecycle-test", "session", () => db.clock.get(), hooks)
    Using.resource(db.dataSource.getConnection)(browser(db.dataSource).initialize)
    val binding = accepted(Digest256.fromBytes(new Array[Byte](32)))
    Using.resource(new LifecycleFaults(db, rollbackAfter = Some(false))) { fault =>
      browser(fault.source).claimExistingView("lifecycle-view", binding, UUID.randomUUID()) shouldBe
        Left(JdbcAuthError.StorageFailure)
      fault.aborts.get() shouldBe 1
      fault.poolCommits.get() shouldBe 0
      fault.physical.get().exists(_.isClosed) shouldBe true
    }
  }

  it should "apply unresolved rollback disposal to browser snapshot transactions" in withDb { db =>
    val hooks = new HostSessionHooks {
      def isCurrent(connection: Connection, sessionId: UUID, now: Instant): Boolean = true
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
    }
    def browser(source: DataSource) = new JdbcBrowserSessions(source, "lifecycle-test", "session", () => db.clock.get(), hooks)
    Using.resource(db.dataSource.getConnection)(browser(db.dataSource).initialize)
    val binding = accepted(Digest256.fromBytes(new Array[Byte](32)))
    val expected = SnapshotIdentity(accepted(SnapshotKeys.Subject.parse("lifecycle-subject")),
      accepted(SnapshotKeys.Scope.parse("lifecycle-scope")), UUID.randomUUID(), SecurityGeneration.initial, SlotGeneration.initial)
    val identityCheck = new HostSnapshotIdentity {
      def isCurrent(connection: Connection, sessionId: UUID, identity: SnapshotIdentity, now: Instant): Boolean = true
    }
    Using.resource(new LifecycleFaults(db, rollbackAfter = Some(false))) { fault =>
      browser(fault.source).withSnapshotView("lifecycle-view", binding, UUID.randomUUID(),
        accepted(ViewOwnershipEpoch.fromLong(1L)), binding, expected, identityCheck)((_, _) => fail("Missing session")) shouldBe
        Left(ViewSnapshotError.StorageFailure)
      fault.aborts.get() shouldBe 1
      fault.poolCommits.get() shouldBe 0
      fault.physical.get().exists(_.isClosed) shouldBe true
    }
  }

  for (abortFails <- Vector(false, true)) {
    it should s"refuse a generation observation without committing borrowed work when abort fails is $abortFails" in withDb { db =>
      val hooks = new HostSessionHooks {
        def isCurrent(connection: Connection, sessionId: UUID, now: Instant): Boolean = true
        def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
      }
      val binding = accepted(Digest256.fromBytes(new Array[Byte](32)))
      Using.resource(new LifecycleFaults(db, abortFails = abortFails, borrowWithWork = true)) { fault =>
        val browser = new JdbcBrowserSessions(fault.source, "lifecycle-test", "session", () => db.clock.get(), hooks)
        browser.captureExistingSlotGeneration(binding) shouldBe Left(JdbcAuthError.StorageFailure)
        fault.preparedStatements.get() shouldBe 0
        fault.aborts.get() shouldBe 1
        fault.closes.get() shouldBe (if (abortFails) 0 else 1)
        fault.poolCommits.get() shouldBe 0
        fault.physical.get().exists(_.isClosed) shouldBe !abortFails
        db.mutations shouldBe 0
      }
      db.mutations shouldBe 0
    }
  }

  for (committed <- Vector(true, false)) {
    it should s"return no result after an uncertain commit that ${if (committed) "committed" else "rolled back"} on the server" in withDb { db =>
      val authority = db.authority()
      val interrupted = new TestDataSource {
        def getConnection: Connection = lostCommit(db.dataSource.getConnection, committed)
      }
      result(db.protocol(interrupted).executeIssued(authority)((scope, _) => db.mutate(scope.transaction))) shouldBe
        Left(TransactionFailure.CommitUnknown)
      accepted(result(db.protocol().reconcile(authority.reference))).status shouldBe
        (if (committed) InvocationStatus.Committed else InvocationStatus.NotCommitted)
      db.mutations shouldBe (if (committed) 1 else 0)
      result(db.protocol().executeIssued(authority)((_, _) => fail("must not replay"))) shouldBe
        Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    }
  }

  it should "commit a negative fence before rejecting late first execution" in withDb { db =>
    val authority = db.authority()
    accepted(result(db.protocol().reconcile(authority.reference))).status shouldBe InvocationStatus.NotCommitted
    result(db.protocol().executeIssued(authority)((_, _) => fail("late callback"))) shouldBe
      Left(TransactionFailure.Rejected(OperationError.NotPrepared))
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  for (commit <- Vector(true, false)) {
    it should s"wait for an executing host writer to ${if (commit) "commit" else "roll back"} before settling recovery" in withDb { db =>
      val authority = db.authority()
      val entered = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val connected = new CountDownLatch(1)
      val reconcilerPid = new AtomicInteger()
      val execution = db.protocol().executeIssued(authority) { (scope, _) =>
        val value = db.mutate(scope.transaction)
        entered.countDown()
        await(release)
        if (!commit) throw new OperationProtocolException(OperationError.HostDenied)
        value
      }
      try {
        await(entered)
        val recovery = db.protocol(db.captureConnection(reconcilerPid, connected)).reconcile(authority.reference)
        await(connected)
        db.awaitBlocked(reconcilerPid.get())
        recovery.isCompleted shouldBe false
        release.countDown()
        result(execution) shouldBe (if (commit) Right("synthetic-once-only-result")
          else Left(TransactionFailure.Rejected(OperationError.HostDenied)))
        accepted(result(recovery)).status shouldBe (if (commit) InvocationStatus.Committed else InvocationStatus.NotCommitted)
        db.mutations shouldBe (if (commit) 1 else 0)
      } finally release.countDown()
    }
  }

  it should "check expiry after waiting for protocol locks before entering host code" in withDb { db =>
    val authority = db.authority()
    val invocation = authority.reference.operation.invocation
    Using.resource(db.dataSource.getConnection) { blocker =>
      blocker.setAutoCommit(false)
      db.advisory(blocker, s"spoonbill.outcome.subject:${invocation.binding.realmId}:${invocation.binding.subjectId}")
      val connected = new CountDownLatch(1)
      val waiter = new AtomicInteger()
      val execution = db.protocol(db.captureConnection(waiter, connected)).executeIssued(authority)((_, _) => fail("expired callback"))
      try {
        await(connected)
        db.awaitBlocked(waiter.get())
        db.clock.set(authority.reference.operation.definition.expiresAt)
        blocker.commit()
        result(execution) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
        db.read(authority.reference.operation) shouldBe None
      } finally blocker.rollback()
    }
  }

  it should "retain terminal identities at capacity and reject terminal flips or metadata replacement" in withDb { db =>
    val limited = new JdbcOperationOutcomes(maxOutcomesPerSubject = 2)
    val first = db.authority().reference.operation
    val second = db.authority().reference.operation
    val overflow = db.authority().reference.operation
    Vector(first, second).foreach { operation => db.transaction { connection =>
      limited.readForDecision(connection, operation.invocation) shouldBe None
      limited.recordOutcome(connection, operation, InvocationStatus.NotCommitted)
    }}
    intercept[OperationProtocolException](db.transaction { connection =>
      limited.readForDecision(connection, overflow.invocation) shouldBe None
      limited.recordOutcome(connection, overflow, InvocationStatus.NotCommitted)
    }).error shouldBe OperationError.CapacityOrConflict
    db.transaction { connection =>
      limited.readForDecision(connection, first.invocation).map(_.status) shouldBe Some(InvocationStatus.NotCommitted)
      limited.recordOutcome(connection, first, InvocationStatus.NotCommitted)
    }
    intercept[OperationProtocolException](db.transaction { connection =>
      limited.readForDecision(connection, first.invocation)
      limited.recordOutcome(connection, first, InvocationStatus.Committed)
    }).error shouldBe OperationError.CapacityOrConflict
    db.clock.set(initialTime.plusSeconds(120))
    db.read(first).map(_.status) shouldBe Some(InvocationStatus.NotCommitted)
    db.read(second).map(_.status) shouldBe Some(InvocationStatus.NotCommitted)
    db.read(overflow) shouldBe None
  }

  it should "use three ledger executions and one outer commit alongside the actual host SQL" in withDb { db =>
    val probe = new PerformanceProbe(db.dataSource)
    val authority = db.authority()
    val (outcome, sample) = probe.measure(result(db.protocol(probe.dataSource)
      .executeIssued(authority)((scope, _) => db.mutate(scope.transaction))))
    outcome shouldBe Right("synthetic-once-only-result")
    sample.counts.sqlExecutions shouldBe 5L // Three ledger calls + SELECT authority + UPDATE host.
    sample.counts.commits shouldBe 1L
    sample.counts.committed shouldBe 1L
    sample.counts.connections shouldBe 1L
    sample.counts.rollbacks shouldBe 0L
    sample.activeConnectionsAfter shouldBe 0L
    db.mutations shouldBe 1
    val (_, recovered) = probe.measure(result(db.protocol(probe.dataSource).reconcile(authority.reference)))
    recovered.counts.sqlExecutions shouldBe 2L
    recovered.counts.commits shouldBe 1L
  }

  it should "acquire subject then grant then invocation fences within its single dependent CTE query" in withDb { db =>
    val operation = db.authority().reference.operation
    val invocation = operation.invocation
    val subjectKey = s"spoonbill.outcome.subject:${invocation.binding.realmId}:${invocation.binding.subjectId}"
    val grantKey = s"spoonbill.outcome.grant:${invocation.grantId}"
    val invocationKey = s"spoonbill.outcome.invocation:${invocation.invocationId}"
    Using.resource(db.dataSource.getConnection) { subjectBlocker =>
      Using.resource(db.dataSource.getConnection) { grantBlocker =>
        Using.resource(db.dataSource.getConnection) { invocationBlocker =>
          Vector(subjectBlocker, grantBlocker, invocationBlocker).foreach(_.setAutoCommit(false))
          db.advisory(subjectBlocker, subjectKey)
          db.advisory(grantBlocker, grantKey)
          db.advisory(invocationBlocker, invocationKey)
          val subjectPid = db.pid(subjectBlocker)
          val grantPid = db.pid(grantBlocker)
          val invocationPid = db.pid(invocationBlocker)
          val connected = new CountDownLatch(1)
          val waiter = new AtomicInteger()
          val read = db.executor(db.captureConnection(waiter, connected)).transact(connection =>
            db.store.readForDecision(connection, invocation))
          try {
            await(connected)
            db.awaitBlocked(waiter.get(), Some(subjectPid))
            subjectBlocker.commit()
            db.awaitBlocked(waiter.get(), Some(grantPid))
            grantBlocker.commit()
            db.awaitBlocked(waiter.get(), Some(invocationPid))
            invocationBlocker.commit()
            accepted(result(read)) shouldBe None
          } finally Vector(subjectBlocker, grantBlocker, invocationBlocker).foreach(_.rollback())
        }
      }
    }
  }

  it should "require an active READ COMMITTED transaction without issuing an isolation introspection query" in withDb { db =>
    val operation = db.authority().reference.operation
    Using.resource(db.dataSource.getConnection) { connection =>
      intercept[OperationProtocolException](db.store.readForDecision(connection, operation.invocation)).error shouldBe
        OperationError.TransactionRequired
      connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ)
      connection.setAutoCommit(false)
      try {
        intercept[OperationProtocolException](db.store.readForDecision(connection, operation.invocation)).error shouldBe
          OperationError.IsolationRequired
      } finally connection.rollback()
    }
  }
}
