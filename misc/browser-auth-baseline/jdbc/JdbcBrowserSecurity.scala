package spoonbill.security.jdbc.baseline

import avocet.Id
import java.sql.{Connection, PreparedStatement}
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.{AtomicBoolean, AtomicReference}
import javax.sql.DataSource
import scala.collection.immutable.Queue
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try, Using}
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.action.{AccessDecision, AccessDenied, InvocationBinding, SessionAuthority}
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.jdbc.*
import spoonbill.security.transaction.*
import spoonbill.server.{
  AuthenticationCompletionConfig,
  BrowserSessionToken,
  SessionAccessControl,
  SessionAccessDenied,
  SessionGuard
}
import spoonbill.snapshot.ViewSnapshotSession
import spoonbill.state.{DeviceId, SessionId, StateDeserializer, StateManager, StateSerializer, StateStorage}
import spoonbill.web.Request

/**
 * One bounded asynchronous owner. Waiting work consumes no JDBC connection or
 * blocking worker. The running job owns admission through actual settlement.
 */
private[jdbc] final class JdbcReferenceGate(capacity: Int, cleanupCapacity: Int = 1)(using ec: ExecutionContext) {
  require(capacity > 0 && cleanupCapacity > 0)
  private trait Job { def start(): Unit; def refuse(): Unit }
  private var waiting            = Queue.empty[Job]
  private var cleanup            = Queue.empty[Job]
  private var pendingMaintenance = Option.empty[Job]
  private var running            = false
  private var stopped            = false
  private val drained            = Promise[Unit]()
  private def dispatch(job: Job): Unit = try ec.execute(() => job.start())
  catch {
    case NonFatal(_) =>
      val refused = synchronized {
        stopped = true; running = false
        val pending = cleanup ++ pendingMaintenance.toVector ++ waiting
        waiting = Queue.empty
        cleanup = Queue.empty
        pendingMaintenance = None
        pending
      }
      job.refuse(); refused.foreach(_.refuse()); drained.trySuccess(()); ()
  }
  def submit[A](operation: => Future[A]): Future[A]            = enqueue(false)(operation)
  def submitCleanup[A](operation: => Future[A]): Future[A]     = enqueue(true)(operation)
  def submitMaintenance[A](operation: => Future[A]): Future[A] = enqueue(false, maintenance = true)(operation)
  private def enqueue[A](release: Boolean, maintenance: Boolean = false)(operation: => Future[A]): Future[A] = {
    val promise = Promise[A]()
    val job = new Job {
      def refuse(): Unit = { promise.tryFailure(new SessionAccessDenied); () }
      def start(): Unit = {
        val work =
          try operation
          catch { case NonFatal(error) => Future.failed(error) }
        work.onComplete { result =>
          val (next, finished) = JdbcReferenceGate.this.synchronized {
            if (cleanup.nonEmpty) { val (head, tail) = cleanup.dequeue; cleanup = tail; Some(head) -> false }
            else if (pendingMaintenance.nonEmpty) {
              val next = pendingMaintenance; pendingMaintenance = None; next -> false
            } else if (waiting.nonEmpty) { val (head, tail) = waiting.dequeue; waiting = tail; Some(head) -> false }
            else { running = false; None -> stopped }
          }
          promise.tryComplete(result)
          if (finished) drained.trySuccess(())
          next.foreach(dispatch)
        }(ExecutionContext.parasitic)
      }
    }
    val admission = synchronized {
      if (stopped && (!release || !running)) -1
      else if (
        running && (if (release) cleanup.size >= cleanupCapacity
                    else if (maintenance) pendingMaintenance.nonEmpty
                    else waiting.size >= capacity)
      ) -2
      else if (running) {
        if (release) cleanup = cleanup.enqueue(job)
        else if (maintenance) pendingMaintenance = Some(job)
        else waiting = waiting.enqueue(job)
        0
      } else { running = true; 1 }
    }
    if (admission < 0) job.refuse()
    else if (admission > 0) dispatch(job)
    promise.future
  }
  def queued: Int = synchronized(waiting.size + cleanup.size + pendingMaintenance.size)
  def close(): Future[Unit] = {
    val (refused, finished) = synchronized {
      stopped = true
      val pending = waiting
      waiting = Queue.empty
      pending -> !running
    }
    refused.foreach(_.refuse())
    if (finished) drained.trySuccess(())
    drained.future
  }
}

object JdbcBrowserSecurity {
  final case class Limits(
    active: Int = 64,
    activePerBinding: Int = 16,
    disconnected: Int = 128,
    bootstrap: Int = 128,
    nodesPerView: Int = 256,
    retainedViews: Int = 1024,
    retainedBindings: Int = 1024,
    auditRecords: Int = 1024,
    queued: Int = 128,
    bootstrapSeconds: Long = 30,
    reconnectSeconds: Long = 120,
    proofAttemptsPerMinute: Int = 10,
    proofBindings: Int = 128
  ) {
    require(
      Vector(
        active,
        activePerBinding,
        disconnected,
        bootstrap,
        nodesPerView,
        retainedViews,
        retainedBindings,
        auditRecords,
        queued,
        proofAttemptsPerMinute,
        proofBindings
      ).forall(_ > 0)
    )
    require(bootstrapSeconds > 0 && reconnectSeconds > 0)
  }
  final case class Principal(
    subject: UUID,
    session: UUID,
    realm: String,
    namespace: String,
    scope: String,
    securityVersion: Long,
    policyVersion: Long,
    slotGeneration: Long
  )
  final case class Resources(
    active: Int,
    disconnected: Int,
    bootstrap: Int,
    nodes: Int,
    queued: Int,
    proofBindings: Int
  )
}

