package spoonbill.performance

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Reporter, Stream}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.server.{SessionAccessControl, SessionGuard, SpoonbillServiceConfig, StateLoader}
import spoonbill.server.internal.services.{CommonService, MessagingService, PageService, SessionsService}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.web.{PathAndQuery, Request}

/** Serial in-process guarded-session startup and disposal. The same source is
  * compiled against both trees; CoreResponseAccess isolates the response API.
  * Every measured operation validates completed cleanup, not merely its start.
  */
object CoreGuardedSessionBenchmark {
  def main(args: Array[String]): Unit = {
    try {
      run(args)
      System.out.flush()
      // The library's shared Scheduler owns a non-daemon Timer and exposes no
      // shutdown method. All per-operation resource assertions ran above.
      System.exit(0)
    } catch {
      case NonFatal(error) =>
        error.printStackTrace(System.err)
        System.exit(1)
    }
  }

  private def run(args: Array[String]): Unit = {
    require(args.length == 5, "variant sourceIdentity block warmupIterations measuredIterations")
    val Array(variant, identity, block, warmupArg, iterationsArg) = args
    val warmup = warmupArg.toInt
    val iterations = iterationsArg.toInt
    require(warmup > 0 && iterations > 0)
    implicit val effect: Effect[Future] = Effect.futureEffect
    val backgroundFailure = new AtomicReference[Throwable]()
    // Both variants register/complete Future fibers synchronously. In baseline,
    // resolving input EOF therefore executes the terminal cleanup chain before
    // cancel returns. No polling or scheduling delay is used to infer cleanup.
    implicit val ec: ExecutionContext = new ExecutionContext {
      def execute(task: Runnable): Unit = task.run()
      def reportFailure(error: Throwable): Unit = { backgroundFailure.compareAndSet(null, error); () }
    }
    val reporter = new Reporter {
      def error(message: String, cause: Throwable): Unit = ec.reportFailure(new IllegalStateException(message, cause))
      def error(message: String): Unit = ec.reportFailure(new IllegalStateException(message))
      def warning(message: String, cause: Throwable): Unit = ()
      def warning(message: String): Unit = ()
      def info(message: String): Unit = ()
      def debug(message: String): Unit = ()
      def debug(message: String, arg1: Any): Unit = ()
      def debug(message: String, arg1: Any, arg2: Any): Unit = ()
      def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    }
    val opened = new AtomicInteger(0)
    val closed = new AtomicInteger(0)
    val activeGuards = new AtomicInteger(0)
    val activeInputs = new AtomicInteger(0)
    var closedSignal: Promise[Unit] = null
    val control = new SessionAccessControl[Future, String] {
      def authorizeHttp(request: Request.Head, state: String): Future[Unit] = Future.unit
      def open(qsid: Qsid, request: Request.Head, id: ConnectionId): Future[SessionGuard[Future, String]] = {
        opened.incrementAndGet()
        activeGuards.incrementAndGet()
        val done = closedSignal
        val released = new AtomicBoolean(false)
        Future.successful(new SessionGuard[Future, String] {
          def authorize(state: String): Future[Unit] = Future.unit
          def connected(state: String): Future[String] = Future.successful(state)
          def close(): Future[Unit] = effect.delay {
            require(released.compareAndSet(false, true), "Guard was released more than once")
            activeGuards.decrementAndGet()
            closed.incrementAndGet()
            done.success(())
            ()
          }
        })
      }
    }
    val config = SpoonbillServiceConfig[Future, String, Any](
      stateLoader = StateLoader.default[Future, String]("initial"),
      // One bounded retired snapshot, no disk persistence or growing view IDs.
      stateStorage = StateStorage.ephemeral[Future, String](1),
      sessionAccessControl = Some(control),
      sessionIdleTimeout = 5.minutes,
      heartbeatInterval = 5.minutes,
      reporter = reporter
    )
    val sessions = new SessionsService[Future, String, Any](config, new PageService[Future, String, Any](config))
    val messaging = new MessagingService[Future](reporter, new CommonService[Future], sessions, None, 1.second)
    val qsid = Qsid("benchmark-device", "benchmark-view")
    val request = Request(Request.Method.Get, PathAndQuery.Root, Nil, None, ())
    val deadline = System.nanoTime() + 120.seconds.toNanos
    var deliveredBytes = 0L

    def operation(): Unit = {
      require(System.nanoTime() < deadline, "Core session benchmark exceeded its 120-second bound")
      closedSignal = Promise[Unit]()
      val pendingInput = Promise[Option[Bytes]]()
      val canceled = new AtomicBoolean(false)
      activeInputs.incrementAndGet()
      val input = new Stream[Future, Bytes] {
        def pull(): Future[Option[Bytes]] = pendingInput.future
        def cancel(): Future[Unit] = effect.delay {
          if (canceled.compareAndSet(false, true)) {
            activeInputs.decrementAndGet()
            pendingInput.success(None)
          }
          ()
        }
      }
      Await.result(sessions.initAppState(qsid, request), 5.seconds)
      val response = Await.result(messaging.webSocketMessaging(qsid, request, input, Seq("json")), 5.seconds)
      try {
        val first = Await.result(CoreResponseAccess.output(response).pull(), 5.seconds)
        require(first.nonEmpty, "Live session completed without initial output")
        val bytes = first.get
        require(bytes.asUtf8String.nonEmpty && bytes.asUtf8String != "[1]", "Expected live-session output, not reload")
        deliveredBytes += bytes.asArray.length
      } finally Await.result(CoreResponseAccess.dispose(response, input), 5.seconds)
      Await.result(closedSignal.future, 5.seconds)
      require(Await.result(sessions.getApp(qsid), 5.seconds).isEmpty, "Application remained after disposal")
      require(activeGuards.get() == 0 && activeInputs.get() == 0, "Operation leaked a guard or input")
      Option(backgroundFailure.get()).foreach(error => throw error)
    }

    def runBatch(count: Int, samples: Array[Long]): Unit = {
      var index = 0
      while (index < count) {
        val operationStarted = System.nanoTime()
        operation()
        samples(index) = System.nanoTime() - operationStarted
        index += 1
      }
    }

    val cpuBean = ManagementFactory.getOperatingSystemMXBean.asInstanceOf[com.sun.management.OperatingSystemMXBean]
    val compilationBean = Option(ManagementFactory.getCompilationMXBean).filter(_.isCompilationTimeMonitoringSupported)
    val gcBeans = ManagementFactory.getGarbageCollectorMXBeans.asScala.toVector
    def compilationTimeMs(): Long = compilationBean.fold(-1L)(_.getTotalCompilationTime)
    def gcTotals(): (Long, Long) = {
      val counts = gcBeans.map(_.getCollectionCount)
      val times = gcBeans.map(_.getCollectionTime)
      (if (counts.exists(_ < 0)) -1L else counts.sum, if (times.exists(_ < 0)) -1L else times.sum)
    }
    // Initialize diagnostic access outside both the warmup and measurement.
    compilationTimeMs()
    gcTotals()
    val allocationBean = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    if (allocationBean.isThreadAllocatedMemorySupported && !allocationBean.isThreadAllocatedMemoryEnabled)
      allocationBean.setThreadAllocatedMemoryEnabled(true)
    val totalAllocated = classOf[com.sun.management.ThreadMXBean].getMethods.find(_.getName == "getTotalThreadAllocatedBytes")
    def allocated(): Option[Long] = totalAllocated.flatMap { method =>
      val value = method.invoke(allocationBean).asInstanceOf[java.lang.Long].longValue
      Option.when(value >= 0)(value)
    }
    // Warm the exact timed loop, including nanoTime and the latency store.
    runBatch(warmup, new Array[Long](warmup))
    val samples = new Array[Long](iterations)
    val bytesBefore = deliveredBytes
    val compilationBefore = compilationTimeMs()
    val (gcCountBefore, gcTimeBefore) = gcTotals()
    val allocationsBefore = allocated()
    val cpuBefore = cpuBean.getProcessCpuTime
    val started = System.nanoTime()
    runBatch(iterations, samples)
    val elapsed = System.nanoTime() - started
    val cpu = cpuBean.getProcessCpuTime - cpuBefore
    val allocations = for { before <- allocationsBefore; after <- allocated() } yield after - before
    val compilationAfter = compilationTimeMs()
    val (gcCountAfter, gcTimeAfter) = gcTotals()
    require(opened.get() == warmup + iterations && closed.get() == opened.get(), "Guard totals did not balance")
    java.util.Arrays.sort(samples)
    def percentile(p: Double): Long = samples(math.min(samples.length - 1, math.ceil(p * samples.length).toInt - 1))
    def counter(value: Long): String = if (value < 0) "null" else value.toString
    def delta(before: Long, after: Long): String = if (before < 0 || after < 0) "null" else (after - before).toString
    def quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    println(s"""{"schema":"spoonbill-core-guarded-session-v1","variant":${quoted(variant)},"identity":${quoted(identity)},"block":${quoted(block)},"warmupIterations":$warmup,"iterations":$iterations,"completedOperations":$iterations,"elapsedNs":$elapsed,"operationsPerSecond":${iterations.toDouble * 1e9 / elapsed},"operationLatencyNs":{"p50":${percentile(.50)},"p95":${percentile(.95)},"p99":${percentile(.99)}},"processCpuNs":$cpu,"processCpuNsPerOperation":${cpu.toDouble / iterations},"jvmTotalAllocatedBytes":${allocations.fold("null")(_.toString)},"allocatedBytesPerOperation":${allocations.fold("null")(value => (value.toDouble / iterations).toString)},"jitCompilationTimeMsBefore":${counter(compilationBefore)},"jitCompilationTimeMsAfter":${counter(compilationAfter)},"jitCompilationTimeMsDelta":${delta(compilationBefore, compilationAfter)},"gcCollectionCountDelta":${delta(gcCountBefore, gcCountAfter)},"gcCollectionTimeMsDelta":${delta(gcTimeBefore, gcTimeAfter)},"initialOutputBytes":${deliveredBytes - bytesBefore},"guardsOpened":${opened.get()},"guardsClosed":${closed.get()},"remainingGuards":${activeGuards.get()},"remainingInputs":${activeInputs.get()},"remainingApplications":0,"executionContext":"direct-serial","teardownMeasured":true,"javaVersion":${quoted(System.getProperty("java.version"))}}""")
  }
}
