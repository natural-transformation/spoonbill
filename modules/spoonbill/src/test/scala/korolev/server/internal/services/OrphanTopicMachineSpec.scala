package spoonbill.server.internal.services

import OrphanTopicMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class OrphanTopicMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Orphan topic decisions" should "renew activity, expire at the boundary and cancel cleanup only on first subscription" in {
    val initialState = initial(0L)
    shouldSchedule(initialState, 150L) shouldBe true
    shouldSchedule(initialState, 0L) shouldBe false
    shouldSchedule(initialState, -1L) shouldBe false
    val touched = step(initialState, Event.Activity(80L))._1
    due(touched.lastActivityMillis, 229L, 150L) shouldBe false
    due(touched.lastActivityMillis, 230L, 150L) shouldBe true
    val (attached, command) = step(touched, Event.Subscribe)
    command shouldBe Command.CancelCleanup
    shouldSchedule(attached, 150L) shouldBe false
    step(attached, Event.Subscribe) shouldBe (attached, Command.Ignore)
    val afterLatePublish = step(attached, Event.Activity(100L))._1
    subscribed(afterLatePublish) shouldBe true
    afterLatePublish.lastActivityMillis shouldBe 100L
  }

  it should "keep subscription permanent while recording explicit activity samples across histories" in {
    val event = Gen.oneOf(Gen.choose(-10000L, 10000L).map(Event.Activity(_)), Gen.const(Event.Subscribe))
    forAll(Gen.listOf(event)) { events =>
      events.foldLeft((initial(0L), false, 0L, 0)) { case ((state, wasSubscribed, last, cancellations), event) =>
        val (next, command)    = step(state, event)
        val expectedSubscribed = wasSubscribed || event == Event.Subscribe
        val expectedLast = event match {
          case Event.Activity(now) => now
          case _                   => last
        }
        val canceled = cancellations + (if (command == Command.CancelCleanup) 1 else 0)
        subscribed(next) shouldBe expectedSubscribed
        next.lastActivityMillis shouldBe expectedLast
        canceled shouldBe (if (expectedSubscribed) 1 else 0)
        (next, expectedSubscribed, expectedLast, canceled)
      }
    }
  }
}
