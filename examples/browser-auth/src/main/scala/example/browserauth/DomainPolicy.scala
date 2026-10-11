package example.browserauth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import scala.util.Try

/**
 * Public synthetic fixtures. Real applications supply their account store,
 * password parameters, factor verifier and deployment-specific rate policy.
 */
object DomainPolicy {
  final case class SeedAccount(id: UUID, name: String, factor: Option[String])
  val sessionSeconds: Long = 900
  val retainedEntries: Int = 32768
  val auditRecords: Int    = 65536
  val accounts: Vector[SeedAccount] = Vector.tabulate(1000) { index =>
    val name = if (index == 0) "alice" else if (index == 1) "bob" else f"account-${index + 1}%04d"
    SeedAccount(new UUID(0L, index.toLong + 1L), name, Option.when((index & 1) == 1)("123456"))
  }
  private val subjects = accounts.iterator.map(account => account.name -> account.id.toString).toMap
  def passwordHash(value: String): Vector[Byte] = {
    val chars = value.toCharArray
    val spec  = new PBEKeySpec(chars, "spoonbill-proof!".getBytes(UTF_8), 100000, 256)
    try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded.toVector
    finally { spec.clearPassword(); java.util.Arrays.fill(chars, '\u0000') }
  }
  def factorHash(value: String): Vector[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(UTF_8)).toVector
  def same(left: Vector[Byte], right: Vector[Byte]): Boolean = MessageDigest.isEqual(left.toArray, right.toArray)
  def challengeReference(now: Instant, ceremonyDeadline: Instant = Instant.MAX): String = {
    val deadline = now.plusSeconds(90)
    val expires  = if (deadline.isBefore(ceremonyDeadline)) deadline else ceremonyDeadline
    s"${UUID.randomUUID()}:${expires.getEpochSecond}"
  }
  def challengeDeadline(reference: String): Option[Instant] = reference.split(":", -1) match {
    case Array(id, seconds) => Try { UUID.fromString(id); Instant.ofEpochSecond(seconds.toLong) }.toOption
    case _                  => None
  }

  /**
   * Bounded browser and browser/subject windows. Known account names and their
   * UUIDs share the same subject bucket across password and factor submissions.
   */
  final class RateLimit(clock: () => Instant) {
    private var observed = Instant.MIN
    private var browsers = Map.empty[Vector[Byte], (Instant, Int)]
    private var tuples   = Map.empty[(Vector[Byte], String), (Instant, Int)]
    def admit(browser: Vector[Byte], username: String): Boolean = synchronized {
      val sample = clock()
      if (sample.isAfter(observed)) observed = sample
      val time = observed
      browsers = browsers.filter { case (_, (start, _)) => time.isBefore(start.plusSeconds(60)) }
      tuples = tuples.filter { case (_, (start, _)) => time.isBefore(start.plusSeconds(60)) }
      val subject                      = browser -> subjects.getOrElse(username, username)
      val (browserStart, browserCount) = browsers.getOrElse(browser, time -> 0)
      val (subjectStart, subjectCount) = tuples.getOrElse(subject, time -> 0)
      if (
        browserCount >= 5 || subjectCount >= 5 ||
        (!browsers.contains(browser) && browsers.size >= retainedEntries) ||
        (!tuples.contains(subject) && tuples.size >= retainedEntries)
      ) false
      else {
        browsers += browser -> (browserStart -> (browserCount + 1))
        tuples += subject   -> (subjectStart -> (subjectCount + 1))
        true
      }
    }
  }
}
