package spoonbill.action

import _root_.cats.effect.{Deferred, IO, IOLocal}
import _root_.cats.effect.syntax.all.*
import _root_.cats.effect.unsafe.implicits.global
import _root_.cats.syntax.all.*
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.Effect

class StateUpdateIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO] = new IO3Effect(global)

  "StateUpdate with IO" should "defer a no-update callback until execution and its acknowledgement" in {
    val callbacks   = new AtomicInteger()
    val transitions = new AtomicInteger()
    val completion = StateUpdateInterpreter.callback[IO, Int] {
      callbacks.incrementAndGet()
      StateUpdate.noUpdate[Int]
    }(_ => IO { transitions.incrementAndGet(); () })
    callbacks.get() shouldBe 0
    transitions.get() shouldBe 0
    val checked = for {
      entered         <- Deferred[IO, Unit]
      acknowledgement <- Deferred[IO, Unit]
      worker          <- (entered.complete(()) *> acknowledgement.get *> completion).start
      _ <- (for {
             _ <- entered.get
             _ <- IO(callbacks.get() shouldBe 0)
             _ <- acknowledgement.complete(())
             _ <- worker.joinWithNever
             _ <- IO {
                    callbacks.get() shouldBe 1
                    transitions.get() shouldBe 0
                  }
           } yield ()).guarantee(worker.cancel)
    } yield ()
    checked.timeout(5.seconds).unsafeRunSync()
  }

  it should "hand off one unevaluated reducer in the caller's local context" in {
    val callbacks   = new AtomicInteger()
    val reducers    = new AtomicInteger()
    val transitions = new AtomicInteger()
    val checked = for {
      local    <- IOLocal("default")
      captured <- Deferred[IO, (String, Int => Int)]
      completion = StateUpdateInterpreter.callback[IO, Int] {
                     callbacks.incrementAndGet()
                     StateUpdate.Modify { state =>
                       reducers.incrementAndGet(); state + 1
                     }
                   } { reduce =>
                     IO(transitions.incrementAndGet()) *> local.get.flatMap(value =>
                       captured.complete(value -> reduce).void
                     )
                   }
      _      <- IO(callbacks.get() shouldBe 0)
      _      <- local.set("request")
      _      <- completion
      result <- captured.get
      _ <- IO {
             callbacks.get() shouldBe 1
             transitions.get() shouldBe 1
             reducers.get() shouldBe 0
             result._1 shouldBe "request"
             result._2(41) shouldBe 42
             reducers.get() shouldBe 1
           }
    } yield ()
    checked.timeout(5.seconds).unsafeRunSync()
  }

  it should "capture thrown callback errors without requesting a transition" in {
    val error       = new IllegalStateException("synthetic callback failure")
    val callbacks   = new AtomicInteger()
    val transitions = new AtomicInteger()
    val completion = StateUpdateInterpreter.callback[IO, Int] {
      callbacks.incrementAndGet()
      throw error
    }(_ => IO { transitions.incrementAndGet(); () })
    callbacks.get() shouldBe 0
    completion.attempt.timeout(5.seconds).unsafeRunSync() shouldBe Left(error)
    callbacks.get() shouldBe 1
    transitions.get() shouldBe 0
  }

  it should "widen direct and nested pure returns in their expected outcome context" in {
    val F                          = Effect[IO]
    val direct: IO[UiOutcome[Int]] = F.pure(StateUpdate.Modify[Int](_ + 1))
    def nested(modify: Boolean): IO[UiOutcome[Int]] =
      if (modify) F.pure(StateUpdate.Modify[Int](_ + 1))
      else F.pure(StateUpdate.noUpdate[Int])
    val helper                      = F.pure(UiOutcome.modify[Int](_ + 1))
    val widened: IO[UiOutcome[Int]] = helper
    (direct, nested(false), widened).tupled.timeout(5.seconds).unsafeRunSync() match {
      case (StateUpdate.Modify(first), StateUpdate.NoUpdate(), StateUpdate.Modify(last)) =>
        first(1) shouldBe 2
        last(2) shouldBe 3
      case _ => fail("Expected direct state-update outcomes")
    }
  }
}
