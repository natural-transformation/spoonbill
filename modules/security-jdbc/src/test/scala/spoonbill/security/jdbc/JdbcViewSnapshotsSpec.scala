package spoonbill.security.jdbc

import java.io.PrintWriter
import java.security.{MessageDigest, SecureRandom}
import java.sql.{Connection, DriverManager, PreparedStatement, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise, blocking}
import scala.util.Using
import spoonbill.security.Versions.*
import spoonbill.snapshot.*

class JdbcViewSnapshotsSpec extends AsyncFlatSpec with Matchers {
  case class Counter(value: Int) derives StateSchema
  private val initialTime = Instant.parse("2026-10-06T12:00:00Z")
  private def accepted[E, A](value: Either[E, A]): A = value.fold(error => fail(error.toString), identity)
  private def hash(): Digest256 = {
    val input = new Array[Byte](32)
    new SecureRandom().nextBytes(input)
    accepted(Digest256.fromBytes(MessageDigest.getInstance("SHA-256").digest(input)))
  }

  private class Fixture(val limits: SnapshotLimits = SnapshotLimits.default) {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Set SPOONBILL_JDBC_TEST_URL to disposable PostgreSQL"))
    val dataSource: DataSource = new DataSource {
      def getConnection: Connection = {
        val properties = new Properties()
        sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
        sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
        DriverManager.getConnection(url, properties)
      }
      def getConnection(user: String, password: String): Connection = DriverManager.getConnection(url, user, password)
      def getLogWriter: PrintWriter = throw new SQLFeatureNotSupportedException()
      def setLogWriter(value: PrintWriter): Unit = throw new SQLFeatureNotSupportedException()
      def getLoginTimeout: Int = 0
      def setLoginTimeout(value: Int): Unit = throw new SQLFeatureNotSupportedException()
      def getParentLogger: Logger = Logger.getLogger("spoonbill.snapshot.test")
      def isWrapperFor(kind: Class[?]): Boolean = false
      def unwrap[T](kind: Class[T]): T = throw new SQLException("Not a wrapper")
    }
    val realm = "snapshot-" + UUID.randomUUID().toString
    val namespace = "session"
    val clock = new AtomicReference(initialTime)
    val bindingHash = hash()
    val tokenHash = hash()
    val sessionId = UUID.randomUUID()
    val viewId = "view-" + UUID.randomUUID().toString
    val ownerId = UUID.randomUUID()
    val hooks = new HostSessionHooks {
      def isCurrent(connection: Connection, session: UUID, now: Instant): Boolean =
        query(connection, "SELECT valid FROM spoonbill_snapshot_test_host WHERE session_id = ? FOR UPDATE") { statement =>
          statement.setObject(1, session)
          Using.resource(statement.executeQuery())(result => result.next() && result.getBoolean(1))
        }
      def acknowledge(connection: Connection, completion: UUID, session: UUID, now: Instant): Unit = ()
    }
    val identityCheck = new HostSnapshotIdentity {
      def isCurrent(connection: Connection, session: UUID, expected: SnapshotIdentity, now: Instant): Boolean =
        query(connection, "SELECT subject_key, scope_key, security_generation FROM spoonbill_snapshot_test_host WHERE session_id = ?") { statement =>
          statement.setObject(1, session)
          Using.resource(statement.executeQuery()) { result =>
            result.next() && result.getString(1) == expected.subject.value && result.getString(2) == expected.scope.value &&
              result.getLong(3) == expected.securityGeneration.toLong
          }
        }
    }
    def sessions: JdbcBrowserSessions = new JdbcBrowserSessions(dataSource, realm, namespace, () => clock.get(), hooks)

