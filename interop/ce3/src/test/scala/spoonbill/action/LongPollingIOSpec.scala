package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.server.internal.services.LongPollingSessionChecks

/** The long-polling checks that need cancellation, under cats-effect. */
class LongPollingIOSpec extends AnyFlatSpec {
  private given Effect[IO]       = new IO3Effect(global)
  private given ExecutionContext = ExecutionContext.global

  /** Starts an effect and returns the function that cancels it and waits for that. */
  private def cancelable(effect: IO[Unit]): () => Unit = {
    val cancel = effect.unsafeRunCancelable()
    () => { Await.result(cancel(), 30.seconds); () }
  }

  "A long poll" should "give a joined poll the one view when the first poll is cancelled during the attach" in {
    LongPollingSessionChecks.cancelledFirstPoll[IO](cancelable)
  }

  it should "neither skip nor repeat the frame of a poll that was cancelled after its pull" in {
    LongPollingSessionChecks.cancelledAfterPull[IO](cancelable)
  }

  it should "not hang after a render that was cancelled between its record and its state" in {
    LongPollingSessionChecks.cancelledRender[IO](cancelable)
  }

  it should "finish the cleanup of a render that was cancelled while it removed an expired state" in {
    LongPollingSessionChecks.cancelledCleanup[IO](cancelable)
  }

  it should "run a WebSocket attach that was cancelled after its claim to its end" in {
    LongPollingSessionChecks.cancelledWebSocketAttach[IO](cancelable)
  }
}
