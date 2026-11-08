package spoonbill.recovery.testkit

import java.util.concurrent.atomic.AtomicLong
import spoonbill.auth.BrowserView
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.{SnapshotFormat, ViewSnapshotError, ViewSnapshotStore}

/**
 * The generic part of a law host: counting wrappers around a provider
 * capability and a payload adapter, the gates probe, bound stores for
 * individual views and two maintenance bindings over the same provider and
 * store. A fixture supplies the provider-specific operations around it.
 */
final class RecoveryHarness[F[_], P, D](
  provider: SnapshotReferenceSupport[F, P],
  payloads: SnapshotPayloadStore[F],
  format: SnapshotFormat[D],
  settings: PayloadRecoverySettings = PayloadRecoverySettings()
)(using F: Effect[F]) {
  val gates = new RecoveryGates[F]
  private val reservations  = new AtomicLong()
  private val publications  = new AtomicLong()
  private val statusLookups = new AtomicLong()
  private val uploads       = new AtomicLong()
  private val downloads     = new AtomicLong()
  private val deletes       = new AtomicLong()

  val countedPayloads: SnapshotPayloadStore[F] = new SnapshotPayloadStore[F] {
    def cleanupBasis: CleanupBasis = payloads.cleanupBasis
    def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite] = {
      uploads.incrementAndGet(); payloads.put(ref, bytes)
    }
    def get(ref: PayloadRef): F[PayloadRead] = { downloads.incrementAndGet(); payloads.get(ref) }
    def delete(ref: PayloadRef): F[PayloadDelete] = { deletes.incrementAndGet(); payloads.delete(ref) }
  }

  val countedProvider: SnapshotReferenceSupport[F, P] = new SnapshotReferenceSupport[F, P] {
    def bind(view: BrowserView[P], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[F]] =
      provider.bind(view, policy).map { bound =>
        new BoundSnapshotReferences[F] {
          def ownerEpoch: ViewOwnershipEpoch                               = bound.ownerEpoch
          def acquireRead(): F[ReferenceLoad]                              = bound.acquireRead()
          def finishRead(pin: ReadPin): F[Either[ViewSnapshotError, Unit]] = bound.finishRead(pin)
          def releaseRead(pin: ReadPin): F[Unit]                           = bound.releaseRead(pin)
          def reserve(
            operation: PublicationId,
            expected: ViewRevision,
            ref: PayloadRef,
            schema: SnapshotSchema,
            intent: PublicationIntent
          ): F[ReservationOutcome] = {
            reservations.incrementAndGet(); bound.reserve(operation, expected, ref, schema, intent)
          }
          def publish(ticket: PublicationTicket): F[PublicationOutcome] = {
            publications.incrementAndGet(); bound.publish(ticket)
          }
          def status(operation: PublicationId): F[PublicationStatus] = {
            statusLookups.incrementAndGet(); bound.status(operation)
          }
        }
      }
    def maintenance: SnapshotReferenceMaintenance[F] = provider.maintenance
  }

  private val counters = new RecoveryCounters
  private val bindings = Vector.fill(2)(
    PayloadRecovery.instrumented[F, P, D, D](
      countedProvider,
      countedPayloads,
      format,
      value => value,
      (_, saved) => saved,
      settings,
      gates
    )
  )

  def binding(worker: Int): PayloadSnapshotBinding[F, P, D] = bindings(worker)

  /** The bound store of one view under the given writer format, gated by this harness. */
  def store(view: BrowserView[P], writer: SnapshotFormat[D] = format): Option[ViewSnapshotStore[F, D]] =
    countedProvider.bind(view, SnapshotSchemaPolicy.of(writer)).map { bound =>
      new ReferenceSnapshotStore(bound, countedPayloads, writer, gates, counters, () => false)
    }

  def operations: OperationCounts = OperationCounts(
    reservations.get(),
    publications.get(),
    statusLookups.get(),
    uploads.get(),
    downloads.get(),
    deletes.get()
  )
}
