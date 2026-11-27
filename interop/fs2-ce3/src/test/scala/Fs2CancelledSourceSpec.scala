import _root_.cats.effect.{Deferred, IO}
import _root_.cats.effect.unsafe.IORuntime
import _root_.fs2.{Stream => Fs2Stream}
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.fs2.*

/**
 * A source that was cancelled did not run to its end. The consumer of the
 * converted stream gets the emitted values in order. Then every pull fails with
 * a `CancellationException`, so a stream that was cut off does not look
 * complete.
 *
 * The pump is cancelled from outside here, by an effect instance that keeps its
 * handle. An effect of the stream that cancels itself has the same result only
 * with fs2 3.2.14 or later: an older fs2 never ends such a stream, and this
 * build has an older one.
 */
class Fs2CancelledSourceSpec extends AnyFlatSpec with Matchers {
  private val runtime            = IORuntime.global
  private given ExecutionContext = ExecutionContext.global

  private type Pulled = Either[Throwable, Option[Int]]

  // A pull that is never answered fails the test through this bound.
  private def run[A](value: IO[A]): A =
    Await.result(value.timeout(5.seconds).unsafeToFuture()(runtime), 6.seconds)

  private def shouldTellTheCancellation(pulled: Pulled): Unit =
    pulled should matchPattern { case Left(_: CancellationException) => }

  /**
   * Keeps the handle of the pump, as an instance that supervises its fibers
   * would.
   */
  private def keepingThePump(cancelPump: AtomicReference[IO[Unit]]): Effect[IO] = new IO3Effect(runtime) {
    override def start[A](value: => IO[A])(implicit ec: ExecutionContext): IO[Effect.Fiber[IO, A]] =
      IO.defer(value).startOn(ec).map { fiber =>
        cancelPump.set(fiber.cancel)
        new Effect.Fiber[IO, A] { def join(): IO[A] = fiber.joinWithNever }
      }
  }

  "An FS2 source whose pump is cancelled" should "give its buffered values and then fail every pull with a CancellationException" in {
    val cancelPump   = new AtomicReference(IO.unit)
    given Effect[IO] = keepingThePump(cancelPump)
    val pulls = run(for {
      reached <- Deferred[IO, Unit]
      source   = Fs2Stream(1, 2, 3) ++ Fs2Stream.eval(reached.complete(()) *> IO.never[Int])
      stream  <- source.toSpoonbill(bufferSize = 3)
      // All three values are in the buffer when the source waits.
      _      <- reached.get
      _      <- IO.defer(cancelPump.get())
      first  <- stream.pull().attempt
      second <- stream.pull().attempt
      third  <- stream.pull().attempt
      fourth <- stream.pull().attempt
      fifth  <- stream.pull().attempt
    } yield List(first, second, third, fourth, fifth))
    pulls.take(3) shouldBe List(Right(Some(1)), Right(Some(2)), Right(Some(3)))
    shouldTellTheCancellation(pulls(3))
    // The failure stays: a later pull gets the same one.
    pulls(4) shouldBe pulls(3)
  }

  it should "answer a pull that is under way" in {
    val cancelPump   = new AtomicReference(IO.unit)
    given Effect[IO] = keepingThePump(cancelPump)
    val pulled = run(for {
      reached <- Deferred[IO, Unit]
      stream  <- Fs2Stream.eval(reached.complete(()) *> IO.never[Int]).toSpoonbill()
      waiting <- stream.pull().attempt.start
      _       <- reached.get
      _       <- IO.defer(cancelPump.get())
      result  <- waiting.joinWithNever
    } yield result)
    shouldTellTheCancellation(pulled)
  }

  it should "end for a consumer that cancels afterwards" in {
    val cancelPump   = new AtomicReference(IO.unit)
    given Effect[IO] = keepingThePump(cancelPump)
    val pulls = run(for {
      reached <- Deferred[IO, Unit]
      stream  <- Fs2Stream.eval(reached.complete(()) *> IO.never[Int]).toSpoonbill()
      _       <- reached.get
      _       <- IO.defer(cancelPump.get())
      told    <- stream.pull().attempt
      _       <- stream.cancel()
      after   <- stream.pull().attempt
    } yield List(told, after))
    shouldTellTheCancellation(pulls(0))
    pulls(1) shouldBe Right(None)
  }
}
