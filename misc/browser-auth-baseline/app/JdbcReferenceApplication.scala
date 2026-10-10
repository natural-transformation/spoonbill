package spoonbill.browserauthbaseline

import MemoryBrowserAuth.{Failure, Recovery, Reply}
import ReferenceApplication.Page
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.jdbc.JdbcAuthError
import spoonbill.security.jdbc.baseline.{JdbcBrowserSecurity, JdbcReferenceHost}
import spoonbill.security.transaction.{ExecutionAuthority, OperationError, TransactionFailure}
import spoonbill.server.SessionAccessDenied
import spoonbill.web.Request

object JdbcReferenceBackend {
  val alice: UUID                 = new UUID(0L, 1L)
  val bob: UUID                   = new UUID(0L, 2L)
  val accounts: Map[String, UUID] = Map("alice" -> alice, "bob" -> bob)

  /**
   * Only immutable display data survives reconnect. Account, authorization and
   * protected routing are supplied by the fresh server-authorized page.
   */
  final case class Projection(
    ceremony: Option[UUID],
    challenge: Option[UUID],
    recoveryPending: Boolean,
    counter: Int,
    mutationUnresolved: Boolean,
    message: String
  )
  def project(page: Page): Projection =
    Projection(page.ceremony, page.challenge, page.recoveryPending, page.counter, page.mutationUnresolved, page.message)
  def restore(page: Page, value: Projection): Page = ReferenceApplication.connected(
    page.copy(
      ceremony = value.ceremony,
      challenge = value.challenge,
      recoveryPending = value.recoveryPending,
      counter = value.counter,
      mutationUnresolved = value.mutationUnresolved,
      message = value.message
    ),
    page.account
  )
}

/**
 * Counted consumer glue sharing the exact Scala UI and official transport with
 * the memory reference. None of these adapters is a proposed provider API.
 */
