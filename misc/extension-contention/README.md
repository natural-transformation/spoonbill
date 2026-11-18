# Extension runtime performance evidence

This fixture measures the actual `ExtensionRuntime` under concurrent writers,
backlog and bounded callback work. It is a synthetic runtime comparison, not an
HTTP/browser measurement or a prediction of application throughput.

[ADR 0004](../../docs/adr/0004-runtime-performance-validation.md) records the
measurement policy and current acceptance status. The
[final main comparison](evidence/2026-11-17-final-main.json) retains all 24 original
rows and six contended confirmation rows: the full gate failed at **93.50%**
contended throughput; the repeat measured **99.23%** and supplemental pooling
**95.19%**. The repeat does not replace the failure or establish aggregate
acceptance. Historical results below retain their original scope and sources.
The final JSON keeps `fullComparison.rawRows` and
`contendedConfirmation.rawRows` separate because their trial labels repeat;
pooling is reported only for the contended statistic, not as a new four-cell gate.

## Workloads and controls

Each fresh JVM uses G1, a 256 MiB heap and `ActiveProcessorCount=8`. Five-second
warmup precedes five seconds of writing; admitted notifications drain before the
measurement ends. Three pairs per workload alternate order. The processor flag
is a JVM hint, not CPU affinity. Avoid concurrent builds or profiling during
comparative collection and record other host load.

| Scenario | Views | Writers | Extensions/view | Window/view | CPU work/callback |
| --- | ---: | ---: | ---: | ---: | ---: |
| isolated | 16 | 16 | 3 | 32 | 1 microsecond |
| contended | 1 | 4 | 3 | 1,024 | none |
| extensions | 1 | 8 | 8 | 2,048 | 1 microsecond |
| backlog | 1 | 4 | 3 | 8,192 | 2 microseconds |

Callback work is a bounded spin. Every callback checks its writer's sequence;
missing, duplicated or reordered delivery fails the run. The last callback
releases an in-flight permit. Counts, bounded backlog and teardown must pass;
closing a socket cannot hide pending callbacks. Smoke runs check these controls
but their metric magnitudes are not comparative evidence.

The JDK 21 total-allocation counter includes fixture and all JVM-thread overhead;
it is not an allocation-site profile. It can temporarily omit exiting threads
([OpenJDK implementation](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.8%2B9/src/hotspot/share/services/management.cpp)).
The fixture waits for producer startup, keeps producers and workers alive through
sampling, and requires total allocation to include producer-local deltas in both
warmup and measurement. Older post-join allocation rows remain provisional. The
analyzer rejects mixing boundary protocols; the gate requires
`producers-alive-through-sample`.

Delivery latency spans the `state` call through the last callback. Admission
latency measures that call, excluding permit wait. Histograms give upper bounds
with 1-microsecond buckets below 10 ms and 100-microsecond buckets thereafter;
overflow is rejected. Throughput includes backlog drain. Teardown is outside the
resource window but must finish. JFR `GCPhasePause` events provide pauses within
the measured interval. Raw recordings stay local because they may include process
metadata; retained hashes identify them.

## Reproduction

Pin both revisions and validate their contracts separately. Use the applicable
repository-local Nix flake for preparation, execution and analysis:

```sh
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh \
  /path/to/reference /tmp/runtime-check/reference.txt --runtime-only
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh \
  /path/to/candidate /tmp/runtime-check/candidate.txt --runtime-only
nix develop --no-write-lock-file --command bash misc/extension-contention/run.sh \
  /tmp/runtime-check/reference.txt /tmp/runtime-check/candidate.txt \
  /tmp/runtime-check/new-comparison 3 5
nix develop --no-write-lock-file --command node misc/extension-contention/analyze.mjs \
  /tmp/runtime-check/new-comparison 3
nix develop --no-write-lock-file --command node misc/extension-contention/check-gate.mjs \
  /tmp/runtime-check/new-comparison/summary.json
```

`prepare.sh` uses temporary sbt source settings; it changes no build or runtime
file. Default mode also compiles contract tests; `--runtime-only` compiles only
the fixture and does not replace correctness validation. Use the same mode on
both sides. Freeze class directories and local JARs before collection; do not
recompile them while their exported classpaths are in use. New output directories
preserve failed and unfavorable runs. A one-pair, two-second invocation provides
eight smoke rows; only the full comparison supplies gate metrics.

The analyzer accepts per-trial files or a combined `trials.jsonl`, checks complete
matched workloads and reports per-version medians/ranges. `check-gate.mjs` requires
at least three trials per version and all four workloads: throughput ≥95%, delivery
p99 ≤110% and allocation ≤110% of the reference, plus the corrected sampling
boundary. Its `baseline`/`main` labels mean the explicitly recorded reference,
not automatically the repository's current main. Do not mix data after inspecting
results or use a passing repeat to overwrite a failed comparison.

An optional sixth `run.sh` argument supplies a third classpath and a seventh names
it (`locked` by default). This collects 36 rows in three rotating orders; analyze
with `3 baseline,cas,locked` (or the chosen third label). Workloads and controls
are unchanged. Report each workload and candidate separately.

## Historical diagnostics and evidence

`StateCellRetryBenchmark` supports the CAS implementation through `18548af3`,
not later ownership-split designs. On that pinned checkout, prepare with
`--with-cas-retries`, then run `spoonbill.internal.StateCellRetryBenchmark` in Nix
with arguments `writers extensions maximum-waiting writes-per-writer` (for
example `4 3 1024 25000`). It counts transition-function attempts minus successful
calls using one consumer and bounded producers. It omits handlers, handler tables
and effect scheduling; its counts are not runtime retry counts and its timing is
not delivery latency. The monitor baseline has no corresponding CAS-retry count.

Retained evidence and patches are immutable historical inputs:

- [Initial runtime/retry observations](evidence/2026-11-16/).
- [CAS/lock comparison](evidence/2026-11-16-pilot-acceptance/) and
  [direct-step comparison](evidence/2026-11-16-direct-steps/).
- [Ownership-design comparisons](evidence/2026-11-16-ownership-designs/), including
  rejected and pre-correction candidates.
- [Corrected live-producer pilot](evidence/2026-11-17-live-allocation/), a pass for
  that historical source, not final-rollout acceptance.
- [Final main comparison and confirmation](evidence/2026-11-17-final-main.json),
  with the unresolved aggregate result described in ADR 0004.

`variants/lock-preserving.patch` applies to `15672bc0`; ownership-design patches
name their bases in their provenance. Reproduce them in separate pinned
worktrees with `git apply --unidiff-zero`, then validate before measurement.
Historical fixtures/patches are not automatically compatible with current APIs.
