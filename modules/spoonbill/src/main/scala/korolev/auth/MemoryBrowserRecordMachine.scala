package spoonbill.auth

import java.time.Instant
import java.util.UUID
import spoonbill.Qsid
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.ViewFence

/** Immutable records and decisions over a supplied provider time. */
private[auth] object MemoryBrowserRecordMachine {
  type Key  = Vector[Byte]
  type Slot = BrowserReplacementSlot
  private val Slot = BrowserReplacementSlot

  // Only ownership metadata is retained, never a view's raw transport cookie.
  final case class ReplacementView(qsid: Qsid, fence: ViewFence, connectionId: ConnectionId)
  final case class Replacement(
    predecessor: BrowserReplacementPredecessor,
    view: ReplacementView,
    reservation: Option[BrowserReplacementReservation] = None
  )
  final case class Session(
    id: UUID,
    binding: Key,
    credential: Key,
    generation: Long,
    expires: Instant,
    completion: UUID,
    activeGeneration: Option[Long] = None
  )
  final case class Completion[+M](
    sessionId: UUID,
    hostCompletionId: UUID,
    expires: Instant,
    material: Option[M],
    deliveries: Int = 0
  )
  final case class View(binding: Key, owner: Option[UUID], epoch: Long, connectionId: ConnectionId, touchedAt: Instant)
  final case class Records[H, M](
    host: H,
    slots: Map[Key, Slot] = Map.empty,
    sessions: Map[UUID, Session] = Map.empty,
    credentials: Map[Key, UUID] = Map.empty,
    completions: Map[UUID, Completion[M]] = Map.empty,
    ceremonies: Map[CeremonyId, BrowserCeremonyRecord] = Map.empty,
    views: Map[Qsid, View] = Map.empty,
    replacements: Map[CeremonyId, Replacement] = Map.empty,
    bindingActivity: Map[Key, Instant] = Map.empty
  )
  final case class Reclaimed[H, M](
    records: Records[H, M],
    bindingFloor: Long,
    viewFloor: Long,
    removedViews: Set[Qsid]
  )

  private def elapsed(since: Instant, seconds: Long, time: Instant): Boolean =
    !time.isBefore(since.plusSeconds(seconds))

  def captureBinding[H, M](
    records: Records[H, M],
    key: Key,
    hasToken: Boolean,
    limits: BrowserPersistenceLimits,
    generationFloor: Long
  ): Either[BrowserFailure, Records[H, M]] =
    if (records.slots.contains(key)) Right(records)
    else if (hasToken) Left(BrowserFailure.Denied)
    else if (
      records.slots.size >= limits.retainedBindings ||
      records.slots.valuesIterator.count(_.current.isEmpty) >= limits.anonymousBindings
    ) Left(BrowserFailure.Capacity)
    else Right(records.copy(slots = records.slots.updated(key, Slot(generationFloor, None))))

  /**
   * Called after the interpreter has authorized identity and validated the view
   * key.
   */
  def claimEpoch[H, M](
    records: Records[H, M],
    qsid: Qsid,
    binding: Key,
    connection: ConnectionId,
    existingOnly: Boolean,
    limits: BrowserPersistenceLimits,
    epochFloor: Long
  ): Either[BrowserFailure, Long] = {
    val previous = records.views.get(qsid)
    if (existingOnly && previous.isEmpty) Left(BrowserFailure.Missing)
    else if (previous.exists(_.binding != binding)) Left(BrowserFailure.Denied)
    else if (previous.isEmpty && records.views.size >= limits.retainedViews) Left(BrowserFailure.Capacity)
    else if (
      !previous.exists(_.owner.nonEmpty) &&
      records.views.valuesIterator.count(v => v.binding == binding && v.owner.nonEmpty) >= limits.activeViewsPerBinding
    )
      Left(BrowserFailure.Capacity)
    else if (records.views.valuesIterator.exists(v => v.owner.nonEmpty && v.connectionId == connection))
      Left(BrowserFailure.Denied)
    else
      previous match {
        case Some(view) if view.epoch == Long.MaxValue => Left(BrowserFailure.Capacity)
        case Some(view)                                => Right(view.epoch + 1L)
        case None                                      => Right(epochFloor)
      }
  }

  def delivered[M](completion: Completion[M], replacement: Boolean, maximum: Int): Completion[M] =
    completion.copy(
      deliveries = completion.deliveries + 1,
      material = if (replacement && completion.deliveries + 1 >= maximum) None else completion.material
    )

  def expireMaterial[M](completions: Map[UUID, Completion[M]], time: Instant): Map[UUID, Completion[M]] =
    completions.map { case (id, completion) =>
      id -> (if (!time.isBefore(completion.expires)) completion.copy(material = None) else completion)
    }

  def logout[H, M](records: Records[H, M], key: Key): Either[BrowserFailure, Option[Records[H, M]]] =
    records.slots.get(key) match {
      case None => Right(None)
      case Some(slot) =>
        val replacementOwners = slot.replacement.nonEmpty || records.replacements.valuesIterator
          .exists(_.reservation.exists(value => slot.current.contains(value.successor.sessionId)))
        if (slot.generation == Long.MaxValue) Left(BrowserFailure.Capacity)
        else
          Right(
            Some(
              records.copy(
                slots = records.slots.updated(key, Slot(slot.generation + 1L, None)),
                completions = records.completions.map { case (id, completion) =>
                  id -> (if (records.sessions(completion.sessionId).binding == key) completion.copy(material = None)
                         else completion)
                },
                views =
                  if (!replacementOwners) records.views
                  else
                    records.views.map { case (id, view) =>
                      id -> (if (view.binding == key) view.copy(owner = None) else view)
                    }
              )
            )
          )
    }

  def reap[H, M](
    before: Records[H, M],
    time: Instant,
    limits: BrowserPersistenceLimits,
    bindingFloor: Long,
    viewFloor: Long
  ): Either[BrowserFailure, Reclaimed[H, M]] = {
    val expiredCurrent = before.slots.iterator.collect {
      case (key, slot)
          if slot.replacement.isEmpty && slot.current
            .exists(id => before.sessions.get(id).exists(s => !time.isBefore(s.expires))) ||
            slot.replacement.exists(id =>
              before.replacements
                .get(id)
                .flatMap(_.reservation)
                .exists(r => !time.isBefore(r.successor.expiresAt))
            ) =>
        key
    }.toSet
    if (before.slots.exists { case (key, slot) => expiredCurrent(key) && slot.generation == Long.MaxValue })
      Left(BrowserFailure.Capacity)
    else {
      // An expired committed replacement never restores its predecessor.
      val slots = before.slots.map { case (key, slot) =>
        key -> (if (expiredCurrent(key)) Slot(slot.generation + 1L, None) else slot)
      }
      val sessions = before.sessions.filterNot { case (_, session) =>
        elapsed(session.expires, limits.retention.historySeconds, time)
      }
      val completions = before.completions.collect {
        case (id, completion) if sessions.contains(completion.sessionId) =>
          id -> (if (!time.isBefore(completion.expires)) completion.copy(material = None) else completion)
      }
      val ceremonies = before.ceremonies.filter { case (_, record) =>
        !elapsed(record.expiresAt, limits.retention.historySeconds, time) || completions.contains(record.completionId)
      }
      val replacements = before.replacements.filter { case (id, _) => ceremonies.contains(id) }
      val retiredViews = before.views.map { case (id, view) =>
        val expired = expiredCurrent(view.binding) || elapsed(view.touchedAt, limits.retention.idleViewSeconds, time)
        id -> (if (view.owner.nonEmpty && expired) view.copy(owner = None) else view)
      }
      val removedViews = retiredViews.filter { case (_, view) =>
        view.owner.isEmpty && elapsed(view.touchedAt, limits.retention.idleViewSeconds, time)
      }
      val views    = retiredViews -- removedViews.keySet
      val activity = before.bindingActivity ++ expiredCurrent.iterator.map(_ -> time)
      val referencedBindings = sessions.valuesIterator.map(_.binding).toSet ++
        ceremonies.valuesIterator.map(_.bindingKey) ++ views.valuesIterator.map(_.binding)
      val removedBindings = slots.filter { case (key, slot) =>
        slot.current.isEmpty && slot.replacement.isEmpty && !referencedBindings(key) &&
          activity.get(key).exists(elapsed(_, limits.retention.idleBindingSeconds, time))
      }
      if (
        removedBindings.valuesIterator.exists(_.generation == Long.MaxValue) ||
        removedViews.valuesIterator.exists(_.epoch == Long.MaxValue)
      ) Left(BrowserFailure.Capacity)
      else {
        val nextBindingFloor = removedBindings.valuesIterator.map(_.generation + 1L).foldLeft(bindingFloor)(math.max)
        val nextViewFloor    = removedViews.valuesIterator.map(_.epoch + 1L).foldLeft(viewFloor)(math.max)
        Right(
          Reclaimed(
            before.copy(
              slots = slots -- removedBindings.keySet,
              sessions = sessions,
              credentials = before.credentials.filter { case (_, id) => sessions.contains(id) },
              completions = completions,
              ceremonies = ceremonies,
              replacements = replacements,
              views = views,
              bindingActivity = activity -- removedBindings.keySet
            ),
            nextBindingFloor,
            nextViewFloor,
            removedViews.keySet
          )
        )
      }
    }
  }
}
