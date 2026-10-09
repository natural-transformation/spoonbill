package spoonbill.snapshot

import java.util.concurrent.atomic.AtomicReference
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}

/** A connection's presentation projection. The store is already bound to fresh
  * authority and an exclusive owner. Neither the projection nor restored data
  * supplies authentication authority. Component-local state remains ephemeral.
  */
trait ViewSnapshotSession[F[_], S]:
  def ownerEpoch: ViewOwnershipEpoch
  def initialize(fresh: S): F[S]
  def commit(candidate: S): F[Unit]

final class ViewSnapshotException(val error: ViewSnapshotError)
    extends RuntimeException(s"View snapshot operation rejected: $error")

object ViewSnapshotSession:
  /** Explicitly select non-sensitive presentation fields and overlay them onto
    * a freshly authorized state. Identity/schema mismatches discard the old
    * projection through the store's fenced reset; malformed data fails closed.
    */
  def projected[F[_]: Effect, S, P](
    store: ViewSnapshotStore[F, P],
    project: S => P,
    restore: (S, P) => S
  ): ViewSnapshotSession[F, S] = new ViewSnapshotSession[F, S]:
    private val revision = new AtomicReference(Option.empty[ViewRevision])
    def ownerEpoch: ViewOwnershipEpoch = store.ownerEpoch

    private def accepted[A](result: Either[ViewSnapshotError, A]): F[A] = result match
      case Right(value) => Effect[F].pure(value)
      case Left(error) => Effect[F].fail(new ViewSnapshotException(error))

    def initialize(fresh: S): F[S] =
      store.load().flatMap(accepted).flatMap {
        case SnapshotLoad.Restored(current, value) =>
          Effect[F].delay { revision.set(Some(current)); restore(fresh, value) }
        case SnapshotLoad.Empty(current) =>
          Effect[F].delay(project(fresh)).flatMap(store.save(current, _)).flatMap(accepted)
            .flatMap(next => Effect[F].delay { revision.set(Some(next)); fresh })
        case SnapshotLoad.ResetRequired(current, _) =>
          Effect[F].delay(project(fresh)).flatMap(store.reset(current, _)).flatMap(accepted)
            .flatMap(next => Effect[F].delay { revision.set(Some(next)); fresh })
      }

    def commit(candidate: S): F[Unit] =
      Effect[F].delay(revision.get()).flatMap {
        case None => Effect[F].fail(new IllegalStateException("View snapshot session is not initialized"))
        case Some(current) =>
          Effect[F].delay(project(candidate)).flatMap(store.save(current, _)).flatMap(accepted)
            .flatMap(next => Effect[F].delay { revision.set(Some(next)); () })
      }
