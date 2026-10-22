package spoonbill.server.internal.services

import _root_.cats.effect.IO
import _root_.cats.effect.syntax.all.*
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all.*
import java.io.{ByteArrayInputStream, IOException}
import java.nio.file.Files
import java.util.concurrent.{CountDownLatch, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.server.StaticResourceLimits
import spoonbill.web.{PathAndQuery, Response}

class FilesServiceCancellationIOSpec extends AnyFlatSpec with Matchers {
  "Static reads" should "close admitted input and release capacity before honoring cancellation" in {
    given Effect[IO] = new IO3Effect(global)
    val root         = Files.createTempDirectory("spoonbill-static-cancel-")
    val directory    = Files.createDirectory(root.resolve("static"))
    val file         = Files.write(directory.resolve("ok.txt"), Array[Byte](65))
    val entered      = new CountDownLatch(1)
    val release      = new CountDownLatch(1)
    val closed       = new AtomicInteger()
    val cancellationSawCleanup = new AtomicBoolean()
    val service = new FilesService[IO](
      new CommonService[IO](),
      StaticResourceLimits(maxCacheEntries = 0, maxResourceBytes = 8, maxConcurrentReads = 1),
      _ => Some(file.toUri.toURL),
      _ =>
        new ByteArrayInputStream(Array[Byte](65)) {
          override def read(buffer: Array[Byte], offset: Int, length: Int): Int = {
            entered.countDown()
            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("fixture timed out")
            super.read(buffer, offset, length)
          }
          override def close(): Unit = { closed.incrementAndGet(); super.close() }
        }
    )
    val path = PathAndQuery.fromString("/static/ok.txt")
    // A fiber containing only an uncancelable operation may finish successfully
    // before an external cancel request is observed. Keep a cancelable boundary
    // after the read and observe cleanup from the actual cancellation finalizer.
    val reading = (service.resourceFromClasspath(path) *> IO.never[Unit]).onCancel(IO {
      cancellationSawCleanup.set(closed.get() == 1 && service.usage == ((0, 0L, 0)))
    })
    val checked = for {
      worker       <- reading.start
      _            <- IO.blocking(entered.await(5, TimeUnit.SECONDS)).flatMap(ready => IO(ready shouldBe true))
      cancellation <- worker.cancel.start
      rejected     <- service.resourceFromClasspath(path)
      _ <- IO {
             rejected.status.code shouldBe 503
             service.usage shouldBe ((0, 0L, 1))
             release.countDown()
           }
      _       <- cancellation.joinWithNever
      outcome <- worker.join
      _ <- IO {
             outcome.isCanceled shouldBe true
             cancellationSawCleanup.get() shouldBe true
             closed.get() shouldBe 1
             service.usage shouldBe ((0, 0L, 0))
           }
      recovered <- service.resourceFromClasspath(path)
      _ <- IO {
             recovered.status shouldBe Response.Status.Ok
             closed.get() shouldBe 2
             service.usage shouldBe ((0, 0L, 0))
           }
    } yield ()
    try checked.timeout(10.seconds).unsafeRunSync()
    finally {
      release.countDown()
      Files.delete(file)
      Files.delete(directory)
      Files.delete(root)
    }
  }
}
