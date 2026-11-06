package spoonbill.internal

import TimerMachine.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import spoonbill.Context
import spoonbill.timer.RestartKey

class TimerMachineSpec extends AnyFlatSpec with Matchers {
  private val ctx    = Context[Option, Int, Unit]
  private val id     = Identity(Owner(1), ctx.timerId("notice"))
  private val config = Configuration(1000000L, false, RestartKey.Stable)

  "Timer machine" should "preserve deadlines, complete once, and create fresh generations on reappearance" in {
    val (waiting, _)          = step(State(), Event.Accept(Vector(id -> config)), 0L)
    val (unchanged, commands) = step(waiting, Event.Accept(Vector(id -> config)), 500000L)
    unchanged shouldBe waiting
    commands shouldBe empty
    val slot               = waiting.slots(id)
    val (queued, dispatch) = step(waiting, Event.Wake(id, slot.generation, slot.arm.get), 1000000L)
    dispatch.size shouldBe 1
    val execution = queued.slots(id).entry.get.execution
    val (done, _) = step(queued, Event.Settle(id, execution, false), 1000001L)
    done.slots(id).phase shouldBe Phase.Completed
    step(done, Event.Accept(Vector(id -> config)), 2000000L)._2 shouldBe empty
    val removed = step(done, Event.Accept(Vector.empty), 2000000L)._1
    val fresh   = step(removed, Event.Accept(Vector(id -> config)), 3000000L)._1
    fresh.slots(id).generation should not be slot.generation
    fresh.slots(id).deadline shouldBe 4000000L
  }

  it should "coalesce restart storms behind one old entry and dispatch an overdue successor only after settlement" in {
    var state = step(State(), Event.Accept(Vector(id -> config)), 0L)._1
    val first = state.slots(id)
    state = step(state, Event.Wake(id, first.generation, first.arm.get), 1000000L)._1
    val old = state.slots(id).entry.get.execution
    for (revision <- 1 to 1000) {
      state = step(state, Event.Accept(Vector(id -> config.copy(key = RestartKey.Number(revision)))), revision)._1
      state.slots(id).entry.get.execution shouldBe old
    }
    val latest              = state.slots(id)
    val (blocked, commands) = step(state, Event.Wake(id, latest.generation, latest.arm.get), 2000000L)
    commands shouldBe empty
    val (next, dispatch) = step(blocked, Event.Settle(id, old, false), 3000000L)
    dispatch.size shouldBe 1
    next.slots(id).entry.get.execution should not be old
    step(next, Event.Settle(id, old, false), 4000000L)._1 shouldBe next
  }

  it should "anchor periodic deadlines at settlement, including skips, without catch-up" in {
    val waiting          = step(State(), Event.Accept(Vector(id -> config.copy(periodic = true))), 0L)._1
    val slot             = waiting.slots(id)
    val queued           = step(waiting, Event.Wake(id, slot.generation, slot.arm.get), 999000000L)._1
    val (next, commands) = step(queued, Event.Settle(id, queued.slots(id).entry.get.execution, false), 1000000000L)
    next.slots(id).deadline shouldBe 1001000000L
    commands.size shouldBe 1
    next.slots(id).entry shouldBe None
  }

  it should "replace an early arm once, round upward, and reject duplicate wakes across signed wrap" in {
    val start   = Long.MaxValue - 500000L
    val waiting = step(State(), Event.Accept(Vector(id -> config)), start)._1
    val slot    = waiting.slots(id)
    due(start, slot.deadline) shouldBe false
    remainingMillis(start + 999999L, slot.deadline) shouldBe 1L
    val early               = Event.Wake(id, slot.generation, slot.arm.get)
    val (rearmed, commands) = step(waiting, early, start + 999999L)
    commands.size shouldBe 1
    rearmed.slots(id).arm should not be slot.arm
    step(rearmed, early, start + 1000000L)._2 shouldBe empty
    due(start + 1000000L, slot.deadline) shouldBe true
  }

  it should "keep failed declarations stopped and retire active records until their matching settlement" in {
    val waiting = step(State(), Event.Accept(Vector(id -> config)), 0L)._1
    val slot    = waiting.slots(id)
    val queued  = step(waiting, Event.Wake(id, slot.generation, slot.arm.get), 1000000L)._1
    val entry   = queued.slots(id).entry.get
    val started = step(queued, Event.Start(id, entry.execution), 1000000L)._1
    started.slots(id).phase shouldBe Phase.Executing
    val failed = step(started, Event.Settle(id, entry.execution, true), 1000000L)._1
    failed.slots(id).phase shouldBe Phase.Failed
    step(failed, Event.Accept(Vector(id -> config)), 1000000L)._2 shouldBe empty
    val retired = step(started, Event.Close(id.owner), 1000000L)._1
    retired.slots(id).phase shouldBe Phase.Retired
    step(retired, Event.Settle(id, entry.execution, false), 1000000L)._1.slots shouldBe empty
  }

  it should "use checked tokens rather than wrapping an exhausted generation" in {
    intercept[ArithmeticException] {
      step(State(serial = Long.MaxValue), Event.Accept(Vector(id -> config)), 0L)
    }
  }
}
