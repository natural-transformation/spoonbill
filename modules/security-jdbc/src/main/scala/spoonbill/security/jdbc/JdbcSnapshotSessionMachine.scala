package spoonbill.security.jdbc

import spoonbill.security.Versions.ViewRevision

/**
 * Pure states of an inline snapshot session that can skip an unchanged
 * projection. Ready holds the expected revision and, when known, a copy of the
 * encoded bytes that the write acknowledged at that revision. Only one write per
 * expected revision can succeed in the database, so an acknowledged result is
 * published whenever the session still expects the revision that write
 * expected, also when another commit of the same session started meanwhile.
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
  case object Empty extends State

  /**
   * One initialization attempt in progress. Each attempt is its own value, so
   * only the attempt that claimed the session can publish its result or put
   * its unfinished claim back.
   */
  final class Starting extends State
  final class Ready(val revision: ViewRevision, val confirmed: Option[Confirmed]) extends State

  /** A restored or converted projection: its stored bytes may differ from what this node writes. */
  def restored(revision: ViewRevision): Ready = new Ready(revision, None)

  /** An acknowledged write publishes its revision and its bytes as one change. */
  def acknowledged(revision: ViewRevision, bytes: Array[Byte]): Ready = new Ready(revision, Some(Confirmed.of(bytes)))

  /**
   * A write that expects `expected` starts: the copy is invalid until a write
   * is acknowledged, so a failed, cancelled or uncertain write leaves none, and
   * the revision never advances without an acknowledgment.
   */
  def begin(state: State, expected: ViewRevision): State = state match {
    case ready: Ready if ready.revision == expected => new Ready(expected, None)
    case other                                     => other
  }

  /**
   * The write that expected `expected` was acknowledged at `next` with these
   * bytes. It publishes them while the session still expects `expected`; a
   * session that is no longer ready, or ready at another revision, stays as it is.
   */
  def acknowledge(state: State, expected: ViewRevision, next: ViewRevision, bytes: Array[Byte]): State = state match {
    case ready: Ready if ready.revision == expected => acknowledged(next, bytes)
    case other                                     => other
  }

  /** An observation of another revision fails the write; the session never adopts it. */
  def conflicts(ready: Ready, observed: ViewRevision): Boolean = observed != ready.revision

  /** The candidate's encoded bytes equal the confirmed copy: the write saves nothing. */
  def unchanged(ready: Ready, candidate: Array[Byte]): Boolean = ready.confirmed.exists(_.matches(candidate))
}