final class JdbcReferenceBackend(
  source: DataSource,
  blockingContext: ExecutionContext,
  origin: String,
  clock: () => Instant,
  entropy: Int => Array[Byte],
  newId: () => UUID,
  material: JdbcReferenceHost.MaterialCipher,
  afterPreparedCommit: UUID => Unit = _ => (),
  val policy: ReferencePolicy = ReferencePolicy.Default
)(using ExecutionContext)
    extends ReferenceBackend[JdbcBrowserSecurity.Principal] {
  import JdbcReferenceBackend.*
  private val accountIds = policy.accounts.map(account => account.name -> account.subject).toMap
  val security = new JdbcBrowserSecurity[Page, Projection](
    source,
    blockingContext,
    "baseline",
    "session",
    clock,
    entropy,
    newId,
    material,
    () => Page(),
    _.protectedPage,
    (page, principal) => ReferenceApplication.connected(page, principal.map(displayName)),
    project,
    restore,
    singleNodeExclusiveWriters = true,
    origin = origin,
    afterPreparedCommit = afterPreparedCommit,
    limits = JdbcBrowserSecurity.limitsFor(policy),
    referencePolicy = Some(policy)
  )

  def accessControl    = security
  def storage          = security.storage
  def authority        = security.authority
  def completionConfig = security.completionConfig
  private def subjectName(subject: UUID): String =
    accountIds.collectFirst { case (name, known) if known == subject => name }
      .getOrElse(throw new SessionAccessDenied)
  def displayName(principal: JdbcBrowserSecurity.Principal): String = subjectName(principal.subject)
  override def initialPage(request: Request.Head): Future[Page] = security.bootstrapRecovery(request).map {
    case Left(_)     => throw new SessionAccessDenied
    case Right(None) => Page()
    case Right(Some(metadata)) =>
      Page(
        ceremony = Some(metadata.ceremony),
        challenge = if (metadata.preparationPending) None else metadata.challenge,
        recoveryPending = metadata.preparationPending,
        message =
          if (metadata.preparationPending)
            s"Recover the interrupted sign-in for ${subjectName(metadata.subject)} without resubmitting credentials."
          else s"Continue the original factor challenge for ${subjectName(metadata.subject)}."
      )
  }
  private def operation(error: OperationError): Failure = error match {
    case OperationError.Expired                                              => Failure.Expired
    case OperationError.CapacityExceeded | OperationError.CapacityOrConflict => Failure.Capacity
    case OperationError.PermitUsed | OperationError.EvidenceUsed             => Failure.Used
    case _                                                                   => Failure.Denied
  }
  private def settlement(error: TransactionFailure): Failure = error match {
    case TransactionFailure.Rejected(reason) => operation(reason)
    case other                               => Failure.Settlement(other)
  }
  private def browser(error: JdbcAuthError): Failure = error match {
    case JdbcAuthError.Expired        => Failure.Expired
    case JdbcAuthError.NotFound       => Failure.Missing
    case JdbcAuthError.StorageFailure => Failure.Settlement(TransactionFailure.StorageFailure)
    case _                            => Failure.Denied
  }
  private def reply(value: security.Reply): Reply = value match {
    case security.Reply.Prepared(id)                   => Reply.Prepared(id)
    case security.Reply.Challenge(ceremony, challenge) => Reply.Challenge(ceremony, challenge)
  }
  def bootstrapBinding(binding: String): Future[Either[Failure, Unit]] =
    security.bootstrapBinding(binding).map(_.left.map(settlement).map(_ => ()))
  def begin(owner: ConnectionId): Future[Either[Failure, UUID]] = security.begin(owner).map(_.left.map(settlement))
  def password(owner: ConnectionId, ceremony: UUID, username: String, value: String): Future[Either[Failure, Reply]] =
    accountIds.get(username) match {
      case None          => Future.successful(Left(Failure.Denied))
      case Some(subject) => security.password(owner, ceremony, subject, value).map(_.left.map(settlement).map(reply))
    }
  def factor(owner: ConnectionId, ceremony: UUID, challenge: UUID, value: String): Future[Either[Failure, Reply]] =
    security.factorForCeremony(owner, ceremony, challenge, value).map(_.left.map(settlement).map(reply))
  def recover(owner: ConnectionId, ceremony: UUID): Future[Either[Failure, Recovery]] =
    security
      .recover(owner, ceremony)
      .map(_.left.map(settlement).map {
        case JdbcReferenceHost.Recovery.Committed(id) => Recovery.Committed(id)
        case JdbcReferenceHost.Recovery.NotCommitted  => Recovery.NotCommitted
      })
  def protectedPage(request: Request.Head): Future[Either[Failure, JdbcBrowserSecurity.Principal]] =
    security.protectedPrincipal(request).map(_.left.map(settlement))
  def actionAuthority(
    owner: ConnectionId,
    principal: JdbcBrowserSecurity.Principal
  ): Future[Either[Failure, ExecutionAuthority]] =
    security.actionAuthority(owner, principal).map(_.left.map(operation))
  def protectedAction(
    owner: ConnectionId,
    principal: JdbcBrowserSecurity.Principal,
    authority: ExecutionAuthority
  ): Future[Either[TransactionFailure, Int]] = security.protectedAction(owner, principal, authority)
  def logoutBinding(binding: String): Future[Either[Failure, Unit]] =
    security.logoutBinding(binding).map(_.left.map(browser).map(_ => ()))
  def retireExpiredMaterial(): Future[Unit] = security.retireExpiredMaterial().map {
    case Right(_) => ()
    case Left(_)  => throw new SessionAccessDenied
  }
  def close(): Future[Unit] = security.close()

  /**
   * Explicit startup migration/seed, never invoked by an HTTP/WS callback. The
   * enclosing owner commits this deployment transaction.
   */
  def initialize(connection: Connection): Unit = {
    security.initialize(connection)
    val passwordDigest = policy.proof.hash("password")
    policy.accounts.foreach { account =>
      Using.resource(connection.prepareStatement("INSERT INTO baseline_account VALUES (?,1,TRUE,?,?)")) { query =>
        query.setObject(1, account.subject); query.setBytes(2, passwordDigest)
        account.factor match {
          case Some(value) => query.setBytes(3, JdbcReferenceHost.syntheticHash(value))
          case None        => query.setNull(3, java.sql.Types.BINARY)
        }
        query.executeUpdate()
      }
    }
  }
}

final class JdbcReferenceApplication(val jdbc: JdbcReferenceBackend, origin: String)(using ExecutionContext)
    extends ReferenceApplication[JdbcBrowserSecurity.Principal](jdbc, origin)
