package spoonbill.security

import Identifiers.*
import Versions.*
import java.time.Instant

/**
 * Resolved server-side lineage, realm, and cookie namespace, not a
 * browser-supplied proof.
 */
final case class SlotBinding(
  slotId: BrowserSessionSlotId,
  browserBindingId: BrowserBindingId,
  realmId: RealmId,
  cookieNamespace: CookieNamespace
)

/**
 * Prepared and delivered do not mean activated. Credential validation belongs
 * to the adapter.
 */
final case class PendingActivation(
  binding: SlotBinding,
  expectedGeneration: SlotGeneration,
  completionId: CompletionId,
  sessionId: AuthSessionId,
  expiresAt: Instant
)

final case class ActiveSlotSession(
  sessionId: AuthSessionId,
  completionId: CompletionId,
  originatingGeneration: SlotGeneration
)

enum SlotError {
  case BindingMismatch, StaleGeneration, CompletionExpired, SessionNotCurrent, GenerationExhausted
}

/**
 * Pure decisions over an authoritative record. A host must atomically
 * read/decide/write this record together with session activation and completion
 * acknowledgement. Calling these methods on two independent copies provides no
 * CAS, persistence, or concurrency guarantee.
 */
final case class BrowserSessionSlot(
  binding: SlotBinding,
  generation: SlotGeneration,
  current: Option[ActiveSlotSession]
) {
  def activate(pending: PendingActivation, now: Instant): Either[SlotError, BrowserSessionSlot] =
    if (binding != pending.binding) Left(SlotError.BindingMismatch)
    else if (!now.isBefore(pending.expiresAt)) Left(SlotError.CompletionExpired)
    else if (current.contains(ActiveSlotSession(pending.sessionId, pending.completionId, pending.expectedGeneration)))
      Right(this) // Same activation, already committed; never install a competing session.
    else if (generation != pending.expectedGeneration) Left(SlotError.StaleGeneration)
    else
      generation.next.left.map(_ => SlotError.GenerationExhausted).map { next =>
        copy(
          generation = next,
          current = Some(
            ActiveSlotSession(
              pending.sessionId,
              pending.completionId,
              pending.expectedGeneration
            )
          )
        )
      }

  /**
   * Logout targets the current slot, even if another ceremony activated since
   * admission.
   */
  def logout(callerBinding: SlotBinding): Either[SlotError, BrowserSessionSlot] =
    if (binding != callerBinding) Left(SlotError.BindingMismatch)
    else
      generation.next.left.map(_ => SlotError.GenerationExhausted).map { next =>
        copy(generation = next, current = None)
      }

  /**
   * Session expiry/revocation must also be checked against the session record
   * by the host.
   */
  def validateCurrent(
    callerBinding: SlotBinding,
    sessionId: AuthSessionId,
    expectedGeneration: SlotGeneration
  ): Either[SlotError, Unit] =
    if (binding != callerBinding) Left(SlotError.BindingMismatch)
    else if (generation != expectedGeneration) Left(SlotError.StaleGeneration)
    else if (!current.exists(_.sessionId == sessionId)) Left(SlotError.SessionNotCurrent)
    else Right(())
}

object BrowserSessionSlot {
  def empty(binding: SlotBinding): BrowserSessionSlot =
    BrowserSessionSlot(binding, SlotGeneration.initial, None)
}
