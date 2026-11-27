import _root_.cats.effect.{ContextShift, IO, Timer}
import _root_.fs2.{Stream => Fs2Stream}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.*
import spoonbill.effect.Stream
import spoonbill.fs2.*

/**
 * `toSpoonbill` returns an effect. Every run of that effect converts the source
 * anew: it gives a stream of its own, with all values.
 */
class Fs2RepeatedRunSpec extends AnyFlatSpec with Matchers {
  private val values             = Vector(1, 2, 3)
  private given ExecutionContext = ExecutionContext.global
  private given ContextShift[IO] = IO.contextShift(ExecutionContext.global)
  private given Timer[IO]        = IO.timer(ExecutionContext.global)

  private def run[A](value: IO[A]): A =
    Await.result(value.timeout(5.seconds).unsafeToFuture(), 6.seconds)

  private def all(stream: Stream[IO, Int]): IO[Vector[Int]] =
    stream.fold(Vector.empty[Int])(_ :+ _)

  "The effect that toSpoonbill returns" should "give all values again when it runs after an earlier run ended" in {
    val convert = Fs2Stream[IO, Int](values: _*).toSpoonbill()
    val (first, second) = run(for {
      first  <- convert.flatMap(all)
      second <- convert.flatMap(all)
    } yield (first, second))
    first shouldBe values
    second shouldBe values
  }

  it should "give two streams that do not share their values when it runs twice before either is read" in {
    val convert = Fs2Stream[IO, Int](values: _*).toSpoonbill()
    val (first, second) = run(for {
      one    <- convert
      other  <- convert
      first  <- all(one)
      second <- all(other)
    } yield (first, second))
    first shouldBe values
    second shouldBe values
  }
}
