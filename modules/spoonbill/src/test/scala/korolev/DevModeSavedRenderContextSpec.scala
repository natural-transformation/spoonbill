package spoonbill

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import spoonbill.effect.{Queue, Reporter, Scheduler}
import spoonbill.effect.syntax.*
import spoonbill.internal.{ApplicationInstance, DevMode, Frontend}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.*
import avocet.{Id, StatefulRenderContext, XmlNs}
import avocet.impl.DiffRenderContext
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * Regression coverage for Spoonbill's dev-mode startup path:
 *
 * If `spoonbill.dev=true` and a saved Avocet render-context exists for the session,
 * Spoonbill must:
 * - load that render-context
 * - diff the current render against it
 * - still start all internal streams so subsequent events/state updates work
 *
 * This path is not covered by the upstream `Issue14Spec` (which exercises the
 * "pre-rendered page" init path).
 */
class DevModeSavedRenderContextSpec extends AnyFlatSpec with Matchers with Eventually {

  import DevModeSavedRenderContextSpec.context._
  import Reporter.PrintReporter.Implicit

  // Development mode is a switch of the whole JVM, and suites run in parallel.
  // The fixture lets one suite at a time switch it: two suites that save and
  // restore the flag at the same time can leave it switched on for good.
  private def exclusively(test: => Unit): Unit =
    SystemPropertyFixture.withSystemProperty("spoonbill.dev", "true")(test)

  "Saved render contexts" should "restore after a smaller snapshot replaces a larger file" in exclusively {
    val devModeKey = "spoonbill.dev"
    val previous = sys.props.get(devModeKey)
    sys.props.update(devModeKey, "true")
    val devMode = new DevMode.ForRenderContext(UUID.randomUUID().toString)

    def render(text: String, rc: DiffRenderContext[Nothing]): Unit = {
      rc.openNode(XmlNs.html, "div")
      rc.addTextNode(text)
      rc.closeNode("div")
      rc.finalizeDocument()
    }

    try {
      val large = DiffRenderContext[Nothing]()
      render("large" * 1000, large)
      devMode.saveRenderContext(large)
      val largeLength = devMode.file.length()

      val small = DiffRenderContext[Nothing]()
      render("small", small)
      devMode.saveRenderContext(small)
      devMode.file.length() shouldBe small.save().remaining().toLong
      devMode.file.length() should be < largeLength

      val restored = DiffRenderContext[Nothing](savedBuffer = devMode.loadRenderContext())
      restored.swap()
      restored.reset()
      render("small", restored)
      val changes = new spoonbill.internal.RemoteDomChangesPerformer
      restored.diff(changes)
      changes.buffer.toString shouldBe ""
    } finally {
      devMode.file.delete()
      previous match {
        case Some(value) => sys.props.update(devModeKey, value)
        case None        => sys.props.remove(devModeKey)
      }
    }
  }

