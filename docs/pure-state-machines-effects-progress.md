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
Subsequent counter audit invalidated reliance on the contended allocation ratio:
the old fixture sampled after thread termination, where the JDK global counter
can undercount. Those rows remain provisional; roundtrip/batch main-thread rows
are unaffected. A live-worker/self-counter/control fixture and fresh contended
comparison replace that claim below; no old contended allocation claim is used as proof.
The corrected [six-trial contended comparison](../misc/queue-state-benchmark/evidence/2026-11-17-live-contended/)
is now complete: throughput ratio 1.0167 and allocation ratio 0.9561, with all
ten owned threads alive and global totals only 232–264 bytes above the sum of
measured thread self counters. Every escaping control and delivery check passed.
All six new and 18 original rows remain retained. Three analyzer regressions
reject counter underflow and mixed protocols; integrated source hashes pin
reproducible baseline/candidate revisions. No significance claim is made.
The baseline is `57d7a8b4`, after the separate waiter fixes. The exact measured
machine source is retained; subsequent alignment-only formatting is recorded in
provenance. The packaged analyzer reproduces the original summary byte-for-byte.
Full validation after the AsyncTable integration follow-up passed 72 effect and
819 runtime tests; purity, shell syntax, targeted formatting and diff checks pass.

The late `Queue.cancelSignal` question is resolved as a confirmed liveness defect,
not an intended difference: FS2 conversion starts its producer asynchronously and
can return the queue stream before `interruptWhen` registers its listener. Early
consumer cancellation stranded that producer. Both a direct Queue regression and
the real FS2 adapter with a gated producer failed before the fix (the latter
terminated only after a second cancel in test cleanup). Explicit stream cancellation
now publishes/replays the shared completion latch; ordinary `close` still does not
signal cancellation. The change is separate from the representation benchmark.
All 73 effect tests, four FS2-CE3 tests and 819 runtime tests pass, along with the
purity guard. The first manual-executor FS2 harness stalled during construction;
its captured thread dump identified the startup acknowledgment dependency, and
the permanent test uses bounded startup/registration gates instead.

`AsyncResourcePool.use` stranded its loan when the entered callback failed or
threw, exhausting a single-resource pool permanently. Both new regressions failed
before the fix; `use` now applies the existing effect guarantee to callback
evaluation and return. All 75 effect tests pass. This does not change the separate
acquisition/disposal protocols or make repeated `Borrow.discard` join cleanup.

`AsyncResourcePool.onDispose` discarded a delayed registration effect inside its
promise constructor: strict Future ran it incidentally, while IO never registered
the observer. The permanent IO regression failed before the one-line fix and now
passes. Two additional characterizations passed before the fix and remain unchanged:
late disposal observers see the claim before earlier observers see physical cleanup,
and repeated discard/give do not join an in-flight retirement or release capacity
early. All 77 effect tests and 14 selected cats-effect tests pass.

`AsyncResourcePool.cleanup` closed idle resources directly, bypassing their
once-only retirement hook. A close notification delivered after cleanup completed
decremented capacity a second time (to -1 with no other loan, or to 0 with one
still borrowed). Both new regressions failed before the one-line fix: cleanup now
uses the already-established retirement hook. All 79 effect tests pass, including
late notifications, discard, creation failure and concurrent pool use.

Pool ownership also depended on resource value equality and identity hashes.
Two permanent regressions failed: closing an idle value removed another distinct
but equal value, and two allocated leases sharing one object retired only one
capacity reservation. Each successful allocation now receives an unbounded BigInt
id from the same atomic pool value. Borrow, return, retirement and idle removal
use that id; retirement hooks are immutable entries. The public diagnostic
`PoolState`/`PoolItem` shape is preserved, with hashes retained only as diagnostic
values, never ownership keys. All 81 effect and 54 HTTP-client tests pass; the final
focused pool run passes 24 tests after removing the obsolete private diagnostic
closure. Existing pool and client specs are unchanged.

One final pool retention defect was confirmed before declaring the item retained:
closing an already-returned idle resource kept a retired-loan marker although no
loan could ever return to remove it. The permanent 100-cycle regression failed
on its first close. Idle closure now drops its entry without a return marker;
closed resources still borrowed retain their marker until return. All 82 effect
and 54 HTTP-client tests pass after this separate fix.

The acquisition-cancellation audit found a further confirmed managed-use leak:
with the actual CE3 3.3.12 adapter, cancellation completed while a use was waiting
for capacity, but its callback stayed queued and consumed the next loan. A late
factory result was lost in the same way. A private per-use ownership scope now
records an assigned loan before publishing the native callback, encloses acquisition
and user work in the finalizer, removes canceled waiters, and returns late loans.
It does not mask capacity waiting, fork user work, or add a public API. Four IO
regressions pass, including a loan held immediately before native publication and
reused effects with caller IOLocal state. The waiting/factory probes failed the
previous implementation; the exact publication probe also failed the original
pre-scope pool. Full effect82 and HTTP-client54 tests pass with this fix.

Final pool retention remains pending the caller handoff audit: public `borrow`
is still the explicit loan primitive, and arbitrary caller continuations have no
pool-owned adoption scope. The transport audit reproduced a canceled queued
HttpClient acquisition separately. Successful response headers transfer a loan
to the streaming body, so that caller requires a distinct adoption protocol;
managed `use` cannot replace it by returning the loan at header completion.

