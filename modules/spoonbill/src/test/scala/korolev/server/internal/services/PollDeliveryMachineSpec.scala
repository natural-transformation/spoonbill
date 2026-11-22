package spoonbill.server.internal.services

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Random

/** Every row of the delivery table of the design, and three properties over generated sequences. */
class PollDeliveryMachineSpec extends AnyFlatSpec with Matchers {
  import PollDeliveryMachine.*

  private type S = State[Int]
  private def poll(state: S, id: Int, seen: Long, awaited: Boolean = true): (S, Result[Int]) =
    step(state, Event.Poll(id, seen, awaited))

  /** A state that has handed out frame `confirmed + 1` of `kind` and waits for its confirmation. */
  private def keeping(confirmed: Long, dom: Long, kind: Kind): S =
    State[Int](confirmed = confirmed, dom = dom, kept = Some(kind))

  "A poll" should "be told to reload when the view has ended, whatever its cursor" in {
    val ended = State[Int](confirmed = 3L, ended = true)
    for (seen <- 0L to 5L) poll(ended, 1, seen) shouldBe (ended, Result.Reload())
  }

  it should "be stale when its cursor is older than what the page has confirmed" in {
    val state = keeping(3L, 1L, Kind.Dom)
    poll(state, 1, 2L) shouldBe (state, Result.Stale())
    poll(state, 1, 0L) shouldBe (state, Result.Stale())
  }

  it should "be told to reload when its cursor claims a frame that was not handed out" in {
    val nothingKept = State[Int](confirmed = 3L)
    poll(nothingKept, 1, 4L) shouldBe (nothingKept, Result.Reload())
    val kept = keeping(3L, 1L, Kind.Other)
    poll(kept, 1, 5L) shouldBe (kept, Result.Reload())
  }

  it should "get the kept frame again, with its numbers, when the page did not confirm it" in {
    for (kind <- Kind.values) {
      val state    = keeping(3L, 1L, kind)
      val expected = if (kind == Kind.Dom) 2L else 1L
      poll(state, 1, 3L) shouldBe (state, Result.Answer(4L, expected, keepAlive = false))
    }
  }

  it should "get a keep-alive frame under the same number when the kept command has expired" in {
    val command = keeping(3L, 1L, Kind.Command)
    poll(command, 1, 3L, awaited = false) shouldBe (command, Result.Answer(4L, 1L, keepAlive = true))
    // Only a command can expire. A DOM frame never does.
    for (kind <- List(Kind.Dom, Kind.Other)) {
      val state = keeping(3L, 1L, kind)
      poll(state, 1, 3L, awaited = false)._2 shouldBe a[Result.Answer[?]]
      poll(state, 1, 3L, awaited = false)._2.asInstanceOf[Result.Answer[Int]].keepAlive shouldBe false
    }
  }

  it should "confirm the kept frame and ask for the next one when its cursor is one ahead" in {
    val (afterDom, result) = poll(keeping(3L, 1L, Kind.Dom), 7, 4L)
    result shouldBe Result.Wait(pull = true)
    afterDom shouldBe State[Int](confirmed = 4L, dom = 2L, pulling = true, waiting = Vector(7))
    // A frame that is not a DOM frame keeps the revision.
    val (afterOther, _) = poll(keeping(3L, 1L, Kind.Command), 7, 4L)
    afterOther.dom shouldBe 1L
  }

  it should "wait without a second pull when a frame is asked for already" in {
    val pulling = State[Int](confirmed = 2L, pulling = true, waiting = Vector(1))
    poll(pulling, 2, 2L) shouldBe (pulling.copy(waiting = Vector(1, 2)), Result.Wait(pull = false))
  }

  it should "wait and ask for one frame when the page has everything" in {
    poll(initial[Int], 1, 0L) shouldBe (State[Int](pulling = true, waiting = Vector(1)), Result.Wait(pull = true))
  }

