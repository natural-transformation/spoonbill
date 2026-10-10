package spoonbill.browserauthbaseline

import MemoryBrowserAuth.*
import avocet.Id
import java.time.Instant
import java.util.UUID
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.action.{AccessDecision, AccessDenied, InvocationBinding, SessionAuthority}
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.Versions.SlotGeneration
import spoonbill.security.transaction.{ExecutionAuthority, TransactionFailure}
import spoonbill.server.{SessionAccessControl, SessionAccessDenied, SessionGuard}
import spoonbill.state.{DeviceId, SessionId, StateDeserializer, StateManager, StateSerializer, StateStorage}
import spoonbill.web.Request

object MemoryBrowserSecurity {
  final case class Limits(
    active: Int = 64,
    disconnected: Int = 128,
    bootstrap: Int = 128,
    nodesPerView: Int = 256,
    bootstrapSeconds: Long = 30,
    reconnectSeconds: Long = 120,
    activePerBinding: Int = 16
  ) {
    require(active > 0 && disconnected > 0 && bootstrap > 0 && nodesPerView > 0)
    require(bootstrapSeconds > 0 && reconnectSeconds > 0)
    require(activePerBinding > 0)
  }
  final case class Resources(active: Int, disconnected: Int, bootstrap: Int, nodes: Int)
}

/**
 * v3 host glue. The host monitor is the authority AND presentation publication
 * boundary. Fresh bootstrap values and every stored node must be immutable,
 * non-sensitive presentation; transient sensitive disclosure remains
 * default-deny.
 */
