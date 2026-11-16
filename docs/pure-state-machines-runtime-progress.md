# Runtime and session lifecycle rollout

This records the phase-3/4 audit and individual validated units. It is not a
claim that the full ten-phase plan is complete. The shared protocol candidate
comparison and phase-2 evidence are in `pure-state-machines-shared-progress.md`.

## Initial phase-3 dispositions

| Item | Current evidence | Disposition |
| --- | --- | --- |
| `ViewSnapshotSession` | Initialization is one Empty/Starting/Ready(revision) enum in one atomic reference. Tests cover failed restore retry, concurrent/repeated initialization, revision progression, identity reset and incompatible schema preservation. | Retain the explicit state model, subject to rerunning those tests; do not relocate projection/store effect order merely to add a Machine filename. |
| `SerialLane` ownership and per-call settlement | Each protocol is already one atomic integer with named states and a complete local transition loop. Original/source-backed specs cover order, failure, refused execution, stack safety, drain and racing writers/close. It is also on the accepted extension performance path. | Retain these explicit atomic protocols, subject to rerunning their suites; no identified defect warrants replacing their synchronization. |
| `DelayInstance` | Finished status and installed cancellation handle are separate publications. Existing tests intentionally distinguish cancellation from completion. Component creation/start and removal/cancel share `miscLock`. | Extract installation/completion/cancel decisions; preserve cancellation not marking a delay finished. Characterize inline scheduling and schedule-construction failure first. |
| `MessagingService` orphan topic | Subscription, activity and timer ownership are separate atomics. Existing timing seam supports deterministic expiry races. Removal currently uses the session key without entry identity. | Investigate stale-entry removal before extracting the lifecycle; any confirmed fix is a separate commit. |
| `ComponentOwnership` | Owner readiness, predecessor settlement, admitted leases, retirement, cleanup and baselines use multiple flags under one lock. Strong handoff/baseline/drain specs exist. | Extract ownership decisions while retaining resource tables, admission lock and effect ordering. |
| `TimerRuntime` render/start/outbox | Timer slots already have a pure machine, but render staging, deferred wakes, startup enablement and report suppression remain separate state. | Extract metadata decisions without moving reducer/target/cancel handles into model state. |
| `BoundedPresentationStore` | Entry phases exist, but entries/history/monotonic observed time/closure are separate mutable fields. | Extract bounded ownership and expiry decisions with explicit clock/identity inputs; preserve manager/snapshot fences. |
| `BrowserCoordinatorGate` | Keyed FIFO read/write dispatch plus close-result joining; not equivalent to a simple count gate. | Own scheduling machine with callback IDs and explicit time input; keep current effect ownership/cancellation boundaries. |

## Phase-4 prerequisites

`SessionsService.required` arbitrates an operation outcome against a scheduled
expiry; it does not compare operation completion against an absolute clock. It
therefore must not be silently replaced with the assembly `DeadlineMachine`,
whose Complete event performs a timely/late comparison. Add a deterministic
scheduler seam to its existing implementation before extracting that race.

Guarded teardown must keep extension stop, component admission retirement,
connection closure, admitted-write drain, application/guard/extension destruction,
storage retirement and attachment-fence removal in their current order. A
nonterminating admitted-write drain must continue preventing unsafe local
replacement. A repeated cleanup call currently joins or returns according to its
specific interpreter contract; a generic completion abstraction must not change
that behavior without a separate bug investigation.

## Confirmed topic-removal defect, fixed before extraction

The deterministic timing seam reproduced a stale owner deleting its successor:
an orphan topic receives two publishes; its current deadline expires and removes
it; a replacement topic subscribes under the same Qsid; then an already-dispatched
canceled deadline from the old topic resumes. Both entries have the same key,
but only the old one satisfied that deadline. The original key-only removal
removed the replacement. The regression failed with expected true/actual false.

