package spoonbill.internal

import avocet.{Id, StatefulRenderContext, XmlNs}
import avocet.dsl.*
import avocet.dsl.html.*
import avocet.events.EventPhase
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, ExecutionContext, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.effect.syntax.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*
import spoonbill.web.PathAndQuery

/**
 * A view that a long-polling entry owns resumes: its page keeps its bridge
 * after a lost poll, and so it can be behind the view. Such a view publishes
 * its revision and its handlers together, as a guarded view that recovers does,
 * and runs a handler only for the display that it was published with.
 */
class ResumingViewSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private val page = UUID.fromString("11111111-1111-4111-8111-111111111111")

  /** Passes the bindings of a render on, as the session service does, so that event handlers are registered. */
  private def forwarding[B](rc: StatefulRenderContext[B], k: (StatefulRenderContext[B], B) => Unit): StatefulRenderContext[B] =
    new StatefulRenderContext[B] {
      def currentContainerId: Id                                   = rc.currentContainerId
      def currentId: Id                                            = rc.currentId
      def subsequentId: Id                                         = rc.subsequentId
      def openNode(xmlns: XmlNs, name: String): Unit               = rc.openNode(xmlns, name)
      def closeNode(name: String): Unit                            = rc.closeNode(name)
      def setAttr(xmlNs: XmlNs, name: String, value: String): Unit = rc.setAttr(xmlNs, name, value)
      def setStyle(name: String, value: String): Unit              = rc.setStyle(name, value)
      def addTextNode(text: String): Unit                          = rc.addTextNode(text)
      def addMisc(misc: B): Unit                                   = k(rc, misc)
    }

  /** Records what the view reports, its debug lines too. */
  private final class Recording extends Reporter {
    @volatile var debugs   = Vector.empty[String]
    @volatile var warnings = Vector.empty[String]
    @volatile var errors   = Vector.empty[String]
    def error(message: String, cause: Throwable): Unit                = synchronized(errors :+= message)
    def error(message: String): Unit                                  = synchronized(errors :+= message)
    def warning(message: String, cause: Throwable): Unit              = synchronized(warnings :+= message)
    def warning(message: String): Unit                                = synchronized(warnings :+= message)
    def info(message: String): Unit                                   = ()
    def debug(message: String): Unit                                  = synchronized(debugs :+= message)
    def debug(message: String, arg1: Any): Unit                       = ()
    def debug(message: String, arg1: Any, arg2: Any): Unit            = ()
    def debug(message: String, arg1: Any, arg2: Any, arg3: Any): Unit = ()
    def discarded: Int = debugs.count(_ == "Obsolete browser event discarded")
  }

  /**
   * One view, with a button whose handler names the stage it was rendered for,
   * and a route `/five` to stage 5.
   *
   * @param resumes
   *   whether a long-polling entry owns the view. A control case uses an
   *   ordinary unguarded view.
   */
  private final class View(resumes: Boolean = true) {
    val worker   = new TimerTestSupport.Worker
    val reports  = new Recording
    given ExecutionContext = worker
    given Reporter         = reports
    val incoming = Queue[Future, String]()
    val frontend = new Frontend[Future](
      incoming.stream,
      None,
      connectionId = Some(ConnectionId.fromUuid(page)),
      resumes = resumes
    )
    @volatile var ran    = Vector.empty[String]
    @volatile var frames = Vector.empty[String]
    // The next readiness check takes this gate, so one event can be held there.
    private val gate = new AtomicReference(Option.empty[Promise[Unit]])
    private val context = Context[Future, Int, Unit]
    import context.*
    val app = new ApplicationInstance[Future, Int, Unit](
      Qsid("resuming-view", UUID.randomUUID().toString),
      frontend,
      StateManager.cached[Future](),
      0,
      state =>
        Html(
          body(
            p(s"stage $state"),
            button("Act", event("click") { _ => Future.successful { ran :+= s"stage $state" } })
          )
        ),
      Root,
      Router[Future, Int](
        PartialFunction.empty,
        { case path if path == PathAndQuery.fromString("/five") => _ => Future.successful(5) }
      ),
      (rc, k) => forwarding(rc, k),
      new Scheduler[Future],
      reports,
      PartialFunction.empty,
      Duration.Zero,
      awaitReady = Some(() => gate.getAndSet(None).fold(Future.unit)(_.future))
    )

    def start(): Unit = {
      result(frontend.outgoingMessages.foreach(frame => Effect[Future].delay { frames :+= frame }).start)
      result(app.initialize())
      worker.drain()
      frames = Vector.empty
    }

    /** The id of the button, the target of a click. */
    def target: String =
      app.topLevelComponentInstance.allEventHandlers.keys
        .filter(id => id.`type` == "click" && id.phase == EventPhase.Bubbling)
        .map(_.target.mkString)
        .head

    def send(frame: String): Unit = { result(incoming.enqueue(frame)); worker.drain() }
    def click(revision: Long, counter: Int = 0): Unit =
      send(s"""[7,"$page:$revision:0:$counter:$target:click"]""")
    def plainClick(counter: Int = 0): Unit = send(s"""[0,"$counter:$target:click"]""")
    def change(state: Int): Unit = {
      result(app.topLevelComponentInstance.browserAccess.transition(_ => state))
      worker.drain()
    }

    /** Holds the next event at its readiness check. */
    def hold(): Promise[Unit] = { val held = Promise[Unit](); gate.set(Some(held)); held }

    def close(): Unit = {
      result(frontend.close())
      val done = app.destroy()
      worker.drain()
      result(done)
    }
  }

  "A view that resumes" should "publish its first render as revision 0, with its handlers" in {
    val view = new View
    view.start()
    try {
      view.click(revision = 0L)
      view.ran shouldBe Vector("stage 0")
    } finally view.close()
  }

  it should "discard a click from a page that is behind, and count it" in {
    val view = new View
    view.start()
    try {
      view.change(1)
      view.frames.count(_.startsWith("[4,")) shouldBe 1
      view.click(revision = 0L)
      view.ran shouldBe empty
      view.reports.discarded shouldBe 1
      view.reports.errors shouldBe empty
    } finally view.close()
  }

  it should "discard a held click when a render is published before its handler is selected" in {
    val view = new View
    view.start()
    try {
      // The click is current when it arrives and waits at its readiness check.
      val held = view.hold()
      view.click(revision = 0L)
      view.ran shouldBe empty
      // A render is published meanwhile. Then the handler is selected.
      view.change(1)
      held.success(())
      view.worker.drain()
      view.ran shouldBe empty
      view.reports.discarded shouldBe 1
    } finally view.close()
  }

  it should "run the handler of the new stage after the page has caught up" in {
    val view = new View
    view.start()
    try {
      view.change(1)
      view.click(revision = 1L)
      view.ran shouldBe Vector("stage 1")
      view.reports.discarded shouldBe 0
    } finally view.close()
  }

  it should "discard a custom callback of an older revision, and run one of the current revision" in {
    val view = new View
    view.start()
    try {
      @volatile var calls = Vector.empty[String]
      result(view.frontend.registerCustomCallback("cb")(arg => Future.successful { calls :+= arg }))
      view.change(1)
      view.send(s"""[7,"$page:0:1:cb:old"]""")
      calls shouldBe empty
      view.reports.discarded shouldBe 1
      view.send(s"""[7,"$page:1:1:cb:current"]""")
      calls shouldBe Vector("current")
    } finally view.close()
  }

  it should "handle a history event of an older revision" in {
    val view = new View
    view.start()
    try {
      view.change(1)
      view.send(s"""[7,"$page:0:3:/five"]""")
      // The browser has changed its URL already. The router moves the state on.
      view.frames.exists(_.contains("stage 5")) shouldBe true
    } finally view.close()
  }

  it should "run no handler for a DOM event or a custom callback without the envelope, and report both" in {
    val view = new View
    view.start()
    try {
      @volatile var calls = Vector.empty[String]
      result(view.frontend.registerCustomCallback("cb")(arg => Future.successful { calls :+= arg }))
      view.plainClick()
      view.send("""[1,"cb:plain"]""")
      view.ran shouldBe empty
      calls shouldBe empty
      view.reports.warnings.count(_.contains("without a view revision")) shouldBe 2
      // The view goes on: an event with the envelope still runs.
      view.click(revision = 0L)
      view.ran shouldBe Vector("stage 0")
    } finally view.close()
  }

  it should "run no handler for a revision that it never published" in {
    val view = new View
    view.start()
    try {
      view.click(revision = 3L)
      view.ran shouldBe empty
    } finally view.close()
  }

  "An unguarded view that does not resume" should "still run a click without the envelope" in {
    val view = new View(resumes = false)
    view.start()
    try {
      view.change(1)
      view.plainClick()
      view.ran shouldBe Vector("stage 1")
      view.reports.warnings shouldBe empty
    } finally view.close()
  }
}
