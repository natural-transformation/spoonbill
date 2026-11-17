package spoonbill.effect

import StreamRoutingMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StreamRoutingMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Shared read ownership" should "survive detaching the last subscriber and publish to subscribers present at completion" in {
    val old     = new Lane
    val oldPull = new Pull
    val waiting = pull(add(State[Int](Mode.Fanout, 1), old), old, oldPull).state
    canRead(waiting) shouldBe true
    val reading  = beginRead(waiting)
    val detached = StreamRoutingMachine.cancel(reading, Some(old))
    detached.deliveries shouldBe Vector(Delivery(oldPull, Right(None)))
    detached.cleanup shouldBe false
    detached.state.reader shouldBe Reader.Reading
    detached.state.lanes shouldBe empty
    canRead(detached.state) shouldBe false
    val repeated = StreamRoutingMachine.cancel(detached.state, Some(old))
    repeated.deliveries shouldBe empty
    repeated.cleanup shouldBe false
    repeated.state.reader shouldBe Reader.Reading

    val joined      = new Lane
    val slow        = new Lane
    val joinedPull  = new Pull
    val joinedState = pull(add(add(detached.state, joined), slow), joined, joinedPull).state
    canRead(joinedState) shouldBe false
    val published = received(joinedState, 7, None)
    published.deliveries shouldBe Vector(Delivery(joinedPull, Right(Some(7))))
    pull(published.state, slow, new Pull).deliveries.map(_.result) shouldBe Vector(Right(Some(7)))
    val tooLate = new Lane
    val late    = pull(add(published.state, tooLate), tooLate, new Pull)
    late.deliveries shouldBe empty
    late.state.lanes(tooLate).values shouldBe empty
  }

  it should "conserve per-subscriber FIFO values, finite credit and once-only pull settlement through membership changes" in {
    forAll(Gen.listOf(Gen.zip(Gen.choose(0, 4), Gen.choose(0, 2)))) { events =>
      val capacity          = 2
      var live              = Vector.fill(3)(new Lane)
      var state             = live.foldLeft(State[Int](Mode.Fanout, capacity))((current, lane) => add(current, lane))
      var accepted          = live.map(_ -> Vector.empty[Int]).toMap
      var delivered         = accepted
      var pendingByLane     = live.map(_ -> Vector.empty[Pull]).toMap
      var pullOwners        = Map.empty[Pull, Lane]
      var settled           = Set.empty[Pull]
      var actualReadPending = false
      var nextValue         = 0

      def observe(step: Step[Int]): Unit = {
        state = step.state
        step.deliveries.foreach { notice =>
          settled should not contain notice.pull
          val lane = pullOwners.getOrElse(notice.pull, fail("Settled a pull without an outstanding owner"))
          pendingByLane(lane).headOption shouldBe Some(notice.pull)
          pendingByLane = pendingByLane.updated(lane, pendingByLane(lane).tail)
          pullOwners -= notice.pull
          settled += notice.pull
          notice.result match {
            case Right(Some(value)) => delivered = delivered.updated(lane, delivered(lane) :+ value)
            case Right(None)        => ()
            case Left(error)        => fail("An open fanout history unexpectedly failed", error)
          }
        }
      }

      events.foreach { case (event, selector) =>
        event match {
          case 0 if live.nonEmpty =>
            val lane   = live(selector % live.size)
            val ticket = new Pull
            pendingByLane = pendingByLane.updated(lane, pendingByLane(lane) :+ ticket)
            pullOwners += ticket -> lane
            observe(pull(state, lane, ticket))
          case 1 if canRead(state) =>
            actualReadPending shouldBe false
            actualReadPending = true
            state = beginRead(state)
          case 2 if actualReadPending =>
            live.foreach(lane => accepted = accepted.updated(lane, accepted(lane) :+ nextValue))
            observe(received(state, nextValue, None))
            nextValue += 1
            actualReadPending = false
          case 3 if live.nonEmpty =>
            val lane     = live(selector % live.size)
            val detached = StreamRoutingMachine.cancel(state, Some(lane))
            detached.cleanup shouldBe false
            observe(detached)
            live = live.filterNot(_ eq lane)
            accepted -= lane
            delivered -= lane
            pendingByLane -= lane
          case 4 if live.size < 3 =>
            val lane = new Lane
            live :+= lane
            state = add(state, lane)
            accepted += lane      -> Vector.empty[Int]
            delivered += lane     -> Vector.empty[Int]
            pendingByLane += lane -> Vector.empty[Pull]
          case _ => ()
        }
        state.reader shouldBe (if (actualReadPending) Reader.Reading else Reader.Waiting)
        state.lanes.keySet shouldBe live.toSet
        live.foreach { lane =>
          val queued = state.lanes(lane).values.toVector
          delivered(lane) ++ queued shouldBe accepted(lane)
          queued.size should be <= capacity
          state.lanes(lane).pulls shouldBe pendingByLane(lane)
        }
        val retainedPulls = state.lanes.valuesIterator.flatMap(_.pulls).toVector
        retainedPulls.distinct.size shouldBe retainedPulls.size
        retainedPulls.toSet shouldBe pullOwners.keySet
        settled.intersect(pullOwners.keySet) shouldBe empty
        val expectedCredit = !actualReadPending && live.nonEmpty && pullOwners.nonEmpty &&
          live.forall(lane => accepted(lane).size - delivered(lane).size < capacity)
        canRead(state) shouldBe expectedCredit
      }
    }
  }

  "Partition failure" should "clear every queued rack, settle every waiting pull once and preserve one shared failure" in {
    val demanded = new Lane
    val queued   = new Lane
    val ticket   = new Pull
    var state    = pull(add(add(State[Int](Mode.Partition, 2), demanded), queued), demanded, ticket).state
    for (value <- Vector(1, 3)) {
      canRead(state) shouldBe true
      state = received(beginRead(state), value, Some(queued)).state
    }
    state.lanes(queued).values.toVector shouldBe Vector(1, 3)
    overflow(state, queued) shouldBe true
    val error    = StreamSortOverflowException(1, 2)
    val terminal = failed(beginRead(state), error)
    terminal.cleanup shouldBe true
    terminal.deliveries shouldBe Vector(Delivery(ticket, Left(error)))
    terminal.state.lanes.valuesIterator.foreach(_.values shouldBe empty)
    val closed = StreamRoutingMachine.cancel(terminal.state, None)
    closed.cleanup shouldBe false
    for (lane <- Vector(demanded, queued)) {
      val result = pull(closed.state, lane, new Pull).deliveries.head.result
      result.left.toOption.get should be theSameInstanceAs error
    }
    received(closed.state, 99, Some(queued)).deliveries shouldBe empty
    canRead(closed.state) shouldBe false
  }

  "Buffered accepted data" should "drain FIFO before failure while explicit cancellation remains terminal" in {
    val lane  = new Lane
    var state = add(State[Int](Mode.Buffered, 2), lane)
    for (value <- Vector(1, 2)) {
      canRead(state) shouldBe true
      state = received(beginRead(state), value, None).state
    }
    canRead(state) shouldBe false
    val first = pull(state, lane, new Pull)
    first.deliveries.map(_.result) shouldBe Vector(Right(Some(1)))
    canRead(first.state) shouldBe true
    val error    = new IllegalStateException("upstream failed")
    val terminal = failed(beginRead(first.state), error)
    val tail     = pull(terminal.state, lane, new Pull)
    tail.deliveries.map(_.result) shouldBe Vector(Right(Some(2)))
    pull(tail.state, lane, new Pull).deliveries.head.result.left.toOption.get should be theSameInstanceAs error
    val canceled = StreamRoutingMachine.cancel(terminal.state, None)
    canceled.cleanup shouldBe true
    pull(canceled.state, lane, new Pull).deliveries.map(_.result) shouldBe Vector(Right(None))
    received(canceled.state, 3, None).state.lanes(lane).values shouldBe empty
  }

  "Terminal routing" should "never reopen or resettle earlier pulls after late read outcomes" in {
    for (mode <- Mode.values; outcome <- 0 to 2) {
      val lane    = new Lane
      val ticket  = new Pull
      val initial = pull(add(State[Int](mode, 2), lane), lane, ticket).state
      val reading = beginRead(initial)
      val error   = new IllegalStateException("first failure")
      val terminal = outcome match {
        case 0 => ended(reading)
        case 1 => failed(reading, error)
        case _ => StreamRoutingMachine.cancel(reading, None)
      }
      terminal.deliveries.map(_.pull) shouldBe Vector(ticket)
      terminal.state.phase should not be Phase.Open
      if (outcome == 2) terminal.state.reader shouldBe Reader.Reading
      val lateValue = received(terminal.state, 42, None)
      val lateEnd   = ended(lateValue.state)
      val lateError = failed(lateEnd.state, new IllegalStateException("late failure"))
      Vector(lateValue, lateEnd, lateError).foreach { step =>
        step.deliveries shouldBe empty
        step.state.phase shouldBe terminal.state.phase
        canRead(step.state) shouldBe false
        step.state.lanes(lane).values shouldBe empty
      }
    }
  }

  it should "retry only a rejected cleanup claim without reopening or releasing the actual read" in {
    val lane    = new Lane
    val reading = beginRead(add(State[Int](Mode.Buffered, 1), lane))
    val first   = StreamRoutingMachine.cancel(reading, None)
    first.cleanup shouldBe true
    StreamRoutingMachine.cancel(first.state, None).cleanup shouldBe false
    val rejected = cleanupRejected(first.state)
    rejected.phase shouldBe first.state.phase
    rejected.reader shouldBe Reader.Reading
    canRead(rejected) shouldBe false
    val retry = StreamRoutingMachine.cancel(rejected, None)
    retry.cleanup shouldBe true
    retry.state.reader shouldBe Reader.Reading
    StreamRoutingMachine.cancel(retry.state, None).cleanup shouldBe false
  }
}
