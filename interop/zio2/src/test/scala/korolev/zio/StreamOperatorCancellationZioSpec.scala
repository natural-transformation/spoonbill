package spoonbill.zio

import _root_.zio.{Duration, FiberRef, Promise, Runtime, Task, ZIO}
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*
import spoonbill.effect.{Effect, Hub, Stream}

/** Native interruption and fiber-local ownership on a second lazy backend. */
final class StreamOperatorCancellationZioSpec extends AnyFlatSpec with Matchers {
  private given Effect[Task] = new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)

  private def run[A](task: Task[A]): A = Await.result(
    Effect[Task].toFuture(
      task.timeoutFail(new IllegalStateException("Stream ownership check timed out"))(Duration.fromSeconds(5))
    ),
    6.seconds
  )

  "An interrupted Hub subscriber" should "detach while the owned pending read still serves its sibling" in {
    val reads         = new AtomicInteger()
    val cancellations = new AtomicInteger()
    run(for {
      entered <- Promise.make[Nothing, Unit]
      value   <- Promise.make[Nothing, Option[Int]]
      source = new Stream[Task, Int] {
                 def pull(): Task[Option[Int]] =
                   ZIO.succeed(reads.incrementAndGet()) *> entered.succeed(()) *> value.await
                 def cancel(): Task[Unit] = ZIO.succeed { cancellations.incrementAndGet(); () }
               }
      hub    = Hub(source, 1)
      first  = hub.newStreamUnsafe()
      second = hub.newStreamUnsafe()
      _ <- (for {
             owner       <- first.pull().fork
             _           <- entered.await
             interrupted <- owner.interrupt
             following   <- second.pull().fork
             _           <- value.succeed(Some(7))
             delivered   <- following.join
             detached    <- first.pull()
             _           <- second.cancel()
             _ <- ZIO.succeed {
                    interrupted.isInterrupted shouldBe true
                    delivered shouldBe Some(7)
                    detached shouldBe None
                    reads.get() shouldBe 1
                    cancellations.get() shouldBe 0
                  }
           } yield ()).ensuring(value.succeed(None).unit)
    } yield ())
  }

  "The owned Hub reader" should "keep its own FiberRef across reads instead of adopting subscriber locals" in {
    val observed = new ConcurrentLinkedQueue[String]()
    val reads    = new AtomicInteger()
    run(ZIO.scoped {
      for {
        local <- FiberRef.make("reader-initial")
        source = new Stream[Task, Int] {
                   def pull(): Task[Option[Int]] = local.get.flatMap { context =>
                     ZIO.succeed { observed.add(context); reads.incrementAndGet() }
                       .flatMap(number => local.set("reader-next").as(Some(number)))
                   }
                   def cancel(): Task[Unit] = ZIO.unit
                 }
        stream = Hub(source).newStreamUnsafe()
        _ <- (for {
               first  <- local.set("subscriber-one") *> stream.pull()
               second <- local.set("subscriber-two") *> stream.pull()
               _ <- ZIO.succeed {
                      first shouldBe Some(1)
                      second shouldBe Some(2)
                      observed.asScala.toVector shouldBe Vector("reader-initial", "reader-next")
                    }
             } yield ()).ensuring(stream.cancel().orDie)
      } yield ()
    })
  }

  "Native partition interruption" should "settle all racks and dispatch cleanup in the canceling fiber context without joining it" in {
    val reads = new AtomicInteger()
    run(ZIO.scoped {
      for {
        local           <- FiberRef.make("initial")
        entered         <- Promise.make[Nothing, Unit]
        pendingRead     <- Promise.make[Nothing, Option[Int]]
        cleanupEntered  <- Promise.make[Nothing, String]
        cleanupFinished <- Promise.make[Nothing, Unit]
        source = new Stream[Task, Int] {
                   def pull(): Task[Option[Int]] =
                     ZIO.succeed(reads.incrementAndGet()) *> entered.succeed(()) *> pendingRead.await
                   def cancel(): Task[Unit] = local.get.flatMap(cleanupEntered.succeed) *> cleanupFinished.await *>
                     pendingRead.succeed(None).unit
                 }
        racks = source.sort(2)(_ % 2)
        _ <- (for {
               owner       <- (local.set("cancel-owner") *> racks(0).pull()).fork
               _           <- entered.await
               interrupted <- owner.interrupt
               context     <- cleanupEntered.await
               sibling     <- racks(1).pull()
               stillHeld   <- cleanupFinished.isDone
               _ <- ZIO.succeed {
                      interrupted.isInterrupted shouldBe true
                      context shouldBe "cancel-owner"
                      sibling shouldBe None
                      stillHeld shouldBe false
                      reads.get() shouldBe 1
                    }
             } yield ()).ensuring(cleanupFinished.succeed(()) *> pendingRead.succeed(None).unit)
      } yield ()
    })
  }

  "An intrinsically interrupted shared source" should "cache its cancellation failure for every Hub subscriber" in {
    val reads = new AtomicInteger()
    run(for {
      entered         <- Promise.make[Nothing, Unit]
      interruptSource <- Promise.make[Nothing, Unit]
      source = new Stream[Task, Int] {
                 def pull(): Task[Option[Int]] = ZIO.succeed(reads.incrementAndGet()) *> entered.succeed(()) *>
                   interruptSource.await *> ZIO.interrupt
                 def cancel(): Task[Unit] = ZIO.unit
               }
      hub    = Hub(source, 1)
      first  = hub.newStreamUnsafe()
      second = hub.newStreamUnsafe()
      _ <- (for {
             one          <- first.pull().either.fork
             _            <- entered.await
             two          <- second.pull().either.fork
             _            <- interruptSource.succeed(())
             firstResult  <- one.join
             secondResult <- two.join
             replay       <- first.pull().either
             _ <- ZIO.succeed {
                    val error = firstResult.swap.toOption.get
                    error shouldBe a[java.util.concurrent.CancellationException]
                    error.getMessage shouldBe "Shared upstream reader canceled"
                    secondResult.swap.toOption.get should be theSameInstanceAs error
                    replay.swap.toOption.get should be theSameInstanceAs error
                    reads.get() shouldBe 1
                  }
           } yield ()).ensuring(interruptSource.succeed(()).unit)
    } yield ())
  }
}