The internal `borrowAndTransfer` helper now reuses that acquisition scope with
an explicit, bounded contract: abort cancel/error before the supplied task
succeeds; transfer the loan to the task's successful result owner. It preserves
caller context and cancelable capacity waiting and adds no public caller API or
Effect capability. Abort must use the loan's guarded discard/give, not mutate a
possibly already-returned resource unconditionally. Tests cover strict task
construction failure, already-returned loans, cancellation inside the task and
before native publication, caller IOLocal state, and retention until the result
owner returns the loan. Validation passes 85 effect, eight pool-IO and 54
HTTP-client tests; real HTTP integration is owned by the transport workstream.

This does **not** claim sealed caller delivery. A controlled CE3 probe observed
success remain success when cancellation arrived during its success finalizer;
the coordinator's CE2, Monix and ZIO2 controls instead demonstrated late
cancellation after a success-finalizer observation. Those counterexamples rule
out a universal map-marker/default-outcome primitive. Returned-resource callers
must still bracket/adopt ownership beyond the supplied task-success boundary.
No new `guaranteeOutcome` capability or uncancelable capacity-wait policy was added.
The [native handoff probes and limitations](../misc/effect-handoff-probes/README.md)
retain the backend-specific evidence for that boundary.

The shared-once comparison excludes distinct existing contracts: AsyncTable keys
reset on removal; `handleConsumed` signals EOF only; pool disposal claims precede
physical cleanup. Queue cancellation joins the shared latch after the separate
confirmed late-listener fix above.
Those differences must not disappear in a shared-helper migration.

Decision 6 remains explicit. Hub currently pulls on demand, drops offers to full
subscriber buffers, discards buffers at upstream EOF, and cancels subscribers
individually without canceling upstream. `Stream.sort` routes by rack demand with
one stored item per rack. `Stream.buffer` already pumps eagerly with enqueue
backpressure. Their terminal/error and ownership defects require characterization
and separate fixes; a different loss or cancellation policy is not a refactor.

The sorted-stream EOF path invoked null callbacks for racks that had never
pulled. Both an empty parent and cancellation completing a parent's pending read
with EOF reproduced the exception. Termination now detaches each registered
callback and skips absent callbacks. Both permanent regressions pass; rack
routing, buffering and upstream cancellation policy are unchanged.

Hub's upstream read failure (including cancel-induced failure) stranded every
non-driving subscriber, and EOF allowed later pulls to read upstream again.
The three regressions failed before the fix. Terminal publication now closes
admission and settles the registered queues with EOF or the same failure;
registration and terminal snapshots share one monitor. Synchronous upstream
construction failures are covered too. Subscriber cancellation still closes
only that subscriber, and overflow/EOF buffering policy is unchanged. All four
Hub regressions and the full 96 effect tests pass. Native cancellation of a
driving pull, distinct from an upstream failure, remains in the operator
ownership contract audit; this unit does not claim to settle that policy.

The native cancellation review is now pinned by a controlled-worker CE3 case:
a canceled driving read can leave its actual upstream callback alive. Releasing
the Hub pull owner in a cancellation finalizer then allowed a second concurrent
upstream read. The characterization passes the original pre-terminal-fix source,
fails that finalizer variant with two reads, and passes success/error-only owner
release. All four terminal cases plus the native Hub and consumption cases pass.
This preserves the original native-cancellation ownership boundary; it does not
claim that canceled-driver sibling progress is solved without an operator contract.

## Browser prerequisite

Connection transport selection, long polling and backoff need behavior coverage
before representation changes. Existing private callback seams that establish
ordering can remain, but assertions on incidental flag layouts must become
assertions about emitted frames, DOM effects and lifecycle events first.

`misc/connection-lifecycle-tests.mjs` adds four public-behavior characterizations
against the unchanged client: legacy long-poll subscribe/renew/publish requests,
guarded refusal of long polling, websocket reopen lifecycle events, and capped
guarded reconnect backoff with readiness/reset behavior. The fixture drives
public `connect`, `send`, `disconnect`, `applicationReady` and transport events;
it does not read or call private connection members. Nix Node validation passes
all four. Private-field assertions in the older client suites remain to migrate
before their represented state is changed.

The first public-behavior regression found that legacy `disconnect(false)` left a
pending reconnect timer alive, opening a websocket after permanent disconnect.
The new case failed on the unchanged client. Pending attempts are now canceled
for legacy and guarded connections alike; transport selection and ordinary
reconnect behavior are unchanged. All 72 connection, redesign, sensitive,
protocol and replacement-bootstrap Node tests pass through Nix.

An old websocket's delayed Blob decoder could clear the new socket's shared
processing marker. A later frame then overtook the new socket's first decoding
frame. The public-event regression failed with that exact order. Processing is
now owned by each physical socket; only that owner clears its current shared
close-wait marker, and stale socket messages cannot start a new drain. All 73
browser lifecycle, redesign, sensitive, protocol and bootstrap tests pass.

Two further decoder regressions failed on the existing client: a rejected
Blob.text() left the close waiter pending and emitted an unhandled rejection;
the FileReader fallback dispatched later frames before its read completed.
The drain now observes decoding failure, closes the affected current transport
through the ordinary error/reconnect lifecycle, and always releases its waiter.
Legacy FileReader completion, error and abort are awaited by the same drain.
Both permanent public-event regressions and all 75 browser tests pass under Nix.
