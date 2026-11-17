package spoonbill.state

import avocet.Id
import java.lang.management.ManagementFactory
import java.time.Instant
import java.util.UUID
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.ViewFence
import spoonbill.state.javaSerialization.*

/**
 * Public operations, one still-live measuring thread, identical on both
 * revisions.
 */
object PresentationBenchmark {
  private given Effect[Future]                 = Effect.futureEffect
  private val allocation                       = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var allocationSink: AnyRef = null
  @volatile private var fixtureSink: AnyRef    = null
  private case class Work(run: () => Unit, verify: () => Unit, close: () => Unit)
  private case class Measurement(operations: Long, nanos: Long, bytes: Long, p99: Long, samples: Int, control: Long)
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private def fixture(scenario: String): Work = {
    var ticks = 0L
    val time  = Instant.parse("2026-10-11T00:00:00Z")
    val store = new BoundedPresentationStore[Future, Int](
      BoundedPresentationStore.Limits(),
      () => {
        if (scenario == "read-clock") { ticks += 1; time.plusNanos(ticks) }
        else time
      }
    )
    fixtureSink = store // Keep ordinary store synchronization observable to the JIT.
    val qsid = Qsid("benchmark", "view")
    def fence(epoch: Long): ViewFence = ViewFence(
      ViewSessionId.fromUuid(new UUID(0L, 1L)),
      ViewOwnerId.fromUuid(new UUID(0L, epoch)),
      ViewOwnershipEpoch.fromLong(epoch).toOption.get
    )
    await(store.bootstrap(qsid, 0))
    var epoch = 1L
    var value = 0
    var lease = await(store.claim(qsid, fence(epoch)))
    await(lease.publish())
    val run: () => Unit = scenario match {
      case "read" | "read-clock" => () => require(await(lease.manager.read[Int](Id.TopLevel)).contains(value))
      case "write"               => () => { value ^= 1; await(lease.manager.write(Id.TopLevel, value)) }
      case "snapshot"            => () => require(await(lease.manager.snapshot).apply[Int](Id.TopLevel).contains(value))
      case "lease" =>
        () => {
          await(lease.release())
          epoch += 1
          lease = await(store.claim(qsid, fence(epoch)))
          await(lease.publish())
        }
      case other => throw new IllegalArgumentException(other)
    }
    Work(
      run,
      () => {
        require(await(lease.manager.read[Int](Id.TopLevel)).contains(value))
        val resources = await(store.resources)
        require(resources.active == 1 && resources.pending == 0 && resources.nodes == 1)
      },
      () => await(store.close())
    )
  }

  private def measure(work: Work, seconds: Int, sampled: Boolean): Measurement = {
    val samples       = if (sampled) new Array[Long](1 << 22) else Array.emptyLongArray
    val controlBefore = allocation.getCurrentThreadAllocatedBytes
    allocationSink = new Array[Byte](65536)
    val control = allocation.getCurrentThreadAllocatedBytes - controlBefore
    require(control >= 65536L, "Escaping allocation control was not accounted")
    val beforeBytes = allocation.getCurrentThreadAllocatedBytes
    require(beforeBytes >= 0L)
    val before      = System.nanoTime()
    val until       = before + seconds.seconds.toNanos
    var operations  = 0L
    var sampleCount = 0
    while (System.nanoTime() < until) {
      var index = 0
      while (index < 128) {
        if (sampled && (operations & 511L) == 0L) {
          require(sampleCount < samples.length, "Latency sample capacity exceeded")
          val started = System.nanoTime()
          work.run()
          samples(sampleCount) = System.nanoTime() - started
          sampleCount += 1
        } else work.run()
        operations += 1L
        index += 1
      }
    }
    val elapsed = System.nanoTime() - before
    val bytes   = allocation.getCurrentThreadAllocatedBytes - beforeBytes
    require(operations > 0L && bytes >= 0L)
    work.verify()
    work.close()
    val p99 =
      if (!sampled) 0L
      else {
        require(sampleCount > 0)
        java.util.Arrays.sort(samples, 0, sampleCount)
        samples(math.min(sampleCount - 1, math.ceil(sampleCount * .99).toInt - 1))
      }
    Measurement(operations, elapsed, bytes, p99, sampleCount, control)
  }

  def main(args: Array[String]): Unit = {
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    val Array(label, scenario, warmArg, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val warm    = warmArg.toInt
    val seconds = secondsArg.toInt
    require(warm > 0 && seconds > 0)
    val warmup  = measure(fixture(scenario), warm, false)
    val result  = measure(fixture(scenario), seconds, true)
    val elapsed = result.nanos / 1e9
    println(
      s"""{"protocol":"presentation-current-thread-v1","label":"$label","scenario":"$scenario","warmupSeconds":$warm,"seconds":$seconds,"operations":${result.operations},"elapsedSeconds":$elapsed,"operationsPerSecond":${result.operations / elapsed},"allocatedBytes":${result.bytes},"bytesPerOperation":${result.bytes.toDouble / result.operations},"p99Nanos":${result.p99},"samples":${result.samples},"sampleStride":512,"escapingControlBytes":${result.control},"warmupControlBytes":${warmup.control},"java":"${System
        .getProperty("java.version")}","processors":${Runtime.getRuntime
        .availableProcessors()},"maxHeapBytes":${Runtime.getRuntime.maxMemory()},"correct":true}"""
    )
  }
}
