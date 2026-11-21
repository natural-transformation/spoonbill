package spoonbill.server.internal.services

import scala.collection.concurrent.TrieMap
import scala.concurrent.{ExecutionContext, Promise}
import spoonbill.Qsid
import spoonbill.effect.Effect

/**
 * The legacy views that are registered, at most one for a session. Each has
 * the transport that attached it and a signal that it has left the registry.
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

  final class Registration(val view: V, val owner: Option[AnyRef]) {
    private[LegacyViews] val left = Promise[Unit]()
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

  /** Completes when the view has left the registry. Fails when its retirement failed. */
  def departure(registration: Registration): F[Unit] = Effect[F].promise { callback =>
    registration.left.future.onComplete(result => callback(result.toEither))(executionContext)
  }
}
