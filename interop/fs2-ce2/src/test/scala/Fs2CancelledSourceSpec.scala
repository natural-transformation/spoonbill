import _root_.cats.effect.{ContextShift, IO, Timer}
import _root_.cats.effect.concurrent.Deferred
import _root_.fs2.{Stream => Fs2Stream}
import java.util.concurrent.CancellationException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.effect.Effect
import spoonbill.fs2.*

/**
 * A source that was cancelled did not run to its end. The consumer of the
 * converted stream gets the emitted values in order. Then every pull fails with
 * a `CancellationException`, so a stream that was cut off does not look
 * complete.
 *
 * An `IO` of cats-effect 2 cannot cancel itself. So the pump is cancelled from
 * outside here, by an effect instance that keeps its handle.
 */
class Fs2CancelledSourceSpec extends AnyFlatSpec with Matchers {
  private given ExecutionContext = ExecutionContext.global
  private given ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private given Timer[IO]        = IO.timer(ExecutionContext.global)

  private type Pulled = Either[Throwable, Option[Int]]

  // A pull that is never answered fails the test through this bound.
  private def run[A](value: IO[A]): A =
    Await.result(value.timeout(5.seconds).unsafeToFuture(), 6.seconds)

  private def shouldTellTheCancellation(pulled: Pulled): Unit =
    pulled should matchPattern { case Left(_: CancellationException) => }

  "An FS2 source whose pump is cancelled" should "give its buffered values and then fail every pull with a CancellationException" in {
    val effect       = new PumpObservingEffect
    given Effect[IO] = effect
    val pulls = run(for {
      reached <- Deferred[IO, Unit]
      source   = Fs2Stream(1, 2, 3) ++ Fs2Stream.eval(reached.complete(()) *> IO.never.map(_ => 0))
      stream  <- source.toSpoonbill(bufferSize = 3)
      // All three values are in the buffer when the source waits.
      _      <- reached.get
      _      <- effect.cancelPump
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

  it should "answer a pull that waits" in {
    val effect       = new PumpObservingEffect
    given Effect[IO] = effect
    val pulled = run(for {
      reached <- Deferred[IO, Unit]
      stream  <- Fs2Stream.eval(reached.complete(()) *> IO.never.map(_ => 0)).toSpoonbill()
      _       <- reached.get
      // The first promise is the cancel signal of the queue.
      _       <- effect.awaitRegistered(1)
      waiting <- stream.pull().attempt.start
      // The second promise is the pull. So the queue holds it when the pump is
      // cancelled, whatever the scheduler does.
      _      <- effect.awaitRegistered(2)
      _      <- effect.cancelPump
      result <- waiting.join
    } yield result)
    shouldTellTheCancellation(pulled)
  }

  it should "end for a consumer that cancels afterwards" in {
    val effect       = new PumpObservingEffect
    given Effect[IO] = effect
    val pulls = run(for {
      reached <- Deferred[IO, Unit]
      stream  <- Fs2Stream.eval(reached.complete(()) *> IO.never.map(_ => 0)).toSpoonbill()
      _       <- reached.get
      _       <- effect.cancelPump
      told    <- stream.pull().attempt
      _       <- stream.cancel()
      after   <- stream.pull().attempt
    } yield List(told, after))
    shouldTellTheCancellation(pulls(0))
    pulls(1) shouldBe Right(None)
  }
}
