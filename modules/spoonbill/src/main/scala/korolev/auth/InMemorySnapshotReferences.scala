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
  private enum Phase {
    case Staged(deadline: Instant)
    case Published(revision: ViewRevision)
    case Retired(since: Instant, revision: Option[ViewRevision])
  }
  private case class Claim(id: UUID, generation: Long, expires: Instant)
  private case class Record(
    ticket: PublicationTicket,
    qsid: Qsid,
    owner: UUID,
    identity: SnapshotIdentity,
    phase: Phase,
    claim: Option[Claim] = None,
    attempts: Int = 0,
    retryAfter: Option[Instant] = None,
    deleted: Boolean = false
  ) {
    def retired: Boolean = phase match {
      case Phase.Retired(_, _) => true
      case _                   => false
    }
  }
  private case class ViewState(revision: ViewRevision, published: Option[PublicationId])
  private case class Pin(
    id: UUID,
    operation: PublicationId,
    qsid: Qsid,
    owner: UUID,
    epoch: Long,
    revision: ViewRevision,
    expires: Instant
  )
  // Mutated only under the provider's monitor, through fenced and bookkeeping.
  private var records = Map.empty[PublicationId, Record]
  private var views   = Map.empty[Qsid, ViewState]
  private var pins    = Map.empty[UUID, Pin]

  // A reclaimed view retires its references in the provider's own sweep.
  persistence.onViewsReclaimed { (reclaimed, now) =>
    records.foreach { case (operation, record) =>
      if (reclaimed.contains(record.qsid) && !record.retired) retire(operation, now)
    }
    views = views -- reclaimed
  }

  private def retire(operation: PublicationId, now: Instant): Unit = records.get(operation).foreach { record =>
    val revision = record.phase match {
      case Phase.Published(value) => Some(value)
      case _                      => None
    }
    records = records.updated(operation, record.copy(phase = Phase.Retired(now, revision)))
  }

  private def expireReservations(now: Instant): Unit = records.foreach { case (operation, record) =>
    record.phase match {
      case Phase.Staged(deadline) if !now.isBefore(deadline) => retire(operation, now)
      case _                                                 => ()
    }
  }

  private def openObligations: Long = records.valuesIterator.count(record => record.retired && !record.deleted).toLong

  private def failure(value: BrowserFailure): ViewSnapshotError = value match {
    case BrowserFailure.Denied | BrowserFailure.Expired | BrowserFailure.Used => ViewSnapshotError.AccessDenied
    // A missing view row or a closed provider: this owner is stale.
    case BrowserFailure.Missing | BrowserFailure.Closed => ViewSnapshotError.StaleOwner
    case _                                              => ViewSnapshotError.StorageFailure
  }

  def bind(view: BrowserView[P], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[F]] =
    SnapshotIdentities.ofView(view).map(identity => new Bound(view, identity, policy))

  val maintenance: SnapshotReferenceMaintenance[F] = new Maintenance

  /** Test-safe counts of the model's records: no payload bytes, credentials or identities. */
  def inventory(qsid: Qsid): F[InMemorySnapshotReferences.Inventory] = F.map(persistence.bookkeeping { now =>
    val state   = views.getOrElse(qsid, ViewState(ViewRevision.initial, None))
    val all     = records.valuesIterator.toVector
    val open    = all.filter(record => record.retired && !record.deleted)
    InMemorySnapshotReferences.Inventory(
      state.revision.toLong,
      state.published.flatMap(records.get).map(_.ticket.ref),
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
      pins.valuesIterator.count(pin => now.isBefore(pin.expires)),
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

    private def current: ViewState = views.getOrElse(qsid, ViewState(ViewRevision.initial, None))

    def acquireRead(): F[ReferenceLoad] = F.map(fenced { now =>
      val state = current
      state.published match {
        case None if state.revision.toLong == 0L => Right(ReferenceLoad.Empty(state.revision))
        case None                                => Right(ReferenceLoad.Rejected(ViewSnapshotError.MalformedSnapshot))
        case Some(operation) =>
          records.get(operation) match {
            case Some(record) if record.phase == Phase.Published(state.revision) =>
              mismatch(record) match {
                case Some(reason) => Right(ReferenceLoad.ResetRequired(state.revision, reason))
                case None =>
                  val pin = Pin(
                    UUID.randomUUID(),
                    operation,
                    qsid,
                    owner,
                    epoch,
                    state.revision,
                    now.plusSeconds(settings.readPinSeconds)
                  )
                  pins += pin.id -> pin
                  Right(ReferenceLoad.Pinned(ReadPin(pin.id, operation, state.revision), record.ticket.ref, record.ticket.schema))
              }
            case _ => Right(ReferenceLoad.Rejected(ViewSnapshotError.MalformedSnapshot))
          }
      }
    })(_.fold(ReferenceLoad.Rejected.apply, load => load))

    def finishRead(pin: ReadPin): F[Either[ViewSnapshotError, Unit]] = fenced { now =>
      val found = pins.get(pin.id)
      pins -= pin.id
      found match {
        case Some(held)
            if held.owner == owner && held.epoch == epoch && held.operation == pin.operation &&
              now.isBefore(held.expires) &&
              views.get(qsid).exists(state => state.published.contains(pin.operation) && state.revision == pin.revision) =>
          Right(())
        case _ => Left(ViewSnapshotError.StaleOwner)
      }
    }

    def releaseRead(pin: ReadPin): F[Unit] = F.map(persistence.bookkeeping { _ =>
      pins.get(pin.id).foreach(held => if (held.owner == owner && held.epoch == epoch) pins -= pin.id)
    })(_ => ())

    def reserve(
      operation: PublicationId,
      expected: ViewRevision,
      ref: PayloadRef,
      schema: SnapshotSchema,
      intent: PublicationIntent
    ): F[ReservationOutcome] = F.map(fenced { now =>
      val state          = current
      val stored         = state.published.flatMap(records.get)
      val storedMismatch = stored.flatMap(mismatch)
      if (state.revision != expected) Left(ViewSnapshotError.RevisionConflict)
      else if (stored.isEmpty && state.revision.toLong != 0L) Left(ViewSnapshotError.MalformedSnapshot)
      else if (intent == PublicationIntent.Reset && storedMismatch.isEmpty) Left(ViewSnapshotError.ResetNotRequired)
      else if (intent == PublicationIntent.Save && storedMismatch.nonEmpty) Left(ViewSnapshotError.ResetRequired)
      else if (records.contains(operation) || openObligations >= settings.maxOpenObligations)
        Left(ViewSnapshotError.StorageFailure)
      else {
        val ticket = PublicationTicket(operation, ref, expected, intent, schema, ownerEpoch)
        records += operation -> Record(
          ticket,
          qsid,
          owner,
          identity,
          Phase.Staged(now.plusSeconds(settings.reservationSeconds))
        )
        Right(ticket)
      }
    }) {
      case Right(ticket) => ReservationOutcome.Prepared(ticket)
      case Left(error)   => ReservationOutcome.Rejected(error)
    }

    def publish(ticket: PublicationTicket): F[PublicationOutcome] = F.map(fenced { now =>
      records.get(ticket.operation) match {
        case Some(record) if record.owner == owner && record.qsid == qsid && record.ticket == ticket =>
          record.phase match {
            case Phase.Staged(deadline) if now.isBefore(deadline) =>
              val state = current
              if (state.revision != ticket.expected) Left(ViewSnapshotError.RevisionConflict)
              else
                ticket.expected.next.left.map(_ => ViewSnapshotError.RevisionExhausted).map { next =>
                  // Publication and retirement of the superseded reference are one step.
                  state.published.foreach(previous => retire(previous, now))
                  records = records.updated(ticket.operation, record.copy(phase = Phase.Published(next)))
                  views = views.updated(qsid, ViewState(next, Some(ticket.operation)))
                  next
                }
            case _ => Left(ViewSnapshotError.StorageFailure)
          }
        case _ => Left(ViewSnapshotError.StorageFailure)
      }
    }) {
      case Right(revision) => PublicationOutcome.Published(revision)
      case Left(error)     => PublicationOutcome.Rejected(error)
    }

    def status(operation: PublicationId): F[PublicationStatus] = F.map(persistence.bookkeeping { now =>
      records.get(operation) match {
        case Some(record) if record.owner == owner && record.ticket.ownerEpoch == ownerEpoch =>
          record.phase match {
            case Phase.Staged(deadline) if now.isBefore(deadline) => PublicationStatus.Prepared(record.ticket)
            case Phase.Staged(_)                                  => PublicationStatus.NotPublished
            case Phase.Published(revision)                        => PublicationStatus.Published(revision)
            case Phase.Retired(_, Some(revision))                 => PublicationStatus.Published(revision)
            case Phase.Retired(_, None)                           => PublicationStatus.NotPublished
          }
        case _ => PublicationStatus.Unresolved
      }
    })(_.getOrElse(PublicationStatus.Unresolved))
  }

  private final class Maintenance extends SnapshotReferenceMaintenance[F] {
    private def claimed(record: Record, claim: GarbageClaim): Boolean =
      record.claim.exists(current => current.id == claim.claimId && current.generation == claim.generation)

    def claimGarbage(limit: Int): F[Vector[GarbageClaim]] = F.map(persistence.bookkeeping { now =>
      expireReservations(now)
      val pinned = pins.valuesIterator.filter(pin => now.isBefore(pin.expires)).map(_.operation).toSet
      val eligible = records.toVector.collect {
        case (operation, record @ Record(_, _, _, _, Phase.Retired(since, _), claim, _, retryAfter, false))
            if claim.forall(current => !now.isBefore(current.expires)) &&
              retryAfter.forall(time => !now.isBefore(time)) && !pinned.contains(operation) =>
          (since, operation, record)
      }
      eligible.sortBy(_._1).take(limit).map { case (_, operation, record) =>
        val claim = Claim(
          UUID.randomUUID(),
          record.claim.fold(1L)(_.generation + 1L),
          now.plusSeconds(settings.claimLeaseSeconds)
        )
        records = records.updated(operation, record.copy(claim = Some(claim)))
        GarbageClaim(operation, record.ticket.ref, claim.id, claim.generation, claim.expires)
      }
    })(_.getOrElse(Vector.empty))

    def completeDelete(claim: GarbageClaim): F[ClaimUpdate] = F.map(persistence.bookkeeping { _ =>
      records.get(claim.operation) match {
        case Some(record) if claimed(record, claim) =>
          if (record.deleted) ClaimUpdate.AlreadyApplied
          else {
            records = records.updated(claim.operation, record.copy(deleted = true))
            ClaimUpdate.Applied
          }
        case _ => ClaimUpdate.StaleClaim
      }
    })(_.getOrElse(ClaimUpdate.StaleClaim))

    def retryDelete(claim: GarbageClaim): F[ClaimUpdate] = F.map(persistence.bookkeeping { now =>
      records.get(claim.operation) match {
        case Some(record) if claimed(record, claim) =>
          if (record.deleted) ClaimUpdate.AlreadyApplied
          else {
            records = records.updated(
              claim.operation,
              record.copy(
                claim = None,
                attempts = record.attempts + 1,
                retryAfter = Some(now.plusSeconds(settings.retryBackoffSeconds))
              )
            )
            ClaimUpdate.Applied
          }
        case _ => ClaimUpdate.StaleClaim
      }
    })(_.getOrElse(ClaimUpdate.StaleClaim))

    def backlog(): F[CleanupBacklog] = F.map(persistence.bookkeeping { _ =>
      val open = records.valuesIterator.filter(record => record.retired && !record.deleted).toVector
      CleanupBacklog(
        open.size.toLong,
        open.count(_.attempts > 0).toLong,
        open.flatMap(record => record.phase match {
          case Phase.Retired(since, _) => Some(since)
          case _                       => None
        }).minOption
      )
    })(_.getOrElse(CleanupBacklog(0L, 0L, None)))
  }
}
