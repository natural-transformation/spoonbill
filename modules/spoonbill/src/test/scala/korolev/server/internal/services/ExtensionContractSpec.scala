package spoonbill.server.internal.services

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration.*
import scala.util.{Failure, Success, Try}
import spoonbill.internal.ExtensionRuntime
import spoonbill.server.SessionAccessDenied
import spoonbill.testExecution.defaultExecutor

/**
 * The extension contract through a real session service. Every case runs in a
 * legacy session and in three kinds of guarded session: one that retains its
 * state, one that reconstructs it, and one with snapshots.
 */
class ExtensionContractSpec extends AnyFlatSpec with Matchers with Eventually {

  import ExtensionContractFixture.*

  private given PatienceConfig = PatienceConfig(timeout = Span(3, Seconds), interval = Span(10, Millis))

  private val failure = new IllegalStateException("synthetic")

  Mode.values.foreach(contract)

  // Ten seconds of real time. A guarded session shares the bound and had it before.
  "A legacy session" should "give up on a setup that never completes" in {
    val log = new Log
    val view = new View(
      Mode.Legacy,
      List(recorder(log, "a"), recorder(log, "never", onSetup = _ => Promise[Unit]().future), recorder(log, "c"))
    )
    val started = System.nanoTime()
    view.tryAttach(limit = 20.seconds).failed.get shouldBe a[ExtensionRuntime.TimedOut]
    (System.nanoTime() - started).nanos should be >= 9.seconds
    // What the first extension acquired is released. The third one never starts.
    eventually(log.all shouldBe Vector("a.setup", "never.setup", "a.onDestroy(initial)"))
    view.errors.map(_._1) should contain("Extension never: setup failed")
    view.registered shouldBe false
  }

  private def contract(mode: Mode): Unit = {

    mode.label should "deliver the state at attach first, then every write in order" in {
      val log  = new Log
      val view = new View(mode, List(recorder(log, "a"))).attach()
      eventually(log.of("a") shouldBe Vector("a.setup", "a.onState(initial)"))
      view.transition("s1")
      view.transition("s2")
      eventually(log.of("a").drop(2) shouldBe Vector("a.onState(s1)", "a.onState(s2)"))
      view.release()
    }

    it should "fold the states written during setup into the newest one, and keep every message" in {
      val log = new Log
      // transitionForce returns after the write was announced, so both writes
      // precede the start of delivery.
      def request(job: String, message: String)(access: Access): Future[Unit] =
        access.transitionForce(_ => job).flatMap(_ => access.publish(message))
      val view = new View(
        mode,
        List(
          recorder(log, "observer"),
          recorder(log, "a", onSetup = request("jobA", "from a")),
          recorder(log, "b", onSetup = request("jobB", "from b"))
        )
      ).attach()
      eventually {
        log.of("observer") shouldBe Vector(
          "observer.setup",
          "observer.onState(jobB)",
          "observer.onMessage(from a)",
          "observer.onMessage(from b)"
        )
      }
      view.release()
    }

    it should "report a failing handler and keep delivering to every extension" in {
      val log = new Log
      val view = new View(
        mode,
        List(
          recorder(
            log,
            "failing",
            onState = state => if (state == "s1") Future.failed(failure) else Future.unit,
            onMessage = message => if (message == "bad") throw failure else Future.unit
          ),
          recorder(log, "observer")
        )
      ).attach()
      view.transition("s1")
      view.publish("bad")
      view.transition("s2")
      view.publish("good")
      eventually {
        log.of("observer").filter(_.contains("onState")) shouldBe
          Vector("observer.onState(initial)", "observer.onState(s1)", "observer.onState(s2)")
        log.of("observer").filter(_.contains("onMessage")) shouldBe
          Vector("observer.onMessage(bad)", "observer.onMessage(good)")
      }
      view.errors shouldBe Vector(
        "Extension failing: onState failed"   -> failure,
        "Extension failing: onMessage failed" -> failure
      )
      view.registered shouldBe true
      view.release()
    }

    it should "close the view before onDestroy, and pass the last delivered state" in {
      val log       = new Log
      val readState = Promise[Try[String]]()
      val view = new View(
        mode,
        List(
          recorder(
            log,
            "a",
            onDestroy = (access, _) =>
              access.state.transform { result =>
                readState.success(result)
                Success(())
              }
          )
        )
      ).attach()
      view.transition("last")
      eventually(log.of("a").last shouldBe "a.onState(last)")
      view.release()
      eventually(log.of("a").last shouldBe "a.onDestroy(last)")
      await(readState.future) match {
        case Failure(error) => error shouldBe a[SessionAccessDenied]
        case Success(state) => fail(s"The access still worked inside onDestroy and returned $state")
      }
    }

    it should "destroy the extensions once, in reverse setup order" in {
      val log  = new Log
      val view = new View(mode, List(recorder(log, "a"), recorder(log, "b"), recorder(log, "c"))).attach()
      view.release()
      eventually {
        log.all.filter(_.contains("onDestroy")) shouldBe
          Vector("c.onDestroy(initial)", "b.onDestroy(initial)", "a.onDestroy(initial)")
      }
      view.registered shouldBe false
    }

    it should "fail the attach when a setup fails, and destroy the extensions before it with a closed view" in {
      val log       = new Log
      val readState = Promise[Try[String]]()
      val view = new View(
        mode,
        List(
          recorder(
            log,
            "a",
            onDestroy = (access, _) =>
              access.state.transform { result =>
                readState.success(result)
                Success(())
              }
          ),
          recorder(log, "b", onSetup = _ => Future.failed(failure)),
          recorder(log, "c")
        )
      )
      view.tryAttach() shouldBe Failure(failure)
      eventually(log.all shouldBe Vector("a.setup", "b.setup", "a.onDestroy(initial)"))
      await(readState.future).failed.get shouldBe a[SessionAccessDenied]
      view.errors.map(_._1) should contain("Extension b: setup failed")
      view.registered shouldBe false
    }

    it should "not set up the remaining extensions when the connection closes during setup" in {
      val log = new Log
      lazy val view: View = new View(
        mode,
        List(recorder(log, "a", onSetup = _ => view.disconnect()), recorder(log, "b"))
      )
      view.tryAttach().isFailure shouldBe true
      eventually(log.all shouldBe Vector("a.setup", "a.onDestroy(initial)"))
      view.registered shouldBe false
    }

    it should "not call an extension again after its view was released" in {
      val log  = new Log
      val view = new View(mode, List(recorder(log, "a")))
      view.attach()
      val access = view.access
      view.release()
      eventually(log.of("a").last shouldBe "a.onDestroy(initial)")
      // A late write of the retired view is refused, and no callback follows.
      Try(await(access.transition(_ => "late")))
      Try(await(access.publish("late")))
      log.of("a").last shouldBe "a.onDestroy(initial)"
    }
  }
}
