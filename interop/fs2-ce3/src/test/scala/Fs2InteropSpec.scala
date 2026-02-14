import _root_.cats.effect.{IO, _}
import _root_.cats.effect.unsafe.IORuntime
import _root_.fs2.{Stream => Fs2Stream}
import spoonbill.cats._
import spoonbill.effect.{Effect => SpoonbillEffect, Queue, Stream => SpoonbillStream}
import spoonbill.fs2._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers

class Fs2InteropSpec extends AsyncFlatSpec with Matchers {

  private implicit val runtime: IORuntime = _root_.cats.effect.unsafe.IORuntime.global

  "SpoonbillStream.toFs2" should "provide fs2.Stream that contain exactly same values as original Spoonbill stream" in {
    val values = List(1, 2, 3, 4, 5)
    SpoonbillStream(values: _*)
      .mat[IO]()
      .flatMap { spoonbillStream =>
        spoonbillStream.toFs2.compile.toList
      }
      .unsafeToFuture()
      .map { result =>
        result shouldEqual values
      }
  }

  it should "provide fs2.Stream which handle values asynchronously" in {
    val queue = Queue[IO, Int]()
    val io =
      for {
        fiber <- SpoonbillEffect[IO].start {
                   queue.stream.toFs2.compile.toList
                 }
        _      <- queue.offer(1)
        _      <- queue.offer(2)
        _      <- queue.offer(3)
        _      <- queue.offer(4)
        _      <- queue.offer(5)
        _      <- queue.stop()
        result <- fiber.join()
      } yield {
        result shouldEqual List(1, 2, 3, 4, 5)
      }
    io.unsafeToFuture()
  }

  "Fs2Stream.toSpoonbillStream" should "provide spoonbill.effect.Stream that contain exactly same values as original fs2.Stream" in {
    val values = Vector(1, 2, 3, 4, 5)
    Fs2Stream[IO, Int](values: _*)
      .toSpoonbill()
      .flatMap { spoonbillStream =>
        spoonbillStream
          .fold(Vector.empty[Int])((acc, value) => acc :+ value)
          .map(result => result shouldEqual values)
      }
      .unsafeToFuture()
  }
}
