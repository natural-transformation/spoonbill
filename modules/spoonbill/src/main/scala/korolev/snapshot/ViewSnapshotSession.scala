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
  /** One successful initialization per connection. A failed attempt may be
    * retried; concurrent or repeated successful initialization is rejected.
    * Commits require initialization to have completed successfully.
    */
  def initialize(fresh: S): F[S]
  def commit(candidate: S): F[Unit]

final class ViewSnapshotException(val error: ViewSnapshotError)
    extends RuntimeException(s"View snapshot operation rejected: $error")

object ViewSnapshotSession:
  private enum Initialization:
    case Empty, Starting
    case Ready(revision: ViewRevision)

  /** Explicitly select non-sensitive presentation fields and overlay them onto
    * a freshly authorized state. Identity changes discard the old projection
    * through the store's fenced reset. Unsupported schemas and malformed data
    * fail closed without discarding the recoverable projection. Both callbacks
    * must be pure, bounded and repeatable: retries or reconnects may call them
    * again. Restore only selected presentation onto fresh state, never authority
    * or business effects.
    */
  def projected[F[_]: Effect, S, P](
    store: ViewSnapshotStore[F, P],
    project: S => P,
    restore: (S, P) => S
  ): ViewSnapshotSession[F, S] = new ViewSnapshotSession[F, S]:
    private val initialization = new AtomicReference[Initialization](Initialization.Empty)
    def ownerEpoch: ViewOwnershipEpoch = store.ownerEpoch

    private def accepted[A](result: Either[ViewSnapshotError, A]): F[A] = result match
      case Right(value) => Effect[F].pure(value)
      case Left(error) => Effect[F].fail(new ViewSnapshotException(error))

    private def restoreInitial(fresh: S): F[S] =
      Effect[F].delayAsync(store.load()).flatMap(accepted).flatMap {
        case SnapshotLoad.Restored(current, value) =>
          Effect[F].delay {
            val restored = restore(fresh, value)
            initialization.set(Initialization.Ready(current))
            restored
          }
        case SnapshotLoad.Empty(current) =>
          Effect[F].delay(project(fresh)).flatMap(store.save(current, _)).flatMap(accepted)
            .flatMap(next => Effect[F].delay { initialization.set(Initialization.Ready(next)); fresh })
        case SnapshotLoad.ResetRequired(_, SnapshotResetReason.SchemaChanged) =>
          Effect[F].fail(new ViewSnapshotException(ViewSnapshotError.SchemaIncompatible))
        case SnapshotLoad.ResetRequired(current, SnapshotResetReason.IdentityChanged) =>
          Effect[F].delay(project(fresh)).flatMap(store.reset(current, _)).flatMap(accepted)
            .flatMap(next => Effect[F].delay { initialization.set(Initialization.Ready(next)); fresh })
      }

    def initialize(fresh: S): F[S] =
      Effect[F].delay {
        if !initialization.compareAndSet(Initialization.Empty, Initialization.Starting) then
          throw new IllegalStateException("View snapshot session initialization is already started or complete")
      }.flatMap { _ =>
        restoreInitial(fresh).recoverF { case error =>
          Effect[F].delay(initialization.set(Initialization.Empty)).flatMap(_ => Effect[F].fail(error))
        }
      }

    def commit(candidate: S): F[Unit] =
      Effect[F].delay(initialization.get()).flatMap {
        case Initialization.Ready(current) =>
          Effect[F].delay(project(candidate)).flatMap(store.save(current, _)).flatMap(accepted)
            .flatMap(next => Effect[F].delay { initialization.set(Initialization.Ready(next)); () })
        case _ => Effect[F].fail(new IllegalStateException("View snapshot session is not initialized"))
      }
