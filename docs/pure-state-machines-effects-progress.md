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
Full runtime integration subsequently exposed an important existing factory
pattern: `SessionsService` can publish its own error before failing. Two new cases
(self-published failure and an external value racing factory failure) passed on
the original table but failed the initial caching fix with `AlreadyContainsKey`.
Failed factory outcomes now publish silently if a value/error already won, then
preserve the original factory error for their initiating caller. Successful
publication remains strict. All 72 effect and 819 runtime tests pass after this
follow-up; the previously failing `InvalidDevRenderContextSpec` is unchanged.

`Scheduler.JobHandler` lost earlier result waiters, failed to replay results when
a waiter was already registered, and stranded results when the job factory threw
synchronously. Three permanent controlled-promise regressions failed on the old
implementation; cancellation of an unstarted job correctly left results pending.
The separate fix publishes one cached result plus all registered callbacks under
one monitor, invoking callbacks outside it, and captures job construction in the
effect. All 54 effect tests pass. Cancellation semantics are unchanged.
After that fix, the job result adopts the shared `CompletionLatchMachine`:
registration, replay, first publication and duplicate completion match its table.
One immutable interpreter value pairs the pure state with result and waiter
handles; callback order stays LIFO and callbacks remain outside the monitor.
All 61 effect tests, including the shared model's two tests, pass after adoption.

`Scheduler.schedule` had three separately characterized cancellation failures:
cancel before the first pull did not close admission, cancel after a completed
tick invoked its callback twice, and cancel with two pending pulls stranded one.
All three regressions failed on the original implementation. The fix tracks each
pending tick under one monitor, detaches completion ownership once, and cancels a
handle installed after its tick was already consumed/canceled. Concurrent pulls
remain supported rather than being rejected; cancellation settles all of them.
All 64 effect tests pass. Timer execution and callbacks remain outside the monitor.
The subsequent `ScheduledStreamMachine` extraction makes open/closed admission
and pending ticket ownership explicit. Opaque empty ticket identities are supplied
by the interpreter; the machine never stores callbacks or timer handles. One
immutable held value pairs its state with the handle registry under the existing
monitor. Race-order tests prove that firing/cancellation partition pending tickets
and stale completion cannot consume another ticket. All 66 effect tests and the
purity guard pass. Both scheduler inventory protocols are now modeled.

`Queue.dequeueAll` cleared the buffer without notifying capacity waiters, so a
producer already blocked by a full buffer could remain stranded indefinitely.
Both single- and multiple-waiter regressions failed before the fix. Bulk drain now
atomically detaches those waiters with the drained buffer and publishes capacity
hints after its CAS; enqueue rechecks capacity normally. All 56 effect tests pass.
This fixes the backpressure edge without changing per-element queue decisions.

`Queue.stop` also retained already-completed pull waiters, so a second stop or
subsequent close completed their promises again and failed. The new regression
failed with `Promise already completed` while stop/drain order and failure-over-close
characterizations passed. Empty-buffer stop now detaches its notified waiters in
the same CAS. All 59 effect tests pass; normal draining and failure precedence stay.

The following Queue refactor separates `Open`/`Stopped`/`Closed` admission from
pull failure in `QueueMachine`. Compact operations return singleton decisions;
buffer data and callbacks remain in the existing atomic interpreter value. No
per-element event or transition-result allocation was introduced. Four runtime
characterizations passed before extraction: direct-handoff broadcast, stop versus
close, error precedence, and explicit cancellation versus close. The late-listener
replay question is investigated separately rather than declared intentional.

The [retained Queue comparison](../misc/queue-state-benchmark/evidence/2026-11-17/)
contains all 18 fresh-JVM rows: three trials per version/workload, three seconds
warmup and measurement, fixed 256 MiB G1 heap and eight active processors, with
other builds paused. Candidate/baseline median throughput ratios were 1.032
(roundtrip), 1.259 (batch), and 1.065 (four producers); allocation ratios were
0.944, 0.934, and 0.913. All count/order checks passed. The ranges are retained;
shared-machine observations do not establish an application-level speedup.
The baseline is `57d7a8b4`, after the separate waiter fixes. The exact measured
machine source is retained; subsequent alignment-only formatting is recorded in
provenance. The packaged analyzer reproduces the original summary byte-for-byte.
Full validation after the AsyncTable integration follow-up passed 72 effect and
819 runtime tests; purity, shell syntax, targeted formatting and diff checks pass.

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
