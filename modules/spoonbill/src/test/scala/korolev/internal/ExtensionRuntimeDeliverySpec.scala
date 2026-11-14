package spoonbill.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.server.SessionAccessDenied

/** What an extension receives, and in which order. */
class ExtensionRuntimeDeliverySpec extends AnyFlatSpec with Matchers {

  private def checks(
    onState: Int => Future[Unit] = _ => Future.unit,
    onMessage: String => Future[Unit] = _ => Future.unit
  ): Option[ExtensionRuntime.Checks[Future, Int, String]] =
    Some(new ExtensionRuntime.Checks[Future, Int, String] {
      def state(value: Int): Future[Unit]      = onState(value)
      def message(value: String): Future[Unit] = onMessage(value)
    })

  "The attach state" should "be the admitted state when nothing was written" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.attach(admitted = 7)
    f.taken() shouldBe Vector("a.setup", "a.onState(7)")
  }

  it should "be the newest state written before delivery started" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.runtime.state(1)
    f.runtime.state(2)
    f.attach(admitted = 0)
    // The older write never follows the newer one, and neither does the admitted state.
    f.taken() shouldBe Vector("a.setup", "a.onState(2)")
  }

  it should "fold two requests written during setup into the newer one, and keep both messages" in {
    val f = new ExtensionRuntimeFixture()(f =>
      List(
        f.recorder("observer"),
        f.recorder("a", onSetup = () => Future.successful { f.runtime.state(101); f.runtime.message("from a") }),
        f.recorder("b", onSetup = () => Future.successful { f.runtime.state(102); f.runtime.message("from b") })
      )
    )
    f.attach()
    f.taken().filter(_.startsWith("observer.on")) shouldBe Vector(
      "observer.onState(102)",
      "observer.onMessage(from a)",
      "observer.onMessage(from b)"
    )
  }

  it should "keep one state, and nothing in the lane, before delivery starts" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    (1 to 10000).foreach(f.runtime.state)
    f.runtime.pending shouldBe 0
    f.runtime.recorded shouldBe 1
    f.attach()
    f.taken() shouldBe Vector("a.setup", "a.onState(10000)")
    f.runtime.recorded shouldBe 0
  }

  it should "keep every message that is published before delivery starts" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    (1 to 100).foreach(index => f.runtime.message(index.toString))
    // Messages are never folded, so they are kept until delivery starts.
    f.runtime.recorded shouldBe 100
    f.attach()
    f.taken().count(_.startsWith("a.onMessage")) shouldBe 100
    f.runtime.recorded shouldBe 0
  }

  "Delivery" should "pass every later write and message on once, in the order they were announced" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.runtime.message("m")
    f.runtime.state(2)
    f.taken() shouldBe Vector("a.onState(1)", "a.onMessage(m)", "a.onState(2)")
    f.runtime.pending shouldBe 0
  }

  it should "never run on the thread that announces a write" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.attach()
    f.taken()
    f.runtime.state(1)
    // Nothing happens until the execution context runs the lane.
    f.worker.pending shouldBe 1
    f.taken() shouldBe Vector("a.onState(1)")
  }

  it should "call the extensions in registration order" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a"), f.recorder("b"), f.recorder("c")))
    f.attach()
    f.taken() shouldBe Vector("a.setup", "b.setup", "c.setup", "a.onState(0)", "b.onState(0)", "c.onState(0)")
  }

  it should "start a callback only after the previous one has completed" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a", onState = state => if (state == 1) gate.future else Future.unit), f.recorder("b"))
    )
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.runtime.message("m")
    f.runtime.state(2)
    // The second extension, the message and the next state all wait.
    f.taken() shouldBe Vector("a.onState(1)")
    gate.success(())
    f.taken() shouldBe Vector(
      "b.onState(1)",
      "a.onMessage(m)",
      "b.onMessage(m)",
      "a.onState(2)",
      "b.onState(2)"
    )
  }

  it should "go on in a new task after a callback that completed later" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a", onState = state => if (state == 1) gate.future else Future.unit), f.recorder("b"))
    )
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.runtime.state(2)
    f.taken() shouldBe Vector("a.onState(1)")
    // This thread completes the callback, as a render thread would. No other
    // callback may run on it: not the next extension, not the next state.
    gate.success(())
    f.logged shouldBe empty
    f.taken() shouldBe Vector("b.onState(1)", "a.onState(2)", "b.onState(2)")
  }

  it should "run the callbacks after a guarded check in a new task, when the check completed later" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture(checks(onState = value => if (value == 1) gate.future else Future.unit))(f =>
      List(f.recorder("a"))
    )
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.taken() shouldBe empty
    // The thread that completes the check does not call the extension.
    gate.success(())
    f.logged shouldBe empty
    f.taken() shouldBe Vector("a.onState(1)")
  }

  "A failed callback" should "be reported with the extension's name, and delivery continues" in {
    val failure = new IllegalStateException("synthetic")
    val f = new ExtensionRuntimeFixture()(f =>
      List(
        f.recorder(
          "a",
          onState = state => if (state == 1) Future.failed(failure) else Future.unit,
          onMessage = _ => throw failure
        ),
        f.recorder("b")
      )
    )
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.runtime.message("m")
    f.runtime.state(2)
    f.taken() shouldBe Vector(
      "a.onState(1)",
      "b.onState(1)",
      "a.onMessage(m)",
      "b.onMessage(m)",
      "a.onState(2)",
      "b.onState(2)"
    )
    f.errors shouldBe Vector("Extension a: onState failed" -> failure, "Extension a: onMessage failed" -> failure)
  }

  "The guarded check" should "run once per notification, not once per extension" in {
    var checked = Vector.empty[String]
    val f = new ExtensionRuntimeFixture(
      checks(
        onState = value => { checked :+= s"state $value"; Future.unit },
        onMessage = value => { checked :+= s"message $value"; Future.unit }
      )
    )(f => List(f.recorder("a"), f.recorder("b"), f.recorder("c")))
    f.attach()
    f.runtime.state(1)
    f.runtime.message("m")
    f.taken()
    checked shouldBe Vector("state 0", "state 1", "message m")
  }

  it should "stop delivery when it fails, without calling a handler or reporting" in {
    val f = new ExtensionRuntimeFixture(
      checks(onState = value => if (value == 2) Future.failed(new SessionAccessDenied) else Future.unit)
    )(f => List(f.recorder("a")))
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.runtime.state(2)
    f.runtime.state(3)
    f.runtime.message("m")
    f.taken() shouldBe Vector("a.onState(1)")
    f.errors shouldBe empty
  }

  it should "report a check that fails for another reason, and stop delivery" in {
    val broken = new IllegalStateException("storage")
    val f = new ExtensionRuntimeFixture(
      checks(onState = value => if (value == 2) Future.failed(broken) else Future.unit)
    )(f => List(f.recorder("a")))
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.runtime.state(2)
    f.runtime.state(3)
    f.taken() shouldBe Vector("a.onState(1)")
    // Delivery never stops without a trace.
    f.errors shouldBe Vector("Extension delivery was stopped: its check failed" -> broken)
  }

  "A stopped runtime" should "ignore what is announced afterwards" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.attach()
    f.taken()
    f.runtime.stop()
    f.runtime.state(1)
    f.runtime.message("m")
    f.taken() shouldBe empty
    f.runtime.pending shouldBe 0
  }

  it should "not call the remaining extensions for the notification in flight" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a", onState = state => if (state == 1) gate.future else Future.unit), f.recorder("b"))
    )
    f.attach()
    f.taken()
    f.runtime.state(1)
    f.taken() shouldBe Vector("a.onState(1)")
    f.runtime.stop()
    gate.success(())
    // The second extension has not seen state 1, and it never will.
    f.taken() shouldBe empty
  }

  it should "not report a denied access from a callback that was already running" in {
    val gate = Promise[Unit]()
    val f    = new ExtensionRuntimeFixture()(f => List(f.recorder("a", onState = _ => gate.future)))
    f.attach()
    f.taken() shouldBe Vector("a.setup", "a.onState(0)")
    f.runtime.stop()
    // The callback used the access of the closing view.
    gate.failure(new SessionAccessDenied)
    f.taken()
    f.errors shouldBe empty
  }
}
