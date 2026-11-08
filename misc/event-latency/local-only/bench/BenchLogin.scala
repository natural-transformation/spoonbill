package bench

import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.{Base64, UUID}
import scala.concurrent.{ExecutionContext, Future}
import spoonbill.Qsid
import spoonbill.auth.*
import spoonbill.security.Identifiers.ConnectionId
import spoonbill.security.jdbc.{JdbcBrowserPersistence, JdbcHostSessions}

/**
 * Sign-in without UI for the harness client. The server provisions a session
 * through the provider SPI exactly as an application's sign-in action would:
 * capture a fresh binding, claim a bootstrap view, begin the ceremony and
 * prepare the host session inside the provider's transaction. The reply
 * carries the binding cookie and the completion id; the client posts that id
 * to the framework's /auth/complete endpoint, which delivers the session
 * cookie through persistence.deliver, and the first WebSocket handshake
 * activates the session. No credential leaves the framework's own paths.
 */
object BenchLogin {

  /** A fixed expiry: the synthetic principal's stamp must be identical at every authority check. */
  val principalExpiry: Instant = Instant.parse("2100-01-01T00:00:00Z")

  /**
   * The synthetic host: every session id resolves to its own principal and
   * nothing is read from the database, so the host adds no SQL to a check. A
   * real host's session lookup comes on top of the numbers measured here.
   */
  final class Host extends JdbcHostSessions[String] {
    def resolve(connection: Connection, sessionId: UUID, now: Instant): Option[HostPrincipal[String]] =
      Some(HostPrincipal(sessionId.toString, sessionId.toString, 1L, 1L, principalExpiry))
    def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit = ()
  }

  /** The binding cookie value and the completion the client turns into a session cookie. */
  final case class Provisioned(binding: String, completionId: UUID)

  private val random = new SecureRandom()

  private def freshBinding(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  private def accepted[A](result: Either[BrowserFailure, A], step: String): Future[A] =
    result.fold(
      failure => Future.failed(new IllegalStateException(s"bench login: $step rejected with $failure")),
      Future.successful
    )

  def provision(persistence: JdbcBrowserPersistence[Future, String], sessionSeconds: Long = 3600)(using
    ExecutionContext
  ): Future[Provisioned] = {
    val binding = freshBinding()
    val qsid    = Qsid("bench-login", UUID.randomUUID().toString)
    for {
      identity <- persistence.capture(binding, None, activate = false).flatMap(accepted(_, "capture"))
      view <- persistence
                .claim(identity, qsid, ConnectionId.fromUuid(UUID.randomUUID()), existingOnly = false)
                .flatMap(accepted(_, "claim"))
      ceremony   <- persistence.begin(view, "sign-in").flatMap(accepted(_, "begin"))
      credential <- BrowserCredentials.generate[Future]
      receipt <- persistence
                   .prepare(ceremony) { tx =>
                     // The host session: a fresh id, its acknowledgment id and an expiry within the retention limit.
                     new HostSessionPrepared(UUID.randomUUID(), UUID.randomUUID(), tx.now.plusSeconds(sessionSeconds), credential)
                   }
                   .flatMap(
                     _.fold(
                       failure => Future.failed(new IllegalStateException(s"bench login: prepare failed with $failure")),
                       Future.successful
                     )
                   )
      // The bootstrap view served only the ceremony; the browser claims its own view at the handshake.
      _ <- persistence.release(view)
    } yield Provisioned(binding, receipt.completionId)
  }
}
