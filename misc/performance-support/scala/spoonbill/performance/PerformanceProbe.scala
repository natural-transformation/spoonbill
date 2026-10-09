package spoonbill.performance

import java.io.PrintWriter
import java.lang.management.ManagementFactory
import java.lang.reflect.{InvocationHandler, InvocationTargetException, Method, Proxy}
import java.sql.{CallableStatement, Connection, PreparedStatement, Statement}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.locks.LockSupport
import java.util.logging.Logger
import javax.sql.DataSource
import scala.jdk.CollectionConverters.*

/** Test-only metadata instrumentation. Counts JDBC API executions, NOT wire
  * round trips: the driver can batch/rewrite SQL or send transaction commands.
  * Never retains SQL, parameters, credentials, results or connection strings.
  * One measurement may include concurrent workers; measurements cannot overlap.
  * Artificial delay tests JDBC-call sensitivity, not actual network behavior.
  */
final class PerformanceProbe(source: DataSource, val delayMicros: Long = 0L) {
  require(delayMicros >= 0 && delayMicros <= 100000, "Invalid diagnostic JDBC delay")
  import PerformanceProbe.*
  private val measuring = new AtomicBoolean(false)
  private val executions, batches, batchRows, commitsAttempted, commitsSucceeded,
    rollbacks, connections, activeConnections, autoCommitChanges, isolationChanges = new AtomicLong()
  private val os = ManagementFactory.getOperatingSystemMXBean match {
    case bean: com.sun.management.OperatingSystemMXBean => Some(bean)
    case _ => None
  }
  private val threadBean = ManagementFactory.getThreadMXBean match {
    case bean: com.sun.management.ThreadMXBean if bean.isThreadAllocatedMemorySupported =>
      if (!bean.isThreadAllocatedMemoryEnabled) bean.setThreadAllocatedMemoryEnabled(true)
      Some(bean)
    case _ => None
  }
  // JDK 21+ keeps this JVM-wide total across thread retirement. Summing only
  // currently live thread counters loses work when a worker exits mid-sample.
  // Unsupported or non-monotonic totals remain unavailable, never zero.
  private def allocations: Option[Long] = threadBean.flatMap { bean =>
    try Option(bean.getTotalThreadAllocatedBytes).filter(_ >= 0)
    catch { case _: UnsupportedOperationException => None }
  }
  private def threadIds: Set[Long] = ManagementFactory.getThreadMXBean.getAllThreadIds.toSet
  private def heap: Long = ManagementFactory.getMemoryMXBean.getHeapMemoryUsage.getUsed
  private def gc: (Long, Long) = {
    val beans = ManagementFactory.getGarbageCollectorMXBeans.asScala
    (beans.map(_.getCollectionCount).filter(_ >= 0).sum, beans.map(_.getCollectionTime).filter(_ >= 0).sum)
  }
  private def counters: Counters = Counters(executions.get(), batches.get(), batchRows.get(),
    commitsAttempted.get(), commitsSucceeded.get(), rollbacks.get(), connections.get(),
    autoCommitChanges.get(), isolationChanges.get())

  val dataSource: DataSource = decorate(source)

  /** Decorate other schemas/pools with the same counters for one concurrent batch. */
  def decorate(underlying: DataSource): DataSource = new DataSource {
    def getConnection: Connection = wrapConnection(underlying.getConnection)
    def getConnection(user: String, password: String): Connection = wrapConnection(underlying.getConnection(user, password))
    def getLogWriter: PrintWriter = underlying.getLogWriter
    def setLogWriter(writer: PrintWriter): Unit = underlying.setLogWriter(writer)
    def getLoginTimeout: Int = underlying.getLoginTimeout
    def setLoginTimeout(seconds: Int): Unit = underlying.setLoginTimeout(seconds)
    def getParentLogger: Logger = underlying.getParentLogger
    def isWrapperFor(kind: Class[?]): Boolean = kind.isInstance(this)
    def unwrap[T](kind: Class[T]): T =
      if (kind.isInstance(this)) kind.cast(this) else throw new java.sql.SQLException("Unsupported performance wrapper")
  }

  def start(): Start = {
    require(measuring.compareAndSet(false, true), "Overlapping performance measurements")
    // Complete variable-cost metadata collection before opening the resource
    // window. Otherwise thread enumeration/heap sampling count as application
    // CPU even though the latency timer excludes them.
    val beforeCounters = counters
    val beforeThreads = threadIds
    val (collections, gcMillis) = gc
    val beforeHeap = heap
    val beforeConnections = activeConnections.get()
    val allocated = allocations
    val cpu = os.map(_.getProcessCpuTime)
    val started = System.nanoTime()
    Start(beforeCounters, cpu, allocated, beforeThreads, collections, gcMillis,
      beforeHeap, beforeConnections, started)
  }

