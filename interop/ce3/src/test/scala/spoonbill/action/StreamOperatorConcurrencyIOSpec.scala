package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Hub, Reporter, Scheduler, Stream}

/** Native consumers settle on the runtime's worker threads, not one caller. */
class StreamOperatorConcurrencyIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO]            = new IO3Effect(global)
  private def run[A](value: IO[A]): A = Await.result(value.timeout(30.seconds).unsafeToFuture(), 31.seconds)
  private val total                   = 5000

  /** Fails a second read that overlaps the one in flight. */
  private final class Counting(last: Int => IO[Option[Int]] = _ => IO.none) extends Stream[IO, Int] {
    private val next    = new AtomicInteger()
    private val reading = new AtomicBoolean()
    val cancellations   = new AtomicInteger()
    def pull(): IO[Option[Int]] =
      IO(require(reading.compareAndSet(false, true), "Concurrent upstream reads"))
        .flatMap(_ => IO.cede)
        .flatMap { _ =>
          IO {
            val value = next.getAndIncrement()
            reading.set(false)
            value
          }
        }
        .flatMap(value => if (value < total) IO.pure(Some(value)) else last(value))
    def cancel(): IO[Unit] = IO(cancellations.incrementAndGet()).void
  }
  private def counting(): Stream[IO, Int] = new Counting
  private def collect[B](streams: Vector[Stream[IO, B]]): Vector[Vector[B]] = {
    def started(remaining: List[Stream[IO, B]]): IO[List[IO[Vector[B]]]] = remaining match {
      case Nil => IO.pure(Nil)
      case stream :: rest =>
        stream.fold(Vector.empty[B])(_ :+ _).start.flatMap(fiber => started(rest).map(fiber.joinWithNever :: _))
    }
    def joined(remaining: List[IO[Vector[B]]]): IO[List[Vector[B]]] = remaining match {
      case Nil          => IO.pure(Nil)
      case join :: rest => join.flatMap(values => joined(rest).map(values :: _))
    }
    run(started(streams.toList).flatMap(joined)).toVector
  }

  "Concurrent native hub subscribers" should "each receive every value once and in order" in {
    val hub = Hub(counting(), 4)
    collect(Vector.fill(3)(hub.newStreamUnsafe())).foreach(_ shouldBe (0 until total).toVector)
  }

  "Concurrent native rack consumers" should "each receive their own values once and in order" in {
    val received = collect(counting().sort(3, Int.MaxValue)(_ % 3).toVector)
    for (rack <- 0 until 3) received(rack) shouldBe (0 until total).filter(_ % 3 == rack).toVector
  }

  "A native buffered consumer" should "receive every value once and in order" in {
    given ExecutionContext = global.compute
    given Reporter         = Reporter.PrintReporter
    given Scheduler[IO]    = Scheduler.schedulerF[IO]
    collect(Vector(counting().buffer(Duration.Zero, 8))).head.flatten shouldBe (0 until total).toVector
  }

  "A failing native source" should "fail every concurrent subscriber after an ordered prefix" in {
    val error   = new IllegalStateException("upstream failed")
    val hub     = Hub(new Counting(_ => IO.raiseError(error)), 4)
    val streams = Vector.fill(3)(hub.newStreamUnsafe())
    def prefix(stream: Stream[IO, Int], seen: Vector[Int]): IO[(Vector[Int], Throwable)] =
      stream.pull().attempt.flatMap {
        case Right(Some(value)) => prefix(stream, seen :+ value)
        case Right(None)        => IO.raiseError(new AssertionError("A failed hub ended instead"))
        case Left(failure)      => IO.pure((seen, failure))
      }
    def started(remaining: List[Stream[IO, Int]]): IO[List[IO[(Vector[Int], Throwable)]]] = remaining match {
      case Nil => IO.pure(Nil)
      case stream :: rest =>
        prefix(stream, Vector.empty).start.flatMap(fiber => started(rest).map(fiber.joinWithNever :: _))
    }
    def joined[B](remaining: List[IO[B]]): IO[List[B]] = remaining match {
      case Nil          => IO.pure(Nil)
      case join :: rest => join.flatMap(value => joined(rest).map(value :: _))
    }
    for ((seen, failure) <- run(started(streams.toList).flatMap(joined))) {
      failure should be theSameInstanceAs error
      // Failure is prompt: a subscriber's queued tail is discarded.
      seen shouldBe (0 until seen.size).toVector
    }
  }

  "A native rack canceled under load" should "end every rack and start upstream cleanup once" in {
    val source = new Counting(_ => IO.never)
    val racks  = source.sort(3, Int.MaxValue)(_ % 3)
    def count(stream: Stream[IO, Int], seen: Int): IO[Int] = stream.pull().flatMap {
      case Some(_) => count(stream, seen + 1)
      case None    => IO.pure(seen)
    }
    val counted = run(for {
      first  <- count(racks(0), 0).start
      second <- count(racks(1), 0).start
      _      <- racks(2).pull()
      _      <- racks(2).cancel()
      a      <- first.joinWithNever
      b      <- second.joinWithNever
      c      <- racks(2).pull()
    } yield (a, b, c))
    counted._3 shouldBe None
    counted._1 should be <= total
    counted._2 should be <= total
    // Cancellation does not join its cleanup child; wait for the child's entry.
    val deadline = System.nanoTime() + 10.seconds.toNanos
    while (source.cancellations.get() == 0 && System.nanoTime() < deadline) Thread.sleep(1)
    source.cancellations.get() shouldBe 1
  }
}
