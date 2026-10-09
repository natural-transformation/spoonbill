package spoonbill.server

import java.net.URI
import java.util.UUID

/** A transport-only opaque credential. Deliberately not a Product or serializable UI value. */
final class BrowserSessionToken private (private[spoonbill] val value: String) {
  override def toString: String = "BrowserSessionToken([redacted])"
}

object BrowserSessionToken {
  def fromString(value: String): Either[String, BrowserSessionToken] =
    if (value.matches("[A-Za-z0-9_-]{43}")) Right(new BrowserSessionToken(value))
    else Left("Invalid browser credential encoding")
}

/**
 * Cookie delivery is deliberately separate from session activation. The host
 * must validate the completion against the HttpOnly browser binding, and the
 * next physical WebSocket handshake must activate it transactionally.
 *
 * Callbacks receive sensitive transport values: never store them in UI state,
 * include them in logs, or return them in action outcomes. Insecure cookies are
 * restricted to explicitly configured loopback origins for local testing.
 */
final case class AuthenticationCompletionConfig[F[_]](
  bindingCookieName: String,
  sessionCookieName: String,
  allowedOrigins: Set[String],
  secureCookies: Boolean,
  cookieMaxAgeSeconds: Long,
  deliver: (UUID, String) => F[Option[BrowserSessionToken]],
  logout: (String, Option[String]) => F[Unit]
) {
  private def validName(value: String): Boolean = value.matches("[A-Za-z0-9_-]{1,80}")
  require(validName(bindingCookieName) && validName(sessionCookieName), "Invalid authentication cookie name")
  require(bindingCookieName != sessionCookieName, "Authentication cookies must have distinct names")
  require(!Set(bindingCookieName, sessionCookieName).contains("deviceId"), "Authentication cookies must not reuse the device cookie")
  require(cookieMaxAgeSeconds > 0 && cookieMaxAgeSeconds <= 31536000L, "Invalid authentication cookie lifetime")
  require(allowedOrigins.nonEmpty, "Authentication requires explicit origins")
  require(allowedOrigins.forall { value =>
    scala.util.Try(new URI(value)).toOption.exists { uri =>
      val schemeAllowed = uri.getScheme == "https" ||
        (!secureCookies && uri.getScheme == "http" && Set("localhost", "127.0.0.1", "[::1]").contains(uri.getHost))
      schemeAllowed && uri.getHost != null && uri.getRawUserInfo == null &&
      Option(uri.getRawPath).forall(_.isEmpty) && uri.getRawQuery == null && uri.getRawFragment == null &&
      (uri.getPort == -1 || (uri.getPort > 0 && uri.getPort <= 65535)) &&
      (secureCookies || Set("localhost", "127.0.0.1", "[::1]").contains(uri.getHost))
    }
  }, "Origins must be exact HTTPS origins; insecure mode is loopback only")
  require(secureCookies || !Set(bindingCookieName, sessionCookieName).exists(_.startsWith("__Host-")),
    "__Host- cookies require Secure")
}
