package spoonbill.recovery

import java.security.MessageDigest
import java.util.UUID

/**
 * Bounded immutable payload bytes owned by the binding. Construction copies the
 * caller's array, so no later mutation reaches a stored or compared value. The
 * digest is the SHA-256 of the bytes: public integrity metadata, never a secret
 * or an authority. toString never prints the bytes.
 */
final class SnapshotBytes private (private val value: Array[Byte]) {
  def length: Int          = value.length
  def toArray: Array[Byte] = value.clone()
  lazy val digest: Vector[Byte] = MessageDigest.getInstance("SHA-256").digest(value).toVector
  override def equals(other: Any): Boolean = other match {
    case that: SnapshotBytes => java.util.Arrays.equals(value, that.value)
    case _                   => false
  }
  override def hashCode(): Int  = java.util.Arrays.hashCode(value)
  override def toString: String = s"SnapshotBytes($length bytes)"
}

object SnapshotBytes {

  /** The binary codec's own ceiling; a larger payload is never a valid snapshot. */
  val MaximumLength: Int = 16 * 1024 * 1024

  def copyOf(bytes: Array[Byte]): SnapshotBytes = {
    require(bytes.length <= MaximumLength, "Snapshot payload exceeds the maximum length")
    new SnapshotBytes(bytes.clone())
  }
}

/**
 * An opaque payload reference: a fresh id together with the exact integrity
 * digest and length of the bytes it names. The metadata cannot change between
 * reservation, upload, publication and read; a reference is unique to one
 * publication attempt and is never reused after retirement.
 */
final case class PayloadRef(id: UUID, digest: Vector[Byte], length: Int) {
  require(digest.size == 32, "A payload digest is 32 bytes")
  require(length >= 0 && length <= SnapshotBytes.MaximumLength, "Invalid payload length")

  /** Exact integrity check of fetched bytes against the published metadata. */
  def matches(bytes: SnapshotBytes): Boolean = bytes.length == length && bytes.digest == digest
  override def toString: String               = s"PayloadRef($id,$length)"
}

object PayloadRef {
  def fresh(bytes: SnapshotBytes): PayloadRef = PayloadRef(UUID.randomUUID(), bytes.digest, bytes.length)
}

/**
 * The outcome of an immutable put. Durable means the adapter's declared
 * durability point was reached, not that a request was enqueued. Conflict
 * means the key already holds different bytes; Retired means the store has
 * fenced the reference and refuses to recreate it. Uncertain and Unavailable
 * never permit publication.
 */
enum PayloadWrite {
  case Durable, Conflict, Retired, Uncertain, Unavailable
}

enum PayloadRead {
  case Found(bytes: SnapshotBytes)
  case Missing, Unavailable
}

/**
 * What the adapter's store can establish about physical deletion. Only a
 * DurableRetirementFence (a write to a retired reference fails at the store,
 * serialized with the delete) or VerifiedQuiescence (admission is closed and
 * every admitted write has settled before the delete) permits FinalDeleted.
 * An Unproven adapter keeps every cleanup obligation open as Uncertain; a
 * caller-side deadline or repeated delete never upgrades that.
 */
enum CleanupBasis {
  case DurableRetirementFence, VerifiedQuiescence, Unproven
}

/**
 * FinalDeleted means durable absence against pending, delayed and retried
 * uploads, including callers paused before submission. Uncertain keeps the
 * provider's cleanup obligation open and retryable.
 */
enum PayloadDelete {
  case FinalDeleted, Uncertain, Unavailable
}

/**
 * The small immutable payload adapter of the portable recovery design. It
 * stores bytes under opaque references and never elects a view owner, sees a
 * BrowserView, a cookie, a raw browser binding, a principal or a subject. The
 * binding checks bounded length and integrity on every read. A duplicate put
 * may succeed only for the same bytes; conflicting content must not overwrite
 * a key. delete is called only by the maintenance path after a provider-issued
 * garbage claim, never by application handlers.
 */
trait SnapshotPayloadStore[F[_]] {
  def cleanupBasis: CleanupBasis
  def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite]
  def get(ref: PayloadRef): F[PayloadRead]
  def delete(ref: PayloadRef): F[PayloadDelete]
}
