package spoonbill.server.internal.services

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect

/** The shared long-polling checks under an effect that can be cancelled. */
class LongPollingCancellationIOSpec extends AnyFlatSpec {
  private given Effect[IO]       = new IO3Effect(global)
  private given ExecutionContext = ExecutionContext.global

  /** Starts a request and returns the function that cancels it and waits for that. */
  private def cancelable(request: IO[Unit]): () => Unit = {
    val cancel = request.unsafeRunCancelable()
    () => { Await.result(cancel(), 30.seconds); () }
  }

  "A long-polling request of the next opening that is cancelled" should "still end the view of the opening before" in {
    LongPollingSessionChecks.cancelledReopening[IO](cancelable)
  }
}
