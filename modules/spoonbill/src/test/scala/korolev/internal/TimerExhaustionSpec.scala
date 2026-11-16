package spoonbill.internal

import TimerTestSupport.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.Context
import spoonbill.effect.Effect
import spoonbill.timer.{RestartKey, TimerSettings}

class TimerExhaustionSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect

  "Timer token exhaustion" should "report once, cancel installed arms and keep later renders stopped" in {
    val worker  = new Worker
    val clock   = new Deadlines
    val reports = new Reports
    val runtime = new TimerRuntime[Future](clock, () => clock.now, worker, reports, TimerSettings(4))
    val target = new TimerRuntime.Target[Future] {
      val owner                                             = TimerMachine.Owner(1)
      def active                                            = true
      def offer(entry: ComponentLaneEntry[Future])          = fail("Exhausted timers must not dispatch")
      def deliver(select: () => Option[Any => Option[Any]]) = fail("Exhausted timers must not run")
    }
    val ctx    = Context[Future, Int, Unit]
    val first  = ctx.timerId("first")
    val second = ctx.timerId("second")
    def render(both: Boolean): Unit = {
      runtime.beginRender()
      runtime.collect(target, first, 1000000L, false, RestartKey.Stable, (n: Int) => Some(n + 1))
      if (both) runtime.collect(target, second, 1000000L, false, RestartKey.Stable, (n: Int) => Some(n + 1))
      runtime.prepareRender(); runtime.acceptTimerRender(); runtime.flush()
    }
    runtime.start()
    render(false)
    val armed = clock.active.head
    // Reach an otherwise impractical boundary without adding a production setter.
    val field = runtime.getClass.getDeclaredField("state")
    field.setAccessible(true)
    field.set(runtime, runtime.snapshot.copy(serial = Long.MaxValue))
    render(true)
    reports.warnings shouldBe Vector("Render timer token space exhausted; timers remain stopped until remount.")
    armed.canceled shouldBe true
    clock.active shouldBe empty
    runtime.retainedDescriptions shouldBe 0
    armed.fire()
    render(true)
    runtime.flush(); worker.drain()
    reports.warnings.size shouldBe 1
    clock.active shouldBe empty
    runtime.retainedDescriptions shouldBe 0
    runtime.close(target.owner)
    runtime.snapshot.slots shouldBe empty
  }

  it should "keep a queued execution inert when it starts after exhaustion and another render is accepted" in {
    val worker  = new Worker
    val clock   = new Deadlines
    val reports = new Reports
    val runtime = new TimerRuntime[Future](clock, () => clock.now, worker, reports, TimerSettings(4))
    var queued  = Option.empty[ComponentLaneEntry[Future]]
    var writes  = 0
    val target = new TimerRuntime.Target[Future] {
      val owner                                             = TimerMachine.Owner(1)
      def active                                            = true
      def offer(entry: ComponentLaneEntry[Future]): Boolean = { queued = Some(entry); true }
      def deliver(select: () => Option[Any => Option[Any]]): Future[Unit] = {
        select().flatMap(_(0)).foreach(_ => writes += 1)
        Future.unit
      }
    }
    val ctx    = Context[Future, Int, Unit]
    val first  = ctx.timerId("first")
    val second = ctx.timerId("second")
    def render(both: Boolean): Unit = {
      runtime.beginRender()
      runtime.collect(target, first, 1000000L, false, RestartKey.Stable, (n: Int) => Some(n + 1))
      if (both) runtime.collect(target, second, 1000000L, false, RestartKey.Stable, (n: Int) => Some(n + 1))
      runtime.prepareRender(); runtime.acceptTimerRender(); runtime.flush()
    }
    runtime.start()
    render(false)
    clock.advance(1.millis); clock.fire(); worker.drain()
    queued should not be empty
    val field = runtime.getClass.getDeclaredField("state")
    field.setAccessible(true)
    field.set(runtime, runtime.snapshot.copy(serial = Long.MaxValue))
    render(true)
    val running = queued.get.run() // Start precedes the task that selects the reducer.
    render(true)
    runtime.retainedDescriptions shouldBe 0
    worker.drain()
    Await.result(running, 5.seconds)
    writes shouldBe 0
    reports.warnings.size shouldBe 1
    runtime.close(target.owner)
    runtime.snapshot.slots shouldBe empty
  }
}
