package spoonbill.security.jdbc

import avocet.Id
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
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.action.{AccessDecision, InvocationBinding}
import spoonbill.browserauthbaseline.ReferencePolicy
import spoonbill.effect.Effect
import spoonbill.performance.PerformanceProbe
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.security.jdbc.baseline.{JdbcBrowserSecurity, JdbcReferenceGate, JdbcReferenceHost}
import spoonbill.security.transaction.{OperationError, TransactionFailure}
import spoonbill.server.SessionAccessDenied
import spoonbill.state.{StateDeserializer, StateSerializer}
import spoonbill.web.{PathAndQuery, Request}

class JdbcBrowserSecuritySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private case class Page(protectedPage: Boolean = false, subject: Option[UUID] = None, count: Int = 0)
  private given StateSerializer[Page]      = (value: Page) => Array.emptyByteArray
  private given StateDeserializer[Page]    = (_: Array[Byte]) => None
  private val start                        = Instant.parse("2026-10-10T12:00:00Z")
  private val binding                      = "synthetic-binding-cookie"
  private val qsid                         = Qsid("device", "reference-view")
  private def owner(n: Long): ConnectionId = ConnectionId.fromUuid(new UUID(0L, n))
  private def invocation(n: Long): InvocationBinding =
    new InvocationBinding(InvocationId.fromUuid(new UUID(1L, n)), owner(n))
  private def result[A](value: Future[A]): A         = Await.result(value, 20.seconds)
  private def accepted[E, A](value: Either[E, A]): A = value.fold(error => fail(error.toString), identity)
  private def request(cookie: Option[String] = None, origin: String = "http://localhost:8080"): Request[Unit] =
    Request(
      Request.Method.Get,
      PathAndQuery.Root,
      Seq("Origin" -> origin),
      None,
      (),
      s"baseline_binding=$binding" + cookie.fold("")(value => s";baseline_session=$value")
    )

  private class Fixture(
    limits: JdbcBrowserSecurity.Limits = JdbcBrowserSecurity.Limits(),
    factorRequired: Boolean = false,
    referencePolicy: Option[ReferencePolicy] = None
  ) extends AutoCloseable {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", fail("Run with scripts/with-test-postgres.sh"))
    require(
      url.startsWith("jdbc:postgresql://") && Set("localhost", "127.0.0.1", "::1", "[::1]")
        .contains(URI.create(url.stripPrefix("jdbc:")).getHost)
    )
    private val schema            = "guarded_baseline_" + UUID.randomUUID().toString.replace("-", "")
    private val workers           = Executors.newFixedThreadPool(4)
    val context: ExecutionContext = ExecutionContext.fromExecutor(workers)
    val clock                     = new AtomicReference(start)
    private val sequence          = new AtomicLong()
    val failNextCommit            = new AtomicBoolean(false)
    val commitBarrier             = new AtomicReference(Option.empty[(CountDownLatch, CountDownLatch)])
    val forbiddenClockThread      = new AtomicReference(Option.empty[Thread])
    val subject                   = new UUID(0L, 1L)
    def raw(): Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      DriverManager.getConnection(url, properties)
    }
    Using.resource(raw())(connection =>
      Using.resource(connection.createStatement())(_.execute(s"CREATE SCHEMA $schema"))
    )
    val source: DataSource = new DataSource {
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
                if (
                  method.getName == "prepareStatement" && Option(arguments).toVector.flatten.headOption
                    .exists(_.toString.startsWith("INSERT INTO baseline_clock"))
                ) {
                  if (forbiddenClockThread.get().contains(Thread.currentThread()))
                    throw new SQLException("Clock checkpoint attempted on the asynchronous caller")
                  clockConnection = true
                }
                val response =
                  try method.invoke(connection, Option(arguments).getOrElse(Array.empty[Object])*)
                  catch {
                    case error: InvocationTargetException => throw error.getCause
                  }
                if (method.getName == "commit" && !clockConnection) {
                  commitBarrier.getAndSet(None).foreach { case (arrived, release) =>
                    arrived.countDown()
                    if (!release.await(10, TimeUnit.SECONDS)) throw new SQLException("Commit barrier timed out")
                  }
                  if (failNextCommit.compareAndSet(true, false))
                    throw new SQLException("Synthetic lost acknowledgement")
                }
                response
              }
            }
          )
          .asInstanceOf[Connection]
      }
      def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
      def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
      def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
      def getLoginTimeout: Int                                      = 0
      def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
      def getParentLogger: Logger                                   = Logger.getLogger("baseline.guard.test")
      def isWrapperFor(kind: Class[?]): Boolean                     = false
      def unwrap[T](kind: Class[T]): T                              = throw new SQLFeatureNotSupportedException()
    }
    val probe = new PerformanceProbe(source)
    def bytes(size: Int): Array[Byte] =
      JdbcReferenceHost.syntheticHash("entropy-" + sequence.incrementAndGet()).take(size)
    private var instances = Vector.empty[JdbcBrowserSecurity[Page, Int]]
    def freshSecurity(afterCommit: UUID => Unit = _ => ()): JdbcBrowserSecurity[Page, Int] = {
      val instance = new JdbcBrowserSecurity[Page, Int](
        probe.dataSource,
        context,
        "baseline",
        "session",
        () => clock.get(),
        bytes,
        () => new UUID(1L, sequence.incrementAndGet()),
        new JdbcReferenceHost.MaterialCipher(JdbcReferenceHost.syntheticHash("test-key"), () => bytes(12)),
        () => Page(),
        _.protectedPage,
        (page, principal) => page.copy(subject = principal.map(_.subject)),
        _.count,
        (page, count) => page.copy(count = count),
        singleNodeExclusiveWriters = true,
        limits = referencePolicy.fold(limits)(JdbcBrowserSecurity.limitsFor),
        afterPreparedCommit = afterCommit,
        referencePolicy = referencePolicy
      )
      instances :+= instance
      instance
    }
    val security = freshSecurity()
    Using.resource(source.getConnection) { connection =>
      security.initialize(connection)
      Using.resource(connection.prepareStatement("INSERT INTO baseline_account VALUES (?,1,TRUE,?,?)")) { query =>
        query.setObject(1, subject);
        query.setBytes(2, referencePolicy.fold(JdbcReferenceHost.syntheticHash("password"))(_.proof.hash("password")))
        if (factorRequired) query.setBytes(3, JdbcReferenceHost.syntheticHash("123456"))
        else query.setNull(3, java.sql.Types.BINARY)
        query.executeUpdate()
      }
    }
    accepted(result(security.bootstrapBinding(binding)))
    def create(id: Qsid = qsid): Unit = { result(security.storage.create(id.deviceId, id.sessionId, Page())); () }
    def open(n: Long, cookie: Option[String] = None, id: Qsid = qsid) = result(
      security.open(id, request(cookie), owner(n))
    )
    def login(n: Long = 1): String = {
      val ceremony = accepted(result(security.begin(owner(n))))
      val attempt = accepted(result(security.password(owner(n), ceremony, subject, "password"))) match {
        case security.Reply.Prepared(id) => id
        case _                           => fail("Unexpected factor")
      }
      accepted(result(security.deliver(request(), attempt))).transportValue
    }
    def authenticated(): (String, spoonbill.server.SessionGuard[Future, Page]) = {
      create(); open(1); val cookie = login(); cookie -> open(2, Some(cookie))
    }
    def scalar(sql: String): Long = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.createStatement()) { query =>
        Using.resource(query.executeQuery(sql)) { rows =>
          rows.next(); rows.getLong(1)
        }
      }
    }
    def execute(sql: String): Unit = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(sql)); ()
    }
    def close(): Unit = {
      instances.foreach(instance => result(instance.close()))
      workers.shutdownNow(); workers.awaitTermination(20, TimeUnit.SECONDS)
      Using.resource(raw())(connection =>
        Using.resource(connection.createStatement())(_.execute(s"DROP SCHEMA $schema CASCADE"))
      )
    }
  }

  "The JDBC guarded reference" should "capture authentication only on the fresh physical handshake and compare full authority" in Using
    .resource(new Fixture) { db =>
      db.create()
      val anonymous = db.open(1)
      result(anonymous.connected(Page())).subject shouldBe None
      val cookie = db.login()
      result(db.security.authority.resolve(invocation(1))).isLeft shouldBe true
      val current = db.open(2, Some(cookie))
      current.sensitive shouldBe None
      current.viewSnapshots.isDefined shouldBe true
      val principal = accepted(result(db.security.authority.resolve(invocation(2))))
      principal.subject shouldBe db.subject
      result(current.connected(Page())).subject shouldBe Some(db.subject)
      result(current.authorize(Page(protectedPage = true))) shouldBe ()
      result(db.security.authority.revalidate(invocation(2), principal)) shouldBe AccessDecision.Allowed
      result(
        db.security.authority.revalidate(invocation(2), principal.copy(scope = "other"))
      ) should not be AccessDecision.Allowed
      intercept[SessionAccessDenied](result(anonymous.authorize(Page())))
    }

  it should "project presentation onto fresh authority and fence takeover, old snapshots, writes and close" in Using
    .resource(new Fixture) { db =>
      val (cookie, first) = db.authenticated()
      val session         = first.viewSnapshots.get
      val initialized     = result(first.connected(Page()))
      result(session.initialize(initialized)).subject shouldBe Some(db.subject)
      result(session.commit(initialized.copy(count = 7)))
      val old      = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
      val snapshot = result(old.snapshot)
      val next     = db.open(3, Some(cookie))
      val fresh    = result(next.connected(Page()))
      result(next.viewSnapshots.get.initialize(fresh)) shouldBe fresh.copy(count = 7)
      intercept[SessionAccessDenied](result(old.write(Id.TopLevel, Page(count = 99))))
      intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
      result(first.close())
      result(next.authorize(fresh)) shouldBe ()
      result(db.security.resources).active shouldBe 1
    }

  it should "permanently detach revoked presentation and require fresh anonymous bootstrap" in Using.resource(
    new Fixture
  ) { db =>
    val (_, current) = db.authenticated()
    val page         = result(current.connected(Page(count = 42)))
    result(current.viewSnapshots.get.initialize(page))
    val manager  = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
    val snapshot = result(manager.snapshot)
    accepted(result(db.security.logout(owner(2))))
    intercept[SessionAccessDenied](result(manager.read[Page](Id.TopLevel)))
    intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
    result(db.security.storage.exists(qsid.deviceId, qsid.sessionId)) shouldBe false
    val fresh = result(db.security.resume(qsid, request(), owner(3)).get)
    result(fresh.viewSnapshots.get.initialize(result(fresh.connected(Page())))) shouldBe Page()
  }

  it should "authorize a protected one-use mutation in its single joined transaction" in Using.resource(new Fixture) {
    db =>
      db.authenticated()
      val principal           = accepted(result(db.security.authority.resolve(invocation(2))))
      val permit              = accepted(result(db.security.actionAuthority(owner(2), principal)))
      val (changed, measured) = db.probe.measure(result(db.security.protectedAction(owner(2), principal, permit)))
      changed shouldBe Right(1)
      db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 2L
      measured.counts.connections shouldBe 1L
      measured.counts.commits shouldBe 1L
      measured.activeConnectionsAfter shouldBe 0L
      result(db.security.protectedAction(owner(2), principal, permit)) shouldBe
        Left(TransactionFailure.Rejected(OperationError.PermitUsed))
      val later = accepted(result(db.security.actionAuthority(owner(2), principal)))
      accepted(result(db.security.changeAccount(db.subject, enabled = false)))
      result(db.security.protectedAction(owner(2), principal, later)).isLeft shouldBe true
      db.scalar("SELECT changes FROM baseline_preference") shouldBe 1L
      db.scalar("SELECT count(*) FROM spoonbill_operation_outcome") should be >= 1L
  }

  it should "discard staged publication after unknown commit and never restore an old manager" in Using.resource(
    new Fixture
  ) { db =>
    val (_, current) = db.authenticated()
    val snapshot     = current.viewSnapshots.get
    result(snapshot.initialize(result(current.connected(Page()))))
    val manager = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
    db.failNextCommit.set(true)
    intercept[SessionAccessDenied](result(snapshot.commit(Page(subject = Some(db.subject), count = 99))))
    intercept[SessionAccessDenied](result(manager.read[Page](Id.TopLevel)))
    result(db.security.resources).nodes shouldBe 0
  }

  it should "hold publication and queued revocation until actual commit acknowledgement" in Using.resource(
    new Fixture
  ) { db =>
    val (_, current) = db.authenticated()
    val projection   = current.viewSnapshots.get
    val page         = result(current.connected(Page(count = 1)))
    result(projection.initialize(page))
    val old      = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
    val snapshot = result(old.snapshot)
    val arrived  = new CountDownLatch(1)
    val release  = new CountDownLatch(1)
    db.commitBarrier.set(Some(arrived -> release))
    val publishing = projection.commit(page.copy(count = 2))
    try {
      arrived.await(10, TimeUnit.SECONDS) shouldBe true
      snapshot.apply[Page](Id.TopLevel).map(_.count) shouldBe Some(1)
      val revoking = db.security.changeAccount(db.subject, enabled = false)
      revoking.isCompleted shouldBe false
      release.countDown()
      result(publishing)
      accepted(result(revoking))
      intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
      intercept[SessionAccessDenied](result(old.read[Page](Id.TopLevel)))
    } finally release.countDown()
  }

  it should "bound queued work without consuming waiting connections and drain safely on shutdown" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(queued = 2))
  ) { db =>
    val (_, current) = db.authenticated()
    val arrived      = new CountDownLatch(1)
    val release      = new CountDownLatch(1)
    db.commitBarrier.set(Some(arrived -> release))
    val active = current.authorize(Page())
    try {
      arrived.await(10, TimeUnit.SECONDS) shouldBe true
      val one = db.security.storage.exists(qsid.deviceId, qsid.sessionId)
      val two = db.security.storage.exists(qsid.deviceId, qsid.sessionId)
      intercept[SessionAccessDenied](result(db.security.storage.exists(qsid.deviceId, qsid.sessionId)))
      result(db.security.resources).queued shouldBe 2
      val closing = db.security.close()
      intercept[SessionAccessDenied](result(one))
      intercept[SessionAccessDenied](result(two))
      closing.isCompleted shouldBe false
      release.countDown()
      result(active); result(closing)
      result(db.security.resources).nodes shouldBe 0
    } finally release.countDown()
  }

  it should "bound callbacks before dispatch while preserving reserved release maintenance and shutdown" in Using
    .resource(new Fixture(JdbcBrowserSecurity.Limits(queued = 1))) { db =>
      db.create(); val guard = db.open(1)
      val arrived            = new CountDownLatch(1)
      val release            = new CountDownLatch(1)
      db.commitBarrier.set(Some(arrived -> release))
      val running = guard.authorize(Page())
      try {
        arrived.await(10, TimeUnit.SECONDS) shouldBe true
        val pending = db.security.begin(owner(1))
        db.security.callbackCounts._1 shouldBe 1
        val (_, rejected) = db.probe.measure {
          intercept[SessionAccessDenied](result(db.security.begin(owner(1))))
        }
        rejected.counts.connections shouldBe 0L
        val cleanup     = guard.close()
        val maintenance = db.security.retireExpiredMaterial()
        val closing     = db.security.close()
        closing.isCompleted shouldBe false
        release.countDown()
        result(running)
        intercept[SessionAccessDenied](result(pending))
        result(cleanup); result(maintenance); result(closing)
        db.security.callbackCounts shouldBe (0 -> 1)
        result(db.security.resources).pendingCallbacks shouldBe 0
      } finally release.countDown()
    }

  it should "bound retained operation outcomes independently of the larger audit capacity" in Using.resource(
    new Fixture(
      referencePolicy = Some(
        ReferencePolicy.Default.copy(retainedEntries = 2, auditRecords = 4, liveCeremonies = 1, accountCount = 2)
      )
    )
  ) { db =>
    val (cookie, _) = db.authenticated()
    val principal   = accepted(result(db.security.protectedPrincipal(request(Some(cookie)))))
    (1 to 2).foreach { expected =>
      val authority = accepted(result(db.security.actionAuthority(owner(2), principal)))
      result(db.security.protectedAction(owner(2), principal, authority)) shouldBe Right(expected)
    }
    val denied = accepted(result(db.security.actionAuthority(owner(2), principal)))
    result(db.security.protectedAction(owner(2), principal, denied)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    db.scalar("SELECT count(*) FROM spoonbill_operation_outcome") shouldBe 2L
    db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 3L
    db.scalar("SELECT changes FROM baseline_preference") shouldBe 2L
    val expiring = accepted(result(db.security.actionAuthority(owner(2), principal)))
    db.clock.set(start.plusSeconds(ReferencePolicy.Default.operationAuthoritySeconds))
    result(db.security.protectedAction(owner(2), principal, expiring)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
  }

  it should "apply the shared browser and tuple rate policy before expensive proof work" in Using.resource(
    new Fixture(referencePolicy = Some(ReferencePolicy.Default))
  ) { db =>
    db.create(); db.open(1)
    val ceremony = accepted(result(db.security.begin(owner(1))))
    (1 to ReferencePolicy.Default.proofAttempts).foreach { _ =>
      result(db.security.password(owner(1), ceremony, db.subject, "wrong")).isLeft shouldBe true
    }
    result(db.security.password(owner(1), ceremony, db.subject, "password")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    val second = Qsid("other-device", "other-view")
    db.create(second)
    accepted(result(db.security.bootstrapBinding("fresh-browser")))
    result(db.security.open(second, request().withCookie("baseline_binding", "fresh-browser"), owner(3)))
    val fresh = accepted(result(db.security.begin(owner(3))))
    result(db.security.password(owner(3), fresh, db.subject, "password")).isRight shouldBe true
  }

  it should "dispatch asynchronous clock checkpoints to workers and leave removal to the owning guard" in Using
    .resource(new Fixture) { db =>
      val (_, guard) = db.authenticated()
      db.forbiddenClockThread.set(Some(Thread.currentThread()))
      db.clock.set(start.plusSeconds(1))
      val principal = accepted(result(db.security.authority.resolve(invocation(2))))
      db.clock.set(start.plusSeconds(2))
      accepted(result(db.security.begin(owner(2))))
      db.clock.set(start.plusSeconds(3))
      val permit = accepted(result(db.security.actionAuthority(owner(2), principal)))
      db.clock.set(start.plusSeconds(4))
      result(db.security.protectedAction(owner(2), principal, permit)) shouldBe Right(1)
      db.clock.set(start.plusSeconds(5))
      val (_, measured) = db.probe.measure(db.security.storage.remove(qsid.deviceId, qsid.sessionId))
      measured.counts.connections shouldBe 0L
      result(guard.authorize(Page(protectedPage = true))) shouldBe ()
      result(guard.close()) shouldBe ()
      result(db.security.resources).active shouldBe 0
      db.scalar("SELECT count(*) FROM spoonbill_browser_view WHERE owner_id IS NOT NULL") shouldBe 0L
    }

  it should "keep synchronous snapshot expiry and session rejection durable across restart" in Using.resource(
    new Fixture
  ) { db =>
    val (cookie, _) = db.authenticated()
    val manager     = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
    val snapshot    = result(manager.snapshot)
    db.clock.set(start.plusSeconds(601))
    val (_, measured) = db.probe.measure {
      intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
    }
    measured.counts.connections shouldBe 1L
    measured.counts.sqlExecutions shouldBe 1L
    measured.counts.commits shouldBe 1L
    measured.activeConnectionsAfter shouldBe 0L
    result(db.security.close())
    db.clock.set(start.plusSeconds(1))
    val restarted = db.freshSecurity()
    result(restarted.protectedPrincipal(request(Some(cookie)))).isLeft shouldBe true
    intercept[SessionAccessDenied](result(restarted.resume(qsid, request(Some(cookie)), owner(3)).get))
  }

  it should "keep factor expiry durable through a guarded adapter restart" in Using.resource(
    new Fixture(factorRequired = true)
  ) { db =>
    db.create(); db.open(1)
    val ceremony = accepted(result(db.security.begin(owner(1))))
    val challenge = accepted(result(db.security.password(owner(1), ceremony, db.subject, "password"))) match {
      case db.security.Reply.Challenge(_, value) => value
      case _                                     => fail("Expected challenge")
    }
    db.clock.set(start.plusSeconds(61))
    result(db.security.factorForCeremony(owner(1), ceremony, challenge, "123456")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
    result(db.security.close())
    db.clock.set(start.plusSeconds(1))
    val restarted = db.freshSecurity()
    result(restarted.bootstrapRecovery(request())) shouldBe Right(None)
    result(restarted.resume(qsid, request(), owner(2)).get)
    result(restarted.factorForCeremony(owner(2), ceremony, challenge, "123456")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.Expired))
  }

  it should "expire retained views and reject unknown recovery identifiers without durable allocation" in Using
    .resource(new Fixture(JdbcBrowserSecurity.Limits(reconnectSeconds = 5))) { db =>
      db.create()
      val first = db.open(1)
      result(first.close())
      db.clock.set(start.plusSeconds(5))
      result(db.security.storage.exists(qsid.deviceId, qsid.sessionId)) shouldBe false
      intercept[SessionAccessDenied](result(db.security.open(Qsid("foreign", "view"), request(), owner(9))))
      db.scalar("SELECT count(*) FROM spoonbill_browser_view") shouldBe 1L
    }

  it should "enforce same-origin completion and protected HTTP checks" in Using.resource(new Fixture) { db =>
    db.create(); db.open(1)
    val ceremony = accepted(result(db.security.begin(owner(1))))
    val attempt = accepted(result(db.security.password(owner(1), ceremony, db.subject, "password"))) match {
      case db.security.Reply.Prepared(id) => id
      case _                              => fail("Unexpected challenge")
    }
    intercept[SessionAccessDenied](result(db.security.deliver(request(origin = "http://wrong.invalid"), attempt)))
    intercept[SessionAccessDenied](result(db.security.authorizeHttp(request(), Page(protectedPage = true))))
    val cookie = accepted(result(db.security.deliver(request(), attempt))).transportValue
    db.open(2, Some(cookie))
    result(db.security.authorizeHttp(request(Some(cookie)), Page(protectedPage = true))) shouldBe ()
  }

  it should "detach synchronous snapshots before takeover and release commit acknowledgement" in Using.resource(
    new Fixture
  ) { db =>
    db.create(); db.open(1)
    val old      = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
    val snapshot = result(old.snapshot)
    val arrived  = new CountDownLatch(1)
    val release  = new CountDownLatch(1)
    db.commitBarrier.set(Some(arrived -> release))
    val opening = db.security.open(qsid, request(), owner(2))
    try {
      arrived.await(10, TimeUnit.SECONDS) shouldBe true
      intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
      release.countDown()
      val successor = result(opening)
      intercept[SessionAccessDenied](result(old.read[Page](Id.TopLevel)))
      result(successor.authorize(Page())) shouldBe ()
      val nextSnapshot   = result(result(db.security.storage.get(qsid.deviceId, qsid.sessionId)).snapshot)
      val closingArrived = new CountDownLatch(1)
      val closingRelease = new CountDownLatch(1)
      db.commitBarrier.set(Some(closingArrived -> closingRelease))
      val closing = successor.close()
      try {
        closingArrived.await(10, TimeUnit.SECONDS) shouldBe true
        intercept[SessionAccessDenied](nextSnapshot.apply[Page](Id.TopLevel))
      } finally closingRelease.countDown()
      result(closing)
    } finally release.countDown()
  }

  it should "discard a late committed view claim after its local bootstrap expires" in Using.resource(new Fixture) {
    db =>
      db.create()
      val arrived = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      db.commitBarrier.set(Some(arrived -> release))
      val opening = db.security.open(qsid, request(), owner(1))
      try {
        arrived.await(10, TimeUnit.SECONDS) shouldBe true
        db.clock.set(start.plusSeconds(31))
        result(db.security.resources).bootstrap shouldBe 0
        release.countDown()
        intercept[SessionAccessDenied](result(opening))
        result(db.security.resources).nodes shouldBe 0
        db.scalar("SELECT count(*) FROM spoonbill_browser_view WHERE owner_id IS NOT NULL") shouldBe 0L
      } finally release.countDown()
  }

  it should "expire bootstrap snapshots and rate-limit proofs before any password lookup" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(proofAttemptsPerMinute = 1))
  ) { db =>
    db.create()
    val bootstrap = result(db.security.storage.get(qsid.deviceId, qsid.sessionId))
    val snapshot  = result(bootstrap.snapshot)
    db.clock.set(start.plusSeconds(31))
    intercept[SessionAccessDenied](snapshot.apply[Page](Id.TopLevel))
    result(db.security.resources)
    db.create(); db.open(1)
    val ceremony = accepted(result(db.security.begin(owner(1))))
    result(db.security.password(owner(1), ceremony, db.subject, "wrong")).isLeft shouldBe true
    val (refused, counters) = db.probe.measure(result(db.security.password(owner(1), ceremony, db.subject, "password")))
    refused shouldBe Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    counters.counts.sqlExecutions shouldBe 0L
    counters.counts.connections shouldBe 0L
  }

  it should "settle gate admission and shutdown when the configured executor rejects dispatch" in {
    given ExecutionContext = ExecutionContext.fromExecutor((_: Runnable) =>
      throw new java.util.concurrent.RejectedExecutionException("Synthetic dispatch rejection")
    )
    val gate = new JdbcReferenceGate(1)
    intercept[SessionAccessDenied](result(gate.submit(Future.successful(()))))
    result(gate.close()) shouldBe ()
    gate.queued shouldBe 0
  }

  it should "run admitted work outside its monitor even with an inline execution context" in {
    given ExecutionContext = ExecutionContext.parasitic
    val gate               = new JdbcReferenceGate(1)
    result(gate.submit {
      Thread.holdsLock(gate) shouldBe false
      Future.successful(())
    }) shouldBe ()
    result(gate.close()) shouldBe ()
  }

  it should "bound retained view epochs independently of host history and preserve rows after release" in Using
    .resource(
      new Fixture(
        referencePolicy = Some(
          ReferencePolicy.Default.copy(
            retainedViews = 2,
            retainedEntries = 4,
            auditRecords = 4,
            liveCeremonies = 1,
            accountCount = 2
          )
        )
      )
    ) { db =>
      db.create(); result(db.open(1).close())
      val second = Qsid("device", "second-retained-view")
      db.create(second); result(db.open(2, id = second).close())
      db.scalar("SELECT count(*) FROM spoonbill_browser_view") shouldBe 2L
      db.scalar("SELECT sum(epoch) FROM spoonbill_browser_view") shouldBe 4L
      val third = Qsid("device", "third-retained-view")
      db.create(third)
      intercept[SessionAccessDenied](db.open(3, id = third))
      db.scalar("SELECT count(*) FROM spoonbill_browser_view") shouldBe 2L
      db.scalar("SELECT sum(epoch) FROM spoonbill_browser_view") shouldBe 4L
      db.scalar("SELECT count(*) FROM spoonbill_browser_view WHERE owner_id IS NOT NULL") shouldBe 0L
      db.scalar("SELECT generation FROM spoonbill_browser_slot") shouldBe 0L
      db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 0L
      result(db.security.resources).bootstrap shouldBe 0
    }

  it should "bound retained browser lineages without resetting their generations" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(retainedBindings = 1))
  ) { db =>
    result(db.security.bootstrapBinding(binding)) shouldBe Right(0L)
    result(db.security.bootstrapBinding("another-binding")) shouldBe
      Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    accepted(result(db.security.logoutBinding(binding))) shouldBe 1L
    result(db.security.bootstrapBinding(binding)) shouldBe Right(1L)
    db.scalar("SELECT count(*) FROM spoonbill_browser_slot") shouldBe 1L
  }

  it should "reject expired unswept bootstrap directly at acquisition" in Using.resource(new Fixture) { db =>
    db.create()
    db.clock.set(start.plusSeconds(31))
    intercept[SessionAccessDenied](result(db.security.open(qsid, request(), owner(1))))
    db.scalar("SELECT count(*) FROM spoonbill_browser_view") shouldBe 0L
  }

  it should "deny anonymous capture when the binding already owns an authenticated slot" in Using.resource(
    new Fixture
  ) { db =>
    db.authenticated()
    val other = Qsid("device", "missing-cookie")
    db.create(other)
    intercept[SessionAccessDenied](result(db.security.open(other, request(), owner(3))))
    result(db.security.protectedPrincipal(request())).isLeft shouldBe true
  }

  it should "wire the official completion callbacks and resume a factor without a UI subject" in Using.resource(
    new Fixture(factorRequired = true)
  ) { db =>
    db.create(); db.open(1)
    val ceremony = accepted(result(db.security.begin(owner(1))))
    val challenge = accepted(result(db.security.password(owner(1), ceremony, db.subject, "password"))) match {
      case db.security.Reply.Challenge(original, challenge) => original shouldBe ceremony; challenge
      case _                                                => fail("Expected factor challenge")
    }
    val attempt = accepted(result(db.security.factorForCeremony(owner(1), ceremony, challenge, "123456"))) match {
      case db.security.Reply.Prepared(id) => id
      case _                              => fail("Expected preparation")
    }
    db.security.completionConfig.allowedOrigins shouldBe Set("http://localhost:8080")
    result(db.security.completionConfig.deliver(attempt, binding)).isDefined shouldBe true
    val cookie = accepted(result(db.security.deliver(request(), attempt))).transportValue
    db.open(2, Some(cookie))
    accepted(result(db.security.protectedPrincipal(request(Some(cookie))))).subject shouldBe db.subject
    result(db.security.completionConfig.logout(binding, Some(cookie))) shouldBe ()
    result(db.security.protectedPrincipal(request(Some(cookie)))).isLeft shouldBe true
    result(db.security.resources).active shouldBe 0
  }

  it should "commit the protected audit atomically and roll back the mutation when auditing fails" in Using.resource(
    new Fixture
  ) { db =>
    db.authenticated()
    val principal = accepted(result(db.security.authority.resolve(invocation(2))))
    val permit    = accepted(result(db.security.actionAuthority(owner(2), principal)))
    db.execute("ALTER TABLE baseline_audit ADD CONSTRAINT reject_new_audit CHECK(FALSE) NOT VALID")
    result(db.security.protectedAction(owner(2), principal, permit)) shouldBe Left(TransactionFailure.RolledBack)
    db.scalar("SELECT count(*) FROM baseline_preference") shouldBe 0L
    db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 1L
    db.scalar("SELECT count(*) FROM spoonbill_operation_outcome") shouldBe 0L
    result(db.security.protectedAction(owner(2), principal, permit)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.PermitUsed))
  }

  it should "apply one retained audit capacity to login and protected actions" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(auditRecords = 1))
  ) { db =>
    db.authenticated()
    val principal = accepted(result(db.security.authority.resolve(invocation(2))))
    val permit    = accepted(result(db.security.actionAuthority(owner(2), principal)))
    result(db.security.protectedAction(owner(2), principal, permit)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.CapacityExceeded))
    db.scalar("SELECT count(*) FROM baseline_preference") shouldBe 0L
    db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 1L
  }

  it should "recover original committed metadata on a new instance and resume only known durable views" in Using
    .resource(new Fixture) { db =>
      db.create(); val oldGuard = db.open(1)
      result(oldGuard.viewSnapshots.get.initialize(Page(count = 42)))
      val ceremony = accepted(result(db.security.begin(owner(1))))
      val attempt = accepted(result(db.security.password(owner(1), ceremony, db.subject, "password"))) match {
        case db.security.Reply.Prepared(id) => id
        case _                              => fail("Unexpected challenge")
      }
      result(db.security.close())
      val restarted = db.freshSecurity()
      val metadata  = accepted(result(restarted.bootstrapRecovery(request()))).get
      metadata.ceremony shouldBe ceremony
      metadata.subject shouldBe db.subject
      metadata.preparationPending shouldBe true
      val restored = result(restarted.resume(qsid, request(), owner(3)).get)
      result(restored.viewSnapshots.get.initialize(result(restored.connected(Page())))) shouldBe Page()
      result(restarted.recover(owner(3), metadata.ceremony)) shouldBe Right(
        JdbcReferenceHost.Recovery.Committed(attempt)
      )
      val cookie = accepted(result(restarted.deliver(request(), attempt))).transportValue
      val active = result(restarted.open(qsid, request(Some(cookie)), owner(4)))
      result(active.connected(Page())).subject shouldBe Some(db.subject)
      db.scalar("SELECT count(*) FROM baseline_session") shouldBe 1L
      db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 1L
      val unknownId = Qsid("device", "unknown-view")
      val missing   = result(restarted.resume(unknownId, request(Some(cookie)), owner(5)).get)
      missing.viewSnapshots shouldBe None
      result(restarted.storage.exists(unknownId.deviceId, unknownId.sessionId)) shouldBe false
      intercept[SessionAccessDenied](result(missing.authorize(Page())))
      db.scalar("SELECT count(*) FROM spoonbill_browser_view") shouldBe 1L
    }

  it should "resume the original factor association after instance restart" in Using.resource(
    new Fixture(factorRequired = true)
  ) { db =>
    db.create(); db.open(1)
    val ceremony = accepted(result(db.security.begin(owner(1))))
    val challenge = accepted(result(db.security.password(owner(1), ceremony, db.subject, "password"))) match {
      case db.security.Reply.Challenge(_, id) => id
      case _                                  => fail("Expected challenge")
    }
    result(db.security.close())
    val restarted = db.freshSecurity()
    val metadata  = accepted(result(restarted.bootstrapRecovery(request()))).get
    metadata.ceremony shouldBe ceremony
    metadata.challenge shouldBe Some(challenge)
    metadata.preparationPending shouldBe false
    result(restarted.resume(qsid, request(), owner(2)).get)
    accepted(result(restarted.factorForCeremony(owner(2), ceremony, challenge, "123456"))) match {
      case restarted.Reply.Prepared(_) => succeed
      case _                           => fail("Expected preparation")
    }
    db.scalar("SELECT count(*) FROM baseline_session") shouldBe 1L
  }

  it should "keep admitted restart metadata unresolved until a fenced reconciliation" in Using.resource(new Fixture) {
    db =>
      db.create(); db.open(1)
      val ceremony = accepted(result(db.security.begin(owner(1))))
      result(db.security.close())
      val raw = new JdbcReferenceHost(
        db.source,
        db.context,
        "baseline",
        "session",
        () => db.clock.get(),
        db.bytes,
        () => new UUID(77L, 1L),
        new JdbcReferenceHost.MaterialCipher(JdbcReferenceHost.syntheticHash("test-key"), () => db.bytes(12))
      )
      val digest = Digest256.fromBytes(JdbcReferenceHost.syntheticHash(binding)).toOption.get
      val proof = accepted(result(raw.password(ceremony, digest, db.subject, "password"))) match {
        case raw.PasswordResult.Ready(value) => value
        case _                               => fail("Unexpected challenge")
      }
      val restarted = db.freshSecurity()
      val metadata  = accepted(result(restarted.bootstrapRecovery(request()))).get
      metadata.ceremony shouldBe ceremony
      metadata.preparationPending shouldBe true
      db.scalar("SELECT count(*) FROM baseline_session") shouldBe 0L
      result(restarted.resume(qsid, request(), owner(2)).get)
      result(restarted.recover(owner(2), ceremony)) shouldBe Right(JdbcReferenceHost.Recovery.NotCommitted)
      result(raw.complete(proof)) shouldBe Left(TransactionFailure.Rejected(OperationError.NotPrepared))
  }

  it should "accept only a current committed pending cookie for public bootstrap without HTTP activation" in Using
    .resource(new Fixture) { db =>
      db.create(); db.open(1)
      val cookie = db.login()
      result(db.security.authorizeHttp(request(Some(cookie)), Page())) shouldBe ()
      result(db.security.protectedPrincipal(request(Some(cookie)))).isLeft shouldBe true
      intercept[SessionAccessDenied](
        result(db.security.authorizeHttp(request(Some(cookie)), Page(protectedPage = true)))
      )
      db.scalar("SELECT count(*) FROM spoonbill_browser_slot WHERE current_session_id IS NOT NULL") shouldBe 0L
      accepted(result(db.security.logoutBinding(binding)))
      intercept[SessionAccessDenied](result(db.security.authorizeHttp(request(Some(cookie)), Page())))
      result(db.security.bootstrapRecovery(request())) shouldBe Right(None)
    }

  it should "filter expired or revoked bootstrap metadata without disclosing it to another binding" in Using.resource(
    new Fixture
  ) { db =>
    db.create(); db.open(1); db.login()
    result(db.security.bootstrapRecovery(request())).toOption.flatten.isDefined shouldBe true
    accepted(result(db.security.changeAccount(db.subject, enabled = false)))
    result(db.security.bootstrapRecovery(request())) shouldBe Right(None)
    accepted(result(db.security.changeAccount(db.subject, enabled = true)))
    result(db.security.bootstrapRecovery(request())).toOption.flatten shouldBe None
    result(db.security.bootstrapRecovery(request().withCookie("baseline_binding", "foreign"))).isLeft shouldBe true
    db.clock.set(start.plusSeconds(61))
    result(db.security.bootstrapRecovery(request())) shouldBe Right(None)
  }

  it should "run its crash checkpoint only after committed rows exist and the preparation connection is released" in Using
    .resource(new Fixture) { db =>
      result(db.security.close())
      val observed = new AtomicReference(Option.empty[UUID])
      val instance = db.freshSecurity { attempt =>
        db.scalar("SELECT count(*) FROM baseline_session") shouldBe 1L
        db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 1L
        val (_, resources) = db.probe.measure(())
        resources.activeConnectionsBefore shouldBe 0L
        resources.activeConnectionsAfter shouldBe 0L
        observed.set(Some(attempt))
        throw new IllegalStateException("Synthetic response loss after acknowledged commit")
      }
      result(instance.storage.create(qsid.deviceId, qsid.sessionId, Page()))
      result(instance.open(qsid, request(), owner(1)))
      val ceremony = accepted(result(instance.begin(owner(1))))
      intercept[IllegalStateException](result(instance.password(owner(1), ceremony, db.subject, "password")))
      observed.get().isDefined shouldBe true
      result(instance.recover(owner(1), ceremony)) shouldBe Right(
        JdbcReferenceHost.Recovery.Committed(observed.get().get)
      )
      db.scalar("SELECT count(*) FROM baseline_session") shouldBe 1L
    }

  it should "reserve bounded priority release capacity when ordinary work is saturated and share alias cleanup" in Using
    .resource(new Fixture(JdbcBrowserSecurity.Limits(queued = 2, active = 1))) { db =>
      db.create(); val guard = db.open(1)
      val arrived            = new CountDownLatch(1)
      val release            = new CountDownLatch(1)
      db.commitBarrier.set(Some(arrived -> release))
      val active = guard.authorize(Page())
      try {
        arrived.await(10, TimeUnit.SECONDS) shouldBe true
        val first  = db.security.storage.get(qsid.deviceId, qsid.sessionId)
        val second = db.security.storage.get(qsid.deviceId, qsid.sessionId)
        val (closed, measured) = db.probe.measure {
          val one   = guard.close()
          val alias = guard.close()
          (one eq alias) shouldBe true
          one
        }
        measured.counts.connections shouldBe 0L
        measured.activeConnectionsAfter shouldBe 1L
        val maintenance = db.security.retireExpiredMaterial()
        (maintenance eq db.security.retireExpiredMaterial()) shouldBe true
        maintenance.isCompleted shouldBe false
        result(db.security.resources).queued shouldBe 4
        release.countDown()
        result(active); result(closed)
        result(maintenance) shouldBe Right(0)
        // Cleanup runs before either ordinary storage acquisition, which must now
        // reject the disconnected owner rather than expose its old manager.
        intercept[SessionAccessDenied](result(first))
        intercept[SessionAccessDenied](result(second))
        result(db.security.resources).active shouldBe 0
        result(db.security.resources).queued shouldBe 0
        db.scalar("SELECT count(*) FROM spoonbill_browser_view WHERE owner_id IS NOT NULL") shouldBe 0L
        db.scalar("SELECT epoch FROM spoonbill_browser_view") shouldBe 2L
        result(guard.close()) shouldBe ()
        db.scalar("SELECT epoch FROM spoonbill_browser_view") shouldBe 2L
      } finally release.countDown()
    }

  it should "drain accepted cleanup during shutdown while rejecting ordinary queued work" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(queued = 1, active = 1))
  ) { db =>
    db.create(); val guard = db.open(1)
    val arrived            = new CountDownLatch(1)
    val release            = new CountDownLatch(1)
    db.commitBarrier.set(Some(arrived -> release))
    val active = guard.authorize(Page())
    try {
      arrived.await(10, TimeUnit.SECONDS) shouldBe true
      val ordinary    = db.security.storage.get(qsid.deviceId, qsid.sessionId)
      val cleanup     = guard.close()
      val maintenance = db.security.retireExpiredMaterial()
      val shutdown    = db.security.close()
      (maintenance eq db.security.retireExpiredMaterial()) shouldBe true
      intercept[SessionAccessDenied](result(ordinary))
      cleanup.isCompleted shouldBe false
      maintenance.isCompleted shouldBe false
      shutdown.isCompleted shouldBe false
      release.countDown()
      result(active); result(shutdown)
      cleanup.isCompleted shouldBe true
      maintenance.isCompleted shouldBe true
      result(cleanup) shouldBe ()
      result(maintenance) shouldBe Right(0)
      result(db.security.resources).nodes shouldBe 0
      result(db.security.resources).queued shouldBe 0
      db.scalar("SELECT count(*) FROM spoonbill_browser_view WHERE owner_id IS NOT NULL") shouldBe 0L
    } finally release.countDown()
  }

  it should "expose serialized material retirement and preserve actual storage failures" in Using
    .resource(new Fixture) { db =>
      db.create(); db.open(1); db.login()
      db.clock.set(start.plusSeconds(61))
      result(db.security.retireExpiredMaterial()) shouldBe Right(1)
      db.scalar("SELECT count(*) FROM baseline_material") shouldBe 0L
      db.scalar("SELECT count(*) FROM baseline_audit") shouldBe 1L
      db.execute("ALTER TABLE baseline_material RENAME TO unavailable_material")
      result(db.security.retireExpiredMaterial()) shouldBe Left(TransactionFailure.RolledBack)
    }

  it should "start fresh maintenance from an immediate completion continuation" in Using.resource(new Fixture) { db =>
    db.create(); val guard = db.open(1)
    val arrived            = new CountDownLatch(1)
    val release            = new CountDownLatch(1)
    db.commitBarrier.set(Some(arrived -> release))
    val active = guard.authorize(Page())
    try {
      arrived.await(10, TimeUnit.SECONDS) shouldBe true
      val first = db.security.retireExpiredMaterial()
      val following = first.flatMap { _ =>
        val next = db.security.retireExpiredMaterial()
        (next eq first) shouldBe false
        next
      }(ExecutionContext.parasitic)
      release.countDown()
      result(active)
      result(first) shouldBe Right(0)
      result(following) shouldBe Right(0)
    } finally release.countDown()
  }

  it should "settle the running job before release, maintenance and ordinary work in that order" in {
    given ExecutionContext        = ExecutionContext.parasitic
    val gate                      = new JdbcReferenceGate(1, 1)
    val held                      = Promise[Unit]()
    val order                     = new AtomicReference(Vector.empty[String])
    def mark(value: String): Unit = { order.updateAndGet(previous => previous :+ value); () }
    val running                   = gate.submit { mark("running"); held.future }
    val ordinary                  = gate.submit { mark("ordinary"); Future.unit }
    val maintenance               = gate.submitMaintenance { mark("maintenance"); Future.unit }
    val release                   = gate.submitCleanup { mark("release"); Future.unit }
    gate.queued shouldBe 3
    order.get() shouldBe Vector("running")
    held.success(())
    result(running); result(release); result(maintenance); result(ordinary)
    order.get() shouldBe Vector("running", "release", "maintenance", "ordinary")
    result(gate.close()) shouldBe ()
    gate.queued shouldBe 0
  }

  it should "reserve bootstrap capacity before detaching an active takeover" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(active = 2, bootstrap = 1))
  ) { db =>
    db.create(); val current = db.open(1)
    db.create(Qsid("device", "other-bootstrap"))
    val (_, measured) = db.probe.measure {
      intercept[SessionAccessDenied](result(db.security.open(qsid, request(), owner(2))))
    }
    measured.counts.connections shouldBe 0L
    result(current.authorize(Page())) shouldBe ()
    result(db.security.resources).bootstrap shouldBe 1
    result(db.security.resources).active shouldBe 1
    db.scalar("SELECT epoch FROM spoonbill_browser_view") shouldBe 1L
  }

  it should "limit active views per binding while allowing another binding and same-view takeover" in Using.resource(
    new Fixture(JdbcBrowserSecurity.Limits(active = 3, activePerBinding = 1))
  ) { db =>
    db.create(); val original = db.open(1)
    val sameBinding           = Qsid("device", "second-view")
    db.create(sameBinding)
    intercept[SessionAccessDenied](result(db.security.open(sameBinding, request(), owner(2))))
    val independent = Qsid("other-device", "independent-view")
    accepted(result(db.security.bootstrapBinding("independent-binding")))
    db.create(independent)
    val other =
      result(db.security.open(independent, request().withCookie("baseline_binding", "independent-binding"), owner(3)))
    result(other.authorize(Page())) shouldBe ()
    val replacement = db.open(4)
    result(original.close())
    result(replacement.authorize(Page())) shouldBe ()
    result(db.security.resources).active shouldBe 2
    db.scalar("SELECT count(*) FROM spoonbill_browser_view") shouldBe 2L
  }
}
