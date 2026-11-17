package spoonbill.effect

import StreamBatchMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class StreamBatchMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  "Batch ownership" should "grant waiting consumers in FIFO order without carrying a predecessor's first value" in {
    forAll(Gen.choose(1, 40)) { count =>
      val tickets = Vector.fill(count)(new Ticket)
      var state   = State[Int]()
      tickets.zipWithIndex.foreach { case (ticket, index) =>
        val (next, started) = begin(state, ticket)
        started shouldBe (index == 0)
        state = next
      }
      tickets.zipWithIndex.foreach { case (ticket, index) =>
        owns(state, ticket) shouldBe true
        state.active.flatMap(_.first) shouldBe None
        state = first(state, ticket, index)
        val captured          = state
        val (next, successor) = finish(state, ticket)
        successor shouldBe tickets.lift(index + 1)
        first(next, ticket, -1) shouldBe next
        finish(next, ticket) shouldBe (next, None)
        captured.active.flatMap(_.first) shouldBe Some(index)
        state = next
      }
      state.active shouldBe None
      state.waiting shouldBe empty
    }
  }

  it should "discard every owned value and waiting ticket on close and reject later admission" in {
    val active  = new Ticket
    val waiting = new Ticket
    val started = begin(State[Int](), active)._1
    val queued  = begin(first(started, active, 7), waiting)._1
    val closed  = close(queued)
    closed.active shouldBe None
    closed.waiting shouldBe empty
    for (ticket <- Vector(active, waiting, new Ticket)) {
      owns(closed, ticket) shouldBe false
      first(closed, ticket, 99) shouldBe closed
      finish(closed, ticket) shouldBe (closed, None)
      begin(closed, ticket) shouldBe (closed, false)
    }
    close(closed) shouldBe closed
  }

  it should "conserve a single owner and FIFO successors through generated stale-finish and cancellation histories" in {
    forAll(Gen.listOf(Gen.choose(0, 4))) { events =>
      var state      = State[Int]()
      var expected   = Vector.empty[Ticket]
      var closed     = false
      var firstValue = Option.empty[Int]
      var value      = 0
      events.foreach { event =>
        event match {
          case 0 =>
            val ticket          = new Ticket
            val (next, started) = begin(state, ticket)
            started shouldBe (!closed && expected.isEmpty)
            if (!closed) expected :+= ticket
            state = next
          case 1 if expected.nonEmpty =>
            val (next, successor) = finish(state, expected.head)
            expected = expected.tail
            successor shouldBe expected.headOption
            firstValue = None
            state = next
          case 2 if expected.nonEmpty =>
            state = first(state, expected.head, value)
            firstValue = Some(value)
            value += 1
          case 3 =>
            val foreign = new Ticket
            first(state, foreign, -1) shouldBe state
            finish(state, foreign) shouldBe (state, None)
          case 4 =>
            state = close(state)
            expected = Vector.empty
            firstValue = None
            closed = true
          case _ => ()
        }
        state.phase shouldBe (if (closed) Phase.Closed else Phase.Open)
        state.active.map(_.ticket) shouldBe expected.headOption
        state.active.flatMap(_.first) shouldBe firstValue
        state.waiting.toVector shouldBe expected.drop(1)
        expected.foreach(ticket => owns(state, ticket) shouldBe expected.headOption.contains(ticket))
      }
    }
  }
}
