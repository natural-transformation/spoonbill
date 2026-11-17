package spoonbill.internal

import java.util.concurrent.RejectedExecutionException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.{Reports, Worker}

class ExtensionRuntimeRetentionSpec extends AnyFlatSpec with Matchers {
  "Delivery admission" should "release its message when the final callback enters, even while that callback waits" in {
    val first = Promise[Unit]()
    val last  = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(
        f.recorder("first", onMessage = _ => first.future),
        f.recorder("last", onMessage = _ => last.future)
      )
    )
    f.attach()
    f.taken()
    f.runtime.retainedDelivery shouldBe false
    f.runtime.message("message")
    f.taken() shouldBe Vector("first.onMessage(message)")
    f.runtime.retainedDelivery shouldBe true
    first.success(())
    f.taken() shouldBe Vector("last.onMessage(message)")
    f.runtime.retainedDelivery shouldBe false
    last.success(())
    f.taken() shouldBe empty
    f.runtime.pending shouldBe 0
    f.runtime.retainedDelivery shouldBe false
    f.result(f.runtime.destroy())
  }

  it should "retain no admission when a delivery has no callbacks" in {
    val f = new ExtensionRuntimeFixture()(_ => Nil)
    f.attach()
    f.taken()
    f.runtime.retainedDelivery shouldBe false
    f.runtime.message("message")
    f.taken() shouldBe empty
    f.runtime.pending shouldBe 0
    f.runtime.retainedDelivery shouldBe false
    f.result(f.runtime.destroy())
  }

  it should "release admission when the final callback effect cannot start" in {
    val rejected = new RejectedExecutionException("synthetic refused effect startup")
    final class RefusingEffect extends Effect[Future] {
      export Effect.futureEffect.{
        pure, delay, uncancelable, fail, unit, never, fromTry,
        promise, promiseF, flatMap, map, recover, recoverF, start,
        blocking, fork, sequence, runAsync, run, toFuture
      }
      var untilRefusal = 0
      override def delayAsync[A](operation: => Future[A]): Future[A] = {
        if (untilRefusal > 0) {
          untilRefusal -= 1
          if (untilRefusal == 0) throw rejected
        }
        super.delayAsync(operation)
      }
    }
    val effect             = new RefusingEffect
    given Effect[Future]   = effect
    val worker             = new Worker
    given ExecutionContext = worker
    val reports            = new Reports
    val bound = new ExtensionRuntime.Bound[Future] {
      def apply[A](operation: => Future[A], late: A => Future[Unit]): Future[A] = operation
    }
    var called = false
    val observer = Extension[Future, Int, String]("observer") { _ =>
      Future.successful(Extension.Handlers[Future, Int, String](onMessage = _ => {
        called = true
        Future.unit
      }))
    }
    val runtime = new ExtensionRuntime[Future, Int, String](List(observer), reports, bound, None)
    Await.result(runtime.setup(null, 0, () => Future.unit), 5.seconds)
    Await.result(runtime.start(), 5.seconds)
    worker.drain()
    // The check starts, then the effect wrapper rejects the sole call before
    // evaluating its by-name body. SerialLane still owns the rejected step.
    effect.untilRefusal = 2
    runtime.message("message")
    worker.drain()
    called shouldBe false
    runtime.pending shouldBe 0
    runtime.retainedDelivery shouldBe false
    reports.errors shouldBe Vector("Extension observer: onMessage failed" -> rejected)
    val destroyed = runtime.destroy()
    worker.drain()
    Await.result(destroyed, 5.seconds)
  }
}
