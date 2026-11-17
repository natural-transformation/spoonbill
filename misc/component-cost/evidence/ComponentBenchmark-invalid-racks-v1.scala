package spoonbill.internal

import avocet.{Id, StatefulRenderContext, XmlNs}
import avocet.impl.DiffRenderContext
import avocet.dsl.*
import avocet.dsl.html.*
import spoonbill.{Component, Context, Qsid}
import java.lang.management.ManagementFactory
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*

/** Same operations on both revisions. Producer liveness fences allocation sampling. */
object ComponentBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global
  private final case class Work(run: () => Unit, close: () => Unit)
  private final case class Measurement(operations: Long, nanos: Long, bytes: Long, producerBytes: Long, alive: Int)
  private def await[A](result: Future[A]): A = Await.result(result, 5.seconds)
  private val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var allocationSink: AnyRef = null
  @volatile private var outputSink: String = ""

  private def allocated: Long = {
    val bytes = allocation.getTotalThreadAllocatedBytes
    require(bytes >= 0L, "Global allocation counter unavailable")
    bytes
  }

  private def fixture(scenario: String): Work = {
    require(Set("ready1", "changing1", "ready32", "changing32", "loading1")(scenario))
    val width = if (scenario.endsWith("32")) 32 else 1
    val changing = scenario.startsWith("changing")
    val loading = scenario == "loading1"
    given Reporter = Reporter.PrintReporter
    given ExecutionContext = ExecutionContext.parasitic
    val frontend = new Frontend[Future](Queue[Future, String]().stream, None)
    var writes = 0L
    val storage = StateManager.cached[Future](Map(Id.TopLevel -> 0), onWrite = (_, _) => { writes += 1L; Future.unit })
    val changes = Queue[Future, (Id, Any, Option[Effect.Promise[Unit]])]()
    val loaded = Promise[Int]()
    var starts = 0L
    var displays = 0L
    var placeholders = 0L
    var parameterChanges = 0L
    var frames = 0L
    val initial: Either[Int => Future[Int], Int] = if (loading) Left((_: Int) => { starts += 1L; loaded.future }) else Right(1)
    val child = new Component[Future, Int, Int, Unit](initial, "bench-child") {
      override def maybeUpdateState(parameters: Int, current: Int): Option[Future[Int]] = {
        parameterChanges += 1L
        None
      }
      def render(parameters: Int, state: Int): context.Node = {
        displays += 1L
        div(if (parameters == 0) "zero" else "one")
      }
      override def renderNoState(parameters: Int): context.Node = { placeholders += 1L; div("loading") }
    }
    val component = new Component[Future, Int, Int, Unit](0, "bench-root") {
      import context.*
      def render(parameters: Int, state: Int): context.Node = avocet.Document.Node[Context.Binding[Future, Int, Unit]] { rc =>
        rc.openNode(XmlNs.html, "html")
        rc.openNode(XmlNs.html, "body")
        var index = 0
        while (index < width) { rc.addMisc(child(parameters)); index += 1 }
        rc.closeNode("body")
        rc.closeNode("html")
      }
    }
    val root = new ComponentInstance[Future, Int, Unit, Int, Int, Unit](
      Id.TopLevel, Qsid("component-cost", scenario), frontend, new EventRegistry(frontend), storage, component, changes,
      (rc, add) => new StatefulRenderContext[Context.Binding[Future, Int, Unit]] {
        def currentContainerId: Id = rc.currentContainerId
        def currentId: Id = rc.currentId
        def subsequentId: Id = rc.subsequentId
        def openNode(ns: XmlNs, name: String): Unit = rc.openNode(ns, name)
        def closeNode(name: String): Unit = rc.closeNode(name)
        def setAttr(ns: XmlNs, name: String, value: String): Unit = rc.setAttr(ns, name, value)
        def setStyle(name: String, value: String): Unit = rc.setStyle(name, value)
        def addTextNode(text: String): Unit = rc.addTextNode(text)
        def addMisc(misc: Context.Binding[Future, Int, Unit]): Unit = add(this, misc)
      }, new Scheduler[Future], Reporter.PrintReporter, PartialFunction.empty, ExecutionContext.parasitic)
    val rc = DiffRenderContext[Context.Binding[Future, Int, Unit]]()
    val performer = new RemoteDomChangesPerformer
    // Ownership and stored state do not change in these render-only cells.
    val snapshot = await(root.stateSnapshot)
    def render(parameters: Int, first: Boolean = false): Unit = {
      root.applyRenderContext(parameters, rc, snapshot)
      rc.finalizeDocument()
      performer.buffer.clear()
      // The initial pre-rendered baseline is accepted without diffing an empty document.
      if (!first) rc.diff(performer)
      outputSink = performer.buffer.toString
      root.dropObsoleteMisc()
      rc.swap()
      rc.reset()
      frames += 1L
    }
    render(0, first = true)
    render(1)
    render(0)
    var parameter = 0
    Work(() => { if (changing) parameter ^= 1; render(parameter) }, () => {
      require(writes == 0L)
      require(if (loading) displays == 0L && placeholders == frames && starts == 1L
        else displays == frames * width && placeholders == 0L && starts == 0L)
      require(parameterChanges == (if (loading) 0L else if (changing) frames * width else 3L * width))
      require(if (changing) outputSink.nonEmpty else outputSink.isEmpty)
      await(frontend.close())
      await(root.destroy())
      loaded.trySuccess(20)
      require(writes == 0L, "Retired loader wrote late state")
      await(changes.close())
    })
  }

  private def measure(work: Work, writers: Int, duration: Int, fixedIterations: Option[Int] = None): Measurement = {
    val ready = new CountDownLatch(writers)
    val start = new CountDownLatch(1)
    val done = new CountDownLatch(writers)
    val release = new CountDownLatch(1)
    val errors = new AtomicReference[Throwable]()
    val completed = new Array[Long](writers)
    val producerAllocation = new Array[Long](writers)
    @volatile var until = 0L
    val threads = Vector.tabulate(writers) { writer => new Thread(() => {
      try {
        ready.countDown()
        require(start.await(10, TimeUnit.SECONDS))
        val ownBefore = allocation.getCurrentThreadAllocatedBytes
        require(ownBefore >= 0L)
        try {
          var count = 0L
          fixedIterations match {
            case Some(iterations) =>
              while (count < iterations) { work.run(); count += 1L }
            case None =>
              while (System.nanoTime() < until) {
                var index = 0
                while (index < 128) { work.run(); index += 1 }
                count += 128L
              }
          }
          completed(writer) = count
        } finally {
          val ownAfter = allocation.getCurrentThreadAllocatedBytes
          require(ownAfter >= ownBefore)
          producerAllocation(writer) = ownAfter - ownBefore
        }
      } catch { case error: Throwable => errors.compareAndSet(null, error); () }
      finally {
        done.countDown()
        // Thread.join is not a safe boundary for global allocation accounting.
        // Keep every producer alive until the main thread has sampled totals.
        if (!release.await(30, TimeUnit.SECONDS))
          errors.compareAndSet(null, new IllegalStateException("Allocation sampling did not release producers"))
      }
    }, s"component-writer-$writer") }
    threads.foreach(_.setDaemon(true))
    threads.foreach(_.start())
    require(ready.await(10, TimeUnit.SECONDS))
    val beforeAllocation = allocated
    val before = System.nanoTime()
    until = before + duration.seconds.toNanos
    start.countDown()
    val measurement = try {
      require(done.await(duration + 30L, TimeUnit.SECONDS), "Benchmark did not drain")
      val elapsed = System.nanoTime() - before
      val alive = threads.count(_.isAlive)
      require(alive == writers, "An allocation producer exited before sampling")
      val producerBytes = producerAllocation.sum
      val bytes = allocated - beforeAllocation
      require(bytes >= producerBytes, s"Global allocation $bytes omitted producer bytes $producerBytes")
      Option(errors.get()).foreach(error => throw error)
      val operations = completed.sum
      require(operations > 0)
      Measurement(operations, elapsed, bytes, producerBytes, alive)
    } finally {
      release.countDown()
      threads.foreach(_.join(5000L))
    }
    require(threads.forall(thread => !thread.isAlive), "Allocation producers did not exit")
    work.close()
    measurement
  }

  private def counterCheck(): Unit = {
    val writers = 4
    val iterations = 1024
    val payload = 4096
    val result = measure(Work(() => { allocationSink = new Array[Byte](payload) }, () => ()), writers, 1,
      Some(iterations))
    val minimum = writers.toLong * iterations * payload
    require(result.producerBytes >= minimum, "Known escaping arrays were not accounted")
    require(result.bytes >= result.producerBytes)
    println(s"""{"protocol":"component-cost-v1","counterCheck":true,"writers":$writers,"workersAliveAtSample":${result.alive},"minimumPayloadBytes":$minimum,"producerAllocatedBytes":${result.producerBytes},"allocatedBytes":${result.bytes}}""")
  }

  def main(args: Array[String]): Unit = {
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    if (args.toList == List("--counter-check")) { counterCheck(); return }
    val Array(label, scenario, writersArg, warmArg, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val writers = writersArg.toInt
    val warm = warmArg.toInt
    val seconds = secondsArg.toInt
    require(writers == 1 && warm > 0 && seconds > 0)
    Reporter.PrintReporter.level = Reporter.Level.Error
    // Warmup also samples while producers are alive, priming the JVM high-water mark.
    val warmed = measure(fixture(scenario), writers, warm)
    val result = measure(fixture(scenario), writers, seconds)
    val wall = result.nanos / 1e9
    println(s"""{"protocol":"component-cost-v1","label":"$label","scenario":"$scenario","writers":$writers,"warmupSeconds":$warm,"seconds":$seconds,"completed":${result.operations},"elapsedSeconds":$wall,"operationsPerSecond":${result.operations / wall},"allocatedBytes":${result.bytes},"producerAllocatedBytes":${result.producerBytes},"bytesPerOperation":${result.bytes.toDouble / result.operations},"producerBytesPerOperation":${result.producerBytes.toDouble / result.operations},"allocationCounterGapBytes":${result.bytes - result.producerBytes},"workersAliveAtSample":${result.alive},"warmupAllocatedBytes":${warmed.bytes},"warmupProducerAllocatedBytes":${warmed.producerBytes},"warmupWorkersAliveAtSample":${warmed.alive},"allocationVerified":true,"java":"${System.getProperty("java.version")}","processors":${Runtime.getRuntime.availableProcessors()},"maxHeapBytes":${Runtime.getRuntime.maxMemory()},"correct":true}""")
  }
}
