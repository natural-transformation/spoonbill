package spoonbill.auth

import java.time.Instant
import java.util.UUID
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.recovery.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.{SnapshotIdentity, SnapshotResetReason, ViewSnapshotError}

/**
 * The deterministic in-memory model of the publication capability, hosted by
 * InMemoryBrowserPersistence. Every operation runs under the provider's
 * monitor after the same ownership and authority checks as its other
 * operations, so publication, takeover and retirement serialize exactly as
 * the protocol requires. It is a law host: it proves the reference, pin,
 * operation and garbage-claim protocol, not durability, shared-view output
 * admission or two-process recovery. Records live as long as the provider.
 */
object InMemorySnapshotReferences {

  /** Counts of the model's records for one view and its scope; diagnostics only. */
  final case class Inventory(
    revision: Long,
    current: Option[PayloadRef],
    published: Int,
    staged: Int,
    retired: Int,
    deleted: Int,
    openPins: Int,
    openObligations: Long,
    uncertainObligations: Long
  )
}

final class InMemorySnapshotReferences[F[_], H, P](
  persistence: InMemoryBrowserPersistence[F, H, P],
  settings: SnapshotReferenceSettings = SnapshotReferenceSettings()
)(using F: Effect[F])
    extends SnapshotReferenceSupport[F, P] {
  import SnapshotReferenceMachine.{Phase, Pin}
  private type Record = SnapshotReferenceMachine.Record[SnapshotIdentity]
  // Mutated only under the provider's monitor, through fenced and bookkeeping.
  private var state = SnapshotReferenceMachine.initial[SnapshotIdentity]
  private def decide[A](step: SnapshotReferenceMachine.State[SnapshotIdentity] =>
    (SnapshotReferenceMachine.State[SnapshotIdentity], A)): A = {
    val (next, result) = step(state)
    state = next
    result
  }

  // A reclaimed view retires its references in the provider's own sweep.
  persistence.onViewsReclaimed { (reclaimed, now) =>
    state = SnapshotReferenceMachine.reclaim(state, reclaimed, now)
  }

  private def failure(value: BrowserFailure): ViewSnapshotError = value match {
    case BrowserFailure.Denied | BrowserFailure.Expired | BrowserFailure.Used => ViewSnapshotError.AccessDenied
    // A missing view row or a closed provider: this owner is stale.
    case BrowserFailure.Missing | BrowserFailure.Closed => ViewSnapshotError.StaleOwner
    case _                                              => ViewSnapshotError.StorageFailure
  }

  private def failure(value: SnapshotReferenceMachine.Error): ViewSnapshotError = value match {
    case SnapshotReferenceMachine.Error.RevisionConflict  => ViewSnapshotError.RevisionConflict
    case SnapshotReferenceMachine.Error.MalformedSnapshot => ViewSnapshotError.MalformedSnapshot
    case SnapshotReferenceMachine.Error.ResetNotRequired  => ViewSnapshotError.ResetNotRequired
    case SnapshotReferenceMachine.Error.ResetRequired     => ViewSnapshotError.ResetRequired
    case SnapshotReferenceMachine.Error.StorageFailure    => ViewSnapshotError.StorageFailure
    case SnapshotReferenceMachine.Error.RevisionExhausted => ViewSnapshotError.RevisionExhausted
  }

  def bind(view: BrowserView[P], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[F]] =
    SnapshotIdentities.ofView(view).map(identity => new Bound(view, identity, policy))

  val maintenance: SnapshotReferenceMaintenance[F] = new Maintenance

  /** Test-safe counts of the model's records: no payload bytes, credentials or identities. */
  def inventory(qsid: Qsid): F[InMemorySnapshotReferences.Inventory] = F.map(persistence.bookkeeping { now =>
    val viewState = SnapshotReferenceMachine.current(state, qsid)
    val all     = state.records.valuesIterator.toVector
    val open    = all.filter(record => record.retired && !record.deleted)
    InMemorySnapshotReferences.Inventory(
      viewState.revision.toLong,
      viewState.published.flatMap(state.records.get).map(_.ticket.ref),
      all.count(record => record.qsid == qsid && (record.phase match {
        case Phase.Published(_) => true
        case _                  => false
      })),
      all.count(record => record.phase match {
        case Phase.Staged(_) => true
        case _               => false
      }),
      all.count(_.retired),
      all.count(_.deleted),
      state.pins.valuesIterator.count(pin => now.isBefore(pin.expires)),
      open.size.toLong,
      open.count(_.attempts > 0).toLong
    )
  })(_.getOrElse(InMemorySnapshotReferences.Inventory(0L, None, 0, 0, 0, 0, 0, 0L, 0L)))

  private final class Bound(view: BrowserView[P], identity: SnapshotIdentity, policy: SnapshotSchemaPolicy)
      extends BoundSnapshotReferences[F] {
    val ownerEpoch: ViewOwnershipEpoch = view.fence.epoch
    private val owner                  = view.ownerId
    private val epoch                  = view.epoch
    private val qsid                   = view.qsid

    private def fenced[A](body: Instant => Either[ViewSnapshotError, A]): F[Either[ViewSnapshotError, A]] =
      F.map(persistence.fenced(view)(body)) {
        case Right(result) => result
        case Left(refused) => Left(failure(refused))
      }

    private def mismatch(record: Record): Option[SnapshotResetReason] =
      if (record.identity != identity) Some(SnapshotResetReason.IdentityChanged)
      else if (!policy.accepts(record.ticket.schema)) Some(SnapshotResetReason.SchemaChanged)
      else None

    def acquireRead(): F[ReferenceLoad] = F.map(fenced { now =>
      SnapshotReferenceMachine.read(state, qsid) match {
        case SnapshotReferenceMachine.Read.Empty(revision) => Right(ReferenceLoad.Empty(revision))
        case SnapshotReferenceMachine.Read.Malformed => Right(ReferenceLoad.Rejected(ViewSnapshotError.MalformedSnapshot))
        case SnapshotReferenceMachine.Read.Published(record, revision) =>
          mismatch(record) match {
            case Some(reason) => Right(ReferenceLoad.ResetRequired(revision, reason))
            case None =>
              val pin = Pin(UUID.randomUUID(), record.ticket.operation, qsid, owner, epoch, revision,
                now.plusSeconds(settings.readPinSeconds))
              state = SnapshotReferenceMachine.pin(state, pin)
              Right(ReferenceLoad.Pinned(ReadPin(pin.id, pin.operation, revision), record.ticket.ref, record.ticket.schema))
          }
      }
    })(_.fold(ReferenceLoad.Rejected.apply, load => load))

    def finishRead(pin: ReadPin): F[Either[ViewSnapshotError, Unit]] = fenced { now =>
      if (decide(current => SnapshotReferenceMachine.finishRead(current, pin, qsid, owner, epoch, now))) Right(())
      else Left(ViewSnapshotError.StaleOwner)
    }

    def releaseRead(pin: ReadPin): F[Unit] = F.map(persistence.bookkeeping { _ =>
      state = SnapshotReferenceMachine.releaseRead(state, pin, owner, epoch)
    })(_ => ())

    def reserve(
      operation: PublicationId,
      expected: ViewRevision,
      ref: PayloadRef,
      schema: SnapshotSchema,
      intent: PublicationIntent
    ): F[ReservationOutcome] = F.map(fenced { now =>
      val current        = SnapshotReferenceMachine.current(state, qsid)
      val stored         = current.published.flatMap(state.records.get)
      val storedMismatch = stored.flatMap(mismatch)
      decide(current => SnapshotReferenceMachine.reserve(current, qsid, owner, ownerEpoch, identity,
        operation, expected, ref, schema, intent, storedMismatch.nonEmpty, now, settings)).left.map(failure)
    }) {
      case Right(ticket) => ReservationOutcome.Prepared(ticket)
      case Left(error)   => ReservationOutcome.Rejected(error)
    }

    def publish(ticket: PublicationTicket): F[PublicationOutcome] = F.map(fenced { now =>
      decide(current => SnapshotReferenceMachine.publish(current, ticket, qsid, owner, now)).left.map(failure)
    }) {
      case Right(revision) => PublicationOutcome.Published(revision)
      case Left(error)     => PublicationOutcome.Rejected(error)
    }

    def status(operation: PublicationId): F[PublicationStatus] = F.map(persistence.bookkeeping { now =>
      SnapshotReferenceMachine.status(state, operation, owner, ownerEpoch, now)
    })(_.getOrElse(PublicationStatus.Unresolved))
  }

  private final class Maintenance extends SnapshotReferenceMaintenance[F] {
    def claimGarbage(limit: Int): F[Vector[GarbageClaim]] = F.map(persistence.bookkeeping { now =>
      val eligible = decide(current => SnapshotReferenceMachine.garbage(current, limit, now))
      eligible.flatMap { operation =>
        val id = UUID.randomUUID()
        decide(current => SnapshotReferenceMachine.claim(current, operation, id, now, settings.claimLeaseSeconds))
      }
    })(_.getOrElse(Vector.empty))

    def completeDelete(claim: GarbageClaim): F[ClaimUpdate] = F.map(persistence.bookkeeping { _ =>
      decide(current => SnapshotReferenceMachine.completeDelete(current, claim))
    })(_.getOrElse(ClaimUpdate.StaleClaim))

    def retryDelete(claim: GarbageClaim): F[ClaimUpdate] = F.map(persistence.bookkeeping { now =>
      decide(current => SnapshotReferenceMachine.retryDelete(current, claim, now, settings.retryBackoffSeconds))
    })(_.getOrElse(ClaimUpdate.StaleClaim))

    def backlog(): F[CleanupBacklog] = F.map(persistence.bookkeeping { _ =>
      SnapshotReferenceMachine.backlog(state)
    })(_.getOrElse(CleanupBacklog(0L, 0L, 0L, None)))
  }
}
