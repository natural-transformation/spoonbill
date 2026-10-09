# Collected measurements

`initial` retains the first paired collections, their zero-slowdown analyses, and
the short JIT diagnostic. Warmup and measurement originally used different loop
bodies. Compilation continued during measurement, so those timings are not used
to certify steady-state behavior. The diagnostic's `diagnostic` identity is not
an artifact hash and it is not acceptance data.

`corrected` uses the shared timed batch loop, longer warmup/measurement windows,
JIT/GC counters, and the output-mapper optimization. Both comparison and baseline
calibration contain 30 independent pairs per workload. Metadata records actual
classpath artifact-content hashes, common fixture hashes, flake lock, JVM options
and runtime. JVM compilation can still occur; the counters are retained rather
than suppressing or selectively discarding observations.

All echo payload checks and guarded-session resource assertions passed. No
corrected metric reports a calibrated regression. Timing confidence intervals
still span zero; the strict zero-slowdown analysis therefore returns
`inconclusive`, not `pass`. Transport allocation reductions pass that criterion.

These are localhost transport and serial guarded-lifecycle workloads on the
recorded Darwin/arm64/JDK21 environment. They do not establish all application,
platform, network, effect-runtime, or idle-heap performance. Core lifecycle counts
include complete disposal; transport teardown is excluded because the old
standalone teardown did not complete equivalent work.

See [the results summary](../../../docs/websocket-performance-results.md) for the
release-readiness interpretation and reproduction commands.
