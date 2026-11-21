package spoonbill.server.internal.services

import scala.concurrent.{Future, Promise}

/**
 * The transport that a legacy attach belongs to, when the attach must not
 * share a view with another transport. For long polling it is the topic of
 * one opening of the page's connection.
 *
 * A newer transport of the same session can take its place. From then on its
 * requests get no view, and the view that it attached leaves the stored state
 * to the view of its successor.
 */
private[services] final class AttachOwner {
  private val replaced = Promise[Unit]()

  /** A newer transport of the session has taken the place of this one. */
  def supersede(): Unit = {
    replaced.trySuccess(())
    ()
  }

  def superseded: Boolean = replaced.isCompleted

  /** Completes when this owner is superseded. */
  def whenSuperseded: Future[Unit] = replaced.future
}
