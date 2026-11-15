package spoonbill.internal

import org.scalatest.flatspec.AnyFlatSpec
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.effect.Effect

/**
 * The shared extension checks under `Future`. The lazy-effect modules run the
 * same checks.
 */
class ExtensionAdapterFutureSpec extends AnyFlatSpec {
  private given Effect[Future]   = Effect.futureEffect
  private given ExecutionContext = ExecutionContext.global

  "Future extensions" should "keep the attach, delivery and teardown contract" in {
    ExtensionAdapterChecks.contract[Future]()
  }

  it should "set up one extension at a time" in {
    ExtensionAdapterChecks.sequentialSetup[Future]()
  }

  it should "stop at a failed setup and roll back" in {
    ExtensionAdapterChecks.failedSetup[Future]()
  }
}
