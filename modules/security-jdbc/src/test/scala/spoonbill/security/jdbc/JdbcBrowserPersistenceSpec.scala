package spoonbill.security.jdbc

import avocet.Id
import avocet.dsl.*
import avocet.dsl.html.*
import java.io.PrintWriter
import java.security.SecureRandom
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException, Timestamp}
import java.time.Instant
import java.util.{Base64, Properties, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.action.*
import spoonbill.auth.*
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Stream}
import spoonbill.security.Identifiers.{ConnectionId, InvocationId}
import spoonbill.security.transaction.*
import spoonbill.server.*
import spoonbill.snapshot.{SchemaId, SchemaVersion, SnapshotFormat, ViewSnapshotException}
import spoonbill.state.BoundedPresentationStore
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

class JdbcBrowserPersistenceSpec extends BrowserPersistenceConformance {
  private given Effect[Future]              = new Effect.FutureEffect
  private given ExecutionContext            = ExecutionContext.global
  private def await[A](value: Future[A]): A = Await.result(value, 20.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected rejection: $error"), identity)
  private val binding = BrowserPersistenceConformance.binding

  private class Fixture(exclusive: Boolean = true) extends BrowserPersistenceConformance.Fixture {
    private val url =
      sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Use the owned disposable PostgreSQL test database"))
    val clock               = new AtomicReference(BrowserPersistenceConformance.start)
    val realm               = "provider-" + UUID.randomUUID().toString
    val loseAcknowledgement = new AtomicBoolean()
    val openedConnections   = new AtomicInteger()
    val domainTransactions  = new AtomicInteger()
    val source: DataSource = new DataSource {
      // sbt can reload the test classloader after DriverManager's discovery.
      // Use this fixture's explicit driver instead of that ambient registry.
      private val driver = new org.postgresql.Driver()
      def getConnection: Connection = {
        val properties = new Properties()
        sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
        sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
        properties.setProperty("options", "-c statement_timeout=15000 -c lock_timeout=12000")
        val connection = Option(driver.connect(url, properties)).getOrElse(throw new SQLException("Unsupported PostgreSQL test URL"))
        openedConnections.incrementAndGet()
        connection
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
      def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = {
        domainTransactions.incrementAndGet()
        outer.transact(body).map {
          case Right(_) if loseAcknowledgement.getAndSet(false) => Left(TransactionFailure.CommitUnknown)
          case result                                           => result
        }
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
        ownership = if (exclusive) BrowserOwnership.ExclusiveScope else BrowserOwnership.SharedViews,
        limits = limits,
        existingRunner = Some(runner)
      )
    def withViewLimit(value: Int): JdbcBrowserPersistence[Future, String] =
      withRunner(observed, BrowserPersistenceLimits(retainedViews = value))
    def withLimits(limits: BrowserPersistenceLimits): JdbcBrowserPersistence[Future, String] =
      withRunner(observed, limits)
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

  "The JDBC exclusive browser coordinator" should "retain ordinary reconnects and require an authorized baseline after credential delivery" in Using
    .resource(new Fixture) { db =>
      val origin = "https://app.example"
      val freshPages = new AtomicInteger()
      def coordinator(provider: JdbcBrowserPersistence[Future, String]) = new BrowserAuthentication[Future, String, String](
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
      val configured = browser.configure(base)
      val access = configured.sessionAccessControl.get
      def owner(): ConnectionId = ConnectionId.fromUuid(UUID.randomUUID())
      def request(token: Option[String] = None): HttpRequest[Future] = {
        val cookies = Seq(browser.configuration.bindingCookieName + "=" + binding) ++
          token.toSeq.map(browser.configuration.sessionCookieName + "=" + _)
        Request(Request.Method.Get, PathAndQuery.Root, Seq("Origin" -> origin), None,
          Stream.empty[Future, Bytes], cookies.mkString("; "))
      }
      def manager = await(configured.stateStorage.get(qsid.deviceId, qsid.sessionId))
      def invocation(connection: ConnectionId): InvocationBinding =
        new InvocationBinding(InvocationId.fromUuid(UUID.randomUUID()), connection, expectedAuthority = Some(browser.authority))
      def context(connection: ConnectionId): PublicActionContext = {
        val captured = Promise[PublicActionContext]()
        val action = new Actions[Future, String, String].public(
          accepted(ActionName.parse("test.jdbc-reconnect")), InputSchema.empty, PublicPolicy.allow[Future, Unit]
        ) { (_, context) =>
          captured.success(context)
          Future.successful(UiOutcome.unchanged[String])
        }
        await(ActionDispatcher.public(action, Vector.empty, invocation(connection))) match {
          case InvocationResult.Completed(UiOutcome.Updated(_)) => ()
          case other => fail(s"The public action did not dispatch: $other")
        }
        await(captured.future)
      }
      try {
        db.persistence.capabilities.ownership shouldBe BrowserOwnership.ExclusiveScope
        await(configured.stateStorage.create(qsid.deviceId, qsid.sessionId, "fresh"))
        val first = await(access.open(qsid, request(), owner()))
        first.ownershipEpoch shouldBe None
        val anonymous = manager
        val child = Id("1_2")
        await(anonymous.write(Id.TopLevel, "anonymous setup with an extra error banner"))
        await(anonymous.write(child, "anonymous component"))
        await(first.close())
        val anonymousOwner = owner()
        val sameAnonymous = await(access.resume(qsid, request(), anonymousOwner).get)
        sameAnonymous.ownershipEpoch shouldBe None
        await(manager.read[String](Id.TopLevel)) shouldBe Some("anonymous setup with an extra error banner")
        await(manager.read[String](child)) shouldBe Some("anonymous component")
        freshPages.get() shouldBe 0

        val ceremony = accepted(await(browser.begin(context(anonymousOwner))))
        val receipt = accepted(await(db.prepare(ceremony)))
        browser.complete(receipt) shouldBe UiOutcome.CommitAuthentication(receipt.completionId)
        val credential = await(configured.authenticationCompletion.get.deliver(receipt.completionId, binding)).get
        await(browser.authority.resolve(invocation(anonymousOwner))) shouldBe Left(AccessDenied.Unauthenticated)
        val staleManager = manager
        await(sameAnonymous.close())
        val authenticatedOwner = owner()
        val authenticated = await(access.resume(qsid, request(Some(credential.value)), authenticatedOwner).get)
        val activeView = accepted(await(browser.currentView(context(authenticatedOwner))))
        authenticated.ownershipEpoch shouldBe Some(activeView.fence.epoch)
        authenticated.viewSnapshots shouldBe None
        await(manager.read[String](Id.TopLevel)) shouldBe Some("fresh")
        await(manager.read[String](child)) shouldBe None
        await(authenticated.connected("fresh")) shouldBe "recovery codes"
        await(authenticated.authorize("recovery codes"))
        await(browser.authority.resolve(invocation(authenticatedOwner))) shouldBe Right("subject")
        intercept[BoundedPresentationStore.Rejected](await(staleManager.write(child, "late anonymous component")))
        freshPages.get() shouldBe 1

        // No client baseline has been acknowledged before this socket closes.
        await(manager.write(Id.TopLevel, "recovery codes"))
        await(manager.write(child, "authenticated component"))
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
          val next = await(nextAccess.resume(qsid, request(Some(credential.value)), owner()).get)
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
      await(db.persistence.transact(first) { _ => ran = true }).isLeft shouldBe true
      ran shouldBe false
      await(db.persistence.release(first))
      await(db.persistence.close())
      accepted(await(second.admitOutput(next, "current frame"))) shouldBe "current frame"
      db.retained("spoonbill_browser_view", "owner_id IS NOT NULL") shouldBe 1
    } finally await(second.close())
  }

  it should "resume an HTTP bootstrap on another node without allocating arbitrary reconnect identifiers" in Using.resource(
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
      await(second.claim(next.identity, Qsid("unregistered", "view"), ConnectionId.fromUuid(UUID.randomUUID()),
        existingOnly = true)) shouldBe Left(BrowserFailure.Missing)
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
      val next = accepted(await(db.persistence.claim(
        original.identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true
      )))
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

  it should "fail closed during a database partition and reject its old fence after connectivity returns" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val partitioned = new AtomicBoolean()
    val outer = new JdbcTransactionExecutor[Future](db.source, ExecutionContext.global)
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
      await(isolated.transact(first) { _ => ran = true }) shouldBe Left(TransactionFailure.StorageFailure)
      ran shouldBe false
      await(isolated.admitOutput(first, "partitioned frame")) shouldBe
        Left(BrowserFailure.Settlement(TransactionFailure.StorageFailure))
      val next = open(db.persistence, existing = true)
      partitioned.set(false)
      await(isolated.transact(first) { _ => ran = true }).isLeft shouldBe true
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
        case _ => fail("Expected committed recovery on the replacement node")
      }
      val credential = await(second.deliver(receipt.completionId, binding)).get
      accepted(await(second.capture(binding, Some(credential.value), activate = true))).principal.map(_.subject) shouldBe
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
    val active = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
    val second = db.fresh()
    try {
      db.clock.set(active.principal.get.expiresAt)
      await(second.capture(binding, Some(credential.value), activate = false)).isLeft shouldBe true
      db.clock.set(BrowserPersistenceConformance.start)
      await(db.persistence.capture(binding, Some(credential.value), activate = false)).isLeft shouldBe true
    } finally await(second.close())
  }

  it should "restore only the selected acknowledged draft through the shared provider snapshot adapter" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    case class Screen(draft: String, freshAuthority: String)
    val format = new SnapshotFormat[String](
      accepted(SchemaId.parse("shared-draft")), accepted(SchemaVersion.fromInt(1))
    )
    val anonymous  = open(db.persistence)
    val receipt    = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
    val credential = await(db.persistence.deliver(receipt.completionId, binding)).get
    val identity = accepted(await(db.persistence.capture(binding, Some(credential.value), activate = true)))
    val first = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    val oldSnapshot = db.persistence.projectedSnapshots[Screen, String](
      format, _.draft, (fresh, draft) => fresh.copy(draft = draft)
    )(first).get
    await(oldSnapshot.initialize(Screen("", "node A"))) shouldBe Screen("", "node A")
    await(oldSnapshot.commit(Screen("acknowledged draft", "node A")))
    val second = db.fresh()
    try {
      val captured = accepted(await(second.capture(binding, Some(credential.value), activate = true)))
      val next = accepted(await(second.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
      val restored = second.projectedSnapshots[Screen, String](
        format, _.draft, (fresh, draft) => fresh.copy(draft = draft)
      )(next).get
      await(restored.initialize(Screen("", "node B"))) shouldBe Screen("acknowledged draft", "node B")
      intercept[ViewSnapshotException](await(oldSnapshot.commit(Screen("stale overwrite", "node A"))))
      await(db.persistence.close())
      await(restored.commit(Screen("next draft", "node B")))
    } finally await(second.close())
  }

  it should "evaluate output presentation policy against current authority in one transaction" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val view = open(db.persistence)
    val calls = new AtomicInteger()
    accepted(await(db.persistence.admitOutputIf(view, "allowed") { current =>
      calls.incrementAndGet()
      current.sameAuthority(view.identity)
    })) shouldBe "allowed"
    calls.get() shouldBe 1
    await(db.persistence.admitOutputIf(view, "denied")(_ => false)) shouldBe Left(BrowserFailure.Denied)
    val next = accepted(await(db.persistence.claim(view.identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    await(db.persistence.admitOutputIf(view, "stale") { _ => calls.incrementAndGet(); true }).isLeft shouldBe true
    calls.get() shouldBe 1
    accepted(await(db.persistence.admitOutputIf(next, "current")(_ => true))) shouldBe "current"
  }

  it should "reduce ordinary protected frame admission from five provider transactions to one" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val anonymous = open(db.persistence)
    val receipt = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
    val token = await(db.persistence.deliver(receipt.completionId, binding)).get
    val identity = accepted(await(db.persistence.capture(binding, Some(token.value), activate = true)))
    val view = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    val output = "immutable protected frame"
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
      else db.persistence.authorize(view).flatMap {
        case Left(error) => Future.successful(Left(error))
        case Right(current) if permitted(current) => earlierChecks(remaining - 1)
        case Right(_) => Future.successful(Left(BrowserFailure.Denied))
      }
    def earlier(): Future[Either[BrowserFailure, String]] = earlierChecks(4).flatMap {
      case Left(error) => Future.successful(Left(error))
      case Right(_) => db.persistence.admitOutput(view, output)
    }
    def fused(): Future[Either[BrowserFailure, String]] = db.persistence.admitOutputIf(view, output)(permitted)
    def counts(operation: => Future[Either[BrowserFailure, String]]): (Int, Int) = {
      val transactions = db.domainTransactions.get()
      val connections = db.openedConnections.get()
      accepted(await(operation)) shouldBe output
      val domains = db.domainTransactions.get() - transactions
      // The fixture opens one connection per domain transaction; remaining
      // successful opens belong to independent durable clock checkpoints.
      domains -> (db.openedConnections.get() - connections - domains)
    }
    val earlierCounts = counts(earlier())
    val fusedCounts = counts(fused())
    earlierCounts._1 shouldBe 5
    fusedCounts._1 shouldBe 1
    fusedCounts._2 should be > 0
    earlierCounts._2 shouldBe fusedCounts._2 * 5
    info(s"Ordinary-frame protocol comparison: provider transactions ${earlierCounts._1} -> ${fusedCounts._1}, clock connections ${earlierCounts._2} -> ${fusedCounts._2}")

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
        val sorted = samples.sorted
        def percentile(value: Double): Double = sorted(math.ceil(value * sorted.size).toInt - 1) / 1000000d
        f"p50=${percentile(0.50)}%.3fms p95=${percentile(0.95)}%.3fms p99=${percentile(0.99)}%.3fms"
      }
      info(s"Within-current-provider protocol smoke; 10 warmups, 50 alternating samples each; earlier ${summary(oldSamples.result())}; fused ${summary(newSamples.result())}. No fixed-binary, allocation, retained-heap or full performance acceptance claim.")
    }

    val successor = db.fresh()
    try {
      val captured = accepted(await(successor.capture(binding, Some(token.value), activate = true)))
      val next = accepted(await(successor.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
      val before = policyCalls.get()
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
    val second = accepted(await(db.persistence.claim(first.identity, other, ConnectionId.fromUuid(UUID.randomUUID()), false)))
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
    await(db.persistence.ownedViewPage(limit = 1001)) shouldBe Left(TransactionFailure.Rejected(OperationError.InvalidDefinition))
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
    val receipt = accepted(await(db.prepare(accepted(await(db.persistence.begin(anonymous, "login"))))))
    val token = await(db.persistence.deliver(receipt.completionId, binding)).get
    val identity = accepted(await(db.persistence.capture(binding, Some(token.value), activate = true)))
    val first = accepted(await(db.persistence.claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), true)))
    val format = new SnapshotFormat[String](accepted(SchemaId.parse("retired-draft")), accepted(SchemaVersion.fromInt(1)))
    val snapshot = db.persistence.projectedSnapshots[String, String](format, value => value, (_, saved) => saved)(first).get
    await(snapshot.initialize("initial"))
    await(snapshot.commit("acknowledged"))
    val target = accepted(await(db.persistence.ownedViewPage())).views.head
    await(db.persistence.retireView(target) { tx =>
      Using.resource(tx.connection.prepareStatement("UPDATE spoonbill_auth_test_host SET enabled=FALSE WHERE realm=?")) { query =>
        query.setString(1, db.realm); query.executeUpdate()
      }
      false
    }) shouldBe Left(TransactionFailure.Rejected(OperationError.HostDenied))
    await(db.persistence.authorize(first)).isRight shouldBe true
    val authorizations = new AtomicInteger()
    accepted(await(db.persistence.retireView(target) { tx =>
      authorizations.incrementAndGet()
      tx.connection.getAutoCommit shouldBe false
      Using.resource(tx.connection.prepareStatement("SELECT enabled FROM spoonbill_auth_test_host WHERE realm=? FOR UPDATE")) { query =>
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
    val restored = db.persistence.projectedSnapshots[String, String](format, value => value, (_, saved) => saved)(next).get
    await(restored.initialize("fresh")) shouldBe "acknowledged"
    accepted(await(db.persistence.retireView(target)(_ => true))) shouldBe false
    await(db.persistence.authorize(next)).isRight shouldBe true
    await(db.persistence.retireView(target.copy(scope = BrowserProviderScope("foreign", "session")))(_ => true)) shouldBe
      Left(TransactionFailure.Rejected(OperationError.InvalidDefinition))
  }

  it should "free active capacity through explicit retirement and expose uncertain settlement" in Using.resource(
    new Fixture(exclusive = false)
  ) { db =>
    val provider = db.withLimits(BrowserPersistenceLimits(activeViewsPerBinding = 1))
    try {
      val first = open(provider)
      val another = Qsid("capacity-device", "capacity-view")
      await(provider.claim(first.identity, another, ConnectionId.fromUuid(UUID.randomUUID()), false)) shouldBe Left(BrowserFailure.Capacity)
      val target = accepted(await(provider.ownedViewPage())).views.head
      db.loseAcknowledgement.set(true)
      await(provider.retireView(target)(_ => true)) shouldBe Left(TransactionFailure.CommitUnknown)
      accepted(await(provider.ownedViewPage())).views shouldBe empty
      val next = accepted(await(provider.claim(first.identity, another, ConnectionId.fromUuid(UUID.randomUUID()), false)))
      val usage = accepted(await(provider.usage()))
      usage.activeViews shouldBe 1
      usage.retainedViews shouldBe 2
      usage.limits.activeViewsPerBinding shouldBe 1
      await(provider.release(first))
      await(provider.authorize(next)).isRight shouldBe true
    } finally await(provider.close())
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
