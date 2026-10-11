package spoonbill.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.{Direct, OperationError, OperationProtocolException, TransactionFailure}
import spoonbill.server.BrowserSessionToken

object InMemoryBrowserPersistence {

  /**
   * Pure host decisions over immutable state. No I/O, detached work or mutable
   * references: returned state joins the browser mutation's atomic publication.
   */
  trait Host[H, P] {
    def resolve(state: H, sessionId: UUID, now: Instant): Option[HostPrincipal[P]]
    def acknowledge(state: H, completionId: UUID, sessionId: UUID, now: Instant): H = state
  }

  /**
   * A synchronous transaction-local view of immutable application state. Proof
   * consumption, session creation and audit/outbox changes compose through
   * modify. Throw to abort; returning an ordinary Left is a successful value.
   */
  class Transaction[H] private[auth] (initial: H, time: () => Instant) {
    private val thread = Thread.currentThread()
    private var active = true
    private var staged = initial
    private def check(): Unit =
      if (Thread.currentThread() != thread || !active)
        throw new OperationProtocolException(OperationError.ScopeClosed)
    final def hostState: H                 = { check(); staged }
    final def now: Instant                 = { check(); time() }
    final def modify(change: H => H): Unit = { check(); staged = change(staged) }
    private[auth] final def close(): Unit  = { check(); active = false }
  }
  final case class Resources(
    bindings: Int,
    views: Int,
    ownedViews: Int,
    ceremonies: Int,
    sessions: Int,
    deliveryMaterials: Int
  )
}

/**
 * A non-durable provider for one process. It stages immutable host H together
 * with browser records under one local transaction owner. Callbacks execute
 * once in Direct, must be short, and must not perform external side effects.
 * Password hashing and browser interaction belong outside the transaction.
 *
 * There is no persistence or clock recovery across restart: a fresh instance
 * has no browser records, rejects old cookies/capabilities, and requires login.
 * Replay/fencing records are retained to explicit limits, never silently
 * evicted. Host state itself is application-owned immutable data; its size is
 * the host's responsibility. No account model or password/factor policy is
 * supplied here.
 */
