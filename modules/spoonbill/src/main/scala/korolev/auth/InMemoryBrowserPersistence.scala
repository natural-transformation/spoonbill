package spoonbill.auth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.util.control.NonFatal
import spoonbill.Qsid
import spoonbill.effect.Effect
import spoonbill.security.Identifiers.{ConnectionId, ViewOwnerId}
import spoonbill.security.Versions.ViewOwnershipEpoch
import spoonbill.security.ViewFence
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
    def maintenance(state: H, now: Instant): H                                      = state
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
    extends ReplacementBrowserPersistence[F, Direct, P] {
  import InMemoryBrowserPersistence.*
  final class LiveTransaction private[InMemoryBrowserPersistence] (initial: H)
      extends InMemoryBrowserPersistence.Transaction[H](initial, () => InMemoryBrowserPersistence.this.now())
  type Tx = LiveTransaction
  def capabilities: BrowserProviderCapabilities =
    BrowserProviderCapabilities(
      atomicHostPreparation = true,
      durable = false,
      ownership = BrowserOwnership.ExclusiveScope
    )
  private type Key  = Vector[Byte]
  private type Slot = BrowserReplacementSlot
  private val Slot = BrowserReplacementSlot
  // Retained attempts contain ownership metadata, never a BrowserView whose
  // identity would keep the revoked predecessor's raw transport cookie alive.
  private case class ReplacementView(qsid: Qsid, fence: ViewFence, connectionId: ConnectionId)
  private def replacementView(view: BrowserView[P]): ReplacementView =
    ReplacementView(view.qsid, view.fence, view.connectionId)
  private case class Replacement(
    predecessor: BrowserReplacementPredecessor,
    view: ReplacementView,
    reservation: Option[BrowserReplacementReservation] = None
  )
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
    views: Map[Qsid, View] = Map.empty,
    replacements: Map[CeremonyId, Replacement] = Map.empty
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
        if (slot.current.nonEmpty || slot.replacement.nonEmpty) deny()
        issuer.identity(binding, None, slot.generation, None)
      case Some(raw) =>
        val session = s.credentials.get(digest(raw)).flatMap(s.sessions.get).getOrElse(deny())
        if (session.binding != key) deny()
        val current = principal(s, session)
        if (slot.current.contains(session.id) && session.activeGeneration.contains(slot.generation))
          issuer.identity(binding, token, slot.generation, Some(current), Some(session.id))
        else if (
          pending && slot.replacement.isEmpty && session.activeGeneration.isEmpty && session.generation == slot.generation &&
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
  private def structuralView(s: State, view: BrowserView[P]): Unit = {
    if (!issuer.owns(view)) deny()
    val found = s.views.getOrElse(view.qsid, deny(BrowserFailure.Missing))
    if (
      !found.owner.contains(view.ownerId) || found.epoch != view.epoch ||
      found.connectionId != view.connectionId || found.binding != digest(view.identity.binding)
    ) deny()
    val slot = s.slots.getOrElse(found.binding, deny())
    if (slot.generation != view.identity.generation || slot.current != view.identity.sessionId) deny()
  }
  private def authorizeView(s: State, view: BrowserView[P]): BrowserIdentity[P] = {
    structuralView(s, view)
    if (s.slots(digest(view.identity.binding)).replacement.nonEmpty) deny()
    authorizeIdentity(s, view.identity)
  }
  private def structuralReplacementView(s: State, replacement: Replacement): Unit = {
    val expected  = replacement.view
    val authority = replacement.predecessor.authority
    val found     = s.views.getOrElse(expected.qsid, deny(BrowserFailure.Missing))
    if (
      !found.owner.exists(id => ViewOwnerId.fromUuid(id) == expected.fence.ownerId) ||
      !ViewOwnershipEpoch.fromLong(found.epoch).contains(expected.fence.epoch) ||
      found.connectionId != expected.connectionId || found.binding != authority.bindingKey
    ) deny()
    val slot = s.slots.getOrElse(found.binding, deny())
    if (slot.generation != authority.generation || slot.current != authority.sessionId) deny()
  }
  private def ceremonyView(value: BrowserCeremony): BrowserView[P] = {
    if (!issuer.owns(value)) deny()
    value.view.asInstanceOf[BrowserView[P]]
  }
  private def ceremony(s: State, value: BrowserCeremony): BrowserCeremonyRecord = {
    if (s.replacements.contains(value.id)) deny()
    val view       = ceremonyView(value)
    val authorized = authorizeView(s, view)
    val record     = s.ceremonies.getOrElse(value.id, deny(BrowserFailure.Missing))
    decision(record.validate(digest(authorized.binding), authorized.generation, value.purpose, now()))
    if (record.expiresAt != value.expiresAt || record.challenge != value.challenge) deny()
    record
  }
  def accepts(receipt: CommittedAuthentication): Boolean = issuer.owns(receipt)

  override def inspectPrincipal(binding: String, token: Option[String])(using
    Effect[F]
  ): F[Either[BrowserFailure, Option[P]]] = result {
    if (!validCredential(binding) || token.exists(value => !validCredential(value))) deny()
    if (token.isEmpty && !state.slots.contains(digest(binding))) None
    else identityFor(state, binding, token, pending = true).principal.map(_.value)
  }

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
        val activatedSlot = slot.replacement.map { id =>
          val reservation = staged.replacements(id).reservation.getOrElse(deny())
          if (
            reservation.successor.sessionId != session.id ||
            replacementRecovery(
              staged,
              reservation,
              digest(binding),
              Some(digest(raw))
            ) != BrowserReplacementAvailability.ActivationAvailable
          )
            deny()
          decision(slot.activate(reservation, now()))
        }.getOrElse(Slot(next(slot.generation), Some(session.id)))
        val generation  = next(slot.generation)
        val updatedHost = host.acknowledge(staged.host, completion.hostCompletionId, session.id, now())
        staged = staged.copy(
          host = updatedHost,
          slots = staged.slots.updated(key, activatedSlot),
          sessions = staged.sessions.updated(session.id, session.copy(activeGeneration = Some(generation))),
          completions = staged.completions.updated(session.completion, completion.copy(material = None)),
          // Replacement activation fences every old owner at the same atomic
          // generation boundary. Preserve history/epochs so delayed releases
          // cannot release a subsequently claimed successor view.
          views =
            if (slot.replacement.isEmpty) staged.views
            else
              staged.views.map { case (id, view) =>
                id -> (if (view.binding == key) view.copy(owner = None) else view)
              }
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
      if (state.replacements.contains(id)) deny()
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
              state.sessions.contains(prepared.sessionId) || state.sessions.valuesIterator
                .exists(_.credential == credential) ||
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
            val staged = state.copy(host = tx.hostState)
            // Ordinary preparation must not acquire replacement authority by
            // revoking the authenticated predecessor in its host callback.
            authorizeView(staged, ceremonyView(value))
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

  private def replacementCeremony(s: State, value: BrowserReplacementCeremony): (Replacement, BrowserCeremonyRecord) = {
    if (!issuer.owns(value)) deny()
    val row        = s.replacements.getOrElse(value.id, deny())
    val view       = value.view.asInstanceOf[BrowserView[P]]
    val authorized = authorizeView(s, view)
    val record     = s.ceremonies.getOrElse(value.id, deny())
    decision(record.validate(digest(authorized.binding), authorized.generation, value.purpose, now()))
    if (
      view.fence != row.view.fence || view.connectionId != row.view.connectionId ||
      authorized.authorityStamp != row.predecessor.authority ||
      record.expiresAt != value.expiresAt || record.challenge != value.challenge
    ) deny()
    (row, record)
  }

  def beginReplacement(view: BrowserView[P], purpose: String): F[Either[BrowserFailure, BrowserReplacementCeremony]] =
    result {
      val authorized = authorizeView(state, view)
      if (authorized.host.isEmpty || authorized.sessionId.isEmpty || purpose.isEmpty || purpose.length > 96) deny()
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
      val predecessor = BrowserReplacementPredecessor(
        scope,
        authorized.authorityStamp,
        digest(authorized.token.getOrElse(deny())),
        view.fence
      )
      save(
        state.copy(
          ceremonies = state.ceremonies.updated(id, record),
          replacements = state.replacements.updated(id, Replacement(predecessor, replacementView(view)))
        )
      )
      issuer.replacementCeremony(view, id, purpose, record.expiresAt, None)
    }

  def bindReplacementChallenge(
    value: BrowserReplacementCeremony,
    challenge: BrowserChallenge
  ): F[Either[BrowserFailure, BrowserReplacementCeremony]] = result {
    val (row, record) = replacementCeremony(state, value)
    val original      = row.predecessor.authority.host.getOrElse(deny())
    if (challenge.subject != original.subject || challenge.policyVersion != original.policyVersion) deny()
    val bound = decision(record.bind(challenge))
    save(state.copy(ceremonies = state.ceremonies.updated(value.id, bound)))
    issuer.replacementCeremony(
      value.view.asInstanceOf[BrowserView[P]],
      value.id,
      value.purpose,
      value.expiresAt,
      Some(challenge)
    )
  }

  def resumeReplacement(
    view: BrowserView[P],
    id: CeremonyId,
    purpose: String
  ): F[Either[BrowserFailure, BrowserReplacementCeremony]] = result {
    val authorized = authorizeView(state, view)
    val row        = state.replacements.getOrElse(id, deny())
    val record     = state.ceremonies(id)
    decision(record.validate(digest(authorized.binding), authorized.generation, purpose, now()))
    if (
      authorized.authorityStamp != row.predecessor.authority || view.qsid != row.view.qsid ||
      (record.phase != BrowserCeremonyPhase.Begun && record.phase != BrowserCeremonyPhase.Challenged)
    ) deny()
    // Explicit ownership resume replaces only the current physical owner. The
    // original predecessor (including its captured fence), proof metadata and
    // deadline remain immutable; the resumed view is independently authorized.
    val resumed = row.copy(view = replacementView(view))
    save(state.copy(replacements = state.replacements.updated(id, resumed)))
    issuer.replacementCeremony(view, id, purpose, record.expiresAt, record.challenge)
  }

  def prepareReplacement(value: BrowserReplacementCeremony, invocation: BrowserReplacementInvocation)(
    prepareHost: Tx => HostSessionPrepared
  ): F[Either[TransactionFailure, CommittedReplacement]] = F.delay(
    owned[Either[TransactionFailure, CommittedReplacement]](error => Left(transactionFailure(new Refused(error)))) {
      monitor.synchronized {
        try {
          entry()
          if (!issuer.admitReplacement(value, invocation)) deny(BrowserFailure.Used)
          val (row, original) = replacementCeremony(state, value)
          val admitted        = decision(original.admit)
          save(state.copy(ceremonies = state.ceremonies.updated(value.id, admitted)))
          if (
            admitted.generation == Long.MaxValue ||
            state.completions.valuesIterator.count(_.material.nonEmpty) >= limits.deliveryMaterials
          )
            deny(BrowserFailure.Capacity)
          val tx = new LiveTransaction(state.host)
          inTransaction.set(true)
          try {
            val prepared   = prepareHost(tx)
            val credential = digest(prepared.credential.value)
            if (
              state.sessions.contains(prepared.sessionId) || state.sessions.valuesIterator
                .exists(_.credential == credential) ||
              state.completions.contains(admitted.completionId)
            ) deny()
            val staged = state.copy(host = tx.hostState)
            val session = Session(
              prepared.sessionId,
              admitted.bindingKey,
              credential,
              admitted.generation,
              prepared.expiresAt,
              admitted.completionId
            )
            val successor = principal(staged, session)
            val deadline  = List(now().plusSeconds(limits.deliverySeconds), admitted.expiresAt, prepared.expiresAt).min
            val reservation = decision(
              BrowserReplacementAttempt(admitted, row.predecessor).prepare(
                BrowserReplacementSuccessor(session.id, credential, successor.stamp, prepared.expiresAt),
                host
                  .resolve(staged.host, row.predecessor.authority.sessionId.get, now())
                  .exists(p => now().isBefore(p.expiresAt)),
                deadline,
                now()
              )
            )
            structuralReplacementView(staged, row)
            val slot = staged.slots(admitted.bindingKey)
            if (slot.replacement.nonEmpty || !now().isBefore(deadline)) deny()
            save(
              staged.copy(
                slots = staged.slots.updated(admitted.bindingKey, decision(slot.reserve(reservation))),
                sessions = staged.sessions.updated(session.id, session),
                credentials = (staged.credentials - row.predecessor.credentialHash).updated(credential, session.id),
                completions = staged.completions.updated(
                  admitted.completionId,
                  Completion(session.id, prepared.completionId, deadline, Some(prepared.credential))
                ),
                ceremonies = staged.ceremonies.updated(value.id, reservation.attempt.ceremony),
                replacements = staged.replacements.updated(value.id, row.copy(reservation = Some(reservation)))
              )
            )
            Right(issuer.committedReplacement(value, invocation, admitted.completionId))
          } finally { tx.close(); inTransaction.set(false) }
        } catch { case NonFatal(error) => Left(transactionFailure(error)) }
      }
    }
  )

  private def replacementRecovery(
    s: State,
    reservation: BrowserReplacementReservation,
    binding: Key,
    credential: Option[Key]
  ): BrowserReplacementAvailability = {
    val slot       = s.slots.getOrElse(binding, deny())
    val completion = s.completions.getOrElse(reservation.attempt.ceremony.completionId, deny())
    decision(
      reservation.recover(
        scope,
        binding,
        slot.generation,
        slot.current,
        slot.replacement,
        host.resolve(s.host, reservation.successor.sessionId, now()).map(_.stamp),
        credential,
        completion.material.nonEmpty,
        limits.maxDeliveries - completion.deliveries,
        now()
      )
    )
  }

  def admitReplacementCompletion(
    handoff: CommittedReplacement,
    invocation: BrowserReplacementInvocation
  ): F[Either[BrowserFailure, BrowserReplacementControl]] = result {
    if (!issuer.owns(handoff) || !issuer.owns(invocation)) deny()
    val row         = state.replacements.getOrElse(handoff.ceremonyId, deny())
    val reservation = row.reservation.getOrElse(deny())
    structuralReplacementView(state, row)
    if (
      row.view.fence != handoff.fence || row.view.connectionId != handoff.connectionId ||
      row.predecessor.authority != handoff.authority || reservation.attempt.ceremony.completionId != handoff.completionId ||
      replacementRecovery(
        state,
        reservation,
        row.predecessor.authority.bindingKey,
        None
      ) != BrowserReplacementAvailability.DeliveryAvailable
    ) deny()
    issuer.replacementControl(handoff, invocation).getOrElse(deny())
  }

  private def requestReplacement(request: BrowserReplacementRequest): (Replacement, BrowserCeremonyRecord) = {
    if (!validCredential(request.binding) || request.token.exists(t => !validCredential(t))) deny()
    val key    = digest(request.binding)
    val slot   = state.slots.getOrElse(key, deny())
    val id     = request.ceremonyId.orElse(slot.replacement).getOrElse(deny())
    val row    = state.replacements.getOrElse(id, deny())
    val record = state.ceremonies(id)
    if (
      record.bindingKey != key || request.token.exists { t =>
        val hash = digest(t)
        hash != row.predecessor.credentialHash && !row.reservation.exists(_.successor.credentialHash == hash)
      }
    ) deny()
    (row, record)
  }

  def recoverReplacement(request: BrowserReplacementRequest): F[Either[BrowserFailure, BrowserReplacementRecovery]] =
    result {
      val (row, record) = requestReplacement(request)
      row.reservation match {
        case Some(reservation) =>
          replacementRecovery(state, reservation, digest(request.binding), request.token.map(digest)) match {
            case BrowserReplacementAvailability.ActivationAvailable => BrowserReplacementRecovery.ActivateExistingCookie
            case BrowserReplacementAvailability.DeliveryAvailable =>
              BrowserReplacementRecovery.Deliver(issuer.replacementDelivery(request, record.completionId))
            case BrowserReplacementAvailability.Unavailable => BrowserReplacementRecovery.CommittedUnavailable
          }
        case None =>
          val captured = row.predecessor.authority
          val slot     = state.slots.getOrElse(captured.bindingKey, deny())
          if (slot.generation != captured.generation || slot.current != captured.sessionId) deny()
          save(state.copy(ceremonies = state.ceremonies.updated(record.id, record.reconciled)))
          BrowserReplacementRecovery.NotCommitted
      }
    }

  def replacementPending(request: BrowserReplacementRequest): F[Either[BrowserFailure, Boolean]] = result {
    if (!validCredential(request.binding) || request.token.exists(t => !validCredential(t))) deny()
    state.slots.get(digest(request.binding)).exists { slot =>
      slot.replacement.exists { id =>
        if (request.ceremonyId.exists(_ != id)) deny()
        val (row, _) = requestReplacement(request)
        row.reservation.exists { reservation =>
          replacementRecovery(
            state,
            reservation,
            digest(request.binding),
            request.token.map(digest)
          ) != BrowserReplacementAvailability.Unavailable
        }
      }
    }
  }

  def deliverReplacement(
    request: BrowserReplacementRequest,
    delivery: BrowserReplacementDelivery
  ): F[Either[BrowserFailure, BrowserSessionToken]] = result {
    if (!issuer.consumeReplacementDelivery(delivery, request)) deny()
    val (row, record) = requestReplacement(request)
    val reservation   = row.reservation.getOrElse(deny())
    if (
      record.completionId != delivery.completionId ||
      replacementRecovery(
        state,
        reservation,
        digest(request.binding),
        request.token.map(digest)
      ) != BrowserReplacementAvailability.DeliveryAvailable
    ) deny()
    val completion = state.completions(record.completionId)
    val material   = completion.material.getOrElse(deny())
    val count      = completion.deliveries + 1
    save(
      state.copy(completions =
        state.completions.updated(
          record.completionId,
          completion.copy(
            deliveries = count,
            material = if (count >= limits.maxDeliveries) None else completion.material
          )
        )
      )
    )
    material
  }

  def withSuccessorCompletion[A](view: BrowserView[P])(
    body: (Tx, VerifiedSuccessorCompletion) => A
  ): F[Either[TransactionFailure, A]] = {
    val entered = new AtomicBoolean()
    F.delay(owned[Either[TransactionFailure, A]](error => Left(transactionFailure(new Refused(error)))) {
      monitor.synchronized {
        try {
          entry()
          if (!entered.compareAndSet(false, true)) deny(BrowserFailure.Used)
          val authorized = authorizeView(state, view)
          val id         = authorized.sessionId.getOrElse(deny())
          val reservation =
            state.replacements.valuesIterator.flatMap(_.reservation).find(_.successor.sessionId == id).getOrElse(deny())
          if (!authorized.host.exists(_.stamp == reservation.successor.principal)) deny()
          val completion = state.completions(reservation.attempt.ceremony.completionId)
          val tx         = new LiveTransaction(state.host)
          inTransaction.set(true)
          try {
            val value = body(tx, issuer.successorCompletion(completion.hostCompletionId, id))
            authorizeView(state.copy(host = tx.hostState), view)
            save(state.copy(host = tx.hostState))
            Right(value)
          } finally { tx.close(); inTransaction.set(false) }
        } catch { case NonFatal(error) => Left(transactionFailure(error)) }
      }
    })
  }

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
    if (state.replacements.contains(id)) deny()
    val record = state.ceremonies.getOrElse(id, deny(BrowserFailure.Missing))
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
      !state.replacements.contains(
        record.id
      ) && record.bindingKey == binding && record.generation == authorized.generation && time.isBefore(
        record.expiresAt
      ) &&
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
    if (slot.replacement.exists(id => state.ceremonies(id).completionId != completionId)) deny()
    state.replacements.valuesIterator
      .find(_.reservation.exists(_.attempt.ceremony.completionId == completionId))
      .foreach { row =>
        if (
          replacementRecovery(
            state,
            row.reservation.get,
            digest(binding),
            None
          ) != BrowserReplacementAvailability.DeliveryAvailable
        ) deny()
      }
    if (
      session.binding != digest(binding) || session.activeGeneration.nonEmpty ||
      slot.generation != session.generation || !now().isBefore(completion.expires)
    ) deny()
    principal(state, session)
    if (completion.deliveries >= limits.maxDeliveries) deny(BrowserFailure.Used)
    val material = completion.material.getOrElse(deny())
    save(
      state.copy(completions =
        state.completions.updated(
          completionId,
          completion.copy(
            deliveries = completion.deliveries + 1,
            material =
              if (slot.replacement.nonEmpty && completion.deliveries + 1 >= limits.maxDeliveries) None
              else completion.material
          )
        )
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
      val replacementOwners = slot.replacement.nonEmpty || state.replacements.valuesIterator
        .exists(_.reservation.exists(value => slot.current.contains(value.successor.sessionId)))
      save(
        state.copy(
          slots = state.slots.updated(key, Slot(next(slot.generation), None)),
          completions = state.completions.map { case (id, completion) =>
            id -> (if (state.sessions(completion.sessionId).binding == key) completion.copy(material = None)
                   else completion)
          },
          // Reset also retires an activated replacement's owners if activation
          // won the race with logout. Ordinary session logout is unchanged.
          views =
            if (!replacementOwners) state.views
            else
              state.views.map { case (id, view) =>
                id -> (if (view.binding == key) view.copy(owner = None) else view)
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
        save(
          state.copy(
            host = host.maintenance(state.host, time),
            completions = state.completions.map { case (id, completion) =>
              id -> (if (!time.isBefore(completion.expires)) completion.copy(material = None) else completion)
            }
          )
        )
      }
    }
  }
  def inspectHost: F[Either[BrowserFailure, H]] = result(state.host)

  /**
   * Aggregate observation under the same owner as admission; never grants
   * authority.
   */
  def usage(): F[Either[BrowserFailure, BrowserPersistenceUsage]] = result {
    val active = state.views.valuesIterator.filter(_.owner.nonEmpty).toVector
    BrowserPersistenceUsage(
      state.slots.size.toLong,
      state.views.size.toLong,
      active.size.toLong,
      state.ceremonies.size.toLong,
      state.completions.valuesIterator.count(_.material.nonEmpty).toLong,
      active.groupMapReduce(_.binding)(_ => 1L)(_ + _).valuesIterator.maxOption.getOrElse(0L),
      state.ceremonies.valuesIterator.toVector
        .groupMapReduce(_.bindingKey)(_ => 1L)(_ + _)
        .valuesIterator
        .maxOption
        .getOrElse(0L),
      limits
    )
  }
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
