package example.browserauth

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.security.MessageDigest
import java.sql.{Connection, PreparedStatement, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.util.{Failure, Using}
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.data.Bytes
import spoonbill.effect.Stream
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.jdbc.JdbcCredentialVault
import spoonbill.security.transaction.{OperationError, TransactionFailure}
import spoonbill.server.spoonbillService
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/**
 * Synthetic host integration, including a real committed DELETE whose commit
 * acknowledgement is lost. The proxy changes only the test observer outcome.
 */
class JdbcAccountsSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext            = ExecutionContext.global
  private val binding                       = "b" * 43
  private def await[A](value: Future[A]): A = Await.result(value, 15.seconds)
  private def accepted[E, A](value: Either[E, A]): A =
    value.fold(error => fail(s"Unexpected host rejection: $error"), identity)
  private def withFixture[A](body: Fixture => A): A = Using.resource(new Fixture) { db =>
    await(db.initialized)
    body(db)
  }

  private class Fixture extends AutoCloseable {
    private val url = sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Run through scripts/with-test-postgres.sh"))
    require(
      url.startsWith("jdbc:postgresql://127.0.0.1:") || url.startsWith("jdbc:postgresql://localhost:"),
      "Owned loopback PostgreSQL is required"
    )
    private val schema          = "replacement_example_" + UUID.randomUUID().toString.replace("-", "")
    val clock                   = new AtomicReference(Instant.parse("2026-10-16T12:00:00Z"))
    val loseConsumptionCommit   = new AtomicBoolean()
    val consumptionQueryStarted = new CountDownLatch(1)
    // sbt can reload this test classloader after DriverManager discovery.
    private val driver = new org.postgresql.Driver()
    private def raw(): Connection = {
      val properties = new Properties()
      sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
      sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
      properties.setProperty("currentSchema", schema)
      properties.setProperty(
        "options",
        "-c statement_timeout=15000 -c lock_timeout=12000 -c idle_in_transaction_session_timeout=10000"
      )
      properties.setProperty("connectTimeout", "5")
      properties.setProperty("socketTimeout", "20")
      Option(driver.connect(url, properties)).getOrElse(throw new SQLException("Unsupported synthetic PostgreSQL URL"))
    }
    Using.resource(raw())(connection =>
      Using.resource(connection.createStatement())(_.executeUpdate(s"CREATE SCHEMA $schema"))
    )
    private val source = new DataSource {
      def getConnection: Connection = {
        val underlying = raw()
        val consumed   = new AtomicBoolean()
        Proxy
          .newProxyInstance(
            classOf[Connection].getClassLoader,
            Array(classOf[Connection]),
            new InvocationHandler {
              def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
                val args = Option(arguments).getOrElse(Array.empty[Object])
                val consumptionStatement = method.getName == "prepareStatement" && args.headOption.exists {
                  case sql: String => sql.startsWith("DELETE FROM example_recovery_result WHERE completion=")
                  case _           => false
                }
                if (consumptionStatement) consumed.set(true)
                try {
                  val result = method.invoke(underlying, args*)
                  if (method.getName == "commit" && consumed.get() && loseConsumptionCommit.compareAndSet(true, false))
                    throw new SQLException("Synthetic lost consumption commit acknowledgement")
                  if (!consumptionStatement) result
                  else
                    Proxy.newProxyInstance(
                      classOf[PreparedStatement].getClassLoader,
                      Array(classOf[PreparedStatement]),
                      new InvocationHandler {
                        def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
                          if (method.getName == "executeQuery") consumptionQueryStarted.countDown()
                          try method.invoke(result, Option(arguments).getOrElse(Array.empty[Object])*)
                          catch { case error: InvocationTargetException => throw error.getCause }
                        }
                      }
                    )
                } catch { case error: InvocationTargetException => throw error.getCause }
              }
            }
          )
          .asInstanceOf[Connection]
      }
      def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
      def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
      def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
      def getLoginTimeout: Int                                      = 0
      def setLoginTimeout(seconds: Int): Unit                       = ()
      def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.synthetic.replacement.test")
      def isWrapperFor(kind: Class[?]): Boolean                     = false
      def unwrap[T](kind: Class[T]): T                              = throw new SQLFeatureNotSupportedException()
    }
    val accounts = new JdbcAccounts(
      source,
      ExecutionContext.global,
      "http://localhost:8080",
      JdbcCredentialVault.aesGcm(MessageDigest.getInstance("SHA-256").digest("synthetic-test-key".getBytes("UTF-8"))),
      clock = () => clock.get(),
      deliveryLimit = 1
    )
    val initialized          = accounts.initialize()
    private lazy val service = spoonbillService(new Application[Future](accounts).config)

    def cookie(completion: UUID): String = await {
      Stream(Bytes.wrap(completion.toString.getBytes("UTF-8")))
        .mat[Future]()
        .flatMap { body =>
          service.http(
            Request(
              Request.Method.Post,
              PathAndQuery.Root / "auth" / "complete",
              Seq("Origin" -> "http://localhost:8080", "Content-Type" -> "text/plain"),
              Some(36L),
              body,
              s"spoonbill_binding=$binding"
            )
          )
        }
        .flatMap { response =>
          response.status.code shouldBe 204
          val value = response.headers.collectFirst {
            case ("Set-Cookie", value) if value.startsWith("spoonbill_session=") =>
              value.drop("spoonbill_session=".length).takeWhile(_ != ';')
          }.getOrElse(fail("No delivered cookie"))
          response.body.cancel().map(_ => value)
        }
    }
    def login(username: String = "alice"): BrowserView[Principal] = {
      val provider = accounts.persistence
      val captured = accepted(await(provider.capture(binding, None, activate = false)))
      val qsid     = Qsid("jdbc-example-device", UUID.randomUUID().toString)
      val anonymous = accepted(
        await(provider.claim(captured, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false))
      )
      val ceremony = accepted(await(provider.begin(anonymous, "sign-in")))
      val receipt = await(accounts.signIn(ceremony, username, "password")) match {
        case SignIn.Completed(value) => value
        case _: SignIn.Challenge =>
          val resumed = accepted(await(provider.resume(anonymous, ceremony.id, "sign-in")))
          await(accounts.factor(resumed, "123456")) match {
            case SignIn.Completed(value) => value
            case _                       => fail("Synthetic factor did not complete")
          }
        case _ => fail("Synthetic sign-in did not complete")
      }
      val active = accepted(await(provider.capture(binding, Some(cookie(receipt.completionId)), activate = true)))
      accepted(await(provider.claim(active, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true)))
    }
    def replace(
      view: BrowserView[Principal],
      change: SecurityChange = SecurityChange.RegenerateRecovery
    ): (BrowserView[Principal], CommittedReplacement) = {
      val provider = accounts.persistence
      val ceremony = accepted(await(provider.beginReplacement(view, change.purpose)))
      val receipt = await(accounts.replace(ceremony, MemoryReplacementTestAccess.invocation(view), "password")) match {
        case SessionReplacement.Completed(value) => value
        case _                                   => fail("Synthetic replacement did not complete")
      }
      val token = cookie(receipt.completionId)
      await(provider.capture(binding, Some(token), activate = false)).isLeft shouldBe true
      val active = accepted(await(provider.capture(binding, Some(token), activate = true)))
      accepted(
        await(provider.claim(active, view.qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = true))
      ) -> receipt
    }
    def resultCount: Int = Using.resource(raw()) { connection =>
      Using.resource(connection.createStatement()) { statement =>
        Using.resource(statement.executeQuery("SELECT count(*) FROM example_recovery_result")) { rows =>
          rows.next(); rows.getInt(1)
        }
      }
    }
    def consume(view: BrowserView[Principal]): Future[Either[TransactionFailure, Option[RecoveryResults.Consumed]]] =
      accounts.consumeRecovery(view, MemoryReplacementTestAccess.invocation(view))
    def withLockedResult[A](body: => A): A = Using.resource(raw()) { connection =>
      connection.setAutoCommit(false)
      try {
        Using.resource(connection.createStatement()) { statement =>
          Using.resource(statement.executeQuery("SELECT completion FROM example_recovery_result FOR UPDATE"))(
            _.next() shouldBe true
          )
        }
        body
      } finally connection.rollback()
    }
    // Await timeout only abandons observation, not initialization. Register
    // cleanup behind its settlement so a late initializer cannot race a schema
    // drop. The same bounded observer may time out while cleanup remains owned.
    private lazy val cleanup: Future[Unit] = initialized.transformWith { _ =>
      Future.unit.flatMap(_ => accounts.close()).transformWith { closed =>
        Future {
          Using.resource(raw())(connection =>
            Using.resource(connection.createStatement())(_.executeUpdate(s"DROP SCHEMA $schema CASCADE"))
          )
          ()
        }.transform { dropped =>
          (closed, dropped) match {
            case (Failure(primary), Failure(secondary)) =>
              primary.addSuppressed(secondary)
              Failure(primary)
            case _ => closed.flatMap(_ => dropped)
          }
        }
      }
    }
    def close(): Unit = await(cleanup)
  }

  "The JDBC host example" should "consume only the activated successor result and retain no reusable ciphertext after presentation ownership transfer" in
    withFixture { db =>
      val predecessor          = db.login()
      val (successor, receipt) = db.replace(predecessor)
      await(db.consume(predecessor)).isLeft shouldBe true
      val invocation = MemoryReplacementTestAccess.invocation(successor)
      val request    = db.accounts.consumeRecovery(successor, invocation)
      val consumed   = accepted(await(request)).get
      consumed.completionId should not be receipt.completionId
      consumed.sessionId shouldBe successor.identity.sessionId.get
      consumed.claim(MemoryReplacementTestAccess.invocation(successor), db.clock.get()) shouldBe None
      consumed.claim(invocation, db.clock.get()).nonEmpty shouldBe true
      accepted(await(request)).get.claim(invocation, db.clock.get()) shouldBe None
      accepted(await(db.consume(successor))) shouldBe None
      db.resultCount shouldBe 0
    }

  it should "withhold disclosure after unknown consumption commit and never redisclose the deleted result" in
    withFixture { db =>
      val (successor, _) = db.replace(db.login())
      db.loseConsumptionCommit.set(true)
      await(db.consume(successor)) shouldBe Left(TransactionFailure.CommitUnknown)
      db.loseConsumptionCommit.get() shouldBe false
      db.resultCount shouldBe 0
      accepted(await(db.consume(successor))) shouldBe None
    }

  it should "retire expired encrypted results through host maintenance" in withFixture { db =>
    val (successor, _) = db.replace(db.login())
    db.resultCount shouldBe 1
    db.clock.set(db.clock.get().plusSeconds(RecoveryResults.lifetimeSeconds))
    await(db.accounts.maintenance())
    db.resultCount shouldBe 0
    accepted(await(db.consume(successor))) shouldBe None
  }

  it should "recheck result expiry after waiting for its locked row" in withFixture { db =>
    val (successor, _) = db.replace(db.login())
    val consuming = db.withLockedResult {
      val pending = db.consume(successor)
      db.consumptionQueryStarted.await(5, TimeUnit.SECONDS) shouldBe true
      db.clock.set(db.clock.get().plusSeconds(RecoveryResults.lifetimeSeconds))
      pending
    }
    await(consuming) shouldBe Left(TransactionFailure.Rejected(OperationError.Expired))
    await(db.accounts.maintenance())
    db.resultCount shouldBe 0
  }

  it should "apply enrollment, replacement and disabling with one successor and the expected one-use result" in {
    Vector(SecurityChange.EnableFactor, SecurityChange.ReplaceFactor, SecurityChange.DisableFactor).foreach {
      operation =>
        withFixture { db =>
          val predecessor    = db.login(if (operation == SecurityChange.EnableFactor) "alice" else "bob")
          val (successor, _) = db.replace(predecessor, operation)
          await(db.accounts.persistence.authorize(predecessor)).isLeft shouldBe true
          accepted(await(db.consume(successor))).nonEmpty shouldBe (operation != SecurityChange.DisableFactor)
        }
    }
  }
}
