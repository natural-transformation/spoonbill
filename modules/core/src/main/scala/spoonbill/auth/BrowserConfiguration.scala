package spoonbill.auth

import java.net.URI

final case class BrowserProviderScope(realm: String, cookieNamespace: String)

/** Authority topology; choosing a mode does not elect or fence a provider. */
enum BrowserOwnership {
  case ExclusiveScope, SharedViews
}

/**
 * A provider must explicitly establish the shared atomic domain and ownership
 * policy. Advertising a capability is a trusted SPI contract, not a substitute
 * for conformance tests against its actual storage engine. ExclusiveScope
 * requires one provider for the whole scope. SharedViews instead
 * requires authoritative per-view takeover, output admission and stale-writer
 * fencing across every overlapping provider in that scope.
 */
final case class BrowserProviderCapabilities(
  atomicHostPreparation: Boolean,
  durable: Boolean,
  ownership: BrowserOwnership
)

/**
 * Immutable configuration; validation reads no clock, entropy or environment.
 */
final case class BrowserConfiguration(
  scope: BrowserProviderScope,
  allowedOrigins: Set[String],
  bindingCookieName: String = "spoonbill_binding",
  sessionCookieName: String = "spoonbill_session",
  secureCookies: Boolean = true,
  cookieMaxAgeSeconds: Long = 3600,
  maxPendingOperations: Int = 256,
  logoutRedirect: String = "/",
  capacityWarningPercent: Int = 80
) {
  def errors: Vector[String] = {
    val scopeValid = Vector(scope.realm, scope.cookieNamespace).forall(_.matches("[A-Za-z0-9_.-]{1,96}"))
    val names      = Vector(bindingCookieName, sessionCookieName)
    val cookiesValid = names.distinct.size == 2 && names.forall(_.matches("[A-Za-z0-9_-]{1,80}")) &&
      !names.contains("deviceId") && (secureCookies || !names.exists(_.startsWith("__Host-")))
    val originsValid = allowedOrigins.nonEmpty && allowedOrigins.forall { value =>
      scala.util.Try(new URI(value)).toOption.exists { uri =>
        val loopback = Set("localhost", "127.0.0.1", "[::1]").contains(uri.getHost)
        val scheme   = uri.getScheme == "https" || (!secureCookies && loopback && uri.getScheme == "http")
        scheme && uri.getHost != null && uri.getRawUserInfo == null &&
        Option(uri.getRawPath).forall(_.isEmpty) && uri.getRawQuery == null && uri.getRawFragment == null &&
        (uri.getPort == -1 || (uri.getPort > 0 && uri.getPort <= 65535)) && (secureCookies || loopback)
      }
    }
    val redirectValid = logoutRedirect.startsWith("/") && !logoutRedirect.startsWith("//") &&
      !logoutRedirect.exists(c => c == '\\' || c <= ' ') && scala.util.Try(new URI(logoutRedirect)).toOption.exists {
        uri =>
          uri.getScheme == null && uri.getRawAuthority == null
      }
    Vector(
      Option.when(!scopeValid)("Invalid browser provider scope"),
      Option.when(!cookiesValid)("Invalid browser cookie names or security settings"),
      Option.when(!originsValid)("Exact HTTPS origins are required; insecure mode is loopback only"),
      Option.when(cookieMaxAgeSeconds <= 0 || cookieMaxAgeSeconds > 31536000L)("Invalid browser cookie lifetime"),
      Option.when(maxPendingOperations <= 0)("Pending operation capacity must be positive"),
      Option.when(capacityWarningPercent <= 0 || capacityWarningPercent >= 100)("Capacity warning percentage must be between 1 and 99"),
      Option.when(!redirectValid)("Logout redirect must be an origin-relative path")
    ).flatten
  }
}
