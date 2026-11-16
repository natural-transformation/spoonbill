package spoonbill.internal

import TimerMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import spoonbill.Context
import spoonbill.timer.RestartKey

class TimerMachinePropertiesSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  private val ctx    = Context[Option, Int, Unit]
  private val id     = Identity(Owner(1), ctx.timerId("timer"))
  private val other  = Identity(Owner(2), ctx.timerId("other"))
  private val config = Configuration(10L, true, RestartKey.Stable)

  "Total timer transitions" should "roll back partial token allocation and report exhaustion once" in {
    val waiting               = step(State(), Event.Accept(Vector(id -> config)), 0L)._1
    val before                = waiting.copy(serial = Long.MaxValue - 1L)
    val (exhausted, commands) = step(before, Event.Accept(Vector(id -> config, other -> config)), 1L)
    exhausted.serial shouldBe before.serial
    exhausted.slots.keySet shouldBe Set(id)
    exhausted.exhausted shouldBe true
    commands shouldBe Vector(Command.Cancel(id, waiting.slots(id).arm.get), Command.Exhausted)
    val events = Vector(
      Event.Accept(Vector(other -> config)),
      Event.Wake(id, waiting.slots(id).generation, waiting.slots(id).arm.get),
      Event.Start(id, Execution(99)),
      Event.Settle(id, Execution(99), false),
      Event.Fail(other, Generation(99)),
      Event.Close(other.owner)
    )
    events.foreach(event => step(exhausted, event, 100L) shouldBe (exhausted, Vector.empty))
    step(exhausted, Event.Close(id.owner), 100L)._1.slots shouldBe empty
  }

  it should "stop on overflow while waking or rearming and still settle retained executions" in {
    val waiting = step(State(), Event.Accept(Vector(id -> config)), 0L)._1
    val slot    = waiting.slots(id)
    for (now <- List(1L, 10L)) {
      val (exhausted, commands) =
        step(waiting.copy(serial = Long.MaxValue), Event.Wake(id, slot.generation, slot.arm.get), now)
      exhausted.exhausted shouldBe true
      commands shouldBe Vector(Command.Cancel(id, slot.arm.get), Command.Exhausted)
    }
    val queued                = step(waiting, Event.Wake(id, slot.generation, slot.arm.get), 10L)._1.copy(serial = Long.MaxValue)
    val execution             = queued.slots(id).entry.get.execution
    val (exhausted, commands) = step(queued, Event.Settle(id, execution, false), 11L)
    commands shouldBe Vector(Command.Exhausted)
    exhausted.slots(id).entry shouldBe queued.slots(id).entry
    val settled = step(exhausted, Event.Settle(id, execution, false), 12L)._1
    settled.slots(id).entry shouldBe None
    settled.slots(id).phase shouldBe Phase.Failed
  }

  it should "keep identities fenced and stop allocating after exhaustion over generated histories" in {
    forAll(Gen.listOf(Gen.choose(0, 7)), Gen.oneOf(0L, Long.MaxValue - 3L, Long.MaxValue)) { (inputs, serial) =>
      inputs.zipWithIndex.foldLeft(State(serial = serial)) { case (before, (input, tick)) =>
        val slot = before.slots.get(id)
        val event = input match {
          case 0 => Event.Accept(Vector(id -> config))
          case 1 => Event.Accept(Vector(id -> config.copy(key = RestartKey.Number(tick))))
          case 2 => Event.Accept(Vector.empty)
          case 3 =>
            Event.Wake(id, slot.map(_.generation).getOrElse(Generation(-1)), slot.flatMap(_.arm).getOrElse(Arm(-1)))
          case 4 => Event.Start(id, slot.flatMap(_.entry).map(_.execution).getOrElse(Execution(-1)))
          case 5 => Event.Settle(id, slot.flatMap(_.entry).map(_.execution).getOrElse(Execution(-1)), false)
          case 6 => Event.Fail(id, slot.map(_.generation).getOrElse(Generation(-1)))
          case _ => Event.Close(id.owner)
        }
        val (next, commands) = step(before, event, tick.toLong * 10L)
        step(before, event, tick.toLong * 10L) shouldBe (next, commands)
        next.serial should be >= before.serial
        next.slots.values.foreach { value =>
          if (value.arm.nonEmpty) value.phase shouldBe Phase.Waiting
          if (value.config.isEmpty) {
            // A late Fail can mark a retired execution failed before it settles.
            Set(Phase.Retired, Phase.Failed) should contain(value.phase)
            value.arm shouldBe None
            value.entry should not be empty
          }
        }
        if (before.exhausted) {
          next.exhausted shouldBe true
          commands.collect {
            case c: Command.Schedule => c; case c: Command.Dispatch => c; case Command.Exhausted => Command.Exhausted
          } shouldBe empty
        }
        next
      }
    }
  }
}
