package spoonbill.effect

import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*

class StreamBufferTerminalSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private given Reporter                    = Reporter.PrintReporter
  private def await[A](value: Future[A]): A = Await.result(value, 3.seconds)
  private class Worker extends ExecutionContext {
    private val tasks                         = new ConcurrentLinkedQueue[Runnable]()
    def execute(task: Runnable): Unit         = { tasks.add(task); () }
    def reportFailure(error: Throwable): Unit = throw error
    def drain(): Unit = {
      var next = tasks.poll()
      while (next != null) { next.run(); next = tasks.poll() }
    }
  }

  "A finite buffered stream" should "drain its accepted elements and then report EOF" in {
    val worker              = new Worker
    given ExecutionContext  = worker
    given Scheduler[Future] = Scheduler.schedulerF[Future]
    val source              = await(Stream(1, 2, 3).mat[Future]())
    val buffered            = source.buffer(Duration.Zero)
    worker.drain()
    await(buffered.pull()) shouldBe Some(Seq(1))
    await(buffered.pull()) shouldBe Some(Seq(2))
    await(buffered.pull()) shouldBe Some(Seq(3))
    val terminal = buffered.pull()
    terminal.isCompleted shouldBe true
    await(terminal) shouldBe None
    await(buffered.pull()) shouldBe None
  }

  it should "drain its accepted prefix before reporting the original producer failure" in {
    val worker              = new Worker
    given ExecutionContext  = worker
    given Scheduler[Future] = Scheduler.schedulerF[Future]
    val error               = new IllegalStateException("producer failed")
    var reads               = 0
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = {
        reads += 1; if (reads == 1) Future.successful(Some(1)) else Future.failed(error)
      }
      def cancel(): Future[Unit] = Future.unit
    }
    val buffered = source.buffer(Duration.Zero)
    worker.drain()
    await(buffered.pull()) shouldBe Some(Seq(1))
    val terminal = buffered.pull()
    terminal.isCompleted shouldBe true
    await(terminal.failed) should be theSameInstanceAs error
    await(buffered.pull().failed) should be theSameInstanceAs error
    reads shouldBe 2
  }

  it should "finish an already delayed batch before exposing producer failure" in {
    val delayReady = Promise[() => Unit]()
    given Effect[Future] = new Effect.FutureEffect {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): Future[A] =
        super.promise(callback =>
          register { result =>
            result match {
              case Right(()) => delayReady.trySuccess(() => callback(result)); ()
              case _         => callback(result)
            }
          }
        )
    }
    val worker              = new Worker
    given ExecutionContext  = worker
    given Scheduler[Future] = Scheduler.schedulerF[Future]
    val error               = new IllegalStateException("producer failed during batch delay")
    val last                = Promise[Option[Int]]()
    var reads               = 0
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = { reads += 1; if (reads <= 2) Future.successful(Some(reads)) else last.future }
      def cancel(): Future[Unit]      = Future.unit
    }
    val buffered = source.buffer(1.millis)
    worker.drain()
    val batch       = buffered.pull()
    val finishDelay = await(delayReady.future)
    try {
      last.failure(error)
      batch.isCompleted shouldBe false
      finishDelay()
      await(batch) shouldBe Some(Seq(1, 2))
      val terminal = buffered.pull()
      terminal.isCompleted shouldBe true
      await(terminal.failed) should be theSameInstanceAs error
    } finally { if (!batch.isCompleted) finishDelay() }
  }

  it should "keep explicit cancellation terminal even after the producer failed" in {
    val worker              = new Worker
    given ExecutionContext  = worker
    given Scheduler[Future] = Scheduler.schedulerF[Future]
    var canceled            = 0
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = Future.failed(new IllegalStateException("producer failed before cancellation"))
      def cancel(): Future[Unit]      = { canceled += 1; Future.unit }
    }
    val buffered = source.buffer(Duration.Zero)
    worker.drain()
    await(buffered.cancel())
    canceled shouldBe 1
    await(buffered.pull()) shouldBe None
  }
}
