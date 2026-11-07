package spoonbill.recovery

import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal
import spoonbill.auth.BrowserView
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.snapshot.{SnapshotFormat, ViewSnapshotSession}

/**
 * The composition of a provider's publication capability and a payload
 * adapter into the coordinator's snapshot-session factory, with an explicit
 * lifecycle. Pass snapshots to the unchanged BrowserAuthentication constructor
 * parameter. Schedule maintenance() beside browser.maintenance(): it claims
 * retired references and runs adapter deletes outside coordinator turns and
 * provider transactions. Close the binding during shutdown after draining
 * admitted snapshot writes; closing stops binding-owned work and releases
 * local resources, and neither closes the shared provider or store nor waits
 * for uncancellable I/O forever. Unsettled obligations remain durable for
 * another maintenance instance.
 */
trait PayloadSnapshotBinding[F[_], P, S] {
  def snapshots: BrowserView[P] => Option[ViewSnapshotSession[F, S]]
  def maintenance(): F[Unit]
  def close(): F[Unit]

  /** Operation counters since construction; diagnostics only. */
  def statistics: RecoveryStatistics

  /** The provider's visible cleanup backlog. */
  def backlog(): F[CleanupBacklog]
}

object PayloadRecovery {

  /**
   * Opt-in reference-backed recovery. Inline provider snapshots stay the
   * default; choose this path for externally governed storage or payloads
   * whose placement or size justifies the extra provider transaction and
   * payload round trip on every committed state transition. project and
   * restore must be pure, bounded and repeatable, exactly as for the inline
   * helper.
   */
  def projectedWithPayloads[F[_]: Effect, P, S, D](
    provider: SnapshotReferenceSupport[F, P],
    payloads: SnapshotPayloadStore[F],
    format: SnapshotFormat[D],
    project: S => D,
    restore: (S, D) => S,
    settings: PayloadRecoverySettings = PayloadRecoverySettings()
  ): PayloadSnapshotBinding[F, P, S] =
    instrumented(provider, payloads, format, project, restore, settings, RecoveryProbe.none)

  /** The same binding with a fault-injection probe, for conformance harnesses. */
  def instrumented[F[_]: Effect, P, S, D](
    provider: SnapshotReferenceSupport[F, P],
    payloads: SnapshotPayloadStore[F],
    format: SnapshotFormat[D],
    project: S => D,
    restore: (S, D) => S,
    settings: PayloadRecoverySettings,
    probe: RecoveryProbe[F]
  ): PayloadSnapshotBinding[F, P, S] =
    new Binding(provider, payloads, format, project, restore, settings, probe)

  private final class Binding[F[_], P, S, D](
    provider: SnapshotReferenceSupport[F, P],
    payloads: SnapshotPayloadStore[F],
    format: SnapshotFormat[D],
    project: S => D,
    restore: (S, D) => S,
    settings: PayloadRecoverySettings,
    probe: RecoveryProbe[F]
  )(using F: Effect[F])
      extends PayloadSnapshotBinding[F, P, S] {
    import FaultDecision.FailBeforeCommit
    import RecoveryFault.*

    private val counters = new RecoveryCounters
    private val policy   = SnapshotSchemaPolicy.of(format)
    private val closed   = new AtomicBoolean()
    private val monitor  = new Object
    private var sweeping = false
    private var waiters  = List.empty[Effect.Promise[Unit]]

    def statistics: RecoveryStatistics = counters.snapshot
    def backlog(): F[CleanupBacklog]   = F.delayAsync(provider.maintenance.backlog())

    /** A session of a closed binding fails closed instead of silently losing durability. */
    val snapshots: BrowserView[P] => Option[ViewSnapshotSession[F, S]] = view =>
      provider.bind(view, policy).map { bound =>
        val store = new ReferenceSnapshotStore(bound, payloads, format, probe, counters, () => closed.get())
        ViewSnapshotSession.projected(store, project, restore)
      }

    private def beginSweep(): Boolean = monitor.synchronized {
      if (closed.get() || sweeping) false
      else { sweeping = true; true }
    }
    private def finishSweep(): Unit = {
      val waiting = monitor.synchronized {
        sweeping = false
        val result = waiters; waiters = Nil; result
      }
      waiting.foreach(_(Right(())))
    }

    def maintenance(): F[Unit] = F.delayAsync {
      if (!beginSweep()) F.unit
      else
        F.uncancelable {
          F.delayAsync(sweep())
            .recoverF { case NonFatal(error) => F.delay(finishSweep()).flatMap(_ => F.fail(error)) }
            .flatMap(_ => F.delay(finishSweep()))
        }
    }

    private def sweep(): F[Unit] =
      provider.maintenance
        .claimGarbage(settings.maintenanceBatch)
        .flatMap(claims => claims.foldLeft(F.unit)((done, claim) => done.flatMap(_ => process(claim))))

    private def process(claim: GarbageClaim): F[Unit] =
      if (closed.get()) F.unit
      else
        probe.at(AfterGarbageClaimBeforeDelete).flatMap {
          // The lease expires and another worker reclaims the obligation.
          case FailBeforeCommit => F.unit
          case _ =>
            counters.deletes.incrementAndGet()
            F.delayAsync(payloads.delete(claim.ref)).recover { case NonFatal(_) => PayloadDelete.Unavailable }.flatMap {
              case PayloadDelete.FinalDeleted if payloads.cleanupBasis == CleanupBasis.Unproven =>
                // An unproven adapter cannot establish finality; keep the obligation.
                counters.downgradedDeletes.incrementAndGet()
                retry(claim)
              case PayloadDelete.FinalDeleted =>
                probe.at(AfterDeleteBeforeComplete).flatMap {
                  case FailBeforeCommit => F.unit
                  case _ =>
                    provider.maintenance.completeDelete(claim).map { update =>
                      if (update == ClaimUpdate.StaleClaim) counters.staleClaims.incrementAndGet()
                      else counters.finalDeletes.incrementAndGet()
                      ()
                    }
                }
              case _ => retry(claim)
            }
        }

    private def retry(claim: GarbageClaim): F[Unit] = {
      counters.uncertainDeletes.incrementAndGet()
      provider.maintenance.retryDelete(claim).map { update =>
        if (update == ClaimUpdate.StaleClaim) counters.staleClaims.incrementAndGet()
        ()
      }
    }

    def close(): F[Unit] = F.delayAsync {
      closed.set(true)
      F.promise[Unit] { callback =>
        val finished = monitor.synchronized {
          if (!sweeping) true
          else { waiters = callback :: waiters; false }
        }
        if (finished) callback(Right(()))
      }
    }
  }
}
