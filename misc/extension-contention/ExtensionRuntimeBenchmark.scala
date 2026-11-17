package spoonbill.internal

import java.lang.management.ManagementFactory
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.{CountDownLatch, Executors, Semaphore, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import jdk.jfr.Recording
import jdk.jfr.consumer.RecordingFile
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import spoonbill.Extension
import spoonbill.effect.{Effect, Reporter}

/** Standalone JVM fixture; compiles against both main and the pilot without changing either runtime. */
object ExtensionRuntimeBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private case class Value(writer: Int, sequence: Int, sent: Long)
  private val bound = new ExtensionRuntime.Bound[Future] {
    def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = operation
  }

  // Upper-bound quantiles: 1 us buckets below 10 ms, 100 us thereafter,
  // capped near 9 seconds. A capped percentile is reported as null, not as a measurement.
  private class Histogram {
    val buckets = new Array[Long](100001)
    var count = 0L
    var max = 0L
    def add(nanos: Long): Unit = {
      val us = nanos / 1000L
      val bin = if (us < 10000L) us else 10000L + (us - 10000L) / 100L
      buckets(math.min(bin, buckets.length - 1L).toInt) += 1L
      count += 1L
      max = math.max(max, nanos)
    }
    def merge(other: Histogram): Unit = {
      buckets.indices.foreach(i => buckets(i) += other.buckets(i))
      count += other.count
      max = math.max(max, other.max)
    }
    def percentile(q: Double): String = {
      val rank = math.ceil(count * q).toLong
      var index = 0
      var sum = 0L
      while (index < buckets.length - 1 && sum < rank) { sum += buckets(index); index += 1 }
      if (count == 0 || sum < rank) "null"
      else {
        val upperUs = if (index <= 10000) index.toLong else 10000L + (index - 10000L) * 100L
        (upperUs.toDouble / 1000.0).toString
      }
    }
    def json: String = s"""{"count":$count,"p50Ms":${percentile(.5)},"p95Ms":${percentile(.95)},"p99Ms":${percentile(.99)},"p999Ms":${percentile(.999)},"maxMs":${max / 1e6},"overflow":${buckets.last}}"""
  }

  def main(args: Array[String]): Unit = {
    val Array(label, viewsArg, writersArg, extensionsArg, windowArg, workArg, warmArg, secondsArg, recordingArg) = args
    require(label.matches("[A-Za-z0-9_-]+"))
    val views = viewsArg.toInt
    val writers = writersArg.toInt
    val extensionCount = extensionsArg.toInt
    val window = windowArg.toInt
    val workNanos = workArg.toLong
    val warmSeconds = warmArg.toInt
    val seconds = secondsArg.toInt
    require(views > 0 && writers >= views && writers % views == 0 && extensionCount > 0 && window > 0)
    require(workNanos >= 0 && warmSeconds > 0 && seconds > 0)
    val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(allocation.isThreadAllocatedMemorySupported, "Thread allocation accounting is required")
    allocation.setThreadAllocatedMemoryEnabled(true)
    def allocated: Long = {
      val bytes = allocation.getTotalThreadAllocatedBytes
      require(bytes >= 0, "JVM-wide allocation counter unavailable")
      bytes
    }
    val workers = math.max(4, views)
    val pool = Executors.newFixedThreadPool(workers)
    given ExecutionContext = ExecutionContext.fromExecutorService(pool)
    Reporter.PrintReporter.level = Reporter.Level.Error
    val reporter = Reporter.PrintReporter

    def round(duration: Int, measured: Boolean): Unit = {
      val ready = new CountDownLatch(views)
      val permits = Vector.fill(views)(new Semaphore(window))
      val delivery = Vector.fill(views)(new Histogram)
      val admission = Vector.fill(writers)(new Histogram)
      val counts = Array.fill(views, extensionCount)(0L)
      val errors = new AtomicReference[Throwable]()
      val runtimes = Vector.tabulate(views) { view =>
        val extensions = List.tabulate(extensionCount) { extension =>
          val last = Array.fill(writers)(0)
          Extension[Future, Value, Unit](s"observer-$extension") { _ =>
            Future.successful(Extension.Handlers[Future, Value, Unit](onState = value => {
              if (value.writer < 0) {
                if (extension == extensionCount - 1) ready.countDown()
              } else {
                if (value.sequence != last(value.writer) + 1)
                  errors.compareAndSet(null, new IllegalStateException("Duplicated, missing or reordered callback"))
                last(value.writer) = value.sequence
                counts(view)(extension) += 1
                if (workNanos > 0L) {
                  val until = System.nanoTime() + workNanos
                  while (System.nanoTime() < until) Thread.onSpinWait()
                }
                if (extension == extensionCount - 1) {
                  delivery(view).add(System.nanoTime() - value.sent)
                  permits(view).release()
                }
              }
              Future.unit
            }))
          }
        }
        val runtime = new ExtensionRuntime[Future, Value, Unit](extensions, reporter, bound, None)
        Await.result(runtime.setup(null, Value(-1, 0, 0L), () => Future.unit), 5.seconds)
        Await.result(runtime.start(), 5.seconds)
        runtime
      }
      require(ready.await(5, TimeUnit.SECONDS), "Attach did not complete")
      val start = new CountDownLatch(1)
      val writersReady = new CountDownLatch(writers)
      val finished = new CountDownLatch(writers)
      val releaseWriters = new CountDownLatch(1)
      val offered = new Array[Long](writers)
      val writerAllocated = new Array[Long](writers)
      @volatile var until = 0L
      val producers = Vector.tabulate(writers) { writer => new Thread(() => {
        val view = writer % views
        try {
          writersReady.countDown()
          require(start.await(5, TimeUnit.SECONDS))
          val thread = Thread.currentThread().threadId()
          val beforeBytes = allocation.getThreadAllocatedBytes(thread)
          require(beforeBytes >= 0L)
          var sequence = 0
          while (System.nanoTime() < until) {
            require(permits(view).tryAcquire(30, TimeUnit.SECONDS), "Delivery stalled")
            if (System.nanoTime() < until) {
              sequence += 1
              val sent = System.nanoTime()
              runtimes(view).state(Value(writer, sequence, sent))
              admission(writer).add(System.nanoTime() - sent)
            } else permits(view).release()
          }
          offered(writer) = sequence
          writerAllocated(writer) = allocation.getThreadAllocatedBytes(thread) - beforeBytes
          require(writerAllocated(writer) >= 0L, "Producer allocation counter unavailable")
        } catch { case error: Throwable => errors.compareAndSet(null, error); () }
        finally {
          finished.countDown()
          // Total allocation accounting can temporarily omit exiting threads.
          // Keep every producer alive until the controller has sampled it.
          releaseWriters.await()
        }
      }, s"writer-$writer") }
      val recording = if (measured) Some(new Recording()) else None
      recording.foreach { value =>
        value.enable("jdk.GCPhasePause").withThreshold(java.time.Duration.ZERO)
        value.start()
      }
      val (elapsed, begun, ended, bytes) = try {
        producers.foreach(_.start())
        require(writersReady.await(5, TimeUnit.SECONDS), "Writers did not start")
        val beforeAllocation = allocated
        val begun = Instant.now()
        val before = System.nanoTime()
        until = before + duration.seconds.toNanos
        start.countDown()
        require(finished.await(duration + 35L, TimeUnit.SECONDS), "Writers stalled")
        permits.foreach(p => require(p.tryAcquire(window, 30, TimeUnit.SECONDS), "Backlog did not drain"))
        require(producers.forall(_.isAlive), "A producer exited before the allocation sample")
        val elapsed = System.nanoTime() - before
        val ended = Instant.now()
        val bytes = allocated - beforeAllocation
        require(bytes >= writerAllocated.sum, "Total allocation omitted producer allocations")
        (elapsed, begun, ended, bytes)
      } finally {
        start.countDown()
        releaseWriters.countDown()
        producers.foreach(_.join())
      }
      Option(errors.get()).foreach(error => throw error)
      val notifications = offered.sum
      require(notifications > 0)
      counts.indices.foreach { view =>
        val expected = offered.indices.filter(_ % views == view).map(offered(_)).sum
        counts(view).foreach(actual => require(actual == expected, s"Callback count $actual != $expected"))
      }
      runtimes.foreach(runtime => Await.result(runtime.destroy(), 10.seconds))
      require(runtimes.forall(_.pending == 0), "Waiting steps retained after teardown")
      if (measured) {
        val file = Path.of(recordingArg)
        recording.foreach { value => value.stop(); value.dump(file); value.close() }
        val pauses = RecordingFile.readAllEvents(file).asScala.filter(event =>
          event.getEventType.getName == "jdk.GCPhasePause" && !event.getStartTime.isBefore(begun) && !event.getStartTime.isAfter(ended)
        ).map(_.getDuration.toNanos).toVector.sorted
        def pause(q: Double): Double = if (pauses.isEmpty) 0.0 else pauses(math.max(0, math.ceil(pauses.size * q).toInt - 1)) / 1e6
        val delivered = new Histogram
        delivery.foreach(delivered.merge)
        val admitted = new Histogram
        admission.foreach(admitted.merge)
        require(delivered.count == notifications)
        val wall = elapsed / 1e9
        println(s"""{"label":"$label","views":$views,"writers":$writers,"extensions":$extensionCount,"windowPerView":$window,"callbackNanos":$workNanos,"workers":$workers,"seconds":$duration,"warmupSeconds":$warmSeconds,"completed":$notifications,"callbacks":${counts.map(_.sum).sum},"elapsedSeconds":$wall,"notificationsPerSecond":${notifications / wall},"allocatedBytes":$bytes,"producerAllocatedBytes":${writerAllocated.sum},"allocationBoundary":"producers-alive-through-sample","bytesPerNotification":${bytes.toDouble / notifications},"allocatedBytesPerSecond":${bytes / wall},"gcPauses":${pauses.size},"gcPauseTotalMs":${pauses.sum / 1e6},"gcPauseP99Ms":${pause(.99)},"gcPauseMaxMs":${pause(1.0)},"delivery":${delivered.json},"admission":${admitted.json},"java":"${System.getProperty("java.version")}","processors":${Runtime.getRuntime.availableProcessors()},"maxHeapBytes":${Runtime.getRuntime.maxMemory()},"correct":true}""")
      }
    }
    try { round(warmSeconds, measured = false); round(seconds, measured = true) }
    finally { pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS) }
  }
}
