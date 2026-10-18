package spoonbill.action

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.effect.Effect

class StateUpdateInterpreterSpec extends AsyncFlatSpec with Matchers:
  private given Effect[Future] = new Effect.FutureEffect

  "StateUpdateInterpreter" should "skip no-update and hand off one unevaluated composed reducer" in {
    var queued                                   = Vector.empty[Int => Int]
    var reductions                               = 0
    val transition: (Int => Int) => Future[Unit] = reduce => { queued :+= reduce; Future.unit }
    val modify = StateUpdate.Modify[Int] { state =>
      reductions += 1; state + 1
    }
    for
      _ <- StateUpdateInterpreter(StateUpdate.noUpdate[Int])(transition)
      _  = queued shouldBe empty
      _ <- StateUpdateInterpreter(modify.andThen(StateUpdate.Modify[Int](_ * 2)))(transition)
    yield
      queued.size shouldBe 1
      reductions shouldBe 0
      queued.head(20) shouldBe 42
      reductions shouldBe 1
  }

  it should "evaluate an eager callback once after its acknowledgement dependency" in {
    val acknowledgement = Promise[Unit]()
    var callbacks       = 0
    var transitions     = 0
    val running = Effect[Future].flatMap(acknowledgement.future) { _ =>
      StateUpdateInterpreter.callback[Future, Int] {
        callbacks += 1
        StateUpdate.Modify(identity)
      } { _ =>
        transitions += 1; Future.unit
      }
    }
    callbacks shouldBe 0
    transitions shouldBe 0
    acknowledgement.success(())
    running.map { _ =>
      callbacks shouldBe 1; transitions shouldBe 1
    }
  }

  it should "capture callback failure without requesting a transition" in {
    val failure     = new IllegalStateException("synthetic callback failure")
    var transitions = 0
    val running = StateUpdateInterpreter.callback[Future, Int](throw failure) { _ =>
      transitions += 1
      Future.unit
    }
    running.failed.map { error =>
      error shouldBe failure; transitions shouldBe 0
    }
  }

  it should "leave reducer failures in the queued execution path" in {
    val failure = new IllegalStateException("synthetic reducer failure")
    var queued  = Option.empty[Int => Int]
    StateUpdateInterpreter(StateUpdate.Modify[Int](_ => throw failure)) { reduce =>
      queued = Some(reduce)
      Future.unit
    }.map(_ => intercept[IllegalStateException](queued.get(1)) shouldBe failure)
  }
