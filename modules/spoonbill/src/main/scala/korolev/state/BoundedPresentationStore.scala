package spoonbill.state

import avocet.Id
import java.time.Instant
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Versions.*
import spoonbill.security.ViewFence

object BoundedPresentationStore {
  final case class Limits(
    active: Int = 1024,
    pending: Int = 256,
    bootstrap: Int = 1024,
    disconnected: Int = 1024,
    history: Int = 131072,
    nodesPerView: Int = 10000,
    bootstrapSeconds: Long = 30,
    pendingSeconds: Long = 30,
    reconnectSeconds: Long = 120
  ) {
    require(Vector(active, pending, bootstrap, disconnected, history, nodesPerView).forall(_ > 0))
    require(Vector(bootstrapSeconds, pendingSeconds, reconnectSeconds).forall(_ > 0))
  }
  enum Error { case Missing, AlreadyExists, StaleOwner, WrongView, Capacity, Closed }
  final class Rejected(val error: Error) extends RuntimeException(s"Presentation rejected: $error")
  final case class Resources(active: Int, pending: Int, bootstrap: Int, disconnected: Int, history: Int, nodes: Int)
}

/**
 * Local, bounded storage of immutable ordinary presentation only.
 *
 * The caller must establish current authority and serialize its provider's
 * ownership changes with claim/publication/invalidation. A ViewFence is not
 * itself authentication. This store provides no cross-node lease or recovery.
 * Sensitive disclosures, credentials and mutable application values must never
 * enter these node maps; sensitive regions retain their separate lifecycle.
 *
 * Ownership history is bounded and never evicted or reset during this store's
 * lifetime. Exhaustion refuses new identities. Restart drops all presentation;
 * the authoritative provider must still preserve its generation/epoch history.
 */