  def finish(start: Start): SampleMetrics = {
    val elapsed = System.nanoTime() - start.nanoTime
    val cpu = os.map(_.getProcessCpuTime)
    val allocated = allocations
    val endCounters = counters
    val (collections, gcMillis) = gc
    val lostThreads = start.threadIds.diff(threadIds).size.toLong
    val bytes = (start.allocations, allocated) match {
      case (Some(before), Some(after)) if after >= before => after - before
      case _ => -1L
    }
    require(measuring.compareAndSet(true, false), "No active performance measurement")
    SampleMetrics(elapsed, endCounters - start.counters,
      cpu.zip(start.cpuNanos).map { case (after, before) => after - before }.getOrElse(-1L),
      bytes, lostThreads, collections - start.gcCount, gcMillis - start.gcMillis,
      start.heapBytes, heap, start.activeConnections, activeConnections.get())
  }

  def measure[A](run: => A): (A, SampleMetrics) = {
    val beginning = start()
    try { val result = run; result -> finish(beginning) }
    catch { case error: Throwable => measuring.set(false); throw error }
  }

  private def delay(): Unit = if (delayMicros > 0 && measuring.get()) {
    val deadline = System.nanoTime() + delayMicros * 1000L
    var remaining = deadline - System.nanoTime()
    while (remaining > 0 && !Thread.currentThread().isInterrupted) {
      LockSupport.parkNanos(remaining)
      remaining = deadline - System.nanoTime()
    }
  }
  private def invoke(target: AnyRef, method: Method, args: Array[Object]): Object =
    try method.invoke(target, args*) catch {
      case error: InvocationTargetException => throw error.getCause
    }

  private def wrapConnection(connection: Connection): Connection = {
    connections.incrementAndGet()
    activeConnections.incrementAndGet()
    val closed = new AtomicBoolean(false)
    Proxy.newProxyInstance(classOf[Connection].getClassLoader, Array(classOf[Connection]), new InvocationHandler {
      def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
        val args = Option(arguments).getOrElse(Array.empty[Object])
        method.getName match {
          case "createStatement" => wrapStatement(PerformanceProbe.this.invoke(connection, method, args).asInstanceOf[Statement], classOf[Statement])
          case "prepareStatement" => wrapStatement(PerformanceProbe.this.invoke(connection, method, args).asInstanceOf[PreparedStatement], classOf[PreparedStatement])
          case "prepareCall" => wrapStatement(PerformanceProbe.this.invoke(connection, method, args).asInstanceOf[CallableStatement], classOf[CallableStatement])
          case "commit" =>
            commitsAttempted.incrementAndGet(); delay()
            val result = PerformanceProbe.this.invoke(connection, method, args)
            commitsSucceeded.incrementAndGet(); result
          case "rollback" => rollbacks.incrementAndGet(); delay(); PerformanceProbe.this.invoke(connection, method, args)
          case "setAutoCommit" => autoCommitChanges.incrementAndGet(); PerformanceProbe.this.invoke(connection, method, args)
          case "setTransactionIsolation" => isolationChanges.incrementAndGet(); PerformanceProbe.this.invoke(connection, method, args)
          case "close" =>
            val result = PerformanceProbe.this.invoke(connection, method, args)
            if (closed.compareAndSet(false, true)) activeConnections.decrementAndGet()
            result
          case "unwrap" if args.headOption.contains(classOf[Connection]) => proxy
          case "isWrapperFor" if args.headOption.contains(classOf[Connection]) => java.lang.Boolean.TRUE
          case _ => PerformanceProbe.this.invoke(connection, method, args)
        }
      }
    }).asInstanceOf[Connection]
  }

  private def wrapStatement(statement: Statement, kind: Class[?]): Object = {
    val pending = new AtomicLong()
    Proxy.newProxyInstance(kind.getClassLoader, Array(kind), new InvocationHandler {
      def invoke(proxy: Object, method: Method, arguments: Array[Object]): Object = {
        val args = Option(arguments).getOrElse(Array.empty[Object])
        method.getName match {
          case "addBatch" =>
            val result = PerformanceProbe.this.invoke(statement, method, args)
            pending.incrementAndGet(); result
          case "clearBatch" => pending.set(0); PerformanceProbe.this.invoke(statement, method, args)
          case "executeBatch" | "executeLargeBatch" =>
            executions.incrementAndGet(); batches.incrementAndGet(); batchRows.addAndGet(pending.getAndSet(0)); delay()
            PerformanceProbe.this.invoke(statement, method, args)
          case "execute" | "executeQuery" | "executeUpdate" | "executeLargeUpdate" =>
            executions.incrementAndGet(); delay(); PerformanceProbe.this.invoke(statement, method, args)
          case _ => PerformanceProbe.this.invoke(statement, method, args)
        }
      }
    })
  }
}

