package spoonbill.internal

import avocet.Id
import java.lang.management.ManagementFactory
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Reporter}
import spoonbill.state.{GuardedStateManager, StateDeserializer, StateManager, StateSerializer}
import spoonbill.state.javaSerialization.*

/** Same operations on both revisions. Producer liveness fences allocation sampling. */
object OwnershipBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global
  private final case class Work(run: () => Unit, close: () => Unit)
  private final case class Measurement(operations: Long, nanos: Long, bytes: Long, producerBytes: Long, alive: Int)
  private def await[A](result: Future[A]): A = Await.result(result, 5.seconds)
  private val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var allocationSink: AnyRef = null

  private def allocated: Long = {
    val bytes = allocation.getTotalThreadAllocatedBytes
    require(bytes >= 0L, "Global allocation counter unavailable")
    bytes
  }

  private def fixture(scenario: String): Work = {
    val storedSnapshot = new StateManager.Snapshot {
      def apply[T: StateDeserializer](id: Id): Option[T] = Some(0.asInstanceOf[T])
    }
    val store = new StateManager[Future] {
      def read[T: StateDeserializer](id: Id): Future[Option[T]] = Future.successful(Some(0.asInstanceOf[T]))
      def write[T: StateSerializer](id: Id, value: T): Future[Unit] = Future.unit
      def delete(id: Id): Future[Unit] = Future.unit
      def snapshot: Future[StateManager.Snapshot] = Future.successful(storedSnapshot)
    }
    scenario match {
      case "leases" | "snapshots" =>
        val owners = new ComponentOwnership[Future](store, Reporter.PrintReporter, summon[ExecutionContext])
        val owner = owners.bind(Id.TopLevel, deleteOnRetire = false)
        val operation: () => Unit = if (scenario == "leases") () => owner.admit().get.release()
        else () => require(owner.snapshotIsCurrent(await(owners.snapshot)), "Snapshot rejected")
        Work(operation, () => { await(owners.closeAndDrain()); require(!owner.isActive) })
      case "guarded" =>
        val manager = new GuardedStateManager[Future, Int](store, _ => Future.unit)
        Work(() => await(manager.write(Id.TopLevel, 1)), () => await(manager.closeAndDrain()))
      case other => throw new IllegalArgumentException(other)
    }
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
    }, s"owner-writer-$writer") }
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
    println(s"""{"protocol":"ownership-cost-v2","counterCheck":true,"writers":$writers,"workersAliveAtSample":${result.alive},"minimumPayloadBytes":$minimum,"producerAllocatedBytes":${result.producerBytes},"allocatedBytes":${result.bytes}}""")
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
    require(writers > 0 && warm > 0 && seconds > 0)
    Reporter.PrintReporter.level = Reporter.Level.Error
    // Warmup also samples while producers are alive, priming the JVM high-water mark.
    val warmed = measure(fixture(scenario), writers, warm)
    val result = measure(fixture(scenario), writers, seconds)
    val wall = result.nanos / 1e9
    println(s"""{"protocol":"ownership-cost-v2","label":"$label","scenario":"$scenario","writers":$writers,"warmupSeconds":$warm,"seconds":$seconds,"completed":${result.operations},"elapsedSeconds":$wall,"operationsPerSecond":${result.operations / wall},"allocatedBytes":${result.bytes},"producerAllocatedBytes":${result.producerBytes},"bytesPerOperation":${result.bytes.toDouble / result.operations},"producerBytesPerOperation":${result.producerBytes.toDouble / result.operations},"allocationCounterGapBytes":${result.bytes - result.producerBytes},"workersAliveAtSample":${result.alive},"warmupAllocatedBytes":${warmed.bytes},"warmupProducerAllocatedBytes":${warmed.producerBytes},"warmupWorkersAliveAtSample":${warmed.alive},"allocationVerified":true,"java":"${System.getProperty("java.version")}","processors":${Runtime.getRuntime.availableProcessors()},"maxHeapBytes":${Runtime.getRuntime.maxMemory()},"correct":true}""")
  }
}