final class BoundedPresentationStore[F[_], S](
  limits: BoundedPresentationStore.Limits,
  clock: () => Instant
)(using F: Effect[F]) {
  import BoundedPresentationStore.*
  private enum Phase { case Bootstrap, Pending, Active, Disconnected }
  private case class Entry(
    token: AnyRef,
    fence: Option[ViewFence],
    phase: Phase,
    deadline: Instant,
    nodes: Map[Id, Any]
  )
  private val monitor  = new Object
  private var entries  = Map.empty[Qsid, Entry]
  private var history  = Map.empty[Qsid, ViewFence]
  private var observed = Instant.MIN
  private var closed   = false

  private def reject(error: Error): Nothing = throw new Rejected(error)
  private def at[A](body: Instant => A): A = {
    val sample = clock()
    monitor.synchronized {
      if (closed) reject(Error.Closed)
      if (sample.isAfter(observed)) observed = sample
      body(observed)
    }
  }
  private def effect[A](body: Instant => A): F[A] = F.delay(at(body))
  private def expired(entry: Entry, time: Instant): Boolean =
    entry.phase != Phase.Active && !time.isBefore(entry.deadline)
  private def sweep(time: Instant): Unit =
    entries = entries.filterNot { case (_, entry) => expired(entry, time) }
  private def count(phase: Phase): Int = entries.valuesIterator.count(_.phase == phase)
  private def checked(qsid: Qsid, token: AnyRef, time: Instant): Entry = {
    val entry = entries.getOrElse(qsid, reject(Error.StaleOwner))
    if (!(entry.token eq token) || entry.phase == Phase.Disconnected) reject(Error.StaleOwner)
    if (expired(entry, time)) {
      entries -= qsid
      reject(Error.StaleOwner)
    }
    entry
  }
  private def manager(qsid: Qsid, token: AnyRef): StateManager[F] = new StateManager[F] {
    def read[T: StateDeserializer](nodeId: Id): F[Option[T]] = effect { time =>
      checked(qsid, token, time).nodes.get(nodeId).asInstanceOf[Option[T]]
    }
    def write[T: StateSerializer](nodeId: Id, value: T): F[Unit] = effect { time =>
      val entry = checked(qsid, token, time)
      if (!entry.nodes.contains(nodeId) && entry.nodes.size >= limits.nodesPerView) reject(Error.Capacity)
      entries += qsid -> entry.copy(nodes = entry.nodes.updated(nodeId, value))
    }
    def delete(nodeId: Id): F[Unit] = effect { time =>
      val entry = checked(qsid, token, time)
      entries += qsid -> entry.copy(nodes = entry.nodes - nodeId)
    }
    def snapshot: F[StateManager.Snapshot] = effect { time =>
      val nodes = checked(qsid, token, time).nodes
      new StateManager.Snapshot {
        def apply[T: StateDeserializer](nodeId: Id): Option[T] = at { currentTime =>
          checked(qsid, token, currentTime)
          nodes.get(nodeId).asInstanceOf[Option[T]]
        }
      }
    }
  }

  def bootstrap(qsid: Qsid, topLevel: S): F[StateManager[F]] = effect { time =>
    sweep(time)
    if (entries.contains(qsid)) reject(Error.AlreadyExists)
    if (count(Phase.Bootstrap) >= limits.bootstrap) reject(Error.Capacity)
    val token = new Object
    entries += qsid -> Entry(
      token,
      None,
      Phase.Bootstrap,
      time.plusSeconds(limits.bootstrapSeconds),
      Map(Id.TopLevel -> topLevel)
    )
    manager(qsid, token)
  }

  /**
   * Reserve a new provider-issued owner before asynchronous setup. The old
   * manager and all its snapshots are detached immediately. Call publish only
   * after setup completes; on setup failure/cancellation call release. A failed
   * late publish requires the caller to release its own external setup
   * resources.
   */
  def claim(qsid: Qsid, fence: ViewFence): F[Lease] = effect { time =>
    sweep(time)
    val previous = entries.getOrElse(qsid, reject(Error.Missing))
    history.get(qsid) match {
      case Some(prior) if prior.viewId != fence.viewId             => reject(Error.WrongView)
      case Some(prior) if fence.epoch.toLong <= prior.epoch.toLong => reject(Error.StaleOwner)
      case None if history.size >= limits.history                  => reject(Error.Capacity)
      case _                                                       => ()
    }
    if (fence.epoch.toLong <= 0) reject(Error.StaleOwner)
    val pendingAdded = if (previous.phase == Phase.Pending) 0 else 1
    val activeAdded  = if (previous.phase == Phase.Pending || previous.phase == Phase.Active) 0 else 1
    if (
      count(Phase.Pending) + pendingAdded > limits.pending ||
      count(Phase.Active) + count(Phase.Pending) + activeAdded > limits.active
    ) reject(Error.Capacity)
    val token = new Object
    history += qsid -> fence
    entries += qsid -> previous.copy(
      token = token,
      fence = Some(fence),
      phase = Phase.Pending,
      deadline = time.plusSeconds(limits.pendingSeconds)
    )
    new Lease(qsid, fence, token)
  }

  final class Lease private[BoundedPresentationStore] (
    val qsid: Qsid,
    val fence: ViewFence,
    private val token: AnyRef
  ) {
    val manager: StateManager[F] = BoundedPresentationStore.this.manager(qsid, token)
    def publish(): F[Unit] = effect { time =>
      val entry = checked(qsid, token, time)
      entries += qsid -> entry.copy(phase = Phase.Active, deadline = Instant.MAX)
    }
    def release(): F[Unit] = F.delay {
      val sample = clock()
      monitor.synchronized {
        if (sample.isAfter(observed)) observed = sample
        entries.get(qsid).filter(_.token eq token).foreach { entry =>
          entries -= qsid
          sweep(observed)
          if (!closed && entry.phase == Phase.Active && count(Phase.Disconnected) < limits.disconnected) {
            entries += qsid -> entry.copy(
              token = new Object,
              phase = Phase.Disconnected,
              deadline = observed.plusSeconds(limits.reconnectSeconds)
            )
          }
        }
      }
    }
    def invalidate(): F[Unit] = BoundedPresentationStore.this.invalidate(qsid, fence)
  }

  /**
   * Invalidates this exact ownership, including retained disconnected state. A
   * delayed old-owner invalidation never erases a successor's presentation.
   */
  def invalidate(qsid: Qsid, fence: ViewFence): F[Unit] = F.delay {
    monitor.synchronized {
      if (entries.get(qsid).exists(_.fence.contains(fence))) entries -= qsid
    }
  }

  val storage: StateStorage[F, S] = new StateStorage[F, S] {
    def exists(deviceId: DeviceId, sessionId: SessionId): F[Boolean] = effect { time =>
      val qsid = Qsid(deviceId, sessionId)
      entries.get(qsid) match {
        case Some(entry) if expired(entry, time) => entries -= qsid; false
        case entry                               => entry.isDefined
      }
    }
    def create(deviceId: DeviceId, sessionId: SessionId, state: S): F[StateManager[F]] =
      bootstrap(Qsid(deviceId, sessionId), state)
    def get(deviceId: DeviceId, sessionId: SessionId): F[StateManager[F]] = effect { time =>
      val qsid  = Qsid(deviceId, sessionId)
      val entry = entries.getOrElse(qsid, reject(Error.Missing))
      checked(qsid, entry.token, time)
      manager(qsid, entry.token)
    }
    // v3 removal carries no owner identity, so it cannot safely revoke even a
    // bootstrap that a newer setup may own. Lease cleanup owns all removal.
    def remove(deviceId: DeviceId, sessionId: SessionId): Unit = ()
  }

  def resources: F[Resources] = F.delay {
    val sample = clock()
    monitor.synchronized {
      if (sample.isAfter(observed)) observed = sample
      sweep(observed)
      Resources(
        count(Phase.Active),
        count(Phase.Pending),
        count(Phase.Bootstrap),
        count(Phase.Disconnected),
        history.size,
        entries.valuesIterator.map(_.nodes.size).sum
      )
    }
  }
  def close(): F[Unit] = F.delay {
    monitor.synchronized {
      closed = true
      entries = Map.empty
      history = Map.empty
    }
  }
}
