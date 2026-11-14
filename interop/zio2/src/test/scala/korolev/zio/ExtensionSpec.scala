package spoonbill.zio

import _root_.zio.{Runtime, Task}
import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.ExecutionContext
import spoonbill.effect.Effect
import spoonbill.internal.ExtensionAdapterChecks

/** The shared extension checks under a lazy effect. */
class ExtensionSpec extends AnyFlatSpec {
  private given Effect[Task]     = new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private given ExecutionContext = ExecutionContext.global

  "ZIO extensions" should "keep the attach, delivery and teardown contract" in {
    ExtensionAdapterChecks.contract[Task]()
  }

  it should "set up one extension at a time" in {
    ExtensionAdapterChecks.sequentialSetup[Task]()
  }

  it should "stop at a failed setup and roll back" in {
    ExtensionAdapterChecks.failedSetup[Task]()
  }
}
