package spoonbill.zio.streams

import _root_.zio._
import spoonbill.effect.{Effect => SpoonbillEffect, Queue, Stream => SpoonbillStream}
import spoonbill.zio._
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import zio.{Runtime, Task}
import zio.stream.{ZSink, ZStream}

class ZIOStreamsInteropTest extends AsyncFlatSpec with Matchers {

  private val runtime                              = Runtime.default
  private implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)

  "SpoonbillStream.toZStream" should "provide zio.Stream that contain exactly same values as original Spoonbill stream" in {

    val values = List(1, 2, 3, 4, 5)

    val io = SpoonbillStream(values: _*)
      .mat[Task]()
      .flatMap { (spoonbillStream: SpoonbillStream[Task, Int]) =>
        spoonbillStream.toZStream
          .run(ZSink.foldLeft(List.empty[Int]) { case (acc, v) => acc :+ v })
      }

    zio.Unsafe.unsafe { implicit u =>
      runtime.unsafe.runToFuture(io).map { result =>
        result shouldEqual values
      }
    }
  }

  it should "provide zio.Stream which handle values asynchronously" in {
    val queue                            = Queue[Task, Int]()
    val stream: SpoonbillStream[Task, Int] = queue.stream
    val io =
      for {
        fiber  <- stream.toZStream.run(ZSink.foldLeft(List.empty[Int]) { case (acc, v) => acc :+ v }).fork
        _      <- queue.offer(1)
        _      <- queue.offer(2)
        _      <- queue.offer(3)
        _      <- queue.offer(4)
        _      <- queue.offer(5)
        _      <- queue.stop()
        result <- fiber.join
      } yield {
        result shouldEqual List(1, 2, 3, 4, 5)
      }
    zio.Unsafe.unsafe { implicit u =>
      runtime.unsafe.runToFuture(io)
    }
  }

  "ZStream.toSpoonbillStream" should "provide spoonbill.effect.Stream that contain exactly same values as original zio.Stream" in {

    val v1     = Vector(1, 2, 3, 4, 5)
    val v2     = Vector(5, 4, 3, 2, 1)
    val values = v1 ++ v2
    val io =
      ZStream
        .fromIterable(v1)
        .concat(ZStream.fromIterable(v2)) // concat need for multiple chunks test
        .toSpoonbill
        .flatMap { spoonbillStream =>
          spoonbillStream.unchunk
            .fold(Vector.empty[Int])((acc, value) => acc :+ value)
            .map(result => result shouldEqual values)
        }
    zio.Unsafe.unsafe { implicit u =>
      runtime.unsafe.runToFuture(io)
    }
  }

}
