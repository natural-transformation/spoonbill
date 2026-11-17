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

/** Same public/private-runtime operations on both revisions; no implementation-specific probes. */
object OwnershipBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global
  private final case class Work(run: () => Unit, close: () => Unit)
  private def await[A](result: Future[A]): A = Await.result(result, 5.seconds)

  private def fixture(scenario: String): Work = {
    // Avoid a changing application map or serialization dominating the admission comparison.
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

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, writersArg, warmArg, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val writers = writersArg.toInt
    val warm = warmArg.toInt
    val seconds = secondsArg.toInt
    require(writers > 0 && warm > 0 && seconds > 0)
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    def allocated: Long = {
      val bytes = allocation.getTotalThreadAllocatedBytes
      require(bytes >= 0L)
      bytes
    }
    Reporter.PrintReporter.level = Reporter.Level.Error

    def round(duration: Int, measured: Boolean): Unit = {
      val work = fixture(scenario)
      val ready = new CountDownLatch(writers)
      val start = new CountDownLatch(1)
      val done = new CountDownLatch(writers)
      val errors = new AtomicReference[Throwable]()
      val completed = new Array[Long](writers)
      @volatile var until = 0L
      val threads = Vector.tabulate(writers) { writer => new Thread(() => {
        try {
          ready.countDown()
          require(start.await(10, TimeUnit.SECONDS))
          var count = 0L
          while (System.nanoTime() < until) {
            // One clock sample per batch, not per tiny admission operation.
            var index = 0
            while (index < 128) { work.run(); index += 1 }
            count += 128L
          }
          completed(writer) = count
        } catch { case error: Throwable => errors.compareAndSet(null, error); () }
        finally done.countDown()
      }, s"owner-writer-$writer") }
      threads.foreach(_.start())
      require(ready.await(10, TimeUnit.SECONDS))
      val beforeAllocation = allocated
      val before = System.nanoTime()
      until = before + duration.seconds.toNanos
      start.countDown()
      require(done.await(duration + 30L, TimeUnit.SECONDS), "Benchmark did not drain")
      threads.foreach(_.join())
      val elapsed = System.nanoTime() - before
      val bytes = allocated - beforeAllocation
      Option(errors.get()).foreach(error => throw error)
      val operations = completed.sum
      require(operations > 0)
      work.close()
      if (measured) {
        val wall = elapsed / 1e9
        println(s"""{"label":"$label","scenario":"$scenario","writers":$writers,"warmupSeconds":$warm,"seconds":$duration,"completed":$operations,"elapsedSeconds":$wall,"operationsPerSecond":${operations / wall},"allocatedBytes":$bytes,"bytesPerOperation":${bytes.toDouble / operations},"allocatedBytesPerSecond":${bytes / wall},"java":"${System.getProperty("java.version")}","processors":${Runtime.getRuntime.availableProcessors()},"maxHeapBytes":${Runtime.getRuntime.maxMemory()},"correct":true}""")
      }
    }
    round(warm, measured = false)
    round(seconds, measured = true)
  }
}
