package spoonbill.recovery

import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal
import spoonbill.effect.Effect
import spoonbill.effect.syntax.*
import spoonbill.security.Versions.{ViewOwnershipEpoch, ViewRevision}
import spoonbill.snapshot.*

/** A snapshot of one binding's protocol counters; see PayloadSnapshotBinding.statistics. */
final case class RecoveryStatistics(
  reservations: Long,
  publications: Long,
  statusLookups: Long,
  uploads: Long,
  downloads: Long,
  deletes: Long,
  reconciledPublications: Long,
  finalDeletes: Long,
  uncertainDeletes: Long,
  staleClaims: Long,
  downgradedDeletes: Long
)

/** Monotonic counters shared by every store of one binding. */
private[recovery] final class RecoveryCounters {
  val reservations           = new AtomicLong()
  val publications           = new AtomicLong()
  val statusLookups          = new AtomicLong()
  val uploads                = new AtomicLong()
  val downloads              = new AtomicLong()
  val deletes                = new AtomicLong()
  val reconciledPublications = new AtomicLong()
  val finalDeletes           = new AtomicLong()
  val uncertainDeletes       = new AtomicLong()
  val staleClaims            = new AtomicLong()
  val downgradedDeletes      = new AtomicLong()
  def snapshot: RecoveryStatistics = RecoveryStatistics(
    reservations.get(),
    publications.get(),
    statusLookups.get(),
    uploads.get(),
    downloads.get(),
    deletes.get(),
    reconciledPublications.get(),
    finalDeletes.get(),
    uncertainDeletes.get(),
    staleClaims.get(),
    downgradedDeletes.get()
  )
}

/**
 * The reference-backed ViewSnapshotStore: the provider publishes references
 * under its fences, the payload adapter stores immutable bytes. A healthy save
 * costs one reservation, one durable put and one publication; status lookups
 * happen only to reconcile an uncertain reply. A read pins the reference,
 * fetches and validates the bytes, then revalidates the pin before adopting
 * them. Missing or corrupt referenced bytes are failures, never an empty
 * snapshot or permission to overwrite. Adapter and provider exceptions fail
 * closed as StorageFailure.
 */
