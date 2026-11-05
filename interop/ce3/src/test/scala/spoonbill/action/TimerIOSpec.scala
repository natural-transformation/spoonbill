package spoonbill.action

import _root_.cats.effect.IO
import _root_.cats.effect.unsafe.implicits.global
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.Context
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect
import spoonbill.internal.TimerAdapterChecks
import spoonbill.state.javaSerialization.*
import spoonbill.timer.Period

class TimerIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO]       = new IO3Effect(global)
  private given ExecutionContext = ExecutionContext.global
  "IO render timers" should "defer legacy constructors and observe their failures once" in {
    TimerAdapterChecks.legacy[IO]()
  }
  it should "run and settle periodic updates in the actual component lane" in {
    val ctx = Context[IO, Int, Any]
    val binding = ctx.timer
      .every(ctx.timerId("tick"), Period.from(1.millis).toOption.get)
      .update(StateUpdate.Modify[Int](_ + 1))
    TimerAdapterChecks.periodic(0, binding, 1, 2)
  }
}
