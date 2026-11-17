# Shared protocol rollout

This records phase-2 candidate comparisons and validated units. It does not mark
later lifecycle, security, transport, effect, browser or testkit phases complete.
The owner authorized one coherent commit per unit in the existing PR. Effect
ordering, cancellation and scheduling remain interpreter responsibilities.

## Setup ownership

The Akka and Pekko `UpgradeOwnership` implementations were literal duplicates.
`zio.http.WebSocketSetup` has the same ownership transitions with different effect
policies. Their common table is now `internal.SetupOwnershipMachine`:

| Event | Waiting | Prepared | Attached | Abandoned |
| --- | --- | --- | --- | --- |
| Prepare | Retain incoming release; Prepared | Release incoming; unchanged | Release incoming; unchanged | Release incoming; unchanged |
| Attach | Refuse; unchanged | Transfer retained ownership; Attached | Refuse; unchanged | Refuse; unchanged |
| Abandon | Release input; Abandoned | Release input and retained response; Abandoned | No ownership cleanup | No ownership cleanup |

The interpreter keeps release thunks paired with machine state under the original
monitor. Akka/Pekko still throw on refused attachment and cancel the timer inside
the monitor; zio-http still returns false and interrupts its fiber outside the
monitor inside its original uninterruptible effect. All input/response release
ordering, failure recovery and late timer installation handling stay unchanged.

**Excluded from this shared machine:** standalone `SetupDeadline` has
`Pending(release?)`, `Attached(release)`, `Expired(release?)`, and `Finished`.
It retains cleanup ownership after attachment, repeated attach succeeds, and a
physical timer expiry defers cleanup to transport termination. Sharing the
four-state model would change those contracts. Its own transport lifecycle is a
later unit, not a missing copy of this machine.

Missing duplicate preparation and premature/repeated attach characterizations
were added before production changes. They passed on the original wrappers:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'pekko/testOnly *PekkoUpgradeOwnershipSpec' \
  'akka/testOnly *AkkaUpgradeOwnershipSpec' \
  'zioHttp/testOnly *WebSocketSetupSpec'
