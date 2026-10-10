package spoonbill.security.jdbc

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.net.URI
import java.sql.{Connection, DriverManager, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.effect.Effect
import spoonbill.performance.PerformanceProbe
import spoonbill.security.jdbc.baseline.{JdbcReferenceClock, JdbcReferenceHost}
import spoonbill.security.jdbc.baseline.JdbcReferenceHost.*
import spoonbill.security.transaction.{
  Direct,
  OperationError,
  TransactionExecutor,
  TransactionFailure,
  TransactionProgram,
  TransactionScopePolicy
}

/**
 * Real PostgreSQL evidence for the v3 baseline host glue. Synthetic identities
 * and deterministic clocks/entropy only. Missing database configuration fails
 * this baseline suite rather than silently counting skipped evidence as a pass.
 */
class JdbcReferenceHostSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]                       = new Effect.FutureEffect
  private val initial                                = Instant.parse("2026-10-10T12:00:00Z")
  private def accepted[E, A](value: Either[E, A]): A = value.fold(error => fail(error.toString), identity)
  private def result[A](value: Future[A]): A         = Await.result(value, 20.seconds)
  private val denied                                 = Left(TransactionFailure.Rejected(OperationError.HostDenied))

  private abstract class Source extends DataSource {
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int                                      = 0
    def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.baseline.test")
    def isWrapperFor(kind: Class[?]): Boolean                     = false
    def unwrap[T](kind: Class[T]): T                              = throw new SQLFeatureNotSupportedException()
  }

  private class Fixture extends AutoCloseable {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", fail("Run with scripts/with-test-postgres.sh"))
    require(
      url.startsWith("jdbc:postgresql://") &&
        Set("localhost", "127.0.0.1", "::1", "[::1]").contains(URI.create(url.stripPrefix("jdbc:")).getHost)
    )
    private val schema                = "browser_baseline_" + UUID.randomUUID().toString.replace("-", "")
    private val sequence              = new AtomicLong()
    private val workers               = Executors.newFixedThreadPool(4)
    val context: ExecutionContext     = ExecutionContext.fromExecutor(workers)
    val clock                         = new AtomicReference(initial)
    val failCommit                    = new AtomicBoolean(false)
    val failClockCommit               = new AtomicBoolean(false)
    val failClockWrite                = new AtomicBoolean(false)
    val subject                       = new UUID(0L, 1L)
    val other                         = new UUID(0L, 2L)
    val binding                       = accepted(Digest256.fromBytes(syntheticHash("synthetic-browser-binding")))
    val wrongBinding                  = accepted(Digest256.fromBytes(syntheticHash("another-browser")))
    def bytes(size: Int): Array[Byte] = syntheticHash("synthetic-entropy-" + sequence.incrementAndGet()).take(size)
    def id(): UUID                    = new UUID(1L, sequence.incrementAndGet())
    val key: Array[Byte]              = syntheticHash("synthetic-test-material-key")
    def cipher: MaterialCipher        = new MaterialCipher(key, () => bytes(12))
    def raw(): Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      DriverManager.getConnection(url, properties)
    }
    Using.resource(raw())(connection =>
      Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema"))
    )
    val source: DataSource = new Source {
      def getConnection: Connection = {
        val connection = raw()
        Using.resource(connection.createStatement()) { query =>
          query.execute(s"SET search_path TO $schema")
          query.execute("SET statement_timeout TO '15s'")
          query.execute("SET lock_timeout TO '12s'")
        }
        Proxy
          .newProxyInstance(
            classOf[Connection].getClassLoader,
            Array(classOf[Connection]),
            new InvocationHandler {
              private var clockConnection = false
              def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
                val args = Option(arguments).getOrElse(Array.empty[Object])
                if (
                  method.getName == "prepareStatement" && args.headOption
                    .exists(_.toString.startsWith("INSERT INTO baseline_clock"))
                ) {
                  clockConnection = true
                  if (failClockWrite.compareAndSet(true, false)) throw new SQLException("Synthetic clock write failure")
                }
                val response =
                  try method.invoke(connection, args*)
                  catch {
                    case error: InvocationTargetException => throw error.getCause
                  }
                if (
                  method.getName == "commit" && (if (clockConnection) failClockCommit else failCommit)
                    .compareAndSet(true, false)
                )
                  throw new SQLException("Synthetic commit acknowledgement loss")
                response
              }
            }
          )
          .asInstanceOf[Connection]
      }
    }
    val probe = new PerformanceProbe(source)
    def host(
      verifier: (String, Array[Byte]) => Boolean = syntheticVerify,
      protectedMaterial: MaterialCipher = cipher,
      admission: () => Boolean = () => true,
      capacity: Int = 1024,
      entropy: Int => Array[Byte] = bytes,
      existingRunner: Option[TransactionExecutor[Future, Direct, Connection]] = None,
      maxCeremoniesPerBinding: Int = 16
    ): JdbcReferenceHost =
      new JdbcReferenceHost(
        probe.dataSource,
        context,
        "baseline",
        "session",
        () => clock.get(),
        entropy,
        id,
        protectedMaterial,
        verifier,
        admission,
        capacity,
        existingRunner,
        maxCeremoniesPerBinding = maxCeremoniesPerBinding
      )
    val app = host()
    Using.resource(source.getConnection)(app.initialize)
    Using.resource(source.getConnection) { connection =>
      Vector(subject, other).foreach { account =>
        Using.resource(connection.prepareStatement("INSERT INTO baseline_account VALUES (?,1,TRUE,?,NULL)")) { query =>
          query.setObject(1, account); query.setBytes(2, syntheticHash("password")); query.executeUpdate()
        }
      }
    }
    accepted(app.browser.openSlot(binding))
    def execute(sql: String): Unit = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(sql)); ()
    }
    def factorRequired(): Unit = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement("UPDATE baseline_account SET factor_hash=? WHERE id=?")) { query =>
        query.setBytes(1, syntheticHash("654321")); query.setObject(2, subject); query.executeUpdate(); ()
      }
    }
    def count(table: String): Long = {
      require(
        Set(
          "baseline_session",
          "baseline_audit",
          "baseline_material",
          "spoonbill_browser_session",
          "spoonbill_browser_completion",
          "baseline_existing_host_outbox"
        ).contains(table)
      )
      Using.resource(source.getConnection) { connection =>
        Using.resource(connection.createStatement()) { query =>
          Using.resource(query.executeQuery(s"SELECT count(*) FROM $table")) { rows =>
            rows.next(); rows.getLong(1)
          }
        }
      }
    }
    def ceremony(): UUID = accepted(result(app.begin(binding)))
    def proof(ceremony: UUID): app.Proof = accepted(
      result(app.password(ceremony, binding, subject, "password"))
    ) match {
      case app.PasswordResult.Ready(value) => value
      case _                               => fail("Unexpected challenge")
    }
    def challenge(ceremony: UUID): UUID = accepted(result(app.password(ceremony, binding, subject, "password"))) match {
      case app.PasswordResult.Challenge(_, value, _) => value
      case _                                         => fail("Expected challenge")
    }
    def committed(): UUID = accepted(result(app.complete(proof(ceremony()))))
    def close(): Unit = {
      workers.shutdownNow()
      workers.awaitTermination(20, TimeUnit.SECONDS)
      Using.resource(raw())(connection =>
        Using.resource(connection.createStatement())(_.execute(s"DROP SCHEMA $schema CASCADE"))
      )
    }
  }

  private class ClockLifecycleFaults(
    db: Fixture,
    commitFails: Boolean = false,
    rollbackFails: Boolean = false,
    abortFails: Boolean = false,
    closeFails: Boolean = false
  ) extends AutoCloseable {
    val aborts   = new AtomicLong()
    val closes   = new AtomicLong()
    val physical = new AtomicReference(Option.empty[Connection])
    val source: DataSource = new Source {
      def getConnection: Connection = {
        val connection = db.source.getConnection
        physical.set(Some(connection))
        Proxy
          .newProxyInstance(
            classOf[Connection].getClassLoader,
            Array(classOf[Connection]),
            new InvocationHandler {
              def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
                val args = Option(arguments).getOrElse(Array.empty[Object])
                method.getName match {
                  case "commit" if commitFails             => throw new SQLException("Synthetic clock commit before delegation")
                  case "prepareStatement" if rollbackFails => throw new SQLException("Synthetic clock body failure")
                  case "rollback" if rollbackFails         => throw new SQLException("Synthetic clock rollback failure")
                  case "abort" =>
                    aborts.incrementAndGet()
                    if (abortFails) throw new SQLException("Synthetic clock abort failure")
                    connection.abort(args(0).asInstanceOf[java.util.concurrent.Executor])
                    null
                  case "close" =>
                    closes.incrementAndGet()
                    // Model a dangerous pool return: unresolved work would commit.
                    if (!connection.isClosed && !connection.getAutoCommit) connection.commit()
                    connection.close()
                    if (closeFails) throw new SQLException("Synthetic clock close acknowledgment failure")
                    null
                  case _ =>
                    try method.invoke(connection, args*)
                    catch { case error: InvocationTargetException => throw error.getCause }
                }
              }
            }
          )
          .asInstanceOf[Connection]
      }
    }
    // Test administrator recovers a quarantined physical connection directly;
    // the production clock must never send it through the pool-like facade.
    def close(): Unit = physical.get().foreach { connection =>
      if (!connection.isClosed) {
        try connection.rollback()
        finally connection.close()
      }
    }
  }

  "The v3 JDBC reference host" should "commit password login atomically, deliver once activated, and fence logout" in Using
    .resource(new Fixture) { db =>
      val attempt = db.committed()
      Vector(
        "baseline_session",
        "baseline_material",
        "baseline_audit",
        "spoonbill_browser_session",
        "spoonbill_browser_completion"
      )
        .foreach(db.count(_) shouldBe 1L)
      val cookie = accepted(db.app.deliver(attempt, db.binding))
      cookie.transportValue should fullyMatch regex "[A-Za-z0-9_-]{43}"
      cookie.toString should not include cookie.transportValue
      accepted(db.app.deliver(attempt, db.binding)).transportValue shouldBe cookie.transportValue
      db.app.browser.validate(cookie.hash, db.binding) shouldBe Left(JdbcAuthError.StaleGeneration)
      val active = accepted(db.app.browser.activate(cookie.hash, db.binding))
      db.app.browser.activate(cookie.hash, db.binding) shouldBe Right(active)
      db.app.browser.validate(cookie.hash, db.binding) shouldBe Right(active)
      db.count("baseline_material") shouldBe 0L
      db.app.deliver(attempt, db.binding).isLeft shouldBe true
      accepted(db.app.browser.logout(db.binding)) should be > active.generation
      db.app.browser.validate(cookie.hash, db.binding) shouldBe Left(JdbcAuthError.StaleGeneration)
    }

  it should "bind a resumed challenge to its original ceremony and subject, and consume it once" in Using.resource(
    new Fixture
  ) { db =>
    db.factorRequired()
    val first          = db.ceremony()
    val second         = db.ceremony()
    val challenge      = db.challenge(first)
    val otherChallenge = db.challenge(second)
    result(db.app.factor(first, db.binding, db.other, challenge, "654321")) shouldBe denied
    result(db.app.factor(first, db.binding, db.subject, otherChallenge, "654321")) shouldBe denied
    result(db.app.factor(second, db.binding, db.subject, challenge, "654321")) shouldBe denied
    result(db.app.factor(first, db.wrongBinding, db.subject, challenge, "654321")) shouldBe denied
    val resumed = db.host()
    val proof   = accepted(result(resumed.factor(first, db.binding, db.subject, challenge, "654321")))
    result(resumed.factor(first, db.binding, db.subject, challenge, "654321")) shouldBe denied
    accepted(result(resumed.complete(proof)))
    result(resumed.complete(proof)) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
    db.count("baseline_session") shouldBe 1L
  }

  it should "recheck account policy changed during password computation" in Using.resource(new Fixture) { db =>
    val ceremony = db.ceremony()
    val app = db.host { (password, hash) =>
      db.execute("UPDATE baseline_account SET version=version+1")
      syntheticVerify(password, hash)
    }
    result(app.password(ceremony, db.binding, db.subject, "password")) shouldBe denied
    db.count("baseline_session") shouldBe 0L
  }

  it should "apply rate admission before hashing and final policy before consuming proof" in Using.resource(
    new Fixture
  ) { db =>
    val app = db.host((_, _) => fail("Rate-denied proof must not hash"), admission = () => false)
    result(app.password(db.ceremony(), db.binding, db.subject, "password")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    val proof = db.proof(db.ceremony())
    db.execute("UPDATE baseline_account SET enabled=FALSE")
    result(db.app.complete(proof)) shouldBe denied
    db.count("baseline_session") shouldBe 0L
  }

  it should "roll back every staged side effect and retain a safe recovery fence" in Using.resource(new Fixture) { db =>
    Vector("proof", "session", "browser", "material", "audit").foreach { stage =>
      val ceremony = db.ceremony()
      val proof    = db.proof(ceremony)
      result(
        db.app.complete(proof, current => if (current == stage) throw new IllegalStateException("Synthetic failure"))
      ) shouldBe
        Left(TransactionFailure.RolledBack)
      Vector(
        "baseline_session",
        "baseline_audit",
        "baseline_material",
        "spoonbill_browser_session",
        "spoonbill_browser_completion"
      )
        .foreach(db.count(_) shouldBe 0L)
      result(db.app.recover(ceremony, db.binding)) shouldBe Right(Recovery.NotCommitted)
      result(db.app.complete(proof)) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      result(db.host().password(ceremony, db.binding, db.subject, "password")) shouldBe denied
    }
  }

  it should "recover the same delivery after commit acknowledgement loss and restart without proof replay" in Using
    .resource(new Fixture) { db =>
      val ceremony = db.ceremony()
      val proof    = db.proof(ceremony)
      db.failCommit.set(true)
      result(db.app.complete(proof)) shouldBe Left(TransactionFailure.CommitUnknown)
      val restarted = db.host()
      val recovered = accepted(result(restarted.recover(ceremony, db.binding)))
      val attempt = recovered match {
        case Recovery.Committed(id) => id
        case _                      => fail("Committed identity was lost")
      }
      result(restarted.recover(ceremony, db.binding)) shouldBe Right(recovered)
      result(restarted.recover(ceremony, db.wrongBinding)) shouldBe denied
      val cookie = accepted(restarted.deliver(attempt, db.binding))
      accepted(restarted.browser.activate(cookie.hash, db.binding))
      db.count("baseline_session") shouldBe 1L
      db.count("baseline_audit") shouldBe 1L
      result(restarted.password(ceremony, db.binding, db.subject, "password")) shouldBe denied
    }

  it should "exclude an admitted but undispatched writer with a durable negative fence" in Using.resource(new Fixture) {
    db =>
      val ceremony = db.ceremony()
      val proof    = db.proof(ceremony)
      result(db.host().recover(ceremony, db.binding)) shouldBe Right(Recovery.NotCommitted)
      result(db.app.complete(proof)) shouldBe Left(TransactionFailure.Rejected(OperationError.NotPrepared))
      db.count("baseline_session") shouldBe 0L
  }

  it should "wait for an active writer before resolving commit disposition" in Using.resource(new Fixture) { db =>
    val ceremony = db.ceremony()
    val staged   = new CountDownLatch(1)
    val finish   = new CountDownLatch(1)
    val completion = db.app.complete(
      db.proof(ceremony),
      stage =>
        if (stage == "audit") {
          staged.countDown()
          if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Coordination timed out")
        }
    )
    try {
      staged.await(10, TimeUnit.SECONDS) shouldBe true
      val recovery = db.host().recover(ceremony, db.binding)
      // Observe the physical lock wait; timing or an uncompleted Future alone is insufficient.
      val deadline = System.nanoTime() + 10.seconds.toNanos
      def blocked: Boolean = Using.resource(db.source.getConnection) { connection =>
        Using.resource(connection.createStatement()) { query =>
          Using.resource(
            query.executeQuery(
              "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE query LIKE 'SELECT attempt, binding,%' AND cardinality(pg_blocking_pids(pid))>0)"
            )
          ) { rows =>
            rows.next(); rows.getBoolean(1)
          }
        }
      }
      var observed = blocked
      while (!observed && System.nanoTime() < deadline) { Thread.`yield`(); observed = blocked }
      observed shouldBe true
      finish.countDown()
      val attempt = accepted(result(completion))
      result(recovery) shouldBe Right(Recovery.Committed(attempt))
    } finally finish.countDown()
  }

  it should "retain captured expiry and generation across challenge resume and competing ceremonies" in Using.resource(
    new Fixture
  ) { db =>
    val first         = db.committed()
    val pending       = db.committed()
    val firstCookie   = accepted(db.app.deliver(first, db.binding))
    val pendingCookie = accepted(db.app.deliver(pending, db.binding))
    accepted(db.app.browser.activate(firstCookie.hash, db.binding))
    db.app.browser.activate(pendingCookie.hash, db.binding) shouldBe Left(JdbcAuthError.StaleGeneration)
    val beforeLogout = db.proof(db.ceremony())
    accepted(db.app.browser.logout(db.binding))
    result(db.app.complete(beforeLogout)) shouldBe denied
    db.factorRequired()
    val ceremony  = db.ceremony()
    val challenge = db.challenge(ceremony)
    db.clock.set(initial.plusSeconds(61))
    result(db.host().factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
  }

  it should "fail closed for missing keys or material and never fabricate another session" in Using.resource(
    new Fixture
  ) { db =>
    val attempt  = db.committed()
    val wrongKey = new MaterialCipher(syntheticHash("wrong-key"), () => db.bytes(12))
    db.host(protectedMaterial = wrongKey).deliver(attempt, db.binding) shouldBe Left(JdbcAuthError.StorageFailure)
    db.app.deliver(attempt, db.wrongBinding) shouldBe Left(JdbcAuthError.BindingMismatch)
    db.execute("DELETE FROM baseline_material")
    db.app.deliver(attempt, db.binding) shouldBe Left(JdbcAuthError.NotFound)
    db.count("baseline_session") shouldBe 1L
  }

  it should "freeze full preparation SQL costs including host glue and release every connection" in Using.resource(
    new Fixture
  ) { db =>
    val proof                    = db.proof(db.ceremony())
    val (completed, measurement) = db.probe.measure(result(db.app.complete(proof)))
    completed.isRight shouldBe true
    measurement.counts.sqlExecutions shouldBe 11L
    measurement.counts.connections shouldBe 1L
    measurement.counts.commits shouldBe 1L
    measurement.counts.rollbacks shouldBe 0L
    measurement.activeConnectionsAfter shouldBe 0L
  }

  it should "fail closed at its retained ceremony capacity without deleting replay fences" in Using.resource(
    new Fixture
  ) { db =>
    val app = db.host(capacity = 1)
    accepted(result(app.begin(db.binding)))
    result(app.begin(db.binding)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
  }

  it should "preserve per-binding ceremony quotas through challenge, commit and expiry using one aggregate query" in Using
    .resource(
      new Fixture
    ) { db =>
      val app = db.host(capacity = 4, maxCeremoniesPerBinding = 2)
      db.factorRequired()
      val (begun, measurement) = db.probe.measure(result(app.begin(db.binding)))
      val first                = accepted(begun)
      measurement.counts.sqlExecutions shouldBe 5L
      val challenge = accepted(result(app.password(first, db.binding, db.subject, "password"))) match {
        case app.PasswordResult.Challenge(_, id, _) => id
        case _                                      => fail("Expected a challenge")
      }
      accepted(result(app.begin(db.binding)))
      result(app.begin(db.binding)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
      accepted(app.browser.openSlot(db.wrongBinding))
      accepted(result(app.begin(db.wrongBinding)))
      val proof      = accepted(result(app.factor(first, db.binding, db.subject, challenge, "654321")))
      val completion = accepted(result(app.complete(proof)))
      db.clock.set(initial.plusSeconds(61))
      result(app.retireExpiredMaterial()) shouldBe Right(1)
      result(app.recover(first, db.binding)) shouldBe Right(Recovery.Committed(completion))
      result(app.begin(db.binding)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
      accepted(result(app.begin(db.wrongBinding)))
      result(app.begin(db.wrongBinding)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
      val thirdBinding = accepted(Digest256.fromBytes(syntheticHash("third-browser")))
      accepted(app.browser.openSlot(thirdBinding))
      result(app.begin(thirdBinding)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
      db.count("baseline_session") shouldBe 1L
    }

  it should "serialize competing per-binding admissions without consuming an independent binding's retained quota" in Using
    .resource(
      new Fixture
    ) { db =>
      val app        = db.host(capacity = 4, maxCeremoniesPerBinding = 2)
      val admissions = Vector.fill(8)(app.begin(db.binding))
      val results    = admissions.map(result)
      results.count(_.isRight) shouldBe 2
      results.count(_ == Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))) shouldBe 6
      accepted(app.browser.openSlot(db.wrongBinding))
      accepted(result(app.begin(db.wrongBinding)))
      accepted(result(app.begin(db.wrongBinding)))
      result(app.begin(db.wrongBinding)) shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    }

  it should "erase expired delivery material while preserving committed recovery and replay fences" in Using.resource(
    new Fixture
  ) { db =>
    val ceremony = db.ceremony()
    val attempt  = accepted(result(db.app.complete(db.proof(ceremony))))
    db.clock.set(initial.plusSeconds(61))
    db.app.deliver(attempt, db.binding) shouldBe Left(JdbcAuthError.Expired)
    result(db.app.retireExpiredMaterial()) shouldBe Right(1)
    result(db.app.retireExpiredMaterial()) shouldBe Right(0)
    db.count("baseline_material") shouldBe 0L
    result(db.host().recover(ceremony, db.binding)) shouldBe Right(Recovery.Committed(attempt))
    db.count("baseline_audit") shouldBe 1L
  }

  it should "bound redelivery and recheck policy after status-only recovery" in Using.resource(new Fixture) { db =>
    val ceremony = db.ceremony()
    val attempt  = accepted(result(db.app.complete(db.proof(ceremony))))
    val cookies  = Vector.fill(3)(accepted(db.app.deliver(attempt, db.binding)).transportValue)
    cookies.distinct.size shouldBe 1
    db.app.deliver(attempt, db.binding) shouldBe Left(JdbcAuthError.NotFound)
    result(db.app.recover(ceremony, db.binding)) shouldBe Right(Recovery.Committed(attempt))
    db.execute("UPDATE baseline_account SET enabled=FALSE")
    db.app.deliver(attempt, db.binding) shouldBe Left(JdbcAuthError.HostDenied)
    db.count("baseline_session") shouldBe 1L
  }

  it should "reject authenticated ciphertext transplanted between completion identities" in Using.resource(
    new Fixture
  ) { db =>
    val first  = db.committed()
    val second = db.committed()
    db.execute(
      s"UPDATE baseline_material SET payload=(SELECT payload FROM baseline_material WHERE attempt='$first') WHERE attempt='$second'"
    )
    db.app.deliver(second, db.binding) shouldBe Left(JdbcAuthError.StorageFailure)
    db.app.deliver(first, db.binding).isRight shouldBe true
    db.count("baseline_session") shouldBe 2L
  }

  it should "reject invalid credential length without partial host or browser writes" in Using.resource(new Fixture) {
    db =>
      val app      = db.host(entropy = _ => Array[Byte](1, 2, 3))
      val ceremony = accepted(result(app.begin(db.binding)))
      val proof = accepted(result(app.password(ceremony, db.binding, db.subject, "password"))) match {
        case app.PasswordResult.Ready(value) => value
        case _                               => fail("Unexpected challenge")
      }
      result(app.complete(proof)) shouldBe Left(TransactionFailure.RolledBack)
      Vector(
        "baseline_session",
        "baseline_audit",
        "baseline_material",
        "spoonbill_browser_session",
        "spoonbill_browser_completion"
      )
        .foreach(db.count(_) shouldBe 0L)
  }

  it should "latch expiry against a backward clock in the running host" in Using.resource(new Fixture) { db =>
    db.factorRequired()
    val ceremony  = db.ceremony()
    val challenge = db.challenge(ceremony)
    db.clock.set(initial.plusSeconds(61))
    result(db.app.factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
    db.clock.set(initial)
    result(db.app.factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
    val restarted = db.host()
    result(restarted.factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
  }

  it should "persist the clock checkpoint independently of rejected domain work and count its full cost" in Using
    .resource(new Fixture) { db =>
      db.factorRequired()
      val ceremony  = db.ceremony()
      val challenge = db.challenge(ceremony)
      db.clock.set(initial.plusSeconds(61))
      val (expired, measured) =
        db.probe.measure(result(db.app.factor(ceremony, db.binding, db.subject, challenge, "654321")))
      expired shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
      measured.counts.connections shouldBe 2L
      measured.counts.sqlExecutions shouldBe 2L
      measured.counts.commits shouldBe 1L
      measured.counts.rollbacks shouldBe 1L
      measured.activeConnectionsAfter shouldBe 0L
      db.clock.set(initial.plusSeconds(1))
      val restarted = db.host()
      result(restarted.factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
        Left(TransactionFailure.Rejected(OperationError.Expired))
    }

  it should "preserve expired active sessions across reconstruction with an earlier wall clock" in Using.resource(
    new Fixture
  ) { db =>
    val cookie = accepted(db.app.deliver(db.committed(), db.binding))
    accepted(db.app.browser.activate(cookie.hash, db.binding))
    db.clock.set(initial.plusSeconds(601))
    db.app.browser.validate(cookie.hash, db.binding).isLeft shouldBe true
    db.clock.set(initial.plusSeconds(1))
    db.host().browser.validate(cookie.hash, db.binding).isLeft shouldBe true
  }

  it should "retain an acknowledged clock when later domain writes roll back" in Using.resource(new Fixture) { db =>
    val proof = db.proof(db.ceremony())
    db.clock.set(initial.plusSeconds(10))
    result(
      db.app
        .complete(proof, stage => if (stage == "session") throw new IllegalStateException("Synthetic domain rollback"))
    ) shouldBe
      Left(TransactionFailure.RolledBack)
    db.count("baseline_session") shouldBe 0L
    db.count("baseline_audit") shouldBe 0L
    db.clock.set(initial)
    new JdbcReferenceClock(db.probe.dataSource, "baseline", "session", () => db.clock.get()).now() shouldBe initial
      .plusSeconds(10)
  }

  it should "recover exact nanoseconds after clock commit acknowledgment loss without publishing unacknowledged time" in Using
    .resource(new Fixture) { db =>
      def freshClock() = new JdbcReferenceClock(db.probe.dataSource, "baseline", "session", () => db.clock.get())
      val first        = freshClock()
      first.now() shouldBe initial
      val later = initial.plusSeconds(61).plusNanos(123456789)
      db.clock.set(later)
      db.failClockCommit.set(true)
      val failure = intercept[JdbcReferenceClock.ClockException](first.now())
      failure.failure shouldBe JdbcReferenceClock.Failure.CommitUnknown
      db.clock.set(initial)
      // A restarted helper must read the committed maximum even without an
      // acknowledged local cache. A retained helper must retry its pending value.
      freshClock().now() shouldBe later
      first.now() shouldBe later
      db.probe.measure(())._2.activeConnectionsAfter shouldBe 0L
    }

  it should "deny proof processing on a clock write failure and retain the candidate for retry" in Using.resource(
    new Fixture
  ) { db =>
    db.factorRequired()
    val ceremony  = db.ceremony()
    val challenge = db.challenge(ceremony)
    db.clock.set(initial.plusSeconds(61))
    db.failClockWrite.set(true)
    val failedClock = new JdbcReferenceClock(db.probe.dataSource, "baseline", "session", () => db.clock.get())
    intercept[JdbcReferenceClock.ClockException](
      failedClock.now()
    ).failure shouldBe JdbcReferenceClock.Failure.StorageFailure
    db.failClockWrite.set(true)
    result(db.app.factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe Left(
      TransactionFailure.RolledBack
    )
    db.clock.set(initial)
    result(db.app.factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
    result(db.host().factor(ceremony, db.binding, db.subject, challenge, "654321")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
    db.count("baseline_session") shouldBe 0L
  }

  for (abortFails <- Vector(false, true)) {
    it should s"dispose an unknown clock commit safely when abort failure is $abortFails" in Using.resource(
      new Fixture
    ) { db =>
      new JdbcReferenceClock(db.probe.dataSource, "baseline", "session", () => initial).now() shouldBe initial
      Using.resource(new ClockLifecycleFaults(db, commitFails = true, abortFails = abortFails)) { fault =>
        val clock = new JdbcReferenceClock(fault.source, "baseline", "session", () => initial.plusSeconds(61))
        intercept[JdbcReferenceClock.ClockException](
          clock.now()
        ).failure shouldBe JdbcReferenceClock.Failure.CommitUnknown
        fault.aborts.get() shouldBe 1L
        fault.closes.get() shouldBe (if (abortFails) 0L else 1L)
        fault.physical.get().get.isClosed shouldBe !abortFails
      }
      new JdbcReferenceClock(db.probe.dataSource, "baseline", "session", () => initial).now() shouldBe initial
    }
  }

  it should "quarantine failed clock rollback and abort without a pool return" in Using.resource(new Fixture) { db =>
    Using.resource(new ClockLifecycleFaults(db, rollbackFails = true, abortFails = true)) { fault =>
      val clock = new JdbcReferenceClock(fault.source, "baseline", "session", () => initial)
      intercept[JdbcReferenceClock.ClockException](
        clock.now()
      ).failure shouldBe JdbcReferenceClock.Failure.CommitUnknown
      fault.aborts.get() shouldBe 1L
      fault.closes.get() shouldBe 0L
      fault.physical.get().get.isClosed shouldBe false
    }
  }

  it should "preserve an acknowledged clock despite pool close failure" in Using.resource(new Fixture) { db =>
    Using.resource(new ClockLifecycleFaults(db, closeFails = true)) { fault =>
      val clock = new JdbcReferenceClock(fault.source, "baseline", "session", () => initial.plusNanos(1))
      clock.now() shouldBe initial.plusNanos(1)
      clock.now() shouldBe initial.plusNanos(1)
      fault.aborts.get() shouldBe 0L
      fault.closes.get() shouldBe 1L
      fault.physical.get().get.isClosed shouldBe true
    }
    new JdbcReferenceClock(db.probe.dataSource, "baseline", "session", () => initial).now() shouldBe initial.plusNanos(
      1
    )
  }

  /**
   * A host runner owns domain work after the joined Spoonbill program as well
   * as resource acquisition/outer commit. No caller marks a receipt committed.
   */
  private class ExistingHostRunner(db: Fixture) extends TransactionExecutor[Future, Direct, Connection] {
    val program: TransactionProgram[Direct] = TransactionProgram.direct
    val scopePolicy: TransactionScopePolicy = TransactionScopePolicy.ThreadConfined
    private val outer                       = new JdbcTransactionExecutor[Future](db.probe.dataSource, db.context)
    val writeAfterProgram                   = new AtomicBoolean(false)
    val failAfterWrite                      = new AtomicBoolean(false)
    db.execute("CREATE TABLE baseline_existing_host_outbox(id UUID PRIMARY KEY)")
    def transact[A](body: Connection => Direct[A]): Future[Either[TransactionFailure, A]] =
      outer.transact { connection =>
        val pending = body(connection)
        if (writeAfterProgram.get()) {
          Using.resource(connection.prepareStatement("INSERT INTO baseline_existing_host_outbox VALUES (?)")) { query =>
            query.setObject(1, db.id()); query.executeUpdate()
          }
          if (failAfterWrite.get()) throw new IllegalStateException("Synthetic outer host program failure")
        }
        pending
      }
  }

  it should "join an existing host runner through its actual outermost commit" in Using.resource(new Fixture) { db =>
    val runner   = new ExistingHostRunner(db)
    val app      = db.host(existingRunner = Some(runner))
    val ceremony = accepted(result(app.begin(db.binding)))
    val proof = accepted(result(app.password(ceremony, db.binding, db.subject, "password"))) match {
      case app.PasswordResult.Ready(value) => value
      case _                               => fail("Unexpected challenge")
    }
    runner.writeAfterProgram.set(true)
    val (completed, measurement) = db.probe.measure(result(app.complete(proof)))
    completed.isRight shouldBe true
    measurement.counts.sqlExecutions shouldBe 12L
    measurement.counts.connections shouldBe 1L
    measurement.counts.commits shouldBe 1L
    measurement.activeConnectionsAfter shouldBe 0L
    db.count("baseline_existing_host_outbox") shouldBe 1L
    db.count("baseline_session") shouldBe 1L
    app.deliver(accepted(completed), db.binding).isRight shouldBe true
  }

  it should "roll back the entire enlisted operation when the host fails after the browser program" in Using.resource(
    new Fixture
  ) { db =>
    val runner   = new ExistingHostRunner(db)
    val app      = db.host(existingRunner = Some(runner))
    val ceremony = accepted(result(app.begin(db.binding)))
    val proof = accepted(result(app.password(ceremony, db.binding, db.subject, "password"))) match {
      case app.PasswordResult.Ready(value) => value
      case _                               => fail("Unexpected challenge")
    }
    runner.writeAfterProgram.set(true)
    runner.failAfterWrite.set(true)
    val (completed, measurement) = db.probe.measure(result(app.complete(proof)))
    completed shouldBe Left(TransactionFailure.RolledBack)
    measurement.counts.connections shouldBe 1L
    measurement.counts.commits shouldBe 0L
    measurement.counts.rollbacks shouldBe 1L
    measurement.activeConnectionsAfter shouldBe 0L
    Vector(
      "baseline_existing_host_outbox",
      "baseline_session",
      "baseline_audit",
      "baseline_material",
      "spoonbill_browser_session",
      "spoonbill_browser_completion"
    ).foreach(db.count(_) shouldBe 0L)
    result(db.app.recover(ceremony, db.binding)) shouldBe Right(Recovery.NotCommitted)
  }

  it should "recover an enlisted host commit whose acknowledgement was lost" in Using.resource(new Fixture) { db =>
    val runner   = new ExistingHostRunner(db)
    val app      = db.host(existingRunner = Some(runner))
    val ceremony = accepted(result(app.begin(db.binding)))
    val proof = accepted(result(app.password(ceremony, db.binding, db.subject, "password"))) match {
      case app.PasswordResult.Ready(value) => value
      case _                               => fail("Unexpected challenge")
    }
    runner.writeAfterProgram.set(true)
    db.failCommit.set(true)
    result(app.complete(proof)) shouldBe Left(TransactionFailure.CommitUnknown)
    db.count("baseline_existing_host_outbox") shouldBe 1L
    db.count("baseline_session") shouldBe 1L
    accepted(result(db.app.recover(ceremony, db.binding))) match {
      case Recovery.Committed(attempt) => db.app.deliver(attempt, db.binding).isRight shouldBe true
      case _                           => fail("The outer host commit was lost")
    }
    result(app.complete(proof)) shouldBe Left(TransactionFailure.Rejected(OperationError.PermitUsed))
  }
}