  "Spoonbill dev mode" should "initialize from a saved render context and still process DOM events after a state transition" in exclusively {
    val devModeKey = "spoonbill.dev"
    val prevDev    = sys.props.get(devModeKey)

    // Enable dev mode for this test only (see spoonbill.internal.DevMode).
    sys.props.update(devModeKey, "true")

    // Use a unique session id so we don't collide with other tests/dev runs.
    val qsid = Qsid("devmode", UUID.randomUUID().toString)

    // Write a valid saved Avocet render-context file for this session.
    // The content doesn't have to match the current render; it just needs to be a well-formed buffer
    // produced by `DiffRenderContext.save()`.
    val savedFile =
      try {
        val devMode = new DevMode.ForRenderContext(qsid.toString)
        val file    = devMode.file

        val rc  = DiffRenderContext[Nothing]()
        import avocet.dsl._
        import avocet.dsl.html._
        val doc = body(div("saved"))
        doc(rc)
        rc.finalizeDocument()

        devMode.saveRenderContext(rc)

        // Sanity-check: make sure we will actually exercise the devMode.saved path.
        devMode.saved shouldBe true

        file
      } catch {
        case t: Throwable =>
          // Restore property before failing the test.
          prevDev match {
            case Some(v) => sys.props.update(devModeKey, v)
            case None    => sys.props.remove(devModeKey)
          }
          throw t
      }

    try {
      val counter = new AtomicInteger(0)

      val incomingMessages = Queue[Future, String]()
      val frontend         = new Frontend[Future](incomingMessages.stream, None)
      val stateManager     = new StateStorage.SimpleInMemoryStateManager[Future]()
      val clock            = new spoonbill.internal.TimerTestSupport.Deadlines
      val timerCalls       = new AtomicInteger(0)
      val startupTimer = timer
        .after(timerId("saved-startup"), spoonbill.timer.DelayDuration.from(1.second).toOption.get)
        .update(spoonbill.action.StateUpdate.Modify[String] { state =>
          timerCalls.incrementAndGet(); state
        })

      val app = new ApplicationInstance[Future, DevModeSavedRenderContextSpec.S, Any](
        sessionId = qsid,
        frontend = frontend,
        rootPath = Root,
        router = Router.empty[Future, String],
        render = {
          DevModeSavedRenderContextSpec.render(
            firstEvents = Seq(
              event("click") { access =>
                access.transition { _ =>
                  counter.incrementAndGet()
                  "secondState"
                }
              },
              event("mousedown") { access =>
                counter.incrementAndGet()
                Future.unit
              }
            ),
            secondEvents = Seq(
              event("click") { access =>
                access.transition { _ =>
                  counter.incrementAndGet()
                  "firstState"
                }
              },
              event("mousedown") { access =>
                counter.incrementAndGet()
                Future.unit
              }
            ),
            timers = Seq(startupTimer)
          )
        },
        stateManager = stateManager,
        initialState = "firstState",
        reporter = Reporter.PrintReporter,
        scheduler = new Scheduler[Future](),
        createMiscProxy = (rc, k) =>
          new StatefulRenderContext[Context.Binding[Future, DevModeSavedRenderContextSpec.S, Any]] {
            def currentContainerId: Id                                           = rc.currentContainerId
            def currentId: Id                                                    = rc.currentId
            def subsequentId: Id                                                 = rc.subsequentId
            def openNode(xmlns: XmlNs, name: String): Unit                       = rc.openNode(xmlns, name)
            def closeNode(name: String): Unit                                    = rc.closeNode(name)
            def setAttr(xmlNs: XmlNs, name: String, value: String): Unit         = rc.setAttr(xmlNs, name, value)
            def setStyle(name: String, value: String): Unit                      = rc.setStyle(name, value)
            def addTextNode(text: String): Unit                                  = rc.addTextNode(text)
            def addMisc(misc: Context.Binding[Future, DevModeSavedRenderContextSpec.S, Any]): Unit = k(rc, misc)
          },
        recovery = PartialFunction.empty,
        delayedRender = 0.seconds,
        timerDeadlines = Some(clock),
        timerClock = () => clock.now
      )

      def fireEvent(data: String) =
        incomingMessages.offerUnsafe(s"""[0,"$data"]""")

      Await.result(app.initialize(), 3.seconds)
      clock.active.size shouldBe 1

      // Derive the click target id from the handler registry so the test is robust across
      // minor internal id allocation changes.
      val targetId =
        app.topLevelComponentInstance.allEventHandlers.keys
          .collectFirst {
            case eid if eid.`type` == "click" && eid.phase == avocet.events.EventPhase.Bubbling =>
              eid.target.mkString
          }
          .getOrElse(fail("Expected a click handler to be registered after initialize()"))

      // First click transitions to secondState.
      fireEvent(s"0:$targetId:click")

      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        Await.result(stateManager.read[DevModeSavedRenderContextSpec.S](Id.TopLevel), 200.millis) shouldBe Some("secondState")
        counter.get shouldBe 1
      }

      // Now validate outdated DOM behaviour.
      fireEvent(s"0:$targetId:click")     // outdated click -> ignored
      fireEvent(s"0:$targetId:mousedown") // mousedown -> processed
      fireEvent(s"1:$targetId:click")     // click with new counter -> processed

      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        counter.get shouldBe 3
      }
      clock.tasks.size shouldBe 1
      clock.advance(1.second)
      clock.fire()
      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        timerCalls.get() shouldBe 1
      }
      Await.result(frontend.close(), 3.seconds)
      Await.result(app.destroy(), 3.seconds)
    } finally {
      // Best-effort cleanup to avoid leaving dev-mode artifacts around.
      try savedFile.delete()
      catch { case _: Throwable => () }

      prevDev match {
        case Some(v) => sys.props.update(devModeKey, v)
        case None    => sys.props.remove(devModeKey)
      }
    }
  }

  it should "publish its handlers from a saved render context in a view that resumes" in exclusively {
    // A view that a long-polling entry owns runs a handler only for the display
    // that it was published with. Its start from a saved render context sends
    // a DOM frame; the click of that display must find the handlers of that
    // frame, and a click of the display before it runs nothing.
    val qsid = Qsid("devmode-resuming", UUID.randomUUID().toString)
    val devMode = new DevMode.ForRenderContext(qsid.toString)
    val saved = {
      val rc = DiffRenderContext[Nothing]()
      import avocet.dsl._
      import avocet.dsl.html._
      body(div("saved"))(rc)
      rc.finalizeDocument()
      devMode.saveRenderContext(rc)
      devMode.saved shouldBe true
      devMode.file
    }
    try {
      val page     = UUID.fromString("11111111-1111-4111-8111-111111111111")
      val counter  = new AtomicInteger(0)
      val incoming = Queue[Future, String]()
      // The view says when it drops a click of an older display. Both clicks
      // carry event counter 0, so a first click that ran would make the
      // second one a repeat, and the count of handler runs alone could not
      // tell the two apart.
      val discarded = new AtomicInteger(0)
      val recording = new Reporter {
        def error(message: String, cause: Throwable): Unit                 = ()
        def error(message: String): Unit                                   = ()
        def warning(message: String, cause: Throwable): Unit               = ()
        def warning(message: String): Unit                                 = ()
        def info(message: String): Unit                                    = ()
        def debug(message: String): Unit                                   =
          if (message == "Obsolete browser event discarded") { discarded.incrementAndGet(); () }
        def debug(message: String, arg1: Any): Unit                        = ()
        def debug(message: String, arg1: Any, arg2: Any): Unit             = ()
        def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
      }
      val frontend = {
        given Reporter = recording
        new Frontend[Future](
          incoming.stream,
          None,
          connectionId = Some(spoonbill.security.Identifiers.ConnectionId.fromUuid(page)),
          resumes = true
        )
      }
      val frames       = new java.util.concurrent.ConcurrentLinkedQueue[String]()
      val stateManager = new StateStorage.SimpleInMemoryStateManager[Future]()
      val app = new ApplicationInstance[Future, DevModeSavedRenderContextSpec.S, Any](
        sessionId = qsid,
        frontend = frontend,
        rootPath = Root,
        router = Router.empty[Future, String],
        render = DevModeSavedRenderContextSpec.render(
          firstEvents = Seq(event("click") { access => access.transition { _ => counter.incrementAndGet(); "secondState" } }),
          secondEvents = Seq()
        ),
        stateManager = stateManager,
        initialState = "firstState",
        reporter = Reporter.PrintReporter,
        scheduler = new Scheduler[Future](),
        createMiscProxy = (rc, k) =>
          new StatefulRenderContext[Context.Binding[Future, DevModeSavedRenderContextSpec.S, Any]] {
            def currentContainerId: Id                                   = rc.currentContainerId
            def currentId: Id                                            = rc.currentId
            def subsequentId: Id                                         = rc.subsequentId
            def openNode(xmlns: XmlNs, name: String): Unit               = rc.openNode(xmlns, name)
            def closeNode(name: String): Unit                            = rc.closeNode(name)
            def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = rc.setAttr(xmlNs, name, value)
            def setStyle(name: String, value: String): Unit              = rc.setStyle(name, value)
            def addTextNode(text: String): Unit                          = rc.addTextNode(text)
            def addMisc(misc: Context.Binding[Future, DevModeSavedRenderContextSpec.S, Any]): Unit = k(rc, misc)
          },
        recovery = PartialFunction.empty,
        delayedRender = 0.seconds
      )
      Await.result(frontend.outgoingMessages.foreach(frame => Future.successful { frames.add(frame); () }).start, 3.seconds)
      Await.result(app.initialize(), 3.seconds)
      val targetId =
        app.topLevelComponentInstance.allEventHandlers.keys
          .collectFirst {
            case eid if eid.`type` == "click" && eid.phase == avocet.events.EventPhase.Bubbling => eid.target.mkString
          }
          .getOrElse(fail("Expected a click handler to be registered after initialize()"))
      // The start sent the difference to the saved render context as one DOM frame.
      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        frames.toArray.count(_.toString.startsWith("[4")) shouldBe 1
      }
      // A click of the display that the page had before that frame runs nothing.
      incoming.offerUnsafe(s"""[7,"$page:0:0:0:$targetId:click"]""")
      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        discarded.get shouldBe 1
      }
      counter.get shouldBe 0
      // A click of the display after it runs the handler of that display.
      incoming.offerUnsafe(s"""[7,"$page:1:0:0:$targetId:click"]""")
      eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
        Await.result(stateManager.read[DevModeSavedRenderContextSpec.S](Id.TopLevel), 200.millis) shouldBe Some("secondState")
        counter.get shouldBe 1
      }
      Await.result(frontend.close(), 3.seconds)
      Await.result(app.destroy(), 3.seconds)
    } finally {
      try saved.delete()
      catch { case _: Throwable => () }
    }
  }
}

object DevModeSavedRenderContextSpec {

  type S = String

  val context = Context[Future, DevModeSavedRenderContextSpec.S, Any]

  import context._
  import avocet.dsl._
  import avocet.dsl.html._

  def render(firstEvents: Seq[Event], secondEvents: Seq[Event], timers: Seq[Binding] = Nil): Render = {
    case "firstState" =>
      body(
        timers,
        div("Hello"),
        div(
          button("Click me", firstEvents)
        )
      )
    case "secondState" =>
      body(
        timers,
        div("Hello"),
        ul(
          li("One", secondEvents),
          li("Two"),
          li("Three")
        ),
        div("Cow")
      )
  }
}

