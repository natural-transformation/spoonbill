package example.forwarding

import scala.concurrent.Future
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.effect.Effect

/**
 * An application's provider decorator, outside Spoonbill's packages, as a host
 * adds behavior such as a logout audit. It forwards everything to the inner
 * provider; forward selects whether it also forwards the concurrent-check
 * setting, which it may do only because its own calls add no shared state.
 */
final class ConcurrencyForwarding[P](inner: BrowserPersistence[Future, P], forward: Boolean)
    extends BrowserPersistence[Future, P] {
  export inner.{scope, capabilities, accepts, capture, claim, authorize, release, begin, bindChallenge, resume,
    pending, recover, deliver, logout, maintenance, close}

  override def checkConcurrency: Option[BrowserCheckConcurrency] = if (forward) inner.checkConcurrency else None
  override def guardedWriteObservations: Option[BrowserWriteObservations[Future, P]] = inner.guardedWriteObservations

  override def inspectPrincipal(binding: String, token: Option[String])(using
    effect: Effect[Future]
  ): Future[Either[BrowserFailure, Option[P]]] = inner.inspectPrincipal(binding, token)

  override def registerView(identity: BrowserIdentity[P], qsid: Qsid)(using
    effect: Effect[Future]
  ): Future[Either[BrowserFailure, Unit]] = inner.registerView(identity, qsid)

  override def admitOutputIf[A](view: BrowserView[P], output: A)(allowed: BrowserIdentity[P] => Boolean)(using
    effect: Effect[Future]
  ): Future[Either[BrowserFailure, A]] = inner.admitOutputIf(view, output)(allowed)
}
