package spoonbill.zio.streams

import _root_.zio._
import _root_.zio.stream.ZStream
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.effect.{Effect => SpoonbillEffect}
import spoonbill.zio._

/**
 * A pull runs in a fiber of the bridge. The stream must not notice that in its
 * `FiberRef` values or in the fibers that it forks. So every case here asks the
 * bridge for the result that the pull of the ZStream itself gives.
 */
class ZioStreamContextSpec extends AnyFlatSpec with Matchers {
  private val runtime                                = Runtime.default
  private implicit val effect: SpoonbillEffect[Task] = taskEffectInstance[Any](runtime)

  // A step that is never answered fails the test through this bound.
  private def run[A](io: Task[A]): A =
    Unsafe.unsafe { implicit unsafe =>
      runtime.unsafe
        .run(io.timeoutFail(new RuntimeException("No answer in 5 seconds"))(5.seconds))
        .getOrThrowFiberFailure()
    }

  /** Two pulls: through the bridge, or with the pull of the stream itself. */
  private def twice[A](source: ZStream[Any, Throwable, A], bridged: Boolean): Task[List[Option[Chunk[A]]]] =
    if (bridged) source.toSpoonbill.flatMap { stream =>
      stream
        .pull()
        .zipWith(stream.pull())((one, two) => List(one.map(Chunk.fromIterable), two.map(Chunk.fromIterable)))
        .ensuring(stream.cancel().ignore)
    }
    else ZIO.scoped(source.toPull.flatMap(pull => pull.unsome.zipWith(pull.unsome)((one, two) => List(one, two))))

  "A fiber that the source forks" should "live on for a later pull" in {
    def scenario(bridged: Boolean): Task[List[Option[Chunk[Int]]]] = for {
      go   <- Promise.make[Nothing, Unit]
      done <- Promise.make[Nothing, Int]
      // The first chunk forks a fiber that waits. The second lets it go on and takes its result.
      source = ZStream.fromZIO((go.await *> done.succeed(7)).fork.as(1)) ++
                 ZStream.fromZIO(go.succeed(()) *> done.await)
      pulls <- twice(source, bridged)
    } yield pulls
    val direct = run(scenario(bridged = false))
    direct shouldBe List(Some(Chunk(1)), Some(Chunk(7)))
    run(scenario(bridged = true)) shouldBe direct
  }

  it should "outlive `cancel` and end with the consumer's fiber, unless it is forked in the scope of the stream" in {
    def scenario(inScope: Boolean): Task[(Boolean, Boolean)] = for {
      started <- Promise.make[Nothing, Unit]
      stopped <- Promise.make[Nothing, Unit]
      gate    <- Promise.make[Nothing, Unit]
      work     = (started.succeed(()) *> ZIO.never).onInterrupt(stopped.succeed(()))
      source = (if (inScope) ZStream.scoped(work.forkScoped).flatMap(child => ZStream.fromZIO(child.join))
                else ZStream.fromZIO(work.fork.flatMap(_.join))): ZStream[Any, Throwable, Int]
      stream <- source.toSpoonbill
      // The consumer lives on after its pull.
      consumer <- (stream.pull() <* gate.await).fork
      _        <- started.await
      _        <- stream.cancel()
      atCancel <- stopped.isDone
      _        <- gate.succeed(())
      _        <- consumer.join
      _        <- stopped.await
      atEnd    <- stopped.isDone
    } yield (atCancel, atEnd)
    // It belongs to the consumer's fiber, as with a direct pull, so `cancel` does not reach it.
    run(scenario(inScope = false)) shouldBe (false, true)
    run(scenario(inScope = true)) shouldBe (true, true)
  }

  "A FiberRef with a rule for forks" should "read in the source as with a direct pull" in {
    def scenario(bridged: Boolean): Task[List[Option[Chunk[Int]]]] = ZIO.scoped(for {
      local <- FiberRef.make(0, fork = _ + 1)
      pulls <- twice(ZStream.repeatZIO(local.get), bridged)
    } yield pulls)
    val direct = run(scenario(bridged = false))
    direct shouldBe List(Some(Chunk(0)), Some(Chunk(0)))
    run(scenario(bridged = true)) shouldBe direct
  }

  "A FiberRef with a rule for joins" should "keep in the consumer what the source set" in {
    def scenario(bridged: Boolean): Task[(List[Option[Chunk[Int]]], Int)] = ZIO.scoped(for {
      local <- FiberRef.make(0, join = (parent: Int, child: Int) => parent + child + 100)
      pulls <- twice(ZStream.repeatZIO(local.updateAndGet(_ + 1)), bridged)
      after <- local.get
    } yield (pulls, after))
    val direct = run(scenario(bridged = false))
    direct shouldBe (List(Some(Chunk(1)), Some(Chunk(2))), 2)
    run(scenario(bridged = true)) shouldBe direct
  }

