package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.internal.ExtensionAdapterChecks
import spoonbill.server.internal.services.ExtensionSessionChecks

/** The shared extension checks under a lazy effect. */
class ExtensionIOSpec extends AnyFlatSpec {
  private given Effect[IO]       = new IO3Effect(global)
  private given ExecutionContext = ExecutionContext.global

  "IO extensions" should "keep the attach, delivery and teardown contract" in {
    ExtensionAdapterChecks.contract[IO]()
  }

  it should "set up one extension at a time" in {
    ExtensionAdapterChecks.sequentialSetup[IO]()
  }

  it should "stop at a failed setup and roll back" in {
    ExtensionAdapterChecks.failedSetup[IO]()
  }

  it should "report a handler that cancels itself, and call the extensions after it" in {
    ExtensionAdapterChecks.interruptedHandler[IO](IO.canceled)
  }

  it should "keep the handlers of a setup whose attach was cancelled" in {
    ExtensionAdapterChecks.cancelledAttach[IO] { attach =>
      val cancel = attach.unsafeRunCancelable()
      () => { Await.result(cancel(), 10.seconds); () }
    }
  }

  /**
   * Starts an attach and returns the function that cancels it and waits for
   * that.
   */
  private def cancelable(attach: IO[Unit]): () => Unit = {
    val cancel = attach.unsafeRunCancelable()
    () => { Await.result(cancel(), 30.seconds); () }
  }

  it should "clean up a guarded attach that was cancelled during a setup" in {
    ExtensionSessionChecks.cancelledGuardedAttach[IO](cancelable)
  }

  it should "finish the rollback of a guarded attach that is cancelled meanwhile" in {
    ExtensionSessionChecks.cancelledGuardedRollback[IO](cancelable)
  }

  it should "finish the cleanup of an attached view whose release is cancelled, before the cancellation returns" in {
    ExtensionSessionChecks.cancelledRelease[IO](cancelable)
  }

  it should "take the failure of an unguarded attach out of the session when the attach is cancelled meanwhile" in {
    ExtensionSessionChecks.cancelledLegacyRollback[IO](cancelable)
  }

  // Ten seconds of real time: the bound of the setup has to expire.
  it should "finish a rollback after a timeout that is cancelled meanwhile, and destroy the late setup result" in {
    ExtensionSessionChecks.cancelledTimeoutRollback[IO](cancelable)
  }
}