  "A pulled frame" should "be kept and answer every poll that waited, with its numbers" in {
    for (kind <- Kind.values) {
      val pulling     = State[Int](confirmed = 2L, dom = 1L, pulling = true, waiting = Vector(1, 2))
      val (next, out) = step(pulling, Event.Pulled(kind))
      val dom         = if (kind == Kind.Dom) 2L else 1L
      out shouldBe Result.Answered(Vector(1, 2), 3L, dom)
      next shouldBe State[Int](confirmed = 2L, dom = 1L, kept = Some(kind))
    }
  }

  it should "be kept when every poll that waited is settled already" in {
    val pulling = State[Int](pulling = true)
    step(pulling, Event.Pulled(Kind.Dom)) shouldBe (State[Int](kept = Some(Kind.Dom)), Result.Answered(Vector.empty, 1L, 1L))
  }

  it should "change nothing when no frame was asked for, or after the end" in {
    val idle = State[Int](confirmed = 2L)
    step(idle, Event.Pulled(Kind.Dom)) shouldBe (idle, Result.Silent())
    val ended = State[Int](ended = true)
    step(ended, Event.Pulled(Kind.Dom)) shouldBe (ended, Result.Silent())
  }

  "The end of the view" should "tell every poll that waits to reload, once" in {
    val waiting       = State[Int](confirmed = 1L, pulling = true, waiting = Vector(4, 5))
    val (ended, out)  = step(waiting, Event.Ended())
    out shouldBe Result.Reloaded(Vector(4, 5))
    ended shouldBe State[Int](confirmed = 1L, ended = true)
    step(ended, Event.Ended()) shouldBe (ended, Result.Reloaded(Vector.empty))
  }

  "A timeout" should "answer a poll that waits with 204, and nothing else" in {
    val waiting = State[Int](pulling = true, waiting = Vector(1, 2))
    step(waiting, Event.TimedOut(1)) shouldBe (waiting.copy(waiting = Vector(2)), Result.NoContent())
    step(waiting, Event.TimedOut(9)) shouldBe (waiting, Result.Silent())
  }

  "A cancellation" should "remove a poll that waits, silently, and leave the pull" in {
    val waiting = State[Int](pulling = true, waiting = Vector(1, 2))
    step(waiting, Event.Cancelled(2)) shouldBe (waiting.copy(waiting = Vector(1)), Result.Silent())
    step(waiting, Event.Cancelled(9)) shouldBe (waiting, Result.Silent())
  }

  "A message" should "go to the view unless the view has ended or it claims more than was handed out" in {
    PollDeliveryMachine.message(State[Int](confirmed = 2L), 2L) shouldBe Message.Deliver
    PollDeliveryMachine.message(State[Int](confirmed = 2L), 0L) shouldBe Message.Deliver
    PollDeliveryMachine.message(State[Int](confirmed = 2L), 3L) shouldBe Message.Gone
    PollDeliveryMachine.message(keeping(2L, 0L, Kind.Dom), 3L) shouldBe Message.Deliver
    PollDeliveryMachine.message(keeping(2L, 0L, Kind.Dom), 4L) shouldBe Message.Gone
    PollDeliveryMachine.message(State[Int](confirmed = 2L, ended = true), 2L) shouldBe Message.Gone
  }

  "The DOM revision of an answer" should "belong to its frame, not to what was published later" in {
    // A heartbeat answer is made at revision 0, then patch 1 is published, then
    // the page polls. The heartbeat says 0, and the patch after it says 1.
    val (asked, _)      = poll(initial[Int], 1, 0L)
    val (heartbeat, a1) = step(asked, Event.Pulled(Kind.Other))
    a1 shouldBe Result.Answered(Vector(1), 1L, 0L)
    // The first response was lost: frame 1 again, with the same revision.
    poll(heartbeat, 2, 0L)._2 shouldBe Result.Answer(1L, 0L, keepAlive = false)
    val (next, wait) = poll(heartbeat, 3, 1L)
    wait shouldBe Result.Wait(pull = true)
    step(next, Event.Pulled(Kind.Dom))._2 shouldBe Result.Answered(Vector(3), 2L, 1L)
  }

