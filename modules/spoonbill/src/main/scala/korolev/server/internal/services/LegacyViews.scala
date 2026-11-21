package spoonbill.server.internal.services

import java.util.concurrent.atomic.AtomicBoolean
import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Future, Promise}
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*

/**
 * The legacy views that are registered, at most one for a session. Each has
 * the transport that attached it, a signal that it has left the registry, and
 * a mark that an attach waits for it to leave.
 * A long-polling attach names its topic as the owner. A WebSocket attach
 * names none. What an attach does with these facts is decided by
 * [[LegacyAttachMachine]].
 *
 * @param executionContext
 *   an attach that waited for a departure goes on in a task of this context,
 *   not on the thread that retired the view: that can be the timer thread of
 *   the service
 * @tparam V
 *   the view, compared by identity
 */
private[services] final class LegacyViews[F[_]: Effect, V <: AnyRef](executionContext: ExecutionContext) {

  final class Registration(val view: V, val owner: Option[AttachOwner]) {
    private[LegacyViews] val left    = Promise[Unit]()
    private[LegacyViews] val waited  = new AtomicBoolean(false)

    /**
     * An attach of the session waits, or has waited, for this view to leave.
     * So the page goes on, and the view leaves the stored state to its
     * successor.
     */
    def awaited: Boolean = waited.get()
  }

  private val registered = TrieMap.empty[Qsid, Registration]

  /**
   * Registers a view that was just built. A retirement removes only its own
   * registration, so it cannot take this one out.
   */
  def register(qsid: Qsid, registration: Registration): Unit = {
    registered.put(qsid, registration)
    ()
  }

  /** The registration of this very view, while it is registered. */
  def registrationOf(qsid: Qsid, view: V): Option[Registration] =
    registered.get(qsid).filter(_.view eq view)

  /** The view has left the registry. The attaches that wait for that go on. */
  def departed(qsid: Qsid, registration: Registration): Unit = {
    registered.remove(qsid, registration)
    registration.left.trySuccess(())
    ()
  }

  /**
   * The retirement of the view failed, so the view stays registered and will
   * not leave. An attach that waits for its departure fails with this error;
   * it does not wait for ever.
   */
  def stuck(registration: Registration, error: Throwable): Unit = {
    registration.left.tryFailure(error)
    ()
  }

  /**
   * Completes when the view has left the registry, or when `waiter` was
   * superseded: its attach has nothing to wait for then. Fails when the
   * retirement of the view failed. The view is marked as awaited from the
   * moment the wait starts.
   */
  def departure(registration: Registration, waiter: Option[AttachOwner]): F[Unit] =
    Effect[F].delay(registration.waited.set(true)).flatMap(_ => signalled(registration.left.future, waiter))

  /**
   * Completes with `signal`, or when `waiter` was superseded: its attach has
   * nothing to wait for then. The attach goes on in a task of the execution
   * context, not on the thread that gave the signal.
   */
  def signalled(signal: Future[Unit], waiter: Option[AttachOwner]): F[Unit] = Effect[F].promise { callback =>
    val woken = Promise[Unit]()
    signal.onComplete(woken.tryComplete)(ExecutionContext.parasitic)
    waiter.foreach(_.whenSuperseded.onComplete(woken.tryComplete)(ExecutionContext.parasitic))
    woken.future.onComplete(result => callback(result.toEither))(executionContext)
  }
}
