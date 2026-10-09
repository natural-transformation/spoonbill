package spoonbill.security.jdbc

import java.io.PrintWriter
import java.security.{MessageDigest, SecureRandom}
import java.sql.{Connection, DriverManager, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{CompletableFuture, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.Using
import spoonbill.performance.PerformanceProbe

/** Opt in with a disposable PostgreSQL JDBC URL. No in-memory SQL substitute is
  * used: row locking, restart recovery and transaction rollback are exercised on
  * real independent JDBC connections. Tests create namespaced fixtures only.
  */
class JdbcBrowserSessionsSpec extends AsyncFlatSpec with Matchers {
  private val initialTime = Instant.parse("2026-10-05T12:00:00Z")
  private def accepted[A](result: Either[JdbcAuthError, A]): A = result.fold(error => fail(error.toString), identity)
  private def digest(): Digest256 = {
    val random = new Array[Byte](32)
    new SecureRandom().nextBytes(random)
    accepted(Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(random)))
  }

  private class Fixture {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Set SPOONBILL_JDBC_TEST_URL to a disposable PostgreSQL database"))
    val dataSource: DataSource = new DataSource {
      private def properties: Properties = {
        val result = new Properties()
        sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(result.setProperty("user", _))
        sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(result.setProperty("password", _))
        result
      }
      def getConnection: Connection = DriverManager.getConnection(url, properties)
      def getConnection(user: String, password: String): Connection = DriverManager.getConnection(url, user, password)
      def getLogWriter: PrintWriter = throw new SQLFeatureNotSupportedException()
      def setLogWriter(writer: PrintWriter): Unit = throw new SQLFeatureNotSupportedException()
      def getLoginTimeout: Int = 0
      def setLoginTimeout(seconds: Int): Unit = throw new SQLFeatureNotSupportedException()
      def getParentLogger: Logger = Logger.getLogger("spoonbill.jdbc.test")
      def isWrapperFor(kind: Class[?]): Boolean = false
      def unwrap[T](kind: Class[T]): T = throw new SQLException("Not a wrapper")
    }
    val realm = "test-" + UUID.randomUUID().toString
    val clock = new AtomicReference(initialTime)
    val hooks: HostSessionHooks = new HostSessionHooks {
      def isCurrent(connection: Connection, sessionId: UUID, now: Instant): Boolean =
        Using.resource(connection.prepareStatement("SELECT valid FROM spoonbill_jdbc_test_host WHERE session_id = ? FOR UPDATE")) { query =>
          query.setObject(1, sessionId)
          Using.resource(query.executeQuery())(rows => rows.next() && rows.getBoolean(1))
        }
      def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit =
        Using.resource(connection.prepareStatement("UPDATE spoonbill_jdbc_test_host SET acknowledged = TRUE WHERE session_id = ? AND completion_id = ?")) { query =>
          query.setObject(1, sessionId); query.setObject(2, completionId)
          if (query.executeUpdate() != 1) throw new IllegalStateException("Missing host fixture")
        }
    }
    def store: JdbcBrowserSessions = new JdbcBrowserSessions(dataSource, realm, "session", () => clock.get(), hooks)
    Using.resource(dataSource.getConnection) { connection =>
      store.initialize(connection)
      Using.resource(connection.createStatement())(_.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_jdbc_test_host (
        session_id UUID PRIMARY KEY, completion_id UUID NOT NULL, valid BOOLEAN NOT NULL,
        acknowledged BOOLEAN NOT NULL DEFAULT FALSE)"""))
    }

    def prepared(binding: Digest256, generation: Long): PreparedBrowserSession =
      PreparedBrowserSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), binding, digest(), generation,
        initialTime.plusSeconds(600), initialTime.plusSeconds(60))

    def insertHost(connection: Connection, prepared: PreparedBrowserSession): Unit =
      Using.resource(connection.prepareStatement("INSERT INTO spoonbill_jdbc_test_host (session_id, completion_id, valid) VALUES (?, ?, TRUE)")) { query =>
        query.setObject(1, prepared.hostSessionId); query.setObject(2, prepared.hostCompletionId); query.executeUpdate(); ()
      }

    def persist(prepared: PreparedBrowserSession): PreparedReceipt =
      Using.resource(dataSource.getConnection) { connection =>
        connection.setAutoCommit(false)
        try {
          insertHost(connection, prepared)
          val result = store.prepare(connection, prepared)
          connection.commit()
          result
        } catch { case error: Throwable => connection.rollback(); throw error }
      }

    def persistNew(prepared: PreparedBrowserSession): PreparedReceipt =
      Using.resource(dataSource.getConnection) { connection =>
        connection.setAutoCommit(false)
        try {
          insertHost(connection, prepared)
          val result = store.prepareNew(connection, prepared)
          connection.commit()
          result
        } catch { case error: Throwable => connection.rollback(); throw error }
      }

    def browserCounts: Vector[Long] = Using.resource(dataSource.getConnection) { connection =>
      Vector("spoonbill_browser_session", "spoonbill_browser_completion").map { table =>
        Using.resource(connection.prepareStatement(s"SELECT count(*) FROM $table WHERE realm=? AND cookie_namespace='session'")) { query =>
          query.setString(1, realm)
          Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
        }
      }
    }

    def hostFlag(sessionId: UUID, column: String): Option[Boolean] = {
      require(Set("valid", "acknowledged").contains(column))
      Using.resource(dataSource.getConnection) { connection =>
        Using.resource(connection.prepareStatement(s"SELECT $column FROM spoonbill_jdbc_test_host WHERE session_id = ?")) { query =>
          query.setObject(1, sessionId)
          Using.resource(query.executeQuery())(rows => if (rows.next()) Some(rows.getBoolean(1)) else None)
        }
      }
    }
  }

  private def concurrent[A](operations: Vector[() => A]): Future[Vector[A]] = {
    val start = Promise[Unit]()
    val running = operations.map(operation => start.future.map(_ => blocking(operation()))(ExecutionContext.global))
    start.success(())
    Future.sequence(running)
  }

  private def bounded[A](promise: Promise[A]): Future[A] = {
    CompletableFuture.delayedExecutor(10, TimeUnit.SECONDS).execute(() => {
      promise.tryFailure(new IllegalStateException("Synthetic preparation coordination deadline")); ()
    })
    promise.future
  }

  private def backendId(connection: Connection): Int = Using.resource(connection.createStatement()) { query =>
    Using.resource(query.executeQuery("SELECT pg_backend_pid()")) { rows => rows.next(); rows.getInt(1) }
  }

  private def awaitBlocked(db: Fixture, waiting: Int, blocker: Int): Future[Unit] = Future(blocking {
    Using.resource(db.dataSource.getConnection) { connection =>
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
      @scala.annotation.tailrec def poll(): Unit = {
        val blocked = Using.resource(connection.prepareStatement("SELECT ?=ANY(pg_blocking_pids(?))")) { query =>
          query.setInt(1, blocker); query.setInt(2, waiting)
          Using.resource(query.executeQuery()) { rows => rows.next(); rows.getBoolean(1) }
        }
        if (blocked) ()
        else if (System.nanoTime() >= deadline) fail("Preparation did not wait on the expected transaction")
        else { Thread.`yield`(); poll() }
      }
      poll()
    }
  })(ExecutionContext.global)

  private def prepareNewObserved(db: Fixture, prepared: PreparedBrowserSession, pid: Promise[Int]): Future[PreparedReceipt] =
    Future(blocking {
      Using.resource(db.dataSource.getConnection) { connection =>
        connection.setAutoCommit(false)
        try {
          db.insertHost(connection, prepared)
          pid.trySuccess(backendId(connection))
          val receipt = db.store.prepareNew(connection, prepared)
          connection.commit()
          receipt
        } catch { case error: Throwable => connection.rollback(); throw error }
      }
    })(ExecutionContext.global)

  "Durable browser sessions" should "survive adapter restart with the same host credential and acknowledge only after cookie proof" in {
    val db = new Fixture
    val binding = digest()
    val generation = accepted(db.store.openSlot(binding))
    val prepared = db.prepared(binding, generation)
    db.persist(prepared)
    db.store.validate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
    val firstDelivery = db.store.delivery(prepared.completionId, binding)
    db.store.delivery(prepared.completionId, binding) shouldBe firstDelivery
    db.hostFlag(prepared.hostSessionId, "acknowledged") shouldBe Some(false)
    val activated = accepted(db.store.activate(prepared.tokenHash, binding))
    db.hostFlag(prepared.hostSessionId, "acknowledged") shouldBe Some(true)
    val restarted = db.store
    restarted.validate(prepared.tokenHash, binding) shouldBe Right(activated)
    restarted.activate(prepared.tokenHash, binding) shouldBe Right(activated)
    db.clock.set(initialTime.plusSeconds(61))
    restarted.validate(prepared.tokenHash, binding) shouldBe Right(activated)
  }

  it should "observe existing generations without creating absent browser slots" in {
    val db = new Fixture
    val binding = digest()
    db.store.captureExistingSlotGeneration(binding) shouldBe Left(JdbcAuthError.NotFound)
    Using.resource(db.dataSource.getConnection) { connection =>
      Using.resource(connection.prepareStatement("""SELECT count(*) FROM spoonbill_browser_slot
        WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ?""")) { query =>
        query.setString(1, db.realm); query.setString(2, "session"); query.setBytes(3, binding.bytes)
        Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) shouldBe 0L }
      }
    }
    val generation = accepted(db.store.openSlot(binding))
    db.store.captureExistingSlotGeneration(binding) shouldBe Right(generation)
  }

  it should "isolate generation observations by realm, cookie namespace and browser binding" in {
    val db = new Fixture
    val binding = digest()
    val generation = accepted(db.store.openSlot(binding))
    val otherRealm = new JdbcBrowserSessions(db.dataSource, "other-" + db.realm, "session", () => db.clock.get(), db.hooks)
    val otherNamespace = new JdbcBrowserSessions(db.dataSource, db.realm, "other-session", () => db.clock.get(), db.hooks)
    otherRealm.captureExistingSlotGeneration(binding) shouldBe Left(JdbcAuthError.NotFound)
    otherNamespace.captureExistingSlotGeneration(binding) shouldBe Left(JdbcAuthError.NotFound)
    db.store.captureExistingSlotGeneration(digest()) shouldBe Left(JdbcAuthError.NotFound)
    db.store.captureExistingSlotGeneration(binding) shouldBe Right(generation)
  }

  it should "reject preparation captured before logout without committing host evidence" in {
    val db = new Fixture
    val binding = digest()
    accepted(db.store.openSlot(binding))
    val captured = accepted(db.store.captureExistingSlotGeneration(binding))
    val prepared = db.prepared(binding, captured)
    accepted(db.store.logout(binding)) should be > captured
    intercept[JdbcAuthException](db.persist(prepared)).error shouldBe JdbcAuthError.StaleGeneration
    db.hostFlag(prepared.hostSessionId, "valid") shouldBe None
    db.store.delivery(prepared.completionId, binding) shouldBe Left(JdbcAuthError.NotFound)
  }

  it should "prepare session and completion with three statements and preserve exact idempotency" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    val probe = new PerformanceProbe(db.dataSource)
    val store = new JdbcBrowserSessions(probe.dataSource, db.realm, "session", () => db.clock.get(), db.hooks)
    Using.resource(probe.dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        db.insertHost(connection, prepared)
        val (receipt, measured) = probe.measure(store.prepare(connection, prepared))
        measured.counts.sqlExecutions shouldBe 3L // slot lock, existing completion, dependent session/completion append
        measured.counts.commits shouldBe 0L
        val (repeated, replayed) = probe.measure(store.prepare(connection, prepared))
        repeated shouldBe receipt
        replayed.counts.sqlExecutions shouldBe 2L
        connection.commit()
      } catch { case error: Throwable => connection.rollback(); throw error }
    }
    db.store.delivery(prepared.completionId, binding).isRight shouldBe true
  }

  it should "prepare new browser records in two statements while retaining caller transaction ownership" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
      .copy(completionExpiresAt = initialTime.plusSeconds(60).plusNanos(789))
    val probe = new PerformanceProbe(db.dataSource)
    val store = new JdbcBrowserSessions(probe.dataSource, db.realm, "session", () => db.clock.get(), db.hooks)
    Using.resource(probe.dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        db.insertHost(connection, prepared)
        val (receipt, measured) = probe.measure(store.prepareNew(connection, prepared))
        measured.counts.sqlExecutions shouldBe 2L
        measured.counts.commits shouldBe 0L
        measured.counts.rollbacks shouldBe 0L
        measured.counts.autoCommitChanges shouldBe 0L
        measured.counts.connections shouldBe 0L
        connection.isClosed shouldBe false
        connection.getAutoCommit shouldBe false
        db.clock.set(initialTime.plusSeconds(61))
        // Existing idempotency is deliberately unchanged, including normalized
        // sub-microsecond expiries and exact recovery after the delivery deadline.
        store.prepare(connection, prepared) shouldBe receipt
      } finally connection.rollback()
    }
    db.hostFlag(prepared.hostSessionId, "valid") shouldBe None
    db.browserCounts shouldBe Vector(0L, 0L)
  }

  it should "reject an exact strict duplicate instead of recovering its receipt and roll back new host work" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persistNew(prepared)
    val newHostWork = db.prepared(binding, prepared.expectedSlotGeneration)
    Using.resource(db.dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        db.insertHost(connection, newHostWork)
        intercept[JdbcAuthException](db.store.prepareNew(connection, prepared)).error shouldBe JdbcAuthError.Conflict
      } finally connection.rollback()
    }
    db.hostFlag(newHostWork.hostSessionId, "valid") shouldBe None
    db.browserCounts shouldBe Vector(1L, 1L)
    db.store.delivery(prepared.completionId, binding).isRight shouldBe true
  }

  it should "roll back a conflicting strict completion from another slot without an orphan session" in {
    val db = new Fixture
    val firstBinding = digest()
    val secondBinding = digest()
    val first = db.prepared(firstBinding, accepted(db.store.openSlot(firstBinding)))
    val conflicting = db.prepared(secondBinding, accepted(db.store.openSlot(secondBinding))).copy(completionId = first.completionId)
    db.persistNew(first)
    intercept[JdbcAuthException](db.persistNew(conflicting)).error shouldBe JdbcAuthError.Conflict
    db.hostFlag(conflicting.hostSessionId, "valid") shouldBe None
    db.browserCounts shouldBe Vector(1L, 1L)
    db.store.delivery(first.completionId, firstBinding).isRight shouldBe true
  }

  it should "commit at most one strict preparation across racing exact duplicates" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    Using.resource(db.dataSource.getConnection)(db.insertHost(_, prepared))
    concurrent(Vector.fill(2)(() => Using.resource(db.dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      try {
        val receipt = db.store.prepareNew(connection, prepared)
        connection.commit()
        Right(receipt)
      } catch { case error: JdbcAuthException => connection.rollback(); Left(error.error) }
    })).map { results =>
      results.count(_.isRight) shouldBe 1
      results.count(_ == Left(JdbcAuthError.Conflict)) shouldBe 1
      db.browserCounts shouldBe Vector(1L, 1L)
    }
  }

  it should "reject missing slots stale generations and expired strict preparation before insertion" in {
    val db = new Fixture
    val binding = digest()
    val captured = accepted(db.store.openSlot(binding))
    val stale = db.prepared(binding, captured)
    val current = accepted(db.store.logout(binding))
    val rejected = Vector(
      stale -> JdbcAuthError.StaleGeneration,
      db.prepared(binding, -1L) -> JdbcAuthError.StaleGeneration,
      db.prepared(digest(), 0L) -> JdbcAuthError.NotFound,
      db.prepared(binding, current).copy(completionExpiresAt = initialTime) -> JdbcAuthError.Expired,
      db.prepared(binding, current).copy(sessionExpiresAt = initialTime) -> JdbcAuthError.Expired,
      db.prepared(binding, current).copy(completionExpiresAt = initialTime.plusSeconds(601)) -> JdbcAuthError.Expired)
    rejected.foreach { case (prepared, expected) =>
      intercept[JdbcAuthException](db.persistNew(prepared)).error shouldBe expected
      db.hostFlag(prepared.hostSessionId, "valid") shouldBe None
    }
    Using.resource(db.dataSource.getConnection) { connection =>
      intercept[JdbcAuthException](db.store.prepareNew(connection, stale)).error shouldBe JdbcAuthError.TransactionRequired
    }
    db.browserCounts shouldBe Vector(0L, 0L)
  }

  it should "resample expiry after strict preparation waits for the browser slot" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    val blocker = db.dataSource.getConnection
    blocker.setAutoCommit(false)
    Using.resource(blocker.prepareStatement("""SELECT generation FROM spoonbill_browser_slot
      WHERE realm=? AND cookie_namespace='session' AND binding_hash=? FOR UPDATE""")) { query =>
      query.setString(1, db.realm); query.setBytes(2, binding.bytes)
      Using.resource(query.executeQuery())(_.next())
    }
    val waiting = Promise[Int]()
    val preparation = prepareNewObserved(db, prepared, waiting)
    (for {
      pid <- bounded(waiting)
      _ <- awaitBlocked(db, pid, backendId(blocker))
      _ = db.clock.set(prepared.completionExpiresAt)
      _ = blocker.rollback()
      error <- preparation.failed
    } yield {
      error match {
        case denied: JdbcAuthException => denied.error shouldBe JdbcAuthError.Expired
        case _ => fail("Expected an expired preparation")
      }
      db.hostFlag(prepared.hostSessionId, "valid") shouldBe None
      db.browserCounts shouldBe Vector(0L, 0L)
    }).transform { result => blocker.rollback(); blocker.close(); result }
  }

  it should "roll back strict insertion if a unique-key wait ends after expiry when its competitor rolls back" in {
    val db = new Fixture
    val firstBinding = digest()
    val secondBinding = digest()
    val first = db.prepared(firstBinding, accepted(db.store.openSlot(firstBinding)))
    val second = db.prepared(secondBinding, accepted(db.store.openSlot(secondBinding))).copy(completionId = first.completionId)
    val blocker = db.dataSource.getConnection
    blocker.setAutoCommit(false)
    db.insertHost(blocker, first)
    db.store.prepareNew(blocker, first)
    val waiting = Promise[Int]()
    val preparation = prepareNewObserved(db, second, waiting)
    (for {
      pid <- bounded(waiting)
      _ <- awaitBlocked(db, pid, backendId(blocker))
      _ = db.clock.set(second.completionExpiresAt)
      _ = blocker.rollback()
      error <- preparation.failed
    } yield {
      error match {
        case denied: JdbcAuthException => denied.error shouldBe JdbcAuthError.Expired
        case _ => fail("Expected an expired preparation")
      }
      db.hostFlag(first.hostSessionId, "valid") shouldBe None
      db.hostFlag(second.hostSessionId, "valid") shouldBe None
      db.browserCounts shouldBe Vector(0L, 0L)
    }).transform { result => blocker.rollback(); blocker.close(); result }
  }

  it should "capture generation with one SQL execution and no explicit transaction" in {
    val db = new Fixture
    val binding = digest()
    val expectedGeneration = accepted(db.store.openSlot(binding))
    val probe = new PerformanceProbe(db.dataSource)
    val store = new JdbcBrowserSessions(probe.dataSource, db.realm, "session", () => db.clock.get(), db.hooks)
    val (result, measured) = probe.measure(store.captureExistingSlotGeneration(binding))
    result shouldBe Right(expectedGeneration)
    measured.counts.sqlExecutions shouldBe 1L
    measured.counts.commits shouldBe 0L
    measured.counts.rollbacks shouldBe 0L
    measured.counts.autoCommitChanges shouldBe 0L
    measured.counts.connections shouldBe 1L
    measured.activeConnectionsAfter shouldBe 0L
  }

  it should "choose one activation across independent connections and reject a delayed competing cookie" in {
    val db = new Fixture
    val binding = digest()
    val generation = accepted(db.store.openSlot(binding))
    val prepared = Vector.fill(8)(db.prepared(binding, generation))
    prepared.foreach(db.persist)
    concurrent(prepared.map(value => () => db.store.activate(value.tokenHash, binding))).map { results =>
      results.count(_.isRight) shouldBe 1
      results.count(_ == Left(JdbcAuthError.StaleGeneration)) shouldBe 7
      val winner = prepared.zip(results).collectFirst { case (value, Right(_)) => value }.getOrElse(fail("No activation won"))
      prepared.filterNot(_ == winner).foreach { loser =>
        db.store.validate(loser.tokenHash, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
        db.store.delivery(loser.completionId, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
      }
      db.store.validate(winner.tokenHash, binding).isRight shouldBe true
    }
  }

  it should "linearize logout against activation and keep every pending completion stale" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persist(prepared)
    concurrent(Vector(
      () => db.store.activate(prepared.tokenHash, binding).map(_ => ()),
      () => db.store.logout(binding).map(_ => ())
    )).map { results =>
      results(1) shouldBe Right(())
      db.store.validate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
      db.store.activate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
      db.store.delivery(prepared.completionId, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
    }
  }

  it should "roll back host evidence and framework preparation together" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    Using.resource(db.dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      db.insertHost(connection, prepared)
      db.store.prepare(connection, prepared)
      connection.rollback()
    }
    db.hostFlag(prepared.hostSessionId, "valid") shouldBe None
    db.store.delivery(prepared.completionId, binding) shouldBe Left(JdbcAuthError.NotFound)
    db.store.activate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.NotFound)
  }

  it should "abort stale preparation and refuse an autocommit transaction" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    accepted(db.store.logout(binding))
    intercept[JdbcAuthException](db.persist(prepared)).error shouldBe JdbcAuthError.StaleGeneration
    db.hostFlag(prepared.hostSessionId, "valid") shouldBe None
    Using.resource(db.dataSource.getConnection) { connection =>
      intercept[JdbcAuthException](db.store.prepare(connection, prepared)).error shouldBe JdbcAuthError.TransactionRequired
    }
  }

  it should "roll back framework activation when host acknowledgment fails and allow safe recovery" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persist(prepared)
    val failingHooks = new HostSessionHooks {
      def isCurrent(connection: Connection, id: UUID, now: Instant): Boolean = db.hooks.isCurrent(connection, id, now)
      def acknowledge(connection: Connection, completion: UUID, session: UUID, now: Instant): Unit = {
        db.hooks.acknowledge(connection, completion, session, now)
        throw new IllegalStateException("Simulated host acknowledgment failure")
      }
    }
    val failing = new JdbcBrowserSessions(db.dataSource, db.realm, "session", () => db.clock.get(), failingHooks)
    failing.activate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.StorageFailure)
    db.hostFlag(prepared.hostSessionId, "acknowledged") shouldBe Some(false)
    db.store.validate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.StaleGeneration)
    db.store.delivery(prepared.completionId, binding).isRight shouldBe true
    db.store.activate(prepared.tokenHash, binding).isRight shouldBe true
  }

  it should "reject wrong-browser proof, realm mismatch, expiration and revoked host policy" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persist(prepared)
    db.store.delivery(prepared.completionId, digest()) shouldBe Left(JdbcAuthError.BindingMismatch)
    db.store.activate(prepared.tokenHash, digest()) shouldBe Left(JdbcAuthError.BindingMismatch)
    val otherRealm = new JdbcBrowserSessions(db.dataSource, "another-" + db.realm, "session", () => db.clock.get(), db.hooks)
    otherRealm.activate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.NotFound)
    db.clock.set(prepared.completionExpiresAt)
    db.store.activate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.Expired)
    db.clock.set(initialTime)
    accepted(db.store.activate(prepared.tokenHash, binding))
    Using.resource(db.dataSource.getConnection) { connection =>
      Using.resource(connection.prepareStatement("UPDATE spoonbill_jdbc_test_host SET valid = FALSE WHERE session_id = ?")) { query =>
        query.setObject(1, prepared.hostSessionId); query.executeUpdate()
      }
    }
    db.store.validate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.HostDenied)
  }

  it should "reject framework revocation and exact session expiry after activation" in {
    val db = new Fixture
    val binding = digest()
    val first = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persist(first)
    accepted(db.store.activate(first.tokenHash, binding))
    accepted(db.store.revoke(first.hostSessionId))
    db.store.validate(first.tokenHash, binding) shouldBe Left(JdbcAuthError.Revoked)
    val second = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persist(second)
    accepted(db.store.activate(second.tokenHash, binding))
    db.clock.set(second.sessionExpiresAt)
    db.store.validate(second.tokenHash, binding) shouldBe Left(JdbcAuthError.Expired)
  }

  it should "resample trusted time after host locking before activating an expired completion" in {
    val db = new Fixture
    val binding = digest()
    val prepared = db.prepared(binding, accepted(db.store.openSlot(binding)))
    db.persist(prepared)
    val first = new AtomicBoolean(true)
    val delayed = new HostSessionHooks {
      def isCurrent(connection: Connection, session: UUID, now: Instant): Boolean = {
        val valid = db.hooks.isCurrent(connection, session, now)
        if (first.getAndSet(false)) db.clock.set(prepared.completionExpiresAt)
        valid
      }
      def acknowledge(connection: Connection, completion: UUID, session: UUID, now: Instant): Unit =
        db.hooks.acknowledge(connection, completion, session, now)
    }
    val store = new JdbcBrowserSessions(db.dataSource, db.realm, "session", () => db.clock.get(), delayed)
    store.activate(prepared.tokenHash, binding) shouldBe Left(JdbcAuthError.Expired)
    db.hostFlag(prepared.hostSessionId, "acknowledged") shouldBe Some(false)
    accepted(db.store.openSlot(binding)) shouldBe prepared.expectedSlotGeneration
  }

  "Durable view ownership" should "fence overlapping owners across connections and reject stale cleanup" in {
    val db = new Fixture
    val binding = digest()
    accepted(db.store.openSlot(binding))
    val oldOwner = UUID.randomUUID()
    val oldEpoch = accepted(db.store.claimView("same-view", binding, oldOwner))
    val owners = Vector.fill(8)(UUID.randomUUID())
    concurrent(owners.map(owner => () => db.store.claimView("same-view", binding, owner))).map { results =>
      results.forall(_.isRight) shouldBe true
      val fences = owners.zip(results.map(accepted(_)))
      fences.count { case (owner, epoch) => db.store.validateView("same-view", binding, owner, epoch).isRight } shouldBe 1
      db.store.validateView("same-view", binding, oldOwner, oldEpoch) shouldBe Left(JdbcAuthError.StaleViewFence)
      db.store.releaseView("same-view", binding, oldOwner, oldEpoch) shouldBe Left(JdbcAuthError.StaleViewFence)
      val newest = fences.maxBy(_._2)
      db.store.validateView("same-view", binding, newest._1, newest._2) shouldBe Right(())
    }
  }

  it should "enforce the active-view cap and preserve binding and epoch history after release" in {
    val db = new Fixture
    val store = new JdbcBrowserSessions(db.dataSource, db.realm, "session", () => db.clock.get(), db.hooks, maxActiveViews = 1)
    val binding = digest()
    val otherBinding = digest()
    accepted(store.openSlot(binding))
    accepted(store.openSlot(otherBinding))
    val owner = UUID.randomUUID()
    val epoch = accepted(store.claimView("first", binding, owner))
    store.claimView("second", binding, UUID.randomUUID()) shouldBe Left(JdbcAuthError.ViewCapacityExceeded)
    store.claimView("first", otherBinding, UUID.randomUUID()) shouldBe Left(JdbcAuthError.BindingMismatch)
    accepted(store.releaseView("first", binding, owner, epoch))
    val newOwner = UUID.randomUUID()
    val newEpoch = accepted(store.claimView("first", binding, newOwner))
    newEpoch should be > epoch
    store.validateView("first", binding, owner, epoch) shouldBe Left(JdbcAuthError.StaleViewFence)
    store.validateView("first", binding, newOwner, newEpoch) shouldBe Right(())
    store.claimView("bad\nview", binding, UUID.randomUUID()) shouldBe Left(JdbcAuthError.InvalidViewId)
  }

  "Credential hashes" should "copy mutable input and keep diagnostics redacted" in {
    val bytes = Array.fill[Byte](32)(1)
    val value = accepted(Digest256.fromBytes(bytes))
    bytes(0) = 9
    val copy = value.bytes
    copy(0) = 7
    value.bytes(0) shouldBe 1.toByte
    value.toString shouldBe "Digest256(<redacted>)"
    Digest256.fromBytes(Array.emptyByteArray) shouldBe Left(JdbcAuthError.InvalidDigest)
  }
}
