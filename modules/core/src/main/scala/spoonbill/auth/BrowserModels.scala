package spoonbill.auth

import java.time.Instant
import java.util.UUID

/** Decoded lookup metadata, never evidence that a proof was verified. */
final case class CeremonyId(value: UUID)

final case class HostPrincipalStamp(subject: String, securityVersion: Long, policyVersion: Long, expiresAt: Instant)

final case class BrowserAuthorityStamp(
  bindingKey: Vector[Byte],
  generation: Long,
  sessionId: Option[UUID],
  host: Option[HostPrincipalStamp]
) {
  override def toString: String = "BrowserAuthorityStamp(<redacted>)"
}

/** Immutable host-owned principal and the policy identity captured with it. */
final case class HostPrincipal[P](
  value: P,
  subject: String,
  securityVersion: Long,
  policyVersion: Long,
  expiresAt: Instant
) {
  require(subject.nonEmpty && subject.length <= 256)
  require(securityVersion >= 0 && policyVersion >= 0)
  def stamp: HostPrincipalStamp = HostPrincipalStamp(subject, securityVersion, policyVersion, expiresAt)
}

/** Host-defined metadata; never the proof or its verifier secret. */
final case class BrowserChallenge(subject: String, credentialVersion: Long, policyVersion: Long, reference: String) {
  require(subject.nonEmpty && subject.length <= 256)
  require(reference.nonEmpty && reference.length <= 256)
  require(credentialVersion >= 0 && policyVersion >= 0)
}

final case class BrowserContinuation(
  id: CeremonyId,
  purpose: String,
  challenge: Option[BrowserChallenge],
  preparationPending: Boolean,
  expiresAt: Instant
)

enum BrowserCeremonyPhase {
  case Begun, Challenged, Admitted, Committed, NotCommitted
}

enum BrowserCeremonyError {
  case Denied, Expired, Used
}

/**
 * Pure decisions only. Providers serialize read/decide/write with their host
 * mutation and preserve negative/replay exclusion. Eligible expired records
 * may be retired only behind permanent issuance and generation fences, after
 * excluding earlier writers. Expiry or absence alone never authorizes replay.
 */
final case class BrowserCeremonyRecord(
  id: CeremonyId,
  completionId: UUID,
  bindingKey: Vector[Byte],
  generation: Long,
  purpose: String,
  expiresAt: Instant,
  challenge: Option[BrowserChallenge] = None,
  phase: BrowserCeremonyPhase = BrowserCeremonyPhase.Begun
) {
  require(bindingKey.size == 32 && generation >= 0)
  require(purpose.nonEmpty && purpose.length <= 96)

  def validate(
    binding: Vector[Byte],
    currentGeneration: Long,
    expectedPurpose: String,
    now: Instant
  ): Either[BrowserCeremonyError, Unit] =
    if (binding != bindingKey || currentGeneration != generation || expectedPurpose != purpose)
      Left(BrowserCeremonyError.Denied)
    else if (!now.isBefore(expiresAt)) Left(BrowserCeremonyError.Expired)
    else Right(())

  def bind(value: BrowserChallenge): Either[BrowserCeremonyError, BrowserCeremonyRecord] =
    if (phase != BrowserCeremonyPhase.Begun || challenge.nonEmpty) Left(BrowserCeremonyError.Used)
    else Right(copy(challenge = Some(value), phase = BrowserCeremonyPhase.Challenged))

  def admit: Either[BrowserCeremonyError, BrowserCeremonyRecord] = phase match {
    case BrowserCeremonyPhase.Begun | BrowserCeremonyPhase.Challenged =>
      Right(copy(phase = BrowserCeremonyPhase.Admitted))
    case _ => Left(BrowserCeremonyError.Used)
  }

  def committed: Either[BrowserCeremonyError, BrowserCeremonyRecord] =
    if (phase != BrowserCeremonyPhase.Admitted) Left(BrowserCeremonyError.Used)
    else Right(copy(phase = BrowserCeremonyPhase.Committed))

  def reconciled: BrowserCeremonyRecord =
    if (phase == BrowserCeremonyPhase.Committed) this else copy(phase = BrowserCeremonyPhase.NotCommitted)
}

final case class BrowserPersistenceLimits(
  retainedCeremonies: Int = 32768,
  ceremoniesPerBinding: Int = 16,
  retainedBindings: Int = 32768,
  retainedViews: Int = 131072,
  activeViewsPerBinding: Int = 16,
  deliveryMaterials: Int = 1024,
  maxDeliveries: Int = 3,
  pendingJobs: Int = 256,
  ceremonySeconds: Long = 120,
  deliverySeconds: Long = 60,
  anonymousBindings: Int = 1024,
  retention: BrowserRetentionPolicy = BrowserRetentionPolicy()
) {
  require(
    Vector(
      retainedCeremonies,
      ceremoniesPerBinding,
      retainedBindings,
      retainedViews,
      activeViewsPerBinding,
      deliveryMaterials,
      maxDeliveries,
      pendingJobs,
      anonymousBindings
    ).forall(_ > 0)
  )
  require(ceremonySeconds > 0 && deliverySeconds > 0)
}
