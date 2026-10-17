package spoonbill.auth

import java.time.Instant
import java.util.UUID
import spoonbill.security.ViewFence

/**
 * Captured before proof verification. Pure data, never an authorization
 * capability.
 */
final case class BrowserReplacementPredecessor(
  scope: BrowserProviderScope,
  authority: BrowserAuthorityStamp,
  credentialHash: Vector[Byte],
  viewFence: ViewFence
) {
  private[auth] def validAt(now: Instant): Boolean =
    authority.bindingKey.size == 32 && authority.generation >= 0 && credentialHash.size == 32 &&
      authority.sessionId.nonEmpty && authority.host.exists(host =>
        host.subject.nonEmpty && host.subject.length <= 256 && host.securityVersion >= 0 && host.policyVersion >= 0 &&
          now.isBefore(host.expiresAt)
      )
}

final case class BrowserReplacementSuccessor(
  sessionId: UUID,
  credentialHash: Vector[Byte],
  principal: HostPrincipalStamp,
  expiresAt: Instant
)

/**
 * Distinct transition input: an ordinary ceremony alone cannot prepare a
 * replacement. This decision checks captured facts and successor validity;
 * providers must revalidate captured authority against current host/browser
 * state in their serialized transaction and enforce current ownership/slot
 * fences before publication. Those checks cannot be established by comparing a
 * capture with itself here. The host verifies challenge.credentialVersion
 * against its own pre-transition credential version, which need not equal
 * HostPrincipalStamp.securityVersion. A successful pure decision does not
 * establish settlement or issue authority.
 */
final case class BrowserReplacementAttempt(
  ceremony: BrowserCeremonyRecord,
  predecessor: BrowserReplacementPredecessor
) {
  def prepare(
    successor: BrowserReplacementSuccessor,
    predecessorAuthorizedAfterMutation: Boolean,
    expiresAt: Instant,
    now: Instant
  ): Either[BrowserCeremonyError, BrowserReplacementReservation] = {
    val captured = predecessor.authority
    val original = captured.host
    val validSuccessor = original.exists { host =>
      successor.principal.subject == host.subject && !captured.sessionId.contains(successor.sessionId) &&
      successor.credentialHash.size == 32 && successor.credentialHash != predecessor.credentialHash &&
      successor.principal.securityVersion >= host.securityVersion &&
      successor.principal.policyVersion >= host.policyVersion &&
      !successor.expiresAt.isAfter(successor.principal.expiresAt)
    }
    if (ceremony.phase != BrowserCeremonyPhase.Admitted) Left(BrowserCeremonyError.Used)
    else if (
      !predecessor.validAt(now) ||
      ceremony.bindingKey != captured.bindingKey || ceremony.generation != captured.generation ||
      ceremony.challenge.exists(challenge =>
        !original.exists(host => challenge.subject == host.subject && challenge.policyVersion == host.policyVersion)
      ) || predecessorAuthorizedAfterMutation || !validSuccessor
    ) Left(BrowserCeremonyError.Denied)
    else if (
      !now.isBefore(ceremony.expiresAt) || !now.isBefore(successor.expiresAt) ||
      !now.isBefore(expiresAt) || expiresAt.isAfter(ceremony.expiresAt) ||
      expiresAt.isAfter(successor.expiresAt)
    ) Left(BrowserCeremonyError.Expired)
    else
      ceremony.committed.map(committed =>
        BrowserReplacementReservation(copy(ceremony = committed), successor, expiresAt)
      )
  }
}

enum BrowserReplacementAvailability {
  case DeliveryAvailable, ActivationAvailable, Unavailable
}

/**
 * Structural slot decisions shared by providers. Reservation is a terminal
 * fence until the exact successor activates or explicit logout advances the
 * generation. Expiry/cleanup deliberately has no operation that clears it. Host
 * authority and credential checks remain the provider's responsibility.
 */
