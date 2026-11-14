package spoonbill.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.server.SessionAccessDenied

/** Setup and teardown: who is set up, who is destroyed, and in which order. */
class ExtensionRuntimeLifecycleSpec extends AnyFlatSpec with Matchers {

  private val failure = new IllegalStateException("synthetic")

  "Setup" should "run for one extension at a time, in registration order" in {
    val gate = Promise[Unit]()
    val f    = new ExtensionRuntimeFixture()(f => List(f.recorder("a", onSetup = () => gate.future), f.recorder("b")))
    val done = f.setup()
    f.taken() shouldBe Vector("a.setup")
    done.isCompleted shouldBe false
    gate.success(())
    f.taken() shouldBe Vector("b.setup")
    f.result(done)
  }

  it should "fail when one setup fails, and not set up the extensions after it" in {
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a"), f.recorder("b", onSetup = () => Future.failed(failure)), f.recorder("c"))
    )
    f.result(f.setup(admitted = 5).failed) shouldBe failure
    f.taken() shouldBe Vector("a.setup", "b.setup")
    f.errors shouldBe Vector("Extension b: setup failed" -> failure)
    // The session closes the view, then asks for the rollback.
    f.result(f.runtime.destroy())
    f.taken() shouldBe Vector("a.onDestroy(5)")
  }

  it should "treat a setup that throws like one that fails" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a"), f.recorder("b", onSetup = () => throw failure)))
    f.result(f.setup().failed) shouldBe failure
    f.errors shouldBe Vector("Extension b: setup failed" -> failure)
  }

  it should "give up on a setup that never completes, and destroy its late result at once" in {
    val never = Promise[Unit]()
    val f     = new ExtensionRuntimeFixture()(f => List(f.recorder("a"), f.recorder("b", onSetup = () => never.future)))
    val done  = f.setup(admitted = 5)
    f.taken() shouldBe Vector("a.setup", "b.setup")
    f.bound.expire()
    f.result(done.failed) shouldBe a[ExtensionRuntime.TimedOut]
    // What the first extension acquired is released, although the second never returned.
    f.result(f.runtime.destroy())
    f.taken() shouldBe Vector("a.onDestroy(5)")
    never.success(())
    f.taken() shouldBe Vector("b.onDestroy(5)")
  }

  it should "keep a late setup result for the teardown, so it is destroyed after the view is closed" in {
    val late = Promise[Unit]()
    val f    = new ExtensionRuntimeFixture()(f => List(f.recorder("a"), f.recorder("b", onSetup = () => late.future)))
    val done = f.setup(admitted = 5)
    f.taken() shouldBe Vector("a.setup", "b.setup")
    f.bound.expire()
    f.result(done.failed) shouldBe a[ExtensionRuntime.TimedOut]
    // The result arrives while the session still closes the view. It waits.
    late.success(())
    f.taken() shouldBe empty
    f.result(f.runtime.destroy())
    f.taken() shouldBe Vector("b.onDestroy(5)", "a.onDestroy(5)")
  }

  it should "not set up the remaining extensions once the connection is gone" in {
    var connected = true
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a", onSetup = () => Future.successful { connected = false }), f.recorder("b"))
    )
    val done = f.setup(proceed = () => if (connected) Future.unit else Future.failed(new SessionAccessDenied))
    f.result(done.failed) shouldBe a[SessionAccessDenied]
    f.taken() shouldBe Vector("a.setup")
    f.errors shouldBe empty
  }

  it should "fail when the connection is gone after the last setup, so delivery does not start" in {
    var connected = true
    val f =
      new ExtensionRuntimeFixture()(f => List(f.recorder("a", onSetup = () => Future.successful { connected = false })))
    val done = f.setup(proceed = () => if (connected) Future.unit else Future.failed(new SessionAccessDenied))
    f.result(done.failed) shouldBe a[SessionAccessDenied]
    f.taken() shouldBe Vector("a.setup")
    f.errors shouldBe empty
  }

  it should "stop once the runtime was stopped" in {
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a", onSetup = () => Future.successful(f.runtime.stop())), f.recorder("b"))
    )
    f.result(f.setup().failed) shouldBe a[ExtensionRuntime.SetupAborted]
    f.taken() shouldBe Vector("a.setup")
    f.errors shouldBe empty
  }

  it should "destroy an extension whose setup completes after teardown" in {
    val gate = Promise[Unit]()
    val f    = new ExtensionRuntimeFixture()(f => List(f.recorder("a", onSetup = () => gate.future)))
    val done = f.setup(admitted = 3)
    f.result(f.runtime.destroy())
    f.taken() shouldBe Vector("a.setup")
    gate.success(())
    // The setup of a view that was torn down does not succeed.
    f.result(done.failed) shouldBe a[ExtensionRuntime.SetupAborted]
    // Teardown had already run, so the extension is destroyed on arrival, once.
    f.taken() shouldBe Vector("a.onDestroy(3)")
    f.errors shouldBe empty
  }

  "Teardown" should "call onDestroy once per extension, in reverse order, with the last delivered state" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a"), f.recorder("b"), f.recorder("c")))
    f.attach(admitted = 0)
    f.runtime.state(4)
    f.taken()
    f.result(f.runtime.destroy())
    f.taken() shouldBe Vector("c.onDestroy(4)", "b.onDestroy(4)", "a.onDestroy(4)")
    f.result(f.runtime.destroy())
    f.taken() shouldBe empty
  }

  it should "pass the admitted state when no state was delivered" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.result(f.setup(admitted = 9))
    f.runtime.state(10) // Written, but delivery never started.
    f.result(f.runtime.destroy())
    f.taken() shouldBe Vector("a.setup", "a.onDestroy(9)")
  }

  it should "pass the admitted state when the first delivery was rejected" in {
    val rejecting = Some(new ExtensionRuntime.Checks[Future, Int, String] {
      def state(value: Int): Future[Unit]      = Future.failed(new SessionAccessDenied)
      def message(value: String): Future[Unit] = Future.unit
    })
    val f = new ExtensionRuntimeFixture(rejecting)(f => List(f.recorder("a")))
    f.runtime.state(8)
    f.attach(admitted = 3)
    // The check rejects the attach state, so onState is not called.
    f.taken() shouldBe Vector("a.setup")
    f.result(f.runtime.destroy())
    // The rejected state 8 is never handed to the extension.
    f.taken() shouldBe Vector("a.onDestroy(3)")
    f.errors shouldBe empty
  }

  it should "skip the attach state when teardown begins before the lane ran" in {
    val f = new ExtensionRuntimeFixture()(f => List(f.recorder("a")))
    f.attach(admitted = 2)
    val done = f.runtime.destroy()
    f.taken() shouldBe Vector("a.setup", "a.onDestroy(2)")
    f.result(done)
  }

  it should "keep destroying when one onDestroy fails and another never completes" in {
    val never = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(
        f.recorder("a"),
        f.recorder("b", onDestroy = _ => Future.failed(failure)),
        f.recorder("c", onDestroy = _ => never.future)
      )
    )
    f.attach()
    f.taken()
    val done = f.runtime.destroy()
    f.taken() shouldBe Vector("c.onDestroy(0)")
    f.bound.expire()
    f.taken() shouldBe Vector("b.onDestroy(0)", "a.onDestroy(0)")
    f.result(done)
    f.errors.map(_._1) shouldBe Vector("Extension c: onDestroy failed", "Extension b: onDestroy failed")
    f.errors.head._2 shouldBe a[ExtensionRuntime.TimedOut]
  }

  it should "report an onDestroy that fails with a denied access" in {
    val denied = new SessionAccessDenied
    val f      = new ExtensionRuntimeFixture()(f => List(f.recorder("a", onDestroy = _ => Future.failed(denied))))
    f.attach()
    f.taken()
    f.result(f.runtime.destroy())
    // Only a callback that was running when the view closed is excused.
    f.errors shouldBe Vector("Extension a: onDestroy failed" -> denied)
  }

  it should "wait for the callback in flight before the first onDestroy" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(f.recorder("a", onState = state => if (state == 1) gate.future else Future.unit))
    )
    f.attach()
    f.runtime.state(1)
    f.taken() shouldBe Vector("a.setup", "a.onState(0)", "a.onState(1)")
    val done = f.runtime.destroy()
    f.taken() shouldBe empty
    gate.success(())
    f.taken() shouldBe Vector("a.onDestroy(1)")
    f.result(done)
  }

  it should "report a callback that outlasts the wait, and destroy anyway" in {
    val never = Promise[Unit]()
    val f     = new ExtensionRuntimeFixture()(f => List(f.recorder("a", onState = _ => never.future)))
    f.attach()
    f.taken() shouldBe Vector("a.setup", "a.onState(0)")
    val done = f.runtime.destroy()
    f.taken() shouldBe empty
    f.bound.expire()
    f.taken() shouldBe Vector("a.onDestroy(0)")
    f.result(done)
    f.errors.map(_._1) shouldBe Vector("An extension callback was still running at teardown")
  }
}
