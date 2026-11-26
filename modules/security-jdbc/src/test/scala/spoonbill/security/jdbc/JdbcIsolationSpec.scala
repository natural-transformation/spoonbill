package spoonbill.security.jdbc

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.security.SecureRandom
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Base64, Properties, UUID}
import java.util.concurrent.{ConcurrentLinkedDeque, ConcurrentLinkedQueue, Executor}
import java.util.concurrent.atomic.AtomicInteger
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import spoonbill.auth.{BrowserOwnership, HostPrincipal}
import spoonbill.effect.Effect

/**
 * Certified READ COMMITTED sources skip the isolation setter; ordinary sources
 * keep it. Calls are counted on each pool's logical handles. The PostgreSQL
 * driver sends one SET per setTransactionIsolation call and one SHOW per
 * getTransactionIsolation call, so these counts are the redundant statements
 * the server would log. The event-latency harness checks the server log itself.
 */
class JdbcIsolationSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]   = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global
  private def await[A](value: Future[A]): A = Await.result(value, 20.seconds)
  private val start = Instant.parse("2026-11-25T00:00:00Z")
  private val random = new SecureRandom()

  private def url: String =
    sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Use the owned disposable PostgreSQL database"))
  private val driver = new org.postgresql.Driver()
  private def physical(): Connection = {
    val properties = new Properties()
    sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
    sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
    Option(driver.connect(url, properties)).getOrElse(throw new SQLException("Unsupported PostgreSQL test URL"))
  }
  private def binding(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private abstract class TestSource extends DataSource {
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter                                 = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit                   = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int                                      = 0
    def setLoginTimeout(seconds: Int): Unit                       = throw new SQLFeatureNotSupportedException()
    def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.jdbc.isolation.test")
    def isWrapperFor(kind: Class[?]): Boolean                     = false
    def unwrap[T](kind: Class[T]): T                              = throw new SQLException("Not a wrapper")
  }

  /**
   * A small pool. It keeps idle physical connections and lends a new logical
   * handle on every checkout. With resets=true it restores READ COMMITTED on
   * checkout after a borrower changed the level, as HikariCP does on return;
   * resets=false models a pool that fails that reset test.
   */
  private final class TestPool(resets: Boolean) extends TestSource with AutoCloseable {
    private final class Physical(val raw: Connection) { @volatile var dirty = false }
    private val idle = new ConcurrentLinkedDeque[Physical]()
    val opened         = new AtomicInteger()
    val handles        = new AtomicInteger()
    val transactions   = new AtomicInteger()
    val isolationSets  = new AtomicInteger()
    val isolationReads = new AtomicInteger()
    // The effective level inside each transaction, read just before its commit.
    val committedAt = new ConcurrentLinkedQueue[String]()

    private def checkout(): Physical = {
      val next = Option(idle.pollFirst()).getOrElse { opened.incrementAndGet(); new Physical(physical()) }
      if (resets && next.dirty) { next.raw.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); next.dirty = false }
      next
    }

    def getConnection: Connection = {
      val lent = checkout()
      handles.incrementAndGet()
      Proxy
        .newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), new InvocationHandler {
          private var returned = false
          private def delegate(method: Method, args: Array[Object]): Object =
            try method.invoke(lent.raw, args*)
            catch { case error: InvocationTargetException => throw error.getCause }
          def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
            val args = Option(arguments).getOrElse(Array.empty[Object])
            method.getName match {
              case "setAutoCommit" if args(0) == java.lang.Boolean.FALSE =>
                transactions.incrementAndGet(); delegate(method, args)
              case "setTransactionIsolation" =>
                // Like HikariCP, only a level other than the pool default needs a reset.
                isolationSets.incrementAndGet()
                lent.dirty = args(0) != Integer.valueOf(Connection.TRANSACTION_READ_COMMITTED)
                delegate(method, args)
              case "getTransactionIsolation" =>
                isolationReads.incrementAndGet(); delegate(method, args)
              case "commit" =>
                committedAt.add(isolation(lent.raw)); delegate(method, args)
              case "abort" =>
                // An aborted physical connection never returns to the pool.
                returned = true; lent.raw.abort(args(0).asInstanceOf[Executor]); null
              case "close" =>
                if (!returned) {
                  returned = true
                  if (!lent.raw.getAutoCommit) { lent.raw.rollback(); lent.raw.setAutoCommit(true) }
                  idle.addFirst(lent)
                }
                null
              case "isClosed" => java.lang.Boolean.valueOf(returned)
              case _          => delegate(method, args)
            }
          }
        })
        .asInstanceOf[Connection]
    }

    /** Another borrower changes the level and returns its connection without restoring it. */
    def borrowAt(level: Int): Unit = {
      val lent = checkout()
      lent.raw.setTransactionIsolation(level)
      lent.dirty = true
      idle.addFirst(lent)
    }

    /** Evict every idle physical connection, so the next checkout opens a new one. */
    def replace(): Unit = Iterator.continually(idle.pollFirst()).takeWhile(_ != null).foreach(_.raw.close())
    def close(): Unit   = replace()
  }

  private def isolation(connection: Connection): String =
    Using.resource(connection.prepareStatement("SELECT current_setting('transaction_isolation')")) { query =>
      Using.resource(query.executeQuery()) { rows => rows.next(); rows.getString(1) }
    }

  private val NoHost = new JdbcHostSessions[String] {
    def resolve(connection: Connection, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = None
    def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
  }
  private val vault = {
    val key = new Array[Byte](32)
    random.nextBytes(key)
    JdbcCredentialVault.aesGcm(key)
  }
  private def certify(pool: TestPool, certified: Boolean): DataSource =
    if (certified) JdbcIsolation.readCommittedOnCheckout(pool) else pool
  private def label(certified: Boolean): String = if (certified) "certified" else "ordinary"

  /**
   * One SharedViews provider: every time sample commits a clock checkpoint on
   * the clock source, so each anonymous capture opens transactions on both.
   */
  private final class Setup(
    workCertified: Boolean,
    clockCertified: Boolean,
    resets: Boolean = true,
    runner: Option[JdbcTransactionExecutor[Future]] = None
  ) extends AutoCloseable {
    val work  = new TestPool(resets)
    val clock = new TestPool(resets)
    val persistence = new JdbcBrowserPersistence[Future, String](
      certify(work, workCertified),
      ExecutionContext.global,
      "isolation-" + UUID.randomUUID(),
      "session",
      () => start,
      NoHost,
      vault,
      BrowserOwnership.SharedViews,
      existingRunner = runner,
      clockSource = Some(certify(clock, clockCertified))
    )
    Using.resource(physical())(persistence.initialize)
    def capture(): Unit = await(persistence.capture(binding(), None, activate = false)).isRight shouldBe true
    def close(): Unit   = { await(persistence.close()); work.close(); clock.close() }
  }

  "Spoonbill JDBC transactions" should "set no isolation level on certified sources and keep the ordinary setter otherwise" in {
    for (workCertified <- Vector(false, true); clockCertified <- Vector(false, true)) withClue(
      s"${label(workCertified)} work source, ${label(clockCertified)} clock source: "
    ) {
      Using.resource(new Setup(workCertified, clockCertified)) { setup =>
        setup.capture()
        // Another borrower leaves each pooled connection at SERIALIZABLE; the pool
        // restores READ COMMITTED on the next checkout.
        setup.work.borrowAt(Connection.TRANSACTION_SERIALIZABLE)
        setup.clock.borrowAt(Connection.TRANSACTION_SERIALIZABLE)
        setup.capture()
        // A replaced physical connection starts at the server default.
        setup.work.replace(); setup.clock.replace()
        setup.capture()
        for (pool <- Vector(setup.work, setup.clock)) {
          pool.transactions.get should be > 0
          pool.committedAt.asScala.toSet shouldBe Set("read committed")
          pool.isolationReads.get shouldBe 0
          // Logical handles are new on every checkout; physical connections are reused.
          pool.handles.get should be > pool.opened.get
        }
        setup.work.isolationSets.get shouldBe (if (workCertified) 0 else setup.work.transactions.get)
        setup.clock.isolationSets.get shouldBe (if (clockCertified) 0 else setup.clock.transactions.get)
      }
    }
  }

  it should "show why a pool that fails the reset test cannot be certified, while the ordinary path still corrects it" in {
    // Certified, the provider trusts the pool and runs at the level the previous borrower left.
    Using.resource(new Setup(workCertified = true, clockCertified = true, resets = false)) { setup =>
      setup.capture()
      setup.work.borrowAt(Connection.TRANSACTION_SERIALIZABLE)
      setup.work.committedAt.clear()
      setup.capture()
      setup.work.committedAt.asScala.toSet shouldBe Set("serializable")
    }
    // The same pool, not certified: the ordinary setter restores READ COMMITTED.
    Using.resource(new Setup(workCertified = false, clockCertified = false, resets = false)) { setup =>
      setup.capture()
      setup.work.borrowAt(Connection.TRANSACTION_SERIALIZABLE)
      setup.capture()
      setup.work.committedAt.asScala.toSet shouldBe Set("read committed")
    }
  }

  it should "leave an injected runner's transaction setup to that runner" in {
    for (runnerCertified <- Vector(false, true)) withClue(s"${label(runnerCertified)} runner source: ") {
      val runnerPool = new TestPool(resets = true)
      val runner     = new JdbcTransactionExecutor[Future](certify(runnerPool, runnerCertified), ExecutionContext.global)
      // The provider's own work source is certified, but its domain transactions run on the runner.
      Using.resource(runnerPool) { _ =>
        Using.resource(new Setup(workCertified = true, clockCertified = true, runner = Some(runner))) { setup =>
          setup.capture()
          runnerPool.transactions.get should be > 0
          runnerPool.committedAt.asScala.toSet shouldBe Set("read committed")
          runnerPool.isolationSets.get shouldBe (if (runnerCertified) 0 else runnerPool.transactions.get)
          setup.work.transactions.get shouldBe 0
        }
      }
    }
  }

  "JdbcIsolation.readCommittedOnCheckout" should "delegate acquisition and unwrapping, and certify once" in {
    val acquired = new AtomicInteger()
    val credentials = new ConcurrentLinkedQueue[(String, String)]()
    final class Pool extends TestSource {
      private def connection(): Connection =
        Proxy
          .newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), (_, _, _) => null)
          .asInstanceOf[Connection]
      def getConnection: Connection = { acquired.incrementAndGet(); connection() }
      override def getConnection(user: String, password: String): Connection = {
        credentials.add(user -> password); connection()
      }
    }
    val pool      = new Pool
    val certified = JdbcIsolation.readCommittedOnCheckout(pool)
    JdbcIsolation.readCommittedOnCheckout(certified) should be theSameInstanceAs certified
    JdbcIsolation.certified(certified) shouldBe true
    JdbcIsolation.certified(pool) shouldBe false
    certified.getConnection
    certified.getConnection("reader", "secret")
    acquired.get shouldBe 1
    credentials.asScala.toVector shouldBe Vector("reader" -> "secret")
    certified.unwrap(classOf[Pool]) should be theSameInstanceAs pool
    certified.unwrap(classOf[DataSource]) should be theSameInstanceAs certified
    certified.isWrapperFor(classOf[Pool]) shouldBe true
    certified.isWrapperFor(classOf[Runnable]) shouldBe false
  }
}
