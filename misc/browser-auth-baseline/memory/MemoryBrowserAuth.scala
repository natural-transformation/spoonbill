package spoonbill.browserauthbaseline

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong, AtomicReference}
import scala.util.control.NonFatal
import spoonbill.effect.Effect
import spoonbill.security.*
import spoonbill.security.Identifiers.*
import spoonbill.security.Versions.*
import spoonbill.security.store.InvocationStatus
import spoonbill.security.transaction.*
import spoonbill.server.{AuthenticationCompletionConfig, BrowserSessionToken}

/**
 * Counted v3 host integration, deliberately single-process. All authoritative
 * records share one monitor, transaction snapshot and publication. No external
 * effects, retries, or independently committing browser store are permitted.
 * Password/factor fixtures are synthetic, not password-storage recommendations.
 */
object MemoryBrowserAuth {
  final case class Account(
    name: String,
    passwordDigest: Vector[Byte],
    factor: Option[String],
    version: Long = 0,
    enabled: Boolean = true
  )
  final case class Principal(name: String, session: AuthSessionId, generation: SlotGeneration, policyVersion: Long)
  final case class Identity private[browserauthbaseline] (
    binding: Vector[Byte],
    generation: SlotGeneration,
    principal: Option[Principal]
  )
  enum Failure {
    case Denied, Expired, Used, Capacity, Throttled, Missing
    case Settlement(value: TransactionFailure)
  }
  enum Reply {
    case Challenge(ceremony: UUID, challenge: UUID)
    case Prepared(completion: UUID)
  }
  enum Recovery {
    case Committed(completion: UUID)
    case NotCommitted
  }
  final case class Counts(
    transactions: Long,
    commits: Long,
    rollbacks: Long,
    proofComputations: Long,
    protectedMutations: Int,
    audits: Int,
    sessions: Int,
    completions: Int,
    retainedDeliveryMaterials: Int
  )
  private[browserauthbaseline] def digest(value: String): Vector[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).toVector
  def syntheticAccount(name: String, password: String, factor: Option[String] = None): Account =
    Account(name, digest(password), factor)
  def secureToken(): String = {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }
}

