package spoonbill.security.jdbc

import java.time.Instant

/**
 * Pure rules of the held shared-view check, which decides every time rule at
 * one durable instant. The checks keep the order in which the two-sample check
 * of JdbcBrowserSessions.authorizeIn reports competing failures when all of its
 * time samples are equal: binding and revocation of the token row need no time;
 * then session expiry, then the host, then the first structural failure found
 * under the slot and view locks.
 */
private[jdbc] object JdbcHeldAuthority {

  /** The exclusive deadlines of one held host resolution. */
  final case class HostDeadlines(principalExpiresAt: Instant, validUntilExclusive: Instant) {
    def admit(at: Instant): Boolean = at.isBefore(principalExpiresAt) && at.isBefore(validUntilExclusive)
  }

  /** Rejections made before any time is sampled; they need no clock checkpoint. */
  def untimed(bindingMatches: Boolean, revoked: Boolean): Option[JdbcAuthError] =
    if (!bindingMatches) Some(JdbcAuthError.BindingMismatch)
    else if (revoked) Some(JdbcAuthError.Revoked)
    else None

  /**
   * A stable host denial at the instant `at`. Only session expiry outranks it,
   * so it is decided without the framework's slot and view locks.
   */
  def denied(sessionExpiresAt: Instant, at: Instant): JdbcAuthError =
    if (!at.isBefore(sessionExpiresAt)) JdbcAuthError.Expired else JdbcAuthError.HostDenied

  /**
   * The decision at the instant `at` for a host that resolved. Every deadline
   * is exclusive, so equality fails.
   */
  def decide[A](
    sessionExpiresAt: Instant,
    host: HostDeadlines,
    structural: Either[JdbcAuthError, A],
    at: Instant
  ): Either[JdbcAuthError, A] =
    if (!at.isBefore(sessionExpiresAt)) Left(JdbcAuthError.Expired)
    else if (!host.admit(at)) Left(JdbcAuthError.HostDenied)
    else structural
}
