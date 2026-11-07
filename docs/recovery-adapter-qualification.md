# Qualifying a payload adapter

This guide is for authors of a `SnapshotPayloadStore`: the small immutable
payload adapter of [portable UI recovery](portable-ui-recovery-plan.md). It says
what the adapter must promise, what the published conformance kit checks, and
what the author still has to establish about the real datastore. The provider
side, reference publication under the owner fence, stays with the configured
authority provider; an adapter never sees a `BrowserView`, a cookie, a raw
browser binding, a principal or a subject.

## The contract

```scala
trait SnapshotPayloadStore[F[_]] {
  def cleanupBasis: CleanupBasis
  def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite]
  def get(ref: PayloadRef): F[PayloadRead]
  def delete(ref: PayloadRef): F[PayloadDelete]
}
```

- `put` is immutable. `Durable` means the store's declared durability point was
  reached, not that a request was enqueued. A duplicate put may succeed only for
  the same bytes; different bytes under the same reference answer `Conflict`.
  A put of a retired reference answers `Retired`. `Uncertain` and `Unavailable`
  are never published; the binding fails that save closed.
- `get` returns the exact bytes or `Missing`. The binding checks length and
  SHA-256 against the published `PayloadRef` before decoding; a mismatch is a
  malformed snapshot, never an empty one.
- `delete` is called only by `binding.maintenance()` after the provider issued a
  garbage claim. `FinalDeleted` means durable absence against pending, delayed
  and retried uploads, including a caller paused before submission. Anything
  less answers `Uncertain`; the provider keeps the obligation open and visible.
- `cleanupBasis` is the trusted claim behind `FinalDeleted`:
  - `DurableRetirementFence`: retirement and write admission serialize at the
    store, for example a tombstone written by the delete together with a
    conditional put that refuses tombstoned keys under the same lock. Checking
    a tombstone and writing later in separate operations is not a fence.
  - `VerifiedQuiescence`: admission is irrevocably closed and every admitted
    write has settled before the final delete. A process-local counter does
    not establish this across crashed or paused writers.
  - `Unproven`: the adapter may delete, but every delete answers `Uncertain`.
    The binding downgrades a `FinalDeleted` from an unproven adapter and keeps
    the obligation. No caller-side deadline or repeated delete upgrades it.

Payload confidentiality is the deployment's responsibility: transport, storage
access and any encryption and key management. The reference carries an
integrity digest, not encryption.

## What the kit checks

Add the kit in test scope:

```scala
libraryDependencies += "com.natural-transformation" %% "spoonbill-recovery-testkit" % spoonbillVersion % Test
```

The kit depends on the public recovery API in `spoonbill`, not on
`spoonbill-security-jdbc` or private classes. Its ScalaTest dependency is a
compile dependency of the kit, so the law traits run from your test sources.

1. `PayloadStoreLaws[F]`: exact reads, immutable puts, `Missing` for unknown
   references, deletion outcomes that honor the declared basis, and for a
   fencing adapter a write that arrives after the delete of a never-written
   reference.
2. `RecoveryLaws[F, D]` with `InMemoryRecoveryFixture(format, yourStore)`: the
   publication protocol on the modeled provider host with your adapter as the
   payload store. It covers the healthy commit budget, takeover and revision
   fences, lost reservation and publication replies, read pins across takeover
   and revocation, reclamation, lost completions, expired garbage claims, a
   paused upload overtaken by cleanup, identity and schema changes, missing
   payloads and backpressure at the obligation bound. The laws branch on your
   basis: a fencing adapter must settle every obligation, an unproven adapter
   must keep every obligation open and charged.
3. If you also implement `SnapshotReferenceSupport` for another authority
   provider, implement `RecoveryFixture` for it and run the same laws; the
   PostgreSQL implementation's fixture in `modules/security-jdbc/src/test` is
   the pattern.

[misc/recovery-adapter-consumer](../misc/recovery-adapter-consumer/README.md)
is a separate sbt build that resolves the published kit and runs both suites
against a file-system adapter with a per-reference lock and tombstone fence.

## What the author still has to prove

The laws establish the adapter's own contract in one process. They do not
establish the datastore's profile. Before declaring a basis, write down:

- **Durability point.** What `Durable` means for your store (replicated,
  fsynced, acknowledged by how many nodes) and what a lost reply after that
  point looks like.
- **Fence or quiescence proof.** How a write of a retired reference fails at
  the store from another process, after a crash or restart, and under client
  retries and queued requests. A bounded-delay argument counts only when the
  bound provably covers all delayed admission, execution and retries; a plain
  object or key-value store documents no such bound and must declare
  `Unproven`.
- **Admission and retry control.** How many uploads and deletes your adapter
  admits at once, how it bounds retries, and what it reports when the store is
  unavailable.
- **Fence retention and reclamation.** When, if ever, tombstones are removed.
  Removing one makes a late write an unreferenced orphan, not a live payload,
  because references are never reused; that residual is storage and retention
  accounting, not a correctness exception, and it must be reported.
- **Extra operations.** Any status, list or size calls beyond the healthy
  path's one put per save and one get per restore.

Keep inline provider snapshots as the default for small drafts. The
reference-backed path spends two provider transactions and one payload write on
every committed state transition, and a slow payload store sits on the click
path of its view; see the commit budget in the
[clustered guide](clustered-browser-authentication.md#external-payload-storage).