    Using.resource(dataSource.getConnection) { connection =>
      sessions.initialize(connection)
      Using.resource(connection.createStatement())(_.executeUpdate("""CREATE TABLE IF NOT EXISTS spoonbill_snapshot_test_host (
        session_id UUID PRIMARY KEY, subject_key VARCHAR(256) NOT NULL, scope_key VARCHAR(256) NOT NULL,
        security_generation BIGINT NOT NULL, valid BOOLEAN NOT NULL)"""))
    }
    val originalSlot = accepted(sessions.openSlot(bindingHash))
    val prepared = PreparedBrowserSession(UUID.randomUUID(), UUID.randomUUID(), sessionId, bindingHash, tokenHash,
      originalSlot, initialTime.plusSeconds(600), initialTime.plusSeconds(60))
    Using.resource(dataSource.getConnection) { connection =>
      connection.setAutoCommit(false)
      query(connection, "INSERT INTO spoonbill_snapshot_test_host VALUES (?, ?, ?, ?, TRUE)") { statement =>
        statement.setObject(1, sessionId); statement.setString(2, "subject-a"); statement.setString(3, "counter:tenant-a")
        statement.setLong(4, 0L); statement.executeUpdate()
      }
      sessions.prepare(connection, prepared)
      connection.commit()
    }
    val active = accepted(sessions.activate(tokenHash, bindingHash))
    val ownerEpoch = accepted(ViewOwnershipEpoch.fromLong(accepted(sessions.claimView(viewId, bindingHash, ownerId))))
    val identity = SnapshotIdentity(accepted(SnapshotKeys.Subject.parse("subject-a")), accepted(SnapshotKeys.Scope.parse("counter:tenant-a")),
      sessionId, SecurityGeneration.initial, accepted(SlotGeneration.fromLong(active.generation)))
    val format = new SnapshotFormat[Counter](accepted(SchemaId.parse("counter")), accepted(SchemaVersion.fromInt(1)), limits)

    def bound(owner: UUID = ownerId, epoch: ViewOwnershipEpoch = ownerEpoch, expected: SnapshotIdentity = identity,
      schema: SnapshotFormat[Counter] = format, check: HostSnapshotIdentity = identityCheck): JdbcViewSnapshots[Counter] =
      sessions.snapshots(viewId, bindingHash, owner, epoch, tokenHash, expected, schema, check)

    def scope(statement: PreparedStatement): Unit = {
      statement.setString(1, realm); statement.setString(2, namespace); statement.setString(3, viewId)
    }
    def sql[A](text: String)(run: PreparedStatement => A): A = Using.resource(dataSource.getConnection)(query(_, text)(run))
    def query[A](connection: Connection, text: String)(run: PreparedStatement => A): A =
      Using.resource(connection.prepareStatement(text))(run)
    def restored(store: JdbcViewSnapshots[Counter] = bound()): (ViewRevision, Counter) = accepted(store.load()) match {
      case SnapshotLoad.Restored(revision, value) => (revision, value)
      case other => fail(s"Expected restored data, got $other")
    }

    def lockedSlot(): Connection = {
      val connection = dataSource.getConnection
      connection.setAutoCommit(false)
      query(connection, "SELECT generation FROM spoonbill_browser_slot WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ? FOR UPDATE") { statement =>
        statement.setString(1, realm); statement.setString(2, namespace); statement.setBytes(3, bindingHash.bytes)
        Using.resource(statement.executeQuery())(result => result.next() shouldBe true)
      }
      connection
    }

    def signaled(checkReached: Promise[Unit]): HostSnapshotIdentity = new HostSnapshotIdentity {
      def isCurrent(connection: Connection, session: UUID, expected: SnapshotIdentity, now: Instant): Boolean = {
        val result = identityCheck.isCurrent(connection, session, expected, now)
        checkReached.trySuccess(())
        result
      }
    }
  }

  "Durable typed snapshots" should "restore after a new adapter and owner claim while fencing the prior owner" in {
    val db = new Fixture
    val original = db.bound()
    original.load() shouldBe Right(SnapshotLoad.Empty(ViewRevision.initial))
    val first = accepted(original.save(ViewRevision.initial, Counter(7)))
    val replacement = UUID.randomUUID()
    val epoch = accepted(ViewOwnershipEpoch.fromLong(accepted(db.sessions.claimExistingView(db.viewId, db.bindingHash, replacement))))
    val resumed = db.bound(replacement, epoch)
    db.restored(resumed) shouldBe (first -> Counter(7))
    original.save(first, Counter(8)) shouldBe Left(ViewSnapshotError.StaleOwner)
    val second = accepted(resumed.save(first, Counter(9)))
    second.toLong shouldBe 2L
    db.restored(resumed) shouldBe (second -> Counter(9))
  }

