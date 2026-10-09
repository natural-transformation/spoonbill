package spoonbill.state

import avocet.Id
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration.*
import spoonbill.effect.{Effect, Queue, Reporter, Stream}
import spoonbill.internal.Frontend
import spoonbill.server.SessionAccessDenied
import spoonbill.testExecution.defaultExecutor

class GuardsSpec extends AnyFlatSpec with Matchers {
  private implicit val effect: Effect[Future] = Effect.futureEffect
  private implicit val reporter: Reporter     = Reporter.PrintReporter

  import javaSerialization.*

  private final case class GuardState(value: Int, permitted: Boolean) extends Serializable

  private def await[A](future: Future[A]): A = Await.result(future, 3.seconds)

  private def allowed(state: GuardState, current: AtomicBoolean): Future[Unit] =
    if (current.get() && state.permitted) Future.successful(())
    else Future.failed(new SessionAccessDenied)

  private final class CountingStateManager(initial: GuardState) extends StateManager[Future] {
    val delegate: StateManager[Future] = StateManager.cached[Future](Map(Id.TopLevel -> initial))
    val reads                          = new AtomicInteger(0)
    val snapshots                      = new AtomicInteger(0)
    val writes                         = new AtomicInteger(0)
    val deletes                        = new AtomicInteger(0)

    def snapshot: Future[StateManager.Snapshot] = {
      snapshots.incrementAndGet()
      delegate.snapshot
    }
    def read[T: StateDeserializer](nodeId: Id): Future[Option[T]] = {
      reads.incrementAndGet()
      delegate.read[T](nodeId)
    }
    def write[T: StateSerializer](nodeId: Id, value: T): Future[Unit] = {
      writes.incrementAndGet()
      delegate.write(nodeId, value)
    }
    def delete(nodeId: Id): Future[Unit] = {
      deletes.incrementAndGet()
      delegate.delete(nodeId)
    }
  }

  "GuardedStateManager" should "deny restored reads, snapshots, and writes after session revocation" in {
    val current    = new AtomicBoolean(true)
    val underlying = new CountingStateManager(GuardState(1, permitted = true))
    val guarded    = new GuardedStateManager[Future, GuardState](underlying, allowed(_, current))
    current.set(false)

    intercept[SessionAccessDenied](await(guarded.read[GuardState](Id.TopLevel)))
    intercept[SessionAccessDenied](await(guarded.snapshot))
    intercept[SessionAccessDenied](await(guarded.write(Id.TopLevel, GuardState(2, permitted = true))))

    underlying.reads.get() shouldBe 3 // only the guard's current-state checks ran
    underlying.snapshots.get() shouldBe 0
    underlying.writes.get() shouldBe 0
  }

  it should "authorize a candidate top-level state before writing it" in {
    val current    = new AtomicBoolean(true)
    val initial    = GuardState(1, permitted = true)
    val underlying = new CountingStateManager(initial)
    val guarded    = new GuardedStateManager[Future, GuardState](underlying, allowed(_, current))

    intercept[SessionAccessDenied](await(guarded.write(Id.TopLevel, GuardState(2, permitted = false))))

    underlying.writes.get() shouldBe 0
    await(underlying.delegate.read[GuardState](Id.TopLevel)) shouldBe Some(initial)
  }

