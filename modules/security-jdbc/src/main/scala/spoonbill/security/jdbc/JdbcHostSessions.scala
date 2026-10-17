package spoonbill.security.jdbc

import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import spoonbill.auth.HostPrincipal

/**
 * Host-owned account/session policy. Both callbacks join the supplied JDBC
 * transaction, lock host rows before framework slots/views, and never settle
 * the connection. resolve must revalidate expiry and revocation each time.
 * Subject/session identity is immutable; policy changes advance its versions.
 * Authenticated replacement requires resolve to serialize with independent host
 * revocation and account-version changes (for example, by locking the relevant
 * host rows until transaction settlement). A plain stale snapshot lookup is
 * insufficient. The host preparation callback verifies its proof against
 * pre-transition credential versions, then stages the successor and predecessor
 * revocation on this same connection; it must not settle or retry.
 */
trait JdbcHostSessions[P] {
  def resolve(connection: Connection, sessionId: UUID, now: Instant): Option[HostPrincipal[P]]
  def acknowledge(connection: Connection, completionId: UUID, sessionId: UUID, now: Instant): Unit
}

/**
 * Deployment-owned encryption and key rotation. Implementations authenticate
 * associatedData and must never log plaintext. Keys must survive process
 * restart for the intended delivery retention period.
 */
trait JdbcCredentialVault {
  def seal(plaintext: Array[Byte], associatedData: Array[Byte]): Array[Byte]
  def open(ciphertext: Array[Byte], associatedData: Array[Byte]): Array[Byte]
}

object JdbcCredentialVault {

  /**
   * Explicit single-key AES-GCM adapter. There is deliberately no default key.
   * A rotating/key-ID envelope can be supplied through JdbcCredentialVault.
   */
  def aesGcm(key: Array[Byte], random: SecureRandom = new SecureRandom()): JdbcCredentialVault = {
    require(Set(16, 24, 32).contains(key.length), "AES key must contain 16, 24, or 32 bytes")
    val secret = new SecretKeySpec(key.clone(), "AES")
    new JdbcCredentialVault {
      def seal(plaintext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        val nonce = new Array[Byte](12)
        random.nextBytes(nonce)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret, new GCMParameterSpec(128, nonce))
        cipher.updateAAD(associatedData)
        nonce ++ cipher.doFinal(plaintext)
      }
      def open(ciphertext: Array[Byte], associatedData: Array[Byte]): Array[Byte] = {
        require(ciphertext.length >= 28, "Invalid credential envelope")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret, new GCMParameterSpec(128, ciphertext.take(12)))
        cipher.updateAAD(associatedData)
        cipher.doFinal(ciphertext.drop(12))
      }
    }
  }
}
