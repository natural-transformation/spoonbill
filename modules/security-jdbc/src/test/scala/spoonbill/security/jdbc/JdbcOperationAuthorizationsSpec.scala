package spoonbill.security.jdbc

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.net.URI
import java.security.MessageDigest
import java.sql.{Connection, DriverManager, PreparedStatement, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{Callable, CountDownLatch, ExecutorService, Executors, Future as JavaFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Using
import spoonbill.performance.PerformanceProbe
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.{GrantDefinition, InvocationStatus}

/** Real PostgreSQL only. Every fixture owns a random schema and uses synthetic
  * authority/domain rows. No production URL or shared-table cleanup is allowed.
  */
class JdbcOperationAuthorizationsSpec extends AnyFlatSpec with Matchers {
  private val initialTime = Instant.parse("2026-10-07T12:00:00Z")
  private val waitSeconds = 10L
  private def accepted[E, A](result: Either[E, A]): A = result.fold(error => fail(error.toString), identity)
  private def digest(intent: String = "v1:change-preference:compact"): RequestDigest =
    accepted(RequestDigest.fromBytes(MessageDigest.getInstance("SHA-256").digest(intent.getBytes(java.nio.charset.StandardCharsets.UTF_8))))

  private abstract class TestDataSource extends DataSource {
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int = 0
    def setLoginTimeout(seconds: Int): Unit = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger = Logger.getLogger("spoonbill.operation.test")
    def isWrapperFor(kind: Class[?]): Boolean = false
    def unwrap[T](kind: Class[T]): T = throw new SQLException("Not a wrapper")
  }

  private class Fixture extends AutoCloseable {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL",
      cancel("Set SPOONBILL_JDBC_TEST_URL using scripts/with-test-postgres.sh"))
    require(url.startsWith("jdbc:postgresql://"), "Tests require an explicit loopback PostgreSQL URL")
    private val host = URI.create(url.stripPrefix("jdbc:")).getHost
    require(Set("127.0.0.1", "localhost", "[::1]", "::1").contains(host), "Tests require loopback PostgreSQL")
    private val schema = "operation_test_" + UUID.randomUUID().toString.replace("-", "")
    private def rawConnection(): Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      DriverManager.getConnection(url, properties)
    }
    Using.resource(rawConnection()) { connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA $schema"))
    }
    val dataSource: DataSource = new TestDataSource {
      def getConnection: Connection = {
        val connection = rawConnection()
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
    val calls = new AtomicInteger(0)
    val binding = OperationBinding(SubjectId.fromUuid(UUID.randomUUID()), RealmId.fromUuid(UUID.randomUUID()),
      AuthSessionId.fromUuid(UUID.randomUUID()), SecurityGeneration.initial,
      OperationPurpose.fromUuid(UUID.randomUUID()), ResourceScope.fromUuid(UUID.randomUUID()))
    def store: JdbcOperationAuthorizations = new JdbcOperationAuthorizations(dataSource, () => clock.get())
    def definition(policy: ReservationPolicy = ReservationPolicy.ReleaseAfterDefiniteFailure): GrantDefinition =
      GrantDefinition(OperationAuthorizationId.fromUuid(UUID.randomUUID()), binding, initialTime.plusSeconds(60), policy)
    def invocation(grant: GrantDefinition): OperationInvocation =
      OperationInvocation(InvocationId.fromUuid(UUID.randomUUID()), grant.id, grant.binding, digest())

    try {
      Using.resource(dataSource.getConnection) { connection =>
        store.initialize(connection)
        Using.resource(connection.createStatement()) { statement =>
          statement.executeUpdate("CREATE TABLE test_authority (singleton BOOLEAN PRIMARY KEY, valid BOOLEAN NOT NULL)")
          statement.executeUpdate("INSERT INTO test_authority VALUES (TRUE, TRUE)")
          statement.executeUpdate("CREATE TABLE test_domain (singleton BOOLEAN PRIMARY KEY, mutations INTEGER NOT NULL)")
          statement.executeUpdate("INSERT INTO test_domain VALUES (TRUE, 0)")
        }
      }
    } catch { case error: Throwable => close(); throw error }

    def query[A](connection: Connection, sql: String)(run: PreparedStatement => A): A =
      Using.resource(connection.prepareStatement(sql))(run)
    def transaction[A](run: Connection => A): A = Using.resource(dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      try { val result = run(connection); connection.commit(); result }
      catch { case error: Throwable => connection.rollback(); throw error }
    }
    def permit(value: OperationPreparation): ExecutionPermit = value match {
      case OperationPreparation.Acquired(result) => result
      case other => fail(s"Expected a new execution permit, got $other")
    }
    def reserved(grant: GrantDefinition): (OperationInvocation, ExecutionPermit) = {
      accepted(store.issue(grant))
      val request = invocation(grant)
      request -> permit(accepted(store.reserve(request)))
    }
    def mutate(connection: Connection, now: Instant): Either[JdbcOperationError, String] = {
      calls.incrementAndGet()
      val current = query(connection, "SELECT valid FROM test_authority WHERE singleton = TRUE FOR UPDATE") { statement =>
        Using.resource(statement.executeQuery())(rows => rows.next() && rows.getBoolean(1))
      }
      if (!current) Left(JdbcOperationError.HostDenied)
      else {
        query(connection, "UPDATE test_domain SET mutations = mutations + 1 WHERE singleton = TRUE")(_.executeUpdate()) shouldBe 1
        Right("synthetic-once-only-result")
      }
    }
    def mutations: Int = Using.resource(dataSource.getConnection) { connection =>
      query(connection, "SELECT mutations FROM test_domain") { statement =>
        Using.resource(statement.executeQuery()) { rows => rows.next() shouldBe true; rows.getInt(1) }
      }
    }
    def status(request: OperationInvocation): InvocationStatus = accepted(store.readStatus(request)).status
    def execute(permit: ExecutionPermit): String = transaction(connection => store.executeIn(connection, permit)(mutate))
    def pid(connection: Connection): Int = query(connection, "SELECT pg_backend_pid()") { statement =>
      Using.resource(statement.executeQuery()) { rows => rows.next() shouldBe true; rows.getInt(1) }
    }
    def awaitBlocked(pid: Int): Unit = Using.resource(dataSource.getConnection) { observer =>
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(waitSeconds)
      def blocked: Boolean = query(observer, "SELECT cardinality(pg_blocking_pids(?)) > 0") { statement =>
        statement.setInt(1, pid)
        Using.resource(statement.executeQuery())(rows => rows.next() && rows.getBoolean(1))
      }
      Iterator.continually(blocked).takeWhile(_ => System.nanoTime() < deadline).find(identity) shouldBe Some(true)
    }
    def close(): Unit = Using.resource(rawConnection()) { connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(s"DROP SCHEMA $schema CASCADE"))
    }
  }

  private def withDb[A](run: Fixture => A): A = Using.resource(new Fixture)(run)
  private def withWorkers[A](run: ExecutorService => A): A = {
    val executor = Executors.newFixedThreadPool(3)
    try run(executor)
    finally { executor.shutdownNow(); executor.awaitTermination(waitSeconds, TimeUnit.SECONDS) shouldBe true }
  }
  private def submit[A](executor: ExecutorService)(run: => A): JavaFuture[A] =
    executor.submit(new Callable[A] { def call(): A = run })
  private def result[A](future: JavaFuture[A]): A = future.get(waitSeconds, TimeUnit.SECONDS)
  private def await(latch: CountDownLatch): Unit = latch.await(waitSeconds, TimeUnit.SECONDS) shouldBe true

  /** Inject failure on either side of the server commit, preserving ambiguity
    * from the adapter's point of view while the test knows the true outcome.
    */
  private def lostCommitAcknowledgment(connection: Connection, committedOnServer: Boolean = true): Connection = {
    val loseOnce = new AtomicBoolean(true)
    Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), new InvocationHandler {
      def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
        val args = Option(arguments).getOrElse(Array.empty[Object])
        val loseCommit = method.getName == "commit" && loseOnce.getAndSet(false)
        if (loseCommit && !committedOnServer) throw new SQLException("Synthetic interruption before server commit")
        val value = try method.invoke(connection, args*)
        catch { case error: InvocationTargetException => throw error.getCause }
        if (loseCommit)
          throw new SQLException("Synthetic lost commit acknowledgment")
        value
      }
    }).asInstanceOf[Connection]
  }

  "JDBC operation authorizations" should "admit one callback and domain commit when independent adapters race for one grant" in withDb { db =>
    val grant = db.definition()
    accepted(db.store.issue(grant))
    val start = new CountDownLatch(1)
    withWorkers { workers =>
      val requests = Vector.fill(2)(db.invocation(grant))
      val jobs = requests.map { request => submit(workers) {
        val adapter = db.store
        await(start)
        adapter.reserve(request).map {
          case OperationPreparation.Acquired(permit) => db.transaction(connection => adapter.executeIn(connection, permit)(db.mutate)); true
          case OperationPreparation.Known(_) => false
        }
      } }
      start.countDown()
      val outcomes = jobs.map(result)
      outcomes.count(_ == Right(true)) shouldBe 1
      outcomes.count(_ == Left(JdbcOperationError.CompetingInvocation)) shouldBe 1
      db.calls.get() shouldBe 1
      db.mutations shouldBe 1
      requests.count(request => db.store.readStatus(request).exists(_.status == InvocationStatus.Committed)) shouldBe 1
    }
  }

  it should "bind every identity dimension and canonical intent before providing execution or retry status" in withDb { db =>
    val grant = db.definition()
    val (request, permit) = db.reserved(grant)
    val mismatches = Vector(
      db.binding.copy(subjectId = SubjectId.fromUuid(UUID.randomUUID())),
      db.binding.copy(realmId = RealmId.fromUuid(UUID.randomUUID())),
      db.binding.copy(sessionId = AuthSessionId.fromUuid(UUID.randomUUID())),
      db.binding.copy(securityGeneration = accepted(SecurityGeneration.fromLong(1))),
      db.binding.copy(purpose = OperationPurpose.fromUuid(UUID.randomUUID())),
      db.binding.copy(resourceScope = ResourceScope.fromUuid(UUID.randomUUID()))
    ).map(binding => request.copy(binding = binding)) :+ request.copy(requestDigest = digest("v1:change-preference:expanded"))
    mismatches.foreach { changed =>
      db.store.reserve(changed).isLeft shouldBe true
      db.store.readStatus(changed).isLeft shouldBe true
      db.store.reconcile(changed).isLeft shouldBe true
    }
    db.calls.get() shouldBe 0
    db.status(request) shouldBe InvocationStatus.InProgress
    val competitor = db.invocation(grant)
    db.store.reconcile(competitor) shouldBe Left(JdbcOperationError.CompetingInvocation)
    db.store.readStatus(competitor) shouldBe Left(JdbcOperationError.NotFound)
    db.execute(permit) shouldBe "synthetic-once-only-result"
    db.mutations shouldBe 1
  }

  it should "return durable status after restart and never replay a callback or its result" in withDb { db =>
    val (request, permit) = db.reserved(db.definition())
    accepted(db.store.reserve(request)) shouldBe OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    db.execute(permit) shouldBe "synthetic-once-only-result"
    val restarted = db.store
    accepted(restarted.reserve(request)) shouldBe OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.Committed))
    accepted(restarted.readStatus(request)).toString should not include "synthetic-once-only-result"
    intercept[JdbcOperationException](db.execute(permit)).error shouldBe JdbcOperationError.PermitUsed
    db.calls.get() shouldBe 1
    db.mutations shouldBe 1
  }

  it should "roll back domain SQL and committed status when a callback returns a typed failure" in withDb { db =>
    val (request, permit) = db.reserved(db.definition())
    val error = intercept[JdbcOperationException] {
      db.transaction { connection =>
        db.store.executeIn(connection, permit) { (sameConnection, now) =>
          sameConnection should be theSameInstanceAs connection
          db.mutate(sameConnection, now)
          Left(JdbcOperationError.HostDenied)
        }
      }
    }
    error.error shouldBe JdbcOperationError.HostDenied
    db.mutations shouldBe 0
    db.status(request) shouldBe InvocationStatus.InProgress
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    accepted(db.store.reserve(request)) shouldBe OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.NotCommitted))
  }

  it should "roll back a thrown callback and recheck current host authority in the execution transaction" in withDb { db =>
    val (request, permit) = db.reserved(db.definition())
    intercept[JdbcOperationException] {
      db.transaction(connection => db.store.executeIn(connection, permit) { (sameConnection, now) =>
        db.mutate(sameConnection, now)
        throw new IllegalStateException("Synthetic callback failure")
      })
    }.error shouldBe JdbcOperationError.StorageFailure
    db.mutations shouldBe 0
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    val next = db.invocation(db.definition().copy(id = request.grantId))
    val freshPermit = db.permit(accepted(db.store.reserve(next)))
    db.transaction(connection => db.query(connection, "UPDATE test_authority SET valid = FALSE")(_.executeUpdate()))
    intercept[JdbcOperationException](db.execute(freshPermit)).error shouldBe JdbcOperationError.HostDenied
    db.mutations shouldBe 0
  }

  it should "require a caller transaction before invoking the host callback" in withDb { db =>
    val (_, permit) = db.reserved(db.definition())
    Using.resource(db.dataSource.getConnection) { connection =>
      intercept[JdbcOperationException](db.store.executeIn(connection, permit)(db.mutate)).error shouldBe JdbcOperationError.TransactionRequired
    }
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "fence a crashed reservation before releasing verified authority and preserve the old terminal invocation" in withDb { db =>
    val grant = db.definition()
    val (request, stalePermit) = db.reserved(grant)
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    intercept[JdbcOperationException](db.execute(stalePermit)).error shouldBe JdbcOperationError.NotReserved
    val next = db.invocation(grant)
    db.execute(db.permit(accepted(db.store.reserve(next)))) shouldBe "synthetic-once-only-result"
    db.status(request) shouldBe InvocationStatus.NotCommitted
    db.status(next) shouldBe InvocationStatus.Committed
    db.calls.get() shouldBe 1
  }

  it should "reserve conditional attempts directly and burn failed attempts instead of creating reusable authority" in withDb { db =>
    val invalid = db.definition()
    db.store.prepareConditionalAttempt(invalid, db.invocation(invalid)) shouldBe Left(JdbcOperationError.DefinitionConflict)
    val grant = db.definition(ReservationPolicy.ConsumeOnAttempt)
    val request = db.invocation(grant)
    val permit = db.permit(accepted(db.store.prepareConditionalAttempt(grant, request)))
    accepted(db.store.prepareConditionalAttempt(grant, request)) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    intercept[JdbcOperationException](db.execute(permit)).error shouldBe JdbcOperationError.NotReserved
    db.store.reserve(db.invocation(grant)) shouldBe Left(JdbcOperationError.DefinitionConflict)
    db.store.prepareConditionalAttempt(grant, db.invocation(grant)) shouldBe Left(JdbcOperationError.Burned)
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "recover a lost reservation acknowledgment without returning a permit or admitting another attempt" in withDb { db =>
    val grant = db.definition()
    accepted(db.store.issue(grant))
    val request = db.invocation(grant)
    val lostReplySource = new TestDataSource {
      def getConnection: Connection = lostCommitAcknowledgment(db.dataSource.getConnection)
    }
    val interrupted = new JdbcOperationAuthorizations(lostReplySource, () => db.clock.get())
    interrupted.reserve(request) shouldBe Left(JdbcOperationError.CommitUnknown)
    db.status(request) shouldBe InvocationStatus.InProgress
    accepted(db.store.reserve(request)) shouldBe OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "recover committed domain state after the execution commit acknowledgment is lost" in withDb { db =>
    val (request, permit) = db.reserved(db.definition())
    Using.resource(lostCommitAcknowledgment(db.dataSource.getConnection)) { connection =>
      connection.setAutoCommit(false)
      db.store.executeIn(connection, permit)(db.mutate) shouldBe "synthetic-once-only-result"
      intercept[SQLException](connection.commit()).getMessage shouldBe "Synthetic lost commit acknowledgment"
      connection.rollback()
    }
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.Committed
    accepted(db.store.reserve(request)) shouldBe OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.Committed))
    db.calls.get() shouldBe 1
    db.mutations shouldBe 1
  }

  it should "burn and tombstone a conditional attempt whose reservation transaction never committed" in withDb { db =>
    val grant = db.definition(ReservationPolicy.ConsumeOnAttempt)
    val request = db.invocation(grant)
    val interruptedSource = new TestDataSource {
      def getConnection: Connection = lostCommitAcknowledgment(db.dataSource.getConnection, committedOnServer = false)
    }
    val interrupted = new JdbcOperationAuthorizations(interruptedSource, () => db.clock.get())
    interrupted.prepareConditionalAttempt(grant, request) shouldBe Left(JdbcOperationError.CommitUnknown)
    db.store.readStatus(request) shouldBe Left(JdbcOperationError.NotFound)
    accepted(db.store.reconcileConditionalAttempt(grant, request)).status shouldBe InvocationStatus.NotCommitted
    accepted(db.store.prepareConditionalAttempt(grant, request)) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.NotCommitted))
    db.store.prepareConditionalAttempt(grant, db.invocation(grant)) shouldBe Left(JdbcOperationError.Burned)
    db.status(request) shouldBe InvocationStatus.NotCommitted
    Using.resource(db.dataSource.getConnection) { connection =>
      db.query(connection, "SELECT state, conditional FROM spoonbill_operation_grant WHERE grant_id = ?") { statement =>
        statement.setObject(1, UUID.fromString(grant.id.toString))
        Using.resource(statement.executeQuery()) { rows =>
          rows.next() shouldBe true
          rows.getString(1) shouldBe "Invalidated"
          rows.getBoolean(2) shouldBe true
        }
      }
    }
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "deduplicate simultaneous retries of the same invocation across adapters" in withDb { db =>
    val grant = db.definition()
    accepted(db.store.issue(grant))
    val request = db.invocation(grant)
    val start = new CountDownLatch(1)
    withWorkers { workers =>
      val jobs = Vector.fill(2) { submit(workers) {
        val adapter = db.store
        await(start)
        accepted(adapter.reserve(request)) match {
          case OperationPreparation.Acquired(permit) =>
            db.transaction(connection => adapter.executeIn(connection, permit)(db.mutate))
            true
          case OperationPreparation.Known(record) =>
            Set(InvocationStatus.InProgress, InvocationStatus.Committed) should contain(record.status)
            false
        }
      } }
      start.countDown()
      jobs.map(result).count(identity) shouldBe 1
      db.calls.get() shouldBe 1
      db.mutations shouldBe 1
      db.status(request) shouldBe InvocationStatus.Committed
    }
  }

  for (commitWriter <- Vector(true, false)) {
    it should s"wait for a held writer to ${if (commitWriter) "commit" else "roll back"} before reconciling" in withDb { db =>
      val (request, permit) = db.reserved(db.definition())
      val staged = new CountDownLatch(1)
      val finish = new CountDownLatch(1)
      val reconcilerConnected = new CountDownLatch(1)
      val reconcilerPid = new AtomicInteger(0)
      val observedSource = new TestDataSource {
        def getConnection: Connection = {
          val connection = db.dataSource.getConnection
          reconcilerPid.set(db.pid(connection))
          reconcilerConnected.countDown()
          connection
        }
      }
      withWorkers { workers =>
        val writing = submit(workers) {
          Using.resource(db.dataSource.getConnection) { connection =>
            connection.setAutoCommit(false)
            try {
              db.store.executeIn(connection, permit)(db.mutate)
              staged.countDown()
              // Hold the caller transaction after the callback; ledger and
              // domain changes remain uncommitted while reconciliation starts.
              await(finish)
              if (commitWriter) connection.commit() else connection.rollback()
            } finally connection.rollback()
          }
        }
        try {
          await(staged)
          val recovering = submit(workers) {
            new JdbcOperationAuthorizations(observedSource, () => db.clock.get()).reconcile(request)
          }
          await(reconcilerConnected)
          db.awaitBlocked(reconcilerPid.get())
          recovering.isDone shouldBe false
          finish.countDown()
          result(writing)
          accepted(result(recovering)).status shouldBe
            (if (commitWriter) InvocationStatus.Committed else InvocationStatus.NotCommitted)
          db.mutations shouldBe (if (commitWriter) 1 else 0)
          db.calls.get() shouldBe 1
        } finally finish.countDown()
      }
    }
  }

  it should "sample expiry after a real ledger lock wait before calling the host" in withDb { db =>
    val grant = db.definition()
    val (request, permit) = db.reserved(grant)
    Using.resource(db.dataSource.getConnection) { blocker =>
      blocker.setAutoCommit(false)
      db.query(blocker, "SELECT grant_id FROM spoonbill_operation_grant WHERE grant_id = ? FOR UPDATE") { statement =>
        statement.setObject(1, UUID.fromString(grant.id.toString))
        Using.resource(statement.executeQuery())(_.next() shouldBe true)
      }
      val connected = new CountDownLatch(1)
      val writerPid = new AtomicInteger(0)
      withWorkers { workers =>
        val writing = submit(workers) {
          intercept[JdbcOperationException] {
            db.transaction { connection =>
              writerPid.set(db.pid(connection))
              connected.countDown()
              db.store.executeIn(connection, permit)(db.mutate)
            }
          }.error
        }
        try {
          await(connected)
          db.awaitBlocked(writerPid.get())
          db.clock.set(grant.expiresAt)
          blocker.commit()
          result(writing) shouldBe JdbcOperationError.Expired
          db.calls.get() shouldBe 0
          db.mutations shouldBe 0
          db.status(request) shouldBe InvocationStatus.InProgress
          accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
          db.store.reserve(db.invocation(grant)) shouldBe Left(JdbcOperationError.Expired)
        } finally blocker.rollback()
      }
    }
  }

  it should "roll back host mutations when expiry occurs during a host lock wait" in withDb { db =>
    val grant = db.definition()
    val (request, permit) = db.reserved(grant)
    Using.resource(db.dataSource.getConnection) { blocker =>
      blocker.setAutoCommit(false)
      db.query(blocker, "SELECT valid FROM test_authority FOR UPDATE") { statement =>
        Using.resource(statement.executeQuery())(_.next() shouldBe true)
      }
      val connected = new CountDownLatch(1)
      val writerPid = new AtomicInteger(0)
      withWorkers { workers =>
        val writing = submit(workers) {
          intercept[JdbcOperationException] {
            db.transaction { connection =>
              writerPid.set(db.pid(connection))
              connected.countDown()
              db.store.executeIn(connection, permit)(db.mutate)
            }
          }.error
        }
        try {
          await(connected)
          db.awaitBlocked(writerPid.get())
          db.clock.set(grant.expiresAt)
          blocker.commit()
          result(writing) shouldBe JdbcOperationError.Expired
          db.calls.get() shouldBe 1
          db.mutations shouldBe 0
          db.status(request) shouldBe InvocationStatus.InProgress
        } finally blocker.rollback()
      }
    }
  }

  it should "retain unresolved invocation identities through subject invalidation and reconcile after expiry" in withDb { db =>
    val first = db.definition()
    val second = db.definition()
    val (reservedRequest, reservedPermit) = db.reserved(first)
    val (unknownRequest, _) = db.reserved(second)
    // Seed a durable unknown outcome as recovered/imported state. No elapsed
    // time, revocation, or adapter restart may delete its identity.
    db.transaction { connection =>
      db.query(connection, "UPDATE spoonbill_operation_grant SET state = 'Unknown' WHERE grant_id = ?") { statement =>
        statement.setObject(1, UUID.fromString(second.id.toString)); statement.executeUpdate() shouldBe 1
      }
      db.query(connection, "UPDATE spoonbill_operation_invocation SET status = 'Unknown' WHERE invocation_id = ?") { statement =>
        statement.setObject(1, UUID.fromString(unknownRequest.invocationId.toString)); statement.executeUpdate() shouldBe 1
      }
      db.store.invalidateSubject(connection, db.binding.realmId, db.binding.subjectId, db.binding.securityGeneration)
    }
    db.clock.set(initialTime.plusSeconds(3600))
    db.status(reservedRequest) shouldBe InvocationStatus.InProgress
    db.status(unknownRequest) shouldBe InvocationStatus.Unknown
    intercept[JdbcOperationException](db.execute(reservedPermit)).error shouldBe JdbcOperationError.Revoked
    accepted(db.store.reconcile(reservedRequest)).status shouldBe InvocationStatus.NotCommitted
    accepted(db.store.reconcile(unknownRequest)).status shouldBe InvocationStatus.NotCommitted
    db.store.reserve(db.invocation(first)) shouldBe Left(JdbcOperationError.Revoked)
    db.store.reserve(db.invocation(second)) shouldBe Left(JdbcOperationError.Revoked)
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "commit an operation that invalidates its own generation while retaining sibling reservations" in withDb { db =>
    val (request, permit) = db.reserved(db.definition())
    val (sibling, siblingPermit) = db.reserved(db.definition())
    db.transaction { connection =>
      db.store.executeIn(connection, permit) { (sameConnection, now) =>
        val value = db.mutate(sameConnection, now)
        db.store.invalidateSubject(sameConnection, db.binding.realmId, db.binding.subjectId, db.binding.securityGeneration)
        value
      }
    }
    db.status(request) shouldBe InvocationStatus.Committed
    db.status(sibling) shouldBe InvocationStatus.InProgress
    intercept[JdbcOperationException](db.execute(siblingPermit)).error shouldBe JdbcOperationError.Revoked
    accepted(db.store.reconcile(sibling)).status shouldBe InvocationStatus.NotCommitted
    db.mutations shouldBe 1
  }

  it should "fail capacity closed without evicting terminal or expired replay records" in withDb { db =>
    val limited = new JdbcOperationAuthorizations(db.dataSource, () => db.clock.get(), maxGrantsPerSubject = 2, maxInvocationsPerSubject = 1)
    val first = db.definition()
    val second = db.definition()
    accepted(limited.issue(first))
    accepted(limited.issue(second))
    limited.issue(db.definition()) shouldBe Left(JdbcOperationError.CapacityExceeded)
    val request = db.invocation(first)
    db.permit(accepted(limited.reserve(request)))
    accepted(limited.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    limited.reserve(db.invocation(first)) shouldBe Left(JdbcOperationError.CapacityExceeded)
    limited.reserve(db.invocation(second)) shouldBe Left(JdbcOperationError.CapacityExceeded)
    db.clock.set(initialTime.plusSeconds(3600))
    limited.issue(db.definition()) shouldBe Left(JdbcOperationError.CapacityExceeded)
    accepted(limited.reserve(request)) shouldBe OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.NotCommitted))
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  private def measuredTransaction[A](source: DataSource)(run: Connection => A): A =
    Using.resource(source.getConnection) { connection =>
      connection.setAutoCommit(false)
      try { val value = run(connection); connection.commit(); value }
      catch { case error: Throwable => connection.rollback(); throw error }
    }

  it should "issue and reserve verified exact intent in eight executions and one commit with status-only retries" in withDb { db =>
    val probe = new PerformanceProbe(db.dataSource)
    val store = new JdbcOperationAuthorizations(probe.dataSource, () => db.clock.get())
    val grant = db.definition()
    val request = db.invocation(grant)
    val (prepared, sample) = probe.measure(store.issueAndReserve(grant, request))
    val permit = db.permit(accepted(prepared))
    sample.counts.sqlExecutions should be <= 8L
    sample.counts.commits shouldBe 1L
    sample.counts.committed shouldBe 1L
    sample.counts.connections shouldBe 1L
    sample.activeConnectionsAfter shouldBe 0L
    accepted(store.issueAndReserve(grant, request)) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    db.calls.get() shouldBe 0
    db.execute(permit) shouldBe "synthetic-once-only-result"
    accepted(store.issueAndReserve(grant, request)) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.Committed))
    db.calls.get() shouldBe 1
    db.mutations shouldBe 1
    // The existing issue API still supports choosing the exact invocation later.
    val previouslyIssued = db.definition()
    accepted(store.issue(previouslyIssued))
    db.permit(accepted(store.issueAndReserve(previouslyIssued, db.invocation(previouslyIssued))))
  }

  it should "reject mismatched verified definitions global invocation reuse and conditional promotion" in withDb { db =>
    val grant = db.definition()
    val request = db.invocation(grant)
    db.store.issueAndReserve(grant, request.copy(grantId = db.definition().id)) shouldBe Left(JdbcOperationError.DefinitionConflict)
    db.store.issueAndReserve(grant, request.copy(binding = request.binding.copy(subjectId = SubjectId.fromUuid(UUID.randomUUID())))) shouldBe
      Left(JdbcOperationError.DefinitionConflict)
    db.permit(accepted(db.store.issueAndReserve(grant, request)))
    db.store.issueAndReserve(grant.copy(expiresAt = grant.expiresAt.plusSeconds(1)), request) shouldBe Left(JdbcOperationError.DefinitionConflict)
    db.store.issueAndReserve(grant.copy(policy = ReservationPolicy.ConsumeOnAttempt), request) shouldBe Left(JdbcOperationError.DefinitionConflict)
    db.store.issueAndReserve(grant, request.copy(requestDigest = digest("another-intent"))) shouldBe Left(JdbcOperationError.InvocationConflict)
    val another = db.definition()
    db.store.issueAndReserve(another, db.invocation(another).copy(invocationId = request.invocationId)) shouldBe
      Left(JdbcOperationError.InvocationConflict)
    val conditional = db.definition(ReservationPolicy.ConsumeOnAttempt)
    val conditionalRequest = db.invocation(conditional)
    db.permit(accepted(db.store.prepareConditionalAttempt(conditional, conditionalRequest)))
    db.store.issueAndReserve(conditional, conditionalRequest) shouldBe Left(JdbcOperationError.DefinitionConflict)
    db.store.prepareConditionalAttempt(grant.copy(policy = ReservationPolicy.ConsumeOnAttempt), request) shouldBe
      Left(JdbcOperationError.DefinitionConflict)
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "retain verified reservation policy after a rolled back business transaction" in withDb { db =>
    ReservationPolicy.values.foreach { policy =>
      val grant = db.definition(policy)
      val request = db.invocation(grant)
      val permit = db.permit(accepted(db.store.issueAndReserve(grant, request)))
      intercept[JdbcOperationException] {
        db.transaction { connection =>
          db.store.executeIn(connection, permit) { (sameConnection, now) =>
            db.mutate(sameConnection, now)
            Left(JdbcOperationError.HostDenied)
          }
        }
      }.error shouldBe JdbcOperationError.HostDenied
      db.mutations shouldBe 0
      accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
      accepted(db.store.issueAndReserve(grant, request)) shouldBe
        OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.NotCommitted))
      policy match {
        case ReservationPolicy.ReleaseAfterDefiniteFailure =>
          db.permit(accepted(db.store.issueAndReserve(grant, db.invocation(grant))))
        case ReservationPolicy.ConsumeOnAttempt =>
          db.store.issueAndReserve(grant, db.invocation(grant)) shouldBe Left(JdbcOperationError.Burned)
      }
    }
    db.calls.get() shouldBe 2
    db.mutations shouldBe 0
  }

  it should "return no verified execution permit after losing the combined reservation commit acknowledgment" in withDb { db =>
    val grant = db.definition()
    val request = db.invocation(grant)
    val lostReplySource = new TestDataSource {
      def getConnection: Connection = lostCommitAcknowledgment(db.dataSource.getConnection)
    }
    val interrupted = new JdbcOperationAuthorizations(lostReplySource, () => db.clock.get())
    interrupted.issueAndReserve(grant, request) shouldBe Left(JdbcOperationError.CommitUnknown)
    db.status(request) shouldBe InvocationStatus.InProgress
    accepted(db.store.issueAndReserve(grant, request)) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    accepted(db.store.reconcile(request)).status shouldBe InvocationStatus.NotCommitted
    db.calls.get() shouldBe 0
    db.mutations shouldBe 0
  }

  it should "fail combined verified preparation closed on expiry and capacity without partial insertion" in withDb { db =>
    val limited = new JdbcOperationAuthorizations(db.dataSource, () => db.clock.get(), maxGrantsPerSubject = 1, maxInvocationsPerSubject = 1)
    val expired = db.definition().copy(expiresAt = initialTime)
    val expiredRequest = db.invocation(expired)
    limited.issueAndReserve(expired, expiredRequest) shouldBe Left(JdbcOperationError.Expired)
    db.store.readStatus(expiredRequest) shouldBe Left(JdbcOperationError.NotFound)
    val grant = db.definition()
    val request = db.invocation(grant)
    db.permit(accepted(limited.issueAndReserve(grant, request)))
    val excess = db.definition()
    val excessRequest = db.invocation(excess)
    limited.issueAndReserve(excess, excessRequest) shouldBe Left(JdbcOperationError.CapacityExceeded)
    db.store.readStatus(excessRequest) shouldBe Left(JdbcOperationError.NotFound)
    accepted(limited.issueAndReserve(grant, request)) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    db.calls.get() shouldBe 0
  }

  it should "bound ledger SQL executions and commits without replaying host mutations" in withDb { db =>
    val probe = new PerformanceProbe(db.dataSource)
    val store = new JdbcOperationAuthorizations(probe.dataSource, () => db.clock.get())
    def budget[A](maximum: Long)(run: => A): A = {
      val (value, sample) = probe.measure(run)
      sample.counts.sqlExecutions should be > 0L
      sample.counts.sqlExecutions should be <= maximum
      sample.counts.commits shouldBe 1L
      sample.counts.committed shouldBe 1L
      sample.counts.rollbacks shouldBe 0L
      sample.counts.connections shouldBe 1L
      sample.activeConnectionsBefore shouldBe 0L
      sample.activeConnectionsAfter shouldBe 0L
      value
    }
    val grant = db.definition()
    budget(4)(accepted(store.issue(grant)))
    budget(2)(accepted(store.issue(grant)))
    val request = db.invocation(grant)
    val permit = budget(7)(db.permit(accepted(store.reserve(request))))
    budget(4)(accepted(store.reserve(request))) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.InProgress))
    db.calls.get() shouldBe 0
    // Seven ledger executions plus the fixture's authority read and domain write.
    budget(9)(measuredTransaction(probe.dataSource)(connection => store.executeIn(connection, permit)(db.mutate))) shouldBe
      "synthetic-once-only-result"
    budget(4)(accepted(store.reserve(request))) shouldBe
      OperationPreparation.Known(DurableInvocationRecord(request, InvocationStatus.Committed))
    budget(4)(accepted(store.readStatus(request))).status shouldBe InvocationStatus.Committed
    budget(4)(accepted(store.reconcile(request))).status shouldBe InvocationStatus.Committed
    db.calls.get() shouldBe 1
    db.mutations shouldBe 1

    val (unexecuted, _) = db.reserved(db.definition())
    budget(6)(accepted(store.reconcile(unexecuted))).status shouldBe InvocationStatus.NotCommitted
    budget(4)(accepted(store.reconcile(unexecuted))).status shouldBe InvocationStatus.NotCommitted
    val conditional = db.definition(ReservationPolicy.ConsumeOnAttempt)
    budget(8)(db.permit(accepted(store.prepareConditionalAttempt(conditional, db.invocation(conditional)))))
    db.calls.get() shouldBe 1
    db.mutations shouldBe 1
  }

  for (population <- Vector(1, 32, 256)) {
    it should s"invalidate $population retained grants with at most two SQL executions" in withDb { db =>
      Vector.fill(population)(db.definition()).foreach(grant => accepted(db.store.issue(grant)))
      val probe = new PerformanceProbe(db.dataSource)
      val store = new JdbcOperationAuthorizations(probe.dataSource, () => db.clock.get())
      val (_, sample) = probe.measure {
        measuredTransaction(probe.dataSource) { connection =>
          store.invalidateSubject(connection, db.binding.realmId, db.binding.subjectId, db.binding.securityGeneration)
        }
      }
      // Before bulk invalidation this path executed 2 + population statements:
      // subject lock, SELECT, and one UPDATE for every retained grant.
      sample.counts.sqlExecutions should be <= 2L
      sample.counts.commits shouldBe 1L
      sample.counts.committed shouldBe 1L
      sample.activeConnectionsAfter shouldBe 0L
      Using.resource(db.dataSource.getConnection) { connection =>
        db.query(connection, "SELECT count(*) FROM spoonbill_operation_grant WHERE state='Invalidated' AND invalidation='Revoked'") { statement =>
          Using.resource(statement.executeQuery()) { rows => rows.next() shouldBe true; rows.getInt(1) shouldBe population }
        }
      }
    }
  }

  it should "match pure revocation for every grant state and preserve subject realm and generation boundaries" in withDb { db =>
    import OperationAuthorizationState.*
    val owner = InvocationId.fromUuid(UUID.randomUUID())
    val states = Vector[OperationAuthorizationState](Available, Committed(owner)) ++
      Vector(None, Some(GrantInvalidation.Expired), Some(GrantInvalidation.Revoked), Some(GrantInvalidation.Burned))
        .flatMap(reason => Vector(Reserved(owner, reason), Unknown(owner, reason))) ++
      GrantInvalidation.values.toVector.map(Invalidated.apply)
    val unaffectedBindings = Vector(
      db.binding.copy(realmId = RealmId.fromUuid(UUID.randomUUID())),
      db.binding.copy(subjectId = SubjectId.fromUuid(UUID.randomUUID())),
      db.binding.copy(securityGeneration = accepted(SecurityGeneration.fromLong(1)))
    )
    val matching = states.map(state => db.definition() -> state)
    val unaffected = unaffectedBindings.map(binding => db.definition().copy(binding = binding) -> Available)
    val all = matching ++ unaffected
    all.foreach { case (definition, _) => accepted(db.store.issue(definition)) }
    def storedState(state: OperationAuthorizationState): (String, Option[UUID], Option[String]) = state match {
      case Available => ("Available", None, None)
      case Reserved(id, reason) => ("Reserved", Some(id.toUuid), reason.map(_.toString))
      case Unknown(id, reason) => ("Unknown", Some(id.toUuid), reason.map(_.toString))
      case Committed(id) => ("Committed", Some(id.toUuid), None)
      case Invalidated(reason) => ("Invalidated", None, Some(reason.toString))
    }
    db.transaction { connection =>
      all.foreach { case (definition, state) =>
        val (name, invocation, invalidation) = storedState(state)
        db.query(connection, "UPDATE spoonbill_operation_grant SET state=?,owner_invocation=?,invalidation=? WHERE grant_id=?") { statement =>
          statement.setString(1, name)
          invocation.fold(statement.setNull(2, java.sql.Types.OTHER))(statement.setObject(2, _))
          invalidation.fold(statement.setNull(3, java.sql.Types.VARCHAR))(statement.setString(3, _))
          statement.setObject(4, definition.id.toUuid)
          statement.executeUpdate() shouldBe 1
        }
      }
      db.store.invalidateSubject(connection, db.binding.realmId, db.binding.subjectId, db.binding.securityGeneration)
    }
    Using.resource(db.dataSource.getConnection) { connection =>
      all.foreach { case (definition, state) =>
        val original = OperationAuthorization(definition.id, definition.binding, definition.expiresAt, definition.policy, state)
        val expected = if (definition.binding == db.binding) original.revoke.state else state
        db.query(connection, "SELECT state,owner_invocation,invalidation FROM spoonbill_operation_grant WHERE grant_id=?") { statement =>
          statement.setObject(1, definition.id.toUuid)
          Using.resource(statement.executeQuery()) { rows =>
            rows.next() shouldBe true
            (rows.getString(1), Option(rows.getObject(2, classOf[UUID])), Option(rows.getString(3))) shouldBe storedState(expected)
            rows.next() shouldBe false
          }
        }
      }
    }
  }

  it should "retain measured allocations when a tracked worker terminates" in {
    val source = new TestDataSource {
      def getConnection: Connection = throw new AssertionError("Allocation measurement must not open a connection")
    }
    val probe = new PerformanceProbe(source)
    val ready = new CountDownLatch(1)
    val release = new CountDownLatch(1)
    val retained = new AtomicReference[Array[Byte]]()
    val worker = new Thread(() => {
      ready.countDown()
      if (release.await(waitSeconds, TimeUnit.SECONDS)) retained.set(new Array[Byte](8 * 1024 * 1024))
    })
    worker.start()
    try {
      ready.await(waitSeconds, TimeUnit.SECONDS) shouldBe true
      val beginning = probe.start()
      release.countDown()
      worker.join(waitSeconds * 1000)
      worker.isAlive shouldBe false
      val sample = probe.finish(beginning)
      retained.get().length shouldBe 8 * 1024 * 1024
      sample.allocationTrackedThreadsLost should be >= 1L
      sample.allocatedBytes should be >= (8L * 1024 * 1024)
      sample.counts.connections shouldBe 0L
    } finally {
      release.countDown()
      worker.join(waitSeconds * 1000)
    }
  }

  it should "count real JDBC executions batches failures and closed connections independently" in withDb { db =>
    val probe = new PerformanceProbe(db.dataSource)
    val other = new PerformanceProbe(db.dataSource)
    val otherStart = other.start()
    val (_, sample) = probe.measure {
      Using.resource(probe.dataSource.getConnection) { connection =>
        connection.setAutoCommit(false)
        Using.resource(connection.createStatement()) { statement =>
          statement.addBatch("UPDATE test_domain SET mutations=mutations+1")
          statement.addBatch("UPDATE test_domain SET mutations=mutations+1")
          statement.executeBatch().toVector shouldBe Vector(1, 1)
          statement.addBatch("UPDATE test_domain SET mutations=mutations+100")
          statement.clearBatch()
          statement.addBatch("UPDATE test_domain SET mutations=mutations+1")
          statement.executeLargeBatch().toVector shouldBe Vector(1L)
          intercept[SQLException](statement.executeQuery("SELECT absent_performance_test_column"))
        }
        connection.rollback()
        connection.close()
        connection.close()
      }
    }
    val idle = other.finish(otherStart)
    idle.counts.sqlExecutions shouldBe 0L
    idle.counts.connections shouldBe 0L
    sample.counts.sqlExecutions shouldBe 3L
    sample.counts.batches shouldBe 2L
    sample.counts.batchRows shouldBe 3L
    sample.counts.rollbacks shouldBe 1L
    sample.counts.commits shouldBe 0L
    sample.counts.autoCommitChanges shouldBe 1L
    sample.counts.connections shouldBe 1L
    sample.activeConnectionsAfter shouldBe 0L
    // Serialized results contain fixed metric names and numbers only.
    sample.jsonFields should not include "test_domain"
    sample.jsonFields should not include "absent_performance_test_column"
    db.mutations shouldBe 0

    val failedCommitSource = new TestDataSource {
      def getConnection: Connection = lostCommitAcknowledgment(db.dataSource.getConnection, committedOnServer = false)
    }
    val measuredFailureSource = probe.decorate(failedCommitSource)
    val (_, failedCommit) = probe.measure {
      Using.resource(measuredFailureSource.getConnection) { connection =>
        connection.setAutoCommit(false)
        Using.resource(connection.prepareStatement("SELECT ?::text")) { statement =>
          statement.setString(1, "synthetic-sensitive-parameter")
          Using.resource(statement.executeQuery())(_.next() shouldBe true)
        }
        intercept[SQLException](connection.commit())
        connection.rollback()
      }
    }
    failedCommit.counts.sqlExecutions shouldBe 1L
    failedCommit.counts.commits shouldBe 1L
    failedCommit.counts.committed shouldBe 0L
    failedCommit.counts.rollbacks shouldBe 1L
    failedCommit.activeConnectionsAfter shouldBe 0L
    failedCommit.jsonFields should not include "synthetic-sensitive-parameter"
  }
}
