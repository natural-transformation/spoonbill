package spoonbill.internal

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.data.Bytes
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.server.SessionAccessDenied

final class FrontendDownloadOwnershipSpec extends AsyncFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private given Reporter       = Reporter.PrintReporter
  private def browser() =
    new Frontend[Future](Queue[Future, String]().stream, None, authorize = Some(() => Future.unit))
  private def descriptor(frame: String): String = "\"([^\"]+)\"".r.findFirstMatchIn(frame).get.group(1)

  "Frontend download ownership" should "detach every download and launch cancellation without joining a blocked finalizer" in {
    val frontend = browser()
    val canceled = new AtomicInteger()
    val release  = Promise[Unit]()
    def stream(block: Boolean) = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.never
      def cancel(): Future[Unit]        = { canceled.incrementAndGet(); if (block) release.future else Future.unit }
    }
    for {
      _ <- frontend.downloadFile("first", stream(true), None, "text/plain")
      _ <- frontend.downloadFile("second", stream(false), None, "text/plain")
      _ <- frontend.close()
      _ <- frontend.close()
    } yield {
      try canceled.get() shouldBe 2
      finally release.trySuccess(())
    }
  }

  it should "reject a new download after close before claiming the caller's stream" in {
    val frontend = browser()
    val canceled = new AtomicInteger()
    val stream = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.never
      def cancel(): Future[Unit]        = { canceled.incrementAndGet(); Future.unit }
    }
    for {
      _     <- frontend.close()
      error <- frontend.downloadFile("late", stream, None, "text/plain").failed
    } yield {
      error shouldBe a[SessionAccessDenied]
      canceled.get() shouldBe 0
    }
  }

  it should "remove a completed download before later lookup or close" in {
    val frontend = browser()
    val canceled = new AtomicInteger()
    val stream = new Stream[Future, Bytes] {
      def pull(): Future[Option[Bytes]] = Future.successful(None)
      def cancel(): Future[Unit]        = { canceled.incrementAndGet(); Future.unit }
    }
    for {
      _        <- frontend.downloadFile("empty", stream, None, "text/plain")
      frame    <- frontend.outgoingMessages.pull()
      id        = descriptor(frame.get)
      selected <- frontend.resolveFileDownload(id)
      end      <- selected.get.stream.pull()
      repeated <- frontend.resolveFileDownload(id)
      _        <- frontend.close()
    } yield {
      end shouldBe None
      repeated shouldBe None
      canceled.get() shouldBe 0
    }
  }
}
