package spoonbill.zio

import _root_.zio.{Runtime, Task}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.Context
import spoonbill.action.StateUpdate
import spoonbill.effect.Effect
import spoonbill.internal.TimerAdapterChecks
import spoonbill.state.javaSerialization.*
import spoonbill.timer.Period

class TimerSpec extends AnyFlatSpec with Matchers {
  private given Effect[Task]     = new Zio2Effect[Any, Throwable](Runtime.default, identity, identity)
  private given ExecutionContext = ExecutionContext.global
  "ZIO render timers" should "defer legacy constructors and observe their failures once" in {
    TimerAdapterChecks.legacy[Task]()
  }
  it should "run and settle periodic updates in the actual component lane" in {
    val ctx = Context[Task, Int, Any]
    val binding = ctx.timer
      .every(ctx.timerId("tick"), Period.from(1.millis).toOption.get)
      .update(StateUpdate.Modify[Int](_ + 1))
    TimerAdapterChecks.periodic(0, binding, 1, 2)
  }

  it should "preserve the first notification when startup waits for readiness" in {
    val ctx = Context[Task, Int, Any]
    val binding = ctx.timer
      .every(ctx.timerId("ready"), Period.from(1.millis).toOption.get)
      .update(StateUpdate.Modify[Int](_ + 1))
    TimerAdapterChecks.periodic(0, binding, 1, 2, waitForReadiness = true)
  }

  it should "close without joining application computation and reject its late result" in {
    val ctx = Context[Task, Int, Any]
    val binding = ctx.timer
      .every(ctx.timerId("close"), Period.from(1.millis).toOption.get)
      .update(StateUpdate.Modify[Int](_ + 1))
    TimerAdapterChecks.periodic(0, binding, 1, 2, holdComputationAtClose = true)
  }
}
