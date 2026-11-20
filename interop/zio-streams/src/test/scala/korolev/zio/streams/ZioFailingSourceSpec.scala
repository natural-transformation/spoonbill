package spoonbill.zio.streams

import _root_.zio.{Chunk, Exit, Runtime, Task, UIO, ZEnv}
import _root_.zio.duration._
import _root_.zio.stream.ZStream
import java.util.concurrent.ConcurrentLinkedQueue
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import spoonbill.effect.{Effect => SpoonbillEffect}
import spoonbill.zio._

/**
 * The consumer of the converted stream gets what a ZIO consumer of the source
 * gets: the emitted chunks in order, then the end or the failure.
 */
class ZioFailingSourceSpec extends AnyFlatSpec with Matchers {
  implicit val runtime: Runtime[ZEnv]        = Runtime.default
  implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)
  private val error                          = new IllegalStateException("source failed")

  // A pull that is never answered fails the test through this bound.
  private def run[A](io: Task[A]): A =
    runtime.unsafeRun(io.timeoutFail(new RuntimeException("No answer in 5 seconds"))(5.seconds))

  "A failing ZStream" should "give its values and then fail every pull with its error" in {
    val source: ZStream[Any, Throwable, Int] = ZStream(1) ++ ZStream.fail(error)
    val pulls = run(source.toSpoonbill.use { stream =>
      for {
        first  <- stream.pull().either
        second <- stream.pull().either
        third  <- stream.pull().either
      } yield List(first, second, third)
    })
    pulls shouldBe List(Right(Some(Chunk(1))), Left(error), Left(error))
  }

  it should "keep what it owns until its managed scope ends, and release it once" in {
    val log                           = new ConcurrentLinkedQueue[String]()
    def note(text: String): UIO[Unit] = UIO { log.add(text); () }
    val source: ZStream[Any, Throwable, Int] =
      ZStream.bracket(note("acquire"))(_ => note("release")).flatMap(_ => ZStream(1) ++ ZStream.fail(error))
    val pulls = run(source.toSpoonbill.use { stream =>
      for {
        first  <- stream.pull().either
        _      <- note("first pull answered")
        second <- stream.pull().either
      } yield List(first, second)
    })
    pulls shouldBe List(Right(Some(Chunk(1))), Left(error))
    val events = log.asScala.toList
    events.take(2) shouldBe List("acquire", "first pull answered")
    events.count(_ == "release") shouldBe 1
  }

  "A ZStream that dies" should "give its values and then pass the defect on, not an end" in {
    val defect                               = new RuntimeException("defect")
    val source: ZStream[Any, Throwable, Int] = ZStream(1) ++ ZStream.die(defect)
    val pulls = run(source.toSpoonbill.use { stream =>
      for {
        first  <- stream.pull().run
        second <- stream.pull().run
        third  <- stream.pull().run
      } yield List(first, second, third)
    })
    pulls.head shouldBe Exit.succeed(Some(Chunk(1)))
    pulls.tail.map {
      case Exit.Failure(cause) => cause.dieOption
      case Exit.Success(_)     => None
    } shouldBe List(Some(defect), Some(defect))
  }

  "A ZStream that ends" should "give its values and then the end, also to a later pull" in {
    val source: ZStream[Any, Throwable, Int] = ZStream(1)
    val pulls = run(source.toSpoonbill.use { stream =>
      for {
        first  <- stream.pull()
        second <- stream.pull()
        third  <- stream.pull()
      } yield List(first, second, third)
    })
    pulls shouldBe List(Some(Chunk(1)), None, None)
  }
}