private[recovery] final class ReferenceSnapshotStore[F[_], D](
  bound: BoundSnapshotReferences[F],
  payloads: SnapshotPayloadStore[F],
  format: SnapshotFormat[D],
  probe: RecoveryProbe[F],
  counters: RecoveryCounters,
  closed: () => Boolean
)(using F: Effect[F])
    extends ViewSnapshotStore[F, D] {
  import FaultDecision.*
  import RecoveryFault.*

  val ownerEpoch: ViewOwnershipEpoch = bound.ownerEpoch
  private val schema                 = SnapshotSchema(format.schemaId.value, format.version.value)
  private val maximumBytes           = SnapshotBinaryCodec.maxEncodedBytes(format.limits)
  private type Result[A] = F[Either[ViewSnapshotError, A]]

  private def storageFailure[A]: Result[A] = F.pure(Left(ViewSnapshotError.StorageFailure))
  private def guarded[A](body: => Result[A]): Result[A] =
    if (closed()) storageFailure
    else F.delayAsync(body).recoverF { case NonFatal(_) => storageFailure[A] }

  def load(): Result[SnapshotLoad[D]] = guarded {
    bound.acquireRead().flatMap {
      case ReferenceLoad.Empty(revision)                 => F.pure(Right(SnapshotLoad.Empty(revision)))
      case ReferenceLoad.ResetRequired(revision, reason) => F.pure(Right(SnapshotLoad.ResetRequired(revision, reason)))
      case ReferenceLoad.Rejected(error)                 => F.pure(Left(error))
      case ReferenceLoad.Inline(revision, stored, bytes) =>
        F.pure(decode(stored, bytes).map(value => SnapshotLoad.Restored(revision, value)))
      case ReferenceLoad.Pinned(pin, ref, stored) => fetch(pin, ref, stored)
    }
  }

  private def fetch(pin: ReadPin, ref: PayloadRef, stored: SnapshotSchema): Result[SnapshotLoad[D]] =
    if (ref.length > maximumBytes) release(pin, ViewSnapshotError.MalformedSnapshot)
    else
      download(ref).flatMap {
        case PayloadRead.Found(bytes) if !ref.matches(bytes) => release(pin, ViewSnapshotError.MalformedSnapshot)
        case PayloadRead.Found(bytes) =>
          decode(stored, bytes) match {
            case Left(error) => release(pin, error)
            case Right(value) =>
              probe.at(AfterPayloadFetchBeforeAdoption).flatMap {
                case FailBeforeCommit => release(pin, ViewSnapshotError.StorageFailure)
                case _ =>
                  bound.finishRead(pin).flatMap {
                    case Right(_) => F.pure(Right(SnapshotLoad.Restored(pin.revision, value)))
                    // A refused revalidation may have stopped before its own pin release
                    // (lost authority, takeover): release through the restricted path too.
                    case Left(error) => release(pin, error)
                  }
              }
          }
        case PayloadRead.Missing     => release(pin, ViewSnapshotError.MalformedSnapshot)
        case PayloadRead.Unavailable => release(pin, ViewSnapshotError.StorageFailure)
      }

  private def download(ref: PayloadRef): F[PayloadRead] = {
    counters.downloads.incrementAndGet()
    F.delayAsync(payloads.get(ref)).recover { case NonFatal(_) => PayloadRead.Unavailable }
  }

  /** Release the pin on every failed outcome; a failed release cannot hide the original error. */
  private def release[A](pin: ReadPin, error: ViewSnapshotError): Result[A] =
    F.delayAsync(bound.releaseRead(pin)).recover { case NonFatal(_) => () }.map(_ => Left(error))

  private def decode(stored: SnapshotSchema, bytes: SnapshotBytes): Either[ViewSnapshotError, D] =
    (try
      SnapshotBinaryCodec.decode(bytes.toArray, format.limits).flatMap { document =>
        if (document.schemaId.value != stored.id || document.version.value != stored.version)
          Left(SnapshotError.MalformedEncoding)
        else format.read(document)
      }
    catch { case NonFatal(_) => Left(SnapshotError.InvalidValue) }).left.map(_ => ViewSnapshotError.MalformedSnapshot)

  def save(expected: ViewRevision, value: D): Result[ViewRevision] = publishValue(expected, value, PublicationIntent.Save)
  def reset(expected: ViewRevision, value: D): Result[ViewRevision] =
    publishValue(expected, value, PublicationIntent.Reset)

  private def encode(value: D): Either[ViewSnapshotError, SnapshotBytes] =
    try
      format
        .write(value)
        .flatMap(SnapshotBinaryCodec.encode(_, format.limits))
        .map(SnapshotBytes.copyOf)
        .left
        .map(ViewSnapshotError.InvalidState.apply)
    catch { case NonFatal(_) => Left(ViewSnapshotError.InvalidState(SnapshotError.InvalidValue)) }

  private def publishValue(expected: ViewRevision, value: D, intent: PublicationIntent): Result[ViewRevision] =
    guarded {
      encode(value) match {
        case Left(error) => F.pure(Left(error))
        case Right(bytes) =>
          val operation = PublicationId.fresh()
          val ref       = PayloadRef.fresh(bytes)
          counters.reservations.incrementAndGet()
          bound.reserve(operation, expected, ref, schema, intent).flatMap {
            case ReservationOutcome.Prepared(ticket) =>
              probe.at(AfterReservationBeforeReply).flatMap {
                case Proceed              => upload(ticket, bytes)
                case FailBeforeCommit     => storageFailure
                case LoseReplyAfterCommit => reconcileReservation(operation, bytes)
              }
            case ReservationOutcome.Rejected(error) => F.pure(Left(error))
            case ReservationOutcome.Uncertain       => reconcileReservation(operation, bytes)
          }
      }
    }

  /** Recover the original ticket before uploading; never create a second attempt blindly. */
  private def reconcileReservation(operation: PublicationId, bytes: SnapshotBytes): Result[ViewRevision] =
    status(operation).flatMap {
      case PublicationStatus.Prepared(ticket)    => upload(ticket, bytes)
      case PublicationStatus.Published(revision) => F.pure(Right(revision))
      case _                                     => storageFailure
    }

  private def upload(ticket: PublicationTicket, bytes: SnapshotBytes): Result[ViewRevision] =
    probe.at(BeforePayloadDispatch).flatMap {
      case FailBeforeCommit => storageFailure
      case _ =>
        probe.at(BeforePayloadDurability).flatMap {
          case FailBeforeCommit     => storageFailure
          case LoseReplyAfterCommit => put(ticket.ref, bytes).flatMap(_ => storageFailure)
          case Proceed =>
            put(ticket.ref, bytes).flatMap {
              case PayloadWrite.Durable =>
                probe.at(AfterPayloadDurabilityBeforePublish).flatMap {
                  case FailBeforeCommit => storageFailure
                  case _                => publish(ticket)
                }
              // Conflict, Retired, Uncertain or Unavailable: nothing durable to publish.
              case _ => storageFailure
            }
        }
    }

  private def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite] = {
    counters.uploads.incrementAndGet()
    F.delayAsync(payloads.put(ref, bytes)).recover { case NonFatal(_) => PayloadWrite.Unavailable }
  }

  private def publish(ticket: PublicationTicket): Result[ViewRevision] = {
    counters.publications.incrementAndGet()
    bound.publish(ticket).flatMap {
      case PublicationOutcome.Published(revision) =>
        probe.at(AfterPublishBeforeReply).flatMap {
          case Proceed              => F.pure(Right(revision))
          case FailBeforeCommit     => storageFailure
          case LoseReplyAfterCommit => reconcilePublication(ticket.operation)
        }
      case PublicationOutcome.Rejected(error) => F.pure(Left(error))
      case PublicationOutcome.Uncertain       => reconcilePublication(ticket.operation)
    }
  }

  /** A lost publish reply resolves to the committed revision, or fails closed. */
  private def reconcilePublication(operation: PublicationId): Result[ViewRevision] =
    status(operation).flatMap {
      case PublicationStatus.Published(revision) =>
        counters.reconciledPublications.incrementAndGet()
        F.pure(Right(revision))
      case _ => storageFailure
    }

  private def status(operation: PublicationId): F[PublicationStatus] = {
    counters.statusLookups.incrementAndGet()
    bound.status(operation)
  }
}
