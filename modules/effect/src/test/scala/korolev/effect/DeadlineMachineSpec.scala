package spoonbill.effect

import DeadlineMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class DeadlineMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "An absolute deadline machine" should "arm once, complete timely and fence the previous timer when reused" in {
    val initial           = State(10L)
    val (armed, schedule) = step(initial, Event.Start(100L))
    schedule shouldBe Vector(Command.Schedule(1L, 10L))
    step(armed, Event.Start(109L)) shouldBe (armed, Vector.empty)
    val (ready, completed) = step(armed, Event.Complete(109L))
    ready.phase shouldBe Phase.Ready
    completed shouldBe Vector(Command.Cancel, Command.Completed(true))
    step(ready, Event.Fired(1L)) shouldBe (ready, Vector.empty)
    val (rearmed, nextSchedule) = step(ready, Event.Start(200L))
    nextSchedule shouldBe Vector(Command.Schedule(3L, 10L))
    step(rearmed, Event.Fired(1L)) shouldBe (rearmed, Vector.empty)
    val (closed, expire) = step(rearmed, Event.Fired(3L))
    closed.phase shouldBe Phase.Closed
    expire shouldBe Vector(Command.Expire)
    step(closed, Event.Fired(3L)) shouldBe (closed, Vector.empty)
    step(closed, Event.Start(201L)) shouldBe (closed, Vector.empty)
  }

  it should "close on completion without an arm, duplicate completion or elapsed time without expiring transport" in {
    val initial               = State(10L)
    val (unstarted, rejected) = step(initial, Event.Complete(0L))
    unstarted.phase shouldBe Phase.Closed
    rejected shouldBe Vector(Command.Completed(false))
    val armed             = step(initial, Event.Start(0L))._1
    val (late, completed) = step(armed, Event.Complete(10L))
    late.phase shouldBe Phase.Closed
    completed shouldBe Vector(Command.Cancel, Command.Completed(false))
    val ready = step(armed, Event.Complete(9L))._1
    step(ready, Event.Complete(9L))._1.phase shouldBe Phase.Closed
    step(ready, Event.Complete(9L))._2 shouldBe Vector(Command.Completed(false))
  }

  it should "cancel at close without asking for completion or shutdown" in {
    val initial = State(10L)
    val armed   = step(initial, Event.Start(0L))._1
    for (before <- Vector(initial, armed, step(armed, Event.Complete(0L))._1)) {
      val (closed, commands) = step(before, Event.Close)
      closed.phase shouldBe Phase.Closed
      commands shouldBe (if (before == armed) Vector(Command.Cancel) else Vector.empty)
      step(closed, Event.Close)._2 shouldBe empty
      step(closed, Event.Complete(0L))._2 shouldBe Vector(Command.Completed(false))
    }
  }

  it should "allow retry after schedule rejection while keeping rejected and stale tickets fenced" in {
    val armed = step(State(10L), Event.Start(0L))._1
    step(armed, Event.ScheduleFailed(0L)) shouldBe (armed, Vector.empty)
    val (ready, commands) = step(armed, Event.ScheduleFailed(1L))
    ready.phase shouldBe Phase.Ready
    ready.generation shouldBe 1L
    commands shouldBe empty
    val rearmed = step(ready, Event.Start(1L))._1
    step(rearmed, Event.Fired(1L)) shouldBe (rearmed, Vector.empty)
    step(rearmed, Event.ScheduleFailed(1L)) shouldBe (rearmed, Vector.empty)
  }

  it should "preserve signed clock and ticket wrap and trust a matching scheduled fire" in {
    val start = Long.MaxValue - 5L
    val armed = step(State(10L, generation = Long.MaxValue), Event.Start(start))._1
    armed.generation shouldBe Long.MinValue
    step(armed, Event.Complete(start + 9L))._2 should contain(Command.Completed(true))
    step(armed, Event.Complete(start + 10L))._2 should contain(Command.Completed(false))
    step(armed, Event.Fired(Long.MinValue))._2 shouldBe Vector(Command.Expire)
    // No wall-clock read is needed to honor a callback the scheduler already selected.
    step(step(State(0L), Event.Start(0L))._1, Event.Complete(0L))._2 should contain(Command.Completed(false))
  }

  it should "remain closed, reject stale callbacks and emit at most one expiration over generated histories" in {
    forAll(Gen.listOf(Gen.choose(0, 6)), Gen.oneOf(0L, Long.MaxValue - 1L), minSuccessful(1000)) { (inputs, serial) =>
      val (_, expirations) = inputs.zipWithIndex.foldLeft((State(10L, serial), 0)) {
        case ((before, expired), (input, tick)) =>
          val event = input match {
            case 0 => Event.Start(tick.toLong * 3L)
            case 1 => Event.Complete(tick.toLong * 3L)
            case 2 => Event.Fired(before.generation)
            case 3 => Event.Fired(before.generation - 1L)
            case 4 => Event.ScheduleFailed(before.generation)
            case 5 => Event.ScheduleFailed(before.generation - 1L)
            case _ => Event.Close
          }
          val (next, commands) = step(before, event)
          step(before, event) shouldBe (next, commands)
          if (before.phase == Phase.Closed) {
            next.phase shouldBe Phase.Closed
            commands.forall {
              case Command.Completed(false) => true
              case _                        => false
            } shouldBe true
          }
          if (input == 3 || input == 5) (next, commands) shouldBe (before, Vector.empty)
          val total = expired + commands.count(_ == Command.Expire)
          total should be <= 1
          (next, total)
      }
      expirations should be <= 1
    }
  }
}
