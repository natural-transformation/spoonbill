package spoonbill.internal

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}

class ExtensionLateCompletionSpec extends AnyFlatSpec with Matchers {
  "A callback completing after timed-out teardown" should "preserve each entered state and leave waiting calls discarded" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(
        f.recorder("a", onState = value => if (value == 1) gate.future else Future.unit),
        f.recorder("b")
      )
    )
    f.attach(0)
    f.taken()
    f.runtime.state(1)
    f.taken() shouldBe Vector("a.onState(1)")
    f.runtime.message("waiting")
    f.runtime.state(2)
    val destroyed = f.runtime.destroy()
    f.taken() shouldBe empty
    f.bound.expire()
    f.result(destroyed)
    f.taken() shouldBe Vector("b.onDestroy(0)", "a.onDestroy(1)")
    f.errors.map(_._1) shouldBe Vector("An extension callback was still running at teardown")

    gate.success(())
    f.taken() shouldBe empty
    f.result(f.runtime.destroy())
    f.taken() shouldBe empty
    f.errors.map(_._1) shouldBe Vector("An extension callback was still running at teardown")
  }

  it should "keep the last state when the timed-out callback is a message" in {
    val gate = Promise[Unit]()
    val f = new ExtensionRuntimeFixture()(f =>
      List(
        f.recorder("a", onMessage = _ => gate.future),
        f.recorder("b")
      )
    )
    f.attach(0)
    f.taken()
    f.runtime.state(1)
    f.taken() shouldBe Vector("a.onState(1)", "b.onState(1)")
    f.runtime.message("running")
    f.taken() shouldBe Vector("a.onMessage(running)")
    f.runtime.state(2)
    val destroyed = f.runtime.destroy()
    f.taken() shouldBe empty
    f.bound.expire()
    f.result(destroyed)
    f.taken() shouldBe Vector("b.onDestroy(1)", "a.onDestroy(1)")
    f.errors.map(_._1) shouldBe Vector("An extension callback was still running at teardown")

    gate.success(())
    f.taken() shouldBe empty
    f.result(f.runtime.destroy())
    f.taken() shouldBe empty
    f.errors.map(_._1) shouldBe Vector("An extension callback was still running at teardown")
  }
}