  "A change of the fiber context before a failure" should "stay in the consumer" in {
    val failure = new IllegalStateException("source failed")
    def scenario(bridged: Boolean): Task[(Option[Throwable], String)] = ZIO.scoped(for {
      local <- FiberRef.make("before")
      source = ZStream.fromZIO(local.set("after") *> ZIO.fail(failure)): ZStream[Any, Throwable, Int]
      exit <- if (bridged) source.toSpoonbill.flatMap(_.pull().exit)
              else source.toPull.flatMap(_.unsome.exit)
      value <- local.get
    } yield (exit.causeOption.flatMap(_.failureOption), value))
    val direct = run(scenario(bridged = false))
    direct shouldBe (Some(failure), "after")
    run(scenario(bridged = true)) shouldBe direct
  }

  "What the consumer sets between two pulls" should "be what the next pull reads" in {
    def scenario(bridged: Boolean): Task[List[Option[Chunk[String]]]] = ZIO.scoped(for {
      local <- FiberRef.make("made")
      source = ZStream.repeatZIO(local.get <* local.set("set by the source")): ZStream[Any, Throwable, String]
      pulls <- if (bridged) source.toSpoonbill.flatMap { stream =>
                 for {
                   one <- stream.pull()
                   _   <- local.set("set by the consumer")
                   two <- stream.pull()
                   _   <- stream.cancel()
                 } yield List(one.map(Chunk.fromIterable), two.map(Chunk.fromIterable))
               }
               else
                 source.toPull.flatMap { pull =>
                   for {
                     one <- pull.unsome
                     _   <- local.set("set by the consumer")
                     two <- pull.unsome
                   } yield List(one, two)
                 }
    } yield pulls)
    val direct = run(scenario(bridged = false))
    direct shouldBe List(Some(Chunk("made")), Some(Chunk("set by the consumer")))
    run(scenario(bridged = true)) shouldBe direct
  }

  "Whether the consumer can be interrupted" should "be the same after a pull as before it" in {
    val interruptible: UIO[Boolean]          = ZIO.checkInterruptible(status => ZIO.succeed(status.isInterruptible))
    val source: ZStream[Any, Throwable, Int] = ZStream(1, 2).rechunk(1)
    val seen = run(source.toSpoonbill.flatMap { stream =>
      for {
        before <- interruptible
        _      <- stream.pull()
        after  <- interruptible
        // The same around a pull that the consumer makes where it cannot be interrupted.
        masked <- (interruptible <* stream.pull()).zipWith(interruptible)(List(_, _)).uninterruptible
        _      <- stream.cancel()
      } yield List(before, after) ++ masked
    })
    seen shouldBe List(true, true, false, false)
  }

  "A FiberRef of the stream's scope" should "be gone from the consumer after `cancel`, as after its own close" in {
    def scenario(bridged: Boolean): Task[(String, String)] = for {
      made <- Promise.make[Nothing, FiberRef[String]]
      source = ZStream
                 .scoped(FiberRef.make("made"))
                 .flatMap(ref => ZStream.fromZIO(made.succeed(ref) *> ref.set("set by the source")).as(1)): ZStream[
                 Any,
                 Throwable,
                 Int
               ]
      seen <- if (bridged) source.toSpoonbill.flatMap { stream =>
                for {
                  _      <- stream.pull()
                  ref    <- made.await
                  during <- ref.get
                  _      <- stream.cancel()
                  after  <- ref.get
                } yield (during, after)
              }
              else
                Scope.make.flatMap { scope =>
                  for {
                    pull   <- scope.extend[Any](source.toPull)
                    _      <- pull.unsome
                    ref    <- made.await
                    during <- ref.get
                    _      <- scope.close(Exit.unit)
                    after  <- ref.get
                  } yield (during, after)
                }
    } yield seen
    val direct = run(scenario(bridged = false))
    direct shouldBe ("set by the source", "made")
    run(scenario(bridged = true)) shouldBe direct
  }

  "A finalizer that runs after `cancel`" should "read the fiber context of the canceller, as after its own close" in {
    def scenario(bridged: Boolean): Task[Int] = ZIO.scoped(for {
      local <- FiberRef.make(0, fork = _ + 1)
      seen  <- Promise.make[Nothing, Int]
      source = ZStream
                 .acquireReleaseWith(ZIO.unit)(_ => local.get.flatMap(seen.succeed))
                 .flatMap(_ => ZStream(1, 2).rechunk(1)): ZStream[Any, Throwable, Int]
      _ <- if (bridged) source.toSpoonbill.flatMap(stream => stream.pull() *> stream.cancel())
           else
             Scope.make.flatMap(scope => scope.extend[Any](source.toPull).flatMap(_.unsome) *> scope.close(Exit.unit))
      value <- seen.await
    } yield value)
    val direct = run(scenario(bridged = false))
    direct shouldBe 0
    run(scenario(bridged = true)) shouldBe direct
  }
}