final class MemoryBrowserAuth[F[_]](
  accounts: Vector[MemoryBrowserAuth.Account],
  clock: () => Instant,
  token: () => String = () => MemoryBrowserAuth.secureToken(),
  id: () => UUID = () => UUID.randomUUID(),
  capacity: Int = 1024,
  maxCeremoniesPerBinding: Int = 16,
  referencePolicy: Option[ReferencePolicy] = None
)(using F: Effect[F]) {
  import MemoryBrowserAuth.*
  require(capacity > 0)
  require(maxCeremoniesPerBinding > 0)
  private val retainedCapacity = referencePolicy.fold(capacity)(_.retainedEntries)
  private val auditCapacity    = referencePolicy.fold(capacity)(_.auditRecords)
  private val ceremonySeconds  = referencePolicy.fold(120L)(_.ceremonySeconds)
  private val sessionSeconds   = referencePolicy.fold(3600L)(_.sessionSeconds)
  private val monitor          = new Object
  private var closed           = false
  private val observedTime     = new AtomicReference(Instant.MIN)
  private val inTransaction = new ThreadLocal[Boolean] {
    override def initialValue(): Boolean = false
  }
  private def now(): Instant = {
    val observed = clock()
    observedTime.updateAndGet(previous => if (observed.isAfter(previous)) observed else previous)
  }
  private val realm     = RealmId.fromUuid(new UUID(0, 1))
  private val namespace = CookieNamespace.fromUuid(new UUID(0, 2))
  private case class Ceremony(
    binding: Vector[Byte],
    generation: SlotGeneration,
    expires: Instant,
    subject: Option[(String, Long)] = None,
    challenge: Option[UUID] = None,
    challengeExpires: Option[Instant] = None,
    claimed: Boolean = false,
    result: Option[Recovery] = None
  )
  private case class Session(principal: Principal, binding: Vector[Byte], expires: Instant)
  private case class Completion(
    ceremony: UUID,
    pending: PendingActivation,
    session: Session,
    tokenDigest: Vector[Byte],
    material: Option[String],
    deliveries: Int = 0
  )
  private case class State(
    accounts: Map[String, Account],
    slots: Map[Vector[Byte], BrowserSessionSlot] = Map.empty,
    ceremonies: Map[UUID, Ceremony] = Map.empty,
    sessions: Map[AuthSessionId, Session] = Map.empty,
    completions: Map[UUID, Completion] = Map.empty,
    operations: Map[InvocationId, StoredOperation] = Map.empty,
    attempts: Map[Vector[Byte], (Instant, Int)] = Map.empty,
    subjectAttempts: Map[(Vector[Byte], String), (Instant, Int)] = Map.empty,
    audits: Vector[String] = Vector.empty,
    subjectCounters: Map[String, Int] = Map.empty,
    mutations: Int = 0
  )
  private var state                                   = State(accounts.map(a => a.name -> a).toMap)
  private val transactions                            = new AtomicLong()
  private val commits                                 = new AtomicLong()
  private val rollbacks                               = new AtomicLong()
  private val computations                            = new AtomicLong()
  private val loseAcknowledgement                     = new AtomicBoolean()
  private val rejectPublication                       = new AtomicBoolean()
  private var presentationChanged: Option[() => Unit] = None
  private def notifyPresentation(): Unit = presentationChanged.foreach { callback =>
    try callback()
    catch { case NonFatal(_) => closed = true; state = State(Map.empty) }
  }
  private final class Denial(val reason: Failure) extends RuntimeException("Synthetic host rejection")
  private def deny(reason: Failure = Failure.Denied): Nothing = throw new Denial(reason)
  private def read[A](body: => A): F[A] = F.delay(monitor.synchronized {
    if (closed) throw new spoonbill.server.SessionAccessDenied
    retireMaterialLocked()
    body
  })
  private def retireMaterialLocked(time: Instant = now()): Int = {
    requireMonitor()
    var retired   = 0
    var remaining = state.completions
    state.completions.foreach { case (id, completion) =>
      if (completion.material.nonEmpty) {
        val account = state.accounts.get(completion.session.principal.name)
        val slot    = state.slots.get(completion.session.binding)
        val deliverable = time.isBefore(completion.pending.expiresAt) && time.isBefore(completion.session.expires) &&
          account.exists(value => value.enabled && value.version == completion.session.principal.policyVersion) &&
          slot.exists(value =>
            value.binding == completion.pending.binding && value.generation == completion.pending.expectedGeneration
          ) &&
          completion.deliveries < 3
        if (!deliverable) {
          remaining = remaining.updated(id, completion.copy(material = None))
          retired += 1
        }
      }
    }
    if (retired > 0) state = state.copy(completions = remaining)
    retired
  }

  /**
   * Bounded by the retained completion capacity. The application owns periodic
   * scheduling and release. Remove plaintext only: expired ceremonies, session
   * digests, generations and negative/replay fences must remain unchanged.
   */
  def retireExpiredMaterial(): F[Int] = F.delay(monitor.synchronized {
    if (closed) 0 else retireMaterialLocked()
  })
  private def attempt[A](body: => A): Either[Failure, A] =
    try Right(body)
    catch { case e: Denial => Left(e.reason) }

  final class Tx private[MemoryBrowserAuth] (initial: State) {
    private val thread = Thread.currentThread()
    private var active = true
    private var value  = initial
    def requireActive(): Unit =
      if (!active || thread != Thread.currentThread()) throw new OperationProtocolException(OperationError.ScopeClosed)
    private[MemoryBrowserAuth] def staged: State               = { requireActive(); value }
    private[MemoryBrowserAuth] def staged_=(next: State): Unit = { requireActive(); value = next }
    private[MemoryBrowserAuth] def close(): Unit               = active = false
  }

  /**
   * This is the existing host runner. Protocol work joins it; it never opens a
   * nested transaction.
   */
  val executor: TransactionExecutor[F, Direct, Tx] = new TransactionExecutor[F, Direct, Tx] {
    val program     = TransactionProgram.direct
    val scopePolicy = TransactionScopePolicy.ThreadConfined
    def transact[A](body: Tx => A): F[Either[TransactionFailure, A]] = {
      val evaluated = new AtomicBoolean()
      read {
        if (inTransaction.get()) Left(TransactionFailure.Rejected(OperationError.TransactionRequired))
        else if (!evaluated.compareAndSet(false, true)) Left(TransactionFailure.Rejected(OperationError.PermitUsed))
        else {
          transactions.incrementAndGet()
          inTransaction.set(true)
          val tx = new Tx(state)
          try {
            val value = body(tx)
            if (rejectPublication.getAndSet(false)) throw new IllegalStateException("Injected rollback")
            val publicationTime = now()
            state = tx.staged
            retireMaterialLocked(publicationTime)
            notifyPresentation()
            commits.incrementAndGet()
            if (loseAcknowledgement.getAndSet(false)) Left(TransactionFailure.CommitUnknown) else Right(value)
          } catch {
            case e: OperationProtocolException =>
              rollbacks.incrementAndGet(); Left(TransactionFailure.Rejected(e.error))
            case _: spoonbill.server.SessionAccessDenied =>
              rollbacks.incrementAndGet(); Left(TransactionFailure.Rejected(OperationError.HostDenied))
            case _: Denial   => rollbacks.incrementAndGet(); Left(TransactionFailure.Rejected(OperationError.HostDenied))
            case NonFatal(_) => rollbacks.incrementAndGet(); Left(TransactionFailure.RolledBack)
          } finally { tx.close(); inTransaction.remove() }
        }
      }
    }
  }
  private val operationStore = new DurableOperationStore[Direct, Tx] {
    def readForDecision(tx: Tx, invocation: OperationInvocation): Option[StoredOperation] = {
      val records = tx.staged.operations.values
      records.find(r =>
        r.operation.invocation.invocationId == invocation.invocationId || r.operation.definition.id == invocation.grantId
      ) match {
        case Some(record) if record.operation.invocation != invocation =>
          throw new OperationProtocolException(OperationError.InvocationConflict)
        case result => result
      }
    }
    def insertPrepared(tx: Tx, operation: PreparedOperation): Unit = throw new OperationProtocolException(
      OperationError.InvalidRegistration
    )
    def recordOutcome(tx: Tx, operation: PreparedOperation, status: InvocationStatus): Unit = {
      if (
        !tx.staged.operations.contains(
          operation.invocation.invocationId
        ) && tx.staged.operations.size >= retainedCapacity
      )
        throw new OperationProtocolException(OperationError.CapacityExceeded)
      tx.staged = tx.staged.copy(operations =
        tx.staged.operations.updated(operation.invocation.invocationId, StoredOperation(operation, status))
      )
    }
  }
  private val issuer =
    new OneUseAuthorityScope(() => now(), maxOutstanding = referencePolicy.fold(capacity)(_.pendingJobs))
  val operations = new OneUseOperationProtocol(executor, operationStore, issuer, () => now())

  private[browserauthbaseline] def atomically[A](body: => A): F[A] = read(body)
  private[browserauthbaseline] def atomicallyNow[A](body: => A): A = monitor.synchronized(body)
  private def requireMonitor(): Unit =
    if (!Thread.holdsLock(monitor)) throw new IllegalStateException("Host monitor required")
  private[browserauthbaseline] def timeLocked: Instant     = { requireMonitor(); now() }
  private[browserauthbaseline] def isClosedLocked: Boolean = { requireMonitor(); closed }
  private[browserauthbaseline] def registerPresentationLocked(callback: () => Unit): Unit = {
    requireMonitor()
    require(presentationChanged.isEmpty, "One presentation registry per host")
    presentationChanged = Some(callback)
  }
  private def ensureSlot(key: Vector[Byte]): BrowserSessionSlot = state.slots.getOrElse(
    key, {
      if (state.slots.size >= retainedCapacity) deny(Failure.Capacity)
      val slot = BrowserSessionSlot.empty(
        SlotBinding(BrowserSessionSlotId.fromUuid(id()), BrowserBindingId.fromUuid(id()), realm, namespace)
      )
      state = state.copy(slots = state.slots.updated(key, slot))
      slot
    }
  )
  private[browserauthbaseline] def captureIdentityLocked(binding: String, credential: Option[String]): Identity = {
    requireMonitor()
    attempt {
      val key       = digest(binding)
      val slot      = ensureSlot(key)
      val principal = credential.map(value => resolve(state, binding, value))
      if (principal.isEmpty && slot.current.nonEmpty) deny()
      Identity(key, slot.generation, principal)
    }.fold(_ => throw new spoonbill.server.SessionAccessDenied, identity)
  }
  private[browserauthbaseline] def lineageGenerationLocked(binding: String): SlotGeneration = {
    requireMonitor()
    ensureSlot(digest(binding)).generation
  }
  private[browserauthbaseline] def checkIdentityLocked(observation: Identity): Unit = {
    requireMonitor()
    if (closed) throw new spoonbill.server.SessionAccessDenied
    val accepted = attempt {
      val slot = state.slots.getOrElse(observation.binding, deny())
      if (slot.generation != observation.generation) deny()
      observation.principal match {
        case Some(principal) => validate(state, principal)
        case None            => if (slot.current.nonEmpty) deny()
      }
    }
    if (accepted.isLeft) throw new spoonbill.server.SessionAccessDenied
  }
  private def admitCeremony(binding: Vector[Byte]): Unit = {
    requireMonitor()
    if (
      state.ceremonies.size >= retainedCapacity ||
      state.ceremonies.valuesIterator
        .count(_.binding == binding) >= referencePolicy.fold(maxCeremoniesPerBinding)(_.ceremoniesPerBinding) ||
      referencePolicy.exists(policy =>
        state.ceremonies.valuesIterator
          .count(c => c.result.isEmpty && now().isBefore(c.expires)) >= policy.liveCeremonies
      )
    )
      deny(Failure.Capacity)
  }
  private[browserauthbaseline] def beginCapturedLocked(observation: Identity): Either[Failure, UUID] = {
    requireMonitor()
    checkIdentityLocked(observation)
    attempt {
      admitCeremony(observation.binding)
      val ceremony = id()
      if (state.ceremonies.contains(ceremony)) deny()
      state = state.copy(ceremonies =
        state.ceremonies
          .updated(ceremony, Ceremony(observation.binding, observation.generation, now().plusSeconds(ceremonySeconds)))
      )
      ceremony
    }
  }

  /** The caller supplies the transport-only binding cookie, never a slot ID. */
  def begin(binding: String): F[Either[Failure, UUID]] = read {
    attempt {
      val key = digest(binding)
      admitCeremony(key)
      if (!state.slots.contains(key) && state.slots.size >= retainedCapacity)
        deny(Failure.Capacity)
      val slot = state.slots.getOrElse(
        key,
        BrowserSessionSlot.empty(
          SlotBinding(BrowserSessionSlotId.fromUuid(id()), BrowserBindingId.fromUuid(id()), realm, namespace)
        )
      )
      val ceremony = id()
      if (state.ceremonies.contains(ceremony)) deny()
      state = state.copy(
        slots = state.slots.updated(key, slot),
        ceremonies =
          state.ceremonies.updated(ceremony, Ceremony(key, slot.generation, now().plusSeconds(ceremonySeconds)))
      )
      ceremony
    }
  }
  private def current(s: State, ceremonyId: UUID, binding: String, allowClaimed: Boolean = false): Ceremony = {
    val ceremony = s.ceremonies.getOrElse(ceremonyId, deny(Failure.Missing))
    if (ceremony.binding != digest(binding)) deny()
    if (!now().isBefore(ceremony.expires)) deny(Failure.Expired)
    if (ceremony.result.isEmpty && ceremony.challengeExpires.exists(deadline => !now().isBefore(deadline)))
      deny(Failure.Expired)
    if (s.slots(ceremony.binding).generation != ceremony.generation) deny()
    if (ceremony.claimed && !allowClaimed) deny(Failure.Used)
    ceremony
  }
  private def policy(s: State, ceremony: Ceremony): Account = {
    val (name, version) = ceremony.subject.getOrElse(deny())
    val account         = s.accounts.getOrElse(name, deny())
    if (!account.enabled || account.version != version) deny()
    account
  }
  private def admitProof(binding: Vector[Byte], subject: String): Unit = {
    val sampledTime = now()
    referencePolicy.foreach { p =>
      state = state.copy(
        attempts = state.attempts.filter { case (_, (time, _)) =>
          sampledTime.isBefore(time.plusSeconds(p.proofWindowSeconds))
        },
        subjectAttempts = state.subjectAttempts.filter { case (_, (time, _)) =>
          sampledTime.isBefore(time.plusSeconds(p.proofWindowSeconds))
        }
      )
    }
    val previous = state.attempts
      .get(binding)
      .filter(p => sampledTime.isBefore(p._1.plusSeconds(referencePolicy.fold(60L)(_.proofWindowSeconds))))
    val count = previous.map(_._2).getOrElse(0)
    if (count >= referencePolicy.fold(8)(_.proofAttempts)) deny(Failure.Throttled)
    if (
      !state.attempts.contains(binding) && state.attempts.size >= referencePolicy.fold(retainedCapacity)(_.rateScopes)
    ) deny(Failure.Capacity)
    state =
      state.copy(attempts = state.attempts.updated(binding, (previous.map(_._1).getOrElse(sampledTime), count + 1)))
    referencePolicy.foreach { p =>
      val key                   = binding -> subject
      val (start, subjectCount) = state.subjectAttempts.getOrElse(key, sampledTime -> 0)
      if (subjectCount >= p.proofAttempts) deny(Failure.Throttled)
      if (!state.subjectAttempts.contains(key) && state.subjectAttempts.size >= p.rateScopes) deny(Failure.Capacity)
      state = state.copy(subjectAttempts = state.subjectAttempts.updated(key, start -> (subjectCount + 1)))
    }
  }
  private def checkFactor(ceremony: Ceremony, account: Account, factor: Option[(UUID, String)]): Unit = {
    if (
      account.factor.nonEmpty && !factor.exists { case (challenge, value) =>
        ceremony.challenge.contains(challenge) && account.factor.contains(value)
      }
    ) deny()
    if (account.factor.isEmpty && factor.nonEmpty) deny()
  }

  /**
   * Rate-limit and capture account version before off-transaction proof
   * computation.
   */
  def password(
    ceremonyId: UUID,
    binding: String,
    name: String,
    password: String,
    checkConnection: () => Unit = () => ()
  ): F[Either[Failure, Reply]] = {
    val captured = read {
      attempt {
        checkConnection()
        val ceremony = current(state, ceremonyId, binding)
        if (ceremony.subject.nonEmpty) deny(Failure.Used)
        admitProof(ceremony.binding, name)
        state.accounts.getOrElse(name, deny())
      }
    }
    F.flatMap(captured) {
      case Left(error) => F.pure(Left(error))
      case Right(account) =>
        F.flatMap(F.delay {
          computations.incrementAndGet()
          referencePolicy.fold(digest(password) == account.passwordDigest)(
            _.verifyPassword(password, account.passwordDigest.toArray)
          )
        }) { valid =>
          F.flatMap(read {
            attempt {
              checkConnection()
              val ceremony = current(state, ceremonyId, binding)
              if (!valid || !account.enabled || state.accounts.get(name) != Some(account) || ceremony.subject.nonEmpty)
                deny()
              val challenge = account.factor.map(_ => id())
              state = state.copy(ceremonies =
                state.ceremonies
                  .updated(
                    ceremonyId,
                    ceremony.copy(
                      subject = Some(name -> account.version),
                      challenge = challenge,
                      challengeExpires =
                        challenge.flatMap(_ => referencePolicy.map(p => now().plusSeconds(p.challengeSeconds)))
                    )
                  )
              )
              challenge
            }
          }) {
            case Left(error)            => F.pure(Left(error))
            case Right(Some(challenge)) => F.pure(Right(Reply.Challenge(ceremonyId, challenge)))
            case Right(None)            => prepare(ceremonyId, binding, None, checkConnection)
          }
        }
    }
  }
  def factor(
    ceremonyId: UUID,
    binding: String,
    challenge: UUID,
    value: String,
    checkConnection: () => Unit = () => ()
  ): F[Either[Failure, Reply]] =
    prepare(ceremonyId, binding, Some(challenge -> value), checkConnection)

  private def prepare(
    ceremonyId: UUID,
    binding: String,
    factor: Option[(UUID, String)],
    checkConnection: () => Unit
  ): F[Either[Failure, Reply]] =
    F.flatMap(read {
      attempt {
        checkConnection()
        val ceremony = current(state, ceremonyId, binding)
        if (factor.nonEmpty) admitProof(ceremony.binding, ceremony.subject.getOrElse(deny())._1)
        checkFactor(ceremony, policy(state, ceremony), factor)
        // Admission survives rollback. No same-attempt proof-consuming callback can be replayed.
        state = state.copy(ceremonies = state.ceremonies.updated(ceremonyId, ceremony.copy(claimed = true)))
      }
    }) {
      case Left(error) => F.pure(Left(error))
      case Right(_) =>
        F.map(executor.transact { tx =>
          checkConnection()
          val ceremony = current(tx.staged, ceremonyId, binding, allowClaimed = true)
          if (ceremony.result.nonEmpty) deny(Failure.Used)
          val account = policy(tx.staged, ceremony)
          checkFactor(ceremony, account, factor)
          if (
            tx.staged.sessions.size >= retainedCapacity || tx.staged.completions.size >= retainedCapacity || tx.staged.audits.size >= auditCapacity
          ) deny(Failure.Capacity)
          if (
            referencePolicy
              .exists(p => tx.staged.completions.valuesIterator.count(_.material.nonEmpty) >= p.deliveryMaterials)
          ) deny(Failure.Capacity)
          val completionId = ceremonyId // Original attempt identity survives response loss.
          val sessionId    = AuthSessionId.fromUuid(id())
          if (tx.staged.sessions.contains(sessionId)) deny()
          val next = tx.staged.slots(ceremony.binding).generation.next.toOption.getOrElse(deny(Failure.Capacity))
          val session = Session(
            Principal(account.name, sessionId, next, account.version),
            ceremony.binding,
            now().plusSeconds(sessionSeconds)
          )
          val material = token()
          BrowserSessionToken.fromString(material).fold(_ => deny(), _ => ())
          if (tx.staged.completions.values.exists(_.tokenDigest == digest(material))) deny()
          val pending = PendingActivation(
            tx.staged.slots(ceremony.binding).binding,
            ceremony.generation,
            CompletionId.fromUuid(completionId),
            sessionId,
            referencePolicy.fold(ceremony.expires) { p =>
              val deadline = now().plusSeconds(p.deliverySeconds)
              if (deadline.isBefore(ceremony.expires)) deadline else ceremony.expires
            }
          )
          val completion      = Completion(ceremonyId, pending, session, digest(material), Some(material))
          val publicationTime = now()
          if (
            !publicationTime.isBefore(ceremony.expires) ||
            ceremony.challengeExpires.exists(deadline => !publicationTime.isBefore(deadline))
          ) deny(Failure.Expired)
          tx.staged = tx.staged.copy(
            sessions = tx.staged.sessions.updated(sessionId, session),
            completions = tx.staged.completions.updated(completionId, completion),
            ceremonies =
              tx.staged.ceremonies.updated(ceremonyId, ceremony.copy(result = Some(Recovery.Committed(completionId)))),
            audits = tx.staged.audits :+ "login-prepared"
          )
          Reply.Prepared(completionId)
        })(_.left.map(Failure.Settlement.apply))
    }

  /**
   * An absent result becomes negative only while excluding every prior writer.
   */
  def recover(ceremonyId: UUID, binding: String, checkConnection: () => Unit = () => ()): F[Either[Failure, Recovery]] =
    F.map(executor.transact { tx =>
      checkConnection()
      val ceremony = current(tx.staged, ceremonyId, binding, allowClaimed = true)
      ceremony.result match {
        case Some(committed @ Recovery.Committed(_)) => policy(tx.staged, ceremony); committed
        case Some(Recovery.NotCommitted)             => Recovery.NotCommitted
        case None =>
          tx.staged = tx.staged.copy(ceremonies =
            tx.staged.ceremonies
              .updated(ceremonyId, ceremony.copy(claimed = true, result = Some(Recovery.NotCommitted)))
          )
          Recovery.NotCommitted
      }
    })(_.left.map(Failure.Settlement.apply))

  def deliver(completionId: UUID, binding: String): F[Option[BrowserSessionToken]] = read {
    attempt {
      val completion = state.completions.getOrElse(completionId, deny())
      val ceremony   = current(state, completion.ceremony, binding, allowClaimed = true)
      policy(state, ceremony)
      if (completion.deliveries >= 3) deny()
      val material = completion.material.getOrElse(deny())
      state = state.copy(completions =
        state.completions.updated(completionId, completion.copy(deliveries = completion.deliveries + 1))
      )
      retireMaterialLocked()
      BrowserSessionToken.fromString(material).toOption.getOrElse(deny())
    }.toOption
  }
  private def resolve(s: State, binding: String, credential: String): Principal = {
    val completion = s.completions.values.find(_.tokenDigest == digest(credential)).getOrElse(deny())
    val session    = completion.session
    if (session.binding != digest(binding) || !now().isBefore(session.expires)) deny()
    val account = s.accounts.getOrElse(session.principal.name, deny())
    if (!account.enabled || account.version != session.principal.policyVersion) deny()
    val slot = s.slots.getOrElse(session.binding, deny())
    slot
      .validateCurrent(slot.binding, session.principal.session, session.principal.generation)
      .fold(_ => deny(), identity)
    session.principal
  }

  /**
   * Only a fresh physical handshake calls activate; existing sockets retain
   * Principal.
   */
  def activate(binding: String, credential: String): F[Either[Failure, Principal]] =
    F.map(executor.transact { tx =>
      val completion = tx.staged.completions.values.find(_.tokenDigest == digest(credential)).getOrElse(deny())
      if (completion.session.binding != digest(binding)) deny()
      val slot = tx.staged.slots(completion.session.binding)
      if (!slot.current.exists(_.sessionId == completion.session.principal.session)) {
        policy(tx.staged, current(tx.staged, completion.ceremony, binding, allowClaimed = true))
        val activated = slot.activate(completion.pending, now()).toOption.getOrElse(deny())
        tx.staged = tx.staged.copy(
          slots = tx.staged.slots.updated(completion.session.binding, activated),
          completions = tx.staged.completions.updated(completion.ceremony, completion.copy(material = None))
        )
      }
      resolve(tx.staged, binding, credential)
    })(_.left.map(Failure.Settlement.apply))
  def protectedPage(binding: String, credential: String): F[Either[Failure, Principal]] = read {
    attempt(resolve(state, binding, credential))
  }
  private def validate(s: State, principal: Principal): Unit = {
    val session = s.sessions.getOrElse(principal.session, deny())
    val account = s.accounts.getOrElse(principal.name, deny())
    if (
      session.principal != principal || !account.enabled || account.version != principal.policyVersion || !now()
        .isBefore(session.expires)
    ) deny()
    val slot = s.slots(session.binding)
    slot.validateCurrent(slot.binding, principal.session, principal.generation).fold(_ => deny(), identity)
  }
  def authorize(principal: Principal): F[Either[Failure, Unit]] = read(attempt(validate(state, principal)))
  def actionAuthority(
    principal: Principal,
    checkConnection: () => Unit = () => ()
  ): F[Either[Failure, ExecutionAuthority]] = read {
    attempt {
      checkConnection()
      validate(state, principal)
      val operationBinding = OperationBinding(
        SubjectId.fromUuid(UUID.nameUUIDFromBytes(principal.name.getBytes(UTF_8))),
        realm,
        principal.session,
        SecurityGeneration.fromLong(principal.policyVersion).toOption.getOrElse(deny()),
        OperationPurpose.fromUuid(new UUID(0, 3)),
        ResourceScope.fromUuid(new UUID(0, 4))
      )
      val request = RequestDigest.fromBytes(digest("increment").toArray).toOption.getOrElse(deny())
      val evidence =
        issuer
          .conditional(
            operationBinding,
            request,
            now().plusSeconds(referencePolicy.fold(60L)(_.operationAuthoritySeconds))
          )
          .toOption
          .getOrElse(deny(Failure.Capacity))
      issuer.issue(evidence).toOption.getOrElse(deny())
    }
  }
  def protectedAction(
    principal: Principal,
    authority: ExecutionAuthority,
    checkConnection: () => Unit = () => ()
  ): F[Either[TransactionFailure, Int]] =
    operations.executeIssued(authority) { (scope, _) =>
      checkConnection()
      val tx = scope.transaction
      validate(tx.staged, principal)
      val binding = authority.reference.operation.invocation.binding
      if (binding.sessionId != principal.session) deny()
      if (tx.staged.audits.size >= auditCapacity) deny(Failure.Capacity)
      // validate above limits keys to registered accounts. Keep the domain
      // counter per subject and the aggregate instrumentation count separate.
      val previous = tx.staged.subjectCounters.getOrElse(principal.name, 0)
      if (previous == Int.MaxValue || tx.staged.mutations == Int.MaxValue) deny(Failure.Capacity)
      val next = previous + 1
      tx.staged = tx.staged.copy(
        subjectCounters = tx.staged.subjectCounters.updated(principal.name, next),
        mutations = tx.staged.mutations + 1,
        audits = tx.staged.audits :+ "protected-action"
      )
      next
    }
  def logout(binding: String, checkConnection: () => Unit = () => ()): F[Either[Failure, Unit]] =
    F.map(executor.transact { tx =>
      checkConnection()
      val key     = digest(binding)
      val slot    = tx.staged.slots.getOrElse(key, deny())
      val cleared = slot.logout(slot.binding).toOption.getOrElse(deny(Failure.Capacity))
      tx.staged = tx.staged.copy(
        slots = tx.staged.slots.updated(key, cleared),
        completions = tx.staged.completions.map { case (key, value) =>
          key -> (if (value.session.binding == digest(binding)) value.copy(material = None) else value)
        }
      )
    })(_.left.map(Failure.Settlement.apply))
  def completionConfig: AuthenticationCompletionConfig[F] = AuthenticationCompletionConfig(
    "baseline_binding",
    "baseline_session",
    Set("http://localhost:8080"),
    false,
    sessionSeconds,
    deliver,
    (binding, _) => F.map(logout(binding))(_.fold(_ => throw new spoonbill.server.SessionAccessDenied, identity))
  )

  /** Trusted synthetic policy/failure controls, not browser endpoints. */
  def changeAccount(name: String, enabled: Boolean): F[Unit] = read {
    val account = state.accounts(name)
    if (account.version == Long.MaxValue) deny(Failure.Capacity)
    state = state.copy(accounts =
      state.accounts.updated(name, account.copy(enabled = enabled, version = account.version + 1))
    )
    retireMaterialLocked()
    notifyPresentation()
  }
  def failNextCommit(): Unit                = rejectPublication.set(true)
  def loseNextCommitAcknowledgement(): Unit = loseAcknowledgement.set(true)
  def counts: F[Counts] = read {
    Counts(
      transactions.get(),
      commits.get(),
      rollbacks.get(),
      computations.get(),
      state.mutations,
      state.audits.size,
      state.sessions.size,
      state.completions.size,
      state.completions.values.count(_.material.nonEmpty)
    )
  }

  /**
   * Diagnostic snapshot of every retained host collection; no handles or
   * secrets.
   */
  def retainedCounts: F[Map[String, Long]] = read {
    Map(
      "accounts"           -> state.accounts.size.toLong,
      "bindings"           -> state.slots.size.toLong,
      "ceremonies"         -> state.ceremonies.size.toLong,
      "sessions"           -> state.sessions.size.toLong,
      "completions"        -> state.completions.size.toLong,
      "outcomes"           -> state.operations.size.toLong,
      "browserRateWindows" -> state.attempts.size.toLong,
      "tupleRateWindows"   -> state.subjectAttempts.size.toLong,
      "audits"             -> state.audits.size.toLong,
      "domainCounters"     -> state.subjectCounters.size.toLong,
      "deliveryMaterials"  -> state.completions.valuesIterator.count(_.material.nonEmpty).toLong
    )
  }
  def close(): Unit = monitor.synchronized {
    closed = true
    issuer.close()
    state = State(Map.empty)
    notifyPresentation()
  }
}
