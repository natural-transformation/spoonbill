# Effects, browser and testkit rollout

This log records bounded units for phases 8–10. Existing behavior is characterized
before decisions move; confirmed fixes are separate commits from refactors.

## Testkit

- `RecoveryGates`: confirmed that resuming an older gate removed a replacement
  armed at the same fault point. The regression failed before an identity-fenced
  removal fixed it (`4ccd1cd0`). First-arrival ownership, reached-observer fanout,
  repeated resume and early resume are characterized. The pure gate state has
  waiting, arrived, resumed-before-arrival and resumed-after-arrival cases;
  callbacks and the fault-point registry remain interpreter-owned.
- `InMemoryPayloadStore`: one per-reference slot distinguishes stored bytes from
  retirement, preserving the diagnostic ability to corrupt bytes behind a fence.
  Unavailable operations remain no-ops, immutable put conflicts remain conflicts,
  and unproven deletion does not prevent later puts. Existing payload and recovery
  laws remain unchanged.

Baseline: `recoveryTestkit/testOnly *RecoveryGatesSpec *PayloadStoreStateSpec`
passed five characterizations and failed the stale-gate regression. After the
isolated fix, `recoveryTestkit/test` passed 50 tests with four existing
cleanup-basis-specific cancellations. All Scala commands use the local flake via
`env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command sbt`.
After extraction the unchanged law suites and characterizations again passed all
50 tests; targeted formatting, `scripts/check-core-purity.mjs` and
`git diff --check` passed. Phase 10's two inventory items are implemented.

## Effect contracts under investigation

`AsyncTable.getFill` had two confirmed factory-failure defects: an asynchronous
failure stranded joined readers and settlement observers, and a synchronous
factory throw escaped the effect. Both new regressions failed before the fix;
six other lifecycle characterizations passed. Factory outcomes are now captured
before `putEither`, so failures are cached exactly like explicit `fail(key)`.
Publication conflicts are not recaught as factory failures; explicit puts and
the existing put-on-absent behavior after removal remain characterized. All
effect tests and all 50 recovery-testkit tests pass after the isolated fix.
The subsequent representation-only table change names each key's `Waiting` and
`Settled` states instead of nesting `Either`. The map remains one atomic value,
callbacks still run only after the winning CAS, and removal still starts a fresh
key lifecycle. This follows the inventory's per-key-enum proposal rather than
introducing per-element event objects or a second callback registry. All 50 effect
and 50 recovery-testkit tests pass unchanged after this refactor.

`Scheduler.JobHandler` lost earlier result waiters, failed to replay results when
a waiter was already registered, and stranded results when the job factory threw
synchronously. Three permanent controlled-promise regressions failed on the old
implementation; cancellation of an unstarted job correctly left results pending.
The separate fix publishes one cached result plus all registered callbacks under
one monitor, invoking callbacks outside it, and captures job construction in the
effect. All 54 effect tests pass. Cancellation semantics are unchanged.

The shared-once comparison excludes distinct existing contracts: AsyncTable keys
reset on removal; queue cancellation does not currently replay to late listeners;
`handleConsumed` signals EOF only; pool disposal claims precede physical cleanup.
Those differences must not disappear in a shared-helper migration.

Decision 6 remains explicit. Hub currently pulls on demand, drops offers to full
subscriber buffers, discards buffers at upstream EOF, and cancels subscribers
individually without canceling upstream. `Stream.sort` routes by rack demand with
one stored item per rack. `Stream.buffer` already pumps eagerly with enqueue
backpressure. Their terminal/error and ownership defects require characterization
and separate fixes; a different loss or cancellation policy is not a refactor.

## Browser prerequisite

Connection transport selection, long polling and backoff need behavior coverage
before representation changes. Existing private callback seams that establish
ordering can remain, but assertions on incidental flag layouts must become
assertions about emitted frames, DOM effects and lifecycle events first.
