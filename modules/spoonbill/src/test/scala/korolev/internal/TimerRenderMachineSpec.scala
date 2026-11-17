package spoonbill.internal

import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import spoonbill.Context
import spoonbill.timer.RestartKey
import TimerRenderMachine.*

class TimerRenderMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  private val ctx = Context[Option, Int, Unit]
  private val owner = TimerMachine.Owner(1L)
  private val id = TimerMachine.Identity(owner, ctx.timerId("notice"))
  private val config = TimerMachine.Configuration(1000000L, false, RestartKey.Stable)
  private val acceptedTimers = TimerMachine.step(TimerMachine.State(), TimerMachine.Event.Accept(Vector(id -> config)), 0L)
  private def wake(arm: Long): TimerMachine.Event.Wake =
    TimerMachine.Event.Wake(id, acceptedTimers._1.slots(id).generation, TimerMachine.Arm(arm))
      .asInstanceOf[TimerMachine.Event.Wake]

  "Timer render decisions" should "coalesce startup arms, retain them across early flush, and drain running commands once" in {
    val first = accepted(initial, acceptedTimers._1, acceptedTimers._2)
    commandsToRun(first) shouldBe empty
    val early = flushed(report(first, "diagnostic"))
    early.reports shouldBe empty
    early.pending shouldBe first.pending
    val secondTimers = TimerMachine.step(acceptedTimers._1,
      TimerMachine.Event.Accept(Vector(id -> config.copy(key = RestartKey.Number(2)))), 1L)
    val second = accepted(early, secondTimers._1, secondTimers._2)
    val startedState = started(second)
    commandsToRun(startedState).size shouldBe 1
    commandsToRun(startedState).head shouldBe TimerMachine.Command.Schedule(id,
      secondTimers._1.slots(id).generation, secondTimers._1.slots(id).arm.get, secondTimers._1.slots(id).deadline)
    val runningState = flushed(startedState)
    commandsToRun(runningState) shouldBe empty
    val thirdTimers = TimerMachine.step(secondTimers._1,
      TimerMachine.Event.Accept(Vector(id -> config.copy(key = RestartKey.Number(3)))), 2L)
    val queued = accepted(runningState, thirdTimers._1, thirdTimers._2)
    commandsToRun(queued) shouldBe thirdTimers._2
    commandsToRun(flushed(queued)) shouldBe empty
  }

  it should "bound reserved wakes, replay the newest on abort and drop them on commit or owner close" in {
    forAll(Gen.nonEmptyListOf(Gen.choose(1L, 10000L))) { arms =>
      val prepared = reserve(initial, Set(id))
      canBegin(prepared) shouldBe false
      val waiting = arms.foldLeft(prepared)((state, arm) => defer(state, wake(arm)))
      deferred(waiting) shouldBe Vector(wake(arms.last))
      reserved(waiting, id) shouldBe true
      val closed = ownerClosed(waiting, owner)
      reserved(closed, id) shouldBe false
      deferred(closed) shouldBe empty
      canBegin(closed) shouldBe false // A staged render remains staged even when its owners retire.
      val committed = finishedRender(waiting)
      canBegin(committed) shouldBe true
      deferred(committed) shouldBe empty
    }
  }

  it should "classify omitted declarations and charge unsettled retired work against fresh capacity" in {
    collection(Declaration.Missing, true) shouldBe Collection.Accept
    collection(Declaration.Missing, false) shouldBe Collection.Invalid
    collection(Declaration.Valid, false) shouldBe Collection.Conflict
    collection(Declaration.Omitted, true) shouldBe Collection.Omit
    freshBudget(acceptedTimers._1, Set.empty, 0, 1) shouldBe 1
    val slot = acceptedTimers._1.slots(id)
    val queued = TimerMachine.step(acceptedTimers._1,
      TimerMachine.Event.Wake(id, slot.generation, slot.arm.get), 1000000L)._1
    freshBudget(queued, Set.empty, 0, 1) shouldBe 0
    freshBudget(queued, Set(id), 1, 1) shouldBe 0
  }

  it should "retain diagnostic suppression across abort and rearm it after a clean accepted render" in {
    val first = invalid(initial, owner)
    val issue = first.issues(owner)
    reportDue(first, owner, issue, 0L) shouldBe true
    val sent = reported(first, owner, issue, 0L, "first")
    val ended = finishedRender(flushed(sent))
    val same = invalid(begun(ended), owner)
    reportDue(same, owner, same.issues(owner), 2000000000L) shouldBe false
    val changed = conflict(same, owner, "timer")
    reportDue(changed, owner, changed.issues(owner), 500000000L) shouldBe false
    reportDue(changed, owner, changed.issues(owner), 1000000000L) shouldBe true
    val clean = retainCurrentSuppression(finishedRender(changed))
    val repeated = invalid(clean, owner)
    reportDue(repeated, owner, repeated.issues(owner), 0L) shouldBe true
    val sampled = (1 to 9).foldLeft(initial)((state, i) => conflict(state, owner, i.toString))
    sampled.issues(owner).samples shouldBe Vector("1", "2", "3", "4")
  }

  it should "transfer execution offer ownership without reattaching a detached target" in {
    unoffered(Offer.Pending) shouldBe true
    unoffered(offered(Offer.Pending)) shouldBe false
    val released = detached(Offer.Offered)
    offered(released) shouldBe Offer.Detached
    unoffered(released) shouldBe false
  }
}
