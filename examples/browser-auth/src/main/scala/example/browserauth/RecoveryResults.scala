package example.browserauth

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.time.Instant
import java.util.{Base64, UUID}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import spoonbill.action.{AuthenticatedActionContext, UiOutcome}
import spoonbill.auth.BrowserReplacementInvocation
import spoonbill.security.jdbc.JdbcCredentialVault
import spoonbill.sensitive.{DisclosureOutcome, Purpose, RegionId, SensitivePayload}

/**
 * Host-owned synthetic recovery-code storage. Only encrypted material and
 * hashes survive the creating transaction. Real deployments own their keys,
 * code verifier and recovery policy; the memory example loses its key on
 * restart.
 */
object RecoveryResults {
  val region: RegionId = RegionId.parse("recovery-codes").toOption.get
  val purpose: Purpose = Purpose.parse("recovery-codes").toOption.get
  val lifetimeSeconds  = 120L
  final case class Record(completionId: UUID, sessionId: UUID, expiresAt: Instant, ciphertext: Vector[Byte]) {
    override def toString: String = "RecoveryResult(<redacted>)"
  }
  final case class Generated(record: Record, hashes: Vector[Vector[Byte]]) {
    override def toString: String = "GeneratedRecoveryResult(<redacted>)"
  }

  /**
   * Returned only after known consumption commit. A cached Future retains this
   * holder, which empties before decryption, rather than a reusable result.
   */
  final class Consumed private[RecoveryResults] (record: Record, private val invocation: BrowserReplacementInvocation) {
    val completionId: UUID = record.completionId
    val sessionId: UUID    = record.sessionId
    private val pending    = new AtomicReference(Option(record))
    private[browserauth] def claim(owner: BrowserReplacementInvocation, now: Instant): Option[Record] =
      if (!invocation.sameInvocation(owner)) None
      else pending.getAndSet(None).filter(value => now.isBefore(value.expiresAt))
    private[RecoveryResults] def take(context: AuthenticatedActionContext[Principal], now: Instant): Option[Record] =
      if (!invocation.belongsTo(context)) None else claim(invocation, now)
    override def toString: String = "ConsumedRecoveryResult(<redacted>)"
  }
  def consumed(record: Record, invocation: BrowserReplacementInvocation): Consumed = {
    require(
      invocation.authority.sessionId.contains(record.sessionId),
      "Recovery result belongs to a different successor"
    )
    new Consumed(record, invocation)
  }
  def ephemeral(clock: () => Instant = () => Instant.now()): RecoveryResults = {
    val key = new Array[Byte](32)
    new SecureRandom().nextBytes(key)
    try new RecoveryResults(JdbcCredentialVault.aesGcm(key), clock)
    finally java.util.Arrays.fill(key, 0.toByte)
  }
}

final class RecoveryResults(vault: JdbcCredentialVault, clock: () => Instant = () => Instant.now()) {
  import RecoveryResults.*
  private val random   = new SecureRandom()
  private val observed = new AtomicReference(Instant.MIN)
  private def now(): Instant = {
    val sampled = clock()
    observed.updateAndGet(previous => if (sampled.isAfter(previous)) sampled else previous)
  }
  private def associated(completionId: UUID, sessionId: UUID): Array[Byte] =
    s"spoonbill-example-recovery-v1:$completionId:$sessionId".getBytes(UTF_8)

  def create(completionId: UUID, sessionId: UUID, now: Instant): Generated = {
    val codes = Vector.fill(4) {
      val bytes = new Array[Byte](12)
      random.nextBytes(bytes)
      try "SYN-" + Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
      finally java.util.Arrays.fill(bytes, 0.toByte)
    }
    val plaintext = codes.mkString("\n").getBytes(UTF_8)
    try
      Generated(
        Record(
          completionId,
          sessionId,
          now.plusSeconds(lifetimeSeconds).truncatedTo(java.time.temporal.ChronoUnit.MICROS),
          vault.seal(plaintext, associated(completionId, sessionId)).toVector
        ),
        codes.map(code => MessageDigest.getInstance("SHA-256").digest(code.getBytes(UTF_8)).toVector)
      )
    finally java.util.Arrays.fill(plaintext, 0.toByte)
  }

  /**
   * Called only after the consumed-record transaction acknowledges commit. No
   * effect or reusable closure retains plaintext: the returned outcome owns the
   * invocation's one-shot disclosure holder, which empties on use/discard. JVM
   * Strings cannot be reliably erased; the working byte buffer is cleared.
   */
  def present(consumed: Consumed, context: AuthenticatedActionContext[Principal]): UiOutcome[Page] =
    consumed.take(context, now()) match {
      case None =>
        UiOutcome.modify[Page](
          _.copy(message = "Recovery codes were already consumed. Regenerate to obtain new codes.")
        )
      case Some(record) => presentRecord(record, context)
    }

  private def presentRecord(record: Record, context: AuthenticatedActionContext[Principal]): UiOutcome[Page] = {
    val plaintext = vault.open(record.ciphertext.toArray, associated(record.completionId, record.sessionId))
    try {
      require(plaintext.length <= 512, "Invalid synthetic recovery result")
      val codes = new String(plaintext, UTF_8).split("\n", -1).toVector
      require(codes.size == 4 && codes.forall(_.matches("SYN-[A-Za-z0-9_-]{16}")), "Invalid synthetic recovery result")
      val payload = SensitivePayload
        .textList(codes)
        .fold(_ => throw new IllegalStateException("Invalid recovery payload"), identity)
      context.presentSensitive[Page](region, purpose, payload, 30.seconds) { outcome =>
        spoonbill.action.StateUpdate.Modify(_.copy(message = outcome match {
          case DisclosureOutcome.BrowserProcessed => "Recovery codes disclosed once. Store them safely."
          case _                                  => "Recovery codes were consumed but delivery was not confirmed. Regenerate to obtain new codes."
        }))
      }
    } finally java.util.Arrays.fill(plaintext, 0.toByte)
  }
}
