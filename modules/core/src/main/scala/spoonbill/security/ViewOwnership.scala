package spoonbill.security

import Identifiers.*
import Versions.*

final case class ViewFence(viewId: ViewSessionId, ownerId: ViewOwnerId, epoch: ViewOwnershipEpoch)

enum OwnershipError {
  case StaleEpoch, WrongView, WrongOwner, Unowned, EpochExhausted
}

/**
 * A decision model, not a lease/coordinator. Admission, output and snapshot
 * receivers must check a current authoritative record at their linearization
 * boundary. A cached successful check cannot fence a later write; takeover
 * cannot roll back an already-running mutation.
 */
final case class ViewOwnership(viewId: ViewSessionId, epoch: ViewOwnershipEpoch, owner: Option[ViewOwnerId]) {
  def acquire(expectedEpoch: ViewOwnershipEpoch, newOwner: ViewOwnerId): Either[OwnershipError, ViewOwnership] =
    if (epoch != expectedEpoch) Left(OwnershipError.StaleEpoch)
    else
      epoch.next.left.map(_ => OwnershipError.EpochExhausted).map { next =>
        copy(epoch = next, owner = Some(newOwner))
      }

  def fence: Either[OwnershipError, ViewFence] =
    owner.toRight(OwnershipError.Unowned).map(ViewFence(viewId, _, epoch))

  def validate(presented: ViewFence): Either[OwnershipError, Unit] =
    if (viewId != presented.viewId) Left(OwnershipError.WrongView)
    else if (epoch != presented.epoch) Left(OwnershipError.StaleEpoch)
    else
      owner match {
        case None                                      => Left(OwnershipError.Unowned)
        case Some(value) if value != presented.ownerId => Left(OwnershipError.WrongOwner)
        case Some(_)                                   => Right(())
      }

  def release(presented: ViewFence): Either[OwnershipError, ViewOwnership] =
    validate(presented).flatMap { _ =>
      epoch.next.left.map(_ => OwnershipError.EpochExhausted).map(next => copy(epoch = next, owner = None))
    }
}

object ViewOwnership {
  def unowned(viewId: ViewSessionId): ViewOwnership =
    ViewOwnership(viewId, ViewOwnershipEpoch.initial, None)
}