final class MemoryBrowserSecurity[F[_], S](
  host: MemoryBrowserAuth[F],
  fresh: () => S,
  requiresAuthentication: S => Boolean,
  connected: (S, Option[Principal]) => S,
  limits: MemoryBrowserSecurity.Limits = MemoryBrowserSecurity.Limits(),
  origin: String = "http://localhost:8080"
)(using F: Effect[F])
    extends SessionAccessControl[F, S] {
  import MemoryBrowserSecurity.*
  private case class Entry(
    lease: AnyRef,
    identity: Option[Identity],
    binding: Option[String],
    owner: Option[ConnectionId],
    nodes: Map[Id, Any],
    deadline: Instant,
    bootstrap: Boolean,
    reloadRequired: Boolean = false
  )
  private var entries           = Map.empty[Qsid, Entry]
  private var connections       = Map.empty[ConnectionId, Qsid]
  private var closed            = false
  private def denied(): Nothing = throw new SessionAccessDenied
  private def atomic[A](body: => A): F[A] = host.atomically {
    if (closed) denied()
    sweep()
    body
  }
  private def freshEntry(): Entry = Entry(
    new Object,
    None,
    None,
    None,
    Map(Id.TopLevel -> fresh()),
    host.timeLocked.plusSeconds(limits.bootstrapSeconds),
    bootstrap = true
  )
  private def remove(qsid: Qsid): Unit = {
    entries.get(qsid).flatMap(_.owner).foreach(owner => connections -= owner)
    entries -= qsid
  }
  private def valid(identity: Identity): Boolean =
    try { host.checkIdentityLocked(identity); true }
    catch { case _: SessionAccessDenied => false }
  private def sweep(): Unit = {
    val now = host.timeLocked
    entries.toVector.foreach { case (qsid, entry) =>
      if (entry.owner.isEmpty && !now.isBefore(entry.deadline)) remove(qsid)
      else if (entry.identity.exists(identity => !valid(identity))) {
        remove(qsid)
        if (entries.values.count(_.bootstrap) < limits.bootstrap)
          entries += qsid -> freshEntry().copy(reloadRequired = true)
      }
    }
  }
  host.atomicallyNow {
    host.registerPresentationLocked { () =>
      if (host.isClosedLocked) { entries = Map.empty; connections = Map.empty; closed = true }
      else
        try sweep()
        catch { case NonFatal(error) => entries = Map.empty; connections = Map.empty; closed = true; throw error }
    }
  }
  private def entry(qsid: Qsid, lease: AnyRef): Entry = {
    val current = entries.getOrElse(qsid, denied())
    if (!(current.lease eq lease)) denied()
    if (current.owner.isEmpty && !host.timeLocked.isBefore(current.deadline)) denied()
    current.identity.foreach(host.checkIdentityLocked)
    current
  }
  private def connection(id: ConnectionId): (Qsid, Entry) = {
    val qsid    = connections.getOrElse(id, denied())
    val current = entries.getOrElse(qsid, denied())
    if (!current.owner.contains(id)) denied()
    current.identity.foreach(host.checkIdentityLocked)
    qsid -> current
  }
  private def authorizeState(current: Entry, state: S): Unit =
    if (requiresAuthentication(state) && current.identity.flatMap(_.principal).isEmpty) denied()

  val storage: StateStorage[F, S] = new StateStorage[F, S] {
    def exists(device: DeviceId, session: SessionId): F[Boolean] = atomic {
      entries.get(Qsid(device, session)).exists(entry => !entry.reloadRequired || entry.owner.isEmpty)
    }
    def create(device: DeviceId, session: SessionId, initial: S): F[StateManager[F]] = atomic {
      val qsid = Qsid(device, session)
      if (entries.contains(qsid) || entries.values.count(_.bootstrap) >= limits.bootstrap) denied()
      // The host state loader supplies immutable, non-sensitive bootstrap
      // presentation. Authority is captured independently at the handshake.
      val created = freshEntry().copy(nodes = Map(Id.TopLevel -> initial))
      entries += qsid -> created
      manager(qsid, created.lease)
    }
    def get(device: DeviceId, session: SessionId): F[StateManager[F]] = atomic {
      val qsid    = Qsid(device, session)
      val current = entries.getOrElse(qsid, denied())
      if (current.reloadRequired) denied()
      if (!current.bootstrap && current.owner.isEmpty) denied()
      current.identity.foreach(host.checkIdentityLocked)
      manager(qsid, current.lease)
    }
    def remove(device: DeviceId, session: SessionId): Unit = host.atomicallyNow {
      // v3 supplies no owner in this callback. Guard.close owns conditional
      // release; delayed removal must never erase a successor or its state.
      if (!closed && !host.isClosedLocked) sweep()
    }
  }
  private def manager(qsid: Qsid, lease: AnyRef): StateManager[F] = new StateManager[F] {
    def read[T: StateDeserializer](node: Id): F[Option[T]] = atomic {
      entry(qsid, lease).nodes.get(node).asInstanceOf[Option[T]]
    }
    def write[T: StateSerializer](node: Id, value: T): F[Unit] = atomic {
      val current = entry(qsid, lease)
      if (!current.nodes.contains(node) && current.nodes.size >= limits.nodesPerView) denied()
      entries += qsid -> current.copy(nodes = current.nodes.updated(node, value))
    }
    def delete(node: Id): F[Unit] = atomic {
      val current = entry(qsid, lease)
      entries += qsid -> current.copy(nodes = current.nodes - node)
    }
    def snapshot: F[StateManager.Snapshot] = atomic {
      val captured = entry(qsid, lease).nodes
      new StateManager.Snapshot {
        def apply[T: StateDeserializer](node: Id): Option[T] = host.atomicallyNow {
          if (closed || host.isClosedLocked) denied()
          entry(qsid, lease)
          captured.get(node).asInstanceOf[Option[T]]
        }
      }
    }
  }
  private def cookies(request: Request.Head): (String, Option[String]) =
    request.cookie("baseline_binding").getOrElse(denied()) -> request.cookie("baseline_session")
  def authorizeHttp(request: Request.Head, state: S): F[Unit] = atomic {
    val (binding, credential) = cookies(request)
    val identity              = host.captureIdentityLocked(binding, credential)
    if (requiresAuthentication(state) && identity.principal.isEmpty) denied()
  }
  def open(qsid: Qsid, request: Request.Head, id: ConnectionId): F[SessionGuard[F, S]] = {
    def acquire(expectedGeneration: SlotGeneration): F[SessionGuard[F, S]] = atomic {
      val (binding, credential) = cookies(request)
      val identity              = host.captureIdentityLocked(binding, credential)
      if (identity.generation != expectedGeneration) denied()
      val previous = entries.getOrElse(qsid, denied())
      if (previous.identity.exists(_.binding != identity.binding)) denied()
      if (connections.contains(id)) denied()
      if (previous.owner.isEmpty && entries.values.count(_.owner.nonEmpty) >= limits.active) denied()
      if (
        previous.owner.isEmpty && entries.values.count(entry =>
          entry.owner.nonEmpty && entry.identity.exists(_.binding == identity.binding)
        ) >= limits.activePerBinding
      ) denied()
      previous.owner.foreach(owner => connections -= owner)
      val sameIdentity = previous.identity.contains(identity)
      val acquired = previous.copy(
        lease = new Object,
        identity = Some(identity),
        binding = Some(binding),
        owner = Some(id),
        nodes = if (sameIdentity || previous.bootstrap) previous.nodes else Map(Id.TopLevel -> fresh()),
        bootstrap = false
      )
      entries += qsid   -> acquired
      connections += id -> qsid
      val lease = acquired.lease
      new SessionGuard[F, S] {
        def authorize(state: S): F[Unit] = atomic(authorizeState(entry(qsid, lease), state))
        def connected(state: S): F[S] = atomic {
          val current = entry(qsid, lease)
          val value   = MemoryBrowserSecurity.this.connected(state, current.identity.flatMap(_.principal))
          authorizeState(current, value)
          value
        }
        def close(): F[Unit] = F.delay(host.atomicallyNow {
          entries.get(qsid).filter(_.lease eq lease).foreach { current =>
            connections -= id
            if (
              current.reloadRequired || entries.values
                .count(e => !e.bootstrap && e.owner.isEmpty) >= limits.disconnected
            ) remove(qsid)
            else
              entries += qsid -> current
                .copy(lease = new Object, owner = None, deadline = host.timeLocked.plusSeconds(limits.reconnectSeconds))
          }
        })
      }
    }
    F.flatMap(atomic {
      if (!request.header("origin").contains(origin) || !entries.contains(qsid)) denied()
      val (binding, credential) = cookies(request)
      (binding, credential, host.lineageGenerationLocked(binding))
    }) { case (binding, credential, generation) =>
      credential match {
        case None => acquire(generation)
        case Some(value) =>
          F.flatMap(host.activate(binding, value)) {
            case Left(_)          => F.fail(new SessionAccessDenied)
            case Right(principal) => acquire(principal.generation)
          }
      }
    }
  }
  // Unknown/expired views never allocate entries; the v3 service sends its
  // existing terminal reload response when this opt-in is absent.
  override def resume(qsid: Qsid, request: Request.Head, id: ConnectionId): Option[F[SessionGuard[F, S]]] = None
  val authority: SessionAuthority[F, Principal] = new SessionAuthority[F, Principal] {
    def resolve(binding: InvocationBinding): F[Either[AccessDenied, Principal]] =
      F.recover(atomic {
        connection(binding.connectionId)._2.identity.flatMap(_.principal).toRight(AccessDenied.Unauthenticated)
      }) { case _: SessionAccessDenied =>
        Left(AccessDenied.StaleAuthority)
      }
    def revalidate(binding: InvocationBinding, principal: Principal): F[AccessDecision] =
      F.map(resolve(binding)) {
        case Right(current) if current == principal => AccessDecision.Allowed
        case _                                      => AccessDecision.Denied(AccessDenied.StaleAuthority)
      }
  }
  private def withConnection[A](id: ConnectionId)(body: (String, () => Unit) => F[A]): F[A] =
    F.flatMap(atomic {
      val (qsid, current) = connection(id)
      val lease           = current.lease
      val check           = () => { entry(qsid, lease); () }
      current.binding.getOrElse(denied()) -> check
    }) { case (binding, check) => body(binding, check) }
  def begin(id: ConnectionId): F[Either[Failure, UUID]] = atomic {
    val current = connection(id)._2
    host.beginCapturedLocked(current.identity.getOrElse(denied()))
  }
  def password(id: ConnectionId, ceremony: UUID, name: String, value: String): F[Either[Failure, Reply]] =
    withConnection(id)((binding, check) => host.password(ceremony, binding, name, value, check))
  def factor(id: ConnectionId, ceremony: UUID, challenge: UUID, value: String): F[Either[Failure, Reply]] =
    withConnection(id)((binding, check) => host.factor(ceremony, binding, challenge, value, check))
  def recover(id: ConnectionId, ceremony: UUID): F[Either[Failure, Recovery]] =
    withConnection(id)((binding, check) => host.recover(ceremony, binding, check))
  def logout(id: ConnectionId): F[Either[Failure, Unit]] =
    withConnection(id)((binding, check) => host.logout(binding, check))
  def actionAuthority(id: ConnectionId, principal: Principal): F[Either[Failure, ExecutionAuthority]] =
    withConnection(id)((_, check) => host.actionAuthority(principal, check))
  def protectedAction(
    id: ConnectionId,
    principal: Principal,
    authority: ExecutionAuthority
  ): F[Either[TransactionFailure, Int]] =
    withConnection(id)((_, check) => host.protectedAction(principal, authority, check))
  def resources: F[Resources] = F.delay(host.atomicallyNow {
    if (!closed && !host.isClosedLocked) sweep()
    Resources(
      entries.values.count(_.owner.nonEmpty),
      entries.values.count(e => !e.bootstrap && e.owner.isEmpty),
      entries.values.count(_.bootstrap),
      entries.values.map(_.nodes.size).sum
    )
  })
  def close(): F[Unit] = F.delay(host.atomicallyNow { entries = Map.empty; connections = Map.empty; closed = true })
}
