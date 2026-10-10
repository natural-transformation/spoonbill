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

final class MemoryReferenceBackend(origin: String)(using ExecutionContext) extends ReferenceBackend[Principal] {
  val host = new MemoryBrowserAuth[Future](
    Vector(syntheticAccount("alice", "password"), syntheticAccount("bob", "password", Some("123456"))),
    () => Instant.now()
  )
  val security = new MemoryBrowserSecurity[Future, Page](
    host,
    () => Page(),
    _.protectedPage,
    (page, principal) => ReferenceApplication.connected(page, principal.map(_.name)),
    origin = origin
  )
  def accessControl = security
  def storage       = security.storage
  def authority     = security.authority
  def completionConfig: AuthenticationCompletionConfig[Future] =
    host.completionConfig.copy(allowedOrigins = Set(origin))
  def displayName(principal: Principal): String = principal.name
  // The memory adapter captures/creates its bounded lineage atomically during
  // authorizeHttp; it requires no separate durable bootstrap operation.
  def bootstrapBinding(binding: String): Future[Either[Failure, Unit]] = Future.successful(Right(()))
  def begin(owner: ConnectionId): Future[Either[Failure, UUID]]        = security.begin(owner)
  def password(owner: ConnectionId, ceremony: UUID, username: String, value: String): Future[Either[Failure, Reply]] =
    security.password(owner, ceremony, username, value)
  def factor(owner: ConnectionId, ceremony: UUID, challenge: UUID, value: String): Future[Either[Failure, Reply]] =
    security.factor(owner, ceremony, challenge, value)
  def recover(owner: ConnectionId, ceremony: UUID): Future[Either[Failure, Recovery]] =
    security.recover(owner, ceremony)
  def protectedPage(request: Request.Head): Future[Either[Failure, Principal]] =
    (request.cookie("baseline_binding"), request.cookie("baseline_session")) match {
      case (Some(binding), Some(credential)) => host.protectedPage(binding, credential)
      case _                                 => Future.successful(Left(Failure.Denied))
    }
  def actionAuthority(owner: ConnectionId, principal: Principal): Future[Either[Failure, ExecutionAuthority]] =
    security.actionAuthority(owner, principal)
  def protectedAction(
    owner: ConnectionId,
    principal: Principal,
    authority: ExecutionAuthority
  ): Future[Either[TransactionFailure, Int]] =
    security.protectedAction(owner, principal, authority)
  def logoutBinding(binding: String): Future[Either[Failure, Unit]] = host.logout(binding)
  def retireExpiredMaterial(): Future[Unit]                         = host.retireExpiredMaterial().map(_ => ())
  def close(): Future[Unit]                                         = security.close().map(_ => host.close())
}

final class MemoryReferenceApplication(origin: String = "http://localhost:8080")(using ExecutionContext)
    extends ReferenceApplication[Principal](new MemoryReferenceBackend(origin), origin)