```

Result: 7 Pekko, 7 Akka and 4 zio-http tests passed. After extraction the same
suites passed, together with 2 `spoonbill/testOnly *SetupOwnershipMachineSpec`
cases covering the exhaustive transition table and generated ownership histories.

## Drain gate candidates

| Candidate | Admission and settlement | Disposition |
| --- | --- | --- |
| `GuardedStateManager.writes` | Unbounded writes; close rejects new writes and joins admitted writes; asynchronous waiters | Share count/close decisions; keep write ordering and callbacks in interpreter |
| `SocketCloseDispatcher` | Bounded reservations; stop rejects new work; executor shutdown when stopped and count reaches zero | Share bounded count/close decisions; reservation's two independent completion bits remain separate |
| `InMemoryBrowserPersistence` admission | Bounded operations; close rejects new work and blocks until zero before destroying provider state | Share count/close decisions without changing monitor wait/notify or transaction order |
| `JdbcBrowserPersistence` admission | Independently bounded ordinary, cleanup and check lanes; all stop together; close also settles owned views and replays cleanup result | Only per-lane count decisions are candidates; require lane-budget and repeated-close characterization before adoption |
| `ServerSocket.ActiveConnections` | `started` increments even after `stopped`; drain replays first failure | Different admission/error contract; own lifecycle machine |
| `BrowserCoordinatorGate` | FIFO readers/writers, per-key read exclusion, bounded pending turns, cleanup/close join | Not a count gate; own keyed scheduling lifecycle |

## Once candidates

`ComponentOwnership.Signal` and `SerialLane`'s close waiters have a replaying
completion-latch protocol, but FIFO versus LIFO waiter notification belongs to
their interpreters. They are candidates for a shared completion decision model.
`WebSocketResponse.releaseOnce` already has one explicit Idle/Running/Done state
and tests for construction, repeated execution, concurrent joining and effect
context. Retain this clear implementation rather than forcing it into a latch
that lacks execution ownership.

The effect-module and testkit audit owns the other candidates. `AsyncTable` allows
removal/recreation and has duplicate-put policies. `Queue.cancelSignal` initially
did not replay a prior cancellation to a new waiter; the effect-unit audit
confirmed this as a defect through a late-listener test and a real FS2 consumer.
The separate fix `d60a8352` adopts `CompletionLatchMachine` for explicit cancellation
while retaining ordinary queue close as a different event. `Scheduler.JobHandler`
was corrected separately (`d1fe59dd`) and adopted the completion latch in
`af05366f`. `Stream.handleConsumed`'s EOF-only/single-observer defects were then
confirmed and fixed separately (`04e1b92e`): all terminal outcomes are cached and
replayed, EOF succeeds and explicit cancellation fails. It adopts the shared
latch in `921f47a6`. Its legacy-session failure-to-retirement caller correction
remains a separately validated companion unit. Pool disposal distinguishes
claiming disposal from settling cleanup, with nonjoining repeated discard
characterized separately. Those contracts are not interchangeable.

The effect/testkit units also fixed AsyncTable failure fanout and explicit
publication (`23fa583f`, `b3e3e4e0`) before its Waiting/Settled extraction
(`666b182b`); this remains a resettable per-key protocol. RecoveryGates fixed stale
rearm (`4ccd1cd0`) before its four-phase model (`c194a85a`); arrival and resume
retain distinct meanings. These are downstream dispositions, not additional
shared-latch candidates. Pool disposal is retained in `92e19cfe` as an explicit
enum with one atomic PoolData/allocation-identity state and strong ownership
tests; raw-borrow/task-success boundary limitations remain documented by that
effect unit. These dispositions were reconciled with the effect/testkit owner;
they do not imply that unrelated browser/effect inventory rows are complete.

## Pending registry candidates

`OwnedBrowserRequests` and `SensitiveDisclosureScope` both need bounded key
ownership, take-once removal, permanent close admission and cancellation handles
installed after retirement. Their error mapping and effect order differ and
must remain in interpreters. Their per-entry late-cancellation protocol is a
separate comparison from their registry admission protocol.

`SensitiveRuntime` additionally owns region exclusivity, presentation phases,
three timer classes, plaintext retirement and clear-before-callback ordering.
Keep that security lifecycle out of a generic registry replacement. Frontend
user actions and downloads have different bounds, expiration and transfer rules;
they stay with the Frontend lifecycle audit rather than being treated as equivalent
pending-request registries.

## Completed drain admission unit

`effect.DrainGateMachine` now supplies compact admission/count/close decisions to
`SocketCloseDispatcher`, `GuardedStateManager.writes`, and memory-provider
admission. It names Open, Closing and Drained explicitly. Capacity is optional:
the guarded writer has no configured limit; the other two retain their existing
limits. Refusal distinguishes closure from capacity exhaustion. Repeated close
is inert in the model, while each interpreter retains its existing repeated
completion/shutdown behavior. Invalid extra finish events leave zero unchanged.

The socket monitor and the provider's wait/notify monitor remain in place. The
writer retains its original CAS plus waiter list and completion guard. No user
effects moved into an atomic section or changed order. The reservation's two-bit
protocol and JDBC admission are not part of this unit.

Additional old-code characterizations cover dispatching an already reserved
socket notification after repeated admission stop, simultaneous write drains
when the admitted write fails, and permanent memory-provider closure. They
passed before extraction (4 HTTP tests, 51 runtime/provider tests). Afterward:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'effect/testOnly *DrainGateMachineSpec' \
  'http/testOnly *SocketCloseDispatcherSpec' \
  'spoonbill/testOnly *GuardsSpec *InMemoryBrowserPersistenceSpec *ViewSnapshotSessionSpec'
```

Result: 2 pure model tests, 4 socket tests and 61 guarded-write/provider/snapshot
tests passed. No performance claim is made for this admission-only extraction.

## Shared completion-latch decisions

`effect.CompletionLatchMachine` defines Pending/Completed with four decisions:
Observe registers while pending and replays after completion; the first Complete
publishes, and subsequent Complete events are ignored. The interpreter must pair
the state with its outcome and callback handles in the same atomic section.
Callbacks and their order are not stored in the machine.

This matches `ComponentOwnership.Signal` (FIFO callbacks), `SerialLane` close
waiters (LIFO callbacks), and the intended scheduler result latch after its
separately characterized lost-waiter defect is fixed. Their interpreter adoption
is owned by the corresponding lifecycle/effect units. It does not model claiming
an effect execution, and therefore does not replace `releaseOnce`.

Validation: `effect/testOnly *CompletionLatchMachineSpec` passes both exhaustive
and generated-history cases. The observation event is named `Observe` because
the source-purity guard reserves the token `Await` for effect execution. No
effect execution API is used by this machine.

## Completed JDBC lane admission unit

JDBC's ordinary, reserved cleanup and check lanes now each use the shared drain
state. One `AdmissionLanes` value is still changed under the existing lifecycle
monitor. The ordinary and cleanup budgets remain `pendingJobs`; concurrent checks
retain `maxConcurrentChecks + 1` (otherwise `pendingJobs`). Closing seals all
three in one critical section, and waiters are detached only after all drain.
The provider's separate once-only owned-view cleanup, failure/result replay,
clock renewal shutdown, transactions and authorization order are unchanged.

