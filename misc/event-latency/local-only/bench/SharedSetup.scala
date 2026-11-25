package bench

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import java.security.MessageDigest
import java.sql.{Connection, Timestamp}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
import spoonbill.auth.*
import spoonbill.security.jdbc.*
import spoonbill.snapshot.ViewSnapshotSession

/**
 * The jdbc-shared mode's switches, read from the environment. Each one selects
 * a production setting. A setting that this build cannot provide yet is
 * refused at startup, so a run never measures something other than its name.
 */
final case class SharedSwitches(
  host: String,
  isolation: String,
  lock: String,
  snapshots: String,
  projection: String,
  cap: Int
) {
  def globalLock: Boolean     = lock == "global"
  def changing: Boolean       = projection == "changing"
  def workCertified: Boolean  = isolation == "work" || isolation == "both"
  def clockCertified: Boolean = isolation == "clock" || isolation == "both"

  def json: String = BenchJson.obj(
    "host"       -> BenchJson.str(host),
    "isolation"  -> BenchJson.str(isolation),
    "lock"       -> BenchJson.str(lock),
    "snapshots"  -> BenchJson.str(snapshots),
    "projection" -> BenchJson.str(projection),
    "cap"        -> cap.toString
  )
}

object SharedSwitches {
  def fromEnvironment(environment: Map[String, String] = sys.env): SharedSwitches = {
    def choice(name: String, default: String, allowed: String*): String = {
      val value = environment.getOrElse(name, default)
      require(allowed.contains(value), s"$name must be one of ${allowed.mkString("|")}, not '$value'")
      value
    }
    val cap = environment.get("BENCH_SHARED_CAP").fold(0) { value =>
      value.toIntOption.filter(_ >= 0).getOrElse {
        throw new IllegalArgumentException(s"BENCH_SHARED_CAP must be a non-negative integer, not '$value'")
      }
    }
    val switches = SharedSwitches(
      choice("BENCH_SHARED_HOST", "legacy", "legacy", "held"),
      choice("BENCH_SHARED_ISOLATION", "neither", "neither", "work", "clock", "both"),
      choice("BENCH_SHARED_LOCK", "session", "session", "global"),
      choice("BENCH_SHARED_SNAPSHOTS", "ordinary", "ordinary", "guarded"),
      choice("BENCH_SHARED_PROJECTION", "constant", "constant", "changing"),
      cap
    )
    val refused = unavailable(switches)
    if (refused.nonEmpty) throw new IllegalArgumentException(refused.mkString("; "))
    switches
  }

  /** The selected settings this build cannot provide yet, each with the production support it needs. */
  def unavailable(switches: SharedSwitches): Vector[String] = Vector(
    Option.when(switches.cap > 0 && switches.host != "held")(
      "BENCH_SHARED_CAP>0 needs BENCH_SHARED_HOST=held: shared-view concurrent checks need a host that holds its resolution"
    )
  ).flatten
}

/**
 * Separate warmed pools for provider work and the durable clock. Each pool has
 * its own PostgreSQL application name, so the statement log attributes every
 * statement to its role. The control connection only writes log markers.
 */
final class SharedPools(url: String, workSize: Int = 32, clockSize: Int = 4) {
  val work: HikariDataSource  = SharedPools.pool(url, SharedPools.WorkApplication, workSize)
  val clock: HikariDataSource = SharedPools.pool(url, SharedPools.ClockApplication, clockSize)

  /** Committed transactions classified by their SQL, per pool. For attribution only: driver-issued SQL is not seen. */
  val workCounted  = new CountingDataSource(work)
  val clockCounted = new CountingDataSource(clock)

  /** Wait until both pools hold all their connections, so that none is opened inside a measurement. */
  def awaitWarm(timeoutMillis: Long = 20000): Unit = {
    val bound = System.nanoTime() + timeoutMillis * 1000000L
    def full(pool: HikariDataSource): Boolean =
      pool.getHikariPoolMXBean.getTotalConnections >= pool.getMaximumPoolSize
    while (!(full(work) && full(clock)) && System.nanoTime() < bound) Thread.sleep(20)
    require(full(work) && full(clock), "The provider and clock pools did not fill in time")
  }

  def control(): Connection = SharedPools.connect(url, SharedPools.ControlApplication)
}

object SharedPools {
  val WorkApplication    = "bench-work"
  val ClockApplication   = "bench-clock"
  val ControlApplication = "bench-control"

  private def pool(url: String, application: String, size: Int): HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(url)
    config.setPoolName(application)
    config.setMaximumPoolSize(size)
    config.setMinimumIdle(size)
    config.addDataSourceProperty("ApplicationName", application)
    new HikariDataSource(config)
  }

  def connect(url: String, application: String): Connection = {
    val properties = new Properties()
    properties.setProperty("ApplicationName", application)
    Option(new org.postgresql.Driver().connect(url, properties))
      .getOrElse(throw new IllegalStateException("Unsupported PostgreSQL URL"))
  }
}

/**
 * A host that keeps one row per session, as a real host does. resolve locks
 * and reads that row (FOR UPDATE). With the global policy lock it first takes
 * one fixed transaction-scoped advisory lock, as a host with one cluster-wide
 * policy lock would. The principal is stable: its expiry is the row's.
 */
class SharedHost(globalLock: Boolean) extends JdbcHostSessions[String] {
  /** Every resolve call, successful or not. */
  val resolutions = new AtomicLong()

  def resolve(connection: Connection, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] = {
    resolutions.incrementAndGet()
    SharedHost.lockAndRead(connection, sessionId, globalLock).filter(principal => now.isBefore(principal.expiresAt))
  }
  def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
}

