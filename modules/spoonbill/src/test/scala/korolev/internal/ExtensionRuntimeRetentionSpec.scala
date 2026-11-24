package spoonbill.internal

import java.util.concurrent.{CyclicBarrier, Executors, RejectedExecutionException, TimeUnit}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Extension
import spoonbill.effect.Effect
import spoonbill.internal.TimerTestSupport.{Reports, Worker}

class ExtensionRuntimeRetentionSpec extends AnyFlatSpec with Matchers {
  /** State 0 has reached the observer. The worker is waiting for the check of state 1. */
  private def pendingGuardedState(): (ExtensionRuntimeFixture, Promise[Unit]) = {
    val gate = Promise[Unit]()
    val checks = new ExtensionRuntime.Checks[Future, Int, String] {
      def state(value: Int): Future[Unit]      = if (value == 1) gate.future else Future.unit
      def message(value: String): Future[Unit] = Future.unit
    }
    val f = new ExtensionRuntimeFixture(Some(checks))(f => List(f.recorder("observer")))
    f.attach()
    f.taken() shouldBe Vector("observer.setup", "observer.onState(0)")
    f.runtime.state(1)
    f.taken() shouldBe empty
    (f, gate)
  }

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

  it should "release a guarded delivery whose check completes after teardown times out" in {
    val (f, gate) = pendingGuardedState()
    val destroyed = f.runtime.destroy()
    f.taken() shouldBe empty
    destroyed.isCompleted shouldBe false
    f.bound.expire()
    f.result(destroyed)
    f.taken() shouldBe Vector("observer.onDestroy(0)")
    gate.success(())
    f.runtime.retainedDelivery shouldBe false
    f.taken() shouldBe empty
    f.runtime.retainedDelivery shouldBe false
  }

  it should "release the delivery when a guarded check completes concurrently with stop" in {
    val completer = ExecutionContext.fromExecutorService(Executors.newSingleThreadExecutor())
    try {
      for (_ <- 1 to 100) {
        val (f, gate) = pendingGuardedState()
        val start     = new CyclicBarrier(2)
        val completed = Future {
          start.await(5, TimeUnit.SECONDS)
          gate.success(())
        }(completer)
        start.await(5, TimeUnit.SECONDS)
        f.runtime.stop()
        Await.result(completed, 5.seconds)
        // Observe both operations before draining the lane or starting teardown.
        f.runtime.retainedDelivery shouldBe false
        f.taken() shouldBe empty
        f.result(f.runtime.destroy())
        f.taken() shouldBe Vector("observer.onDestroy(0)")
      }
    } finally completer.shutdownNow()
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
