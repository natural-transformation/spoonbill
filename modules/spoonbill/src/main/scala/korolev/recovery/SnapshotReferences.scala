package spoonbill.recovery

import java.time.Instant
import java.util.UUID
import spoonbill.auth.BrowserView
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.{SnapshotFormat, SnapshotResetReason, ViewSnapshotError}

/** A stable operation id; a retry of the same operation carries the same id. */
final case class PublicationId(value: UUID)

object PublicationId {
  def fresh(): PublicationId = PublicationId(UUID.randomUUID())
}

/** Ordinary save, or the explicit checked reset after an identity or schema change. */
enum PublicationIntent {
  case Save, Reset
}

/** Non-secret schema metadata of a stored projection. */
final case class SnapshotSchema(id: String, version: Int) {
  require(id.nonEmpty && id.length <= 96 && version > 0, "Invalid snapshot schema")
}

/**
 * The schema a bound session writes and the pure, bounded decision of which
 * stored schemas it can read. A provider may evaluate accepts inside its own
 * transaction, so it must not perform I/O or retain authority.
 */
final class SnapshotSchemaPolicy(val current: SnapshotSchema, val accepts: SnapshotSchema => Boolean)

object SnapshotSchemaPolicy {
  def of[D](format: SnapshotFormat[D]): SnapshotSchemaPolicy =
    new SnapshotSchemaPolicy(
      SnapshotSchema(format.schemaId.value, format.version.value),
      schema => format.accepts(schema.id, schema.version)
    )
}

/**
 * A durable reservation: operation, reference, expected revision, intent,
 * schema and the captured owner epoch. The provider revalidates every field
 * against its own record at publication; the ticket is bookkeeping, never
 * domain execution authority.
 */
final case class PublicationTicket(
  operation: PublicationId,
  ref: PayloadRef,
  expected: ViewRevision,
  intent: PublicationIntent,
  schema: SnapshotSchema,
  ownerEpoch: ViewOwnershipEpoch
)

enum ReservationOutcome {
  case Prepared(ticket: PublicationTicket)
  case Rejected(error: ViewSnapshotError)
  case Uncertain
}

enum PublicationOutcome {
  case Published(revision: ViewRevision)
  case Rejected(error: ViewSnapshotError)
  case Uncertain
}

/**
 * Reconciliation of one operation through a restricted bookkeeping path that
 * stays available after takeover or revocation. Published carries the original
 * committed revision so a binding can advance its expected revision; it does
 * not claim that revision is still current or grant successor authority.
 * A provider must not infer NotPublished from absence while an earlier attempt
 * may still commit; it answers Unresolved instead.
 */
enum PublicationStatus {
  case Prepared(ticket: PublicationTicket)
  case Published(revision: ViewRevision)
  case NotPublished, Unresolved
}

/** A bounded read pin on one published reference at one revision. */
final case class ReadPin(id: UUID, operation: PublicationId, revision: ViewRevision)

/**
 * The provider's current reference under a read pin. Inline carries bytes the
 * provider still holds itself (a snapshot written before external payloads
 * were enabled); the next publication of that view moves it to a reference.
 */
enum ReferenceLoad {
  case Empty(revision: ViewRevision)
  case Inline(revision: ViewRevision, schema: SnapshotSchema, bytes: SnapshotBytes)
  case Pinned(pin: ReadPin, ref: PayloadRef, schema: SnapshotSchema)
  case ResetRequired(revision: ViewRevision, reason: SnapshotResetReason)
  case Rejected(error: ViewSnapshotError)
}

/**
 * A provider-issued lease on one retired reference's physical deletion. Its
 * id and generation identify the current claim; a stale claim can neither
 * complete nor retry a successor's obligation.
 */
final case class GarbageClaim(
  operation: PublicationId,
  ref: PayloadRef,
  claimId: UUID,
  generation: Long,
  expiresAt: Instant
)

enum ClaimUpdate {
  case Applied, AlreadyApplied, StaleClaim
}

/** Visible cleanup backlog: open obligations, those already retried, and the oldest retirement. */
final case class CleanupBacklog(open: Long, uncertain: Long, oldestRetiredAt: Option[Instant])

/**
 * The optional, provider-neutral publication capability. An authority provider
 * that implements it publishes payload references in the same atomic domain as
 * its ownership decisions. It is separate from BrowserPersistence: no existing
 * provider gains a mandatory method. Binding is pure; I/O starts inside the
 * bound operations and maintenance.
 */
trait SnapshotReferenceSupport[F[_], P] {

  /** None for a view without a durable projection, such as an anonymous view. */
  def bind(view: BrowserView[P], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[F]]
  def maintenance: SnapshotReferenceMaintenance[F]
}

/**
 * Reference operations bound to one claimed view. acquireRead, finishRead,
 * reserve and publish check current authority, owner epoch and revision at
 * their admission boundary. releaseRead and status are restricted bookkeeping
 * that remains possible for a retired owner: they return no payload, no
 * successor authority and admit no publication.
 */
trait BoundSnapshotReferences[F[_]] {
  def ownerEpoch: ViewOwnershipEpoch
  def acquireRead(): F[ReferenceLoad]

  /** Revalidate the pin's reference, revision, authority and owner, then release it. */
  def finishRead(pin: ReadPin): F[Either[ViewSnapshotError, Unit]]
  def releaseRead(pin: ReadPin): F[Unit]
  def reserve(
    operation: PublicationId,
    expected: ViewRevision,
    ref: PayloadRef,
    schema: SnapshotSchema,
    intent: PublicationIntent
  ): F[ReservationOutcome]
  def publish(ticket: PublicationTicket): F[PublicationOutcome]
  def status(operation: PublicationId): F[PublicationStatus]
}

/**
 * Short provider operations around physical deletion, which the binding runs
 * outside coordinator turns and provider transactions. completeDelete is
 * called only after FinalDeleted and must match the claim's id and generation;
 * retryDelete keeps the obligation and sets bounded retry eligibility.
 */
trait SnapshotReferenceMaintenance[F[_]] {
  def claimGarbage(limit: Int): F[Vector[GarbageClaim]]
  def completeDelete(claim: GarbageClaim): F[ClaimUpdate]
  def retryDelete(claim: GarbageClaim): F[ClaimUpdate]
  def backlog(): F[CleanupBacklog]
}
