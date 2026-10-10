package spoonbill.browserauthbaseline

import MemoryBrowserAuth.{Failure, Recovery, Reply}
import ReferenceApplication.Page
import java.util.UUID
import scala.concurrent.Future
import spoonbill.action.SessionAuthority
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.transaction.{ExecutionAuthority, TransactionFailure}
import spoonbill.server.{AuthenticationCompletionConfig, SessionAccessControl}
import spoonbill.state.StateStorage
import spoonbill.web.Request

/**
 * Counted application glue for sharing the exact reference UI/workload between
 * v3 integrations. This is not a library provider SPI or a proposed v4 API.
 */
trait ReferenceBackend[P] {
  def accessControl: SessionAccessControl[Future, Page]
  def storage: StateStorage[Future, Page]
  def authority: SessionAuthority[Future, P]
  def completionConfig: AuthenticationCompletionConfig[Future]
  def displayName(principal: P): String
  def initialPage(request: Request.Head): Future[Page] = Future.successful(Page())
  def bootstrapBinding(binding: String): Future[Either[Failure, Unit]]
  def begin(owner: ConnectionId): Future[Either[Failure, UUID]]
  def password(owner: ConnectionId, ceremony: UUID, username: String, value: String): Future[Either[Failure, Reply]]
  def factor(owner: ConnectionId, ceremony: UUID, challenge: UUID, value: String): Future[Either[Failure, Reply]]
  def recover(owner: ConnectionId, ceremony: UUID): Future[Either[Failure, Recovery]]
  def protectedPage(request: Request.Head): Future[Either[Failure, P]]
  def actionAuthority(owner: ConnectionId, principal: P): Future[Either[Failure, ExecutionAuthority]]
  def protectedAction(
    owner: ConnectionId,
    principal: P,
    authority: ExecutionAuthority
  ): Future[Either[TransactionFailure, Int]]
  def logoutBinding(binding: String): Future[Either[Failure, Unit]]
  def retireExpiredMaterial(): Future[Unit]
  def close(): Future[Unit]
}
