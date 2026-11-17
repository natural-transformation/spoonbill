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
 * Public operations retain bounded ownership history for the store's lifetime.
 * The framework coordinator may reclaim absent history after authoritative
 * provider checks; managers and leases retain independent unforgeable tokens.
 * Restart drops all presentation; the authoritative provider still preserves
 * its generation/epoch fences.
 */
final class BoundedPresentationStore[F[_], S](
  limits: BoundedPresentationStore.Limits,
  clock: () => Instant
)(using F: Effect[F]) {
  import BoundedPresentationStore.*
  import PresentationMachine.Phase
  private type Entry = PresentationMachine.Entry
  private val monitor  = new Object
  private var entries  = Map.empty[Qsid, Entry]
  private var registry = PresentationMachine.initial
  private var observed = Instant.MIN

  private def reject(error: Error): Nothing = throw new Rejected(error)
  private inline def at[A](inline body: Instant => A): A = {
    val sample = clock()
    monitor.synchronized {
      if (PresentationMachine.closed(registry)) reject(Error.Closed)
      if (PresentationMachine.advances(observed, sample)) observed = sample
      body(observed)
    }
  }
  private inline def effect[A](inline body: Instant => A): F[A] = F.delay(at(body))
  private def expired(entry: Entry, time: Instant): Boolean =
    PresentationMachine.expired(entry, time)
  private def sweep(time: Instant): Unit =
    entries = PresentationMachine.sweep(entries, time)
  private def count(phase: Phase): Int = entries.valuesIterator.count(_.phase == phase)
  private def checked(qsid: Qsid, token: AnyRef, time: Instant): Entry = {
    val entry = entries.getOrElse(qsid, reject(Error.StaleOwner))
    if (!PresentationMachine.currentOwner(entry, token)) reject(Error.StaleOwner)
    if (PresentationMachine.expired(entry, time)) {
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
      if (!PresentationMachine.canWrite(entry.nodes.contains(nodeId), entry.nodes.size, limits.nodesPerView)) reject(Error.Capacity)
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

  def bootstrap(qsid: Qsid, topLevel: S): F[StateManager[F]] =
    bootstrap(qsid, topLevel, resetBrowser = false)

  // A reconstructed presentation cannot assume that the browser has this DOM.
  // Keep that requirement with the entry, including failed claims and reconnects:
  // there is no browser acknowledgement that makes it safe to clear the flag.
  private[spoonbill] def bootstrap(qsid: Qsid, topLevel: S, resetBrowser: Boolean): F[StateManager[F]] = effect { time =>
    sweep(time)
    PresentationMachine.bootstrapError(entries.contains(qsid), count(Phase.Bootstrap), limits.bootstrap).foreach(reject)
    val token = new Object
    entries += qsid -> PresentationMachine.bootstrap(token, time.plusSeconds(limits.bootstrapSeconds),
      Map[Id, Any](Id.TopLevel -> topLevel), resetBrowser)
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
    PresentationMachine.claimFenceError(registry, qsid, fence, limits.history).foreach(reject)
    PresentationMachine.claimCapacityError(previous.phase, count(Phase.Pending), count(Phase.Active), limits).foreach(reject)
    val token = new Object
    registry = PresentationMachine.remember(registry, qsid, fence)
    entries += qsid -> PresentationMachine.claim(previous, token, fence, time.plusSeconds(limits.pendingSeconds))
    new Lease(qsid, fence, token, previous.resetBrowser)
  }

  final class Lease private[BoundedPresentationStore] (
    val qsid: Qsid,
    val fence: ViewFence,
    private val token: AnyRef,
    private[spoonbill] val resetBrowser: Boolean
  ) {
    val manager: StateManager[F] = BoundedPresentationStore.this.manager(qsid, token)
    def publish(): F[Unit] = effect { time =>
      val entry = checked(qsid, token, time)
      entries += qsid -> PresentationMachine.publish(entry)
    }
    def release(): F[Unit] = F.delay {
      val sample = clock()
      monitor.synchronized {
        if (PresentationMachine.advances(observed, sample)) observed = sample
        entries.get(qsid).filter(_.token eq token).foreach { entry =>
          entries -= qsid
          sweep(observed)
          if (PresentationMachine.retainOnRelease(registry, entry.phase, count(Phase.Disconnected), limits.disconnected)) {
            entries += qsid -> PresentationMachine.disconnect(entry, new Object, observed.plusSeconds(limits.reconnectSeconds))
          }
        }
      }
    }
    def invalidate(): F[Unit] = F.delay {
      monitor.synchronized {
        if (entries.get(qsid).exists(_.leaseToken.exists(_ eq token))) entries -= qsid
      }
    }
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
      if (PresentationMachine.advances(observed, sample)) observed = sample
      sweep(observed)
      Resources(
        count(Phase.Active),
        count(Phase.Pending),
        count(Phase.Bootstrap),
        count(Phase.Disconnected),
        PresentationMachine.history(registry).size,
        entries.valuesIterator.map(_.nodes.size).sum
      )
    }
  }

  /**
   * Guarded coordinators call only after establishing current provider
   * authority. Public store operations deliberately do not reclaim history:
   * this store cannot prove that a caller-supplied fence is still authorized.
   */
  private[spoonbill] def reclaimAbsentHistory(): F[Set[Qsid]] = effect { time =>
    sweep(time)
    registry = PresentationMachine.reclaim(registry, entries.keySet)
    entries.keySet
  }
  def close(): F[Unit] = F.delay {
    monitor.synchronized {
      registry = PresentationMachine.close(registry)
      entries = Map.empty
    }
  }
}
