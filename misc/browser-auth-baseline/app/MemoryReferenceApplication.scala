package spoonbill.browserauthbaseline

import MemoryBrowserAuth.*
import ReferenceApplication.Page
import java.time.Instant
import java.util.UUID
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.{ExecutionAuthority, TransactionFailure}
import spoonbill.server.AuthenticationCompletionConfig
import spoonbill.web.Request

final class MemoryReferenceBackend(
  origin: String,
  proofExecutionContext: ExecutionContext,
  val policy: ReferencePolicy = ReferencePolicy.Default
)(using ExecutionContext)
    extends ReferenceBackend[Principal] {
  private val callbacks          = new ReferenceAdmission(policy.pendingJobs)
  def callbackCounts: (Int, Int) = callbacks.counts
  private def submitted[A](operation: => Future[A]): Future[A] =
    callbacks.submit(Future(operation)(proofExecutionContext).flatten)
  private val passwordDigest = policy.proof.hash("password").toVector
  private val accountNames   = policy.accounts.iterator.map(_.name).toSet
  val host = new MemoryBrowserAuth[Future](
    policy.accounts.map(account => Account(account.name, passwordDigest, account.factor, version = 1L)),
    () => Instant.now(),
    referencePolicy = Some(policy)
  )
  val security = new MemoryBrowserSecurity[Future, Page](
    host,
    () => Page(),
    _.protectedPage,
    (page, principal) => ReferenceApplication.connected(page, principal.map(_.name)),
    origin = origin,
    limits = MemoryBrowserSecurity.limitsFor(policy)
  )
  def accessControl = security
  def storage       = security.storage
  def authority     = security.authority
  def completionConfig: AuthenticationCompletionConfig[Future] =
    host.completionConfig.copy(
      allowedOrigins = Set(origin),
      deliver = (attempt, binding) => submitted(host.deliver(attempt, binding)),
      logout = (binding, _) =>
        submitted(host.logout(binding)).map(_.fold(_ => throw new spoonbill.server.SessionAccessDenied, identity))
    )
  def displayName(principal: Principal): String = principal.name
  // The memory adapter captures/creates its bounded lineage atomically during
  // authorizeHttp; it requires no separate durable bootstrap operation.
  def bootstrapBinding(binding: String): Future[Either[Failure, Unit]] = Future.successful(Right(()))
  def begin(owner: ConnectionId): Future[Either[Failure, UUID]]        = submitted(security.begin(owner))
  def password(owner: ConnectionId, ceremony: UUID, username: String, value: String): Future[Either[Failure, Reply]] =
    if (!accountNames.contains(username)) Future.successful(Left(Failure.Denied))
    else submitted(security.password(owner, ceremony, username, value))
  def factor(owner: ConnectionId, ceremony: UUID, challenge: UUID, value: String): Future[Either[Failure, Reply]] =
    submitted(security.factor(owner, ceremony, challenge, value))
  def recover(owner: ConnectionId, ceremony: UUID): Future[Either[Failure, Recovery]] =
    submitted(security.recover(owner, ceremony))
  def protectedPage(request: Request.Head): Future[Either[Failure, Principal]] =
    submitted((request.cookie("baseline_binding"), request.cookie("baseline_session")) match {
      case (Some(binding), Some(credential)) => host.protectedPage(binding, credential)
      case _                                 => Future.successful(Left(Failure.Denied))
    })
  def actionAuthority(owner: ConnectionId, principal: Principal): Future[Either[Failure, ExecutionAuthority]] =
    submitted(security.actionAuthority(owner, principal))
  def protectedAction(
    owner: ConnectionId,
    principal: Principal,
    authority: ExecutionAuthority
  ): Future[Either[TransactionFailure, Int]] =
    submitted(security.protectedAction(owner, principal, authority))
  def logoutBinding(binding: String): Future[Either[Failure, Unit]] = submitted(host.logout(binding))
  def retireExpiredMaterial(): Future[Unit] = callbacks.submitMaintenance(
    Future(host.retireExpiredMaterial())(proofExecutionContext).flatten.map(_ => ())(ExecutionContext.parasitic)
  )
  def close(): Future[Unit] = callbacks
    .close()
    .flatMap(_ => security.close())(ExecutionContext.parasitic)
    .map(_ => host.close())(ExecutionContext.parasitic)
}

final class MemoryReferenceApplication(
  proofExecutionContext: ExecutionContext,
  origin: String = "http://localhost:8080",
  policy: ReferencePolicy = ReferencePolicy.Default
)(using ExecutionContext)
    extends ReferenceApplication[Principal](new MemoryReferenceBackend(origin, proofExecutionContext, policy), origin)
