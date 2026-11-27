import _root_.cats.effect.{ContextShift, IO, Timer}
import _root_.cats.effect.concurrent.Deferred
import _root_.fs2.{Stream => Fs2Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.*
import spoonbill.effect.Effect
import spoonbill.fs2.*

/**
 * The consumer of the converted stream gets what an FS2 consumer of the source
 * gets: the emitted values in order, then the error.
 */
class Fs2FailingSourceSpec extends AnyFlatSpec with Matchers {
  private val error              = new IllegalStateException("source failed")
  private given ExecutionContext = ExecutionContext.global
  private given ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private given Timer[IO]        = IO.timer(ExecutionContext.global)

  // A pull that is never answered fails the test through this bound.
  private def run[A](value: IO[A]): A =
    Await.result(value.timeout(5.seconds).unsafeToFuture(), 6.seconds)

  "A failing FS2 source" should "give its values and then fail every pull with its error" in {
    val source = Fs2Stream.emit(1) ++ Fs2Stream.raiseError[IO](error)
    val pulls = run(for {
      stream <- source.toSpoonbill()
      first  <- stream.pull().attempt
      second <- stream.pull().attempt
      third  <- stream.pull().attempt
    } yield List(first, second, third))
    pulls shouldBe List(Right(Some(1)), Left(error), Left(error))
  }

  it should "give the values that are still buffered when it fails" in {
    val pulls = run(for {
      failed <- Deferred[IO, Unit]
      source  = (Fs2Stream(1, 2, 3) ++ Fs2Stream.raiseError[IO](error)).onFinalize(failed.complete(()))
      stream <- source.toSpoonbill(bufferSize = 3)
      // The buffer takes all three values, so the source fails without a
      // consumer. The pump may still be handling that failure at the first
      // pull. The fs2-ce3 spec waits for the pump itself.
      _      <- failed.get
      first  <- stream.pull().attempt
      second <- stream.pull().attempt
      third  <- stream.pull().attempt
      fourth <- stream.pull().attempt
    } yield List(first, second, third, fourth))
    pulls shouldBe List(Right(Some(1)), Right(Some(2)), Right(Some(3)), Left(error))
  }

  it should "answer a pull that waits when it fails" in {
    val effect       = new PumpObservingEffect
    given Effect[IO] = effect
    val pulled = run(for {
      fail   <- Deferred[IO, Unit]
      stream <- Fs2Stream.eval(fail.get.flatMap(_ => IO.raiseError[Int](error))).toSpoonbill()
      // The first promise is the cancel signal of the queue.
      _       <- effect.awaitRegistered(1)
      waiting <- stream.pull().attempt.start
      // The second promise is the pull. So the queue holds it when the source
      // fails, whatever the scheduler does.
      _      <- effect.awaitRegistered(2)
      _      <- fail.complete(())
      result <- waiting.join
    } yield result)
    pulled shouldBe Left(error)
  }

  it should "end for a consumer that cancels after the failure" in {
    val pulls = run(for {
      stream   <- Fs2Stream.eval(IO.raiseError[Int](error)).toSpoonbill()
      failed   <- stream.pull().attempt
      _        <- stream.cancel()
      canceled <- stream.pull().attempt
    } yield List(failed, canceled))
    pulls shouldBe List(Left(error), Right(None))
  }
}
