package spoonbill.effect

import java.lang.management.ManagementFactory
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*

/** Same public Queue operations against each checkout, in fresh fixed-heap JVMs. */
object QueueLifecycleBenchmark {
  private given Effect[Future] = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 10.seconds)

  def main(args: Array[String]): Unit = {
    val Array(label, scenario, secondsArg) = args
    require(label.matches("[a-z0-9-]+"))
    val seconds = secondsArg.toInt
    require(seconds > 0)
    val memory = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
    require(memory.isThreadAllocatedMemorySupported)
    memory.setThreadAllocatedMemoryEnabled(true)

    def round(measured: Boolean): Unit = {
      val queue = Queue[Future, Long](if (scenario == "contended") 1024 else 64)
      val ready = new CountDownLatch(1)
      val failure = new AtomicReference[Throwable]()
      val writers = if (scenario == "contended") 4 else 0
      val counts = Array.fill[Long](math.max(writers, 1))(0L)
      @volatile var deadline = 0L
      @volatile var consumed = 0L
      val producers = (0 until writers).map { writer =>
        new Thread(() => {
          try {
            require(ready.await(10, TimeUnit.SECONDS))
            while (System.nanoTime() < deadline) {
              val sequence = counts(writer) + 1L
              await(queue.enqueue((writer.toLong << 48) | sequence))
              counts(writer) = sequence
            }
          } catch { case error: Throwable => failure.compareAndSet(null, error); () }
        })
      }
      val reader = new Thread(() => {
        val sequences = Array.fill[Long](writers)(0L)
        try {
          require(ready.await(10, TimeUnit.SECONDS))
          var ended = false
          while (!ended) await(queue.stream.pull()) match {
            case Some(value) =>
              val writer = (value >>> 48).toInt
              sequences(writer) += 1L
              require((value & 0xffffffffffffL) == sequences(writer), "Lost or reordered queue value")
              consumed += 1L
            case None => ended = true
          }
        } catch { case error: Throwable => failure.compareAndSet(null, error); () }
      })
      if (writers > 0) { producers.foreach(_.start()); reader.start() }
      val beforeBytes = memory.getTotalThreadAllocatedBytes
      require(beforeBytes >= 0)
      val before = System.nanoTime()
      deadline = before + seconds.seconds.toNanos
      ready.countDown()
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
          producers.foreach(_.join((seconds + 15L) * 1000L))
          require(!producers.exists(_.isAlive), "Producer did not finish")
          await(queue.stop())
          reader.join(15000L)
          require(!reader.isAlive, "Consumer did not drain")
          require(consumed == counts.sum, "Missing delivered values")
        case _ => throw new IllegalArgumentException("Unknown scenario")
      }
      val elapsed = (System.nanoTime() - before) / 1e9
      val bytes = memory.getTotalThreadAllocatedBytes - beforeBytes
      await(queue.close())
      Option(failure.get()).foreach(throw _)
      val operations = counts.sum
      require(operations > 0 && bytes >= 0)
      if (measured)
        println(s"""{"label":"$label","scenario":"$scenario","seconds":$seconds,"operations":$operations,"elapsedSeconds":$elapsed,"operationsPerSecond":${operations / elapsed},"allocatedBytes":$bytes,"bytesPerOperation":${bytes.toDouble / operations},"correct":true}""")
    }
    round(measured = false)
    round(measured = true)
  }
}