  it should "stop new writes and drain a write admitted before close" in {
    val started = Promise[Unit]()
    val release = Promise[Unit]()
    val underlying = new StateManager[Future] {
      def snapshot: Future[StateManager.Snapshot] = Future.successful(new StateManager.Snapshot {
        def apply[T: StateDeserializer](nodeId: Id): Option[T] =
          if (nodeId == Id.TopLevel) Some(GuardState(1, permitted = true).asInstanceOf[T]) else None
      })
      def read[T: StateDeserializer](nodeId: Id): Future[Option[T]] =
        Future.successful(if (nodeId == Id.TopLevel) Some(GuardState(1, permitted = true).asInstanceOf[T]) else None)
      def write[T: StateSerializer](nodeId: Id, value: T): Future[Unit] = {
        started.trySuccess(())
        release.future
      }
      def delete(nodeId: Id): Future[Unit] = Future.successful(())
    }
    val current  = new AtomicBoolean(true)
    val guarded  = new GuardedStateManager[Future, GuardState](underlying, allowed(_, current))
    val admitted = guarded.write(Id.TopLevel, GuardState(2, permitted = true))
    await(started.future)
    val drained = guarded.closeAndDrain()

    drained.isCompleted shouldBe false
    intercept[SessionAccessDenied](await(guarded.write(Id.TopLevel, GuardState(3, permitted = true))))
    release.success(())
    await(admitted)
    await(drained)
  }

  "Frontend" should "revalidate queued protected output when authority is revoked before pull" in {
    val current                = new AtomicBoolean(true)
    val revokedAuthorityDenied = new AtomicBoolean(false)
    val incoming               = Queue[Future, String](8)
    val frontend = new Frontend[Future](
      incomingMessages = incoming.stream,
      heartbeatLimit = None,
      authorize = Some { () =>
        if (current.get()) Future.successful(())
        else {
          revokedAuthorityDenied.set(true)
          Future.failed(new SessionAccessDenied)
        }
      }
    )
    try {
      await(frontend.focus(Id("1")))
      current.set(false)

      intercept[SessionAccessDenied](await(frontend.outgoingMessages.pull()))
      revokedAuthorityDenied.get() shouldBe true
    } finally await(frontend.close())
  }

  it should "suppress private output when its source state changes to public before dequeue" in {
    final case class ViewState(containsPrivate: Boolean)
    val state                            = new java.util.concurrent.atomic.AtomicReference(ViewState(containsPrivate = true))
    val capturedPrivateSource            = new AtomicBoolean(false)
    val currentPublicAuthorizationPassed = new AtomicBoolean(false)
    val staleProducerDenied              = new AtomicBoolean(false)
    val incoming                         = Queue[Future, String](8)
    val frontend = new Frontend[Future](
      incomingMessages = incoming.stream,
      heartbeatLimit = None,
      authorize = Some { () =>
        if (!state.get().containsPrivate) currentPublicAuthorizationPassed.set(true)
        Future.successful(())
      },
      captureOutputAuthorization = Some { () =>
        val producedFromPrivateState = state.get().containsPrivate
        capturedPrivateSource.set(producedFromPrivateState)
        Future.successful { () =>
          if (producedFromPrivateState && !state.get().containsPrivate) {
            staleProducerDenied.set(true)
            Future.failed(new SessionAccessDenied)
          } else Future.successful(())
        }
      }
    )
    try {
      await(frontend.setProperty(Id("1"), "textContent", "synthetic private patch"))
      capturedPrivateSource.get() shouldBe true
      state.set(ViewState(containsPrivate = false))

      intercept[SessionAccessDenied](await(frontend.outgoingMessages.pull()))
      currentPublicAuthorizationPassed.get() shouldBe true
      staleProducerDenied.get() shouldBe true
    } finally await(frontend.close())
  }

  it should "process an evalJs reply while its serial guarded user action is waiting" in {
    val incoming = Queue[Future, String](8)
    val frontend = new Frontend[Future](
      incomingMessages = incoming.stream,
      heartbeatLimit = None,
      authorize = Some(() => Future.successful(()))
    )
    try {
      val action   = frontend.runUserAction(frontend.evalJs("return 42"))
      val outgoing = await(frontend.outgoingMessages.pull()).getOrElse(fail("Missing evalJs request"))
      outgoing should startWith("[10,")
      await(incoming.enqueue("[4,\"0:0:42\"]"))

      await(action) shouldBe "42"
    } finally await(frontend.close())
  }
}
