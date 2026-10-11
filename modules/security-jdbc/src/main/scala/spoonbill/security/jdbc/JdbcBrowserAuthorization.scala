package spoonbill.security.jdbc

import java.sql.Connection
import java.time.Instant
import java.util.UUID

/**
 * Trusted, synchronous host authorization on the supplied transaction. Resolve
 * the current account/session policy and acquire all required host locks here,
 * before framework slot/view locks. The second invocation must reacquire only
 * those same locks and use the fresh supplied time. Never open another
 * connection, commit, roll back, close, or perform external IO.
 *
 * P must be an immutable value whose equality includes every security-relevant
 * identity/version field. Returning None denies authorization. A changed
 * principal between the two observations also denies it; an existing browser
 * session must never silently adopt a different host identity.
 */
trait HostSessionAuthorization[P] {
  def authorize(connection: Connection, hostSessionId: UUID, now: Instant): Option[P]
}

/**
 * Server-owned view identity. Neither this fence nor a view ID authenticates a
 * browser: the binding, current session and host principal are checked with it.
 */
final case class JdbcViewFence(viewId: String, ownerId: UUID, epoch: Long)

/**
 * Coherent observation, not a transferable permit for later domain writes.
 * Reauthorize inside the transaction which applies a protected mutation.
 */
sealed trait JdbcBrowserAuthorization[+P] {
  def generation: Long
}

object JdbcBrowserAuthorization {
  final case class Anonymous(generation: Long) extends JdbcBrowserAuthorization[Nothing]
  final case class Authenticated[P](session: ActiveBrowserSession, principal: P) extends JdbcBrowserAuthorization[P] {
    def generation: Long = session.generation
  }
}
