# Executable workload coverage

`coverage.mjs` expands every required manifest cell and pass into its useful-work
assertions, reusable source/test assets, missing implementation, required metrics,
and resource/counter accounting. It is an implementation inventory, not measured
evidence or an approved contract. A passing correctness test does not mark a
performance runner implemented. Unknown groups/scenarios and duplicate IDs fail.

```sh
nix develop --no-write-lock-file --command node \
  misc/browser-auth-baseline/performance/coverage.mjs
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/coverage.test.mjs \
  misc/browser-auth-baseline/performance/legacy-collector.test.mjs
```

| Cells | Reusable implementation | Remaining executable work |
| --- | --- | --- |
| 4 unused-feature | Core session fixture | Public-only startup/event/idle/teardown runner with authentication disabled |
| 12 guarded setup | Actual native browser and guarded core fixtures | Fresh/existing/missing × anonymous/authenticated; cold child startup and warm completion boundaries |
| 8 login | Native password/factor flows and reference hosts | Repeated eligible browser bindings, actual configured proof cost, concurrency, completion and exchanges |
| 16 protected | Reference protected-write path | Exact node populations, policy rounds, controlled contention barrier and commit/render checks |
| 5 reconnect | Native retained reconnect and host ownership tests | All named scenarios, old-owner fencing, expiry and repeated lifecycle accounting |
| 7 failure | Host fault tests, native completion loss/restart | Repeatable fault injection and settlement/duplicate-mutation checks in the measured loop |
| 24 node read/snapshot | Actual `StateManager.cached`/`serialized` | Separate read/snapshot loops with internal traversal/copy observation; do not optimize baseline code to fit desired candidate counts |
| 7 lifetime | Ownership, cancellation, retirement and restart tests | Full capacity scopes, configured idle/churn, quiescence and retained metadata accounting |
| 4 historical echo | Actual standalone WebSocket fixture | Latency adapter exists; memory, operations and resource instrumentation remain |
| 1 historical core | Actual serial guarded core fixture | Latency adapter exists; memory, operations and complete resource instrumentation remain |
| 4 Pekko echo | Official Pekko adapter | Actual echo fixture; standalone echo is not a substitute |
| 2 browser reference | Chromium/WebKit real-cookie workflows | Repeated full workflow and supported server/browser sensor synchronization |

The legacy adapter runs the existing Scala fixtures through an explicit Java
argument array. It hashes every classpath entry before and after the process,
checks requested identity and exact total warmup/measured counts, verifies the
fixture result schema and completed ownership/work, and preserves raw JVM output
in the harness process log. The transport fixture validates every echoed byte;
the core fixture waits for released input/guard state and checks balanced owners.
Its requested work-contract hash must match the actual fixture source and exact
workload. Build the pinned historical and feature artifacts separately as
documented in the [transport](../../websocket-performance/README.md) and
[core](../../websocket-performance/CoreSessionBenchmark.md) guides. A classpath
hash by itself does not identify its Git revision.

For `run.mjs`, a legacy latency command is:

```json
["node", "misc/browser-auth-baseline/performance/legacy-collector.mjs", "GENERATED_CLASSPATH_FILE"]
```

It deliberately rejects memory and operations passes. Existing fixtures require
positive warmup; changing the proposed zero-warmup operation pass silently would
change the protocol. The adapter returns actual latency values but leaves sensor
resolution and full resource accounting null. It cannot pass the strict analyzer.
It never turns unobserved resources into zero or claims JFR/retained/native heap
support from the fixture's total-allocation counter.

A short diagnostic probe can validate an already compiled fixture without SBT:

```sh
nix develop --no-write-lock-file --command node \
  misc/browser-auth-baseline/performance/legacy-collector.mjs --probe \
  misc/browser-auth-baseline/performance/manifest.json historical-echo-c1-p128 \
  GENERATED_CLASSPATH_FILE NEW_PROBE.jsonl 1000 1000
```

The probe owns its collector group through `run.mjs`, preserves raw process logs
and failures, and exclusively creates the destination. Probe records are labeled
`unfrozen-legacy-probe`, with `acceptanceEvidence: false`. Their counts intentionally
differ from the frozen protocol and they cannot become calibration by renaming a
file. The collector subprocess is designed to run under the owned-group harness,
not as a separately managed service. No raw failure or prior probe is overwritten.

## Cost and Phase 0 sequence

The current 94-cell proposal requires 94 × 3 passes × 30 pairs × 2 observations
= **16,920 independent processes per mode**. Baseline calibration plus later
candidate comparison totals **33,840**. The mandatory 30-second memory quiescence
alone is **47 hours per mode**, or **94 hours combined**, on the serial runner.
The seven cold cells add **42,840 child server launches per mode**: 42,000 for
latency and 840 for memory/operations. These are deterministic configured-work
counts, not a measured wall-time estimate. Startup, proof cost, browser work,
cleanup/GC, builds and analysis all add time. The proposed collection limit is
72 hours per mode; its feasibility needs a pilot. Parallelizing on the same
host would change the controlled-hardware contract.

Phase 0 freezes the reference and measurement protocol before broad candidate
implementation. Candidate/baseline zero-regression comparison is a later release
gate; do not require an unimplemented candidate to finish Phase 0. The smallest
sound sequence is:

1. Finish source review and equivalent, configurable reference behavior; produce
   immutable artifacts for the pinned revisions plus the reviewed integration.
2. Implement each missing workload and instrument actual counters/resources at
   their owning boundary. Observe JDBC API execution and physical wire sync cycles
   separately, including durable clock and maintenance work. Verify useful work
   before counting completion and drain owned work before retained measurements.
3. Validate sensors and per-cell metric applicability. Warm event/login/reconnect
   cells do not automatically perform browser startup/download/parse/compile:
   resolve their shared browser metric set before freezing, keeping those metrics
   on workloads that actually perform the corresponding work. No zero timing may
   stand in for an unmeasured phase. Validate actual clock/sensor granularity;
   nominal nanosecond units are not a resolution probe.
4. Run small baseline-only pilots per distinct path, engine/provider and pass.
   Preserve all raw results, record actual durations and resource peaks, and
   estimate the full calibration cost on the selected host. Pilot data is not
   frozen acceptance evidence. Fill deterministic operation/resource ceilings
   from observed, explained reference work; never infer SQL or traversal counts
   from the desired implementation.
5. Review and freeze source/work contracts, client byte accounting, every metric
   threshold, work count, sensor configuration and provenance. Run baseline-only
   calibration under the agreed protocol. Preserve superseded protocols and raw
   runs when calibration requires a justified revision before candidate sampling.

The current manifest remains `awaiting-reference-review`. Missing executable
workloads, unsupported measurements, unmeasured budgets and unstable calibration
remain concrete Phase 0 blockers. This inventory neither supplies those values
nor relaxes the later zero-regression rule.
