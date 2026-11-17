package spoonbill.server

import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Stream}

final class StandaloneOutputAttachmentSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect

  Seq(false, true).foreach { canceled =>
    "Standalone output attachment" should s"not revive application output after late attachment succeeds (canceled=$canceled)" in {
      val attached  = Promise[Boolean]()
      val pulls     = new AtomicInteger()
      val aborts    = new AtomicInteger()
      val lifecycle = new standalone.WebSocketOutputCell
      val frames = new Stream[Future, Int] {
        def pull(): Future[Option[Int]] = { pulls.incrementAndGet(); Future.never }
        def cancel(): Future[Unit]      = Future.unit
      }
      val output = standalone.finishAfter[Future, Int, Int](
        frames,
        identity,
        () => Future.unit,
        () => { aborts.incrementAndGet(); lifecycle.claimCleanup(true); Future.unit },
        () => attached.future,
        -1,
        lifecycle
      )
      val pending = output.pull()
      if (canceled) Await.result(output.cancel(), 3.seconds)
      else { lifecycle.claimCleanup(true); aborts.incrementAndGet() }
      attached.success(true)
      pulls.get() shouldBe 0
      Await.result(pending, 3.seconds) shouldBe None
      Await.result(output.pull(), 3.seconds) shouldBe None
      aborts.get() shouldBe 1
    }
  }
}
