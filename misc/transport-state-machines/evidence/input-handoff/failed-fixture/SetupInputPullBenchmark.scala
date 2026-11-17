package spoonbill.server

import java.lang.management.ManagementFactory
import java.util.Arrays
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Stream}

/** Isolates the direct coupled-input path; contains no network or scheduler load. */
object SetupInputPullBenchmark {
  private given Effect[Future] = Effect.futureEffect
  @volatile private var published = Array.empty[Stream[Future, Int]]
  @volatile private var sink: Future[Option[Int]] = Future.successful(None)

  def main(args: Array[String]): Unit = {
    val Array(label, sourcesArg, secondsArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val sources = sourcesArg.toInt
    val seconds = secondsArg.toInt
    require(sources > 0 && (sources & (sources - 1)) == 0 && seconds > 0)
    val ready = Future.successful(Some(1))
    val upstream = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = ready
      def cancel(): Future[Unit] = Future.unit
    }
    val inputs = Array.fill(sources) {
      val input = new SetupInput(upstream)
      require(!Await.result(input.couple(), 5.seconds))
      input.stream
    }
    published = inputs // Make the handoffs escape, retaining their real volatile-read cost.
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    allocation.setThreadAllocatedMemoryEnabled(true)
    val thread = Thread.currentThread().getId
    val samples = new Array[Long](1 << 22)
    var index = 0
    def pull(): Unit = {
      val result = inputs(index).pull()
      sink = result
      index = (index + 1) & (sources - 1)
    }
    val warmUntil = System.nanoTime() + seconds.toLong * 1000000000L
    while (System.nanoTime() < warmUntil) pull()
    val allocatedBefore = allocation.getThreadAllocatedBytes(thread)
    val begun = System.nanoTime()
    val until = begun + seconds.toLong * 1000000000L
    var count = 0L
    var sampleCount = 0
    while (System.nanoTime() < until) {
      var batch = 0
      while (batch < 256) {
        if (batch == 0) {
          val before = System.nanoTime()
          pull()
          if (sampleCount < samples.length) samples(sampleCount) = System.nanoTime() - before
          sampleCount += 1
        } else pull()
        batch += 1
      }
      count += 256
    }
    val elapsed = System.nanoTime() - begun
    val allocated = allocation.getThreadAllocatedBytes(thread) - allocatedBefore
    require(sampleCount > 0 && sampleCount <= samples.length && sink.eq(ready))
    Arrays.sort(samples, 0, sampleCount)
    val wall = elapsed / 1e9
    println(s"""{"label":"$label","sources":$sources,"seconds":$seconds,"pulls":$count,"pullsPerSecond":${count / wall},"bytesPerPull":${allocated.toDouble / count},"p99Nanos":${samples(math.ceil(sampleCount * .99).toInt - 1)},"samples":$sampleCount,"correct":true}""")
  }
}
