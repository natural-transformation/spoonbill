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
 * Optional host capability that lets a shared-view authority check resolve the
 * host once. The host locks and reads first; Spoonbill then evaluates every
 * time rule at one durable instant that it samples after the last lock.
 *
 * The JDBC provider calls lockResolution only for a BrowserOwnership.SharedViews
 * view that was captured as authenticated, inside the provider transaction,
 * after the browser token row and before the slot and view locks. It must
 * acquire the host locks the decision needs and capture every non-time input
 * under them, and it must not decide by time. It returns None for a stable
 * denial. The returned principal and cutoff must stay valid for evaluation at
 * the final instant, with every other input of the decision held stable until
 * the transaction settles. A host whose time rule a single exclusive cutoff
 * cannot express keeps the resolve contract instead.
 *
 * resolve and acknowledge keep their contract. Activation, replacement,
 * anonymous and pending flows, ExclusiveScope providers and explicit host
 * tx.now still use resolve and the provider's ordinary clock.
 */
trait JdbcHeldHostSessions[P] extends JdbcHostSessions[P] {
  def lockResolution(connection: Connection, sessionId: UUID): Option[JdbcHeldResolution[P]]
}

/**
 * One check's held host resolution. It belongs to that invocation and its
 * connection; the provider never keeps it after the check.
 */
trait JdbcHeldResolution[P] {

  /**
   * The principal. Keep the stable absolute expiry in its expiresAt: that field
   * is part of the captured authority identity, so it must not move with idle
   * refreshes.
   */
  def principal: HostPrincipal[P]

  /**
   * The decision holds for instants strictly before this cutoff, for example
   * the earliest of the idle expiry before any refresh, the absolute expiry and
   * an unacknowledged-completion deadline. Equality fails.
   */
  def validUntilExclusive: Instant

  /**
   * Runs once at the decision instant, after every authority and owner-lease
   * check passed, in the same transaction. It may stage rollback-safe writes on
   * the rows this resolution locked, such as a conditional idle refresh, and may
   * throw to fail the transaction. It must not settle the connection. The
   * provider owns settlement and never replays a check once this call starts.
   */
  def onAcceptedAt(now: Instant): Unit = ()
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
