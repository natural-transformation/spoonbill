package spoonbill.action

import _root_.cats.effect.{Deferred, IO, Resource}
import _root_.cats.effect.unsafe.implicits.global
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import java.util.concurrent.atomic.AtomicLong
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{AsyncResourcePool, Close, Effect}
import spoonbill.effect.Reporter.PrintReporter.Implicit

class AsyncResourcePoolFileCleanupSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO] = new IO3Effect(global)

  private case class Handle(channel: FileChannel, closed: Deferred[IO, Unit]) {
    def close(): IO[Unit] = IO.blocking(channel.close()) *> closed.complete(()).void
  }

  private def open(path: Path): Resource[IO, Handle] = Resource.make {
    for {
      closed  <- Deferred[IO, Unit]
      channel <- IO.blocking(FileChannel.open(path, StandardOpenOption.WRITE))
    } yield Handle(channel, closed)
  }(_.close())

  "Idle file cleanup" should "close both handles and serve new work after the cleanup caller cancels" in {
    val file = Resource.make(IO.blocking(Files.createTempFile("spoonbill-pool-", ".tmp")))(path =>
      IO.blocking(Files.deleteIfExists(path)).void
    )
    val test = file.use { path =>
      val handles = for { a <- open(path); b <- open(path); c <- open(path) } yield List(a, b, c)
      handles.use { allocated =>
        for {
          closing    <- Deferred[IO, Handle]
          allowClose <- Deferred[IO, Unit]
          _ <- {
            given Close[IO, Handle] with {
              def onClose(handle: Handle): IO[Unit] = handle.closed.get
              def close(handle: Handle): IO[Unit] = closing.complete(handle).flatMap { first =>
                (if (first) allowClose.get else IO.unit) *> handle.close()
              }
            }
            val now       = new AtomicLong(0L)
            val available = allocated.iterator
            val pool =
              new AsyncResourcePool[IO, Handle]("files", IO(available.next()), () => IO(now.get()), 2, 1.second)
            (for {
              a        <- pool.borrow()
              b        <- pool.borrow()
              _        <- a.give() *> b.give()
              _        <- IO(now.set(1.second.toNanos))
              cleanup  <- pool.cleanup().start
              held     <- closing.get
              _        <- cleanup.cancel
              _        <- pool.cleanup()
              other     = if (held eq a.value) b.value else a.value
              _        <- IO { other.channel.isOpen shouldBe false; held.channel.isOpen shouldBe true }
              _        <- allowClose.complete(()) *> held.closed.get
              bytes    <- pool.use(h => IO.blocking(h.channel.write(ByteBuffer.wrap(Array[Byte](42)))))
              contents <- IO.blocking(Files.readAllBytes(path))
              _        <- IO { held.channel.isOpen shouldBe false; bytes shouldBe 1; contents.toList shouldBe List(42.toByte) }
            } yield ()).guarantee(allowClose.complete(()).void)
          }
        } yield ()
      }
    }
    test.timeout(10.seconds).unsafeRunSync()
  }
}
