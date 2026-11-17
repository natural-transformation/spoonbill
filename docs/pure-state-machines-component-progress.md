# Component baseline and loader rollout

This is the phase-five ComponentInstance baseline/loader unit. Existing component
ownership, delay, timer, application startup and render-cycle contracts remain in
place. The pure metadata extraction follows a separately validated defect fix.

## Confirmed fresh-loader defects

On aggregate `a07387f8`, three renders while a fresh asynchronous component loader
was pending started that loader with parameters 0, 1 and 2. Reused-address loading
already admitted only one loader. A second public-behavior regression showed that
when the original parameters finished loading, `maybeUpdateState` was never
called with the latest parameters: loading placeholders had already advanced
`lastParameters`. Both cases failed before the fix. Retry after failed/thrown
loading and reused-address admission characterizations passed unchanged.

The fresh path now admits one loader while pending. Only rendering actual state
advances render parameters; the initial loader records the parameters that
produced its result so the next state render can offer any newer parameters to
`maybeUpdateState`. Its existing effect factory/start boundary is preserved.
Failure still reports the original `Unhandled error`, and admission is reset for
an open owner so a later render may retry. Synchronous effect-start refusal also
resets admission and propagates the same exception; an additional direct-component
probe caught that edge while implementing the admission fix. Ownership leases
still surround the actual write and notification; retirement rejects late results.

Validation through the repository Nix flake:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *ComponentBaselineSpec *ComponentOwnership*Spec *TimerApplicationSpec *ApplicationLifecycleSpec *RenderLifecycleSpec'
```

Result: 60 tests passed across six suites. The new spec covers five cases,
including returned failure versus a throwing loader factory and direct
synchronous startup refusal. New-spec formatting and whitespace checks passed.
Logs retain the original duplicate/missed-update failures, the refusal probe,
and final success under `/tmp/spoonbill-component-*`. This is the bugfix only;
explicit baseline/refresh/loader metadata and its cost validation remain next.
