package spoonbill

import java.util.concurrent.atomic.AtomicInteger

import spoonbill.effect.{Queue, Reporter, Scheduler}
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.state.StateStorage
import spoonbill.state.javaSerialization.*
import spoonbill.testExecution.*
import avocet.{Id, StatefulRenderContext, XmlNs}
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration.*

class Issue14Spec extends AnyFlatSpec with Matchers with Eventually {

  import Issue14Spec.context._
  import Reporter.PrintReporter.Implicit

  "Spoonbill" should "ignore events from outdated DOM" in {

    val counter = new AtomicInteger(0)

    val incomingMessages = Queue[Future, String]()
    val frontend         = new Frontend[Future](incomingMessages.stream, None)
    val stateManager     = new StateStorage.SimpleInMemoryStateManager[Future]()
    val app = new ApplicationInstance[Future, Issue14Spec.S, Any](
      sessionId = Qsid("", ""),
      frontend = frontend,
      rootPath = Root,
      router = Router.empty[Future, String],
      render = {
        Issue14Spec.render(
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
          )
        )
      },
      stateManager = stateManager,
      initialState = "firstState",
      reporter = Reporter.PrintReporter,
      scheduler = new Scheduler[Future](),
      createMiscProxy = (rc, k) =>
        new StatefulRenderContext[Context.Binding[Future, Issue14Spec.S, Any]] { proxy =>
          def currentContainerId: Id                                           = rc.currentContainerId
          def currentId: Id                                                    = rc.currentId
          def subsequentId: Id                                                 = rc.subsequentId
          def openNode(xmlns: XmlNs, name: String): Unit                       = rc.openNode(xmlns, name)
          def closeNode(name: String): Unit                                    = rc.closeNode(name)
          def setAttr(xmlNs: XmlNs, name: String, value: String): Unit         = rc.setAttr(xmlNs, name, value)
          def setStyle(name: String, value: String): Unit                      = rc.setStyle(name, value)
          def addTextNode(text: String): Unit                                  = rc.addTextNode(text)
          def addMisc(misc: Context.Binding[Future, Issue14Spec.S, Any]): Unit = k(rc, misc)
        },
      recovery = PartialFunction.empty,
      delayedRender = 0.seconds
    )

    def fireEvent(data: String) =
      incomingMessages.offerUnsafe(s"""[0,"$data"]""")

    // Ensure background fibers are started before we push messages into the queue.
    Await.result(app.initialize(), 2.seconds)

    // First click transitions to secondState.
    fireEvent("0:1_2_1:click")

    // Wait until the state transition has been applied and rendered.
    eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
      Await.result(stateManager.read[Issue14Spec.S](Id.TopLevel), 200.millis) shouldBe Some("secondState")
      counter.get shouldBe 1
    }

    // Now fire:
    // - an outdated click event (counter=0) -> ignored
    // - a mousedown event (counter=0) -> handled in secondState
    // - a click event with updated counter (counter=1) -> handled in secondState
    fireEvent("0:1_2_1:click")
    fireEvent("0:1_2_1:mousedown")
    fireEvent("1:1_2_1:click")

    eventually(timeout(Span(2, Seconds)), interval(Span(25, Millis))) {
      counter.get shouldBe 3
    }
  }
}

object Issue14Spec {

  type S = String

  val context = Context[Future, Issue14Spec.S, Any]

  import context._
  import avocet.dsl._
  import avocet.dsl.html._

  def render(firstEvents: Seq[Event], secondEvents: Seq[Event]): Render = {
    case "firstState" =>
      body(
        div("Hello"),
        div(
          button("Click me", firstEvents)
        )
      )
    case "secondState" =>
      body(
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
