# Canonical Future instance: current-runtime comparison

This is a scoped API cost comparison, **not a final aggregate comparison against
original main**. Both sides start at compact7 `5d71d676`: `baseline` means that
current revision, and `pilot` means the same source plus the exact closed Future
instance API changes. The inherited gate report's `main` field also names the
current `5d71d676` reference; it does not mean the original main branch.

The fixture explicitly selects `Effect.futureEffect`, so this complements the
[stream v3 comparison](../stream-operator-cost/v3/README.md), which exercises the
instrumented factory. The copied files in `fixture/` are byte-for-byte unchanged
from `misc/extension-contention`: no runtime, workload, counting, sampling or
lifecycle behavior is changed for this experiment.

New isolated worktrees and classpaths are used for both versions. None of the
frozen stream-reference class directories is rebuilt. Every classpath directory
class and local/dependency JAR is hashed before collection and checked afterward;
production sources, fixture/scripts and the exact candidate patch are retained.
Raw JFR recordings stay outside the repository because they can contain process
environment data; their hashes and local paths are retained.

Preparation and controls use the applicable repository `nix develop` environment:

```sh
bash misc/effect-instance-runtime-cost/fixture/prepare.sh BEFORE BEFORE_CP --runtime-only
bash misc/effect-instance-runtime-cost/fixture/prepare.sh AFTER AFTER_CP --runtime-only
bash misc/effect-instance-runtime-cost/fixture/run.sh BEFORE_CP AFTER_CP NEW_SMOKE_DIR 1 2
node misc/effect-instance-runtime-cost/fixture/analyze.mjs NEW_SMOKE_DIR 1
```

The eight smoke rows only verify correct delivery counts/order, zero histogram
overflow, complete teardown, and the live-producer allocation boundary. Their
throughput, latency and allocation magnitudes are not performance evidence and
may be collected alongside functional builds. The fixture keeps every producer
alive through both warmup and measured total-allocation samples, and requires
the global measured delta to include the sum of producer-local deltas.

Only after correctness checks and explicit exclusive-window authorization:

```sh
bash misc/effect-instance-runtime-cost/fixture/run.sh BEFORE_CP AFTER_CP NEW_TRIAL_DIR 3 5
node misc/effect-instance-runtime-cost/fixture/analyze.mjs NEW_TRIAL_DIR 3
node misc/effect-instance-runtime-cost/fixture/check-gate.mjs NEW_TRIAL_DIR/summary.json
```

This collects 24 independent JVMs: four workloads, three alternating pairs,
five seconds warmup and five seconds measurement. Each workload must reach
at least 0.95 of current throughput, at most 1.10 of current delivery p99, and
at most 1.10 of current bytes/notification, with the corrected allocation
boundary verified. All rows and failed attempts are retained; no selective
rerun or favorable-row replacement. Three-trial medians/ranges are not confidence
intervals. The completed comparison passes all four gates.

## Recorded result

The candidate is current `5d71d676` plus the exact
[`candidate.patch`](evidence/api-v1/candidate.patch): Effect SHA256
`3524d05c796d0232212683755c6a3af3ea5f3df4551b6a4b1be3a49f7ac0c700`
and routing runtime SHA256
`92017bb7294b3fc26375e57b96a4a072aabc966072ee993b3862a17ca5f90587`.
The other 271 recorded production Scala source hashes match. The six fixture
class files are byte-for-byte identical between the two builds. Root completed
the full correctness command before timing: 2,192 ScalaTest and 41 native ZIO
cases passed, with six existing basis-specific cancellations. The ZIO summary's
58 includes 17 ScalaTest cases already counted above.

All ratios below are **API candidate / current compact7**, not original main:

| Workload | Throughput | Delivery p99 | Bytes / notification |
| --- | ---: | ---: | ---: |
| Isolated | 0.996314551698485 | 0.9322842155460181 | 1.0005040830509382 |
| Contended | 0.9834941442467718 | 1.0437636761487965 | 1.0000553381732062 |
| Extensions | 0.9969059848427076 | 0.9477911646586347 | 0.9999878130196344 |
| Backlog | 0.9953886605488417 | 1.013761467889908 | 1.0001554918078974 |

Every median satisfies the predeclared guide. All 24 rows, full metric ranges,
per-run stderr, live-counter cross-checks and gate output are retained in
[`evidence/api-v1`](evidence/api-v1). One isolated reference allocation trial is
1,040.8453 bytes/notification versus about 1,136 in the other two; it remains in
the data. Nothing was selectively rerun or excluded. Three-trial medians do not
establish zero regression or a confidence interval.

All 4,138 recorded class/JAR hashes were unchanged after collection. All eight
smoke rows and 24 measured rows passed the live-producer boundary checks,
callback ordering/counts, teardown and empty-stderr checks. The 32 raw JFR files
remain local; their hashes are preserved in the measured and smoke evidence.
This closes the scoped canonical-default API cost check against current compact7.
The [performance validation decision](../../docs/adr/0004-runtime-performance-validation.md)
records the separate comparison of the full rollout against original main.
