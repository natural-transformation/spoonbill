package spoonbill.zio.streams

import _root_.zio._
import _root_.zio.stream.ZStream
import java.util.concurrent.{ConcurrentLinkedQueue, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import spoonbill.effect.{Effect => SpoonbillEffect, Reporter, Stream => SpoonbillStream}
import spoonbill.zio._

/**
 * `sort` and `buffer` read their source in a fiber of their own, and they
 * cancel it from another one. The read that waits in the source is interrupted,
 * and what the source owns is released.
 */
class ZioStreamOperatorCancelSpec extends AnyFlatSpec with Matchers {
  private val runtime                                     = Runtime.default
  private implicit val effect: SpoonbillEffect[Task]      = taskEffectInstance[Any](runtime)
  private implicit val executionContext: ExecutionContext = ExecutionContext.global
  private implicit val reporter: Reporter                 = Reporter.PrintReporter

  // A step that is never answered fails the test through this bound.
  private def run[A](io: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(io.timeoutFail(new RuntimeException("No answer in 5 seconds"))(5.seconds))
        .getOrThrowFiberFailure()
    }

  /** A converted stream that owns a resource and waits in every read. */
  private final case class Waiting(
    stream: SpoonbillStream[Task, Seq[Int]],
    started: Queue[Unit],
    released: Promise[Nothing, Unit],
    log: ConcurrentLinkedQueue[String]
  ) {
    def events: List[String] = log.asScala.toList
  }

  private def waiting: Task[Waiting] = {
    val log                           = new ConcurrentLinkedQueue[String]()
    def note(text: String): UIO[Unit] = ZIO.succeed { log.add(text); () }
    for {
      queue    <- Queue.unbounded[Int]
      started  <- Queue.unbounded[Unit]
      released <- Promise.make[Nothing, Unit]
      // A take that is interrupted says so, and it takes a moment to unwind.
      take = (started.offer(()) *> queue.take).onInterrupt(ZIO.sleep(50.millis) *> note("take interrupted"))
      source = ZStream
                 .acquireReleaseWith(note("acquire"))(_ => note("release") *> released.succeed(()))
                 .flatMap(_ => ZStream.repeatZIO(take)): ZStream[Any, Throwable, Int]
      stream <- source.toSpoonbill
    } yield Waiting(stream, started, released, log)
  }

  "A rack of a sorted stream" should "interrupt the read that waits in its source when it is cancelled" in {
    val (pulled, events) = run(for {
      source <- waiting
      racks   = source.stream.sort(2)(_.head % 2)
      read   <- racks.head.pull().fork
      _      <- source.started.take
      _      <- racks.head.cancel()
      _      <- source.released.await
      pulled <- read.join
    } yield (pulled, source.events))
    pulled shouldBe None
    events shouldBe List("acquire", "take interrupted", "release")
  }

  "A buffered stream" should "interrupt the read that waits in its source when it is cancelled" in {
    val (pulled, events) = run(for {
      source  <- waiting
      buffered = source.stream.buffer(FiniteDuration(10, TimeUnit.MILLISECONDS), 8)
      read    <- buffered.pull().fork
      _       <- source.started.take
      _       <- buffered.cancel()
      _       <- source.released.await
      pulled  <- read.join
    } yield (pulled, source.events))
    pulled shouldBe None
    events shouldBe List("acquire", "take interrupted", "release")
  }
}
