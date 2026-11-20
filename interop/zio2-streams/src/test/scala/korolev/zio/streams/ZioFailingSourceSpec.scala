package spoonbill.zio.streams

import _root_.zio._
import _root_.zio.stream.ZStream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.effect.{Effect => SpoonbillEffect}
import spoonbill.zio._

/**
 * The consumer of the converted stream gets what a ZIO consumer of the source
 * gets: the emitted chunks in order, then the end or the failure.
 */
class ZioFailingSourceSpec extends AnyFlatSpec with Matchers {
  private val runtime                                = Runtime.default
  private implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)
  private val error                                  = new IllegalStateException("source failed")

  // A pull that is never answered fails the test through this bound.
  private def run[A](io: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(io.timeoutFail(new RuntimeException("No answer in 5 seconds"))(5.seconds))
        .getOrThrowFiberFailure()
    }

  "A failing ZStream" should "give its values and then fail every pull with its error" in {
    val source: ZStream[Any, Throwable, Int] = ZStream(1) ++ ZStream.fail(error)
    val pulls = run(source.toSpoonbill.flatMap { stream =>
      for {
        first  <- stream.pull().either
        second <- stream.pull().either
        third  <- stream.pull().either
      } yield List(first, second, third)
    })
    pulls shouldBe List(Right(Some(Chunk(1))), Left(error), Left(error))
  }

  "A ZStream that dies" should "give its values and then pass the defect on, not an end" in {
    val defect                               = new RuntimeException("defect")
    val source: ZStream[Any, Throwable, Int] = ZStream(1) ++ ZStream.die(defect)
    val pulls = run(source.toSpoonbill.flatMap { stream =>
      for {
        first  <- stream.pull().exit
        second <- stream.pull().exit
        third  <- stream.pull().exit
      } yield List(first, second, third)
    })
    pulls.head shouldBe Exit.succeed(Some(Chunk(1)))
    pulls.tail.map(_.causeOption.flatMap(_.dieOption)) shouldBe List(Some(defect), Some(defect))
  }

  "A ZStream that ends" should "give its values and then the end, also to a later pull" in {
    val source: ZStream[Any, Throwable, Int] = ZStream(1)
    val pulls = run(source.toSpoonbill.flatMap { stream =>
      for {
        first  <- stream.pull()
        second <- stream.pull()
        third  <- stream.pull()
      } yield List(first, second, third)
    })
    pulls shouldBe List(Some(Chunk(1)), None, None)
  }
}
