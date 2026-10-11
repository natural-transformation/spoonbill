package example.browserauth

import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.ExecutionContext
import scala.util.Try
import scala.util.control.NonFatal
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.transaction.{OperationError, OperationProtocolException, TransactionFailure}

/**
 * Complete synthetic host policy for the non-durable provider. Only immutable
 * host state enters the transaction. Proof computation and credential
 * generation precede transaction admission; expensive password work uses the
 * supplied worker.
 */
final class MemoryAccounts[F[_]](
  origin: String = "http://localhost:8080",
  clock: () => Instant = () => Instant.now(),
  proofContext: ExecutionContext = ExecutionContext.global
)(using F: Effect[F], ec: ExecutionContext)
    extends Accounts[F] {
  private case class Account(
    principal: Principal,
    password: Vector[Byte],
    factor: Option[Vector[Byte]],
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
    counters: Map[UUID, Int] = Map.empty
  )
  private val passwordDigest = DomainPolicy.passwordHash("password")
  private val initial = HostState(DomainPolicy.accounts.iterator.map { seed =>
    seed.id -> Account(Principal(seed.id, seed.name), passwordDigest, seed.factor.map(DomainPolicy.factorHash))
  }.toMap)
  private val byName = DomainPolicy.accounts.iterator.map(account => account.name -> account.id).toMap
  private val rate   = new DomainPolicy.RateLimit(clock)
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
    },
    clock,
    () => UUID.randomUUID(),
    scope = BrowserProviderScope("example", "session")
  )
  val browser = new BrowserAuthentication[F, Page, Principal](
    provider,
    Presentation.policy,
    BrowserConfiguration(provider.scope, Set(origin), secureCookies = origin.startsWith("https://")),
    clock = clock
  )

  private val admission = new Object
  private var pending   = 0
  private var stopped   = false
  private var waiters   = List.empty[Effect.Promise[Unit]]
  private def proof(ceremony: BrowserCeremony)(body: => F[SignIn]): F[SignIn] = F.uncancelable {
    F.delayAsync {
      val admitted = admission.synchronized {
        if (stopped || pending >= 256) false else { pending += 1; true }
      }
      if (!admitted) F.pure(SignIn.Rejected("Sign-in is temporarily unavailable."))
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
            case NonFatal(_) => SignIn.Unresolved(ceremony.id)
          }
          .flatMap(value => F.delay { release(); value })
          .recoverF { case NonFatal(error) =>
            F.delay(release()).flatMap(_ => F.fail(error))
          }
      }
    }
  }
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

  def signIn(ceremony: BrowserCeremony, username: String, password: String): F[SignIn] = proof(ceremony) {
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
  def factor(ceremony: BrowserCeremony, value: String): F[SignIn] = proof(ceremony) {
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