  "Two polls with one cursor" should "get the same frame from one pull" in {
    val (first, r1)  = poll(initial[Int], 1, 0L)
    val (second, r2) = poll(first, 2, 0L)
    r1 shouldBe Result.Wait(pull = true)
    r2 shouldBe Result.Wait(pull = false)
    step(second, Event.Pulled(Kind.Dom))._2 shouldBe Result.Answered(Vector(1, 2), 1L, 1L)
  }

  /**
   * Generated sequences of events against a model of the frames. A page polls
   * with cursors around the current state, frames come only when asked for,
   * and requests time out, are cancelled, or the view ends.
   */
  "Generated sequences" should "settle each poll once, keep at most one frame out, and count the DOM frames" in {
    for (seed <- 1 to 500) {
      val random     = new Random(seed)
      var state: S   = initial[Int]
      var nextId     = 0
      val issued     = scala.collection.mutable.ArrayBuffer.empty[Int]
      val settled    = scala.collection.mutable.Set.empty[Int]
      val kinds      = scala.collection.mutable.ArrayBuffer.empty[Kind] // kinds(n - 1) is frame n
      def settle(ids: Iterable[Int]): Unit = ids.foreach { id =>
        withClue(s"seed $seed, poll $id: ")(settled.contains(id) shouldBe false)
        settled += id
      }
      def checkFrame(frame: Long, dom: Long): Unit = withClue(s"seed $seed, frame $frame: ") {
        frame shouldBe state.confirmed + 1L
        dom shouldBe kinds.take(frame.toInt).count(_ == Kind.Dom).toLong
      }
      for (_ <- 1 to 80) {
        val before = state
        val event: Event[Int] = random.nextInt(10) match {
          case 0 | 1 | 2 | 3 =>
            nextId += 1
            issued += nextId
            val seen = before.confirmed + (random.nextInt(4) - 1).toLong
            Event.Poll(nextId, math.max(0L, seen), random.nextBoolean())
          case 4 | 5 => Event.Pulled(Kind.fromOrdinal(random.nextInt(Kind.values.length)))
          case 6 if issued.nonEmpty => Event.TimedOut(issued(random.nextInt(issued.size)))
          case 7 if issued.nonEmpty => Event.Cancelled(issued(random.nextInt(issued.size)))
          case 8 if random.nextInt(8) == 0 => Event.Ended()
          case _ =>
            nextId += 1
            issued += nextId
            Event.Poll(nextId, before.confirmed + (if (before.kept.nonEmpty) 1L else 0L), keptAwaited = true)
        }
        val (after, result) = step(before, event)
        // A frame that comes is the frame after the confirmed ones.
        (event, result) match {
          case (Event.Pulled(kind), Result.Answered(_, _, _)) => kinds += kind
          case _                                              => ()
        }
        state = after
        (event, result) match {
          case (Event.Poll(id, _, _), Result.Reload() | Result.Stale()) => settle(List(id))
          case (Event.Poll(id, _, _), Result.Answer(frame, dom, _)) =>
            settle(List(id))
            checkFrame(frame, dom)
          case (Event.Poll(id, _, _), Result.Wait(_)) =>
            withClue(s"seed $seed: ")(after.waiting should contain(id))
          case (_, Result.Answered(ids, frame, dom)) =>
            settle(ids)
            checkFrame(frame, dom)
          case (_, Result.Reloaded(ids))                => settle(ids)
          case (Event.TimedOut(id), Result.NoContent()) => settle(List(id))
          case (Event.Cancelled(id), _) if before.waiting.contains(id) => settle(List(id))
          case _                                                       => ()
        }
        withClue(s"seed $seed: ") {
          (handed(after) - after.confirmed) should (be >= 0L and be <= 1L)
          if (after.kept.nonEmpty) after.pulling shouldBe false
          after.waiting.distinct shouldBe after.waiting
          after.waiting.foreach(id => settled.contains(id) shouldBe false)
          if (after.ended) after.waiting shouldBe empty
        }
      }
    }
  }
}
