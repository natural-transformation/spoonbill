package spoonbill.internal

import avocet.{Document, XmlNs}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.effect.syntax.*
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*

class RenderLifecycleSpec extends AnyFlatSpec with Matchers {
  private given Effect[Future] = new Effect.FutureEffect
  private def result[A](value: Future[A]): A = Await.result(value, 3.seconds)

  private class App(recovery: Boolean) {
    val worker = new TimerTestSupport.Worker
    val reports = new TimerTestSupport.Reports
    given scala.concurrent.ExecutionContext = worker
    given Reporter = reports
    val frontend = new Frontend[Future](
      Queue[Future, String]().stream,
      None,
      authorize = if (recovery) Some(() => Future.unit) else None,
      viewRecoveryEpoch = if (recovery) Some(ViewOwnershipEpoch.initial) else None
    )
    var frames = Vector.empty[String]
    val renderFailure = new IllegalStateException("template failed with open nodes")
    val app = new ApplicationInstance[Future, Int, Unit](
      Qsid("render-lifecycle", java.util.UUID.randomUUID().toString),
      frontend,
      StateManager.cached[Future](),
      0,
      state => Document.Node[Context.Binding[Future, Int, Unit]] { rc =>
        rc.openNode(XmlNs.html, "html")
        rc.openNode(XmlNs.html, "body")
        if (state == 1) {
          rc.openNode(XmlNs.html, "div")
          rc.addTextNode("unpublished partial document")
          throw renderFailure
        }
        rc.openNode(XmlNs.html, "span")
        rc.addTextNode(if (state == 0) "initial" else if (state == 10) "accepted" else "recovered")
        rc.closeNode("span")
        rc.closeNode("body")
        rc.closeNode("html")
      },
      Root,
      Router.empty[Future, Int],
      (rc, _) => rc,
      new Scheduler[Future],
      reports,
      PartialFunction.empty,
      Duration.Zero,
      authorizeProduced = if (recovery) Some(_ => Future.unit) else None
    )

    def start(): Unit = {
      result(frontend.outgoingMessages.foreach(frame => Effect[Future].delay { frames :+= frame }).start)
      result(app.initialize())
      worker.drain()
      frames = Vector.empty
    }

    def change(state: Int): Unit = {
      result(app.topLevelComponentInstance.browserAccess.transition(_ => state))
      worker.drain()
    }

    def close(): Unit = {
      result(frontend.close())
      val done = app.destroy()
      worker.drain()
      result(done)
    }
  }

  for (recovery <- Seq(false, true)) {
    "Application rendering" should s"retain the accepted DOM after a partial template failure (view recovery = $recovery)" in {
      val f = new App(recovery)
      f.start()
      try {
        f.change(10)
        f.frames.size shouldBe 1
        f.frames = Vector.empty

        f.change(1)
        f.frames shouldBe empty
        f.reports.errors.map(_._2) shouldBe Vector(f.renderFailure)

        f.change(2)
        f.reports.errors.map(_._2) shouldBe Vector(f.renderFailure)
        val patch = "[4,1,\"1_1_1\",\"1_1_1_1\",\"recovered\" ]"
        if (recovery) {
          f.frames.size shouldBe 1
          f.frames.head should startWith("[20,")
          f.frames.head should endWith("," + patch + "]")
        } else f.frames shouldBe Vector(patch)

        // A later identical document should diff against the recovered render.
        f.frames = Vector.empty
        f.change(3)
        if (recovery) f.frames.head should endWith(",[4 ]]")
        else f.frames shouldBe Vector("[4 ]")
      } finally f.close()
    }
  }

  "DOM frame serialization" should "discard partial output when a diff throws" in {
    val f = new App(recovery = false)
    f.start()
    try {
      val failure = new IllegalStateException("diff failed")
      result(f.frontend.performDomChanges(_ => throw failure).failed) shouldBe failure
      result(f.frontend.performDomChanges(_ => ()))
      f.worker.drain()
      f.frames shouldBe Vector("[4 ]")
    } finally f.close()
  }
}
