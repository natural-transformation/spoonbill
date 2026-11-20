package spoonbill.zio.streams

import _root_.zio._
import _root_.zio.stream.ZStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import spoonbill.effect.{Effect => SpoonbillEffect}
import spoonbill.zio._

/**
 * A consumer that is interrupted while it waits takes its pull with it. What
 * died while that pull unwound is in the exit of the consumer, as with a pull
 * that the consumer runs itself. The stream stays open, unless the pull had
 * just come back with the end of the stream: then the scope is closed.
 */
class ZioStreamInterruptedSpec extends AnyFlatSpec with Matchers {
  private val runtime                                = Runtime.default
  private implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)
  private val error                                  = new IllegalStateException("source failed")

  // A step that is never answered fails the test through this bound.
  private def run[A](io: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(io.timeoutFail(new RuntimeException("No answer in 5 seconds"))(5.seconds))
        .getOrThrowFiberFailure()
    }

  "A consumer that is interrupted while it waits" should "get what died while its pull unwound, as with a direct pull" in {
    val defect = new RuntimeException("interruption failed")
    def scenario(bridged: Boolean): Task[(Option[Throwable], Option[Chunk[Int]])] = for {
      queue   <- Queue.unbounded[Int]
      started <- Queue.unbounded[Unit]
      take     = (started.offer(()) *> queue.take).onInterrupt(ZIO.die(defect))
      source   = ZStream.repeatZIO(take): ZStream[Any, Throwable, Int]
      // `one` is a pull: through the bridge, or of the stream itself.
      steps = (one: Task[Option[Chunk[Int]]]) =>
                for {
                  waiting <- one.fork
                  _       <- started.take
                  exit    <- waiting.interrupt
                  _       <- queue.offer(7)
                  next    <- one
                } yield (exit.causeOption.flatMap(_.dieOption), next)
      seen <- if (bridged) source.toSpoonbill.flatMap { stream =>
                steps(stream.pull().map(_.map(Chunk.fromIterable))).ensuring(stream.cancel().ignore)
              }
              else ZIO.scoped(source.toPull.flatMap(pull => steps(pull.unsome)))
    } yield seen
    val direct = run(scenario(bridged = false))
    // The stream is open after it: the next pull takes the next value.
    direct shouldBe (Some(defect), Some(Chunk(7)))
    run(scenario(bridged = true)) shouldBe direct
  }

  it should "leave the stream open when its pull could not be interrupted, as a direct pull does" in {
    def scenario(bridged: Boolean): Task[(Boolean, List[String], Int, Option[Throwable], Int)] = {
      val events                        = new ConcurrentLinkedQueue[String]()
      def note(text: String): UIO[Unit] = ZIO.succeed { events.add(text); () }
      val runs                          = new AtomicInteger()
      OneThread {
        for {
          entered <- Promise.make[Nothing, Unit]
          finish  <- Promise.make[Nothing, Unit]
          // The last effect of the stream fails where it cannot be interrupted.
          last = ZStream.fromZIO(
                   ZIO.uninterruptible(
                     ZIO.succeed(runs.incrementAndGet()) *> entered.succeed(()) *> finish.await *> ZIO.fail(error)
                   )
                 )
          source = ZStream.unwrapScoped(
                     ZIO.acquireRelease(note("acquire"))(_ => note("release")).as(ZStream(1) ++ last)
                   ): ZStream[Any, Throwable, Int]
          // `one` is a pull: through the bridge, or of the stream itself.
          steps = (one: Task[Any]) =>
                    for {
                      _            <- one
                      waiting      <- one.fork
                      _            <- entered.await
                      interrupting <- waiting.interrupt.fork
                      // The interruption reaches the pull before the effect goes on and fails.
                      _      <- ZIO.yieldNow.repeatN(9)
                      _      <- finish.succeed(())
                      exit   <- interrupting.join
                      midway <- ZIO.succeed((events.asScala.toList, runs.get()))
                      next   <- one.exit
                    } yield (
                      exit.isInterrupted,
                      midway._1,
                      midway._2,
                      next.causeOption.flatMap(_.failureOption),
                      runs.get()
                    )
          seen <- if (bridged)
                    source.toSpoonbill.flatMap(stream => steps(stream.pull()).ensuring(stream.cancel().ignore))
                  else ZIO.scoped(source.toPull.flatMap(pull => steps(pull.unsome)))
        } yield seen
      }
    }
    val direct = run(scenario(bridged = false))
    // ZIO drops the failure of a fiber that is interrupted, and the next pull runs the effect again.
    direct shouldBe (true, List("acquire"), 1, Some(error), 2)
    run(scenario(bridged = true)) shouldBe direct
  }

  "A consumer that was interrupted where it cannot be" should "still pull there, as with a direct pull" in {
    def scenario(bridged: Boolean): Task[(Boolean, List[Option[Chunk[Int]]])] = OneThread {
      for {
        inside <- Promise.make[Nothing, Unit]
        go     <- Promise.make[Nothing, Unit]
        seen   <- Promise.make[Nothing, (Boolean, List[Option[Chunk[Int]]])]
        source  = ZStream(1, 2).rechunk(1): ZStream[Any, Throwable, Int]
        // `two` makes two pulls. The consumer says whether its interruption was pending when it made them.
        body = (two: Task[List[Option[Chunk[Int]]]]) =>
                 (inside.succeed(()) *> go.await *>
                   ZIO.descriptor.map(_.interrupters.nonEmpty).zip(two).flatMap(seen.succeed)).uninterruptible
        consumer <- (if (bridged) source.toSpoonbill.flatMap { stream =>
                       val one = stream.pull().map(_.map(Chunk.fromIterable))
                       body(one.zipWith(one)(List(_, _))).ensuring(stream.cancel().ignore)
                     }
                     else
                       ZIO.scoped(
                         source.toPull.flatMap(pull => body(pull.unsome.zipWith(pull.unsome)(List(_, _))))
                       )).fork
        _        <- inside.await
        stopping <- consumer.interrupt.fork
        // The interruption reaches the consumer before it goes on.
        _     <- ZIO.yieldNow.repeatN(9)
        _     <- go.succeed(())
        _     <- stopping.join
        pulls <- seen.await
      } yield pulls
    }
    val direct = run(scenario(bridged = false))
    direct shouldBe (true, List(Some(Chunk(1)), Some(Chunk(2))))
    run(scenario(bridged = true)) shouldBe direct
  }

  /**
   * A consumer that is interrupted when its pull has just come back with
   * `last`. Gives the exit of the consumer, the closings of the scope up to
   * then, and what the next pull gets.
   */
  private def atReturn(
    last: ZIO[Any, Option[Throwable], Chunk[Int]]
  ): (Exit[Throwable, Any], List[Exit[Any, Any]], Exit[Throwable, Option[Seq[Int]]]) = {
    val closes = new ConcurrentLinkedQueue[Exit[Any, Any]]()
    val back   = new AtomicBoolean()
    val (exit, closed, next) = run(OneThread {
      for {
        entered  <- Promise.make[Nothing, Unit]
        gate     <- Promise.make[Nothing, Unit]
        pull      = entered.succeed(()) *> gate.await *> ZIO.succeed(back.set(true)) *> last
        life     <- Ref.make[Life](Life.Idle)
        stream    = ZSpoonbillStream[Any, Int](pull, exit => ZIO.succeed { closes.add(exit); () }, life)
        consumer <- stream.pull().fork
        _        <- entered.await
        // The pull runs to its end now. The consumer then finds its interruption before the result.
        _      <- gate.succeed(())
        exit   <- consumer.interrupt
        closed <- ZIO.succeed(closes.asScala.toList)
        next   <- stream.pull().exit
      } yield (exit, closed, next)
    })
    withClue("The case needs a pull that is back when its consumer is interrupted: ") {
      (back.get(), exit.isInterrupted) shouldBe (true, true)
    }
    (exit, closed, next)
  }

  "A pull that is back with the end when its consumer is interrupted" should "close the scope all the same" in {
    val (exit, closed, next) = atReturn(ZIO.fail(None))
    exit.causeOption.toList.flatMap(_.defects) shouldBe Nil
    closed shouldBe List(Exit.unit)
    next shouldBe Exit.succeed(None)
  }

  "A pull that is back with a failure when its consumer is interrupted" should "close the scope and keep the failure" in {
    val (exit, closed, next) = atReturn(ZIO.fail(Some(error)))
    exit.causeOption.toList.flatMap(_.defects) shouldBe Nil
    closed.map(_.causeOption.flatMap(_.failureOption)) shouldBe List(Some(error))
    next.causeOption.flatMap(_.failureOption) shouldBe Some(error)
  }

  "A pull that is back with a defect when its consumer is interrupted" should "close the scope and tell the defect" in {
    val defect               = new RuntimeException("pull died")
    val (exit, closed, next) = atReturn(ZIO.die(defect))
    // The consumer is told, and so is the next pull.
    exit.causeOption.flatMap(_.dieOption) shouldBe Some(defect)
    closed.map(_.causeOption.flatMap(_.dieOption)) shouldBe List(Some(defect))
    next.causeOption.flatMap(_.dieOption) shouldBe Some(defect)
  }

  "A pull that is back with a chunk when its consumer is interrupted" should "leave the stream open" in {
    val (exit, closed, next) = atReturn(ZIO.succeed(Chunk(1)))
    exit.causeOption.toList.flatMap(_.defects) shouldBe Nil
    closed shouldBe Nil
    // The chunk went with the consumer, as with a direct pull. The next pull pulls again.
    next shouldBe Exit.succeed(Some(Chunk(1)))
  }
}
