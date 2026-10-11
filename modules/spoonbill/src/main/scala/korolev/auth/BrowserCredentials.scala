package spoonbill.auth

import java.security.SecureRandom
import java.util.Base64
import spoonbill.effect.Effect
import spoonbill.server.BrowserSessionToken

/**
 * Trusted transport helper. Generate outside a host transaction; never store
 * the token in presentation or ordinary action results. Applications with an
 * existing issuer may keep it when it meets the same entropy/encoding contract.
 */
object BrowserCredentials {
  def generate[F[_]](using F: Effect[F]): F[BrowserSessionToken] = F.delay {
    val bytes = new Array[Byte](32)
    new SecureRandom().nextBytes(bytes)
    try
      BrowserSessionToken
        .fromString(Base64.getUrlEncoder.withoutPadding().encodeToString(bytes))
        .fold(message => throw new IllegalStateException(message), identity)
    finally java.util.Arrays.fill(bytes, 0.toByte)
  }
}