final case class BrowserReplacementSlot(
  generation: Long,
  current: Option[UUID],
  replacement: Option[CeremonyId] = None
) {
  def reserve(value: BrowserReplacementReservation): Either[BrowserCeremonyError, BrowserReplacementSlot] = {
    val original = value.attempt.predecessor.authority
    if (
      generation == Long.MaxValue || replacement.nonEmpty || generation != original.generation || current != original.sessionId
    )
      Left(BrowserCeremonyError.Denied)
    else Right(copy(replacement = Some(value.attempt.ceremony.id)))
  }

  def activate(
    value: BrowserReplacementReservation,
    now: Instant
  ): Either[BrowserCeremonyError, BrowserReplacementSlot] = {
    val original = value.attempt.predecessor.authority
    if (!now.isBefore(value.expiresAt)) Left(BrowserCeremonyError.Expired)
    else if (
      generation != original.generation || current != original.sessionId ||
      !replacement.contains(value.attempt.ceremony.id) || generation == Long.MaxValue
    )
      Left(BrowserCeremonyError.Denied)
    else Right(BrowserReplacementSlot(generation + 1L, Some(value.successor.sessionId)))
  }

  def logout: Either[BrowserCeremonyError, BrowserReplacementSlot] =
    if (generation == Long.MaxValue) Left(BrowserCeremonyError.Denied)
    else Right(BrowserReplacementSlot(generation + 1L, None))
}

/**
 * Committed linkage is retained independently of short-lived delivery material.
 * expiresAt is the fixed pending-completion deadline for both delivery and
 * activation. Removing material or exhausting delivery attempts does not
 * shorten or extend that deadline for a successor credential already held.
 * Providers may persist this only with the atomic host/browser mutation and may
 * expose its outcome only after acknowledged settlement or authoritative
 * recovery.
 */
final case class BrowserReplacementReservation private[auth] (
  attempt: BrowserReplacementAttempt,
  successor: BrowserReplacementSuccessor,
  expiresAt: Instant
) {
  require(attempt.ceremony.phase == BrowserCeremonyPhase.Committed)

  /**
   * No ordinary authority is returned. A provider must check these observations
   * against authoritative storage, including the pending reservation,
   * atomically. Missing/known predecessor credentials may recover delivery; an
   * unrelated supplied credential must never fall back to that path. A
   * committed reservation can report availability only; it cannot establish
   * that an attempt did not commit.
   */
  def recover(
    scope: BrowserProviderScope,
    bindingKey: Vector[Byte],
    generation: Long,
    currentSessionId: Option[UUID],
    pendingAttempt: Option[CeremonyId],
    currentSuccessor: Option[HostPrincipalStamp],
    suppliedCredentialHash: Option[Vector[Byte]],
    materialAvailable: Boolean,
    deliveriesRemaining: Int,
    now: Instant
  ): Either[BrowserCeremonyError, BrowserReplacementAvailability] = {
    val predecessor = attempt.predecessor
    val captured    = predecessor.authority
    val knownCredential = suppliedCredentialHash.forall(hash =>
      hash.size == 32 && (hash == predecessor.credentialHash || hash == successor.credentialHash)
    )
    if (scope != predecessor.scope || bindingKey != captured.bindingKey || !knownCredential)
      Left(BrowserCeremonyError.Denied)
    else {
      val pending = generation == captured.generation && currentSessionId == captured.sessionId &&
        pendingAttempt.contains(attempt.ceremony.id) && currentSuccessor.contains(successor.principal) &&
        now.isBefore(expiresAt) && now.isBefore(attempt.ceremony.expiresAt) && now.isBefore(successor.expiresAt) &&
        now.isBefore(successor.principal.expiresAt)
      val availability =
        if (!pending) BrowserReplacementAvailability.Unavailable
        else if (suppliedCredentialHash.contains(successor.credentialHash))
          BrowserReplacementAvailability.ActivationAvailable
        else if (materialAvailable && deliveriesRemaining > 0)
          BrowserReplacementAvailability.DeliveryAvailable
        else BrowserReplacementAvailability.Unavailable
      Right(availability)
    }
  }
}
