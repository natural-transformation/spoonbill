package spoonbill.action

import _root_.cats.effect.{Deferred, IO, IOLocal}
import _root_.cats.effect.unsafe.implicits.global
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{AsyncResourcePool, Close, Effect}
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolCleanupIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO] = new IO3Effect(global)
  private final class Resource(val name: String) {
    var attempts = 0
    val entered  = Deferred.unsafe[IO, String]
    val release  = Deferred.unsafe[IO, Unit]
    val closed   = Deferred.unsafe[IO, Unit]
  }

  "Canceled startup" should "abandon its unentered child without releasing a later cleanup's claim" in {
    val captured   = Deferred.unsafe[IO, IO[Unit]]
    val firstStart = new AtomicBoolean(true)
    given Effect[IO] = new IO3Effect(global) {
      override def start[A](task: => IO[A])(implicit ec: ExecutionContext): IO[Effect.Fiber[IO, A]] =
        if (firstStart.compareAndSet(true, false)) captured.complete(task.void) *> IO.never
        else super.start(task)
    }
    val resource = new Resource("late-child")
    given Close[IO, Resource] with {
      def onClose(value: Resource): IO[Unit] = value.closed.get
      def close(value: Resource): IO[Unit] = IO(value.attempts += 1) *>
        value.entered.complete("entered").void *> value.release.get *> value.closed.complete(()).void
    }
    var now  = 0L
    val pool = new AsyncResourcePool[IO, Resource]("canceled-start", IO.pure(resource), () => IO(now), 1, 1.second)
    val test = for {
      loan    <- pool.borrow()
      _       <- loan.give()
      _       <- IO { now = 1.second.toNanos }
      first   <- pool.cleanup().start
      late    <- captured.get
      _       <- first.cancel
      retry   <- pool.cleanup().start
      _       <- resource.entered.get
      _       <- late
      skipped <- pool.cleanup()
      _       <- IO { skipped shouldBe 0; resource.attempts shouldBe 1 }
      _       <- resource.release.complete(())
      count   <- retry.joinWithNever
      _ <- IO {
             count shouldBe 1
             pool.unsafeGetState.total shouldBe 0
             pool.unsafeGetState.closedItems shouldBe empty
           }
    } yield ()
    test.timeout(5.seconds).unsafeRunSync()
  }

  "An intrinsically canceled close" should "report a retryable error and still retire another ready resource" in {
    val healthy     = new Resource("healthy")
    val canceled    = new Resource("canceled")
    var cancelClose = true
    given Close[IO, Resource] with {
      def onClose(value: Resource): IO[Unit] = value.closed.get
      def close(value: Resource): IO[Unit] = IO {
        value.attempts += 1
      } *>
        (if ((value eq canceled) && cancelClose) IO.canceled *> IO.never else value.closed.complete(()).void)
    }
    var now       = 0L
    var available = List(healthy, canceled)
    val pool = new AsyncResourcePool[IO, Resource](
      "self-cancel-cleanup",
      IO {
        val value = available.head; available = available.tail; value
      },
      () => IO(now),
      2,
      1.second
    )
    val test = for {
      one    <- pool.borrow()
      two    <- pool.borrow()
      _      <- one.give()
      _      <- two.give()
      _      <- IO { now = 1.second.toNanos }
      result <- pool.cleanup().attempt
      _ <- IO {
             result.left.toOption.get shouldBe a[java.util.concurrent.CancellationException]
             healthy.attempts shouldBe 1
             canceled.attempts shouldBe 1
             cancelClose = false
           }
      retry <- pool.cleanup()
      _ <- IO {
             retry shouldBe 1
             canceled.attempts shouldBe 2
             pool.unsafeGetState.closedItems shouldBe empty
             pool.unsafeGetState.total shouldBe 0
           }
    } yield ()
    test.timeout(5.seconds).unsafeRunSync()
  }

  "Failed cleanup" should "try every ready resource once before propagating the first error" in {
    val failure = new IllegalStateException("persistent close failure")
    val healthy = new Resource("healthy")
    val failing = new Resource("failing")
    given Close[IO, Resource] with {
      def onClose(value: Resource): IO[Unit] = value.closed.get
      def close(value: Resource): IO[Unit] = IO {
        value.attempts += 1
      } *>
        (if (value eq failing) IO.raiseError(failure) else value.closed.complete(()).void)
    }
    var now       = 0L
    var available = List(healthy, failing)
    val pool = new AsyncResourcePool[IO, Resource](
      "cleanup-failures",
      IO {
        val value = available.head; available = available.tail; value
      },
      () => IO(now),
      2,
      1.second
    )
    val test = for {
      one <- pool.borrow()
      two <- pool.borrow()
      _   <- one.give()
      _ <- two.give() // the failing resource is first in the idle list
      _      <- IO { now = 1.second.toNanos }
      result <- pool.cleanup().attempt
      _ <- IO {
             result shouldBe Left(failure)
             healthy.attempts shouldBe 1
             failing.attempts shouldBe 1
             pool.unsafeGetState.total shouldBe 0
           }
      retry <- pool.cleanup().attempt
      _ <- IO {
             retry shouldBe Left(failure)
             healthy.attempts shouldBe 1
             failing.attempts shouldBe 2
           }
      _ <- failing.closed.complete(())
    } yield ()
    test.timeout(5.seconds).unsafeRunSync()
  }

  "Canceled cleanup waiting" should "leave its owned close alive and other expired resources available to another caller" in {
    val test = for {
      context                      <- IOLocal("initial")
      resources                    <- IO((new Resource("first"), new Resource("blocked"), new Resource("replacement")))
      (first, blocked, replacement) = resources
      fixture <- IO {
                   given Close[IO, Resource] with {
                     def onClose(value: Resource): IO[Unit] = value.closed.get
                     def close(value: Resource): IO[Unit] =
                       IO(value.attempts += 1) *> context.get.flatMap(value.entered.complete).void *>
                         (if (value eq blocked) value.release.get else IO.unit) *> value.closed.complete(()).void
                   }
                   var now       = 0L
                   var available = List(first, blocked, replacement)
                   val pool = new AsyncResourcePool[IO, Resource](
                     "native-cleanup",
                     IO {
                       val value = available.head; available = available.tail; value
                     },
                     () => IO(now),
                     2,
                     1.second
                   )
                   (pool, () => { now = 1.second.toNanos })
                 }
      (pool, expire) = fixture
      one           <- pool.borrow()
      two           <- pool.borrow()
      _             <- one.give()
      _             <- two.give()
      _             <- IO(expire())
      _             <- context.set("cleanup-owner")
      cleaning      <- pool.cleanup().start
      owner         <- blocked.entered.get
      _             <- IO(owner shouldBe "cleanup-owner")
      _ <- cleaning.cancel // must not wait for the blocked close
      _     <- context.set("next-caller")
      count <- pool.cleanup()
      _ <- IO {
             count shouldBe 1
             first.attempts shouldBe 1
             blocked.attempts shouldBe 1
           }
      nextOwner <- first.entered.get
      _         <- IO(nextOwner shouldBe "next-caller")
      fresh     <- pool.borrow()
      _         <- IO(fresh.value should be theSameInstanceAs replacement)
      _         <- blocked.release.complete(())
      _         <- blocked.closed.get
      _ <- {
        def settled: IO[Unit] = IO(pool.unsafeGetState.closedItems.isEmpty).flatMap {
          case true  => IO.unit
          case false => IO.cede *> settled
        }
        settled
      }
      _     <- IO(pool.unsafeGetState.total shouldBe 1)
      _     <- fresh.give()
      again <- pool.borrow()
      _     <- IO(again.value should be theSameInstanceAs replacement)
      _     <- again.discard()
      _     <- IO(pool.unsafeGetState.total shouldBe 0)
    } yield ()
    test.timeout(5.seconds).unsafeRunSync()
  }
}
