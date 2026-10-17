package example.browserauth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal
import spoonbill.action.{AuthenticatedActionContext, UiOutcome}
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.transaction.{OperationError, OperationProtocolException, TransactionFailure}
import spoonbill.sensitive.{Audience, Purpose, SensitiveAccess}

/**
 * Complete synthetic host policy for the non-durable provider. Only immutable
 * host state enters the transaction. Proof computation and credential
 * generation precede transaction admission; expensive password work uses the
 * supplied worker.
 */
final class MemoryAccounts[F[_]](
  origin: String = "http://localhost:8080",
  clock: () => Instant = () => Instant.now(),
  proofContext: ExecutionContext = ExecutionContext.global,
  deliveryLimit: Int = 3
)(using F: Effect[F], ec: ExecutionContext)
    extends Accounts[F] {
  private case class Account(
    principal: Principal,
    password: Vector[Byte],
    factor: Option[Vector[Byte]],
    recoveryHashes: Vector[Vector[Byte]] = Vector.empty,
    credentialVersion: Long = 1L,
    policyVersion: Long = 1L,
    enabled: Boolean = true
  )
  private case class Session(
    subject: UUID,
    securityVersion: Long,
    policyVersion: Long,
    expires: Instant,
    completion: UUID,
    acknowledged: Boolean = false
  )
  private case class Audit(subject: UUID, operation: String, time: Instant)
  private case class HostState(
    accounts: Map[UUID, Account],
    sessions: Map[UUID, Session] = Map.empty,
    consumed: Set[String] = Set.empty,
    audits: Vector[Audit] = Vector.empty,
    counters: Map[UUID, Int] = Map.empty,
    results: Map[UUID, RecoveryResults.Record] = Map.empty
  )
  private val passwordDigest    = DomainPolicy.passwordHash("password")
  private val enrollmentFactor  = DomainPolicy.factorHash("123456")
  private val replacementFactor = DomainPolicy.factorHash("654321")
  private val initial = HostState(DomainPolicy.accounts.iterator.map { seed =>
    seed.id -> Account(Principal(seed.id, seed.name), passwordDigest, seed.factor.map(DomainPolicy.factorHash))
  }.toMap)
  private val byName          = DomainPolicy.accounts.iterator.map(account => account.name -> account.id).toMap
  private val rate            = new DomainPolicy.RateLimit(clock)
  private val recoveryResults = RecoveryResults.ephemeral(clock)
  private val provider = new InMemoryBrowserPersistence[F, HostState, Principal](
    initial,
    new InMemoryBrowserPersistence.Host[HostState, Principal] {
      def resolve(state: HostState, sessionId: UUID, time: Instant): Option[HostPrincipal[Principal]] = for {
        session <- state.sessions.get(sessionId)
        account <- state.accounts.get(session.subject)
        if account.enabled && account.credentialVersion == session.securityVersion &&
          account.policyVersion == session.policyVersion && time.isBefore(session.expires)
      } yield HostPrincipal(
        account.principal,
        session.subject.toString,
        session.securityVersion,
        session.policyVersion,
        session.expires
      )
      override def acknowledge(state: HostState, completionId: UUID, sessionId: UUID, time: Instant): HostState = {
        val session = state.sessions.getOrElse(sessionId, reject())
        if (session.completion != completionId) reject()
        state.copy(sessions = state.sessions.updated(sessionId, session.copy(acknowledged = true)))
      }
      override def maintenance(state: HostState, time: Instant): HostState =
        state.copy(results = state.results.filter { case (_, record) => time.isBefore(record.expiresAt) })
    },
    clock,
    () => UUID.randomUUID(),
    limits = BrowserPersistenceLimits(maxDeliveries = deliveryLimit),
    scope = BrowserProviderScope("example", "session")
  )
  val browser = new BrowserAuthentication[F, Page, Principal](
    provider,
    Presentation.policy,
    BrowserConfiguration(provider.scope, Set(origin), secureCookies = origin.startsWith("https://")),
    clock = clock,
    sensitiveAccess = view =>
      view.identity.sessionId.map { session =>
        new SensitiveAccess[F, Page] {
          def authorize(purpose: Purpose, state: Page): F[Audience] =
            if (purpose == RecoveryResults.purpose)
              provider.withSuccessorCompletion(view)((_, _) => Audience.fromUuid(session)).flatMap {
                case Right(audience) => F.pure(audience)
                case Left(_)         => F.fail(new IllegalStateException("The recovery-code successor is no longer current"))
              }
            else F.fail(new IllegalStateException("Unknown sensitive purpose"))
        }
      }
  )

  def currentCount(principal: Principal): F[Int] = provider.inspectHost.flatMap {
    case Right(state) => F.pure(state.counters.getOrElse(principal.subject, 0))
    case Left(_)      => F.fail(new IllegalStateException("The example account store is unavailable"))
  }

  private val admission = new Object
  private var pending   = 0
  private var stopped   = false
  private var waiters   = List.empty[Effect.Promise[Unit]]
  private def proof[A](unavailable: => A, unresolved: => A)(body: => F[A]): F[A] = F.uncancelable {
    F.delayAsync {
      val admitted = admission.synchronized {
        if (stopped || pending >= 256) false else { pending += 1; true }
      }
      if (!admitted) F.pure(unavailable)
      else {
        val released = new AtomicBoolean()
        def release(): Unit = if (released.compareAndSet(false, true)) {
          val callbacks = admission.synchronized {
            pending -= 1
            if (stopped && pending == 0) { val result = waiters; waiters = Nil; result }
            else Nil
          }
          callbacks.foreach(_(Right(())))
        }
        F.delayAsync(body)
          .recover {
            // Observer failure never proves rollback; retain the recovery lookup.
            case NonFatal(_) => unresolved
          }
          .flatMap(value => F.delay { release(); value })
          .recoverF { case NonFatal(error) =>
            F.delay(release()).flatMap(_ => F.fail(error))
          }
      }
    }
  }
  private def signInProof(ceremony: BrowserCeremony)(body: => F[SignIn]): F[SignIn] =
    proof[SignIn](SignIn.Rejected("Sign-in is temporarily unavailable."), SignIn.Unresolved(ceremony.id))(body)
  private def reject(): Nothing = throw new OperationProtocolException(OperationError.HostDenied)
  private def current(tx: provider.Tx, expected: Account): Account = {
    val account = tx.hostState.accounts.getOrElse(expected.principal.subject, reject())
    if (
      !account.enabled || account.credentialVersion != expected.credentialVersion ||
      account.policyVersion != expected.policyVersion
    ) reject()
    account
  }
  private def completed(
    ceremony: BrowserCeremony,
    expected: Account,
    proofKey: String,
    factor: Option[Vector[Byte]]
  ): F[SignIn] = {
    val sessionId    = UUID.randomUUID()
    val completionId = UUID.randomUUID()
    BrowserCredentials.generate[F].flatMap { credential =>
      provider
        .prepare(ceremony) { tx =>
          if (ceremony.purpose != "sign-in") reject()
          val account = current(tx, expected)
          val time    = tx.now
          ceremony.challenge.foreach { challenge =>
            if (
              challenge.subject != account.principal.subject.toString ||
              challenge.credentialVersion != account.credentialVersion ||
              challenge.policyVersion != account.policyVersion ||
              !DomainPolicy.challengeDeadline(challenge.reference).exists(time.isBefore)
            ) reject()
          }
          if (account.factor.nonEmpty && !factor.exists(value => account.factor.exists(DomainPolicy.same(_, value))))
            reject()
          if (tx.hostState.consumed.contains(proofKey)) reject()
          if (
            tx.hostState.consumed.size >= DomainPolicy.retainedEntries ||
            tx.hostState.sessions.size >= DomainPolicy.retainedEntries || tx.hostState.audits.size >= DomainPolicy.auditRecords
          )
            throw new OperationProtocolException(OperationError.CapacityExceeded)
          val expires = time.plusSeconds(DomainPolicy.sessionSeconds)
          tx.modify(state =>
            state.copy(
              consumed = state.consumed + proofKey,
              sessions = state.sessions.updated(
                sessionId,
                Session(
                  account.principal.subject,
                  account.credentialVersion,
                  account.policyVersion,
                  expires,
                  completionId
                )
              ),
              audits = state.audits :+ Audit(account.principal.subject, "sign-in", time)
            )
          )
          new HostSessionPrepared(sessionId, completionId, expires, credential)
        }
        .map {
          case Right(receipt) => SignIn.Completed(receipt)
          case Left(TransactionFailure.Rejected(OperationError.PermitUsed | OperationError.EvidenceUsed)) =>
            SignIn.Unresolved(ceremony.id)
          case Left(TransactionFailure.CommitUnknown | TransactionFailure.StorageFailure) =>
            SignIn.Unresolved(ceremony.id)
          case Left(_) => SignIn.Rejected("Sign-in was not accepted. Begin a new attempt.")
        }
    }
  }

  def signIn(ceremony: BrowserCeremony, username: String, password: String): F[SignIn] = signInProof(ceremony) {
    if (!rate.admit(ceremony.browserKey, username)) F.pure(SignIn.Rejected("Too many attempts. Try later."))
    else
      provider.inspectHost.flatMap {
        case Right(state) =>
          byName.get(username).flatMap(state.accounts.get) match {
            case Some(account) if account.enabled =>
              F.blocking(DomainPolicy.passwordHash(password))(proofContext).flatMap { submitted =>
                if (!DomainPolicy.same(submitted, account.password))
                  F.pure(SignIn.Rejected("Credentials were not accepted."))
                else
                  account.factor match {
                    case None => completed(ceremony, account, "password:" + ceremony.id.value, None)
                    case Some(_) =>
                      val challenge = BrowserChallenge(
                        account.principal.subject.toString,
                        account.credentialVersion,
                        account.policyVersion,
                        DomainPolicy.challengeReference(clock(), ceremony.expiresAt)
                      )
                      provider.bindChallenge(ceremony, challenge).map {
                        case Right(bound) => SignIn.Challenge(bound.id, challenge.reference)
                        case Left(_)      => SignIn.Rejected("This sign-in is no longer available.")
                      }
                  }
              }
            case _ => F.pure(SignIn.Rejected("Credentials were not accepted."))
          }
        case Left(_) => F.pure(SignIn.Rejected("Sign-in is temporarily unavailable."))
      }
  }
  def factor(ceremony: BrowserCeremony, value: String): F[SignIn] = signInProof(ceremony) {
    ceremony.challenge match {
      case Some(challenge) if rate.admit(ceremony.browserKey, challenge.subject) =>
        provider.inspectHost.flatMap {
          case Right(state) =>
            Try(UUID.fromString(challenge.subject)).toOption.flatMap(state.accounts.get) match {
              case Some(account) =>
                val submitted = DomainPolicy.factorHash(value)
                if (!account.factor.exists(DomainPolicy.same(_, submitted)))
                  F.pure(SignIn.Rejected("Factor was not accepted."))
                else completed(ceremony, account, challenge.reference, Some(submitted))
              case _ => F.pure(SignIn.Rejected("Factor was not accepted."))
            }
          case Left(_) => F.pure(SignIn.Rejected("Sign-in is temporarily unavailable."))
        }
      case _ => F.pure(SignIn.Rejected("This challenge is unavailable or rate limited."))
    }
  }
  def replace(
    ceremony: BrowserReplacementCeremony,
    invocation: BrowserReplacementInvocation,
    password: String
  ): F[SessionReplacement] =
    proof[SessionReplacement](
      SessionReplacement.Rejected("Security changes are temporarily unavailable."),
      SessionReplacement.Unresolved(ceremony.id)
    ) {
      val captured  = invocation.authority
      val operation = SecurityChange.values.find(_.purpose == ceremony.purpose)
      val subject   = captured.host.flatMap(p => Try(UUID.fromString(p.subject)).toOption)
      if (
        operation.isEmpty || subject.isEmpty || captured.sessionId.isEmpty ||
        !rate.admit(ceremony.browserKey, subject.get.toString)
      )
        F.pure(SessionReplacement.Rejected("This security change is unavailable or rate limited."))
      else
        provider.inspectHost.flatMap {
          case Right(state) =>
            state.accounts.get(subject.get) match {
              case Some(expected)
                  if expected.enabled && captured.host.exists(p =>
                    p.securityVersion == expected.credentialVersion && p.policyVersion == expected.policyVersion
                  ) =>
                val challenge = BrowserChallenge(
                  expected.principal.subject.toString,
                  expected.credentialVersion,
                  expected.policyVersion,
                  DomainPolicy.challengeReference(clock(), ceremony.expiresAt)
                )
                provider.bindReplacementChallenge(ceremony, challenge).flatMap {
                  case Left(BrowserFailure.Used) => F.pure(SessionReplacement.Unresolved(ceremony.id))
                  case Left(_)                   => F.pure(SessionReplacement.Rejected("Begin a new security change."))
                  case Right(bound) =>
                    F.blocking(DomainPolicy.passwordHash(password))(proofContext).flatMap { submitted =>
                      if (!DomainPolicy.same(submitted, expected.password))
                        F.pure(SessionReplacement.Rejected("Password was not accepted."))
                      else
                        BrowserCredentials.generate[F].flatMap { credential =>
                          F.delay {
                            val sessionId    = UUID.randomUUID()
                            val completionId = UUID.randomUUID()
                            val generated = Option.when(operation.get != SecurityChange.DisableFactor)(
                              recoveryResults.create(completionId, sessionId, clock())
                            )
                            (sessionId, completionId, generated)
                          }.flatMap { case (sessionId, completionId, generated) =>
                            provider
                              .prepareReplacement(bound, invocation) { tx =>
                                val account     = current(tx, expected)
                                val time        = tx.now
                                val predecessor = tx.hostState.sessions.getOrElse(captured.sessionId.get, reject())
                                if (
                                  predecessor.subject != account.principal.subject ||
                                  predecessor.securityVersion != account.credentialVersion || predecessor.policyVersion != account.policyVersion ||
                                  !time.isBefore(predecessor.expires) ||
                                  !DomainPolicy.challengeDeadline(challenge.reference).exists(time.isBefore) ||
                                  tx.hostState.consumed.contains(challenge.reference)
                                ) reject()
                                val retained = tx.hostState.results.filter { case (_, record) =>
                                  time.isBefore(record.expiresAt) &&
                                    !tx.hostState.sessions
                                      .get(record.sessionId)
                                      .exists(_.subject == account.principal.subject)
                                }
                                if (
                                  account.credentialVersion == Long.MaxValue || account.policyVersion == Long.MaxValue ||
                                  tx.hostState.consumed.size >= DomainPolicy.retainedEntries ||
                                  tx.hostState.sessions.size >= DomainPolicy.retainedEntries ||
                                  tx.hostState.audits.size >= DomainPolicy.auditRecords ||
                                  (generated.nonEmpty && retained.size >= 256)
                                )
                                  throw new OperationProtocolException(OperationError.CapacityExceeded)
                                val factor = operation.get match {
                                  case SecurityChange.RegenerateRecovery => account.factor
                                  case SecurityChange.EnableFactor =>
                                    if (account.factor.nonEmpty) reject()
                                    Some(enrollmentFactor)
                                  case SecurityChange.ReplaceFactor =>
                                    if (account.factor.isEmpty) reject()
                                    Some(replacementFactor)
                                  case SecurityChange.DisableFactor =>
                                    if (account.factor.isEmpty) reject()
                                    None
                                }
                                if (generated.exists(g => !time.isBefore(g.record.expiresAt))) reject()
                                val changed = account.copy(
                                  factor = factor,
                                  recoveryHashes = generated.fold(Vector.empty[Vector[Byte]])(_.hashes),
                                  credentialVersion = account.credentialVersion + 1L,
                                  policyVersion = account.policyVersion + 1L
                                )
                                val expires = time.plusSeconds(DomainPolicy.sessionSeconds)
                                tx.modify(s =>
                                  s.copy(
                                    accounts = s.accounts.updated(subject.get, changed),
                                    sessions = (s.sessions - captured.sessionId.get).updated(
                                      sessionId,
                                      Session(
                                        subject.get,
                                        changed.credentialVersion,
                                        changed.policyVersion,
                                        expires,
                                        completionId
                                      )
                                    ),
                                    consumed = s.consumed + challenge.reference,
                                    audits = s.audits :+ Audit(subject.get, operation.get.purpose, time),
                                    results = generated.fold(retained)(g => retained.updated(completionId, g.record))
                                  )
                                )
                                new HostSessionPrepared(sessionId, completionId, expires, credential)
                              }
                              .map {
                                case Right(handoff) => SessionReplacement.Completed(handoff)
                                case Left(TransactionFailure.CommitUnknown | TransactionFailure.StorageFailure) =>
                                  SessionReplacement.Unresolved(ceremony.id)
                                case Left(
                                      TransactionFailure
                                        .Rejected(OperationError.PermitUsed | OperationError.EvidenceUsed)
                                    ) =>
                                  SessionReplacement.Unresolved(ceremony.id)
                                case Left(_) =>
                                  SessionReplacement.Rejected("Security change was not accepted. Begin a new attempt.")
                              }
                          }
                        }
                    }
                }
              case _ => F.pure(SessionReplacement.Rejected("The authenticated account changed. Sign in again."))
            }
          case Left(_) => F.pure(SessionReplacement.Rejected("Security changes are temporarily unavailable."))
        }
    }

  def consumeRecovery(
    view: BrowserView[Principal],
    invocation: BrowserReplacementInvocation
  ): F[Either[TransactionFailure, Option[RecoveryResults.Consumed]]] =
    if (!invocation.matchesView(view)) F.pure(Left(TransactionFailure.Rejected(OperationError.HostDenied)))
    else
      provider
        .withSuccessorCompletion(view) { (tx, completion) =>
          val time     = tx.now
          val retained = tx.hostState.results.filter { case (_, record) => time.isBefore(record.expiresAt) }
          val result   = retained.get(completion.hostCompletionId).filter(_.sessionId == completion.successorSessionId)
          tx.modify(s => s.copy(results = retained - completion.hostCompletionId))
          result
        }
        .map(_.map(_.map(record => RecoveryResults.consumed(record, invocation))))

  def presentRecovery(
    record: RecoveryResults.Consumed,
    context: AuthenticatedActionContext[Principal]
  ): F[UiOutcome[Page]] =
    F.delay(recoveryResults.present(record, context))
  def increment(view: BrowserView[Principal]): F[Either[TransactionFailure, Int]] = provider.transact(view) { tx =>
    val subject = view.principal.map(_.value.subject).getOrElse(reject())
    val state   = tx.hostState
    if (state.audits.size >= DomainPolicy.auditRecords)
      throw new OperationProtocolException(OperationError.CapacityExceeded)
    val previous = state.counters.getOrElse(subject, 0)
    if (previous == Int.MaxValue) throw new OperationProtocolException(OperationError.CapacityExceeded)
    val value = previous + 1
    tx.modify(current =>
      current.copy(
        counters = current.counters.updated(subject, value),
        audits = current.audits :+ Audit(subject, "increment", tx.now)
      )
    )
    value
  }
  def initialize(): F[Unit] = F.unit
  override def close(): F[Unit] = F.uncancelable {
    F.promise[Unit] { callback =>
      val finished = admission.synchronized {
        stopped = true
        if (pending == 0) true else { waiters = callback :: waiters; false }
      }
      if (finished) callback(Right(()))
    }.flatMap(_ => browser.close())
  }
}