  it should "commit exactly one same-owner write for an expected revision" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(0)))
    val start = Promise[Unit]()
    val writes = Vector(11, 12).map { value =>
      start.future.map(_ => blocking(db.bound().save(revision, Counter(value))))(ExecutionContext.global)
    }
    start.success(())
    Future.sequence(writes).map { results =>
      results.count(_.isRight) shouldBe 1
      results.count(_ == Left(ViewSnapshotError.RevisionConflict)) shouldBe 1
      val (storedRevision, value) = db.restored()
      storedRevision.toLong shouldBe 2L
      Set(11, 12) should contain(value.value)
      db.bound().save(revision, Counter(99)) shouldBe Left(ViewSnapshotError.RevisionConflict)
      db.restored() shouldBe (storedRevision -> value)
    }
  }

  it should "not disclose another authorized scope and require explicit revision-preserving reset" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(37)))
    val nextScope = accepted(SnapshotKeys.Scope.parse("counter:tenant-b"))
    db.sql("UPDATE spoonbill_snapshot_test_host SET scope_key = ? WHERE session_id = ?") { statement =>
      statement.setString(1, nextScope.value); statement.setObject(2, db.sessionId); statement.executeUpdate()
    }
    val owner = UUID.randomUUID()
    val epoch = accepted(ViewOwnershipEpoch.fromLong(accepted(db.sessions.claimExistingView(db.viewId, db.bindingHash, owner))))
    val changed = db.bound(owner, epoch, db.identity.copy(scope = nextScope))
    changed.load() shouldBe Right(SnapshotLoad.ResetRequired(revision, SnapshotResetReason.IdentityChanged))
    changed.load().toString should not include "37"
    changed.save(revision, Counter(0)) shouldBe Left(ViewSnapshotError.ResetRequired)
    val reset = accepted(changed.reset(revision, Counter(0)))
    reset.toLong shouldBe 2L
    db.restored(changed) shouldBe (reset -> Counter(0))
  }

  it should "validate every pinned identity dimension before returning payload" in {
    val db = new Fixture
    val identities = List(
      db.identity.copy(subject = accepted(SnapshotKeys.Subject.parse("other-subject"))),
      db.identity.copy(scope = accepted(SnapshotKeys.Scope.parse("other-scope"))),
      db.identity.copy(sessionId = UUID.randomUUID()),
      db.identity.copy(securityGeneration = accepted(SecurityGeneration.fromLong(1))),
      db.identity.copy(slotGeneration = accepted(SlotGeneration.fromLong(db.active.generation + 1)))
    )
    identities.foreach(identity => db.bound(expected = identity).load() shouldBe Left(ViewSnapshotError.AccessDenied))
    db.bound().load() shouldBe Right(SnapshotLoad.Empty(ViewRevision.initial))
  }

  it should "reject schema evolution until an explicit current-owner reset" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(7)))
    val next = new SnapshotFormat[Counter](db.format.schemaId, accepted(SchemaVersion.fromInt(2)))
    val upgraded = db.bound(schema = next)
    upgraded.load() shouldBe Right(SnapshotLoad.ResetRequired(revision, SnapshotResetReason.SchemaChanged))
    upgraded.save(revision, Counter(0)) shouldBe Left(ViewSnapshotError.ResetRequired)
    val reset = accepted(upgraded.reset(revision, Counter(0)))
    reset.toLong shouldBe 2L
    upgraded.reset(reset, Counter(0)) shouldBe Left(ViewSnapshotError.ResetNotRequired)
    db.restored(upgraded) shouldBe (reset -> Counter(0))
  }

  it should "fail closed on compatible corrupt or oversized payload and refuse an automatic reset" in {
    val db = new Fixture(accepted(SnapshotLimits.create(maxUtf8Bytes = 64, maxNodes = 8, maxStringUtf8Bytes = 32)))
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(1)))
    def corrupt(bytes: Array[Byte]): Unit = db.sql("UPDATE spoonbill_view_snapshot SET payload = ? WHERE realm = ? AND cookie_namespace = ? AND view_id = ?") { statement =>
      statement.setBytes(1, bytes); statement.setString(2, db.realm); statement.setString(3, db.namespace)
      statement.setString(4, db.viewId); statement.executeUpdate(); ()
    }
    corrupt(Array[Byte](1, 2, 3))
    db.bound().load() shouldBe Left(ViewSnapshotError.MalformedSnapshot)
    db.bound().reset(revision, Counter(0)) shouldBe Left(ViewSnapshotError.ResetNotRequired)
    corrupt(new Array[Byte](SnapshotBinaryCodec.maxEncodedBytes(db.limits) + 1))
    db.bound().load() shouldBe Left(ViewSnapshotError.MalformedSnapshot)
  }

  it should "roll back payload replacement if the view revision update fails" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(4)))
    val name = "snapshot_test_" + UUID.randomUUID().toString.replace("-", "")
    Using.resource(db.dataSource.getConnection) { connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(
        s"ALTER TABLE spoonbill_browser_view ADD CONSTRAINT $name CHECK (realm <> '${db.realm}' OR snapshot_revision <= 1)"))
    }
    try {
      db.bound().save(revision, Counter(99)) shouldBe Left(ViewSnapshotError.StorageFailure)
      db.restored() shouldBe (revision -> Counter(4))
    } finally Using.resource(db.dataSource.getConnection) { connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(s"ALTER TABLE spoonbill_browser_view DROP CONSTRAINT $name"))
    }
  }

  it should "recheck slot revocation after waiting for its lock" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(4)))
    val blocker = db.lockedSlot()
    val reached = Promise[Unit]()
    val writing = Future(blocking(db.bound(check = db.signaled(reached)).save(revision, Counter(99))))(ExecutionContext.global)
    val observed = Future.firstCompletedOf(Vector(reached.future, writing.map(_ => ())))
    observed.flatMap { _ =>
      try {
        reached.isCompleted shouldBe true
        db.query(blocker, "UPDATE spoonbill_browser_slot SET generation = generation + 1, current_session_id = NULL WHERE realm = ? AND cookie_namespace = ? AND binding_hash = ?") { statement =>
          statement.setString(1, db.realm); statement.setString(2, db.namespace); statement.setBytes(3, db.bindingHash.bytes)
          statement.executeUpdate()
        }
        blocker.commit()
      } finally blocker.close()
      writing.map { result =>
        result shouldBe Left(ViewSnapshotError.AccessDenied)
        db.sql("SELECT snapshot_revision FROM spoonbill_browser_view WHERE realm = ? AND cookie_namespace = ? AND view_id = ?") { statement =>
          db.scope(statement)
          Using.resource(statement.executeQuery()) { rows => rows.next() shouldBe true; rows.getLong(1) shouldBe revision.toLong }
        }
      }
    }
  }

  it should "reject an old owner whose request passed host checks before takeover committed" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(4)))
    val blocker = db.lockedSlot()
    val reached = Promise[Unit]()
    val owner = UUID.randomUUID()
    val writing = Future(blocking(db.bound(check = db.signaled(reached)).save(revision, Counter(99))))(ExecutionContext.global)
    Future.firstCompletedOf(Vector(reached.future, writing.map(_ => ()))).flatMap { _ =>
      try {
        reached.isCompleted shouldBe true
        db.query(blocker, "UPDATE spoonbill_browser_view SET owner_id = ?, epoch = epoch + 1 WHERE realm = ? AND cookie_namespace = ? AND view_id = ?") { statement =>
          statement.setObject(1, owner); statement.setString(2, db.realm); statement.setString(3, db.namespace)
          statement.setString(4, db.viewId); statement.executeUpdate()
        }
        blocker.commit()
      } finally blocker.close()
      writing.map { result =>
        result shouldBe Left(ViewSnapshotError.StaleOwner)
        val epoch = accepted(db.ownerEpoch.next)
        db.restored(db.bound(owner, epoch)) shouldBe (revision -> Counter(4))
      }
    }
  }

  it should "recheck expiry after a lock wait instead of persisting under the earlier timestamp" in {
    val db = new Fixture
    val revision = accepted(db.bound().save(ViewRevision.initial, Counter(4)))
    val blocker = db.lockedSlot()
    val reached = Promise[Unit]()
    val writing = Future(blocking(db.bound(check = db.signaled(reached)).save(revision, Counter(99))))(ExecutionContext.global)
    Future.firstCompletedOf(Vector(reached.future, writing.map(_ => ()))).flatMap { _ =>
      try { reached.isCompleted shouldBe true; db.clock.set(db.prepared.sessionExpiresAt); blocker.commit() }
      finally blocker.close()
      writing.map(_ shouldBe Left(ViewSnapshotError.AccessDenied))
    }
  }

  "Existing-view resume" should "never allocate an unknown view while preserving real takeover" in {
    val db = new Fixture
    val missing = "missing-" + UUID.randomUUID().toString
    db.sessions.claimExistingView(missing, db.bindingHash, UUID.randomUUID()) shouldBe Left(JdbcAuthError.NotFound)
    db.sql("SELECT COUNT(*) FROM spoonbill_browser_view WHERE realm = ? AND cookie_namespace = ? AND view_id = ?") { statement =>
      statement.setString(1, db.realm); statement.setString(2, db.namespace); statement.setString(3, missing)
      Using.resource(statement.executeQuery()) { result => result.next() shouldBe true; result.getLong(1) shouldBe 0L }
    }
    accepted(db.sessions.claimExistingView(db.viewId, db.bindingHash, UUID.randomUUID())) should be > db.ownerEpoch.toLong
  }
}
