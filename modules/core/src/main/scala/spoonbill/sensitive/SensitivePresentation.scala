package spoonbill.sensitive

import java.time.{Duration, Instant}
import java.util.UUID
import spoonbill.security.Identifiers.ConnectionId

opaque type RegionId = String
object RegionId {
  def parse(value: String): Either[SensitiveError, RegionId] = Names.checked(value)
  extension (id: RegionId) def value: String = id
}

opaque type Purpose = String
object Purpose {
  def parse(value: String): Either[SensitiveError, Purpose] = Names.checked(value)
  extension (purpose: Purpose) def value: String = purpose
}

private object Names {
  def checked(value: String): Either[SensitiveError, String] =
    if (value != null && value.matches("[a-z][a-z0-9_.-]{0,63}")) Right(value)
    else Left(SensitiveError.InvalidName)
}

/** Host-issued identity for one authorized subject/scope/session generation or
  * credential-proven preauthentication ceremony. It is a comparison key, never
  * authentication evidence. The host must freshly validate before returning it.
  */
opaque type Audience = UUID
object Audience {
  def fromUuid(value: UUID): Audience = value
}

opaque type PresentationId = UUID
object PresentationId {
  private[spoonbill] def fromUuid(value: UUID): PresentationId = value
  extension (id: PresentationId) private[spoonbill] def value: UUID = id
}

/** Metadata only. Browser acknowledgments must resolve the audience from the
  * server-owned presentation record; the browser cannot supply that authority.
  */
final case class PresentationBinding(connectionId: ConnectionId, regionId: RegionId,
  presentationId: PresentationId, audience: Audience)

enum DisclosureOutcome {
  case NotSent, Uncertain, BrowserProcessed
}

enum ClearReason {
  case Requested, Replaced, Navigation, Expired, Disconnected, Revoked, DeliveryFailed
}

enum PresentationPhase {
  case Prepared, Emitted, BrowserAcknowledged
  case Closed(reason: ClearReason, outcome: DisclosureOutcome)
}

/** Pure, metadata-only delivery lifecycle; no plaintext, effect, or output cache.
  * Runtime supplies a trusted clock and serializes transitions. Mark emitted
  * before handing bytes to transport: failure thereafter is uncertain. An ack
  * only proves browser protocol processing, never human reading or safe storage.
  */
final class SensitivePresentation private (
  val binding: PresentationBinding,
  val purpose: Purpose,
  val issuedAt: Instant,
  val expiresAt: Instant,
  val phase: PresentationPhase
) {
  override def toString: String = "SensitivePresentation(<metadata>)"

  def emitted(presented: PresentationBinding, now: Instant): Either[SensitiveError, SensitivePresentation] =
    validate(presented, now).flatMap { _ => phase match {
      case PresentationPhase.Prepared => Right(next(PresentationPhase.Emitted))
      case _ => Left(SensitiveError.InvalidTransition)
    }}

  def acknowledge(presented: PresentationBinding, now: Instant): Either[SensitiveError, SensitivePresentation] =
    validate(presented, now).flatMap { _ => phase match {
      case PresentationPhase.Emitted => Right(next(PresentationPhase.BrowserAcknowledged))
      case PresentationPhase.BrowserAcknowledged => Right(this)
      case _ => Left(SensitiveError.InvalidTransition)
    }}

  /** Idempotent terminal transition. Clearing cannot undo possible disclosure. */
  def clear(reason: ClearReason): SensitivePresentation = phase match {
    case PresentationPhase.Closed(_, _) => this
    case PresentationPhase.Prepared => next(PresentationPhase.Closed(reason, DisclosureOutcome.NotSent))
    case PresentationPhase.Emitted => next(PresentationPhase.Closed(reason, DisclosureOutcome.Uncertain))
    case PresentationPhase.BrowserAcknowledged => next(PresentationPhase.Closed(reason, DisclosureOutcome.BrowserProcessed))
  }

  def expire(now: Instant): Either[SensitiveError, SensitivePresentation] =
    if (now.isBefore(expiresAt)) Left(SensitiveError.NotExpired)
    else Right(clear(ClearReason.Expired))

  private def validate(presented: PresentationBinding, now: Instant): Either[SensitiveError, Unit] =
    if (binding != presented) Left(SensitiveError.WrongBinding)
    else phase match {
      case PresentationPhase.Closed(_, _) => Left(SensitiveError.Closed)
      case _ if now.isBefore(issuedAt) || !now.isBefore(expiresAt) => Left(SensitiveError.DeadlineExceeded)
      case _ => Right(())
    }

  private def next(updated: PresentationPhase): SensitivePresentation =
    new SensitivePresentation(binding, purpose, issuedAt, expiresAt, updated)
}

object SensitivePresentation {
  val maximumLifetime: Duration = Duration.ofMinutes(5)

  def prepare(binding: PresentationBinding, purpose: Purpose, issuedAt: Instant,
    expiresAt: Instant): Either[SensitiveError, SensitivePresentation] =
    if (!expiresAt.isAfter(issuedAt) || Duration.between(issuedAt, expiresAt).compareTo(maximumLifetime) > 0)
      Left(SensitiveError.InvalidDeadline)
    else Right(new SensitivePresentation(binding, purpose, issuedAt, expiresAt, PresentationPhase.Prepared))
}