object PerformanceProbe {
  /** Collected outside operation timing; keeps calibration on the same runtime,
    * database version, machine class and repository-pinned development shell.
    */
  def environment(postgresVersion: String, projectRoot: java.nio.file.Path): Map[String, String] = {
    def fingerprint(name: String): String = java.util.HexFormat.of().formatHex(
      java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(projectRoot.resolve(name))))
    Map("javaVersion" -> System.getProperty("java.version"),
      "javaVm" -> System.getProperty("java.vm.name"), "osName" -> System.getProperty("os.name"),
      "osVersion" -> System.getProperty("os.version"), "osArch" -> System.getProperty("os.arch"),
      "processors" -> Runtime.getRuntime.availableProcessors().toString,
      "jvmMaxHeapBytes" -> Runtime.getRuntime.maxMemory().toString,
      "allocationMeasurement" -> "jvm-total-thread-allocated-bytes-v1",
      "measurementBoundaryVersion" -> "aligned-resource-window-v2",
      "postgresVersion" -> postgresVersion,
      "nixFlakeSha256" -> fingerprint("flake.nix"), "nixLockSha256" -> fingerprint("flake.lock"))
  }
  def stringMapJson(fields: Map[String, String]): String = {
    def quote(value: String): String = "\"" + value.flatMap {
      case '\\' => "\\\\"
      case '"' => "\\\""
      case '\n' => "\\n"
      case '\r' => "\\r"
      case '\t' => "\\t"
      case char if char < ' ' => f"\\u${char.toInt}%04x"
      case char => char.toString
    } + "\""
    fields.toVector.sortBy(_._1).map { case (key, value) => quote(key) + ":" + quote(value) }.mkString("{", ",", "}")
  }
  final case class Counters(sqlExecutions: Long, batches: Long, batchRows: Long,
    commits: Long, committed: Long, rollbacks: Long, connections: Long,
    autoCommitChanges: Long, isolationChanges: Long) {
    def -(other: Counters): Counters = Counters(sqlExecutions - other.sqlExecutions,
      batches - other.batches, batchRows - other.batchRows, commits - other.commits,
      committed - other.committed, rollbacks - other.rollbacks, connections - other.connections,
      autoCommitChanges - other.autoCommitChanges, isolationChanges - other.isolationChanges)
  }
  final case class Start private[performance] (counters: Counters, cpuNanos: Option[Long],
    allocations: Option[Long], threadIds: Set[Long], gcCount: Long, gcMillis: Long,
    heapBytes: Long, activeConnections: Long, nanoTime: Long)
  final case class SampleMetrics(elapsedNanos: Long, counts: Counters, processCpuNanos: Long,
    allocatedBytes: Long, allocationTrackedThreadsLost: Long, gcCount: Long, gcTimeMillis: Long,
    heapUsedBeforeBytes: Long, heapUsedAfterBytes: Long,
    activeConnectionsBefore: Long, activeConnectionsAfter: Long) {
    def fields: Map[String, Long] = Map(
      "elapsedNanos" -> elapsedNanos, "processCpuNanos" -> processCpuNanos,
      "allocatedBytes" -> allocatedBytes, "allocationTrackedThreadsLost" -> allocationTrackedThreadsLost,
      "gcCount" -> gcCount, "gcTimeMillis" -> gcTimeMillis,
      "heapUsedBeforeBytes" -> heapUsedBeforeBytes, "heapUsedAfterBytes" -> heapUsedAfterBytes,
      "activeConnectionsBefore" -> activeConnectionsBefore, "activeConnectionsAfter" -> activeConnectionsAfter,
      "sqlExecutions" -> counts.sqlExecutions, "batches" -> counts.batches, "batchRows" -> counts.batchRows,
      "commits" -> counts.commits, "committed" -> counts.committed, "rollbacks" -> counts.rollbacks,
      "connections" -> counts.connections, "autoCommitChanges" -> counts.autoCommitChanges,
      "isolationChanges" -> counts.isolationChanges)
    def jsonFields: String = fields.toVector.sortBy(_._1).map { case (key, value) => s"\"$key\":$value" }.mkString(",")
  }
}
