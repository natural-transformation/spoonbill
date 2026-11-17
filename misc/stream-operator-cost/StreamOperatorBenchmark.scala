package spoonbill.effect

import java.lang.management.ManagementFactory
import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

/** Public compatibility paths only. Every timed source/delivery runs on the live measuring thread. */
object StreamOperatorBenchmark {
  private val allocation = ManagementFactory.getThreadMXBean.asInstanceOf[com.sun.management.ThreadMXBean]
  @volatile private var escaping: Array[Byte] = null
  private def await[A](value: Future[A]): A = Await.result(value, 5.seconds)

  private class TrackedEffect extends Effect.FutureEffect {
    private val started = new ConcurrentLinkedQueue[Future[Unit]]()
    override def start[A](create: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] = {
      val result = super.start(create)
      result.foreach(fiber => { started.add(fiber.join().map(_ => ())(ExecutionContext.parasitic)); () })(ExecutionContext.parasitic)
      result
    }
    def beginChecks(): Unit = ()
    def endChecks(): Long = 0L
    def awaitStarted(): Unit = {
      var next = started.poll()
      while (next != null) { await(next); next = started.poll() }
    }
  }

  private final class CheckedEffect(owner: Thread) extends TrackedEffect {
    private var checking = false
    private var continuations = 0L
    private def check(): Unit = if (checking) {
      require(Thread.currentThread() eq owner, "Effect continuation escaped the measuring thread")
      continuations += 1L
    }
    override def beginChecks(): Unit = { checking = true }
    override def endChecks(): Long = { checking = false; continuations }
    override def delay[A](value: => A): Future[A] = super.delay { check(); value }
    override def map[A, B](value: Future[A])(f: A => B): Future[B] = super.map(value) { a => check(); f(a) }
    override def flatMap[A, B](value: Future[A])(f: A => Future[B]): Future[B] =
      super.flatMap(value) { a => check(); f(a) }
  }

  private final class Input(owner: Thread)(using Effect[Future]) extends Stream[Future, Long] {
    private var pending = Option.empty[Promise[Option[Long]]]
    private var closed = false
    var reads = 0L
    var publications = 0L
    var cancellations = 0L
    val cancellationFinished = Promise[Unit]()
    def pull(): Future[Option[Long]] = synchronized {
      require(Thread.currentThread() eq owner, "Source read escaped the measuring thread")
      require(pending.isEmpty, "Concurrent upstream reads")
      reads += 1L
      if (closed) Future.successful(None)
      else {
        val next = Promise[Option[Long]]()
        pending = Some(next)
        next.future
      }
    }
    def publish(value: Long): Unit = {
      require(Thread.currentThread() eq owner)
      val target = synchronized {
        require(!closed, "Publication after close")
        val target = pending.getOrElse(throw new IllegalStateException("No upstream demand"))
        pending = None
        publications += 1L
        target
      }
      target.success(Some(value))
      ()
    }
    def cancel(): Future[Unit] = {
      val target = synchronized {
        cancellations += 1L
        closed = true
        val target = pending
        pending = None
        target
      }
      target.foreach(_.trySuccess(None))
      cancellationFinished.trySuccess(())
      Future.unit
    }
    def pendingReads: Int = synchronized(pending.size)
  }

  private final class Work(scenario: String, verifyContinuations: Boolean) {
    val owner = Thread.currentThread()
    val effect: TrackedEffect = if (verifyContinuations) new CheckedEffect(owner) else new TrackedEffect
    given Effect[Future] = effect
    given ExecutionContext = ExecutionContext.parasitic
    given Reporter = Reporter.PrintReporter
    given Scheduler[Future] = new Scheduler[Future]
    val source = new Input(owner)
    var rounds = 0L
    var deliveries = 0L
    val perRound = if (scenario == "buffer") 1L else 2L
    private var next = 0L
    private def expect(pull: Future[Option[Long]], value: Long): Future[Unit] = effect.map(pull) { actual =>
      require(Thread.currentThread() eq owner, "Delivery escaped the measuring thread")
      require(actual.contains(value), s"Expected $value, received $actual")
      deliveries += 1L
    }
    private val (runRound, stop): (() => Unit, () => Unit) = scenario match {
      case "hub" =>
        val hub = Hub(source, 128)
        val first = hub.newStreamUnsafe()
        val second = hub.newStreamUnsafe()
        (() => {
          val left = expect(first.pull(), next)
          val right = expect(second.pull(), next)
          source.publish(next)
          await(left); await(right)
          next += 1L
        }, () => {
          val leftEnd = first.pull()
          val rightEnd = second.pull()
          await(source.cancel())
          require(await(leftEnd).isEmpty && await(rightEnd).isEmpty)
          await(first.cancel()); await(second.cancel())
        })
      case "sort" =>
        val racks = source.sort(2)(value => (value & 1L).toInt)
        (() => {
          val even = expect(racks(0).pull(), next)
          source.publish(next)
          await(even)
          next += 1L
          val odd = expect(racks(1).pull(), next)
          source.publish(next)
          await(odd)
          next += 1L
        }, () => await(racks.head.cancel()))
      case "buffer" =>
        val buffered = source.buffer(Duration.Zero, 16)
        (() => {
          val pulled = effect.map(buffered.pull()) { actual =>
            require(Thread.currentThread() eq owner, "Batch delivery escaped the measuring thread")
            require(actual.contains(Seq(next)), s"Expected singleton $next, received $actual")
            deliveries += 1L
          }
          source.publish(next)
          await(pulled)
          next += 1L
        }, () => await(buffered.cancel()))
      case other => throw new IllegalArgumentException(other)
    }
    def run(): Unit = { runRound(); rounds += 1L }
    def close(): Unit = {
      require(deliveries == rounds * perRound)
      require(source.publications == (if (scenario == "hub") rounds else deliveries))
      require(source.reads == source.publications + (if (scenario == "buffer") 1L else 0L))
      stop()
      await(source.cancellationFinished.future)
      // Includes old buffer's eager producer and new operators' nonjoining cleanup child.
      effect.awaitStarted()
      require(source.pendingReads == 0)
      require(source.cancellations == 1L)
    }
  }

