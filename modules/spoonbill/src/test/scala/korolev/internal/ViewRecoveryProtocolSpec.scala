package spoonbill.internal

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.SessionAccessDenied

class ViewRecoveryProtocolSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter = Reporter.PrintReporter
  private val connection = ConnectionId.fromUuid(UUID.fromString("11111111-1111-4111-8111-111111111111"))

  private def browser(incoming: Queue[Future, String]) = new Frontend[Future](incoming.stream, Some(2),
    connectionId = Some(connection), authorize = Some(() => Future.unit),
    viewRecoveryEpoch = Some(ViewOwnershipEpoch.initial))

  "Durable view protocol" should "stamp full baselines and ordered patches without persisting DOM" in {
    val frontend = browser(Queue[Future, String]())
    for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      reset <- frontend.outgoingMessages.pull()
      _ <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit)
      patch <- frontend.outgoingMessages.pull()
      _ <- frontend.close()
    } yield {
      reset.getOrElse(fail("No baseline")) should startWith(s"""[19,"0","$connection","0",[4""")
      patch.getOrElse(fail("No patch")) should startWith(s"""[20,"$connection","0","1",[4""")
    }
  }

  it should "carry revision-bearing browser events to handlers and keep control messages independent" in {
    val incoming = Queue[Future, String]()
    val frontend = browser(incoming)
    val invoked = Promise[String]()
    for {
      _ <- frontend.registerCustomCallback("counter")(value => Future.successful { invoked.trySuccess(value); () })
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      _ <- incoming.enqueue(s"""[7,"$connection:0:1:counter:payload:with:colons"]""")
      value <- invoked.future
      _ <- incoming.enqueue("[6]")
      heartbeat <- frontend.outgoingMessages.pull()
      _ <- frontend.close()
    } yield {
      value shouldBe "payload:with:colons"
      heartbeat shouldBe Some("[16]")
    }
  }

  it should "stamp direct DOM property updates in the same render sequence" in {
    val frontend = browser(Queue[Future, String]())
    for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      _ <- frontend.setProperty(avocet.Id("1"), "title", "presentation")
      patch <- frontend.outgoingMessages.pull()
      _ <- frontend.close()
    } yield patch.getOrElse(fail("No property patch")) should startWith(s"""[20,"$connection","0","1",[4,""")
  }

  it should "reject an already queued user event when a newer render wins before execution" in {
    val frontend = browser(Queue[Future, String]())
    val entered = Promise[Unit]()
    val release = Promise[Unit]()
    val invoked = new AtomicBoolean(false)
    for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      blocker = frontend.runUserAction { entered.trySuccess(()); release.future }
      _ <- entered.future
      stale = frontend.runUserAction(Future.successful { invoked.set(true); () }, Some(0L))
      _ <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- frontend.outgoingMessages.pull()
      _ = release.success(())
      _ <- blocker
      error <- stale.failed
      _ <- frontend.close()
    } yield {
      error shouldBe a[SessionAccessDenied]
      invoked.get() shouldBe false
    }
  }
}