A deterministic public-API characterization filled each lane through a controlled
transaction executor before the refactor. It verifies independent budgets,
reserved cleanup admission, complete three-lane draining, concurrent/repeated
close and post-close behavior. It passed against the original provider using an
owned disposable PostgreSQL instance.

After extraction the complete provider suite passed, 96 tests:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false \
  -Dsbt.server.autostart=false 'securityJdbc/testOnly *JdbcBrowserPersistenceSpec'
```

The script creates, runs and removes its own loopback-only test database; it does
not use a production database. The test characterization selects
`-- -z "independent lane budgets"` in the same suite. No DB effect moved across an
admission or cleanup boundary.

## Completed pending registry metadata unit

`internal.PendingRegistryMachine` now owns bounded key-to-token membership and
permanent admission closure for `OwnedBrowserRequests` and
`SensitiveDisclosureScope`. Tokens are empty identities supplied by interpreters;
no callbacks, cancellation thunks or sensitive payloads enter model state.

RPC registration rejects duplicates before capacity and atomic close detaches
all entries. Sensitive disclosure registration retains its previous Map.put
replacement policy (UUID generation normally supplies unique keys), and close
seals admission without clearing membership: each discard callback retires its
entry in the original order. The model makes those distinctions explicit rather
than normalizing them. Late timer installation still consults current ownership;
stale request callbacks cannot take a replacement owner of the same descriptor.

The interpreter's callback/cancellation handle handoff stays in place, including
cancellation-before-callback ordering and all existing exception isolation. This
unit does not move sensitive presentation, plaintext retirement or Frontend
request-family routing into a generic registry.

A new old-code characterization verifies duplicate-error priority at capacity,
same-key reuse and replay of an earlier expiry callback. Together with existing
RPC and disclosure-scope cases, 22 tests passed before extraction. Afterward:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *PendingRegistryMachineSpec *BrowserRpcLifecycleSpec *SensitiveDisclosureScopeSpec *SensitiveRuntime*Spec'
```

Result: 39 tests across five suites passed, including pure bounded-membership
histories and sensitive-runtime concurrency checks. The source-purity guard
passes with seven machine files. The first test compilation required qualifying
`PendingRegistryMachine.size` to avoid ScalaTest's unrelated `size` matcher;
production sources compiled on that attempt, and the corrected full selection
passed. Scoped new-file formatting and `git diff --check` also passed.

## Completed late-handle ownership unit

The metadata registry alone did not resolve `OwnedBrowserRequests.Entry`'s three
independent atomics. That remaining protocol now uses `effect.LateHandleMachine`:
Waiting/Installed/Finished with Install and Finish transitions. The RPC entry
pairs model state, callback and cancellation handle in one atomic Held value.
The first finish detaches both handles, cancels an installed timer and then
notifies; subsequent finish does nothing. A handle arriving after finish is
canceled outside the atomic section and never retained.

Sensitive disclosure entries use the same decisions with their cancellation
handle under the existing registry monitor. Lost key ownership means finished;
merely sealing registry admission does not, since close still discards individual
holders in its original order. Cancellation remains before request notification;
cancellation exceptions remain isolated during finish/release, while late-install
cancellation retains each interpreter's original exception handling.

Three additional characterizations pinned cancellation-before-notification even
when cancellation throws, synchronous expiry before the schedule call returns,
and a sensitive holder's late cancellation failure after close. The original
implementations passed 25 RPC/scope tests before extraction.

After extraction, `effect/testOnly *LateHandleMachineSpec` passed 2 exhaustive and
generated cases, and the RPC/scope/sensitive/pending selection passed 42 tests in
five suites through the repository Nix flake. The purity guard reports eleven
machines with no violations; scoped new-model formatting and diff whitespace
checks pass. This completes the audited metadata plus late-handle portions of
the two shared pending owners; it does not replace the separate sensitive
presentation lifecycle or Frontend user-action/download protocols.

## Measured compact drain follow-up

The initial `DrainGateMachine` enum allocated an Open/Closing wrapper per count
change. The accepted follow-up keeps the same explicit pure decisions in an
opaque Long, preserving the complete signed Int count and permanent close bit.
An authorized finish retains wrapping arithmetic, including through zero; the
interpreter's one-use ownership guard, not a zero-count heuristic, rejects a
duplicate release. This supersedes the initial model's zero-count saturation.
Capacity precedence, closure and drain notifications retain their existing
ordering. Hot unbounded admission uses one pure transition and primitive change
test, without rebuilding a result wrapper.

Three direct model laws, four dispatcher cases, runtime ownership/guard tests and
96 real PostgreSQL cases passed through Nix. The complete command/results and
final five-cell measured gate are recorded in the runtime progress notes and
`misc/ownership-cost/README.md`; all failed comparisons remain available. Provider
lanes remain independent and no callback or SQL effect moved across a lock.
