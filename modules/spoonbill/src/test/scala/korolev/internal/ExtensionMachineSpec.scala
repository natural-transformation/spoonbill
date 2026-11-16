package spoonbill.internal

import ExtensionMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks
import scala.annotation.tailrec

class ExtensionMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  private type Machine = State[Int, String]
  private type Input   = Event[Int, String]
  private type Item    = Step[Int, String]
  private def registered(guarded: Boolean = false): Machine =
    List[Input](Event.Admit(7), Event.Register("a"), Event.Register("b"))
      .foldLeft(initial[Int, String](guarded))((s, e) => step(s, e)._1)

  @tailrec private def drain(state: Machine, items: Vector[Item] = Vector.empty): (Machine, Vector[Item]) = {
    val (next, commands) = step(state, Event.Next)
    commands match {
      case Vector(Command.Hand(item)) => drain(next, items :+ item)
      case _                          => (state, items)
    }
  }
  private def delivering(guarded: Boolean = false): (Machine, Vector[Item]) =
    drain(step(registered(guarded), Event.Begin)._1)

  "Extension decisions" should "fold early states, keep every message and freeze delivery targets at Begin" in {
    val inputs        = List[Input](Event.Written(1), Event.Published("early"), Event.Written(2), Event.Published("second"))
    val recordedState = inputs.foldLeft(registered())((s, e) => step(s, e)._1)
    recorded(recordedState) shouldBe 3
    val (begun, commands) = step(recordedState, Event.Begin)
    commands shouldBe Vector(Command.Wake)
    waiting(begun) shouldBe 9
    val (active, offered) = drain(begun)
    waiting(active) shouldBe 0
    offered.collect { case Step.Check(_, n) => n } shouldBe
      Vector(Notification.State(2), Notification.Message("early"), Notification.Message("second"))
    offered.grouped(3).zipWithIndex.foreach { case (items, index) =>
      items.map(_.delivery) shouldBe Vector.fill(3)(index + 1L)
      items.collect { case Step.Call(_, slot, name, _) => (slot, name) } shouldBe Vector(0 -> "a", 1 -> "b")
    }
    recorded(active) shouldBe 0
    step(active, Event.Begin)._2 shouldBe empty
    val late = step(active, Event.Register("late"))._1
    drain(step(late, Event.Written(3))._1)._2.size shouldBe 3
    delivering()._2.head shouldBe Step.Check(1L, Notification.State(7))
    step(initial[Int, String](false), Event.Begin)._2 shouldBe empty
    step(active, Event.Next) shouldBe (active, Vector.empty)
  }

  it should "admit each call only after its check and remember only states whose calls started" in {
    val (active, Vector(check, first, second)) = delivering(true): @unchecked
    step(active, Event.Start(first))._2 shouldBe empty
    step(active, Event.Start(check))._2 shouldBe Vector(Command.RunCheck(Notification.State(7)))
    val passed        = step(active, Event.Passed(check.delivery))._1
    val (called, run) = step(passed, Event.Start(first))
    run shouldBe Vector(Command.RunCall(0, Notification.State(7)))
    called.slots.get.map(_.received) shouldBe Vector(Some(7), None)
    step(called, Event.Start(Step.Call(99L, 0, "a", Notification.State(9))))._2 shouldBe empty
    step(called, Event.Start(Step.Call(1L, -1, "bad", Notification.State(9))))._2 shouldBe empty
    val failed = step(called, Event.Failed(second, Failure.Other))
    failed._1 shouldBe called
    failed._2 shouldBe Vector(Command.Report("Extension b: onState failed"))
    val taken = step(called, Event.Take)
    taken._2 shouldBe Vector(Command.Close, Command.Destroy(1, "b", Some(7)), Command.Destroy(0, "a", Some(7)))
    step(taken._1, Event.Take)._2 shouldBe empty
    step(taken._1, Event.Register("late"))._2 shouldBe Vector(Command.Reject(Some(7)))
  }

  it should "keep failures identifiable after halt and take, suppress expected reports and report a refused lane once" in {
    val (active, offered) = delivering()
    val call              = offered(1)
    val halted            = step(active, Event.Halt)._1
    val taken             = step(halted, Event.Take)._1
    for (state <- Vector(registered(), active, halted, taken)) {
      step(state, Event.Failed(call, Failure.Other))._2 shouldBe Vector(Command.Report("Extension a: onState failed"))
      step(state, Event.Failed(call, Failure.Aborted))._2 shouldBe empty
      val (closed, failed) = step(state, Event.Failed(offered.head, Failure.Other))
      stopped(closed) shouldBe true
      failed shouldBe Vector(Command.Close, Command.Report("Extension delivery was stopped: its check failed"))
      step(state, Event.Failed(offered.head, Failure.Denied))._2 shouldBe Vector(Command.Close)
    }
    for (state <- Vector(halted, taken)) {
      step(state, Event.Passed(1L)) shouldBe (state, Vector.empty)
      offered.foreach(item => step(state, Event.Start(item))._2 shouldBe empty)
      step(state, Event.Written(9))._2 shouldBe empty
      step(state, Event.Published("late"))._2 shouldBe empty
      step(state, Event.Next)._2 shouldBe empty
      step(state, Event.Failed(call, Failure.Denied))._2 shouldBe empty
    }
    val (reported, reports) = step(active, Event.Unscheduled)
    reports shouldBe Vector(Command.Report("Extension delivery could not be scheduled"))
    step(reported, Event.Unscheduled)._2 shouldBe empty
    for (callback <- Callback.values; failure <- Failure.values; closed <- List(false, true)) {
      val silent = failure == Failure.Aborted || (failure == Failure.Denied && closed &&
        (callback == Callback.OnState || callback == Callback.OnMessage))
      reportOf("test", callback, failure, closed).isEmpty shouldBe silent
    }
  }

  it should "hand out waiting steps in order and drop them atomically on any stop" in {
    val queued                                    = step(registered(), Event.Begin)._1
    val (afterCheck, Vector(Command.Hand(check))) = step(queued, Event.Next): @unchecked
    waiting(afterCheck) shouldBe 2
    for (event <- Vector[Input](Event.Halt, Event.Take, Event.Failed(check, Failure.Other))) {
      val stoppedState = step(afterCheck, event)._1
      waiting(stoppedState) shouldBe 0
      step(stoppedState, Event.Next)._2 shouldBe empty
      step(stoppedState, Event.Start(check))._2 shouldBe empty
    }
    val wrapped: Machine = queued.copy(phase = Phase.Delivering(2, Long.MaxValue, None, Vector.empty))
    val (next, offered)  = drain(step(wrapped, Event.Written(8))._1)
    offered.map(_.delivery).distinct shouldBe Vector(Long.MinValue)
    val passed = step(next, Event.Start(offered.head))._1
    step(passed, Event.Start(offered(1)))._2 shouldBe Vector(Command.RunCall(0, Notification.State(8)))
  }

  it should "preserve notification order for generated before/after-start histories" in {
    val notification: Gen[Notification[Int, String]] =
      Gen.oneOf(Gen.choose(0, 100).map(Notification.State(_)), Gen.alphaStr.map(Notification.Message(_)))
    forAll(Gen.listOf(notification), Gen.listOf(notification)) { (before, after) =>
      def event(n: Notification[Int, String]): Input = n match {
        case Notification.State(value)   => Event.Written(value)
        case Notification.Message(value) => Event.Published(value)
      }
      val recording = before.foldLeft(registered())((s, n) => step(s, event(n))._1)
      val begun     = step(recording, Event.Begin)._1
      val queued    = after.foldLeft(begun)((s, n) => step(s, event(n))._1)
      val expected = before.collect { case n @ Notification.State(_) => n }.lastOption.toVector match {
        case values if values.nonEmpty => values
        case _                         => Vector(Notification.State(7))
      }
      val offered = drain(queued)._2
      offered.collect { case Step.Check(_, n) => n } shouldBe
        (expected ++ before.collect { case n @ Notification.Message(_) => n } ++ after).toVector
      offered.grouped(3).zipWithIndex.foreach { case (items, index) =>
        items.map(_.delivery) shouldBe Vector.fill(3)(index + 1L)
        items.collect { case Step.Call(_, slot, _, _) => slot } shouldBe Vector(0, 1)
      }
    }
  }

  it should "retain the last entered state and make teardown terminal for generated lane histories" in {
    forAll(Gen.listOf(Gen.zip(Gen.choose(0, 1000), Gen.oneOf(true, false), Gen.oneOf(true, false)))) { history =>
      val (state, last) = history.foldLeft((delivering()._1, Vector.fill(2)(7))) {
        case ((before, received), (value, firstStarts, secondStarts)) =>
          val (queued, Vector(check, a, b)) = drain(step(before, Event.Written(value))._1): @unchecked
          val passed                        = step(queued, Event.Start(check))._1
          val calledA =
            if (firstStarts) step(passed, Event.Start(a))._1 else step(passed, Event.Failed(a, Failure.Other))._1
          val calledB =
            if (secondStarts) step(calledA, Event.Start(b))._1 else step(calledA, Event.Failed(b, Failure.Other))._1
          (calledB, Vector(if (firstStarts) value else received(0), if (secondStarts) value else received(1)))
      }
      val (taken, commands) = step(state, Event.Take)
      commands.collect { case Command.Destroy(_, _, value) => value } shouldBe last.reverse.map(Some(_))
      step(taken, Event.Take)._2 shouldBe empty
      step(taken, Event.Written(42))._2 shouldBe empty
      step(taken, Event.Next)._2 shouldBe empty
      step(taken, Event.Start(Step.Call(1, 0, "a", Notification.State(42))))._2 shouldBe empty
    }
  }
}
