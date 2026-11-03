package spoonbill.security.jdbc

import java.io.PrintWriter
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.{CountDownLatch, TimeUnit}
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
    // Holds the next checkpoint connection's acquisition: (entered, resume).
    val pause = new AtomicReference(Option.empty[(CountDownLatch, CountDownLatch)])
    val realm                     = "clock-" + UUID.randomUUID()
    val source: DataSource = new DataSource {
      // Keep connection creation independent of sbt's driver-discovery context.
      private val driver = new org.postgresql.Driver()
      def getConnection: Connection = {
        connections.incrementAndGet()
        pause.getAndSet(None).foreach { case (entered, resume) => entered.countDown(); resume.await(5, TimeUnit.SECONDS) }
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

  it should "return real time within its checkpoint lease with no JDBC work" in {
    val db    = new Fixture
    val clock = db.clock()
    clock.now() shouldBe start
    // The first advance commits one checkpoint a lease (one second) ahead.
    db.time.set(start.plusMillis(1))
    clock.now() shouldBe start.plusMillis(1)
    db.counts shouldBe (2, 2, 2)
    db.time.set(start.plusMillis(500))
    clock.now() shouldBe start.plusMillis(500)
    db.time.set(start.plusMillis(1001))
    clock.now() shouldBe start.plusMillis(1001)
    db.counts shouldBe (2, 2, 2)
    db.time.set(start.plusMillis(1002))
    clock.now() shouldBe start.plusMillis(1002)
    db.counts shouldBe (3, 3, 3)
  }

  /** Starts a caller thread; its result or failure is kept for the assertions. */
  private final class Caller(clock: JdbcBrowserClock) {
    val result  = new AtomicReference[Either[Throwable, Instant]]()
    val thread  = new Thread(() => result.set(scala.util.Try(clock.now()).toEither))
    thread.start()
    // Returns once the caller waits for the renewal lock, or has finished.
    def awaitWaiting(): Unit = {
      val bound   = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      val settled = Set(Thread.State.BLOCKED, Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.TERMINATED)
      while (!settled.contains(thread.getState) && System.nanoTime() < bound) Thread.sleep(1)
    }
    def outcome: Either[Throwable, Instant] = { thread.join(5000); result.get() }
  }

  it should "share one lease renewal between concurrent observations" in {
    val db    = new Fixture
    val clock = db.clock()
    clock.now() shouldBe start
    val entered = new CountDownLatch(1)
    val resume  = new CountDownLatch(1)
    db.pause.set(Some(entered -> resume))
    db.time.set(start.plusMillis(1))
    val first = new Caller(clock)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    // A later observation crosses the same lease while that renewal is in flight.
    db.time.set(start.plusMillis(2))
    val second = new Caller(clock)
    second.awaitWaiting()
    // Time passes while it waits; it must judge expiry at the time it returns.
    db.time.set(start.plusMillis(3))
    resume.countDown()
    // Each caller returns a fresh observation, never another caller's lease.
    first.outcome shouldBe Right(start.plusMillis(1))
    second.outcome shouldBe Right(start.plusMillis(3))
    db.counts shouldBe (2, 2, 2)
  }

  it should "fail an observation that waited across a failed renewal" in {
    val db    = new Fixture
    val clock = db.clock()
    clock.now() shouldBe start
    val entered = new CountDownLatch(1)
    val resume  = new CountDownLatch(1)
    db.pause.set(Some(entered -> resume)); db.failAcquire.set(true)
    db.time.set(start.plusMillis(1))
    val first = new Caller(clock)
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    db.time.set(start.plusMillis(2))
    val second = new Caller(clock)
    second.awaitWaiting()
    resume.countDown()
    // The waiting caller fails with the renewal instead of retrying it in turn.
    for (outcome <- Seq(first.outcome, second.outcome))
      outcome.left.toOption.collect { case error: JdbcAuthException => error.error } shouldBe Some(JdbcAuthError.StorageFailure)
    db.counts shouldBe (2, 1, 1)
    // A caller that starts after the failure retries normally.
    db.time.set(start.plusMillis(3))
    clock.now() shouldBe start.plusMillis(3)
    db.counts shouldBe (3, 2, 2)
  }

  it should "never return an earlier time than its acknowledged lease after restart" in {
    val db    = new Fixture
    val first = db.clock()
    first.now() shouldBe start
    db.time.set(start.plusMillis(1))
    first.now() shouldBe start.plusMillis(1)
    // The restarted process's clock is behind; the durable lease bounds it.
    db.time.set(start.minusSeconds(10))
    db.clock().now() shouldBe start.plusMillis(1).plusSeconds(1)
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

  it should "renew ahead only on demand in the second half of a lease, so callers do no JDBC work" in {
    val f     = new Fixture
    val clock = f.clock()
    // No observation yet: nothing to renew.
    clock.renewAhead() shouldBe false
    f.counts shouldBe ((0, 0, 0))
    clock.now() shouldBe start
    val exact = f.counts
    // The exact first checkpoint leaves no lease; the renewal commits one.
    clock.renewAhead() shouldBe true
    f.counts._1 shouldBe exact._1 + 1
    val renewed = f.counts
    // Within the lease a caller does no JDBC work and a renewal is not due.
    f.time.set(start.plusMillis(400))
    clock.now() shouldBe start.plusMillis(400)
    clock.renewAhead() shouldBe false
    f.counts shouldBe renewed
    // In the second half of the lease a renewal is due, but only after an observation.
    f.time.set(start.plusMillis(600))
    clock.renewAhead() shouldBe false
    clock.now() shouldBe start.plusMillis(600)
    clock.renewAhead() shouldBe true
    val ahead = f.counts
    ahead._1 shouldBe renewed._1 + 1
    // The caller that would have paid the renewal at the boundary now pays nothing.
    f.time.set(start.plusMillis(1500))
    clock.now() shouldBe start.plusMillis(1500)
    f.counts shouldBe ahead
  }

  it should "not wait for a renewal in progress when renewing ahead" in {
    val f     = new Fixture
    val clock = f.clock()
    clock.now() shouldBe start
    f.time.set(start.plusSeconds(1))
    val entered = new CountDownLatch(1)
    val resume  = new CountDownLatch(1)
    f.pause.set(Some((entered, resume)))
    val caller = new Thread(() => { clock.now(); () })
    caller.start()
    entered.await(5, TimeUnit.SECONDS) shouldBe true
    // The caller holds the renewal lock inside its checkpoint; renewing ahead returns at once.
    clock.renewAhead() shouldBe false
    resume.countDown()
    caller.join(5000)
    // The foreground renewal that completed meanwhile moved the lease two
    // seconds out. With fresh demand, the background path decides under the
    // lock from wall time: it neither renews again nor adopts that lease as
    // observed time.
    clock.now() shouldBe start.plusSeconds(1)
    val renewed = f.counts
    clock.renewAhead() shouldBe false
    f.counts shouldBe renewed
    f.time.set(start.plusMillis(1900))
    clock.now() shouldBe start.plusMillis(1900)
    f.counts shouldBe renewed
  }

  it should "not extend an adopted future checkpoint without wall-clock progress" in {
    val f     = new Fixture
    val first = f.clock()
    first.now() shouldBe start
    first.renewAhead() shouldBe true
    // A restart adopts the lease: at most one second ahead of a fixed wall clock.
    val second = f.clock()
    second.now() shouldBe start.plusSeconds(1)
    // Without wall-clock progress the adopted checkpoint is not extended, so
    // repeated restarts stay within one lease of wall time.
    second.renewAhead() shouldBe false
    val third = f.clock()
    third.now() shouldBe start.plusSeconds(1)
    // Once wall time reaches the lease's second half, the renewal extends it from wall time.
    f.time.set(start.plusMillis(600))
    third.now() shouldBe start.plusSeconds(1)
    third.renewAhead() shouldBe true
    f.time.set(start.plusMillis(1500))
    third.now() shouldBe start.plusMillis(1500)
    val fourth = f.clock()
    fourth.now() shouldBe start.plusMillis(1600)
  }
}
