# WebSocket performance results

Measured 2026-10-09–10 against 2.0.0 commit
`0c6df154a185815d187b4f5d04aad3aab0570f8b`. The corrected candidate's production
source is commit `3d3041e1218ba8aa5ff9e404621bce15478a8a3c`.

**Result:** no corrected, calibrated metric reports a statistically detected
regression. Transport allocation decreases are supported by the comparison.
Timing/CPU comparisons remain inconclusive under the literal zero-slowdown gate;
these results do not prove zero performance change.

## Corrected comparison

Numbers below are the change in the ratio of candidate and baseline means.
Latency columns compare means of independently measured block quantiles, not
pooled percentiles. Timing confidence intervals include zero change.

| Workload | Median latency | p95 | p99 | Throughput | CPU/op | Allocation/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 connection, 128 bytes | −0.04% | −0.61% | −1.31% | +0.09% | +0.56% | −0.26% |
| 1 connection, 4096 bytes | −0.13% | +0.18% | +0.83% | +0.23% | −0.47% | −0.005% |
| 8 connections, 128 bytes | −0.22% | −0.02% | −0.05% | +0.15% | −0.24% | −0.53% |
| 8 connections, 4096 bytes | −0.07% | −0.43% | −7.02% | +0.41% | +0.14% | −0.02% |
| Guarded startup + disposal | −1.27% | −1.69% | −1.88% | +1.46% | +2.10% | −0.03% |

The guarded-lifecycle allocation comparison is also inconclusive. The table's
point estimates must not be interpreted as statistically established speedups or
slowdowns where the analysis says inconclusive. Exact values, confidence bounds,
calibration results, and artifact identities are in the
[transport report](../misc/websocket-performance/results/corrected/spoonbill-transport-v2-report.json)
and [core report](../misc/websocket-performance/results/corrected/spoonbill-core-v2-report.json).

## Method and limits

- 30 independent baseline/candidate pairs per workload plus 30 baseline/baseline
  calibration pairs; fresh JVMs, seeded balanced order, matched 512 MiB heap.
- Transport: 40,000 warmup echoes across the connections, then 10,000 measured
  echoes per connection. Each response payload is verified.
- Core: 10,000 warmup and 5,000 measured guarded-session operations per JVM.
  Each operation awaits guard closure and checks zero active inputs/guards and
  an absent application before proceeding.
- Both phases use the same timed batch function. Compilation and GC counters
  are recorded, as are actual artifact, fixture and Nix lock hashes.
- Conservative paired-block bootstrap intervals use a 95% family confidence
  level, Bonferroni correction, 20,000 replicates, and no slowdown allowance.
- CPU and allocation cover the benchmark JVM, including identical fixture work.
  The workload scope is localhost standalone transport and serial in-process
  guarded lifecycle; it does not certify every production deployment.

The earlier collection showed single-connection transport regressions. Diagnostic
counters exposed compilation inside its measurement window, and warmup used a
different loop from measurement. The fixture was corrected and the normal output
mapper was shortened with a cached callback. Both initial and corrected data are
[retained](../misc/websocket-performance/results/README.md); no failed or noisy
blocks were silently discarded to obtain the reported result.

## Reproduction

Compile the common fixtures with each version's response factory and export the
classpaths as described in the [transport guide](../misc/websocket-performance/README.md)
and [core guide](../misc/websocket-performance/CoreSessionBenchmark.md). Then run
inside the repository's Nix environment:

```sh
node misc/websocket-performance/run.mjs BASE_CP CANDIDATE_CP comparison.jsonl 30 1:128,1:4096,8:128,8:4096 comparison
node misc/websocket-performance/run.mjs BASE_CP CANDIDATE_CP calibration.jsonl 30 1:128,1:4096,8:128,8:4096 calibration
node misc/websocket-performance/analyze.mjs comparison.jsonl calibration.jsonl report.json
```

For guarded lifecycle use the core classpaths and append `core` to both collection
commands. The analyzer deliberately exits 1 for an inconclusive strict gate.
Functional CI passing does not change that statistical result.

## Readiness

Functional checks and GitHub CI are green. The literal zero-slowdown statistical
gate is not a pass. Accepting “no statistically detected regression in these
workloads” as the release criterion requires an explicit readiness decision;
this document does not silently replace the stricter criterion.
