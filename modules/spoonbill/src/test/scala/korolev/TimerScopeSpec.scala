package spoonbill

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.action.StateUpdate
import spoonbill.timer.*
import spoonbill.util.Lens

class TimerScopeSpec extends AnyFlatSpec with Matchers {
  private val delay = DelayDuration.from(1.second).toOption.get
  private case class State(focus: Option[Int], untouched: String)
  private val ctx = Context[Option, State, Unit]
  private val lens = Lens[State, Int](
    { case State(Some(value), _) => value },
    { case (state, value) if value < 10 => state.copy(focus = Some(value)) }
  )
  private def reduce(binding: Context.Binding[Option, State, Unit], state: State): Option[State] =
    binding.asInstanceOf[Context.TimerBinding[Option, State, Unit]].update(state)

  "Timer scope" should "defer reducers, skip missing reads and undefined writes, and preserve parent fields" in {
    var calls  = 0
    val scoped = ctx.scope(lens)
    val binding = scoped.timer
      .after(ctx.timerId("focus"), delay)
      .update(StateUpdate.Modify { current =>
        calls += 1; current + 1
      })
    calls shouldBe 0
    reduce(binding, State(None, "keep")) shouldBe None
    calls shouldBe 0
    reduce(binding, State(Some(9), "keep")) shouldBe None
    calls shouldBe 1
    reduce(binding, State(Some(1), "keep")) shouldBe Some(State(Some(2), "keep"))
  }

  it should "distinguish NoUpdate from identity and keep repeated scope rooted in the original state" in {
    val scoped = ctx.scope(lens).scope(lens)
    val state  = State(Some(1), "keep")
    reduce(scoped.timer.after(ctx.timerId(), delay).update(StateUpdate.NoUpdate()), state) shouldBe None
    reduce(scoped.timer.after(ctx.timerId(), delay).update(StateUpdate.Modify(identity)), state) shouldBe Some(state)
    val throwing = ctx.scope[Int]({ case _ => throw new AssertionError("NoUpdate read focus") }, { case (s, _) => s })
    reduce(throwing.timer.after(ctx.timerId(), delay).update(StateUpdate.NoUpdate()), state) shouldBe None
  }

  "Timer identities" should "use named value equality and fresh anonymous identities without throwing on invalid names" in {
    ctx.timerId("site:item") shouldBe ctx.timerId("site:item")
    ctx.timerId("site:item").hashCode shouldBe ctx.timerId("site:item").hashCode
    ctx.timerId("site:item") should not be ctx.timerId("other:item")
    ctx.timerId() should not be ctx.timerId()
    TimerId.valid(ctx.timerId("")) shouldBe false
    TimerId.valid(ctx.timerId("x" * 129)) shouldBe false
    TimerId.diagnostic(ctx.timerId("a\n|b")) shouldBe "a\\u000a\\u007cb"
  }

  "Timer durations" should "reject fractional, nonfinite, negative, zero-period and out-of-horizon values exactly" in {
    DelayDuration.from(Duration.Zero).toOption.get.milliseconds shouldBe 0L
    Period.from(Duration.Zero).isLeft shouldBe true
    DelayDuration.from(1.microsecond).isLeft shouldBe true
    DelayDuration.from((-1).millis).isLeft shouldBe true
    DelayDuration.from(Duration.Inf).isLeft shouldBe true
    DelayDuration.from(DelayDuration.MaxMilliseconds.millis).isRight shouldBe true
    // Scala itself refuses durations beyond its signed nanosecond horizon.
    intercept[IllegalArgumentException]((DelayDuration.MaxMilliseconds + 1).millis)
    DelayDuration.from(Long.MaxValue.nanos).isLeft shouldBe true
    RestartKey.valid(RestartKey.Parts(Vector.fill(65)(RestartKey.Stable))) shouldBe false
    RestartKey.valid(RestartKey.Text("secret" * 1000)) shouldBe false
  }

  it should "reject broad control outcomes and identifiers for a different root state at compile time" in {
    assertDoesNotCompile("""
      import spoonbill.*, spoonbill.timer.*, spoonbill.action.*
      import scala.concurrent.duration.*
      val ctx = Context[Option, Int, Unit]
      ctx.timer.after(ctx.timerId(), DelayDuration.from(1.second).toOption.get).update(UiOutcome.noUpdate[Int])
    """)
    assertDoesNotCompile("""
      import spoonbill.*, spoonbill.timer.*
      import scala.concurrent.duration.*
      val a = Context[Option, Int, Unit]
      val b = Context[Option, String, Unit]
      b.timer.after(a.timerId(), DelayDuration.from(1.second).toOption.get)
    """)
  }
}
