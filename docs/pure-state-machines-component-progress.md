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

## Explicit baseline metadata

`ComponentBaselineMachine` uses cached states for Ready,
NeedsBaseline, initial loading, refresh of a ready/unready baseline, and suppressed
ready/unready loading. A ready baseline can have a refresh in flight; those facts
are not collapsed. Pure decisions admit a load, mark a written baseline or handle
failure using the owner's explicitly sampled openness. A closed-owner failure
does not clear an existing loading reservation. A successful write still makes
the baseline ready and clears loading.

One atomic metadata reference replaces the two independent volatile flags.
Captured-snapshot validation still belongs to ComponentOwnership and is queried
in its original short-circuit order. No storage, reducer, loader, reporting or
scheduling effect moves inside a metadata transition, and no new monitor is
introduced across owner or render locks.

Parameters remain in their separate volatile data slot, published at the same
points as the bugfixed interpreter. An opaque direct payload plus private Unseen
marker distinguishes absence without adding a Some or copied-state wrapper per
render. Equality and legitimate null parameter behavior match the original
comparison. Each render still publishes the actual supplied parameter reference.

The model and unchanged interpreter selection passed 70 tests across eight suites
through Nix, including the five new loader cases and existing ownership, timer,
application/render and legacy-delay cases:

```
spoonbill/testOnly *ComponentBaseline*Spec *ComponentOwnership*Spec *TimerApplicationSpec *ApplicationLifecycleSpec *RenderLifecycleSpec *LegacyDelaySpec
```

Four model cases cover ready refresh, open-owner retry, generated histories
against the original readiness/loading observations, and parameter/null equality.
New-model formatting and a final four-case model run passed; the purity guard
reports 33 models and zero violations in this aggregate. The cost fixture and
full comparison are a separate acceptance gate; no performance conclusion follows
from these correctness checks.

## Measured acceptance and scope

The matched comparison in `misc/component-cost` uses the corrected loader commit
as baseline, isolating metadata extraction. It measures real component traversal,
diff generation/serialization and buffer acceptance with stable/changing
parameters at one/32 children, plus a pending initial loader. Both variants share
the same read-only snapshot and serialized producer contract. Construction,
snapshot capture and asynchronous refresh completion are outside this fixture.

All 30 rows passed the observed 95% throughput/110% allocation median gates.
Throughput ratios were 0.999/0.996/1.003/0.988/0.983 and allocation ratios
1.000/1.000/1.000/1.000/1.029 in the documented cell order. All live-producer,
self-versus-global, strict reporter and stderr checks passed. The 2.9% allocation
increase in the pending-loader cell is reported explicitly; no byte-identical
whole-operation allocation claim is made. Exact sources, all rows, ranges,
known-allocation controls and the invalid initial fixture smoke are retained.

The invalid smoke exposed the already confirmed Stream.sort EOF null-waiter
defect when the direct fixture omitted app-level consumers. The corrected fixture
arms those consumers symmetrically outside measurement, as ApplicationInstance
does, and fails on unexpected reports; production comparisons are unchanged.
The separate effect rollout owns that terminal bugfix.

This completes the assigned ComponentInstance baseline/loader inventory row.
Component retirement/drain remains with ComponentOwnership; delay and timer
lifecycles retain their independently validated models. Parameter data and
application effects remain interpreter-owned. No broader phase completion or
full-application latency claim follows from this unit.
