package spoonbill.state

import BoundedPresentationStore.{Error, Limits}
import avocet.Id
import java.time.Instant
import spoonbill.Qsid
import spoonbill.security.Versions.*
import spoonbill.security.ViewFence

/**
 * Ownership rules over immutable metadata. Lifecycle changes carry ordinary
 * immutable node maps unchanged; node updates remain with the interpreter.
 */
private[state] object PresentationMachine {
  enum Phase  { case Bootstrap, Pending, Active, Disconnected }
  enum Access { case Current, Stale, Expired                  }
  enum Registry {
    case Open(history: Map[Qsid, ViewFence])
    case Closed
  }
  final case class Entry(
    token: AnyRef,
    leaseToken: Option[AnyRef],
    fence: Option[ViewFence],
    phase: Phase,
    deadline: Instant,
    nodes: Map[Id, Any],
    resetBrowser: Boolean
  )

  val initial: Registry                   = Registry.Open(Map.empty)
  def closed(registry: Registry): Boolean = registry eq Registry.Closed
  def history(registry: Registry): Map[Qsid, ViewFence] = registry match {
    case Registry.Open(values) => values
    case Registry.Closed       => Map.empty
  }
  def remember(registry: Registry, qsid: Qsid, fence: ViewFence): Registry = registry match {
    case Registry.Open(values) => Registry.Open(values.updated(qsid, fence))
    case Registry.Closed       => registry
  }
  def reclaim(registry: Registry, present: Set[Qsid]): Registry = registry match {
    case Registry.Open(values) => Registry.Open(values.filter { case (key, _) => present.contains(key) })
    case Registry.Closed       => registry
  }
  def close(registry: Registry): Registry = Registry.Closed

  /**
   * The monotone clock remains its own primitive frontier, with no aggregate
   * copy per read.
   */
  def advances(previous: Instant, sample: Instant): Boolean =
    sample.isAfter(previous)

  def expired(entry: Entry, time: Instant): Boolean =
    entry.phase != Phase.Active && !time.isBefore(entry.deadline)

  def sweep(entries: Map[Qsid, Entry], time: Instant): Map[Qsid, Entry] =
    entries.filterNot { case (_, entry) => expired(entry, time) }

  def access(entry: Entry, token: AnyRef, time: Instant): Access =
    if (!currentOwner(entry, token)) Access.Stale
    else if (expired(entry, time)) Access.Expired
    else Access.Current

  /**
   * Primitive hot query; the interpreter preserves stale-before-expiry effect
   * ordering.
   */
  def currentOwner(entry: Entry, token: AnyRef): Boolean =
    (entry.token eq token) && entry.phase != Phase.Disconnected

  def bootstrapError(exists: Boolean, bootstrapCount: Int, limit: Int): Option[Error] =
    if (exists) Some(Error.AlreadyExists)
    else if (bootstrapCount >= limit) Some(Error.Capacity)
    else None

  def claimFenceError(registry: Registry, qsid: Qsid, fence: ViewFence, historyLimit: Int): Option[Error] = {
    val prior = history(registry)
    if (closed(registry)) Some(Error.Closed)
    else
      prior.get(qsid) match {
        case Some(value) if value.viewId != fence.viewId             => Some(Error.WrongView)
        case Some(value) if fence.epoch.toLong <= value.epoch.toLong => Some(Error.StaleOwner)
        case None if prior.size >= historyLimit                      => Some(Error.Capacity)
        case _ if fence.epoch.toLong <= 0                            => Some(Error.StaleOwner)
        case _                                                       => None
      }
  }

  def claimCapacityError(previous: Phase, pending: Int, active: Int, limits: Limits): Option[Error] = {
    val pendingAdded = if (previous == Phase.Pending) 0 else 1
    val activeAdded  = if (previous == Phase.Pending || previous == Phase.Active) 0 else 1
    Option
      .when(pending + pendingAdded > limits.pending || active + pending + activeAdded > limits.active)(Error.Capacity)
  }

  def bootstrap(token: AnyRef, deadline: Instant, nodes: Map[Id, Any], resetBrowser: Boolean): Entry =
    Entry(token, None, None, Phase.Bootstrap, deadline, nodes, resetBrowser)

  def claim(entry: Entry, token: AnyRef, fence: ViewFence, deadline: Instant): Entry =
    entry.copy(token = token, leaseToken = Some(token), fence = Some(fence), phase = Phase.Pending, deadline = deadline)

  def publish(entry: Entry): Entry = entry.copy(phase = Phase.Active, deadline = Instant.MAX)

  def retainOnRelease(registry: Registry, phase: Phase, disconnected: Int, limit: Int): Boolean =
    !closed(registry) && phase == Phase.Active && disconnected < limit

  def disconnect(entry: Entry, managerToken: AnyRef, deadline: Instant): Entry =
    entry.copy(token = managerToken, phase = Phase.Disconnected, deadline = deadline)

  def canWrite(present: Boolean, size: Int, limit: Int): Boolean = present || size < limit
}
