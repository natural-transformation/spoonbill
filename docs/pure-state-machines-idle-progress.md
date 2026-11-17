# Session idle deadline comparison

The shared assembly deadline is not automatically the right model for idle
rearming. This follow-up closes the candidate-comparison gap left by the earlier
phase-two review. It preserves the existing timer winner and cancellation rules;
any independently confirmed resource-handoff race is a separate fix.

## Candidate table

| Candidate | Activity/rearm | Expiry and closure | Disposition |
| --- | --- | --- | --- |
| Guarded session | While admission is live, schedule the new job, publish its handle, then cancel the old handle synchronously. | The first terminal signal wins, including an old callback already dispatched before cancellation. Teardown denies admission and detaches/cancels the currently published idle handle; a late installation is claimed for cancellation only if still owned. | Retain GuardedSessionMachine's first-winner signal and interpreter-owned scheduler handles after the separate late-install fix below. |
| Legacy session | Cancel the previous job's effect, schedule its replacement, then publish the handle in the communication loop. | The first timeout/input-terminal callback wins. A timer callback cancels that loop's stream even if it loses terminal publication. Retirement does not itself cancel the current job; later delivery remains fenced to the old loop and cannot retire a successor. | Preserve the characterized communication-loop policy; it is not absolute-time completion arbitration. |
| Orphan long-polling topic | Cancel the prior cleanup, record the supplied activity time, then schedule cleanup. Subscription cancels cleanup. | A callback checks current subscription and elapsed time using the clock-before-activity-read order. Removal is fenced to the exact topic entry. Later activity may therefore make an old callback inert. | Existing OrphanTopicMachine and stale-entry regression already cover this distinct policy. |
| Request/message assembly deadline | Arm one current assembly ticket; completion compares the supplied clock with its absolute bound. | Completion may be rejected as late independently of when the physical timer task executes; stale ticket callbacks are ignored. | Shared DeadlineMachine already adopted; do not impose its ticket/absolute-completion rules on the session idle callbacks. |

The legacy current-job behavior above is a compatibility observation, not a
claim that every cancellation policy is interchangeable. In particular, these
cases deliberately fire a canceled job to model an already-dispatched callback;
they do not assume cancellation can recall one that has begun.

## Deterministic characterization

An optional internal `IdleTiming` seam preserves the original synchronous guarded
scheduler call and effectful legacy scheduler call separately. Without the seam,
the same Scheduler methods receive the same duration and by-name job. Mandatory
operation bounds still use their independent RequiredTiming seam.
Managed-JDK javap inspection confirms both seam methods forward their existing
Function0 argument directly (`aload_1` before the scheduler call), without an
invokedynamic/new job thunk in those methods. This is a representation check,
not a throughput claim.

Six new public session-lifetime cases passed without changing production idle
policy. Each mode pins rearm order, an already-dispatched canceled callback winning
once, and an old terminal callback being unable to retire a new attachment at the
same Qsid. They also record the different current-job cancellation behavior at
teardown. All 24 tests passed through the repository Nix flake:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *SessionIdleTimingSpec *SessionsRequiredSpec *SessionLifetimeSpec *SessionsServiceSpec'
```

No case sleeps for a deadline. The log is
`/tmp/spoonbill-session-idle-characterization.log`. A separate late-install probe
reproduced an uncanceled handle arriving after guarded teardown; that red probe
and its fix are separate from this passing characterization unit.

## Confirmed late-install ownership race

The guarded path checked live admission, called the scheduler, then installed
the returned handle. A deterministic callback closed input and completed teardown
between scheduling and handle publication. The guard and extension were already
destroyed, but the returned job had zero cancellation calls. The permanent
regression failed before correction (`/tmp/spoonbill-session-idle-install-before2.log`).
The first probe attempt used Queue.cancel instead of Queue.stream.cancel; that
test-only compile error was corrected before the behavioral result was recorded.

The fix keeps the same Some handle allocation and rearm order. After installation
and prior-handle cancellation, denied admission attempts to take that exact Some
with compare-and-set. Only the successful owner cancels it. If teardown or a later
rearm already took the handle, that owner keeps cancellation; no double cancel is
introduced. This does not invalidate an already-dispatched callback or substitute
absolute-time/ticket semantics. The legacy implementation is unchanged.

Both the late-return race and the competing teardown-after-publication case pass,
together with all six original rearming characterizations. Nix validation passed
35 runtime cases and 16 native-IO cases:

```
spoonbill/testOnly *SessionIdle*Spec *SessionsRequiredSpec *SessionLifetimeSpec *SessionsServiceSpec *Guarded*Spec
ce3/testOnly *GuardedOpenCancellationSpec *Extension*Spec
```

The log is `/tmp/spoonbill-session-idle-fixed.log`. New tests were formatted with
exact module-relative scalafmtOnly paths; whitespace checks passed. The final
disposition is **retain the distinct characterized idle policies**, with the
guarded resource-handoff defect fixed separately. Shared lifecycle/first-winner
state already supplies the terminal fence; physical scheduler handles remain in
their owning interpreters. No further idle-contract decision is left open by
this comparison, and no equivalence with the assembly deadline is claimed.