/**
 * Single-node v3 integration. Every supported authority writer belongs to this
 * instance and its gate. Out-of-band SQL writers and multiple processes are
 * explicitly unsupported: require the deployment assertion before acquisition.
 * SQL and local publication share admission through actual outer settlement.
 * Failed/unknown settlement discards affected leases, never restores revoked
 * state. No monitor is held across a Future or JDBC call.
 *
 * project/restore select only immutable, non-sensitive presentation.
 * Credentials, principals and ceremony authority are never recovered from a
 * projection.
 */
final class JdbcBrowserSecurity[S, P](
  source: DataSource,
  blockingContext: ExecutionContext,
  realm: String,
  namespace: String,
  clock: () => Instant,
  entropy: Int => Array[Byte],
  newId: () => UUID,
  cipher: JdbcReferenceHost.MaterialCipher,
  fresh: () => S,
  requiresAuthentication: S => Boolean,
  connected: (S, Option[JdbcBrowserSecurity.Principal]) => S,
  project: S => P,
  restore: (S, P) => S,
  singleNodeExclusiveWriters: Boolean,
  origin: String = "http://localhost:8080",
  limits: JdbcBrowserSecurity.Limits = JdbcBrowserSecurity.Limits(),
  afterPreparedCommit: UUID => Unit = _ => ()
)(using F: Effect[Future])
    extends SessionAccessControl[Future, S] {
  import JdbcBrowserSecurity.*
  require(singleNodeExclusiveWriters, "The baseline requires one instance owning every authority writer")
  private given ExecutionContext = blockingContext
  private val gate               = new JdbcReferenceGate(limits.queued, limits.active)
  private val outer              = new JdbcTransactionExecutor[Future](source, blockingContext)
  private val maintenanceResult  = new AtomicReference[Promise[Either[TransactionFailure, Int]]]()
  private val durableClock       = new JdbcReferenceClock(source, realm, namespace, clock)
  private def now(): Instant = {
    require(!Thread.holdsLock(registry), "Clock checkpoint must precede registry acquisition")
    durableClock.now()
  }
  private val joined = new TransactionExecutor[Future, Direct, Connection] {
    val program                                                                   = TransactionProgram.direct
    val scopePolicy                                                               = TransactionScopePolicy.ThreadConfined
    def transact[A](body: Connection => A): Future[Either[TransactionFailure, A]] = gate.submit(outer.transact(body))
  }
  // Raw browser methods are confined to this trusted adapter; ordinary handlers
  // cannot bypass the shared gate to activate/logout or mutate host policy.
  private val host = new JdbcReferenceHost(
    source,
    blockingContext,
    realm,
    namespace,
    clock,
    entropy,
    newId,
    cipher,
    existingRunner = Some(joined),
    maxAudits = limits.auditRecords,
    sharedClock = Some(durableClock)
  )
  private val outcomes = new JdbcOperationOutcomes()
  private val issuer   = new OneUseAuthorityScope(() => now(), limits.queued)
  private val operations =
    new OneUseOperationProtocol[Future, Direct, Connection](joined, outcomes, issuer, () => now())
  private val operationRealm =
    RealmId.fromUuid(UUID.nameUUIDFromBytes(realm.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
  private val operationPurpose = OperationPurpose.fromUuid(
    UUID.nameUUIDFromBytes("baseline-preference".getBytes(java.nio.charset.StandardCharsets.UTF_8))
  )
  private val operationScope = ResourceScope.fromUuid(
    UUID.nameUUIDFromBytes("baseline-preference".getBytes(java.nio.charset.StandardCharsets.UTF_8))
  )
  private case class Identity(
    binding: Digest256,
    token: Option[Digest256],
    generation: Long,
    principal: Option[Principal],
    expires: Instant
  )
  private case class Entry(
    lease: AnyRef,
    identity: Option[Identity],
    owner: Option[ConnectionId],
    ownerKey: UUID,
    epoch: Long,
    nodes: Map[Id, Any],
    projection: Option[P],
    deadline: Instant,
    bootstrap: Boolean
  )
  private val registry     = new Object
  private var entries      = Map.empty[Qsid, Entry]
  private var owners       = Map.empty[ConnectionId, Qsid]
  private var closed       = false
  private var proofWindows = Map.empty[Digest256, (Instant, Int)]
  private def admitProof(binding: Digest256): Boolean = {
    val time = now()
    registry.synchronized {
      proofWindows = proofWindows.filter { case (_, (start, _)) => time.isBefore(start.plusSeconds(60)) }
      val (start, count) = proofWindows.getOrElse(binding, time -> 0)
      if (
        closed || count >= limits.proofAttemptsPerMinute || (!proofWindows.contains(
          binding
        ) && proofWindows.size >= limits.proofBindings)
      ) false
      else { proofWindows += binding -> (start -> (count + 1)); true }
    }
  }
  private def denied(): Nothing = throw new SessionAccessDenied
  private def reject(): Nothing = throw new OperationProtocolException(OperationError.HostDenied)
  private def sql[A](connection: Connection, text: String)(body: PreparedStatement => A): A =
    Using.resource(connection.prepareStatement(text))(body)
  private def hash(value: String): Digest256 = Digest256.fromBytes(JdbcReferenceHost.syntheticHash(value)).toOption.get
  private def viewId(qsid: Qsid): String     = s"${qsid.deviceId}-${qsid.sessionId}"
  private def cookies(request: Request.Head): (Digest256, Option[Digest256]) =
    hash(request.cookie("baseline_binding").getOrElse(denied())) -> request.cookie("baseline_session").map(hash)
  private def remove(qsid: Qsid): Unit = {
    entries.get(qsid).flatMap(_.owner).foreach(id => owners -= id)
    entries -= qsid
  }
  private def removeIf(qsid: Qsid, lease: AnyRef): Unit = registry.synchronized {
    if (entries.get(qsid).exists(_.lease eq lease)) remove(qsid)
  }
  private def invalidate(predicate: Identity => Boolean): Unit = registry.synchronized {
    entries.collect { case (id, entry) if entry.identity.exists(predicate) => id }.toVector.foreach(remove)
  }
  private def sweep(time: Instant): Unit =
    entries.collect {
      case (id, entry)
          if (entry.owner.isEmpty && !time.isBefore(entry.deadline)) ||
            entry.identity.exists(value => !time.isBefore(value.expires)) =>
        id
    }.toVector.foreach(remove)
  private def local[A](body: Instant => A): Future[A] = gate.submit(Future {
    val time = now()
    registry.synchronized { if (closed) denied(); sweep(time); body(time) }
  })
  private def current(qsid: Qsid, lease: AnyRef, time: Instant = now()): Entry = registry.synchronized {
    val entry = entries.getOrElse(qsid, denied())
    if (
      closed || !(entry.lease eq lease) || entry.owner.isEmpty || entry.identity.exists(v => !time.isBefore(v.expires))
    ) denied()
    entry
  }
  private def byOwner(owner: ConnectionId): (Qsid, Entry) = {
    val time = now()
    registry.synchronized {
      val qsid  = owners.getOrElse(owner, denied())
      val entry = entries.getOrElse(qsid, denied())
      if (!entry.owner.contains(owner)) denied()
      qsid -> current(qsid, entry.lease, time)
    }
  }

  /** Deployment only: schema creation never occurs in a request. */
  def initialize(connection: Connection): Unit = {
    host.initialize(connection)
    outcomes.initialize(connection)
    Using.resource(connection.createStatement())(
      _.executeUpdate(
        "CREATE TABLE baseline_preference(subject UUID PRIMARY KEY REFERENCES baseline_account(id), changes INTEGER NOT NULL)"
      )
    )
  }
  def bootstrapBinding(value: String): Future[Either[TransactionFailure, Long]] = gate.submit {
    val binding = hash(value)
    outer.transact { connection =>
      val existing = sql(
        connection,
        "SELECT generation FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=? FOR UPDATE"
      ) { query =>
        query.setString(1, realm); query.setString(2, namespace); query.setBytes(3, binding.bytes)
        Using.resource(query.executeQuery())(rows => if (rows.next()) Some(rows.getLong(1)) else None)
      }
      existing.getOrElse {
        val count =
          sql(connection, "SELECT count(*) FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=?") { query =>
            query.setString(1, realm); query.setString(2, namespace)
            Using.resource(query.executeQuery()) { rows =>
              rows.next(); rows.getLong(1)
            }
          }
        if (count >= limits.retainedBindings) throw new OperationProtocolException(OperationError.CapacityExceeded)
        sql(
          connection,
          "INSERT INTO spoonbill_browser_slot(realm,cookie_namespace,binding_hash,generation) VALUES (?,?,?,0)"
        ) { query =>
          query.setString(1, realm); query.setString(2, namespace); query.setBytes(3, binding.bytes);
          query.executeUpdate()
        }
        0L
      }
    }
  }

  /**
   * Fresh coherent SQL, joined to the supplied transaction. Account/session
   * locks precede slot and view. No nested browser.validate transaction.
   */
  private def identity(
    connection: Connection,
    binding: Digest256,
    token: Option[Digest256],
    allowPending: Boolean = false
  ): Identity = {
    val session = token.map { credential =>
      val found = sql(
        connection,
        "SELECT host_session_id FROM spoonbill_browser_session WHERE realm=? AND cookie_namespace=? AND token_hash=?"
      ) { query =>
        query.setString(1, realm); query.setString(2, namespace); query.setBytes(3, credential.bytes)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) reject(); rows.getObject(1, classOf[UUID])
        }
      }
      val subject = sql(connection, "SELECT subject FROM baseline_session WHERE id=?") { query =>
        query.setObject(1, found)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) reject(); rows.getObject(1, classOf[UUID])
        }
      }
      val version = sql(connection, "SELECT version,enabled FROM baseline_account WHERE id=? FOR UPDATE") { query =>
        query.setObject(1, subject)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next() || !rows.getBoolean(2)) reject(); rows.getLong(1)
        }
      }
      val expiry = sql(connection, "SELECT version,valid,expires_at FROM baseline_session WHERE id=? FOR UPDATE") {
        query =>
          query.setObject(1, found)
          Using.resource(query.executeQuery()) { rows =>
            if (!rows.next() || !rows.getBoolean(2) || rows.getLong(1) != version) reject()
            rows.getTimestamp(3).toInstant
          }
      }
      (found, subject, version, expiry)
    }
    val (generation, active) = sql(
      connection,
      "SELECT generation,current_session_id FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=? FOR UPDATE"
    ) { query =>
      query.setString(1, realm); query.setString(2, namespace); query.setBytes(3, binding.bytes)
      Using.resource(query.executeQuery()) { rows =>
        if (!rows.next()) reject()
        rows.getLong(1) -> Option(rows.getObject(2, classOf[UUID]))
      }
    }
    if (token.isEmpty && active.nonEmpty) reject()
    val principal = session.flatMap { case (sessionId, subject, version, expiry) =>
      if (!now().isBefore(expiry)) reject()
      val authenticated = sql(
        connection,
        """SELECT s.binding_hash,s.active_generation,s.revoked,s.origin_generation,c.expires_at,c.acknowledged_at,b.state
          FROM spoonbill_browser_session s JOIN spoonbill_browser_completion c
          ON c.realm=s.realm AND c.cookie_namespace=s.cookie_namespace AND c.token_hash=s.token_hash
          JOIN baseline_ceremony b ON b.attempt=c.completion_id
          WHERE s.realm=? AND s.cookie_namespace=? AND s.host_session_id=?"""
      ) { query =>
        query.setString(1, realm); query.setString(2, namespace); query.setObject(3, sessionId)
        Using.resource(query.executeQuery()) { rows =>
          if (
            !rows.next() || rows.getBoolean(3) ||
            Digest256.fromBytes(rows.getBytes(1)).toOption.get != binding
          ) reject()
          val activeGeneration = Option(rows.getObject(2, classOf[java.lang.Long])).map(_.longValue)
          val acknowledged     = rows.getTimestamp(6) != null
          val isActive         = active.contains(sessionId) && activeGeneration.contains(generation) && acknowledged
          val isPending = allowPending && activeGeneration.isEmpty && !acknowledged &&
            generation == rows.getLong(4) && now().isBefore(rows.getTimestamp(5).toInstant) && rows.getString(
              7
            ) == "committed"
          if (!isActive && !isPending) reject()
          isActive
        }
      }
      if (authenticated)
        Some(Principal(subject, sessionId, realm, namespace, "baseline-preference", version, version, generation))
      else None
    }
    Identity(binding, token, generation, principal, session.fold(Instant.MAX)(_._4))
  }
  private def check(connection: Connection, qsid: Qsid, entry: Entry): Identity = {
    val captured = entry.identity.getOrElse(reject())
    val actual   = identity(connection, captured.binding, captured.token)
    if (actual != captured) reject()
    sql(
      connection,
      "SELECT owner_id,epoch,binding_hash FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=? AND view_id=? FOR UPDATE"
    ) { query =>
      query.setString(1, realm); query.setString(2, namespace); query.setString(3, viewId(qsid))
      Using.resource(query.executeQuery()) { rows =>
        if (
          !rows.next() || entry.owner.isEmpty || entry.ownerKey != rows.getObject(1, classOf[UUID]) ||
          entry.epoch != rows.getLong(2) || Digest256.fromBytes(rows.getBytes(3)).toOption.get != captured.binding
        ) reject()
      }
    }
    current(qsid, entry.lease)
    actual
  }
  private def authorizeState(identity: Identity, state: S): Unit =
    if (requiresAuthentication(state) && identity.principal.isEmpty) reject()

  /**
   * Stage in the JDBC callback; publish only after its actual outer commit,
   * still inside the gate. Failure detaches the affected lease permanently.
   */
  private def transaction[A](qsid: Qsid, lease: AnyRef)(body: Connection => (A, Option[Entry])): Future[A] =
    gate.submit {
      outer
        .transact(body)
        .flatMap {
          case Right((value, staged)) =>
            val time = now()
            registry.synchronized {
              current(qsid, lease, time)
              staged.foreach { entry =>
                entries.get(qsid).flatMap(_.owner).foreach(id => owners -= id)
                entries += qsid -> entry
                entry.owner.foreach(id => owners += id -> qsid)
              }
            }
            Future.successful(value)
          case Left(_) => removeIf(qsid, lease); Future.failed(new SessionAccessDenied)
        }
        .recoverWith { case NonFatal(error) => removeIf(qsid, lease); Future.failed(error) }
    }
  private def withEntry[A](qsid: Qsid, lease: AnyRef)(
    body: (Connection, Entry, Identity) => (A, Option[Entry])
  ): Future[A] =
    transaction(qsid, lease) { connection =>
      val entry = current(qsid, lease)
      body(connection, entry, check(connection, qsid, entry))
    }

  val storage: StateStorage[Future, S] = new StateStorage[Future, S] {
    def exists(device: DeviceId, session: SessionId): Future[Boolean] =
      local(_ => entries.contains(Qsid(device, session)))
    def create(device: DeviceId, session: SessionId, initial: S): Future[StateManager[Future]] = local { time =>
      val qsid = Qsid(device, session)
      if (entries.contains(qsid) || entries.values.count(_.bootstrap) >= limits.bootstrap) denied()
      val entry = Entry(
        new Object,
        None,
        None,
        newId(),
        0L,
        Map(Id.TopLevel -> initial),
        None,
        time.plusSeconds(limits.bootstrapSeconds),
        bootstrap = true
      )
      entries += qsid -> entry
      manager(qsid, entry.lease)
    }
    def get(device: DeviceId, session: SessionId): Future[StateManager[Future]] = local { _ =>
      val qsid  = Qsid(device, session)
      val entry = entries.getOrElse(qsid, denied())
      if (!entry.bootstrap && entry.owner.isEmpty) denied()
      manager(qsid, entry.lease)
    }
    // v3's callback has no owner token. Guard.close performs conditional release;
    // this callback must never erase a successor from an old connection.
    def remove(device: DeviceId, session: SessionId): Unit = ()
  }
  private def manager(qsid: Qsid, lease: AnyRef): StateManager[Future] = new StateManager[Future] {
    private def access[A](body: Entry => (A, Option[Entry])): Future[A] = {
      val bootstrap = registry.synchronized(entries.get(qsid).exists(e => (e.lease eq lease) && e.bootstrap))
      if (bootstrap) local { _ =>
        val entry = entries.getOrElse(qsid, denied())
        if (!(entry.lease eq lease) || !entry.bootstrap) denied()
        val (value, staged) = body(entry); staged.foreach(value => entries += qsid -> value); value
      }
      else withEntry(qsid, lease)((_, entry, _) => body(entry))
    }
    def read[T: StateDeserializer](node: Id): Future[Option[T]] =
      access(entry => entry.nodes.get(node).asInstanceOf[Option[T]] -> None)
    def write[T: StateSerializer](node: Id, value: T): Future[Unit] = access { entry =>
      if (!entry.nodes.contains(node) && entry.nodes.size >= limits.nodesPerView) denied()
      if (node == Id.TopLevel) entry.identity.foreach(authorizeState(_, value.asInstanceOf[S]))
      () -> Some(entry.copy(nodes = entry.nodes.updated(node, value)))
    }
    def delete(node: Id): Future[Unit] = access(entry => () -> Some(entry.copy(nodes = entry.nodes - node)))
    def snapshot: Future[StateManager.Snapshot] = access { entry =>
      val capturedNodes = entry.nodes
      val snapshot = new StateManager.Snapshot {
        def apply[T: StateDeserializer](node: Id): Option[T] = {
          val time = now()
          registry.synchronized {
            val latest = entries.getOrElse(qsid, denied())
            if (
              closed || !(latest.lease eq lease) || (latest.owner.isEmpty && !time.isBefore(latest.deadline)) ||
              latest.identity.exists(v => !time.isBefore(v.expires))
            ) denied()
            capturedNodes.get(node).asInstanceOf[Option[T]]
          }
        }
      }
      snapshot -> None
    }
  }

  def authorizeHttp(request: Request.Head, state: S): Future[Unit] = gate.submit {
    val (binding, token) = cookies(request)
    outer
      .transact(connection => authorizeState(identity(connection, binding, token, allowPending = true), state))
      .flatMap {
        case Right(_) => Future.unit
        case Left(_)  => Future.failed(new SessionAccessDenied)
      }
  }

  def open(qsid: Qsid, request: Request.Head, owner: ConnectionId): Future[SessionGuard[Future, S]] =
    acquire(qsid, request, owner, existingOnly = false)

  private def acquire(
    qsid: Qsid,
    request: Request.Head,
    owner: ConnectionId,
    existingOnly: Boolean
  ): Future[SessionGuard[Future, S]] = gate.submit {
    if (!request.header("origin").contains(origin)) denied()
    val (binding, token) = cookies(request)
    val admissionTime    = now()
    val (known, wasPresent) = registry.synchronized {
      sweep(admissionTime)
      val previous = entries.get(qsid)
      if (!previous.exists(_.bootstrap) && entries.values.count(_.bootstrap) >= limits.bootstrap) denied()
      if (previous.exists(_.identity.exists(_.binding != binding))) denied()
      val replacement = previous.exists(_.owner.nonEmpty)
      if (
        !replacement && (entries.values.count(_.owner.nonEmpty) >= limits.active ||
          entries.values.count(entry =>
            entry.owner.nonEmpty && entry.identity.exists(_.binding == binding)
          ) >= limits.activePerBinding)
      ) denied()
      val entry = entries.getOrElse(
        qsid, {
          if (!existingOnly || entries.values.count(_.bootstrap) >= limits.bootstrap) denied()
          Entry(
            new Object,
            None,
            None,
            newId(),
            0L,
            Map(Id.TopLevel -> fresh()),
            None,
            admissionTime.plusSeconds(limits.bootstrapSeconds),
            bootstrap = true
          )
        }
      )
      if (entry.identity.exists(_.binding != binding)) denied()
      entry -> previous.isDefined
    }
    // Every takeover permanently detaches the old lease before SQL dispatch,
    // including same-cookie reconnect. Keep only its immutable projection in
    // this bounded in-flight job; it is not accessible to old snapshots.
    val claim     = new Object
    val claimTime = now()
    registry.synchronized {
      if (wasPresent && !entries.get(qsid).exists(_.lease eq known.lease)) denied()
      token.foreach(value => invalidate(id => id.binding == binding && !id.token.contains(value)))
      remove(qsid)
      entries += qsid -> known.copy(
        lease = claim,
        identity = None,
        owner = None,
        nodes = Map(Id.TopLevel -> fresh()),
        projection = None,
        bootstrap = true,
        deadline = claimTime.plusSeconds(limits.bootstrapSeconds)
      )
    }
    val activated = token.fold(Future.successful(())) { value =>
      F.blocking(host.browser.activate(value, binding))(blockingContext).flatMap {
        case Right(_) => Future.unit
        case Left(_)  => Future.failed(new SessionAccessDenied)
      }
    }
    activated.flatMap { _ =>
      outer.transact { connection =>
        val actual = identity(connection, binding, token)
        val time   = now()
        registry.synchronized {
          sweep(time)
          if (closed || owners.contains(owner)) denied()
          val entry = entries.getOrElse(qsid, denied())
          if (!(entry.lease eq claim)) denied()
          if (entry.owner.isEmpty && entries.values.count(_.owner.nonEmpty) >= limits.active) denied()
          if (
            entries.values.count(entry =>
              entry.owner.nonEmpty && entry.identity.exists(_.binding == binding)
            ) >= limits.activePerBinding
          ) denied()
        }
        val prior = sql(
          connection,
          "SELECT epoch,binding_hash FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=? AND view_id=? FOR UPDATE"
        ) { query =>
          query.setString(1, realm); query.setString(2, namespace); query.setString(3, viewId(qsid))
          Using.resource(query.executeQuery()) { rows =>
            if (!rows.next()) None
            else {
              if (Digest256.fromBytes(rows.getBytes(2)).toOption.get != binding) reject()
              Some(rows.getLong(1))
            }
          }
        }
        if (existingOnly && prior.isEmpty) None
        else {
          if (prior.contains(Long.MaxValue)) reject()
          if (prior.isEmpty) {
            val count =
              sql(connection, "SELECT count(*) FROM spoonbill_browser_view WHERE realm=? AND cookie_namespace=?") {
                query =>
                  query.setString(1, realm); query.setString(2, namespace)
                  Using.resource(query.executeQuery()) { rows =>
                    rows.next(); rows.getLong(1)
                  }
              }
            if (count >= limits.retainedViews) reject()
          }
          val epoch    = prior.fold(1L)(_ + 1L)
          val ownerKey = newId()
          sql(
            connection,
            "INSERT INTO spoonbill_browser_view(realm,cookie_namespace,view_id,binding_hash,owner_id,epoch) VALUES (?,?,?,?,?,?) ON CONFLICT (realm,cookie_namespace,view_id) DO UPDATE SET owner_id=EXCLUDED.owner_id,epoch=EXCLUDED.epoch"
          ) { query =>
            query.setString(1, realm); query.setString(2, namespace); query.setString(3, viewId(qsid))
            query.setBytes(4, binding.bytes); query.setObject(5, ownerKey); query.setLong(6, epoch);
            query.executeUpdate()
          }
          val same = known.identity.contains(actual)
          Some(
            known.copy(
              lease = new Object,
              identity = Some(actual),
              owner = Some(owner),
              ownerKey = ownerKey,
              epoch = epoch,
              nodes = if (same) known.nodes else Map(Id.TopLevel -> fresh()),
              projection = if (same) known.projection else None,
              bootstrap = false
            )
          )
        }
      }.flatMap {
        case Left(_) => removeIf(qsid, claim); Future.failed(new SessionAccessDenied)
        case Right(None) =>
          removeIf(qsid, claim)
          Future.successful(new SessionGuard[Future, S] {
            // With no snapshot session or local storage, v3's ordinary missing
            // baseline path returns its terminal reload. This guard grants no
            // authority and owns no durable view or local presentation.
            def authorize(state: S): Future[Unit] = Future.failed(new SessionAccessDenied)
            def connected(state: S): Future[S]    = Future.failed(new SessionAccessDenied)
            def close(): Future[Unit]             = Future.unit
          })
        case Right(Some(entry)) =>
          Try {
            val time = now()
            registry.synchronized {
              val placeholder = entries.getOrElse(qsid, denied())
              if (
                closed || !(placeholder.lease eq claim) || !time.isBefore(placeholder.deadline) ||
                entry.identity.exists(value => !time.isBefore(value.expires))
              ) denied()
              entries.get(qsid).flatMap(_.owner).foreach(id => owners -= id)
              entries += qsid -> entry; owners += owner -> qsid
            }
            guard(qsid, entry)
          } match {
            case Success(value) => Future.successful(value)
            case Failure(error) =>
              F.blocking(host.browser.releaseView(viewId(qsid), binding, entry.ownerKey, entry.epoch))(blockingContext)
                .flatMap(_ => Future.failed(error))
          }
      }
    }.recoverWith { case NonFatal(error) => removeIf(qsid, claim); Future.failed(error) }
  }
  override def resume(qsid: Qsid, request: Request.Head, owner: ConnectionId): Option[Future[SessionGuard[Future, S]]] =
    Some(acquire(qsid, request, owner, existingOnly = true))

  private def guard(qsid: Qsid, acquired: Entry): SessionGuard[Future, S] = {
    val lease       = acquired.lease
    val epoch       = acquired.epoch
    val closeResult = new AtomicReference[Promise[Unit]]()
    new SessionGuard[Future, S] {
      def authorize(state: S): Future[Unit] = withEntry(qsid, lease) { (_, _, actual) =>
        authorizeState(actual, state); () -> None
      }
      def connected(state: S): Future[S] = withEntry(qsid, lease) { (_, _, actual) =>
        val value = JdbcBrowserSecurity.this.connected(state, actual.principal)
        authorizeState(actual, value); value -> None
      }
      override val viewSnapshots: Option[ViewSnapshotSession[Future, S]] = Some(new ViewSnapshotSession[Future, S] {
        def ownerEpoch: ViewOwnershipEpoch = ViewOwnershipEpoch.fromLong(epoch).toOption.get
        def initialize(state: S): Future[S] = withEntry(qsid, lease) { (_, entry, actual) =>
          val value = entry.projection.fold(state)(value => restore(state, value))
          authorizeState(actual, value)
          value -> Some(entry.copy(projection = Some(project(value)), nodes = Map(Id.TopLevel -> value)))
        }
        def commit(candidate: S): Future[Unit] = withEntry(qsid, lease) { (_, entry, actual) =>
          authorizeState(actual, candidate)
          () -> Some(
            entry.copy(projection = Some(project(candidate)), nodes = entry.nodes.updated(Id.TopLevel, candidate))
          )
        }
      })
      private def release(): Future[Unit] = gate.submitCleanup {
        val matching = registry.synchronized(entries.get(qsid).filter(_.lease eq lease))
        matching match {
          case None => Future.unit
          case Some(entry) =>
            registry.synchronized(remove(qsid))
            F.blocking(host.browser.releaseView(viewId(qsid), entry.identity.get.binding, entry.ownerKey, entry.epoch))(
              blockingContext
            ).map { result =>
              val time = now()
              registry.synchronized {
                if (
                  result.isLeft || entry.identity.exists(value => !time.isBefore(value.expires)) ||
                  entries.values.count(e => !e.bootstrap && e.owner.isEmpty) >= limits.disconnected
                ) ()
                else {
                  entry.owner.foreach(id => owners -= id)
                  entries += qsid -> entry.copy(
                    lease = new Object,
                    owner = None,
                    epoch = entry.epoch + 1,
                    deadline = time.plusSeconds(limits.reconnectSeconds)
                  )
                }
              }
            }.recover { case NonFatal(_) => registry.synchronized(remove(qsid)) }
        }
      }
      def close(): Future[Unit] = {
        val proposed = Promise[Unit]()
        if (!closeResult.compareAndSet(null, proposed)) closeResult.get().future
        else {
          val work =
            try {
              val owned = registry.synchronized(entries.get(qsid).exists(_.lease eq lease))
              if (owned) release() else Future.unit
            } catch { case NonFatal(error) => Future.failed(error) }
          work.onComplete { result =>
            proposed.tryComplete(result); ()
          }(ExecutionContext.parasitic)
          proposed.future
        }
      }
    }
  }

  val authority: SessionAuthority[Future, Principal] = new SessionAuthority[Future, Principal] {
    def resolve(binding: InvocationBinding): Future[Either[AccessDenied, Principal]] =
      F.blocking(byOwner(binding.connectionId))(blockingContext)
        .flatMap { case (qsid, entry) =>
          withEntry(qsid, entry.lease)((_, _, actual) => actual.principal.toRight(AccessDenied.Unauthenticated) -> None)
        }
        .recover { case _: SessionAccessDenied => Left(AccessDenied.StaleAuthority) }
    def revalidate(binding: InvocationBinding, principal: Principal): Future[AccessDecision] = resolve(binding).map {
      case Right(current) if current == principal => AccessDecision.Allowed
      case _                                      => AccessDecision.Denied(AccessDenied.StaleAuthority)
    }
  }
  enum Reply {
    case Challenge(ceremony: UUID, challenge: UUID)
    case Prepared(completion: UUID)
  }
  private def committedReply(attempt: UUID): Reply = {
    afterPreparedCommit(attempt)
    Reply.Prepared(attempt)
  }
  private def captured[A](owner: ConnectionId)(body: (Identity, Connection => Unit) => Future[A]): Future[A] =
    F.blocking(byOwner(owner))(blockingContext).flatMap { case (qsid, entry) =>
      val authority = entry.copy(nodes = Map.empty, projection = None)
      body(authority.identity.get, connection => { check(connection, qsid, authority); () })
    }
  def begin(owner: ConnectionId): Future[Either[TransactionFailure, UUID]] =
    captured(owner)((id, validate) => host.begin(id.binding, validate))
  def password(
    owner: ConnectionId,
    ceremony: UUID,
    subject: UUID,
    value: String
  ): Future[Either[TransactionFailure, Reply]] =
    captured(owner) { (id, validate) =>
      if (!admitProof(id.binding)) Future.successful(Left(TransactionFailure.Rejected(OperationError.CapacityExceeded)))
      else
        host.password(ceremony, id.binding, subject, value, validate).flatMap {
          case Left(error) => Future.successful(Left(error))
          case Right(host.PasswordResult.Challenge(ceremony, challenge, _)) =>
            Future.successful(Right(Reply.Challenge(ceremony, challenge)))
          case Right(host.PasswordResult.Ready(proof)) =>
            host.complete(proof, checkConnection = validate).map(_.map(committedReply))
        }
    }
  def factor(
    owner: ConnectionId,
    ceremony: UUID,
    subject: UUID,
    challenge: UUID,
    value: String
  ): Future[Either[TransactionFailure, Reply]] =
    captured(owner) { (id, validate) =>
      if (!admitProof(id.binding)) Future.successful(Left(TransactionFailure.Rejected(OperationError.CapacityExceeded)))
      else
        host.factor(ceremony, id.binding, subject, challenge, value, validate).flatMap {
          case Left(error)  => Future.successful(Left(error))
          case Right(proof) => host.complete(proof, checkConnection = validate).map(_.map(committedReply))
        }
    }
  def factorForCeremony(
    owner: ConnectionId,
    ceremony: UUID,
    challenge: UUID,
    value: String
  ): Future[Either[TransactionFailure, Reply]] = captured(owner) { (id, validate) =>
    if (!admitProof(id.binding)) Future.successful(Left(TransactionFailure.Rejected(OperationError.CapacityExceeded)))
    else
      host.factorForCeremony(ceremony, id.binding, challenge, value, validate).flatMap {
        case Left(error)  => Future.successful(Left(error))
        case Right(proof) => host.complete(proof, checkConnection = validate).map(_.map(committedReply))
      }
  }
  def recover(owner: ConnectionId, ceremony: UUID): Future[Either[TransactionFailure, JdbcReferenceHost.Recovery]] =
    captured(owner)((id, validate) => host.recover(ceremony, id.binding, validate))
  def deliver(request: Request.Head, attempt: UUID): Future[Either[JdbcAuthError, JdbcReferenceHost.CookieCredential]] =
    gate.submit {
      if (!request.header("origin").contains(origin)) denied()
      val (binding, _) = cookies(request)
      F.blocking(host.deliver(attempt, binding))(blockingContext)
    }
  def logout(owner: ConnectionId): Future[Either[JdbcAuthError, Long]] = gate.submit {
    val (qsid, entry) = byOwner(owner)
    outer.transact(connection => check(connection, qsid, entry)).flatMap {
      case Left(_) => registry.synchronized(remove(qsid)); Future.successful(Left(JdbcAuthError.HostDenied))
      case Right(id) =>
        invalidate(_.binding == id.binding)
        F.blocking(host.browser.logout(id.binding))(blockingContext)
    }
  }

  /**
   * Trusted HTTP integration; the official completion service or wrapper owns
   * exact Origin/CSRF checking. These callbacks never fabricate request
   * headers.
   */
  private def deliverBound(attempt: UUID, rawBinding: String): Future[Option[BrowserSessionToken]] = gate.submit {
    F.blocking(host.deliver(attempt, hash(rawBinding)))(blockingContext).map {
      _.toOption.flatMap(credential => BrowserSessionToken.fromString(credential.transportValue).toOption)
    }
  }
  def logoutBinding(rawBinding: String): Future[Either[JdbcAuthError, Long]] = gate.submit {
    val binding = hash(rawBinding)
    invalidate(_.binding == binding)
    F.blocking(host.browser.logout(binding))(blockingContext)
  }
  val completionConfig: AuthenticationCompletionConfig[Future] = AuthenticationCompletionConfig(
    "baseline_binding",
    "baseline_session",
    Set(origin),
    origin.startsWith("https://"),
    3600,
    deliverBound,
    (binding, _) => logoutBinding(binding).map(_.fold(_ => throw new SessionAccessDenied, _ => ()))
  )
  def protectedPrincipal(request: Request.Head): Future[Either[TransactionFailure, Principal]] = gate.submit {
    val (binding, token) = cookies(request)
    outer.transact(connection => identity(connection, binding, token).principal.getOrElse(reject()))
  }

  @scala.annotation.tailrec
  final def retireExpiredMaterial(): Future[Either[TransactionFailure, Int]] = {
    val existing = maintenanceResult.get()
    if (existing != null) existing.future
    else {
      val proposed = Promise[Either[TransactionFailure, Int]]()
      if (!maintenanceResult.compareAndSet(null, proposed)) retireExpiredMaterial()
      else {
        val work =
          try gate.submitMaintenance(outer.transact(connection => host.retireExpiredMaterial(connection)))
          catch { case NonFatal(error) => Future.failed(error) }
        work.onComplete { result =>
          maintenanceResult.compareAndSet(proposed, null)
          proposed.tryComplete(result)
          ()
        }(ExecutionContext.parasitic)
        proposed.future
      }
    }
  }

  /**
   * Current HTTP binding recovers only existing lookup metadata. The initial
   * generation read is an observation; the callback locks/rechecks it after the
   * original ceremony and host locks, preserving writer exclusion order.
   */
  def bootstrapRecovery(
    request: Request.Head
  ): Future[Either[TransactionFailure, Option[JdbcReferenceHost.RecoveryMetadata]]] = gate.submit {
    val (binding, token) = cookies(request)
    outer.transact { connection =>
      val observedGeneration = sql(
        connection,
        "SELECT generation FROM spoonbill_browser_slot WHERE realm=? AND cookie_namespace=? AND binding_hash=?"
      ) { query =>
        query.setString(1, realm); query.setString(2, namespace); query.setBytes(3, binding.bytes)
        Using.resource(query.executeQuery()) { rows =>
          if (!rows.next()) reject(); rows.getLong(1)
        }
      }
      var validated = Option.empty[Identity]
      val metadata = host.bootstrapRecovery(
        connection,
        binding,
        observedGeneration,
        expected => {
          val actual = identity(connection, binding, token, allowPending = true)
          if (actual.generation != expected) reject()
          validated = Some(actual)
        }
      )
      val actual = validated.getOrElse(identity(connection, binding, token, allowPending = true))
      if (actual.principal.nonEmpty) None else metadata
    }
  }

  /**
   * All account writers increment the same policy/security version and detach
   * local leases before dispatch. Even a failed revocation requires fresh view.
   */
  def changeAccount(subject: UUID, enabled: Boolean): Future[Either[TransactionFailure, Unit]] = gate.submit {
    invalidate(_.principal.exists(_.subject == subject))
    outer.transact { connection =>
      sql(
        connection,
        "UPDATE baseline_account SET enabled=?,version=version+1 WHERE id=? AND version<9223372036854775807"
      ) { query =>
        query.setBoolean(1, enabled); query.setObject(2, subject); if (query.executeUpdate() != 1) reject()
      }
    }
  }
  private def operationBinding(principal: Principal): OperationBinding = OperationBinding(
    SubjectId.fromUuid(principal.subject),
    operationRealm,
    AuthSessionId.fromUuid(principal.session),
    SecurityGeneration.fromLong(principal.securityVersion).toOption.get,
    operationPurpose,
    operationScope
  )
  def actionAuthority(owner: ConnectionId, principal: Principal): Future[Either[OperationError, ExecutionAuthority]] =
    F.blocking(byOwner(owner))(blockingContext).flatMap { case (qsid, entry) =>
      withEntry(qsid, entry.lease) { (_, _, actual) =>
        if (!actual.principal.contains(principal)) reject()
        () -> None
      }.map { _ =>
        val digest = RequestDigest.fromBytes(JdbcReferenceHost.syntheticHash("increment-preference")).toOption.get
        issuer.verified(operationBinding(principal), digest, now().plusSeconds(30)).flatMap(issuer.issue)
      }
    }
  def protectedAction(
    owner: ConnectionId,
    principal: Principal,
    permit: ExecutionAuthority
  ): Future[Either[TransactionFailure, Int]] =
    if (permit.reference.operation.invocation.binding != operationBinding(principal))
      Future.successful(Left(TransactionFailure.Rejected(OperationError.HostDenied)))
    else
      F.fork(operations.executeIssued(permit) { (scope, _) =>
        JdbcReferenceHost.admitAudit(scope.transaction, limits.auditRecords)
        val (qsid, entry) = byOwner(owner)
        val actual        = check(scope.transaction, qsid, entry)
        if (!actual.principal.contains(principal)) reject()
        val changed = sql(
          scope.transaction,
          "INSERT INTO baseline_preference(subject,changes) VALUES (?,1) ON CONFLICT(subject) DO UPDATE SET changes=baseline_preference.changes+1 RETURNING changes"
        ) { query =>
          query.setObject(1, principal.subject)
          Using.resource(query.executeQuery()) { rows =>
            rows.next(); rows.getInt(1)
          }
        }
        sql(scope.transaction, "INSERT INTO baseline_audit(attempt,subject) VALUES (?,?)") { query =>
          query.setObject(1, permit.reference.operation.invocation.invocationId.toUuid)
          query.setObject(2, principal.subject); query.executeUpdate()
        }
        changed
      })(blockingContext)
  def resources: Future[Resources] = Future {
    val queued = gate.queued
    val time   = now()
    registry.synchronized {
      sweep(time)
      Resources(
        entries.values.count(_.owner.nonEmpty),
        entries.values.count(e => !e.bootstrap && e.owner.isEmpty),
        entries.values.count(_.bootstrap),
        entries.values.map(_.nodes.size).sum,
        queued,
        proofWindows.size
      )
    }
  }
  def close(): Future[Unit] = gate.close().map { _ =>
    registry.synchronized {
      closed = true; entries = Map.empty; owners = Map.empty; proofWindows = Map.empty; issuer.close()
    }
  }
}
