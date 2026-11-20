package spoonbill.zio.streams

import _root_.zio._
import _root_.zio.stream.ZStream
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.effect.{Effect => SpoonbillEffect}
import spoonbill.zio._

/**
 * What died in a converted stream is not forgotten. A defect that a pull comes
 * back with, such as a finalizer inside the stream that died, is told to that
 * pull and to every later one, also after `cancel`. A `cancel` that takes the
 * stream from that pull is told too.
 *
 * The bridge can tell only what the pull tells it. ZIO drops a defect that is
 * on its way through a handler when the fiber is interrupted there, also in the
 * pull of a ZStream itself. So the racing case uses a pull that dies where it
 * cannot be interrupted.
 */
class ZioStreamDefectSpec extends AnyFlatSpec with Matchers {
  private val runtime                                = Runtime.default
  private implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)

  // A step that is never answered fails the test through this bound.
  private def run[A](io: Task[A], bound: Duration = 5.seconds): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(io.timeoutFail(new RuntimeException(s"No answer in ${bound.toSeconds} seconds"))(bound))
        .getOrThrowFiberFailure()
    }

  // A case of many rounds takes longer than one step may, when the machine is busy.
  private def runMany[A](io: Task[A]): A = run(io, 60.seconds)

  private def died(exit: Exit[Any, Any]): Option[Throwable] = exit.causeOption.flatMap(_.dieOption)

  /**
   * Two parts. The first owns a resource whose release dies. The second pull
   * leaves that part, so the release dies inside that pull and not when the
   * scope of the stream is closed.
   */
  private def dyingPart(defect: Throwable): ZStream[Any, Throwable, Int] =
    ZStream.acquireReleaseWith(ZIO.unit)(_ => ZIO.die(defect)).flatMap(_ => ZStream(1)) ++ ZStream(2)

  "What died in a pull" should "be told to that pull and to every pull after it, also after `cancel`" in {
    val defect = new RuntimeException("release failed")
    val (first, pulls, cancels) = run(dyingPart(defect).toSpoonbill.flatMap { stream =>
      for {
        first  <- stream.pull()
        second <- stream.pull().exit
        cancel <- stream.cancel().exit
        later  <- stream.pull().exit
        again  <- stream.cancel().exit
      } yield (first, List(second, later), List(cancel, again))
    })
    first shouldBe Some(Chunk(1))
    pulls.map(died) shouldBe List(Some(defect), Some(defect))
    // The release died in a pull and that pull told it. The closing went well, so `cancel` has nothing to tell.
    cancels shouldBe List(Exit.unit, Exit.unit)
  }

  it should "be told to the pull and to a later pull, at whatever moment a `cancel` comes" in {
    val defect = new RuntimeException("pull died")
    val rounds = 2000
    val outcomes = runMany(ZIO.foreach((1 to rounds).toList) { round =>
      val pulls = new AtomicInteger()
      val dead  = new AtomicBoolean()
      // The second pull dies where it cannot be interrupted, so `dead` says for every round whether it died.
      val pull: ZIO[Any, Option[Throwable], Chunk[Int]] = ZIO.suspendSucceed {
        if (pulls.incrementAndGet() == 1) ZIO.succeed(Chunk(1))
        else ZIO.uninterruptible(ZIO.succeed(dead.set(true)) *> ZIO.die(defect))
      }
      for {
        life   <- Ref.make[Life](Life.Idle)
        stream  = ZSpoonbillStream[Any, Int](pull, _ => ZIO.unit, life)
        _      <- stream.pull()
        puller <- stream.pull().exit.fork
        _      <- ZIO.yieldNow.repeatN(round % 5)
        cancel <- stream.cancel().exit
        pulled <- puller.join
        later  <- stream.pull().exit
      } yield (dead.get(), List(pulled, cancel, later).map(died))
    })
    // What the pull, the canceller and the later pull are told, in that order.
    val all   = (true, List[Option[Throwable]](Some(defect), Some(defect), Some(defect)))
    val pulls = (true, List[Option[Throwable]](Some(defect), None, Some(defect)))
    val none  = (false, List[Option[Throwable]](None, None, None))
    // A pull that died is told to both pulls, and to the canceller when the `cancel` took the stream from it.
    // A pull that the `cancel` kept from running is told to nobody.
    (outcomes.toSet -- Set(all, pulls, none)) shouldBe Set.empty
    outcomes should contain(all)
  }

  "A `cancel` that takes the stream when the pull has just come back" should "tell what died in that pull" in {
    val defect = new RuntimeException("pull died")
    val back   = new AtomicBoolean()
    val (order, exits) = run(OneThread {
      for {
        entered <- Promise.make[Nothing, Unit]
        gate    <- Promise.make[Nothing, Unit]
        pull = entered.succeed(()) *> gate.await *> ZIO.succeed(back.set(true)) *> ZIO.die(defect): ZIO[
                 Any,
                 Option[Throwable],
                 Chunk[Int]
               ]
        life     <- Ref.make[Life](Life.Idle)
        stream    = ZSpoonbillStream[Any, Int](pull, _ => ZIO.unit, life)
        consumer <- stream.pull().exit.fork
        _        <- entered.await
        // The pull runs to its end now, then this fiber goes on, and the consumer only after it.
        _      <- gate.succeed(())
        _      <- ZIO.yieldNow
        order  <- life.get.map(cell => (back.get(), cell.isInstanceOf[Life.Pulling]))
        cancel <- stream.cancel().exit
        pulled <- consumer.join
        later  <- stream.pull().exit
      } yield (order, List(cancel, pulled, later))
    })
    withClue("The case needs a pull that is back while its consumer has not taken the result: ") {
      order shouldBe (true, true)
    }
    exits.map(died) shouldBe List.fill(3)(Some(defect))
  }
}
