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

  // Ten seconds of real time, as above. The guarded bound once failed with a
  // denied access, which the report filter dropped.
  "A guarded session" should "report an onDestroy that never completes" in {
    val log  = new Log
    val view = new View(Mode.Retained, List(recorder(log, "never", onDestroy = (_, _) => Promise[Unit]().future)))
    view.attach()
    // The release waits for the teardown, so it is not awaited here.
    view.attached.release()
    eventually(log.of("never").last shouldBe s"never.onDestroy(${view.admitted})")
    eventually(timeout(Span(20, Seconds)), interval(Span(100, Millis))) {
      view.errors.collect { case ("Extension never: onDestroy failed", error) => error.getClass } shouldBe
        Vector(classOf[ExtensionRuntime.TimedOut])
    }
    eventually(view.registered shouldBe false)
  }

  guarded.foreach(deniedDelivery)

  // After a failed attach nothing has reached the browser. The next attach
  // must start from the state the page was rendered with.
  "A legacy session" should "restore the rendered state when a setup fails after another setup wrote" in {
    val log = new Log
    val view = new View(
      Mode.Legacy,
      List(
        recorder(log, "writer", onSetup = _.transitionForce(_ => "written")),
        recorder(log, "failing", onSetup = _ => Future.failed(failure))
      )
    )
    view.tryAttach() shouldBe Failure(failure)
    eventually(view.registered shouldBe false)
    view.storedState shouldBe Some("initial")
  }

  // A reload would rebuild the state and fail in the same way, without an end.
  it should "fail the attach, and not ask for a reload, when a setup fails after the state was rebuilt" in {
    val log = new Log
    val view =
      new View(Mode.Legacy, List(recorder(log, "failing", onSetup = _ => Future.failed(failure))), rendered = false)
    view.tryAttach() shouldBe Failure(failure)
    view.errors.map(_._1) should contain("Extension failing: setup failed")
    view.errors.map(_._1).exists(_.startsWith("Unable to rebuild state")) shouldBe false
  }

  it should "attach a view whose state was rebuilt" in {
    val log  = new Log
    val view = new View(Mode.Legacy, List(recorder(log, "a")), rendered = false).attach()
    eventually(log.of("a") shouldBe Vector("a.setup", "a.onState(initial)"))
    view.release()
  }

  private def guarded: List[Mode] = Mode.values.toList.filterNot(_ == Mode.Legacy)

  /** The guard rejects a delivery. The check itself must deny the view. */
  private def deniedDelivery(mode: Mode): Unit = {

    mode.label should "deny the view when the check rejects a delivery, and call no handler" in {
      val log  = new Log
      val view = new View(mode, List(recorder(log, "a"))).attach()
      view.transition("s1")
      eventually(log.of("a").last shouldBe "a.onState(s1)")
      // A publish is checked twice: when it is made, and before it is
      // delivered. The first check passes, so the second one is the lane's.
      view.revokeAfter(1)
      Try(view.publish("refused"))
      eventually(log.of("a").last shouldBe "a.onDestroy(s1)")
      log.all.exists(_.contains("onMessage")) shouldBe false
      eventually(view.registered shouldBe false)
      // A denied access is the expected outcome, not an error.
      view.errors.filter(_._1.startsWith("Extension")) shouldBe empty
    }

    // A session with snapshots keeps its state in memory, where no read fails.
    if (mode != Mode.Snapshots)
      it should "deny the view, and report it, when the check before a delivery cannot read the state" in {
        val log  = new Log
        val view = new View(mode, List(recorder(log, "a")), limitedReads = true).attach()
        eventually(log.of("a") shouldBe Vector("a.setup", s"a.onState(${view.admitted})"))
        // A publish reads the state for its own check. The next read is the
        // one of the delivery check, and it fails.
        view.failReadsAfter(1)
        Try(view.publish("unread"))
        eventually(log.of("a").last shouldBe s"a.onDestroy(${view.admitted})")
        log.all.exists(_.contains("onMessage")) shouldBe false
        eventually(view.registered shouldBe false)
        // The view does not go on without delivery, and the reason is on record.
        view.errors should contain("Extension delivery was stopped: its check failed" -> unreadable)
      }

    it should "deny the view when the check rejects the attach state, and pass the admitted state to onDestroy" in {
      val log             = new Log
      lazy val view: View = new View(mode, List(recorder(log, "a", onSetup = _ => Future.successful(view.revoke()))))
      // The attach itself can still complete: the rejection comes with the first delivery.
      view.tryAttach()
      eventually(log.all shouldBe Vector("a.setup", s"a.onDestroy(${view.admitted})"))
      eventually(view.registered shouldBe false)
    }
  }

  private def contract(mode: Mode): Unit = {

    mode.label should "deliver the state at attach first, then every write in order" in {
      val log  = new Log
      val view = new View(mode, List(recorder(log, "a"))).attach()
      eventually(log.of("a") shouldBe Vector("a.setup", s"a.onState(${view.admitted})"))
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
          Vector(s"observer.onState(${view.admitted})", "observer.onState(s1)", "observer.onState(s2)")
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
          Vector("c", "b", "a").map(name => s"$name.onDestroy(${view.admitted})")
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
      // No state was delivered, so onDestroy gets the admitted state.
      eventually(log.all shouldBe Vector("a.setup", "b.setup", s"a.onDestroy(${view.admitted})"))
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
      eventually(log.all shouldBe Vector("a.setup", s"a.onDestroy(${view.admitted})"))
      view.registered shouldBe false
    }

    it should "fail the attach and forget the view when the connection closes during the last setup" in {
      val log             = new Log
      lazy val view: View = new View(mode, List(recorder(log, "a", onSetup = _ => view.disconnect())))
      view.tryAttach().isFailure shouldBe true
      // Delivery never starts: the only callback after setup is onDestroy.
      eventually(log.all shouldBe Vector("a.setup", s"a.onDestroy(${view.admitted})"))
      eventually(view.registered shouldBe false)
      // The view stays forgotten. A retired view must not come back.
      Thread.sleep(200)
      view.registered shouldBe false
    }

    it should "not call an extension again after its view was released" in {
      val log  = new Log
      val view = new View(mode, List(recorder(log, "a")))
      view.attach()
      val access = view.access
      view.release()
      eventually(log.of("a").last shouldBe s"a.onDestroy(${view.admitted})")
      // A late write of the retired view is refused, and no callback follows.
      Try(await(access.transition(_ => "late")))
      Try(await(access.publish("late")))
      log.of("a").last shouldBe s"a.onDestroy(${view.admitted})"
    }
  }
}
