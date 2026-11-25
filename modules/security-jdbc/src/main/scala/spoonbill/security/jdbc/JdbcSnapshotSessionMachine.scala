package spoonbill.security.jdbc

import spoonbill.security.Versions.ViewRevision

/**
 * Pure states of an inline snapshot session that can skip an unchanged
 * projection. Ready holds the expected revision and, when known, a copy of the
 * encoded bytes that the write acknowledged at that revision. Every change makes
 * a new Ready value, so the interpreter can recognize a late result of an older
 * attempt by reference and drop it.
 */
private[jdbc] object JdbcSnapshotSessionMachine {

  /** The encoded bytes of an acknowledged write: the only evidence that lets a write save nothing. */
  final class Confirmed private (bytes: Array[Byte]) {
    def matches(candidate: Array[Byte]): Boolean = java.util.Arrays.equals(bytes, candidate)
  }
  object Confirmed {
    def of(bytes: Array[Byte]): Confirmed = new Confirmed(bytes.clone())
  }

  sealed trait State
  case object Empty    extends State
  case object Starting extends State
  final class Ready(val revision: ViewRevision, val confirmed: Option[Confirmed]) extends State

  /** A restored or converted projection: its stored bytes may differ from what this node writes. */
  def restored(revision: ViewRevision): Ready = new Ready(revision, None)

  /** An acknowledged write publishes its revision and its bytes as one change. */
  def acknowledged(revision: ViewRevision, bytes: Array[Byte]): Ready = new Ready(revision, Some(Confirmed.of(bytes)))

  /**
   * A write attempt starts: the copy stays invalid until the attempt is
   * acknowledged, so a failed, cancelled or uncertain attempt leaves none, and
   * the revision never advances without an acknowledgment.
   */
  def attempt(ready: Ready): Ready = new Ready(ready.revision, None)

  /** An observation of another revision fails the write; the session never adopts it. */
  def conflicts(ready: Ready, observed: ViewRevision): Boolean = observed != ready.revision

  /** The candidate's encoded bytes equal the confirmed copy: the write saves nothing. */
  def unchanged(ready: Ready, candidate: Array[Byte]): Boolean = ready.confirmed.exists(_.matches(candidate))
}
