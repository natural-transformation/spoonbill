package spoonbill.effect

import java.lang.management.ManagementFactory
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/** Keep every owned thread alive across allocation snapshots, including warmup workers. */
object QueueLiveAllocationBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 10.seconds)
  private val controlBytes = 65536
  @volatile private var escapedControls = Vector.empty[Array[Array[Byte]]]

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, secondsArg) = args
    require(label.matches("[a-z0-9-]+"))
    val seconds = secondsArg.toInt
    require(seconds > 0)
    val memory = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(memory.isThreadAllocatedMemorySupported)
    memory.setThreadAllocatedMemoryEnabled(true)
    val releaseWorkers = new CountDownLatch(1)
    var ownedThreads = Vector.empty[Thread]

    def round(measured: Boolean): Unit = {
      val queue = Queue[Future, Long](if (scenario == "contended") 1024 else 64)
      val ready = new CountDownLatch(1)
      val failure = new AtomicReference[Throwable]()
      val writers = if (scenario == "contended") 4 else 0
      val workerCount = if (writers > 0) writers + 1 else 0
      val producersFinished = new CountDownLatch(writers)
      val allFinished = new CountDownLatch(workerCount)
      val selfBytes = Array.fill[Long](workerCount)(0L)
      val controls = new Array[Array[Byte]](workerCount + 1)
      escapedControls :+= controls
      val counts = Array.fill[Long](math.max(writers, 1))(0L)
      @volatile var deadline = 0L
      @volatile var consumed = 0L

      def tracked(index: Int, producer: Boolean)(body: => Unit): Runnable = () => {
        var before = -1L
        try {
          require(ready.await(10, TimeUnit.SECONDS))
          before = memory.getCurrentThreadAllocatedBytes
          require(before >= 0)
          controls(index) = new Array[Byte](controlBytes)
          controls(index)(0) = index.toByte
          body
        } catch { case error: Throwable => failure.compareAndSet(null, error); () }
        finally {
          selfBytes(index) = if (before < 0) 0L else memory.getCurrentThreadAllocatedBytes - before
          if (producer) producersFinished.countDown()
          allFinished.countDown()
          // Warmup workers also wait here until the measured round has sampled.
          releaseWorkers.await(seconds.toLong * 2L + 30L, TimeUnit.SECONDS)
        }
      }
      val producers = Vector.tabulate(writers) { writer =>
        new Thread(tracked(writer, producer = true) {
          while (System.nanoTime() < deadline) {
            val sequence = counts(writer) + 1L
            await(queue.enqueue((writer.toLong << 48) | sequence))
            counts(writer) = sequence
          }
        })
      }
      val reader = new Thread(tracked(writers, producer = false) {
        val sequences = Array.fill[Long](writers)(0L)
        var ended = false
        while (!ended) await(queue.stream.pull()) match {
          case Some(value) =>
            val writer = (value >>> 48).toInt
            sequences(writer) += 1L
            require((value & 0xffffffffffffL) == sequences(writer), "Lost or reordered queue value")
            consumed += 1L
          case None => ended = true
        }
      })
      val currentThreads = if (writers > 0) producers :+ reader else Vector.empty[Thread]
      ownedThreads ++= currentThreads
      currentThreads.foreach(_.start())
      val beforeBytes = memory.getTotalThreadAllocatedBytes
      val beforeMain = memory.getCurrentThreadAllocatedBytes
      require(beforeBytes >= 0 && beforeMain >= 0)
      val before = System.nanoTime()
      deadline = before + seconds.seconds.toNanos
      controls(workerCount) = new Array[Byte](controlBytes)
      controls(workerCount)(0) = workerCount.toByte
      ready.countDown()
      try {
        scenario match {
          case "roundtrip" =>
            while (System.nanoTime() < deadline) {
              require(queue.offerUnsafe(counts(0)))
              require(await(queue.stream.pull()).contains(counts(0)))
              counts(0) += 1L
            }
          case "batch" =>
            val expected = (0L until 64L).toVector
            while (System.nanoTime() < deadline) {
              expected.foreach(value => require(queue.offerUnsafe(value)))
              require(await(queue.dequeueAll()).toVector == expected)
              counts(0) += expected.size
            }
          case "contended" =>
            require(producersFinished.await(seconds.toLong + 15L, TimeUnit.SECONDS), "Producer did not finish")
            await(queue.stop())
            require(allFinished.await(15, TimeUnit.SECONDS), "Consumer did not drain")
            require(consumed == counts.sum, "Missing delivered values")
          case _ => throw new IllegalArgumentException("Unknown scenario")
        }
        Option(failure.get()).foreach(throw _)
        val operations = counts.sum
        val alive = ownedThreads.count(_.isAlive)
        require(alive == ownedThreads.size, "Owned thread exited before allocation snapshot")
        controls.indices.foreach(index => require(controls(index).length == controlBytes && controls(index)(0) == index.toByte))
        val elapsed = (System.nanoTime() - before) / 1e9
        val mainBytes = memory.getCurrentThreadAllocatedBytes - beforeMain
        val bytes = memory.getTotalThreadAllocatedBytes - beforeBytes
        require(ownedThreads.forall(_.isAlive), "Owned thread exited during allocation snapshot")
        val workerBytes = selfBytes.toVector
        val selfTotal = mainBytes + workerBytes.sum
        val validCounter = mainBytes >= controlBytes && workerBytes.forall(_ >= controlBytes) && bytes >= selfTotal
        require(operations > 0)
        if (measured)
          println(s"""{"label":"$label","scenario":"$scenario","seconds":$seconds,"operations":$operations,"elapsedSeconds":$elapsed,"operationsPerSecond":${operations / elapsed},"allocatedBytes":$bytes,"bytesPerOperation":${bytes.toDouble / operations},"mainSelfAllocatedBytes":$mainBytes,"workerSelfAllocatedBytes":${workerBytes.mkString("[", ",", "]")},"selfAllocatedBytes":$selfTotal,"threadsAliveAtSnapshot":$alive,"expectedLiveThreads":${ownedThreads.size},"escapingControlBytesPerThread":$controlBytes,"allocationCounterValid":$validCounter,"correct":true}""")
        require(validCounter, "Global allocation counter fell below self-allocation/control lower bound")
      } finally await(queue.close())
    }
    try { round(measured = false); round(measured = true) }
    finally {
      releaseWorkers.countDown()
      ownedThreads.foreach(_.join(15000L))
      require(!ownedThreads.exists(_.isAlive), "Owned benchmark thread did not terminate")
    }
  }
}