Both timer and queue-cancellation retirement now use
`longPollingTopics.remove(qsid, entry)`, fencing removal to the entry that owns the
callback. No timer or subscription lifecycle refactor is included in this fix.

Validation through the repository flake:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *MessagingServiceSpec'
```

Result: all 8 tests passed after the ownership fix. The new regression was run
first on original production code and failed at the final replacement-preservation
assertion. `git diff --check` passed.

## Legacy delay extraction and retained explicit protocols

`LegacyDelayMachine` names uninstalled/armed and finished-uninstalled/finished-armed
states. Installation, firing, schedule failure and cancellation are pure events.
The runtime pairs the state with its physical cancellation handle and invokes all
scheduler/application effects outside that small critical section. Cancellation
still invokes an available handle without marking completion; inline firing before
handle installation and scheduling failure retain their original outcomes.

The old-code characterizations passed all 6 `LegacyDelaySpec` tests before the
production refactor. After extraction, this selection passed 73 tests in six
suites, including the new exhaustive/generated delay decisions:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *LegacyDelay*Spec *TimerApplicationSpec *ViewSnapshotSessionSpec *SerialLaneSpec *SerialLaneSourceSpec'
```

The source-purity guard passes with eight machine files; scoped new-file formatting
and `git diff --check` pass. Only the nested `DelayInstance` region changed in
`ComponentInstance`; baseline/loader work is a separate phase-5 unit.

The same run closes the retain validation for `ViewSnapshotSession` and
`SerialLane`. Both satisfy the plan's retain criterion: one explicit state value
per protocol, strong tests rejecting wrong transitions, and no confirmed defect
in the reviewed protocols. Their implementation and effect/scheduling boundaries
remain unchanged. SerialLane's completion waiter table is already one atomic
optional value and is not expanded merely to adopt the shared latch helper.

## Deterministic mandatory-bound characterization

`SessionsService` now accepts an optional internal `RequiredTiming` scheduler;
without it, the existing ten-second `Scheduler.scheduleOnce` path is unchanged.
The existing `required` CAS arbitration and effect sequence were not refactored
in this seam unit. Its package-private visibility permits direct lifecycle tests.

Five deterministic cases pin success/failure cancellation and stale timeout,
expiry before operation completion, cleanup of late success, ignoring late
failure, inline expiry before handle publication, and scheduling failure without
constructing the operation. (Success/failure share one parameterized case.)
No test waits ten real seconds.

The seam plus unchanged session initialization/lifecycle suites passed 18 tests:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *SessionsRequiredSpec *SessionsServiceSpec *SessionInitializationContractSpec'
```

The test fixture uses the repository's standard execution context. The first test
compilation identified that missing implicit import; after adding it, the full
selection passed. This is evidence for a subsequent first-winner race extraction,
not permission to change timeout into an absolute elapsed-time check.

## Orphan topic subscription/activity extraction

After the separately committed stale-owner fix, `OrphanTopicMachine` now owns an
explicit Orphan/Subscribed state with the last activity timestamp. Subscribe and
Activity transitions are pure; the first subscription requests timer cancellation
and all later subscriptions are inert. A late publication can update activity
without reopening subscription. Expiry policy takes both time samples explicitly.

The interpreter keeps its existing cancel-then-schedule effect order and physical
timer handle. It also preserves expiry's subscription-check, clock-sample,
latest-activity-read order. Registry membership remains independently fenced to
the concrete TopicEntry, as established by the preceding regression fix.

Validation through Nix passed 17 tests across the pure model, messaging and reload
integration suites:

```
spoonbill/testOnly *OrphanTopicMachineSpec *MessagingServiceSpec *MissingViewReloadSpec *InvalidDevRenderContextSpec
```

The command ran through `nix develop --no-write-lock-file --command sbt --batch
--supershell=false -Dsbt.server.autostart=false`, with `JAVA_HOME` unset and
`SBT_NATIVE_CLIENT=false`, as above. The purity guard reports nine machines and no
violations; scoped new-file formatting and diff whitespace checks pass.
