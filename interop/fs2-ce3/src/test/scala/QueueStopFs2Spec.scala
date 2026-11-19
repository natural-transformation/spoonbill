import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Queue}
import spoonbill.fs2.*

class QueueStopFs2Spec extends AnyFlatSpec with Matchers {
  private given Effect[IO]            = new IO3Effect(global)
  private def run[A](value: IO[A]): A = Await.result(value.timeout(5.seconds).unsafeToFuture(), 6.seconds)

  "A stopped queue feeding FS2" should "release all registered producers and let the compiled stream collect only accepted values" in {
    val queue = Queue[IO, Int](1)
    def registered: IO[Unit] = queue.size().flatMap {
      case 4 => IO.unit // One accepted value and three capacity waiters.
      case _ => IO.cede.flatMap(_ => registered)
    }
    // Construct while admission is open; these effects run only after the buffer fills.
    val second = queue.enqueue(2)
    val third  = queue.enqueue(3)
    val fourth = queue.enqueue(4)
    val result = run(for {
      _         <- queue.enqueue(1)
      a         <- second.start
      b         <- third.start
      c         <- fourth.start
      _         <- registered
      _         <- queue.stop()
      _         <- a.joinWithNever
      _         <- b.joinWithNever
      _         <- c.joinWithNever
      values    <- queue.stream.toFs2.compile.toList
    } yield values)
    result shouldBe List(1)
  }

  "A failed queue feeding FS2" should "release all registered producers and fail the compiled stream with that failure" in {
    val queue   = Queue[IO, Int](1)
    val failure = new IllegalStateException("pull failure")
    def registered: IO[Unit] = queue.size().flatMap {
      case 4 => IO.unit // One accepted value and three capacity waiters.
      case _ => IO.cede.flatMap(_ => registered)
    }
    // Construct while the queue is healthy; these effects run only after the buffer fills.
    val second = queue.enqueue(2)
    val third  = queue.enqueue(3)
    val fourth = queue.enqueue(4)
    val (outcome, buffered) = run(for {
      _ <- queue.enqueue(1)
      a <- second.start
      b <- third.start
      c <- fourth.start
      _ <- registered
      _ <- queue.fail(failure)
      _ <- a.joinWithNever
      _ <- b.joinWithNever
      _ <- c.joinWithNever
      // A producer that arrives after the failure does not wait either.
      _        <- queue.enqueue(5)
      outcome  <- queue.stream.toFs2.compile.toList.attempt
      buffered <- queue.dequeueAll()
    } yield (outcome, buffered))
    outcome shouldBe Left(failure)
    buffered shouldBe Seq(1)
  }
}
