# WebSocket lifecycle validation

This records the implementation prepared for 3.0.0. The 2.0.0 baseline is
`0c6df154a185815d187b4f5d04aad3aab0570f8b`.

## Review and fixes

The response API now distinguishes live duplex sessions from finite terminal
output. Session startup returns an explicit attached/reload result. Adapters own
transport lifetime and dispose responses on failure, termination, or abandoned
setup without inferring intent from cancellation timing or frame contents.

Takeover review additionally fixed:

- Interrupted cleanup owners leaving concurrent release callers waiting forever.
  Cleanup and result publication use the effect runtime's cancellation mask while
  retaining its execution context.
- Reentrant standalone teardown, input reads stranded by physical-close ordering,
  and graceful Close frames arriving while application output is pending.
- Setup results arriving after HTTP timeout, including preserving custom timeout
  responses and bounding cleanup when HTTP timeouts are disabled.
- Retention of close observers after rejected standalone upgrades.
- Redundant frontend-output cancellation after guarded session cleanup.

Setup ownership is bounded and transferred explicitly in all shipped adapters.
The bound is configurable and stops at attachment; it is not a frame-delivery
delay or a reconnect workaround. See the [migration guide](migration-3.0.md).

## Functional checks

The full CI-equivalent JVM command passed locally with disposable PostgreSQL:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

Client and comparator checks passed, 61 tests:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs \
  misc/performance-regression-tests.mjs
```

The final standalone close-ordering additions passed a separate targeted rerun:
`http/test` (30), `standalone/test` (21), and a ZIO-backed standalone setup probe
(2). GitHub CI, including the Linux native-browser job, remains the merge check.

## Performance evidence

The reproducible fixtures and runners are in
[misc/websocket-performance](../misc/websocket-performance/README.md). They use
the same workload source with narrow response-API adapters for 2.0.0 and 3.0.0.
Measurements retain artifact/fixture/flake hashes, matched JVM settings, completed
work assertions, balanced process order, and baseline/baseline calibration.

The first calibrated guarded-session comparison used 30 independent pairs,
2,000 warmup operations and 2,000 measured operations per JVM. Every operation
verified guard closure, absent application, and zero active inputs/guards.
Median latency was essentially unchanged, mean allocation per completed operation
was about 0.16% lower, and latency/CPU/throughput confidence intervals included
zero change. Under the plan's strict zero-slowdown criterion that result was
**inconclusive**, not a performance pass.

The initial transport comparison reported single-connection regressions.
Diagnostics subsequently found JIT compilation inside the measurement window:
warmup and measurement used different loop bodies. Those records are retained as
diagnostic evidence, not used to certify steady-state performance. Both fixtures
now warm the same timed loop, collect JIT/GC counters, and use longer windows. The
standalone output mapper also caches its callback per connection and keeps the
normal pull path small. Corrected calibrated comparisons are pending.

These checks do not prove performance for all host applications, networks, effect
runtimes, or idle-connection populations. A passing functional test is not
performance evidence. Do not convert missing or inconclusive measurements into
a no-regression claim.

## Release

The 2.0.0 POM is present on Maven Central and its GitHub publishing workflow
completed successfully. Sonatype's [immutability policy](https://central.sonatype.org/faq/can-i-change-a-component/)
prevents replacing that published version. Keep 2.0.0 available and use 3.0.0 for
the breaking WebSocket/Effect API change. Publication occurs only after the
release readiness decision and green CI.
