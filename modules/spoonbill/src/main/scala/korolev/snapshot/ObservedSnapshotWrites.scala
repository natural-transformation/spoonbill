package spoonbill.snapshot

import spoonbill.auth.SnapshotRevisionReceipt

/**
 * The capability of a snapshot session that the coordinator can pair with a
 * provider's guarded write observation in its queued top-level write. It stays
 * internal: receipts are created and consumed only inside Spoonbill, and a
 * session wrapped by application code no longer has it, so the coordinator
 * then keeps the ordinary authorization and commit.
 */
private[spoonbill] trait ObservedSnapshotWrites[F[_], S] {

  /**
   * Commit candidate under one observation's receipt. The receipt must name
   * this session's view, owner and epoch and be unused, and its revision must
   * equal this session's expected revision; otherwise the write fails without
   * a fallback. A candidate whose encoded bytes equal those of the last
   * acknowledged write is not saved again; any other is saved once, with the
   * expected revision.
   */
  private[spoonbill] def commitObserved(receipt: SnapshotRevisionReceipt, candidate: S): F[Unit]
}
