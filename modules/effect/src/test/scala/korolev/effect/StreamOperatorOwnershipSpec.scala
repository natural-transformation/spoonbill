package spoonbill.effect

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import scala.util.Success

class StreamOperatorOwnershipSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future]              = Effect.futureEffect
  private def await[A](value: Future[A]): A = Await.result(value, 4.seconds)

  "Rejected cleanup startup" should "settle consumers and allow a later cancel to start the still-unentered cleanup" in {
    val attempts  = new AtomicInteger()
    val rejection = new java.util.concurrent.RejectedExecutionException("synthetic cleanup startup rejection")
    given Effect[Future] = new Effect.FutureEffect {
      override def start[A](task: => Future[A])(implicit ec: ExecutionContext): Future[Effect.Fiber[Future, A]] =
        if (attempts.incrementAndGet() == 1) Future.failed(rejection) else super.start(task)
    }
    val input   = Promise[Option[Int]]()
    val entered = Promise[Unit]()
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = input.future
      def cancel(): Future[Unit]      = { entered.trySuccess(()); input.trySuccess(None); Future.unit }
    }
    val racks   = source.sort(2)(identity)
    val pending = racks(0).pull()
    await(racks(1).cancel().failed) should be theSameInstanceAs rejection
    await(pending) shouldBe None
    entered.isCompleted shouldBe false
    await(racks(1).cancel())
    await(entered.future)
    attempts.get() shouldBe 2
  }

  for (overflow <- List(false, true)) {
    "Partition terminal publication" should s"dispatch cleanup before a continuation waits for it (overflow: $overflow)" in {
      val first    = Promise[Option[Int]]()
      val entered  = Promise[Unit]()
      val finished = Promise[Unit]()
      val reads    = new AtomicInteger()
      val source = new Stream[Future, Int] {
        def pull(): Future[Option[Int]] = if (reads.incrementAndGet() == 1) first.future else Future.successful(Some(1))
        def cancel(): Future[Unit]      = { entered.trySuccess(()); finished.future }
      }
      val racks   = source.sort(2, 1)(identity)
      val waiting = racks(0).pull()
      val observed = waiting.transform { _ =>
        await(entered.future); Success(())
      }(ExecutionContext.parasitic)
      try {
        if (overflow) first.success(Some(1)) else await(racks(1).cancel())
        await(observed)
        finished.isCompleted shouldBe false
      } finally { finished.trySuccess(()); first.trySuccess(None) }
    }
  }

  "Reentrant partition cancellation" should "start cleanup without joining the notification that invoked it" in {
    val first    = Promise[Option[Int]]()
    val entered  = Promise[Unit]()
    val finished = Promise[Unit]()
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = first.future
      def cancel(): Future[Unit]      = { entered.trySuccess(()); finished.future }
    }
    val racks = source.sort(2)(identity)
    val observed = racks(0)
      .pull()
      .map { _ =>
        await(racks(1).cancel())
        await(entered.future)
      }(ExecutionContext.parasitic)
    try { first.success(Some(0)); await(observed); finished.isCompleted shouldBe false }
    finally finished.trySuccess(())
  }

  "A terminal partition" should "ignore late upstream values before entering user routing" in {
    val first    = Promise[Option[Int]]()
    val finished = Promise[Unit]()
    val routes   = new AtomicInteger()
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = first.future
      def cancel(): Future[Unit]      = finished.future
    }
    val racks = source.sort(2) { value =>
      routes.incrementAndGet(); value
    }
    val waiting = racks(0).pull()
    await(racks(1).cancel())
    await(waiting) shouldBe None
    first.success(Some(1))
    routes.get() shouldBe 0
    finished.success(())
  }

  "A timed batch" should "reserve its whole FIFO suffix and wake the eager producer before notifying the consumer" in {
    given Reporter         = Reporter.PrintReporter
    given ExecutionContext = ExecutionContext.global
    val jobs               = new java.util.concurrent.ConcurrentLinkedQueue[() => Future[Unit]]()
    val nextRead           = Promise[Unit]()
    val reads              = new AtomicInteger()
    val source = new Stream[Future, Int] {
      def pull(): Future[Option[Int]] = {
        val n = reads.incrementAndGet()
        if (n == 3) nextRead.trySuccess(())
        Future.successful(if (n <= 3) Some(n) else None)
      }
      def cancel(): Future[Unit] = Future.unit
    }
    val stream = new StreamBufferRuntime(
      source,
      1.second,
      1,
      job => {
        jobs.add(job)
        new Scheduler.JobHandler[Future, Unit] {
          def unsafeCancel(): Unit   = ()
          def cancel(): Future[Unit] = Future.unit
          def result: Future[Unit]   = Future.never
        }
      }
    )
    val first = stream
      .pull()
      .map { batch =>
        await(nextRead.future); batch
      }(ExecutionContext.parasitic)
    val second = stream.pull()
    await(jobs.remove().apply())
    await(first) shouldBe Some(Seq(1, 2))
    await(jobs.remove().apply())
    await(second) shouldBe Some(Seq(3))
    await(stream.pull()) shouldBe None
  }
}
