package spoonbill.browserauthbaseline

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Executable synthetic workload policy shared by both v3 reference providers.
 */
final case class ReferencePolicy(
  sessionSeconds: Long = 900,
  ceremonySeconds: Long = 120,
  challengeSeconds: Long = 90,
  deliverySeconds: Long = 60,
  operationAuthoritySeconds: Long = 30,
  reconnectSeconds: Long = 30,
  bootstrapSeconds: Long = 15,
  retainedEntries: Int = 32768,
  retainedViews: Int = 131072,
  auditRecords: Int = 65536,
  liveCeremonies: Int = 1024,
  ceremoniesPerBinding: Int = 4,
  activeViews: Int = 1024,
  viewsPerBinding: Int = 16,
  disconnectedViews: Int = 1024,
  bootstrapEntries: Int = 1024,
  pendingJobs: Int = 256,
  nodesPerView: Int = 10000,
  accountCount: Int = 1000,
  proofAttempts: Int = 5,
  deliveryMaterials: Int = 1024,
  rateScopes: Int = 32768,
  proofWindowSeconds: Long = 60,
  proof: ReferencePolicy.Proof = ReferencePolicy.Proof.Short
) {
  require(
    Vector(
      sessionSeconds,
      ceremonySeconds,
      challengeSeconds,
      deliverySeconds,
      operationAuthoritySeconds,
      reconnectSeconds,
      bootstrapSeconds,
      proofWindowSeconds
    ).forall(_ > 0)
  )
  require(
    Vector(
      retainedEntries,
      retainedViews,
      auditRecords,
      liveCeremonies,
      ceremoniesPerBinding,
      activeViews,
      viewsPerBinding,
      disconnectedViews,
      bootstrapEntries,
      pendingJobs,
      nodesPerView,
      accountCount,
      proofAttempts,
      deliveryMaterials,
      rateScopes
    ).forall(_ > 0)
  )
  require(accountCount >= 2 && retainedEntries >= liveCeremonies)
  require(auditRecords >= retainedEntries)

  lazy val accounts: Vector[ReferencePolicy.Account] = Vector.tabulate(accountCount) { index =>
    val name = if (index == 0) "alice" else if (index == 1) "bob" else f"account-${index + 1}%04d"
    ReferencePolicy.Account(name, new UUID(0L, index.toLong + 1L), if ((index & 1) == 1) Some("123456") else None)
  }
  def verifyPassword(value: String, expected: Array[Byte]): Boolean =
    MessageDigest.isEqual(proof.hash(value), expected)

  /**
   * Stable report of the actual constructor values, not a second configuration.
   */
  def json: String = {
    val fields = productElementNames
      .zip(productIterator)
      .map { case (name, value) =>
        val encoded = value match {
          case number: Int  => number.toString
          case number: Long => number.toString
          case mode: ReferencePolicy.Proof =>
            s"""{"algorithm":"${mode.algorithm}","iterations":${mode.iterations},"keyBits":${mode.keyBits},"saltBytes":${mode.salt.size}}"""
          case _ => throw new IllegalStateException("Unsupported reference policy field")
        }
        s"\"$name\":$encoded"
      }
      .mkString(",")
    s"{$fields}"
  }
}

object ReferencePolicy {
  val Default: ReferencePolicy = ReferencePolicy()
  final case class Account(name: String, subject: UUID, factor: Option[String])
  enum Proof(val iterations: Int) {
    case Short extends Proof(1)
    case Representative extends Proof(100000)
    val algorithm: String = "PBKDF2WithHmacSHA256"
    val keyBits: Int      = 256
    // Fixed, public, exactly sixteen bytes. Synthetic fixtures only.
    val salt: Vector[Byte] = "spoonbill-proof!".getBytes(UTF_8).toVector
    def hash(value: String): Array[Byte] = {
      val password = value.toCharArray
      val spec     = new PBEKeySpec(password, salt.toArray, iterations, keyBits)
      try SecretKeyFactory.getInstance(algorithm).generateSecret(spec).getEncoded
      finally { spec.clearPassword(); java.util.Arrays.fill(password, '\u0000') }
    }
  }
}
