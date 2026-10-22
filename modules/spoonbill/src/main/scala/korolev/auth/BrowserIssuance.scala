package spoonbill.auth

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.{Base64, UUID}
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import spoonbill.server.BrowserSessionToken

/** Compact permanent issuance state; callers serialize and persist the counter. */
private[spoonbill] object BrowserIssuance {
  val MaxSequence: Long = (1L << 62) - 1L

  def newPrefix(): Long = (UUID.randomUUID().getMostSignificantBits & ~0xf000L) | 0x4000L

  def newKey(): Array[Byte] = {
    val key = new Array[Byte](32)
    new SecureRandom().nextBytes(key)
    key
  }

  def identifier(prefix: Long, sequence: Long): UUID = {
    require(sequence > 0 && sequence <= MaxSequence, "Browser issuance sequence exhausted")
    new UUID((prefix & ~0xf000L) | 0x4000L, sequence | Long.MinValue)
  }

  /** Reused or previously exposed host material cannot recreate a retired cookie. */
  def credential(
    issuerKey: Array[Byte],
    raw: BrowserSessionToken,
    scope: BrowserProviderScope,
    binding: Vector[Byte],
    attempt: UUID
  ): BrowserSessionToken = {
    require(issuerKey.length == 32, "Browser issuance key must be 32 bytes")
    require(binding.size == 32, "Binding digest must be 32 bytes")
    val fields = Vector("spoonbill-browser-issuance-v2", scope.realm, scope.cookieNamespace).map(_.getBytes(UTF_8))
    val rawBytes = Base64.getUrlDecoder.decode(raw.value)
    val payload = ByteBuffer.allocate(fields.map(_.length + 4).sum + rawBytes.length + 32 + 16)
    fields.foreach { value => payload.putInt(value.length); payload.put(value) }
    payload.put(rawBytes)
    payload.put(binding.toArray)
    payload.putLong(attempt.getMostSignificantBits)
    payload.putLong(attempt.getLeastSignificantBits)
    val derived = try {
      val mac = Mac.getInstance("HmacSHA256")
      mac.init(new SecretKeySpec(issuerKey, "HmacSHA256"))
      mac.doFinal(payload.array())
    } finally {
      java.util.Arrays.fill(rawBytes, 0.toByte)
      java.util.Arrays.fill(payload.array(), 0.toByte)
    }
    try BrowserSessionToken.fromString(Base64.getUrlEncoder.withoutPadding().encodeToString(derived)).toOption.get
    finally java.util.Arrays.fill(derived, 0.toByte)
  }
}
