# Session idle deadline comparison

The shared assembly deadline is not automatically the right model for idle
rearming. This follow-up closes the candidate-comparison gap left by the earlier
phase-two review. It preserves the existing timer winner and cancellation rules;
any independently confirmed resource-handoff race is a separate fix.

## Candidate table

| Candidate | Activity/rearm | Expiry and closure | Disposition |
| --- | --- | --- | --- |
| Guarded session | While admission is live, schedule the new job, publish its handle, then cancel the old handle synchronously. | The first terminal signal wins, including an old callback already dispatched before cancellation. Teardown denies admission and detaches/cancels the currently published idle handle. | Keep first-winner signal decisions in GuardedSessionMachine; scheduler/handle ownership stays in the interpreter. Late installation is being probed separately. |
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
and its fix are separate from this passing characterization unit. The guarded
handle-ownership disposition is not final until that fix is validated.
