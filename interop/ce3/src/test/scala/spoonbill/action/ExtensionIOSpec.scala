package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.ExecutionContext
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.internal.ExtensionAdapterChecks

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
}
