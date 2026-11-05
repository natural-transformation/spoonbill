package spoonbill.internal

import TimerTestSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import spoonbill.Context
import spoonbill.effect.{Effect, Scheduler}

class LegacyDelaySpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect

  "Legacy delay" should "defer authorization and eager callback construction to the borrowed worker" in {
    val worker        = new Worker
    val clock         = new Deadlines
    val reports       = new Reports
    val authorization = Promise[Unit]()
    var authorized    = false
    var called        = 0
    val binding       = Context.Delay[Future, Int, Unit](1.second, _ => { called += 1; Future.unit })
    val delay         = new ComponentInstance.DelayInstance(binding, new Scheduler[Future], reports, worker, Some(clock))
    delay.start(null, () => { authorized = true; authorization.future })
    delay.isFinished shouldBe false
    clock.fire()
    delay.isFinished shouldBe true
    authorized shouldBe false
    called shouldBe 0
    worker.drain()
    authorized shouldBe true
    authorization.success(()) // inline effect continuation on the simulated scheduler
    called shouldBe 0
    worker.drain()
    called shouldBe 1
    reports.errors shouldBe empty
  }

  it should "observe a thrown constructor or failed callback exactly once" in {
    for (throws <- List(true, false)) {
      val worker  = new Worker
      val clock   = new Deadlines
      val reports = new Reports
      val error   = new IllegalStateException("synthetic")
      val binding = Context.Delay[Future, Int, Unit](1.second, _ => if (throws) throw error else Future.failed(error))
      val delay   = new ComponentInstance.DelayInstance(binding, new Scheduler[Future], reports, worker, Some(clock))
      delay.start(null, () => Future.unit)
      clock.fire()
      worker.drain()
      reports.errors.map(_._2) shouldBe Vector(error)
    }
  }

  it should "cancel pending scheduling and treat authority retirement as expected" in {
    val worker  = new Worker
    val clock   = new Deadlines
    val reports = new Reports
    val binding = Context.Delay[Future, Int, Unit](1.second, _ => fail("Retired callback ran"))
    val delay   = new ComponentInstance.DelayInstance(binding, new Scheduler[Future], reports, worker, Some(clock))
    delay.start(null, () => Future.failed(new spoonbill.server.SessionAccessDenied))
    clock.fire()
    worker.drain()
    reports.errors shouldBe empty
    val canceled = new ComponentInstance.DelayInstance(binding, new Scheduler[Future], reports, worker, Some(clock))
    canceled.start(null, () => Future.unit)
    canceled.cancel()
    clock.active shouldBe empty
    canceled.isFinished shouldBe false
  }

  it should "keep the shared scheduler responsive during a slow constructor, unlike the original dispatch path" in {
    for (originalPath <- List(true, false)) {
      val executor      = java.util.concurrent.Executors.newSingleThreadExecutor()
      val worker        = scala.concurrent.ExecutionContext.fromExecutorService(executor)
      val scheduler     = new Scheduler[Future]
      val entered       = new java.util.concurrent.CountDownLatch(1)
      val release       = new java.util.concurrent.CountDownLatch(1)
      val otherDeadline = Promise[Unit]()
      val binding = Context.Delay[Future, Int, Unit](
        Duration.Zero,
        _ => {
          entered.countDown()
          if (!release.await(3, java.util.concurrent.TimeUnit.SECONDS))
            throw new IllegalStateException("Worker gate timed out")
          Future.unit
        }
      )
      try {
        if (originalPath) {
          // Exact pre-change scheduling boundary: eager callback construction
          // occurred inside the shared scheduler's job.
          scheduler.unsafeScheduleOnce(Duration.Zero)(binding.effect(null))
        } else {
          new ComponentInstance.DelayInstance(binding, scheduler, new Reports, worker).start(null, () => Future.unit)
        }
        entered.await(3, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        scheduler.unsafeScheduleOnce(Duration.Zero) { otherDeadline.trySuccess(()); Future.unit }
        if (originalPath) otherDeadline.isCompleted shouldBe false
        else scala.concurrent.Await.result(otherDeadline.future, 3.seconds)
        release.countDown()
        scala.concurrent.Await.result(otherDeadline.future, 3.seconds)
      } finally {
        release.countDown()
        worker.shutdown()
      }
    }
  }
}
