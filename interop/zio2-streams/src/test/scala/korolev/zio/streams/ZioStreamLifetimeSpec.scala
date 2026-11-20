package spoonbill.zio.streams

import _root_.zio._
import _root_.zio.stream.ZStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.jdk.CollectionConverters._
import spoonbill.effect.{Effect => SpoonbillEffect}
import spoonbill.zio._

/**
 * What a converted stream owns lives from its first pull to its end, its
 * failure or `cancel`, and it is released once. `cancel` interrupts a pull that
 * is under way and leaves the consumer alone.
 */
class ZioStreamLifetimeSpec extends AnyFlatSpec with Matchers {
  private val runtime                                = Runtime.default
  private implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)
  private val error                                  = new IllegalStateException("source failed")

  // A step that is never answered fails the test through this bound.
  private def run[A](io: Task[A], bound: Duration = 5.seconds): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(io.timeoutFail(new RuntimeException(s"No answer in ${bound.toSeconds} seconds"))(bound))
        .getOrThrowFiberFailure()
    }

  // A case of many rounds takes longer than one step may, when the machine is busy.
  private def runMany[A](io: Task[A]): A = run(io, 60.seconds)

  /** What the source did, in order. */
  private final class Events {
    private val log                   = new ConcurrentLinkedQueue[String]()
    def note(text: String): UIO[Unit] = ZIO.succeed { log.add(text); () }
    def all: List[String]             = log.asScala.toList
  }

  /** Owns a resource and gives the values of `inner`. */
  private def owning[A](events: Events)(inner: ZStream[Any, Throwable, A]): ZStream[Any, Throwable, A] =
    ZStream.acquireReleaseWith(events.note("acquire"))(_ => events.note("release")).flatMap(_ => inner)

  /**
   * One value of `queue` per pull. Every take says that it started. A take that
   * is interrupted says so, and it takes a moment to unwind.
   */
  private def taking(events: Events, queue: Queue[Int], started: Queue[Unit]): ZStream[Any, Throwable, Int] =
    ZStream.repeatZIO {
      (started.offer(()) *> queue.take).onInterrupt(ZIO.sleep(50.millis) *> events.note("take interrupted"))
    }

  "A converted ZStream" should "keep what it owns until it ends, and release it once" in {
    val events = new Events
    val (pulls, held, atEnd) =
      run(owning(events)(ZStream.fromIterable(1 to 2).rechunk(1)).toSpoonbill.flatMap { stream =>
        for {
          first  <- stream.pull()
          held   <- ZIO.succeed(events.all)
          second <- stream.pull()
          third  <- stream.pull()
          atEnd  <- ZIO.succeed(events.all)
          fourth <- stream.pull()
          _      <- stream.cancel()
        } yield (List(first, second, third, fourth), held, atEnd)
      })
    pulls shouldBe List(Some(Chunk(1)), Some(Chunk(2)), None, None)
    held shouldBe List("acquire")
    atEnd shouldBe List("acquire", "release")
    events.all shouldBe List("acquire", "release")
  }

  it should "release what it owns when it fails, once, and keep the failure as it was" in {
    val events = new Events
    val (first, second, atFailure, third, cancelled) =
      run(owning(events)(ZStream(1) ++ ZStream.fail(error)).toSpoonbill.flatMap { stream =>
        for {
          first     <- stream.pull().either
          second    <- stream.pull().exit
          atFailure <- ZIO.succeed(events.all)
          third     <- stream.pull().exit
          _         <- stream.cancel()
          cancelled <- stream.pull().either
        } yield (first, second, atFailure, third, cancelled)
      })
    first shouldBe Right(Some(Chunk(1)))
    List(second, third).map(_.causeOption.flatMap(_.failureOption)) shouldBe List(Some(error), Some(error))
    atFailure shouldBe List("acquire", "release")
    // After `cancel` a pull gets the end, also when the stream had failed.
    cancelled shouldBe Right(None)
    events.all shouldBe List("acquire", "release")
  }

  it should "release what it owns when it dies, once, and pass the defect on" in {
    val events = new Events
    val defect = new RuntimeException("defect")
    val (second, third) = run(owning(events)(ZStream(1) ++ ZStream.die(defect)).toSpoonbill.flatMap { stream =>
      for {
        _      <- stream.pull()
        second <- stream.pull().exit
        third  <- stream.pull().exit
      } yield (second, third)
    })
    List(second, third).map(_.causeOption.flatMap(_.dieOption)) shouldBe List(Some(defect), Some(defect))
    events.all shouldBe List("acquire", "release")
  }

  "cancel" should "release what the stream owns and admit no new pull" in {
    val events = new Events
    val taken  = new AtomicInteger()
    val source = owning(events)(ZStream.repeatZIO(ZIO.succeed(taken.incrementAndGet())))
    val (pulls, atCancel, takes) = run(source.toSpoonbill.flatMap { stream =>
      for {
        first    <- stream.pull()
        _        <- stream.cancel()
        atCancel <- ZIO.succeed(events.all)
        takes    <- ZIO.succeed(taken.get())
        second   <- stream.pull()
        third    <- stream.pull()
      } yield (List(first, second, third), atCancel, takes)
    })
    pulls shouldBe List(Some(Chunk(1)), None, None)
    atCancel shouldBe List("acquire", "release")
    takes shouldBe 1
    taken.get() shouldBe 1
  }

  it should "close the scope once when it is repeated, also at the same time" in {
    val events = new Events
    run(owning(events)(ZStream.fromIterable(1 to 3).rechunk(1)).toSpoonbill.flatMap { stream =>
      for {
        _ <- stream.pull()
        _ <- ZIO.collectAllParDiscard(List.fill(8)(stream.cancel()))
        _ <- stream.cancel()
      } yield ()
    })
    events.all shouldBe List("acquire", "release")
  }

  it should "release nothing and start nothing when no pull was made" in {
    val events = new Events
    val pulled = run(owning(events)(ZStream(1)).toSpoonbill.flatMap(stream => stream.cancel() *> stream.pull()))
    pulled shouldBe None
    events.all shouldBe Nil
  }

  it should "interrupt a pull that waits, give it the end, and release only when it has unwound" in {
    val events = new Events
    val (atCancel, pulled, later, takes) = run(for {
      queue    <- Queue.unbounded[Int]
      started  <- Queue.unbounded[Unit]
      stream   <- owning(events)(taking(events, queue, started)).toSpoonbill
      waiting  <- stream.pull().fork
      _        <- started.take
      _        <- stream.cancel()
      atCancel <- ZIO.succeed(events.all)
      pulled   <- waiting.join
      later    <- stream.pull()
      takes    <- started.size
    } yield (atCancel, pulled, later, takes))
    atCancel shouldBe List("acquire", "take interrupted", "release")
    pulled shouldBe None
    later shouldBe None
    takes shouldBe 0
  }

  it should "call the finalizer of the scope once, whatever ended the stream first" in {
    val closes                                         = new AtomicInteger()
    val ended: ZIO[Any, Option[Throwable], Chunk[Int]] = ZIO.fail(None)
    run(for {
      life  <- Ref.make[Life](Life.Idle)
      stream = ZSpoonbillStream[Any, Int](ended, _ => ZIO.succeed { closes.incrementAndGet(); () }, life)
      _     <- stream.pull()
      _     <- stream.pull()
      _     <- ZIO.collectAllParDiscard(List.fill(8)(stream.cancel()))
    } yield ())
    closes.get() shouldBe 1
  }

  it should "close once and start no take afterwards when it races a pull at any moment" in {
    val rounds = 500
    val outcomes = runMany(ZIO.foreach((1 to rounds).toList) { round =>
      val closes = new AtomicInteger()
      val taken  = new AtomicInteger()
      for {
        queue <- Queue.unbounded[Int]
        _     <- queue.offerAll(1 to 3)
        take = queue.take.map { value =>
                 taken.incrementAndGet(); Chunk(value)
               }: ZIO[Any, Option[Throwable], Chunk[Int]]
        life  <- Ref.make[Life](Life.Idle)
        stream = ZSpoonbillStream[Any, Int](take, _ => ZIO.succeed { closes.incrementAndGet(); () }, life)
        // Pulls until the end. The fourth pull waits, unless the cancel came first.
        reader   <- stream.pull().repeatUntil(_.isEmpty).fork
        _        <- ZIO.yieldNow.repeatN(round % 9)
        _        <- stream.cancel()
        atCancel <- ZIO.succeed(taken.get())
        _        <- reader.join
        _        <- stream.pull()
      } yield (closes.get(), taken.get() - atCancel)
    })
    outcomes.distinct shouldBe List((1, 0))
  }

  "A second pull that overlaps the first" should "wait its turn and get the next value" in {
    val events = new Events
    val pulls = run(for {
      queue   <- Queue.unbounded[Int]
      started <- Queue.unbounded[Unit]
      stream  <- owning(events)(taking(events, queue, started)).toSpoonbill
      first   <- stream.pull().fork
      _       <- started.take
      second  <- stream.pull().fork
      _       <- queue.offerAll(List(1, 2))
      one     <- first.join
      two     <- second.join
      _       <- stream.cancel()
    } yield List(one, two))
    pulls shouldBe List(Some(Chunk(1)), Some(Chunk(2)))
  }

  "A consumer that is interrupted while it waits" should "take its pull with it and leave the stream open" in {
    val events = new Events
    val (atInterrupt, next) = run(for {
      queue       <- Queue.unbounded[Int]
      started     <- Queue.unbounded[Unit]
      stream      <- owning(events)(taking(events, queue, started)).toSpoonbill
      waiting     <- stream.pull().fork
      _           <- started.take
      _           <- waiting.interrupt
      atInterrupt <- ZIO.succeed(events.all)
      _           <- queue.offer(7)
      next        <- stream.pull()
      _           <- stream.cancel()
    } yield (atInterrupt, next))
    atInterrupt shouldBe List("acquire", "take interrupted")
    next shouldBe Some(Chunk(7))
    events.all shouldBe List("acquire", "take interrupted", "release")
  }

  it should "let its pull unwind before another pull is admitted" in {
    val inside = new AtomicInteger()
    val most   = new AtomicInteger()
    val next = run(for {
      queue   <- Queue.unbounded[Int]
      started <- Queue.unbounded[Unit]
      // Counts the pulls that are inside the source. Leaving it takes a moment.
      take = ZIO.acquireReleaseWith(ZIO.succeed(most.accumulateAndGet(inside.incrementAndGet(), math.max(_, _))))(_ =>
               ZIO.sleep(50.millis) *> ZIO.succeed(inside.decrementAndGet())
             )(_ => started.offer(()) *> queue.take.map(Chunk(_))): ZIO[Any, Option[Throwable], Chunk[Int]]
      life    <- Ref.make[Life](Life.Idle)
      stream   = ZSpoonbillStream[Any, Int](take, _ => ZIO.unit, life)
      waiting <- stream.pull().fork
      _       <- started.take
      _       <- waiting.interruptFork
      // The stream is idle again as soon as the consumer has given its pull up.
      _    <- (ZIO.yieldNow *> life.get).repeatUntil(_ == Life.Idle)
      _    <- queue.offer(7)
      next <- stream.pull()
    } yield next)
    next shouldBe Some(Chunk(7))
    most.get() shouldBe 1
  }

  /** Owns a resource whose release dies, and gives the values of `inner`. */
  private def dying[A](defect: Throwable)(inner: ZStream[Any, Throwable, A]): ZStream[Any, Throwable, A] =
    ZStream.acquireReleaseWith(ZIO.unit)(_ => ZIO.die(defect)).flatMap(_ => inner)

  "A finalizer that dies" should "be told to every canceller" in {
    val defect = new RuntimeException("release failed")
    val exits = run(dying(defect)(ZStream.repeatZIO(ZIO.succeed(1))).toSpoonbill.flatMap { stream =>
      for {
        _     <- stream.pull()
        both  <- stream.cancel().exit.zipPar(stream.cancel().exit)
        later <- stream.cancel().exit
      } yield List(both._1, both._2, later)
    })
    exits.map(_.causeOption.flatMap(_.dieOption)) shouldBe List(Some(defect), Some(defect), Some(defect))
  }

  it should "be told to the pull that meets the end, and to a later pull" in {
    val defect = new RuntimeException("release failed")
    val (first, atEnd, later) = run(dying(defect)(ZStream(1)).toSpoonbill.flatMap { stream =>
      for {
        first <- stream.pull()
        atEnd <- stream.pull().exit
        later <- stream.pull().exit
      } yield (first, atEnd, later)
    })
    first shouldBe Some(Chunk(1))
    List(atEnd, later).map(_.causeOption.flatMap(_.dieOption)) shouldBe List(Some(defect), Some(defect))
  }

  it should "leave the failure of the stream in place, for that pull and for a later one" in {
    val defect = new RuntimeException("release failed")
    val exits = run(dying(defect)(ZStream(1) ++ ZStream.fail(error)).toSpoonbill.flatMap { stream =>
      for {
        _      <- stream.pull()
        failed <- stream.pull().exit
        later  <- stream.pull().exit
      } yield List(failed, later)
    })
    exits.map(_.causeOption.toList.flatMap(_.failures)) shouldBe List(List(error), List(error))
    exits.map(_.causeOption.toList.flatMap(_.defects)) shouldBe List(List(defect), List(defect))
  }

  it should "be told to the canceller when it is in the pull that was interrupted" in {
    val defect = new RuntimeException("interruption failed")
    val (cancelled, pulled) = run(for {
      queue     <- Queue.unbounded[Int]
      started   <- Queue.unbounded[Unit]
      take       = (started.offer(()) *> queue.take).onInterrupt(ZIO.die(defect))
      stream    <- (ZStream.repeatZIO(take): ZStream[Any, Throwable, Int]).toSpoonbill
      waiting   <- stream.pull().exit.fork
      _         <- started.take
      cancelled <- stream.cancel().exit
      pulled    <- waiting.join
    } yield (cancelled, pulled))
    cancelled.causeOption.flatMap(_.dieOption) shouldBe Some(defect)
    // The pull that `cancel` interrupted answers as every pull of that stream does now.
    pulled.causeOption.flatMap(_.dieOption) shouldBe Some(defect)
  }

  "A pull" should "run with the consumer's fiber context and bring its changes back" in {
    val seen = run(ZIO.scoped(for {
      local  <- FiberRef.make("made")
      source  = ZStream.repeatZIO(local.get <* local.set("set by the stream")): ZStream[Any, Throwable, String]
      stream <- source.toSpoonbill
      _      <- local.set("set by the consumer")
      pulled <- stream.pull()
      after  <- local.get
      _      <- stream.cancel()
    } yield (pulled, after)))
    seen shouldBe (Some(Chunk("set by the consumer")), "set by the stream")
  }
}