  private final case class Result(rounds: Long, deliveries: Long, elapsedNanos: Long, bytes: Long, checkedContinuations: Long)
  private def measure(scenario: String, seconds: Int, fixed: Option[Int] = None): Result = {
    val work = new Work(scenario, fixed.nonEmpty)
    work.effect.beginChecks()
    val beforeBytes = allocation.getCurrentThreadAllocatedBytes
    require(beforeBytes >= 0L)
    val started = System.nanoTime()
    val until = started + seconds.seconds.toNanos
    try {
      fixed match {
        case Some(count) =>
          var index = 0
          while (index < count) { work.run(); index += 1 }
        case None =>
          while (System.nanoTime() < until) {
            var index = 0
            while (index < 128) { work.run(); index += 1 }
          }
      }
      val elapsed = System.nanoTime() - started
      val bytes = allocation.getCurrentThreadAllocatedBytes - beforeBytes
      require(bytes >= 0L && Thread.currentThread().isAlive)
      val checked = work.effect.endChecks()
      if (fixed.nonEmpty) require(checked > 0L)
      Result(work.rounds, work.deliveries, elapsed, bytes, checked)
    } finally { work.effect.endChecks(); work.close() }
  }

  def main(args: Array[String]): Unit = {
    require(allocation.isThreadAllocatedMemorySupported)
    allocation.setThreadAllocatedMemoryEnabled(true)
    Reporter.PrintReporter.level = Reporter.Level.Error
    if (args.toList == List("--allocation-control")) {
      val count = 4096
      val payload = 4096
      val before = allocation.getCurrentThreadAllocatedBytes
      var index = 0
      while (index < count) { escaping = new Array[Byte](payload); index += 1 }
      val bytes = allocation.getCurrentThreadAllocatedBytes - before
      require(bytes >= count.toLong * payload)
      println(s"""{"protocol":"stream-compat-v2","control":true,"minimumBytes":${count.toLong * payload},"allocatedBytes":$bytes,"threadAlive":${Thread.currentThread().isAlive}}""")
    } else if (args.toList == List("--smoke")) {
      for (scenario <- Vector("hub", "sort", "buffer")) {
        val result = measure(scenario, 1, Some(64))
        println(s"""{"protocol":"stream-compat-v2","smoke":true,"scenario":"$scenario","rounds":${result.rounds},"deliveries":${result.deliveries},"checkedContinuations":${result.checkedContinuations},"correct":true}""")
      }
    } else {
      val Array(label, scenario, warmArg, durationArg) = args
      require(label.matches("[A-Za-z0-9_-]+"))
      val warm = warmArg.toInt
      val seconds = durationArg.toInt
      require(warm > 0 && seconds > 0)
      measure(scenario, warm)
      val result = measure(scenario, seconds)
      val elapsed = result.elapsedNanos / 1e9
      println(s"""{"protocol":"stream-compat-v2","label":"$label","scenario":"$scenario","warmupSeconds":$warm,"seconds":$seconds,"rounds":${result.rounds},"deliveries":${result.deliveries},"elapsedSeconds":$elapsed,"roundsPerSecond":${result.rounds / elapsed},"deliveriesPerSecond":${result.deliveries / elapsed},"allocatedBytes":${result.bytes},"bytesPerRound":${result.bytes.toDouble / result.rounds},"bytesPerDelivery":${result.bytes.toDouble / result.deliveries},"allocationCounter":"current-live-thread","threadAlive":${Thread.currentThread().isAlive},"java":"${System.getProperty("java.version")}","processors":${Runtime.getRuntime.availableProcessors()},"maxHeapBytes":${Runtime.getRuntime.maxMemory()},"correct":true}""")
    }
  }
}
