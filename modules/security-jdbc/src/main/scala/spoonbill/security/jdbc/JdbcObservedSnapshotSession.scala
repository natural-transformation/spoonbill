package spoonbill.security.jdbc

import java.util.concurrent.atomic.AtomicReference
import spoonbill.auth.{BrowserView, SnapshotRevisionReceipt}
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.*
import spoonbill.security.jdbc.JdbcSnapshotSessionMachine.*

/**
 * The inline session of projectedSnapshotsIfChanged. It behaves as
 * ViewSnapshotSession.projected, and it also tracks the encoded bytes its
 * writes acknowledged, so the coordinator's queued top-level write can save
 * nothing when the candidate encodes to exactly those bytes at the revision the
 * write's observation found. The public commit always performs the ordinary
 * fenced save. The phases live in JdbcSnapshotSessionMachine; this class only
 * runs the store and publishes each result with one compare-and-set.
 */
private[jdbc] final class JdbcObservedSnapshotSession[F[_], P, S, D](
  store: JdbcInlineSnapshotStore[F, P, D],
  view: BrowserView[P],
  project: S => D,
  restore: (S, D) => S
)(using F: Effect[F]) extends ViewSnapshotSession[F, S] with ObservedSnapshotWrites[F, S] {
  private val state = new AtomicReference[State](Empty)

  def ownerEpoch: ViewOwnershipEpoch = store.ownerEpoch

  private def accepted[A](result: Either[ViewSnapshotError, A]): F[A] = result match {
    case Right(value) => F.pure(value)
    case Left(error)  => F.fail(new ViewSnapshotException(error))
  }
  private def encoded(candidate: S): F[Array[Byte]] = F.delay(store.encode(project(candidate))).flatMap(accepted)

  /**
   * One attempt claims the empty session and, whether it fails or is
   * cancelled, puts its own unfinished claim back, so a later attempt can
   * reload storage and start again. A published Ready state stays. An initial
   * save that committed before a cancellation is found by that reload. The
   * finalizer is installed first and the claim is made inside the guarded
   * task, so no cancellation can land between the claim and the finalizer
   * that puts it back. Each execution of the returned effect is its own
   * attempt. The Cats Effect 2 and Monix instances run that finalizer as soon
   * as the cancellation is requested, on the cancelling thread, while the
   * attempt runs on. If it runs before the claim, the attempt gives the
   * session back as soon as it has taken it. If it runs after, a provider call
   * that has started still runs to its end, and the cancelled attempt can
   * still make its initial save; so a retry on the same session can run beside
   * it and fail once with RevisionConflict, and the retry after that restores
   * the save.
   */
  def initialize(fresh: S): F[S] =
    F.delayAsync {
      val attempt = new Starting
      val phase   = new AtomicReference(AttemptPhase.Pending)
      F.guarantee(F.delay(claim(attempt, phase)).flatMap(_ => restoreInitial(fresh, attempt)))(
        F.delay(release(attempt, phase))
      )
    }

  /**
   * The attempt takes the session only while it is empty, and keeps it only if
   * its finalizer has not run first. It takes the session before it marks its
   * claim: a finalizer that runs in between finds the attempt pending and marks
   * it released, and the attempt then gives the session back here.
   */
  private def claim(attempt: Starting, phase: AtomicReference[AttemptPhase]): Unit = {
    if (!state.compareAndSet(Empty, attempt))
      throw new IllegalStateException("View snapshot session initialization is already started or complete")
    if (phase.updateAndGet(JdbcSnapshotSessionMachine.claimed) != AttemptPhase.Claimed) {
      state.compareAndSet(attempt, Empty)
      throw new IllegalStateException("View snapshot session initialization was cancelled")
    }
  }

  /**
   * An attempt that marked its claim and did not publish puts the claim back;
   * a published or another attempt's state stays. A pending attempt is only
   * marked released.
   */
  private def release(attempt: Starting, phase: AtomicReference[AttemptPhase]): Unit =
    if (phase.getAndUpdate(JdbcSnapshotSessionMachine.released) == AttemptPhase.Claimed) {
      state.compareAndSet(attempt, Empty); ()
    }

  private def restoreInitial(fresh: S, attempt: Starting): F[S] =
    F.delayAsync(store.load()).flatMap(accepted).flatMap {
      case SnapshotLoad.Restored(current, value) =>
        F.delay(restore(fresh, value)).flatMap(restored => publish(attempt, JdbcSnapshotSessionMachine.restored(current), restored))
      case SnapshotLoad.Empty(current) => seed(current, fresh, resetting = false, attempt)
      case SnapshotLoad.ResetRequired(_, SnapshotResetReason.SchemaChanged) =>
        F.fail(new ViewSnapshotException(ViewSnapshotError.SchemaIncompatible))
      case SnapshotLoad.ResetRequired(current, SnapshotResetReason.IdentityChanged) =>
        seed(current, fresh, resetting = true, attempt)
    }

  /** The initial save or identity reset, which seeds the confirmed copy. */
  private def seed(current: ViewRevision, fresh: S, resetting: Boolean, attempt: Starting): F[S] =
    encoded(fresh).flatMap { bytes =>
      F.delayAsync(store.writeEncoded(current, bytes, resetting)).flatMap(accepted).flatMap { next =>
        publish(attempt, JdbcSnapshotSessionMachine.acknowledged(next, bytes), fresh)
      }
    }

  /** Only the attempt that claimed the session publishes its result. */
  private def publish(attempt: Starting, ready: Ready, value: S): F[S] =
    F.delay {
      if (!state.compareAndSet(attempt, ready))
        throw new IllegalStateException("View snapshot session initialization was taken over")
      value
    }

  private def ready: F[Ready] = F.delay(state.get()).flatMap {
    case current: Ready => F.pure(current)
    case _              => F.fail(new IllegalStateException("View snapshot session is not initialized"))
  }

  /** The ordinary fenced save, also for an owner that has lost the view; it never skips. */
  def commit(candidate: S): F[Unit] = ready.flatMap(current => encoded(candidate).flatMap(write(current, _)))

  private[spoonbill] def commitObserved(receipt: SnapshotRevisionReceipt, candidate: S): F[Unit] =
    ready.flatMap { current =>
      if (!receipt.consume(view)) F.fail(new ViewSnapshotException(ViewSnapshotError.AccessDenied))
      else if (JdbcSnapshotSessionMachine.conflicts(current, receipt.revision))
        F.fail(new ViewSnapshotException(ViewSnapshotError.RevisionConflict))
      else
        encoded(candidate).flatMap { bytes =>
          // The observation admitted this no-op; a later takeover still fences all output.
          if (JdbcSnapshotSessionMachine.unchanged(current, bytes)) F.unit else write(current, bytes)
        }
    }

  /**
   * One fenced save of these bytes with the expected revision. The copy is
   * invalid while the save runs. Its acknowledgment publishes the next revision
   * with these bytes while the session still expects the revision this save
   * expected, also when another commit of the session started meanwhile: that
   * one expects the same revision and loses the database's comparison. The
   * provider's transaction cannot be cancelled once it runs, so the save and
   * its acknowledgment are one uncancelable step: a cancellation requested
   * meanwhile is honored only after the session has recorded the result that
   * the save returned.
   */
  private def write(current: Ready, bytes: Array[Byte]): F[Unit] = {
    val expected = current.revision
    F.uncancelable {
      F.delay(state.updateAndGet(JdbcSnapshotSessionMachine.begin(_, expected))).flatMap { _ =>
        F.delayAsync(store.writeEncoded(expected, bytes, resetting = false)).flatMap(accepted).flatMap { next =>
          F.delay { state.updateAndGet(JdbcSnapshotSessionMachine.acknowledge(_, expected, next, bytes)); () }
        }
      }
    }
  }
}