final class InMemoryBrowserPersistence[F[_], H, P](
  initialHost: H,
  host: InMemoryBrowserPersistence.Host[H, P],
  clock: () => Instant,
  newId: () => UUID,
  limits: BrowserPersistenceLimits = BrowserPersistenceLimits(),
  val scope: BrowserProviderScope = BrowserProviderScope("spoonbill", "session")
)(using F: Effect[F])
    extends TransactionalBrowserPersistence[F, Direct, P] {
  import InMemoryBrowserPersistence.*
  final class LiveTransaction private[InMemoryBrowserPersistence] (initial: H)
      extends InMemoryBrowserPersistence.Transaction[H](initial, () => InMemoryBrowserPersistence.this.now())
  type Tx = LiveTransaction
  def capabilities: BrowserProviderCapabilities =
    BrowserProviderCapabilities(atomicHostPreparation = true, durable = false, exclusiveOwnership = true)
  private type Key = Vector[Byte]
  private case class Slot(generation: Long, current: Option[UUID])
  private case class Session(
    id: UUID,
    binding: Key,
    credential: Key,
    generation: Long,
    expires: Instant,
    completion: UUID,
    activeGeneration: Option[Long] = None
  )
  private case class Completion(
    sessionId: UUID,
    hostCompletionId: UUID,
    expires: Instant,
    material: Option[BrowserSessionToken],
    deliveries: Int = 0
  )
  private case class View(binding: Key, owner: Option[UUID], epoch: Long, connectionId: ConnectionId)
  private case class State(
    host: H,
    slots: Map[Key, Slot] = Map.empty,
    sessions: Map[UUID, Session] = Map.empty,
    credentials: Map[Key, UUID] = Map.empty,
    completions: Map[UUID, Completion] = Map.empty,
    ceremonies: Map[CeremonyId, BrowserCeremonyRecord] = Map.empty,
    views: Map[Qsid, View] = Map.empty
  )
  private final class Refused(val failure: BrowserFailure) extends RuntimeException("Browser operation refused")
  private val monitor           = new Object
  private val admission         = new Object
  private var pendingOperations = 0
  private var accepting         = true
  private val issuer            = new BrowserCapabilityScope[P]
  private val inTransaction = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }
  private var currentState                                                 = Option(State(initialHost))
  private var observed                                                     = Instant.MIN
  private def state: State                                                 = currentState.getOrElse(deny(BrowserFailure.Closed))
  private def save(value: State): Unit                                     = currentState = Some(value)
  private def deny(error: BrowserFailure = BrowserFailure.Denied): Nothing = throw new Refused(error)
  private def now(): Instant = {
    val time = clock()
    if (time.isAfter(observed)) observed = time
    observed
  }
  private def digest(value: String): Key              = MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).toVector
  private def validCredential(value: String): Boolean = value != null && value.matches("[A-Za-z0-9_-]{43}")
  private def next(value: Long): Long                 = if (value == Long.MaxValue) deny(BrowserFailure.Capacity) else value + 1
  private def entry(): Unit = {
    if (inTransaction.get()) throw new OperationProtocolException(OperationError.TransactionRequired)
    if (issuer.isClosed) deny(BrowserFailure.Closed)
  }
  private def owned[A](refuse: BrowserFailure => A)(body: => A): A = {
    if (inTransaction.get()) throw new OperationProtocolException(OperationError.TransactionRequired)
    val admitted = admission.synchronized {
      if (!accepting) Left(BrowserFailure.Closed)
      else if (pendingOperations >= limits.pendingJobs) Left(BrowserFailure.Capacity)
      else { pendingOperations += 1; Right(()) }
    }
    admitted.fold(
      refuse,
      _ =>
        try body
        finally {
          admission.synchronized {
            pendingOperations -= 1
            if (pendingOperations == 0) admission.notifyAll()
          }
        }
    )
  }
  private def result[A](body: => A): F[Either[BrowserFailure, A]] =
    F.delay(owned[Either[BrowserFailure, A]](error => Left(error)) {
      monitor.synchronized {
        try { entry(); Right(body) }
        catch {
          case error: Refused                    => Left(error.failure)
          case error: OperationProtocolException => throw error
          case NonFatal(_)                       => Left(BrowserFailure.StorageFailure)
        }
      }
    })
  private def decision[A](value: Either[BrowserCeremonyError, A]): A = value.fold(
    {
      case BrowserCeremonyError.Denied  => deny()
      case BrowserCeremonyError.Expired => deny(BrowserFailure.Expired)
      case BrowserCeremonyError.Used    => deny(BrowserFailure.Used)
    },
    identity
  )
  private def principal(s: State, session: Session): HostPrincipal[P] = {
    val found = host.resolve(s.host, session.id, now()).getOrElse(deny())
    val time  = now()
    if (!time.isBefore(session.expires) || !time.isBefore(found.expiresAt)) deny(BrowserFailure.Expired)
    found
  }
  private def identityFor(
    s: State,
    binding: String,
    token: Option[String],
    pending: Boolean = false
  ): BrowserIdentity[P] = {
    val key  = digest(binding)
    val slot = s.slots.getOrElse(key, deny(BrowserFailure.Missing))
    token match {
      case None =>
        if (slot.current.nonEmpty) deny()
        issuer.identity(binding, None, slot.generation, None)
      case Some(raw) =>
        val session = s.credentials.get(digest(raw)).flatMap(s.sessions.get).getOrElse(deny())
        if (session.binding != key) deny()
        val current = principal(s, session)
        if (slot.current.contains(session.id) && session.activeGeneration.contains(slot.generation))
          issuer.identity(binding, token, slot.generation, Some(current), Some(session.id))
        else if (
          pending && session.activeGeneration.isEmpty && session.generation == slot.generation &&
          s.completions.get(session.completion).exists(c => now().isBefore(c.expires) && c.material.nonEmpty)
        )
          issuer.identity(binding, token, slot.generation, None)
        else deny()
    }
  }
  private def authorizeIdentity(s: State, value: BrowserIdentity[P]): BrowserIdentity[P] = {
    if (!issuer.owns(value)) deny()
    val fresh = identityFor(s, value.binding, value.token)
    if (!fresh.sameAuthority(value)) deny()
    fresh
  }
  private def authorizeView(s: State, view: BrowserView[P]): BrowserIdentity[P] = {
    if (!issuer.owns(view)) deny()
    val found = s.views.getOrElse(view.qsid, deny(BrowserFailure.Missing))
    if (
      !found.owner.contains(view.ownerId) || found.epoch != view.epoch ||
      found.connectionId != view.connectionId || found.binding != digest(view.identity.binding)
    ) deny()
    authorizeIdentity(s, view.identity)
  }
  private def ceremonyView(value: BrowserCeremony): BrowserView[P] = {
    if (!issuer.owns(value)) deny()
    value.view.asInstanceOf[BrowserView[P]]
  }
  private def ceremony(s: State, value: BrowserCeremony): BrowserCeremonyRecord = {
    val view       = ceremonyView(value)
    val authorized = authorizeView(s, view)
    val record     = s.ceremonies.getOrElse(value.id, deny(BrowserFailure.Missing))
    decision(record.validate(digest(authorized.binding), authorized.generation, value.purpose, now()))
    if (record.expiresAt != value.expiresAt || record.challenge != value.challenge) deny()
    record
  }
  def accepts(receipt: CommittedAuthentication): Boolean = issuer.owns(receipt)

  def capture(
    binding: String,
    token: Option[String],
    activate: Boolean
  ): F[Either[BrowserFailure, BrowserIdentity[P]]] = result {
    if (!validCredential(binding) || token.exists(value => !validCredential(value))) deny()
    val key    = digest(binding)
    var staged = state
    if (!staged.slots.contains(key)) {
      if (token.nonEmpty) deny()
      if (staged.slots.size >= limits.retainedBindings) deny(BrowserFailure.Capacity)
      staged = staged.copy(slots = staged.slots.updated(key, Slot(0L, None)))
    }
    if (activate) token.foreach { raw =>
      val session = staged.credentials.get(digest(raw)).flatMap(staged.sessions.get).getOrElse(deny())
      val slot    = staged.slots(key)
      if (session.binding != key) deny()
      principal(staged, session)
      if (session.activeGeneration.isEmpty) {
        val completion = staged.completions.getOrElse(session.completion, deny())
        if (slot.generation != session.generation || !now().isBefore(completion.expires)) deny()
        val generation  = next(slot.generation)
        val updatedHost = host.acknowledge(staged.host, completion.hostCompletionId, session.id, now())
        staged = staged.copy(
          host = updatedHost,
          slots = staged.slots.updated(key, Slot(generation, Some(session.id))),
          sessions = staged.sessions.updated(session.id, session.copy(activeGeneration = Some(generation))),
          completions = staged.completions.updated(session.completion, completion.copy(material = None))
        )
      }
    }
    val captured = identityFor(staged, binding, token, pending = !activate)
    save(staged)
    captured
  }

  def claim(
    value: BrowserIdentity[P],
    qsid: Qsid,
    connectionId: ConnectionId,
    existingOnly: Boolean
  ): F[Either[BrowserFailure, BrowserView[P]]] = result {
    val authorized = authorizeIdentity(state, value)
    if (qsid.deviceId.isEmpty || qsid.sessionId.isEmpty || BrowserView.key(qsid).length > 192) deny()
    val binding  = digest(authorized.binding)
    val previous = state.views.get(qsid)
    if (existingOnly && previous.isEmpty) deny(BrowserFailure.Missing)
    if (previous.exists(_.binding != binding)) deny()
    if (previous.isEmpty && state.views.size >= limits.retainedViews) deny(BrowserFailure.Capacity)
    if (
      !previous.exists(_.owner.nonEmpty) &&
      state.views.valuesIterator.count(v => v.binding == binding && v.owner.nonEmpty) >= limits.activeViewsPerBinding
    )
      deny(BrowserFailure.Capacity)
    if (state.views.valuesIterator.exists(v => v.owner.nonEmpty && v.connectionId == connectionId)) deny()
    val epoch = previous.fold(1L)(row => next(row.epoch))
    val owner = newId()
    save(state.copy(views = state.views.updated(qsid, View(binding, Some(owner), epoch, connectionId))))
    issuer.view(authorized, qsid, connectionId, owner, epoch)
  }
  def authorize(view: BrowserView[P]): F[Either[BrowserFailure, BrowserIdentity[P]]] = result {
    authorizeView(state, view)
  }
  def release(view: BrowserView[P]): F[Unit] = F.delay {
    monitor.synchronized {
      if (inTransaction.get()) throw new OperationProtocolException(OperationError.TransactionRequired)
      if (issuer.owns(view)) state.views.get(view.qsid).foreach { row =>
        if (row.owner.contains(view.ownerId) && row.epoch == view.epoch)
          save(state.copy(views = state.views.updated(view.qsid, row.copy(owner = None))))
      }
    }
  }
  def begin(view: BrowserView[P], purpose: String): F[Either[BrowserFailure, BrowserCeremony]] = result {
    val authorized = authorizeView(state, view)
    if (purpose.isEmpty || purpose.length > 96) deny()
    val binding = digest(authorized.binding)
    if (
      state.ceremonies.size >= limits.retainedCeremonies ||
      state.ceremonies.valuesIterator.count(_.bindingKey == binding) >= limits.ceremoniesPerBinding
    )
      deny(BrowserFailure.Capacity)
    val id         = CeremonyId(newId())
    val completion = newId()
    if (state.ceremonies.contains(id) || state.ceremonies.valuesIterator.exists(_.completionId == completion)) deny()
    val record = BrowserCeremonyRecord(
      id,
      completion,
      binding,
      authorized.generation,
      purpose,
      now().plusSeconds(limits.ceremonySeconds)
    )
    save(state.copy(ceremonies = state.ceremonies.updated(id, record)))
    issuer.ceremony(view, id, purpose, record.expiresAt, None)
  }
  def bindChallenge(value: BrowserCeremony, challenge: BrowserChallenge): F[Either[BrowserFailure, BrowserCeremony]] =
    result {
      val bound = decision(ceremony(state, value).bind(challenge))
      save(state.copy(ceremonies = state.ceremonies.updated(value.id, bound)))
      issuer.ceremony(ceremonyView(value), value.id, value.purpose, value.expiresAt, Some(challenge))
    }
  def resume(view: BrowserView[P], id: CeremonyId, purpose: String): F[Either[BrowserFailure, BrowserCeremony]] =
    result {
      val authorized = authorizeView(state, view)
      val record     = state.ceremonies.getOrElse(id, deny(BrowserFailure.Missing))
      decision(record.validate(digest(authorized.binding), authorized.generation, purpose, now()))
      if (record.phase != BrowserCeremonyPhase.Begun && record.phase != BrowserCeremonyPhase.Challenged)
        deny(BrowserFailure.Used)
      issuer.ceremony(view, id, purpose, record.expiresAt, record.challenge)
    }
  private def transactionFailure(error: Throwable): TransactionFailure = error match {
    case protocol: OperationProtocolException => TransactionFailure.Rejected(protocol.error)
    case refusal: Refused =>
      TransactionFailure.Rejected(refusal.failure match {
        case BrowserFailure.Expired  => OperationError.Expired
        case BrowserFailure.Used     => OperationError.PermitUsed
        case BrowserFailure.Capacity => OperationError.CapacityExceeded
        case BrowserFailure.Closed   => OperationError.ScopeClosed
        case _                       => OperationError.HostDenied
      })
    case _ => TransactionFailure.RolledBack
  }
  def prepare(
    value: BrowserCeremony
  )(prepareHost: Tx => HostSessionPrepared): F[Either[TransactionFailure, CommittedAuthentication]] = F.delay(
    owned[Either[TransactionFailure, CommittedAuthentication]](error => Left(transactionFailure(new Refused(error)))) {
      monitor.synchronized {
        try {
          entry()
          if (!issuer.admit(value)) deny(BrowserFailure.Used)
          val admitted = decision(ceremony(state, value).admit)
          // Admission survives failure of the later atomic mutation. A new alias
          // cannot replay this attempt; reconciliation may only fence it negative.
          save(state.copy(ceremonies = state.ceremonies.updated(value.id, admitted)))
          val tx = new LiveTransaction(state.host)
          inTransaction.set(true)
          try {
            val prepared = prepareHost(tx)
            ceremony(state, value)
            val credential = digest(prepared.credential.value)
            if (
              state.sessions.contains(prepared.sessionId) || state.credentials.contains(credential) ||
              state.completions.contains(admitted.completionId)
            ) deny()
            if (state.completions.valuesIterator.count(_.material.nonEmpty) >= limits.deliveryMaterials)
              deny(BrowserFailure.Capacity)
            val deadline = now().plusSeconds(limits.deliverySeconds)
            val expires  = if (deadline.isBefore(admitted.expiresAt)) deadline else admitted.expiresAt
            val session = Session(
              prepared.sessionId,
              admitted.bindingKey,
              credential,
              admitted.generation,
              prepared.expiresAt,
              admitted.completionId
            )
            val staged   = state.copy(host = tx.hostState)
            val resolved = principal(staged, session)
            if (prepared.expiresAt.isAfter(resolved.expiresAt)) deny(BrowserFailure.Expired)
            admitted.challenge.foreach { expected =>
              if (resolved.subject != expected.subject || resolved.policyVersion != expected.policyVersion) deny()
            }
            val committed = decision(admitted.committed)
            // Host hooks may have observed a later instant; recheck the original
            // ceremony before publishing all participant state at once.
            ceremony(state, value)
            save(
              staged.copy(
                sessions = staged.sessions.updated(session.id, session),
                credentials = staged.credentials.updated(credential, session.id),
                completions = staged.completions.updated(
                  admitted.completionId,
                  Completion(
                    session.id,
                    prepared.completionId,
                    if (prepared.expiresAt.isBefore(expires)) prepared.expiresAt else expires,
                    Some(prepared.credential)
                  )
                ),
                ceremonies = staged.ceremonies.updated(value.id, committed)
              )
            )
            Right(issuer.committed(admitted.completionId))
          } finally { tx.close(); inTransaction.set(false) }
        } catch { case NonFatal(error) => Left(transactionFailure(error)) }
      }
    }
  )

  /**
   * Authorize a domain mutation and publish its immutable host state in one
   * atomic operation. This does not grant one-use proof authority: applications
   * must consume their operation evidence inside this same callback.
   */
  def transact[A](view: BrowserView[P])(body: Tx => A): F[Either[TransactionFailure, A]] =
    F.delay(owned[Either[TransactionFailure, A]](error => Left(transactionFailure(new Refused(error)))) {
      monitor.synchronized {
        try {
          entry()
          authorizeView(state, view)
          val tx = new LiveTransaction(state.host)
          inTransaction.set(true)
          try {
            val value = body(tx)
            authorizeView(state, view)
            save(state.copy(host = tx.hostState))
            Right(value)
          } finally { tx.close(); inTransaction.set(false) }
        } catch { case NonFatal(error) => Left(transactionFailure(error)) }
      }
    })
  def recover(view: BrowserView[P], id: CeremonyId): F[Either[BrowserFailure, BrowserRecovery]] = result {
    val authorized = authorizeView(state, view)
    val record     = state.ceremonies.getOrElse(id, deny(BrowserFailure.Missing))
    decision(record.validate(digest(authorized.binding), authorized.generation, record.purpose, now()))
    if (record.phase == BrowserCeremonyPhase.Committed) {
      val completion = state.completions.getOrElse(record.completionId, deny())
      if (!now().isBefore(completion.expires) || completion.material.isEmpty) deny(BrowserFailure.Expired)
      if (completion.deliveries >= limits.maxDeliveries) deny(BrowserFailure.Used)
      principal(state, state.sessions(completion.sessionId))
      BrowserRecovery.Committed(issuer.committed(record.completionId))
    } else {
      save(state.copy(ceremonies = state.ceremonies.updated(id, record.reconciled)))
      BrowserRecovery.NotCommitted
    }
  }
  def pending(view: BrowserView[P]): F[Either[BrowserFailure, Option[BrowserContinuation]]] = result {
    val authorized = authorizeView(state, view)
    val binding    = digest(authorized.binding)
    val time       = now()
    val matching = state.ceremonies.valuesIterator.filter { record =>
      record.bindingKey == binding && record.generation == authorized.generation && time.isBefore(record.expiresAt) &&
      (record.phase == BrowserCeremonyPhase.Challenged || record.phase == BrowserCeremonyPhase.Admitted ||
        (record.phase == BrowserCeremonyPhase.Committed && state.completions.get(record.completionId).exists {
          completion =>
            val session = state.sessions(completion.sessionId)
            completion.material.nonEmpty && completion.deliveries < limits.maxDeliveries &&
            time.isBefore(completion.expires) && time.isBefore(session.expires) &&
            host.resolve(state.host, session.id, time).exists(p => time.isBefore(p.expiresAt))
        }))
    }
    matching
      .foldLeft(Option.empty[BrowserCeremonyRecord]) { (selected, record) =>
        selected match {
          case Some(previous)
              if previous.expiresAt.isAfter(record.expiresAt) ||
                (previous.expiresAt == record.expiresAt && previous.id.value.toString > record.id.value.toString) =>
            selected
          case _ => Some(record)
        }
      }
      .map { record =>
        val expires =
          if (record.phase == BrowserCeremonyPhase.Committed) state.completions(record.completionId).expires
          else record.expiresAt
        BrowserContinuation(
          record.id,
          record.purpose,
          record.challenge,
          record.phase != BrowserCeremonyPhase.Challenged,
          expires
        )
      }
  }
  def deliver(completionId: UUID, binding: String): F[Option[BrowserSessionToken]] = F.map(result {
    if (!validCredential(binding)) deny()
    val completion = state.completions.getOrElse(completionId, deny(BrowserFailure.Missing))
    val session    = state.sessions(completion.sessionId)
    val slot       = state.slots.getOrElse(session.binding, deny())
    if (
      session.binding != digest(binding) || session.activeGeneration.nonEmpty ||
      slot.generation != session.generation || !now().isBefore(completion.expires)
    ) deny()
    principal(state, session)
    if (completion.deliveries >= limits.maxDeliveries) deny(BrowserFailure.Used)
    val material = completion.material.getOrElse(deny())
    save(
      state.copy(completions =
        state.completions.updated(completionId, completion.copy(deliveries = completion.deliveries + 1))
      )
    )
    material
  })(_.toOption)
  def logout(binding: String, token: Option[String]): F[Either[BrowserFailure, Unit]] = result {
    if (!validCredential(binding)) deny()
    val key = digest(binding)
    // A fresh non-durable instance has no old authority to revoke. A confirmed
    // absence is a successful no-op, allowing transport to clear a stale cookie.
    // Never allocate a slot or reset retained generation/replay history here.
    state.slots.get(key).foreach { slot =>
      save(
        state.copy(
          slots = state.slots.updated(key, Slot(next(slot.generation), None)),
          completions = state.completions.map { case (id, completion) =>
            id -> (if (state.sessions(completion.sessionId).binding == key) completion.copy(material = None)
                   else completion)
          }
        )
      )
    }
  }
  def maintenance(): F[Unit] = F.delay {
    monitor.synchronized {
      if (inTransaction.get()) throw new OperationProtocolException(OperationError.TransactionRequired)
      if (!issuer.isClosed) {
        val time = now()
        save(state.copy(completions = state.completions.map { case (id, completion) =>
          id -> (if (!time.isBefore(completion.expires)) completion.copy(material = None) else completion)
        }))
      }
    }
  }
  def inspectHost: F[Either[BrowserFailure, H]] = result(state.host)
  def resources: F[Resources] = F.delay {
    monitor.synchronized {
      currentState.fold(Resources(0, 0, 0, 0, 0, 0)) { s =>
        Resources(
          s.slots.size,
          s.views.size,
          s.views.valuesIterator.count(_.owner.nonEmpty),
          s.ceremonies.size,
          s.sessions.size,
          s.completions.valuesIterator.count(_.material.nonEmpty)
        )
      }
    }
  }
  def close(): F[Unit] = F.delay {
    if (inTransaction.get()) throw new OperationProtocolException(OperationError.TransactionRequired)
    admission.synchronized {
      accepting = false
      while (pendingOperations > 0) admission.wait()
    }
    monitor.synchronized {
      issuer.close()
      currentState = None
    }
  }
}
