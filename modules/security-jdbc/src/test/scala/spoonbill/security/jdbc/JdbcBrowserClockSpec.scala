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
    // Runs on the calling thread before each statement is prepared, with its SQL.
    val prepareHook = new AtomicReference[String => Unit](_ => ())
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
                if (method.getName == "prepareStatement") {
                  statements.incrementAndGet()
                  prepareHook.get()(String.valueOf(args0(arguments)))
                }
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

    /** Lock this realm's clock row in a separate open transaction; the result releases it. */
    def lockRow(): () => Unit = {
      val connection = source.getConnection
      connection.setAutoCommit(false)
      Using.resource(connection.prepareStatement(
        "SELECT 1 FROM spoonbill_auth_clock WHERE realm=? AND namespace='session' FOR UPDATE"
      )) { query =>
        query.setString(1, realm); Using.resource(query.executeQuery())(_.next()) shouldBe true
      }
      () => try connection.rollback() finally connection.close()
    }

    /** Wait until another backend waits for a lock on the clock table. */
    def awaitClockWait(): Unit = Using.resource(source.getConnection) { observer =>
      val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
      def waiting: Boolean = Using.resource(observer.prepareStatement(
        """SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE wait_event_type='Lock'
        AND pid<>pg_backend_pid() AND query LIKE '%spoonbill_auth_clock%')"""
      ))(query => Using.resource(query.executeQuery())(rows => rows.next() && rows.getBoolean(1)))
      while (!waiting && System.nanoTime() < deadline) Thread.sleep(5)
      waiting shouldBe true
    }

    def stored: Instant = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement(
        "SELECT epoch_second,nano FROM spoonbill_auth_clock WHERE realm=? AND namespace='session'"
      )) { query =>
        query.setString(1, realm)
        Using.resource(query.executeQuery()) { rows =>
          rows.next() shouldBe true; Instant.ofEpochSecond(rows.getLong(1), rows.getInt(2).toLong)
        }
      }
    }
  }

  private def args0(arguments: Array[Object]): Object = if (arguments.isEmpty) null else arguments(0)

  /** Runs a checkpoint on its own thread; the outcome is kept for the assertions. */
  private final class Checkpoint(clock: JdbcBrowserClock) {
    val result = new AtomicReference[Either[Throwable, Instant]]()
    val thread = new Thread(() => result.set(scala.util.Try(clock.checkpoint()).toEither))
    thread.start()
    def outcome: Either[Throwable, Instant] = { thread.join(10000); result.get() }
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

  it should "renew at the exact half-lease boundary and consume each demand once" in {
    val f     = new Fixture
    val clock = f.clock()
    clock.now() shouldBe start
    clock.renewAhead() shouldBe true
    val leased = f.counts
    f.time.set(start.plusMillis(500).minusNanos(1))
    clock.now() shouldBe f.time.get()
    clock.renewAhead() shouldBe false
    f.counts shouldBe leased
    f.time.set(start.plusMillis(500))
    // The earlier unsuccessful check consumed demand too.
    clock.renewAhead() shouldBe false
    clock.now() shouldBe f.time.get()
    clock.renewAhead() shouldBe true
    f.counts shouldBe ((leased._1 + 1, leased._2 + 1, leased._3 + 1))
    clock.renewAhead() shouldBe false
  }

  it should "preserve the acknowledged lease after a failed background renewal" in {
    val f     = new Fixture
    val clock = f.clock()
    clock.now() shouldBe start
    clock.renewAhead() shouldBe true
    f.time.set(start.plusMillis(600))
    clock.now() shouldBe f.time.get()
    f.failAcquire.set(true)
    clock.renewAhead() shouldBe false
    val failed = f.counts
    f.time.set(start.plusMillis(900))
    clock.now() shouldBe f.time.get()
    f.counts shouldBe failed
    // A fresh demand retries normally; failure did not acknowledge a new lease.
    clock.renewAhead() shouldBe true
    f.counts shouldBe ((failed._1 + 1, failed._2 + 1, failed._3 + 1))
    f.time.set(start.plusMillis(1800))
    clock.now() shouldBe f.time.get()
  }

  "A shared JDBC clock checkpoint" should "insert the first floor of a scope, then lock and update the row once per call" in {
    val f     = new Fixture
    val clock = f.clock(exclusive = false)
    clock.checkpoint() shouldBe start
    // The locking read, the first-use insert, the locking read again and the update, in one transaction.
    f.counts shouldBe ((1, 4, 1))
    f.time.set(start.plusNanos(1))
    clock.checkpoint() shouldBe start.plusNanos(1)
    f.counts shouldBe ((2, 6, 2))
    f.stored shouldBe start.plusNanos(1)
  }

  it should "sample the instant after waiting for the clock row" in {
    val f     = new Fixture
    val clock = f.clock(exclusive = false)
    clock.checkpoint() shouldBe start
    val release = f.lockRow()
    val waiting = new Checkpoint(clock)
    f.awaitClockWait()
    f.time.set(start.plusSeconds(5))
    release()
    waiting.outcome shouldBe Right(start.plusSeconds(5))
    f.stored shouldBe start.plusSeconds(5)
  }

  it should "never return below the stored floor, this process's floor or the wall clock" in {
    val f = new Fixture
    f.time.set(start.plusSeconds(2))
    f.clock(exclusive = false).now() shouldBe start.plusSeconds(2)
    // A restarted instance with an earlier wall clock gets the stored floor.
    f.time.set(start)
    val clock = f.clock(exclusive = false)
    clock.checkpoint() shouldBe start.plusSeconds(2)
    // A failed observation still sampled a later wall time; the next checkpoint stays at or above it.
    f.time.set(start.plusSeconds(4))
    f.failAcquire.set(true)
    an[JdbcAuthException] should be thrownBy clock.now()
    f.time.set(start.plusSeconds(1))
    clock.checkpoint() shouldBe start.plusSeconds(4)
    f.time.set(start.plusSeconds(6))
    clock.checkpoint() shouldBe start.plusSeconds(6)
  }

  it should "let two writers bootstrap an empty row, ordered by its lock" in {
    val f        = new Fixture
    val first    = f.clock(exclusive = false)
    val second   = f.clock(exclusive = false)
    val updating = new CountDownLatch(1)
    val resume   = new CountDownLatch(1)
    // Armed before the first writer starts: hold whichever update comes first,
    // after its insert and its wall sample. The second writer starts only later.
    val armed = new AtomicBoolean(true)
    f.prepareHook.set { sql =>
      if (sql.trim.startsWith("UPDATE spoonbill_auth_clock") && armed.compareAndSet(true, false)) {
        updating.countDown(); resume.await(5, TimeUnit.SECONDS)
      }
    }
    val earlier = new Checkpoint(first)
    updating.await(5, TimeUnit.SECONDS) shouldBe true
    // The second writer finds no committed row and waits on the first one's insert.
    f.time.set(start.plusSeconds(1))
    val later = new Checkpoint(second)
    f.awaitClockWait()
    resume.countDown()
    earlier.outcome shouldBe Right(start)
    later.outcome shouldBe Right(start.plusSeconds(1))
    f.stored shouldBe start.plusSeconds(1)
  }

  it should "keep a wall-clock sample taken under the row lock when its checkpoint rolls back" in {
    val f     = new Fixture
    val clock = f.clock(exclusive = false)
    clock.checkpoint() shouldBe start
    // The update fails after the sample under the row lock, and the transaction rolls back.
    val failUpdate = new AtomicBoolean(true)
    f.prepareHook.set { sql =>
      if (sql.trim.startsWith("UPDATE spoonbill_auth_clock") && failUpdate.getAndSet(false))
        throw new SQLException("Synthetic failure after the locked sample")
    }
    f.time.set(start.plusSeconds(4))
    intercept[JdbcAuthException](clock.checkpoint()).error shouldBe JdbcAuthError.StorageFailure
    f.stored shouldBe start
    // The process never returns less than a time it sampled, also after the wall clock moves back.
    f.time.set(start.plusSeconds(1))
    clock.checkpoint() shouldBe start.plusSeconds(4)
    f.stored shouldBe start.plusSeconds(4)
  }

  it should "publish no instant from a failed or uncertain checkpoint" in {
    val f     = new Fixture
    val clock = f.clock(exclusive = false)
    clock.checkpoint() shouldBe start
    f.time.set(start.plusSeconds(1))
    f.loseCommitAcknowledgement.set(true)
    intercept[JdbcAuthException](clock.checkpoint()).error shouldBe JdbcAuthError.StorageFailure
    // The commit reached the server, so even an earlier wall clock now gets that floor.
    f.time.set(start)
    clock.checkpoint() shouldBe start.plusSeconds(1)
    f.failAcquire.set(true)
    intercept[JdbcAuthException](clock.checkpoint()).error shouldBe JdbcAuthError.StorageFailure
  }
}
