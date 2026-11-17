# Application lifecycle and render cycle

## Confirmed startup defects

Three public-behavior probes failed before the fix: repeated and concurrent
`initialize` calls rendered into an already finalized document, and initialization
after destruction succeeded. The failures are retained in
`/tmp/spoonbill-application-lifecycle-before.log`. Starting another render also
risks another set of consumers for queues whose application contract is serial.

Startup now has one owner and one shared completion. Repeated calls join or
replay that completion. A failed startup stays failed; retry requires a fresh
instance, since a partially started instance cannot safely restart its consumers.
Destruction rejects new startup, settles joiners and fences delayed authorization,
render publication, readiness and timer start. Already-admitted work may overlap
destruction; no lock is held across an application effect.

The owning caller's native cancellation publishes failure for joiners. Its
finalizer is installed before it claims startup, and a per-evaluation ownership
flag prevents cancellation of a joining caller from settling another caller's
startup. Independent review found and corrected a publication race: destruction
could win the shared completion between the owner's state change and result
publication. The owner now observes that same shared completion on both success
and failure, so it cannot report a different outcome from its joiners.

The first post-fix Nix run passed 56 runtime and one native IO test. The integrated
recheck after the publication-race correction selects:

```
spoonbill/testOnly *ApplicationLifecycleSpec *RenderLifecycleSpec *TimerApplicationSpec *SessionsServiceSpec
ce3/testOnly *ApplicationInitializationIOSpec *HttpClientBorrowCancellationIOSpec *AsyncResourcePool*Spec
standalone/testOnly *SetupInput*Spec *SetupDeadlineSpec
```

All selections passed through `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix
develop --no-write-lock-file --command sbt --batch --supershell=false
-Dsbt.server.autostart=false`. Logs are
`/tmp/spoonbill-application-lifecycle-after.log` and
`/tmp/spoonbill-rollout-integration-10.log`. This is the defect fix; pure lifecycle
extraction and the final render-cycle disposition are the next unit.

## Pure startup decisions and retained render cycle

`ApplicationLifecycleMachine` now owns the five startup states and pure decisions
to start, join, reject, publish completion or remain stopped. `StateCell` commits
those decisions; the interpreter still owns the shared result and per-caller
execution claim, with callbacks and application effects outside state updates.
The unchanged startup, rendering, timer and session selection passed 49 tests,
and the native IO cancellation case passed again. The log is
`/tmp/spoonbill-application-model-after.log`; the source-purity guard passes.

The render cycle is **retained** under rule 7. Its accepted-candidate fact is
already one boolean in one place, and initialization/state renders are serialized.
The startup fix restores that serialization for repeated initialization. Existing
`RenderLifecycleSpec` cases reject losing the accepted DOM after a partial render
failure in both ordinary and recovery modes. `TimerApplicationSpec` pins rejected
publication and accepted timer retention after cleanup failure. A new frame-level
case also verifies that, after publication succeeds and obsolete-binding cleanup
throws, rendering the same document produces an empty patch against the accepted
baseline. All 28 timer/application cases passed in
`/tmp/spoonbill-render-retain-after.log`. No confirmed defect remains in that
two-state render protocol; no extra state object or per-render event allocation
was introduced for it.

Browser input streams remain frontend-owned. Both guarded and legacy session
teardown close the frontend before destroying the application; the application
owns its state queue and component cleanup. The separate frontend close and Hub
terminal-propagation work must preserve that shutdown chain. These two
ApplicationInstance inventory rows are complete; the remaining Frontend,
ComponentInstance and SensitiveRuntime rows are separate work.
