package spoonbill.server.internal.services

import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import scala.concurrent.Future
import spoonbill.testExecution.defaultExecutor

/**
 * An extension that is written for a part of the state, inside an application
 * with a larger state. The state here is "page:draft"; the child extension
 * works on the draft.
 */
class ExtensionImapStateSpec extends AnyFlatSpec with Matchers with Eventually {

  import ExtensionContractFixture.*

  private given PatienceConfig = PatienceConfig(timeout = Span(3, Seconds), interval = Span(10, Millis))

  private def read(state: String): String                = state.split(":", 2)(1)
  private def write(state: String, part: String): String = state.split(":", 2)(0) + ":" + part

  Mode.values.foreach { mode =>
    s"imapState in ${mode.label.toLowerCase}" should "show the child its part, and write only that part" in {
      val log                 = new Log
      var childAccess: Access = null
      val child               = recorder(log, "child", onSetup = access => { childAccess = access; Future.unit })
      val view                = new View(mode, List(child.imapState[String](read, write)), initial = "home:empty").attach()
      eventually(log.of("child") shouldBe Vector("child.setup", "child.onState(empty)"))

      await(childAccess.state) shouldBe "empty"
      await(childAccess.transition(_ => "typed"))
      eventually(log.of("child").last shouldBe "child.onState(typed)")
      // The page part is untouched.
      await(view.access.state) shouldBe "home:typed"

      view.transition("about:typed")
      eventually(await(view.access.state) shouldBe "about:typed")
      view.release()
      eventually(log.of("child").last shouldBe "child.onDestroy(typed)")
    }
  }

  "imapState" should "keep the name of the extension" in {
    recorder(new Log, "child").imapState[String](read, write).name shouldBe "child"
  }

  it should "compose when it is applied twice" in {
    val log                 = new Log
    var childAccess: Access = null
    val child               = recorder(log, "child", onSetup = access => { childAccess = access; Future.unit })
    // "site:page:draft": the first step focuses on "page:draft", the second on "draft".
    val nested = child.imapState[String](read, write).imapState[String](read, write)
    val view   = new View(Mode.Legacy, List(nested), initial = "site:home:empty").attach()
    eventually(log.of("child") shouldBe Vector("child.setup", "child.onState(empty)"))
    await(childAccess.transition(_ => "typed"))
    eventually(await(view.access.state) shouldBe "site:home:typed")
    view.release()
    eventually(log.of("child").last shouldBe "child.onDestroy(typed)")
  }

  it should "leave setup failures to the runtime" in {
    val log     = new Log
    val failure = new IllegalStateException("synthetic")
    val child   = recorder(log, "child", onSetup = _ => Future.failed(failure))
    val view    = new View(Mode.Legacy, List(recorder(log, "first"), child.imapState[String](read, write)), "home:empty")
    view.tryAttach().failed.get shouldBe failure
    eventually(log.all shouldBe Vector("first.setup", "child.setup", "first.onDestroy(home:empty)"))
    view.errors.map(_._1) should contain("Extension child: setup failed")
  }
}
