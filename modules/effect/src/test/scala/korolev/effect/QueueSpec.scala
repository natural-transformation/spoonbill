package spoonbill.effect

import org.scalatest.Assertion
import org.scalatest.concurrent.AsyncTimeLimitedTests
import org.scalatest.freespec.AsyncFreeSpec
import org.scalatest.time.{Seconds, Span}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class QueueSpec extends AsyncFreeSpec with AsyncTimeLimitedTests {

  override val timeLimit: Span = Span(10, Seconds)

  implicit override def executionContext: ExecutionContext =
    scala.concurrent.ExecutionContext.Implicits.global

  // Test Queue independently of Stream.foreach/fold: the legacy Future effect
  // runs nested callbacks inline and is not stack-safe for long ready streams.
  private def produce(values: Range)(offer: Int => Future[Unit]): Future[Unit] =
    values.foldLeft(Future.unit)((previous, value) => previous.flatMap(_ => offer(value)))

  private def consume(queue: Queue[Future, Int]): Future[Int] = {
    val stream = queue.stream
    def next(count: Int): Future[Int] = stream.pull().flatMap {
      case Some(_) => next(count + 1)
      case None    => Future.successful(count)
    }
    next(0)
  }

  private def finish(queue: Queue[Future, Int], producers: Seq[Future[Unit]]): Future[Unit] =
    Future.sequence(producers).flatMap(_ => queue.stop()).recoverWith { case NonFatal(error) =>
      queue.fail(error).flatMap(_ => Future.failed(error))
    }

  "Concurrent offer/pull invocations" in {
    val queue     = Queue[Future, Int]()
    val consumed  = consume(queue)
    val producers = Seq(0 until 100, 100 until 200, 200 until 300).map(values => produce(values)(queue.enqueue))

    finish(queue, producers).zip(consumed).map { case (_, count) => assert(count == 300) }
  }

  "A failed producer releases a waiting consumer" in {
    val queue    = Queue[Future, Int]()
    val consumed = consume(queue)
    val failure  = new IllegalStateException("Synthetic producer failure")
    val produced = finish(queue, Seq(Future.failed(failure)))

    produced.failed.zip(consumed.failed).map { case (producerError, consumerError) =>
      assert((producerError eq failure) && (consumerError eq failure))
    }
  }

  "A failed queue releases a waiting producer" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("Synthetic pull failure")
    val first   = queue.enqueue(1)
    val waiting = queue.enqueue(2)
    assert(first.isCompleted && !waiting.isCompleted)

    val failed = queue.fail(failure)
    // Check before awaiting: a regression fails here at once instead of
    // holding the asynchronous suite until its time limit.
    assert(waiting.isCompleted)
    // The producer completes normally, as it does after stop or close. Its
    // value was not accepted: the buffer holds only the first one.
    for {
      _        <- failed
      _        <- waiting
      pulled   <- queue.stream.pull().failed
      buffered <- queue.dequeueAll()
    } yield assert((pulled eq failure) && buffered == Seq(1))
  }

  "A failed queue takes nothing from a producer that waited, also when a drain makes room" in {
    val queue   = Queue[Future, Int](1)
    val failure = new IllegalStateException("Synthetic pull failure")
    val first   = queue.enqueue(1)
    val waiting = queue.enqueue(2)
    assert(first.isCompleted && !waiting.isCompleted)
    // A second capacity waiter. It registers later, so `fail` calls it first.
    // It drains the buffer before the waiting producer runs again.
    val drained = Effect[Future].flatMap(queue.canOffer)(_ => queue.dequeueAll())
    assert(!drained.isCompleted)

    val failed = queue.fail(failure)
    assert(waiting.isCompleted && drained.isCompleted)
    for {
      _      <- failed
      _      <- waiting
      taken  <- drained
      left   <- queue.dequeueAll()
      pulled <- queue.stream.pull().failed
    } yield assert(taken == Seq(1) && left.isEmpty && (pulled eq failure))
  }

  ".canOffer" - {

    "passes the free-capacity hint onward after direct handoff" in {
      val queue = Queue[Future, Int](1)
      val initial = queue.offer(1)
      val older = queue.canOffer
      val newer = queue.canOffer
      assert(!older.isCompleted && !newer.isCompleted)

      val first = queue.stream.pull()
      assert(newer.isCompleted)
      assert(!older.isCompleted)
      val waitingPull = queue.stream.pull()
      assert(!waitingPull.isCompleted)

      val handoff = queue.offer(2)
      // Check before awaiting either future: a regression fails immediately
      // instead of leaving an asynchronous suite waiting forever.
      assert(older.isCompleted)
      assert(waitingPull.isCompleted)
      for {
        initialAccepted <- initial
        firstValue <- first
        handoffAccepted <- handoff
        delivered <- waitingPull
        _ <- older
        _ <- newer
      } yield {
        assert(initialAccepted && handoffAccepted)
        assert(firstValue.contains(1) && delivered.contains(2))
      }
    }

    def checkCanOffer(maxSize: Int): Future[Assertion] = {
      val queue = Queue[Future, Int](maxSize)

      def offer(value: Int): Future[Unit] = queue.offer(value).flatMap {
        case false => queue.canOffer.flatMap(_ => offer(value))
        case true  => Future.unit
      }

      val consumed  = consume(queue)
      val producers = Seq(0 until 1000, 1000 until 2000, 2000 until 3000).map(values => produce(values)(offer))
      finish(queue, producers).zip(consumed).map { case (_, count) =>
        assert(count == 3000)
      }
    }

    "maxSize = 1" in checkCanOffer(1)
    "maxSize = 5" in checkCanOffer(5)
    "maxSize = 10" in checkCanOffer(10)
  }

}
