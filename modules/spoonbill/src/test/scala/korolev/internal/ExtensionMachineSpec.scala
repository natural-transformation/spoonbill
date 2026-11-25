package spoonbill.internal

import ExtensionMachine.*
import org.scalacheck.Gen
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatestplus.scalacheck.ScalaCheckPropertyChecks

class ExtensionMachineSpec extends AnyFlatSpec with Matchers with ScalaCheckPropertyChecks {
  private type Machine = State[Int, String]
  private type Input   = Event[Int, String]
  private type Item    = Step[Int, String]

  private def registered(guarded: Boolean = false): Machine =
    List[Input](Event.Admit(7), Event.Register("a"), Event.Register("b"))
      .foldLeft(initial[Int, String](guarded))((s, e) => step(s, e)._1)

  private def emitted(result: (Machine, Vector[Command[Int, String]])): (Machine, Vector[Item]) =
    (
      result._1,
      result._2.flatMap {
        // Tests supply identity envelopes, just as the interpreter does. The
        // machine's emitted commands contain only substitutable notification data.
        case Command.Deliver(notification, targets) =>
          val check = Step.Check(notification)
          check +: Vector.tabulate[Item](targets)(index => Step.Call(check, index, result._1.slots.get(index)))
        case _ => Vector.empty
      }
    )

  private def delivering(guarded: Boolean = false): (Machine, Vector[Item]) =
    emitted(step(registered(guarded), Event.Begin))

  "Extension decisions" should "fold early states, keep every message and freeze delivery targets at Begin" in {
    val inputs    = List[Input](Event.Written(1), Event.Published("early"), Event.Written(2), Event.Published("second"))
    val recording = inputs.foldLeft(registered())((s, e) => step(s, e)._1)
    recorded(recording) shouldBe 3
    val (active, offered) = emitted(step(recording, Event.Begin))
    offered.collect { case check: Step.Check[Int, String] => check.notification } shouldBe
      Vector(Notification.State(2), Notification.Message("early"), Notification.Message("second"))
    offered.grouped(3).foreach { items =>
      val check = items.head.asInstanceOf[Step.Check[Int, String]]
      items.tail.foreach {
        case Step.Call(identity, _, _) => (identity eq check) shouldBe true
        case _                         => fail("A delivery must begin with exactly one check")
      }
      items.collect { case Step.Call(_, slot, name) => (slot, name) } shouldBe Vector(0 -> "a", 1 -> "b")
    }
    recorded(active) shouldBe 0
    step(active, Event.Begin)._2 shouldBe empty
    val late = step(active, Event.Register("late"))._1
    emitted(step(late, Event.Written(3)))._2.size shouldBe 3
    delivering()._2.head.asInstanceOf[Step.Check[Int, String]].notification shouldBe Notification.State(7)
    step(initial[Int, String](false), Event.Begin)._2 shouldBe empty
    // Ordinary delivery does not rewrite lifecycle state shared with producers.
    step(active, Event.Written(3))._1 should be theSameInstanceAs active
    step(active, Event.Published("next"))._1 should be theSameInstanceAs active
  }

  it should "preserve buffered delivery order and frozen targets for empty, single and large extension lists" in {
    for (targets <- Vector(0, 1, 33)) {
      val admitted = step(initial[Int, String](false), Event.Admit(7))._1
      val configured = (0 until targets).foldLeft(admitted) { (state, index) =>
        step(state, Event.Register(s"observer-$index"))._1
      }
      val begun    = step(configured, Event.Begin)
      val late     = step(begun._1, Event.Register("late"))._1
      val written  = step(late, Event.Written(8))
      val buffered = step(written._1, Event.Published("buffered"))
      withClue(s"targets=$targets: ") {
        (begun._2 ++ written._2 ++ buffered._2) shouldBe Vector(
          Command.Deliver(Notification.State(7), targets),
          Command.Deliver(Notification.State(8), targets),
          Command.Deliver(Notification.Message("buffered"), targets)
        )
        // Commands carry no freshly allocated identity: repeating a transition
        // produces a substitutable result, unaffected by subsequent admission.
        step(configured, Event.Begin) shouldBe begun
        val items = emitted(buffered)._2
        items.collect { case Step.Call(_, index, name) => (index, name) } shouldBe
          Vector.tabulate(targets)(index => (index, s"observer-$index"))
      }
    }
  }

