package spoonbill.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ApplicationLifecycleMachineSpec extends AnyFlatSpec with Matchers {
  import ApplicationLifecycleMachine.*

  "Application startup ownership" should "admit one starter and replay every later initialization" in {
    val (starting, admission) = initialize(initial)
    admission shouldBe Initialization.Start
    initialize(starting) shouldBe ((starting, Initialization.Join))
    val (running, published) = started(starting)
    published shouldBe true
    initialize(running) shouldBe ((running, Initialization.Join))
    started(running) shouldBe ((running, false))
    failed(running) shouldBe ((running, false))
  }

  it should "retain failure as a completed startup and leave retry to a fresh instance" in {
    val (failedStart, publish) = failed(initialize(initial)._1)
    publish shouldBe true
    active(failedStart) shouldBe false
    initialize(failedStart) shouldBe ((failedStart, Initialization.Join))
    started(failedStart) shouldBe ((failedStart, false))
  }

  it should "reject startup and late completion after destruction in every prior state" in {
    State.values.foreach { state =>
      val stopped = destroy(state)._1
      active(stopped) shouldBe false
      initialize(stopped) shouldBe ((stopped, Initialization.Reject))
      started(stopped) shouldBe ((stopped, false))
      failed(stopped) shouldBe ((stopped, false))
      destroy(stopped)._1 shouldBe stopped
    }
  }
}
