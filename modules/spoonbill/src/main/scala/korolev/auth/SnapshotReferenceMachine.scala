package spoonbill.auth

import java.time.Instant
import java.util.UUID
import spoonbill.Qsid
import spoonbill.recovery.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}

/**
 * Publication and cleanup metadata only. Fences, schema callbacks and entropy
 * belong to the interpreter.
 */
private[auth] object SnapshotReferenceMachine {
  final case class Claim(id: UUID, generation: Long, expires: Instant)
  enum Cleanup {
    case Pending(attempts: Int, retryAfter: Option[Instant])
    case Claimed(claim: Claim, attempts: Int)
    case Deleted(claim: Claim)
  }
  enum Phase {
    case Staged(deadline: Instant)
    case Published(revision: ViewRevision)
    case Retired(since: Instant, revision: Option[ViewRevision], cleanup: Cleanup)
  }
  final case class Record[I](ticket: PublicationTicket, qsid: Qsid, owner: UUID, identity: I, phase: Phase) {
    def retired: Boolean = phase match {
      case Phase.Retired(_, _, _) => true
      case _                      => false
    }
    def deleted: Boolean = phase match {
      case Phase.Retired(_, _, Cleanup.Deleted(_)) => true
      case _                                       => false
    }
    def attempts: Int = phase match {
      case Phase.Retired(_, _, Cleanup.Pending(value, _)) => value
      case Phase.Retired(_, _, Cleanup.Claimed(_, value)) => value
      case _                                              => 0
    }
  }
  final case class ViewState(revision: ViewRevision, published: Option[PublicationId])
  final case class Pin(
    id: UUID,
    operation: PublicationId,
    qsid: Qsid,
    owner: UUID,
    epoch: Long,
    revision: ViewRevision,
    expires: Instant
  )
  final case class State[I](records: Map[PublicationId, Record[I]], views: Map[Qsid, ViewState], pins: Map[UUID, Pin])
  def initial[I]: State[I] = State(Map.empty, Map.empty, Map.empty)

  enum Error {
    case RevisionConflict, MalformedSnapshot, ResetNotRequired, ResetRequired, StorageFailure, RevisionExhausted
  }
  sealed trait Read[+I]
  object Read {
    final case class Empty(revision: ViewRevision)                           extends Read[Nothing]
    case object Malformed                                                    extends Read[Nothing]
    final case class Published[I](record: Record[I], revision: ViewRevision) extends Read[I]
  }

  def current[I](state: State[I], qsid: Qsid): ViewState =
    state.views.getOrElse(qsid, ViewState(ViewRevision.initial, None))

  def staged[I](record: Record[I]): Boolean = record.phase match {
    case Phase.Staged(_) => true
    case _               => false
  }

  def retire[I](state: State[I], operation: PublicationId, now: Instant): State[I] =
    state.records.get(operation).fold(state) { record =>
      if (record.retired) state
      else {
        val revision = record.phase match {
          case Phase.Published(value) => Some(value)
          case _                      => None
        }
        state.copy(records =
          state.records.updated(operation, record.copy(phase = Phase.Retired(now, revision, Cleanup.Pending(0, None))))
        )
      }
    }

  def reclaim[I](state: State[I], reclaimed: Set[Qsid], now: Instant): State[I] = {
    val retired = state.records.foldLeft(state) { case (next, (operation, record)) =>
      if (reclaimed.contains(record.qsid) && !record.retired) retire(next, operation, now) else next
    }
    retired.copy(views = retired.views -- reclaimed)
  }

  def read[I](state: State[I], qsid: Qsid): Read[I] = {
    val view = current(state, qsid)
    view.published match {
      case None if view.revision.toLong == 0L => Read.Empty(view.revision)
      case None                               => Read.Malformed
      case Some(operation) =>
        state.records.get(operation) match {
          case Some(record) if record.phase == Phase.Published(view.revision) => Read.Published(record, view.revision)
          case _                                                              => Read.Malformed
        }
    }
  }

  def pin[I](state: State[I], value: Pin): State[I] = state.copy(pins = state.pins.updated(value.id, value))

  def finishRead[I](
    state: State[I],
    pin: ReadPin,
    qsid: Qsid,
    owner: UUID,
    epoch: Long,
    now: Instant
  ): (State[I], Boolean) = {
    val found = state.pins.get(pin.id).filter(held => held.owner == owner && held.epoch == epoch)
    val next  = if (found.nonEmpty) state.copy(pins = state.pins - pin.id) else state
    val valid = found.exists { held =>
      held.operation == pin.operation && now.isBefore(held.expires) &&
      state.views.get(qsid).exists(view => view.published.contains(pin.operation) && view.revision == pin.revision)
    }
    (next, valid)
  }

  def releaseRead[I](state: State[I], pin: ReadPin, owner: UUID, epoch: Long): State[I] =
    if (state.pins.get(pin.id).exists(held => held.owner == owner && held.epoch == epoch))
      state.copy(pins = state.pins - pin.id)
    else state

  def openObligations[I](state: State[I]): Long =
    state.records.valuesIterator.count(record => (record.retired && !record.deleted) || staged(record)).toLong

  def reserve[I](
    state: State[I],
    qsid: Qsid,
    owner: UUID,
    ownerEpoch: ViewOwnershipEpoch,
    identity: I,
    operation: PublicationId,
    expected: ViewRevision,
    ref: PayloadRef,
    schema: SnapshotSchema,
    intent: PublicationIntent,
    storedMismatch: Boolean,
    now: Instant,
    settings: SnapshotReferenceSettings
  ): (State[I], Either[Error, PublicationTicket]) = {
    val view   = current(state, qsid)
    val stored = view.published.flatMap(state.records.get)
    val rejected =
      if (view.revision != expected) Some(Error.RevisionConflict)
      else if (stored.isEmpty && view.revision.toLong != 0L) Some(Error.MalformedSnapshot)
      else if (intent == PublicationIntent.Reset && !storedMismatch) Some(Error.ResetNotRequired)
      else if (intent == PublicationIntent.Save && storedMismatch) Some(Error.ResetRequired)
      else if (state.records.contains(operation) || openObligations(state) >= settings.maxOpenObligations)
        Some(Error.StorageFailure)
      else None
    rejected match {
      case Some(error) => (state, Left(error))
      case None =>
        val ticket = PublicationTicket(operation, ref, expected, intent, schema, ownerEpoch)
        val record = Record(ticket, qsid, owner, identity, Phase.Staged(now.plusSeconds(settings.reservationSeconds)))
        (state.copy(records = state.records.updated(operation, record)), Right(ticket))
    }
  }

  def publish[I](
    state: State[I],
    ticket: PublicationTicket,
    qsid: Qsid,
    owner: UUID,
    now: Instant
  ): (State[I], Either[Error, ViewRevision]) = state.records.get(ticket.operation) match {
    case Some(record) if record.owner == owner && record.qsid == qsid && record.ticket == ticket =>
      record.phase match {
        case Phase.Staged(deadline) if now.isBefore(deadline) =>
          val view = current(state, qsid)
          if (view.revision != ticket.expected) (state, Left(Error.RevisionConflict))
          else
            ticket.expected.next match {
              case Left(_) => (state, Left(Error.RevisionExhausted))
              case Right(next) =>
                val retired = view.published.fold(state)(previous => retire(state, previous, now))
                (
                  retired.copy(
                    records = retired.records.updated(ticket.operation, record.copy(phase = Phase.Published(next))),
                    views = retired.views.updated(qsid, ViewState(next, Some(ticket.operation)))
                  ),
                  Right(next)
                )
            }
        case _ => (state, Left(Error.StorageFailure))
      }
    case _ => (state, Left(Error.StorageFailure))
  }

  def status[I](
    state: State[I],
    operation: PublicationId,
    owner: UUID,
    epoch: ViewOwnershipEpoch,
    now: Instant
  ): PublicationStatus =
    state.records.get(operation) match {
      case Some(record) if record.owner == owner && record.ticket.ownerEpoch == epoch =>
        record.phase match {
          case Phase.Staged(deadline) if now.isBefore(deadline) => PublicationStatus.Prepared(record.ticket)
          case Phase.Staged(_)                                  => PublicationStatus.NotPublished
          case Phase.Published(revision)                        => PublicationStatus.Published(revision)
          case Phase.Retired(_, Some(revision), _)              => PublicationStatus.Published(revision)
          case Phase.Retired(_, None, _)                        => PublicationStatus.NotPublished
        }
      case _ => PublicationStatus.Unresolved
    }

  /**
   * Expire reservations and select candidates before the interpreter allocates
   * claim identities.
   */
  def garbage[I](state: State[I], limit: Int, now: Instant): (State[I], Vector[PublicationId]) = {
    val expired = state.records.foldLeft(state) { case (next, (operation, record)) =>
      record.phase match {
        case Phase.Staged(deadline) if !now.isBefore(deadline) => retire(next, operation, now)
        case _                                                 => next
      }
    }
    val pinned = expired.pins.valuesIterator.filter(pin => now.isBefore(pin.expires)).map(_.operation).toSet
    val eligible = expired.records.toVector.collect {
      case (operation, Record(_, _, _, _, Phase.Retired(since, _, cleanup)))
          if eligibleCleanup(cleanup, now) && !pinned.contains(operation) =>
        (since, operation)
    }
    (expired, eligible.sortBy(_._1).take(limit).map(_._2))
  }

  private def eligibleCleanup(cleanup: Cleanup, now: Instant): Boolean = cleanup match {
    case Cleanup.Pending(_, retryAfter) => retryAfter.forall(time => !now.isBefore(time))
    case Cleanup.Claimed(claim, _)      => !now.isBefore(claim.expires)
    case Cleanup.Deleted(_)             => false
  }

  def claim[I](
    state: State[I],
    operation: PublicationId,
    id: UUID,
    now: Instant,
    leaseSeconds: Long
  ): (State[I], Option[GarbageClaim]) = state.records.get(operation) match {
    case Some(record @ Record(_, _, _, _, Phase.Retired(since, revision, cleanup))) if eligibleCleanup(cleanup, now) =>
      val generation = cleanup match {
        case Cleanup.Claimed(previous, _) => previous.generation + 1L
        case _                            => 1L
      }
      val next    = Claim(id, generation, now.plusSeconds(leaseSeconds))
      val updated = record.copy(phase = Phase.Retired(since, revision, Cleanup.Claimed(next, record.attempts)))
      (
        state.copy(records = state.records.updated(operation, updated)),
        Some(GarbageClaim(operation, record.ticket.ref, next.id, next.generation, next.expires))
      )
    case _ => (state, None)
  }

  private def owns(held: Claim, presented: GarbageClaim): Boolean =
    held.id == presented.claimId && held.generation == presented.generation

  def completeDelete[I](state: State[I], claim: GarbageClaim): (State[I], ClaimUpdate) =
    state.records.get(claim.operation) match {
      case Some(record @ Record(_, _, _, _, Phase.Retired(since, revision, Cleanup.Claimed(held, _))))
          if owns(held, claim) =>
        (
          state.copy(records =
            state.records
              .updated(claim.operation, record.copy(phase = Phase.Retired(since, revision, Cleanup.Deleted(held))))
          ),
          ClaimUpdate.Applied
        )
      case Some(Record(_, _, _, _, Phase.Retired(_, _, Cleanup.Deleted(held)))) if owns(held, claim) =>
        (state, ClaimUpdate.AlreadyApplied)
      case _ => (state, ClaimUpdate.StaleClaim)
    }

  def retryDelete[I](
    state: State[I],
    claim: GarbageClaim,
    now: Instant,
    backoffSeconds: Long
  ): (State[I], ClaimUpdate) =
    state.records.get(claim.operation) match {
      case Some(record @ Record(_, _, _, _, Phase.Retired(since, revision, Cleanup.Claimed(held, attempts))))
          if owns(held, claim) =>
        val retry = Cleanup.Pending(attempts + 1, Some(now.plusSeconds(backoffSeconds)))
        (
          state.copy(records =
            state.records.updated(claim.operation, record.copy(phase = Phase.Retired(since, revision, retry)))
          ),
          ClaimUpdate.Applied
        )
      case Some(Record(_, _, _, _, Phase.Retired(_, _, Cleanup.Deleted(held)))) if owns(held, claim) =>
        (state, ClaimUpdate.AlreadyApplied)
      case _ => (state, ClaimUpdate.StaleClaim)
    }

  def backlog[I](state: State[I]): CleanupBacklog = {
    val open = state.records.valuesIterator.filter(record => record.retired && !record.deleted).toVector
    CleanupBacklog(
      open.size.toLong,
      open.count(_.attempts > 0).toLong,
      state.records.valuesIterator.count(staged).toLong,
      open
        .flatMap(record =>
          record.phase match {
            case Phase.Retired(since, _, _) => Some(since)
            case _                          => None
          }
        )
        .minOption
    )
  }
}