  it should "admit calls only after their own check and retain only entered states" in {
    val (active, items) = delivering(true)
    val check           = items.head.asInstanceOf[Step.Check[Int, String]]
    val first           = items(1).asInstanceOf[Step.Call[Int, String]]
    val second          = items(2).asInstanceOf[Step.Call[Int, String]]
    checkAction(active) shouldBe CheckAction.Run
    checkAction(delivering()._1) shouldBe CheckAction.Pass
    mayCall(active, None, first) shouldBe false
    val admitted = passed(active, check)
    mayCall(active, admitted, first) shouldBe true
    mayCall(active, admitted, second) shouldBe true
    endsDelivery(active, first) shouldBe false
    endsDelivery(active, second) shouldBe true
    // Equal payloads are distinct deliveries; user state equality is not admission.
    val other = Step.Check[Int, String](Notification.State(7))
    mayCall(active, passed(active, other), first) shouldBe false
    mayCall(active, admitted, Step.Call(check, -1, "bad")) shouldBe false
    mayCall(active, admitted, Step.Call(check, 2, "bad")) shouldBe false
    val failed = step(active, Event.Failed(second, Failure.Other))
    failed._1 shouldBe active
    failed._2 shouldBe Vector(Command.Report("Extension b: onState failed"))
    val taken = step(active, Event.Take)
    taken._2 shouldBe Vector(Command.Close, Command.Destroy(1, "b", Some(7)), Command.Destroy(0, "a", Some(7)))
    step(taken._1, Event.Take)._2 shouldBe empty
    step(taken._1, Event.Register("late"))._2 shouldBe Vector(Command.Reject(Some(7)))
    endsDelivery(taken._1, second) shouldBe false
    val noTargets = step(initial[Int, String](false), Event.Begin)._1
    passed(noTargets, check) shouldBe empty
  }

