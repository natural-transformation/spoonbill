package spoonbill.internal

import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger, AtomicReference}
import org.scalatest.flatspec.AsyncFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Future, Promise}
import spoonbill.effect.{Effect, Queue, Reporter}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.server.SessionAccessDenied

class PublishedViewAdmissionSpec extends AsyncFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter = Reporter.PrintReporter

  private def browser(authorize: () => Future[Unit]) =
    new Frontend[Future](Queue[Future, String]().stream, Some(2),
      connectionId = Some(ConnectionId.fromUuid(UUID.randomUUID())),
      authorize = Some(authorize), viewRecoveryEpoch = Some(ViewOwnershipEpoch.initial))

  "Published view admission" should "retain the old handler until the replacement frame is published" in {
    val frontend = browser(() => Future.unit)
    val oldInvocations = new AtomicInteger(0)
    val newInvocations = new AtomicInteger(0)
    val oldHandlers = Map("submit" -> (() => { oldInvocations.incrementAndGet(); () }))
    val newHandlers = Map("submit" -> (() => { newInvocations.incrementAndGet(); () }))
    val published = new AtomicReference(Map.empty[String, () => Unit])
    val producerEntered = Promise[Unit]()
    val allowPublication = Promise[Unit]()

    for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit, () => published.set(oldHandlers))
      _ <- frontend.outgoingMessages.pull()
      // The new render and its handlers exist, but authority has not yet
      // permitted publishing that render. Admission must use the old map.
      pendingPatch = frontend.performDomChangesAuthorized(_ => (),
        () => { producerEntered.trySuccess(()); allowPublication.future }, () => published.set(newHandlers))
      _ <- producerEntered.future
      capturedOld <- frontend.selectFromPublishedView(Some(0L))(published.get()("submit"))
      _ = allowPublication.success(())
      _ <- pendingPatch
      _ <- frontend.outgoingMessages.pull()
      capturedNew <- frontend.selectFromPublishedView(Some(1L))(published.get()("submit"))
      _ = capturedOld()
      _ = capturedNew()
      _ <- frontend.close()
    } yield {
      oldInvocations.get() shouldBe 1
      newInvocations.get() shouldBe 1
    }
  }

  it should "reject an old revision after asynchronous authority yields to a newer publication" in {
    val gateNextCheck = new AtomicBoolean(false)
    val authorityEntered = Promise[Unit]()
    val allowAuthority = Promise[Unit]()
    val frontend = browser(() =>
      if (gateNextCheck.compareAndSet(true, false)) {
        authorityEntered.trySuccess(())
        allowAuthority.future
      } else Future.unit)
    val oldInvocations = new AtomicInteger(0)
    val newInvocations = new AtomicInteger(0)
    val published = new AtomicReference[() => Unit](() => ())

    for {
      _ <- frontend.resetDomChangesAuthorized(_ => (), () => Future.unit,
        () => published.set(() => { oldInvocations.incrementAndGet(); () }))
      _ <- frontend.outgoingMessages.pull()
      _ = gateNextCheck.set(true)
      // History/custom callbacks also need the post-authorization revision
      // check; their operation does not select a DOM handler a second time.
      oldEvent = frontend.runUserAction(
        Future.successful(published.get()()), Some(0L))
      _ <- authorityEntered.future
      _ <- frontend.performDomChangesAuthorized(_ => (), () => Future.unit,
        () => published.set(() => { newInvocations.incrementAndGet(); () }))
      _ <- frontend.outgoingMessages.pull()
      _ = allowAuthority.success(())
      failure <- oldEvent.failed
      _ <- frontend.close()
    } yield {
      failure shouldBe a[SessionAccessDenied]
      oldInvocations.get() shouldBe 0
      newInvocations.get() shouldBe 0
    }
  }
}
