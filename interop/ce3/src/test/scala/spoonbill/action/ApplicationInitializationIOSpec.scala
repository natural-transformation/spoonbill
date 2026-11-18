package spoonbill.action

import _root_.cats.effect.{Deferred, IO, Outcome}
import _root_.cats.effect.unsafe.implicits.global
import avocet.{Document, XmlNs}
import java.util.concurrent.atomic.AtomicInteger
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.*
import spoonbill.*
import spoonbill.cats.IO3Effect
import spoonbill.effect.{Effect, Queue, Reporter, Scheduler}
import spoonbill.internal.{ApplicationInstance, Frontend}
import spoonbill.server.SessionAccessDenied
import spoonbill.state.StateManager
import spoonbill.state.javaSerialization.*

class ApplicationInitializationIOSpec extends AnyFlatSpec with Matchers {
  private given Effect[IO] = new IO3Effect(global)
  private given ExecutionContext = ExecutionContext.global
  private given Reporter = Reporter.PrintReporter

  "Canceled application startup" should "settle joiners and never restart the canceled initialization" in {
    val program = for {
      entered <- Deferred[IO, Unit]
      release <- Deferred[IO, Unit]
      renders <- IO(new AtomicInteger())
      frontend <- IO(new Frontend[IO](Queue[IO, String]().stream, None,
        authorize = Some(() => entered.complete(()).flatMap(_ => release.get))))
      app <- IO(new ApplicationInstance[IO, Int, Unit](
        Qsid("startup-cancel", java.util.UUID.randomUUID().toString), frontend,
        StateManager.cached[IO](), 0,
        _ => Document.Node[Context.Binding[IO, Int, Unit]] { rc =>
          renders.incrementAndGet()
          rc.openNode(XmlNs.html, "html"); rc.closeNode("html")
        },
        Root, Router.empty[IO, Int], (rc, _) => rc,
        new Scheduler[IO], summon[Reporter], PartialFunction.empty, Duration.Zero))
      _ <- (for {
        first <- app.initialize().start
        _ <- entered.get
        joined <- app.initialize().start
        _ <- first.cancel
        outcome <- joined.join
        _ <- IO {
          outcome match {
            case Outcome.Errored(error) => error shouldBe a[SessionAccessDenied]
            case other => fail(s"Expected joined startup rejection, got $other")
          }
        }
        _ <- release.complete(())
        again <- app.initialize().attempt
        _ <- IO {
          again.left.toOption.get shouldBe a[SessionAccessDenied]
          renders.get() shouldBe 0
        }
      } yield ()).guarantee(release.complete(()).flatMap(_ => frontend.close()).flatMap(_ => app.destroy()))
    } yield ()
    program.timeout(5.seconds).unsafeRunSync()
  }
}
