package spoonbill.internal

import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{ExecutionContext, Future, Promise}
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Versions.ViewOwnershipEpoch

final class FrontendPublicationReentrySpec extends AsyncFlatSpec with Matchers {
  private given Effect[Future] = Effect.futureEffect
  private given Reporter       = Reporter.PrintReporter

  "Frontend publication" should "preserve a close triggered inline by output delivery" in {
    val frontend = new Frontend[Future](
      Queue[Future, String]().stream,
      None,
      authorize = Some(() => Future.unit),
      viewRecoveryEpoch = Some(ViewOwnershipEpoch.initial)
    )
    val delivery = frontend.outgoingMessages.pull()
    val closed   = Promise[Unit]()
    delivery.onComplete(_ => frontend.close().onComplete(closed.tryComplete)(ExecutionContext.parasitic))(
      ExecutionContext.parasitic
    )
    for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit)
      _ <- closed.future
      _ <- delivery
    } yield frontend.interactionIsLive shouldBe false
  }
}
