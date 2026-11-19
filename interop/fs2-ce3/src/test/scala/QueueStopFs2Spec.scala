import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.implicits.global
import java.util.concurrent.atomic.AtomicReference
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

  it should "take nothing from a released producer when another fiber drains before it runs again" in {
    val failure = new IllegalStateException("pull failure")
    val gate    = run(Deferred[IO, Unit])
    val armed   = new AtomicReference(Option(gate))
    // Holds the producer between its wake-up and its next attempt, so the drain
    // below always comes first.
    val holding = new IO3Effect(global) {
      override def promise[A](register: (Either[Throwable, A] => Unit) => Unit): IO[A] =
        super.promise(register).flatMap(value => armed.getAndSet(None).fold(IO.pure(value))(_.get.as(value)))
    }
    val queue = new Queue[IO, Int](1)(using holding)
    def registered: IO[Unit] = queue.size().flatMap {
      case 2 => IO.unit // One accepted value and one capacity waiter.
      case _ => IO.cede.flatMap(_ => registered)
    }
    val (drained, left) = run(for {
      _        <- queue.enqueue(1)
      producer <- queue.enqueue(2).start
      _        <- registered
      _        <- queue.fail(failure)
      drained  <- queue.dequeueAll()
      _        <- gate.complete(())
      _        <- producer.joinWithNever
      left     <- queue.dequeueAll()
    } yield (drained, left))
    drained shouldBe Seq(1)
    left shouldBe Seq.empty
  }
}
