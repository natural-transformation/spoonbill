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

  "Total timer transitions" should "roll back partial token allocation and ignore later admissions and stale events" in {
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

  it should "report exhaustion again when a queued periodic execution starts and settles after a failed replacement" in {
    // The review's [0, 3, 1, 4, 5] history must exercise the same assertions as
    // the generated histories, independently of ScalaCheck's random seed.
    val history = exercise(List(0, 3, 1, 4, 5), Long.MaxValue - 3L)
    history.map(_._1.slots(id).phase) shouldBe
      Vector(Phase.Waiting, Phase.Queued, Phase.Failed, Phase.Executing, Phase.Failed)
    history(2)._2 shouldBe Vector(Command.Exhausted)
    history(4)._2 shouldBe Vector(Command.Exhausted)
    history.last._1.slots(id).entry should not be empty
  }

  it should "allow the last available arm of an already queued execution after a failed replacement" in {
    // A replacement needs two tokens, but a periodic rearm needs only one.
    // Exhaustion blocks Accept; it does not make every other event inert.
    val history = exercise(List(0, 3, 1, 4, 5, 3), Long.MaxValue - 4L)
    history(2)._2 shouldBe Vector(Command.Exhausted)
    val rearmed = history(4)._1
    rearmed.exhausted shouldBe true
    rearmed.slots(id).phase shouldBe Phase.Waiting
    history(4)._2 shouldBe Vector(Command.Schedule(id, rearmed.slots(id).generation, Arm(Long.MaxValue), 50L))
    history(5)._2 shouldBe Vector(Command.Cancel(id, Arm(Long.MaxValue)), Command.Exhausted)
    history.last._1.slots(id).entry shouldBe None
  }

  it should "keep tokens fenced and render admission closed after exhaustion over generated histories" in {
    val serial = Gen.oneOf(Gen.const(0L), Gen.choose(0, 6).map(offset => Long.MaxValue - offset))
    forAll(Gen.listOf(Gen.choose(0, 7)), serial, minSuccessful(1000)) { (inputs, start) =>
      exercise(inputs, start)
      ()
    }
  }

  private def exercise(inputs: List[Int], serial: Long): Vector[(State, Vector[Command])] =
    inputs.zipWithIndex
      .scanLeft((State(serial = serial), Vector.empty[Command])) { case ((before, _), (input, tick)) =>
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
          if (event.isInstanceOf[Event.Accept]) (next, commands) shouldBe (before, Vector.empty)
        }
        commands.foreach {
          case Command.Schedule(_, _, arm, _) =>
            arm.value should be > before.serial
            arm.value should be <= next.serial
          case Command.Dispatch(_, _, execution) =>
            execution.value should be > before.serial
            execution.value should be <= next.serial
          case Command.Exhausted =>
            next.exhausted shouldBe true
            next.serial shouldBe before.serial // Partial allocations do not escape a failed transition.
            next.slots.values.foreach { slot =>
              slot.phase shouldBe Phase.Failed
              slot.arm shouldBe None
            }
          case _ => ()
        }
        (next, commands)
      }
      .tail
      .toVector
}
