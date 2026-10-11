package spoonbill.security.jdbc

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import java.util.logging.Logger
import javax.sql.DataSource
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Using

/**
 * Counts establish the checkpoint contract, not a latency/throughput benchmark.
 */
class JdbcBrowserClockSpec extends AnyFlatSpec with Matchers {
  private val start = Instant.parse("2026-10-11T00:00:00.123456789Z")
  private class Fixture {
    private val url =
      sys.env.getOrElse("SPOONBILL_JDBC_TEST_URL", cancel("Use the owned disposable PostgreSQL database"))
    val time                      = new AtomicReference(start)
    val connections               = new AtomicInteger()
    val statements                = new AtomicInteger()
    val commits                   = new AtomicInteger()
    val failAcquire               = new AtomicBoolean()
    val loseCommitAcknowledgement = new AtomicBoolean()
    val realm                     = "clock-" + UUID.randomUUID()
    val source: DataSource = new DataSource {
      // Keep connection creation independent of sbt's driver-discovery context.
      private val driver = new org.postgresql.Driver()
      def getConnection: Connection = {
        connections.incrementAndGet()
        if (failAcquire.getAndSet(false)) throw new SQLException("Synthetic checkpoint acquisition failure")
        val properties = new Properties()
        sys.env.get("SPOONBILL_JDBC_TEST_USER").foreach(properties.setProperty("user", _))
        sys.env.get("SPOONBILL_JDBC_TEST_PASSWORD").foreach(properties.setProperty("password", _))
        val raw =
          Option(driver.connect(url, properties)).getOrElse(throw new SQLException("Unsupported PostgreSQL test URL"))
        Proxy
          .newProxyInstance(
            classOf[Connection].getClassLoader,
            Array(classOf[Connection]),
            new InvocationHandler {
              def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
                if (method.getName == "prepareStatement") statements.incrementAndGet()
                if (method.getName == "commit") commits.incrementAndGet()
                val args = Option(arguments).getOrElse(Array.empty[Object])
                val result =
                  try method.invoke(raw, args*)
                  catch { case error: InvocationTargetException => throw error.getCause }
                if (method.getName == "commit" && loseCommitAcknowledgement.getAndSet(false))
                  throw new SQLException("Synthetic lost checkpoint commit acknowledgment")
                result
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
      def getParentLogger: Logger                                   = Logger.getLogger("spoonbill.jdbc.clock.test")
      def isWrapperFor(kind: Class[?]): Boolean                     = false
      def unwrap[T](kind: Class[T]): T                              = throw new SQLFeatureNotSupportedException()
    }
    def clock(exclusive: Boolean = true): JdbcBrowserClock =
      new JdbcBrowserClock(source, realm, "session", () => time.get(), exclusive)
    Using.resource(source.getConnection)(clock().initialize)
    connections.set(0); statements.set(0); commits.set(0)
    def counts: (Int, Int, Int) = (connections.get(), statements.get(), commits.get())
  }

  "An exclusively owned JDBC clock" should "reuse only acknowledged equal/backward checkpoints with no JDBC work" in {
    val db    = new Fixture
    val clock = db.clock()
    clock.now() shouldBe start
    db.counts shouldBe (1, 1, 1)
    clock.now() shouldBe start
    db.time.set(start.minusSeconds(1))
    clock.now() shouldBe start
    db.counts shouldBe (1, 1, 1)
    db.time.set(start.plusNanos(1))
    clock.now() shouldBe start.plusNanos(1)
    db.counts shouldBe (2, 2, 2)
  }

  it should "consult durable state before using any checkpoint after restart" in {
    val db = new Fixture
    db.clock().now() shouldBe start
    db.time.set(start.minusSeconds(10))
    db.clock().now() shouldBe start
    db.counts shouldBe (2, 2, 2)
  }

  it should "retain an unacknowledged candidate without treating failed acquisition as cached authority" in {
    val db    = new Fixture
    val clock = db.clock()
    clock.now() shouldBe start
    db.time.set(start.plusSeconds(1)); db.failAcquire.set(true)
    intercept[JdbcAuthException](clock.now()).error shouldBe JdbcAuthError.StorageFailure
    db.time.set(start.minusSeconds(1))
    clock.now() shouldBe start.plusSeconds(1)
    db.counts shouldBe (3, 2, 2)
  }

  it should "require fresh acknowledgment after an uncertain checkpoint commit" in {
    val db    = new Fixture
    val clock = db.clock()
    clock.now() shouldBe start
    db.time.set(start.plusSeconds(1)); db.loseCommitAcknowledgement.set(true)
    intercept[JdbcAuthException](clock.now()).error shouldBe JdbcAuthError.StorageFailure
    db.time.set(start.minusSeconds(1))
    clock.now() shouldBe start.plusSeconds(1)
    db.counts shouldBe (3, 3, 3)
  }

  "A shared JDBC clock" should "observe another writer even when its own sample remains unchanged" in {
    val db    = new Fixture
    val first = db.clock(exclusive = false)
    first.now() shouldBe start
    db.time.set(start.plusSeconds(1))
    db.clock(exclusive = false).now() shouldBe start.plusSeconds(1)
    db.time.set(start)
    first.now() shouldBe start.plusSeconds(1)
    db.counts shouldBe (3, 3, 3)
  }
}