/**
 * The same host, holding its resolution (BENCH_SHARED_HOST=held): it takes the
 * same locks and reads the same row once per check, and leaves time to the
 * provider. Its cutoff is the row's expiry; it has no idle refresh.
 */
final class SharedHeldHost(globalLock: Boolean) extends SharedHost(globalLock) with JdbcHeldHostSessions[String] {
  def lockResolution(connection: Connection, sessionId: UUID): Option[JdbcHeldResolution[String]] = {
    resolutions.incrementAndGet()
    SharedHost.lockAndRead(connection, sessionId, globalLock).map { held =>
      new JdbcHeldResolution[String] {
        val principal: HostPrincipal[String] = held
        val validUntilExclusive: Instant     = held.expiresAt
      }
    }
  }
}

object SharedHost {
  val schema: String = """CREATE TABLE IF NOT EXISTS bench_host_session (
    id UUID PRIMARY KEY, subject TEXT NOT NULL, expires_at TIMESTAMP WITH TIME ZONE NOT NULL)"""

  /** The host's own session row, written inside the sign-in transaction. */
  def insert(connection: Connection, sessionId: UUID): Unit =
    Using.resource(connection.prepareStatement("INSERT INTO bench_host_session(id,subject,expires_at) VALUES (?,?,?)")) {
      query =>
        query.setObject(1, sessionId)
        query.setString(2, sessionId.toString)
        query.setTimestamp(3, Timestamp.from(BenchLogin.principalExpiry))
        query.executeUpdate(); ()
    }

  /** The host's locks and its read. Every host variant locks the same rows in this order. */
  def lockAndRead(connection: Connection, sessionId: UUID, globalLock: Boolean): Option[HostPrincipal[String]] = {
    if (globalLock)
      Using.resource(connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended('bench-host-policy',0))")) {
        query => Using.resource(query.executeQuery())(_.next())
      }
    Using.resource(connection.prepareStatement("SELECT subject,expires_at FROM bench_host_session WHERE id=? FOR UPDATE")) {
      query =>
        query.setObject(1, sessionId)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) None
          else {
            val subject = rows.getString(1)
            Some(HostPrincipal(subject, subject, 1L, 1L, rows.getTimestamp(2).toInstant))
          }
        }
    }
  }
}

/**
 * Everything the jdbc-shared server measures, built in one place from the
 * switches: pools, host, provider and the inline snapshot session factory.
 */
final class SharedSetup(val switches: SharedSwitches, url: String) {
  val pools                      = new SharedPools(url)
  val blocking: ExecutionContext = ExecutionContext.fromExecutorService(Executors.newFixedThreadPool(16))
  val host: SharedHost =
    if (switches.host == "held") new SharedHeldHost(switches.globalLock) else new SharedHost(switches.globalLock)

  // The data sources the provider receives; the counting wrappers only attribute transactions.
  // A certification is the outermost wrapper, as a deployment passes it. Hikari keeps
  // the server default, READ COMMITTED, on every checkout here: nothing changes the level.
  private def certified(source: DataSource, certify: Boolean): DataSource =
    if (certify) JdbcIsolation.readCommittedOnCheckout(source) else source
  private val workSource: DataSource  = certified(pools.workCounted, switches.workCertified)
  private val clockSource: DataSource = certified(pools.clockCounted, switches.clockCertified)

  // Benchmark-only key; never use a fixed key for real deployments.
  private val key = MessageDigest.getInstance("SHA-256").digest("bench-only".getBytes("UTF-8"))

  // A positive cap opts the provider in to shared-view concurrent checks; the clock pool is the reserved clock source.
  val persistence: JdbcBrowserPersistence[Future, String] = host match {
    case held: SharedHeldHost if switches.cap > 0 =>
      JdbcBrowserPersistence.sharedViewsWithConcurrentChecks[Future, String](
        workSource,
        blocking,
        GuardedMain.scope.realm,
        GuardedMain.scope.cookieNamespace,
        () => Instant.now(),
        held,
        JdbcCredentialVault.aesGcm(key),
        JdbcConcurrentChecks(clockSource, switches.cap)
      )
    case _ =>
      new JdbcBrowserPersistence[Future, String](
        workSource,
        blocking,
        GuardedMain.scope.realm,
        GuardedMain.scope.cookieNamespace,
        () => Instant.now(),
        host,
        JdbcCredentialVault.aesGcm(key),
        BrowserOwnership.SharedViews,
        clockSource = Some(clockSource)
      )
  }

  /** The provider's explicit migration and the host table, once, outside every measurement. */
  def initialize(): Unit = Using.resource(pools.work.getConnection) { connection =>
    connection.setAutoCommit(false)
    persistence.initialize(connection)
    Using.resource(connection.createStatement())(_.executeUpdate(SharedHost.schema))
    connection.commit()
  }

  private val padding = "x".repeat(SnapshotMain.payloadBytes)

  /** A constant projection omits the count, so every write projects the same value. */
  def project(state: Counter): CounterSnapshot = CounterSnapshot(if (switches.changing) state.count else 0, padding)
  def restore(fresh: Counter, saved: CounterSnapshot): Counter =
    if (switches.changing) fresh.copy(count = saved.count) else fresh

  /**
   * The provider's own inline session, handed to the coordinator unwrapped:
   * ordinary saves every top-level write; guarded skips a write whose
   * projection encodes to the bytes the session last saved.
   */
  val snapshots: BrowserView[String] => Option[ViewSnapshotSession[Future, Counter]] =
    if (switches.snapshots == "guarded")
      persistence.projectedSnapshotsIfChanged[Counter, CounterSnapshot](SnapshotMain.format, project, restore)
    else persistence.projectedSnapshots[Counter, CounterSnapshot](SnapshotMain.format, project, restore)
}
