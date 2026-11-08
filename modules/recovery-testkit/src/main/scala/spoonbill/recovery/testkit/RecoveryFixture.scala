package spoonbill.recovery.testkit

import scala.concurrent.duration.FiniteDuration
import spoonbill.recovery.*
import spoonbill.snapshot.{SnapshotFormat, ViewSnapshotStore}

/**
 * A controlled fault point. reached completes when the gated operation
 * arrives there; resume lets it continue with one decision. A gate fires
 * once; later arrivals at the same point proceed normally.
 */
trait RecoveryGate[F[_]] {
  def reached: F[Unit]
  def resume(decision: FaultDecision): F[Unit]
}

/** Provider and adapter operations observed by the harness since it was built. */
final case class OperationCounts(
  reservations: Long,
  publications: Long,
  statusLookups: Long,
  uploads: Long,
  downloads: Long,
  deletes: Long
)

/**
 * Test-safe observations: revisions, reference states, pins and cleanup
 * counts, never credentials or payload-bearing diagnostics. Per-view fields
 * describe the observed view; the state counts cover the fixture's scope.
 */
final case class RecoveryObservation(
  revision: Long,
  published: Int,
  staged: Int,
  retired: Int,
  deleted: Int,
  openPins: Int,
  openObligations: Long,
  uncertainObligations: Long,
  payloadPresent: Option[Boolean],
  operations: OperationCounts
)

/**
 * The contract a law host implements so RecoveryLaws can drive takeover,
 * revocation, identity rotation, clock, reclamation and fault gates against
 * any provider capability and payload adapter. A fixture is one scope of one
 * provider; open() logs a fresh browser in and claims a fresh view.
 */
trait RecoveryFixture[F[_], D] {
  type View

  /** The adapter's declared basis; the laws branch on it. */
  def payloadBasis: CleanupBasis

  /** The provider's windows and bounds, so the laws can advance the clock past them. */
  def referenceSettings: SnapshotReferenceSettings
  def open(): F[View]

  /** The same browser identity reclaims the same view under a new owner and epoch. */
  def takeOver(previous: View): F[View]

  /** Revoke the browser's authority, as logout does. */
  def revoke(view: View): F[Unit]

  /** A new authenticated session for the same browser and view: the projection identity changes. */
  def rotateIdentity(view: View): F[View]
  def advanceClock(by: FiniteDuration): F[Unit]

  /** Release the view and let the provider reclaim it, retiring its references. */
  def reclaimView(view: View): F[Unit]
  def store(view: View): ViewSnapshotStore[F, D]

  /** The same view bound with another writer format, for schema-change laws. */
  def rebind(view: View, format: SnapshotFormat[D]): ViewSnapshotStore[F, D]
  def gate(point: RecoveryFault): F[RecoveryGate[F]]

  /** One maintenance sweep of the given binding; workers 0 and 1 share the provider and store. */
  def maintenance(worker: Int = 0): F[Unit]
  def statistics(worker: Int = 0): RecoveryStatistics

  /** Remove the current reference's bytes directly from the adapter, outside the protocol. */
  def dropPayload(view: View): F[Unit]
  def observe(view: View): F[RecoveryObservation]
  def close(): F[Unit]
}
