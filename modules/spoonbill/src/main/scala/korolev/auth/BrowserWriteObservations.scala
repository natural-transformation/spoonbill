package spoonbill.auth

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import spoonbill.security.Versions.ViewRevision

/**
 * A provider's optional capability for the coordinator's queued top-level write
 * of a snapshot session that skips unchanged projections. One call is one fresh
 * authority observation of the view, as authorize is, and it also reads the
 * view's snapshot revision in the same transaction, under the same owner fence.
 * The result is exposed only after that transaction's acknowledged settlement.
 *
 * Only Spoonbill's own providers issue observations. A provider decorator may
 * forward the inner provider's capability when it preserves the inner
 * provider's checks; a decorator that adds checks of its own must not forward
 * it, so that the coordinator keeps the ordinary authorization and commit.
 */
trait BrowserWriteObservations[F[_], P] {
  def observeWrite(view: BrowserView[P]): F[Either[BrowserFailure, BrowserWriteObservation[P]]]
}

/** A fresh checked identity and an opaque, one-use snapshot revision receipt from one transaction. */
final class BrowserWriteObservation[P] private[spoonbill] (
  val identity: BrowserIdentity[P],
  private[spoonbill] val receipt: SnapshotRevisionReceipt
) {
  override def toString: String = "BrowserWriteObservation(<redacted>)"
}

/**
 * Evidence of a view's snapshot revision at one authority observation, bound to
 * the provider's issuer, the observed view, its owner and its epoch. It grants
 * nothing else. Spoonbill creates it after the observing transaction settles and
 * consumes it once, inside the same queued write; a receipt for another view or
 * a second use is refused.
 */
final class SnapshotRevisionReceipt private[spoonbill] (
  private[spoonbill] val issuer: AnyRef,
  private[spoonbill] val view: BrowserView[?],
  private[spoonbill] val ownerId: UUID,
  private[spoonbill] val epoch: Long,
  private[spoonbill] val revision: ViewRevision
) {
  private val consumed = new AtomicBoolean()

  /** True once, for the session of exactly this view, owner and epoch. */
  private[spoonbill] def consume(owner: BrowserView[?]): Boolean =
    (view eq owner) && (issuer eq owner.issuer) && ownerId == owner.ownerId && epoch == owner.epoch &&
      consumed.compareAndSet(false, true)

  override def toString: String = "SnapshotRevisionReceipt(<redacted>)"
}