  it should "keep failures identifiable after halt and take and suppress only expected reports" in {
    val (active, offered) = delivering()
    val check             = offered.head.asInstanceOf[Step.Check[Int, String]]
    val call              = offered(1).asInstanceOf[Step.Call[Int, String]]
    val halted            = step(active, Event.Halt)._1
    val taken             = step(halted, Event.Take)._1
    for (state <- Vector(registered(), active, halted, taken)) {
      step(state, Event.Failed(call, Failure.Other))._2 shouldBe Vector(Command.Report("Extension a: onState failed"))
      step(state, Event.Failed(call, Failure.Aborted))._2 shouldBe empty
      val (closed, failed) = step(state, Event.Failed(check, Failure.Other))
      stopped(closed) shouldBe true
      failed shouldBe Vector(Command.Close, Command.Report("Extension delivery was stopped: its check failed"))
      step(state, Event.Failed(check, Failure.Denied))._2 shouldBe Vector(Command.Close)
    }
    for (state <- Vector(halted, taken)) {
      passed(state, check) shouldBe empty
      checkAction(state) shouldBe CheckAction.Skip
      mayCall(state, Some(check), call) shouldBe false
      step(state, Event.Written(9))._2 shouldBe empty
      step(state, Event.Published("late"))._2 shouldBe empty
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

  it should "fence callbacks and emit no new delivery on any stop" in {
    val (active, offered) = delivering()
    val check             = offered.head.asInstanceOf[Step.Check[Int, String]]
    for (event <- Vector[Input](Event.Halt, Event.Take, Event.Failed(check, Failure.Other))) {
      val (closed, commands) = step(active, event)
      commands should contain(Command.Close)
      checkAction(closed) shouldBe CheckAction.Skip
      offered.tail.foreach { item =>
        mayCall(closed, Some(check), item.asInstanceOf[Step.Call[Int, String]]) shouldBe false
      }
      step(closed, Event.Written(8))._2 shouldBe empty
    }
  }

  it should "preserve notification order for generated before/after-start histories" in {
    val notification: Gen[Notification[Int, String]] =
      Gen.oneOf(Gen.choose(0, 100).map(Notification.State(_)), Gen.alphaStr.map(Notification.Message(_)))
    forAll(Gen.listOf(notification), Gen.listOf(notification)) { (before, after) =>
      def event(n: Notification[Int, String]): Input = n match {
        case Notification.State(value)   => Event.Written(value)
        case Notification.Message(value) => Event.Published(value)
      }
      def compact(state: Machine, n: Notification[Int, String]): (Machine, Vector[Command[Int, String]]) = {
        val interpreted: (Machine, Vector[Command[Int, String]]) = notificationAction(state) match {
          case NotificationAction.Record  => (record(state, n), Vector.empty)
          case NotificationAction.Deliver => (state, Vector(Command.Deliver(n, targets(state))))
          case NotificationAction.Ignore  => (state, Vector.empty)
        }
        interpreted shouldBe step(state, event(n))
        interpreted
      }
      val recording = before.foldLeft(registered())((s, n) => compact(s, n)._1)
      val begun     = step(recording, Event.Begin)
      val queued = after.foldLeft(begun) { case ((state, commands), notification) =>
        val (next, emitted) = compact(state, notification)
        (next, commands ++ emitted)
      }
      val expected = before.collect { case n @ Notification.State(_) => n }.lastOption.toVector match {
        case values if values.nonEmpty => values
        case _                         => Vector(Notification.State(7))
      }
      val offered = emitted(queued)._2
      offered.collect { case check: Step.Check[Int, String] => check.notification } shouldBe
        (expected ++ before.collect { case n @ Notification.Message(_) => n } ++ after).toVector
      offered.grouped(3).foreach { items =>
        items.collect { case Step.Call(_, slot, _) => slot } shouldBe Vector(0, 1)
      }
      val halted = step(queued._1, Event.Halt)._1
      (before ++ after).foreach(n => compact(halted, n) shouldBe (halted, Vector.empty))
    }
  }

  it should "keep the entered state notification across messages, including a delivered null" in {
    val state: Notification[String, String] = Notification.State(null)
    val seen = received(NeverReceived, state)
    seen should be theSameInstanceAs state
    received(seen, Notification.Message("message")) should be theSameInstanceAs state
    lastState(seen, Some("fallback")) shouldBe Some(null)
    val unseen: Received[String, String] = received(NeverReceived, Notification.Message("before state"))
    unseen shouldBe NeverReceived
    lastState(unseen, Some("fallback")) shouldBe Some("fallback")
    lastState(unseen, Some(null)) shouldBe Some(null)
    lastState(unseen, None) shouldBe None
  }

  it should "track the last entered state independently for generated lane histories" in {
    forAll(Gen.listOf(Gen.zip(Gen.choose(0, 1000), Gen.oneOf(true, false), Gen.oneOf(true, false)))) { history =>
      val (active, _) = delivering()
      val unseen = Vector.fill[Received[Int, String]](2)(NeverReceived)
      val (actual, expected) =
        history.foldLeft((unseen, Vector.fill(2)(Option.empty[Int]))) {
          case ((previous, expected), (value, firstStarts, secondStarts)) =>
            val (_, items) = emitted(step(active, Event.Written(value)))
            val check      = items.head.asInstanceOf[Step.Check[Int, String]]
            val admitted   = passed(active, check)
            val starts     = Vector(firstStarts, secondStarts)
            val next = previous.indices.map { index =>
              val call = items(index + 1).asInstanceOf[Step.Call[Int, String]]
              if (starts(index) && mayCall(active, admitted, call)) received(previous(index), check.notification)
              else previous(index)
            }.toVector
            (next, expected.indices.map(i => if (starts(i)) Some(value) else expected(i)).toVector)
        }
      actual.map(lastState(_, Option.empty[Int])) shouldBe expected
      val (taken, _) = step(active, Event.Take)
      step(taken, Event.Take)._2 shouldBe empty
      step(taken, Event.Written(42))._2 shouldBe empty
      actual.map(lastState(_, taken.admitted)) shouldBe expected.map(_.orElse(Some(7)))
    }
  }
}
