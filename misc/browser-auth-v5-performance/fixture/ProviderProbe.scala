package spoonbillprobe

import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.sql.{Connection, SQLException, SQLFeatureNotSupportedException, Timestamp}
import java.time.Instant
import java.util.{Properties, UUID}
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.logging.Logger
import javax.sql.DataSource
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.performance.PerformanceProbe
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.jdbc.*
import spoonbill.server.BrowserSessionToken

object ProviderProbe {
  private given Effect[Future] = new Effect.FutureEffect
  private val fixedTime = Instant.parse("2026-10-11T12:00:00Z")
  private val expiry = fixedTime.plusSeconds(86400)
  private val binding = "b" * 43
  private val tokenText = "t" * 43
  private val token = BrowserSessionToken.fromString(tokenText).toOption.get
  private val sequence = new AtomicLong(1)
  private def id(): UUID = new UUID(42L, sequence.getAndIncrement())
  private def connectionId(): ConnectionId = ConnectionId.fromUuid(id())
  private def await[A](value: Future[A]): A = Await.result(value, 15.seconds)
  private def accepted[E,A](value: Either[E,A]): A = value.fold(error => throw new IllegalStateException(error.toString), identity)
  private val principal = HostPrincipal("synthetic", "synthetic", 1, 1, expiry)
  private def quote(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
  private class Source(underlying: Option[DataSource] = None, opened: () => Unit = () => ()) extends DataSource {
    private val driver = new org.postgresql.Driver()
    def getConnection: Connection = {
      val result = underlying match {
        case Some(source) => source.getConnection
        case None =>
          val properties = new Properties()
          properties.setProperty("user", sys.env.getOrElse("SPOONBILL_JDBC_TEST_USER", "spoonbill_test"))
          properties.setProperty("options", "-c statement_timeout=10000 -c lock_timeout=8000")
          properties.setProperty("sslmode", "disable")
          properties.setProperty("gssEncMode", "disable")
          Option(driver.connect(sys.env("SPOONBILL_JDBC_TEST_URL"), properties)).getOrElse(throw new SQLException("Test URL rejected"))
      }
      opened(); result
    }
    def getConnection(user: String, password: String): Connection = throw new SQLFeatureNotSupportedException()
    def getLogWriter: PrintWriter = throw new SQLFeatureNotSupportedException()
    def setLogWriter(writer: PrintWriter): Unit = throw new SQLFeatureNotSupportedException()
    def getLoginTimeout: Int = 0
    def setLoginTimeout(seconds: Int): Unit = ()
    def getParentLogger: Logger = Logger.getLogger("spoonbill-provider-probe")
    def isWrapperFor(clazz: Class[?]): Boolean = false
    def unwrap[A](clazz: Class[A]): A = throw new SQLException("Not wrapped")
  }
  trait Bench {
    val provider: BrowserPersistence[Future,String]
    def prepare(ceremony: BrowserCeremony): CommittedAuthentication
    def increment(view: BrowserView[String]): Long
    def retained: Long
  }
  case class HostState(session: Option[UUID], count: Long)
  private def memory(): Bench = new Bench {
    val memoryProvider = new InMemoryBrowserPersistence[Future,HostState,String](HostState(None, 0),
      new InMemoryBrowserPersistence.Host[HostState,String] {
        def resolve(state: HostState, session: UUID, now: Instant): Option[HostPrincipal[String]] =
          Option.when(state.session.contains(session) && now.isBefore(expiry))(principal)
      }, () => fixedTime, () => id())
    val provider = memoryProvider
    def prepare(ceremony: BrowserCeremony): CommittedAuthentication = accepted(await(memoryProvider.prepare(ceremony) { tx =>
      val session = id(); tx.modify(_.copy(session = Some(session)))
      new HostSessionPrepared(session, id(), expiry, token)
    }))
    def increment(view: BrowserView[String]): Long = accepted(await(memoryProvider.transact(view) { tx =>
      tx.modify(state => state.copy(count = state.count + 1)); tx.hostState.count
    }))
    def retained: Long = await(memoryProvider.resources).views.toLong
  }
  private def jdbc(source: DataSource, clocks: DataSource, ec: ExecutionContext): Bench = new Bench {
    private val realm = "probe-" + UUID.randomUUID().toString
    private val host = new JdbcHostSessions[String] {
      def resolve(connection: Connection, session: UUID, now: Instant): Option[HostPrincipal[String]] =
        Using.resource(connection.prepareStatement("SELECT expires FROM spoonbill_probe_host WHERE realm=? AND session=? FOR UPDATE")) { query =>
          query.setString(1, realm); query.setObject(2, session)
          Using.resource(query.executeQuery()) { rows =>
            if (rows.next() && now.isBefore(rows.getTimestamp(1).toInstant)) Some(principal) else None
          }
        }
      def acknowledge(connection: Connection, completion: UUID, session: UUID, now: Instant): Unit = ()
    }
    val jdbcProvider = ProviderAdapter.jdbc(source, clocks, ec, realm, () => fixedTime, host,
      JdbcCredentialVault.aesGcm(Array.fill[Byte](32)(42)))
    val provider = jdbcProvider
    Using.resource(source.getConnection) { connection =>
      jdbcProvider.initialize(connection)
      Using.resource(connection.createStatement())(_.executeUpdate("CREATE TABLE IF NOT EXISTS spoonbill_probe_host(realm TEXT PRIMARY KEY,session UUID NOT NULL,expires TIMESTAMPTZ NOT NULL,counter BIGINT NOT NULL)"))
    }
    def prepare(ceremony: BrowserCeremony): CommittedAuthentication = accepted(await(jdbcProvider.prepare(ceremony) { tx =>
      val session = id()
      Using.resource(tx.connection.prepareStatement("INSERT INTO spoonbill_probe_host VALUES (?,?,?,0)")) { query =>
        query.setString(1, realm); query.setObject(2, session); query.setTimestamp(3, Timestamp.from(expiry)); query.executeUpdate()
      }
      new HostSessionPrepared(session, id(), expiry, token)
    }))
    def increment(view: BrowserView[String]): Long = accepted(await(jdbcProvider.transact(view) { tx =>
      Using.resource(tx.connection.prepareStatement("UPDATE spoonbill_probe_host SET counter=counter+1 WHERE realm=? RETURNING counter")) { query =>
        query.setString(1, realm)
        Using.resource(query.executeQuery()) { rows => require(rows.next()); rows.getLong(1) }
      }
    }))
    def retained: Long = Using.resource(source.getConnection) { connection =>
      Using.resource(connection.prepareStatement("SELECT count(*) FROM spoonbill_browser_view WHERE realm=?")) { query =>
        query.setString(1, realm); Using.resource(query.executeQuery()) { rows => rows.next(); rows.getLong(1) }
      }
    }
  }
  private def gcCount: Long = ManagementFactory.getGarbageCollectorMXBeans.asScala.map(_.getCollectionCount).filter(_ >= 0).sum
  private def retainedHeap(): Long = {
    Thread.sleep(30000)
    for (_ <- 0 until 2) {
      val before = gcCount
      System.gc()
      val deadline = System.nanoTime() + 3.seconds.toNanos
      while (gcCount <= before && System.nanoTime() < deadline) Thread.sleep(10)
      require(gcCount > before, "Explicit GC not observed")
      Thread.sleep(100)
    }
    ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
  }
  def main(args: Array[String]): Unit = {
    val Array(variant, backend, pass, block) = args
    require(Set("memory","jdbc").contains(backend) && Set("latency","memory").contains(pass))
    val executor = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutorService(executor)
    val raw = new Source()
    val probe = Option.when(pass == "memory")(new PerformanceProbe(raw))
    val source = probe.fold[DataSource](raw)(_.dataSource)
    val checkpoints = new AtomicLong()
    val clockSource = new Source(Some(source), () => { checkpoints.incrementAndGet(); () })
    val bench = if (backend == "memory") memory() else jdbc(source, clockSource, ec)
    try {
      val qsid = Qsid("probe-device", "initial-view")
      val anonymous = accepted(await(bench.provider.capture(binding, None, false)))
      val loginView = accepted(await(bench.provider.claim(anonymous, qsid, connectionId(), false)))
      val receipt = bench.prepare(accepted(await(bench.provider.begin(loginView, "login"))))
      require(await(bench.provider.deliver(receipt.completionId, binding)).isDefined)
      val identity = accepted(await(bench.provider.capture(binding, Some(tokenText), true)))
      var current = accepted(await(bench.provider.claim(identity, qsid, connectionId(), true)))
      await(bench.provider.release(loginView))
      var expected = 0L
      def operation(): Unit = {
        val value = bench.increment(current)
        expected += 1
        require(value == expected)
        require(accepted(await(bench.provider.authorize(current))).principal.exists(_.subject == "synthetic"))
      }
      def reconnect(): Unit = {
        val previous = current
        current = accepted(await(bench.provider.claim(identity, qsid, connectionId(), true)))
        await(bench.provider.release(previous))
        require(await(bench.provider.authorize(previous)).isLeft)
        require(accepted(await(bench.provider.authorize(current))).principal.exists(_.subject == "synthetic"))
      }
      val warmup = if (backend == "memory") 1000 else 50
      val iterations = if (backend == "memory") 5000 else 200
      for (_ <- 0 until warmup) operation()
      for (_ <- 0 until 10) reconnect()
      def base(kind: String): String = s"\"schema\":\"spoonbill-provider-probe-v1\",\"variant\":${quote(variant)},\"backend\":${quote(backend)},\"pass\":${quote(pass)},\"block\":${quote(block)},\"kind\":${quote(kind)},\"javaVersion\":${quote(System.getProperty("java.version"))},\"fixedClock\":true,\"exclusiveScope\":true"
      if (pass == "latency") {
        def latency(kind: String, count: Int)(step: () => Unit): Unit = {
          val samples = new Array[Long](count)
          val clockBefore = checkpoints.get()
          val start = System.nanoTime()
          for (index <- 0 until count) { val before = System.nanoTime(); step(); samples(index) = System.nanoTime() - before }
          val elapsed = System.nanoTime() - start
          scala.util.Sorting.quickSort(samples)
          def at(p: Double): Long = samples(math.ceil(p * count).toInt - 1)
          println(s"{${base(kind)},\"operations\":$count,\"elapsedNs\":$elapsed,\"p50Ns\":${at(.50)},\"p95Ns\":${at(.95)},\"p99Ns\":${at(.99)},\"clockConnections\":${checkpoints.get()-clockBefore}}")
        }
        latency("protected-operation", iterations)(() => operation())
        latency("same-view-reconnect", if (backend == "memory") 1000 else 100)(() => reconnect())
      } else {
        val resourceProbe = probe.get
        val clockBefore = checkpoints.get()
        val (_, metrics) = resourceProbe.measure { for (_ <- 0 until iterations) operation() }
        require(metrics.allocatedBytes >= 0 && metrics.activeConnectionsAfter == 0)
        println(s"{${base("protected-operation")},\"operations\":$iterations,\"clockConnections\":${checkpoints.get()-clockBefore},${metrics.jsonFields}}")
        val (_, reconnectMetrics) = resourceProbe.measure { for (_ <- 0 until (if (backend == "memory") 1000 else 100)) reconnect() }
        println(s"{${base("same-view-reconnect")},\"operations\":${if (backend == "memory") 1000 else 100},${reconnectMetrics.jsonFields}}")
        val before = retainedHeap()
        val oldCount = bench.retained
        val churn = if (backend == "memory") 500 else 50
        for (index <- 0 until churn) {
          val view = accepted(await(bench.provider.claim(identity, Qsid("probe-device", s"retained-$index"), connectionId(), false)))
          await(bench.provider.release(view))
        }
        val retainedCount = bench.retained
        require(retainedCount == oldCount + churn)
        val after = retainedHeap()
        println(s"{${base("distinct-view-retention")},\"operations\":$churn,\"heapBefore\":$before,\"heapAfter\":$after,\"heapGrowth\":${after-before},\"retainedViewsBefore\":$oldCount,\"retainedViewsAfter\":$retainedCount,\"gcObserved\":true,\"quietSecondsPerBoundary\":30}")
      }
    } finally { await(bench.provider.close()); ec.shutdown(); executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS) }
  }
}
