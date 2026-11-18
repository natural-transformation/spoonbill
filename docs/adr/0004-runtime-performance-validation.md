# ADR 0004 — Runtime performance validation

Status: measurement policy adopted. Aggregate performance acceptance for PR #34 remains unresolved. The latest full comparison, `d057488e`, fails contended delivery p99.

## Decision

Pure decision models must preserve lifecycle contracts and meet an explicit cost
budget. A cleaner state representation is not sufficient evidence of acceptable
allocation or contention. Compact immutable values and interpreter-owned storage
are acceptable when ownership remains explicit and correctness tests preserve
the effect boundaries. Diagnostic profiles can suggest a change; matched runtime
measurements determine its observed cost.

For the four-workload runtime gate, each workload must achieve at least **95% of
the reference's throughput**, at most **110% of delivery p99**, and at most
**110% of bytes per notification**, with correctness checks passing separately.
Ratios use per-version medians of independent JVM trials; report absolute values
and ranges as well. Do not average workload scores or ratios. A numerical gate
is not a confidence interval, statistical noninferiority proof, or application
latency guarantee.

Freeze the reference and candidate revisions, fixture, classpaths and dependency
artifacts before collection. Use identical workloads, warmup, JVM settings and
counting boundaries, alternating execution order in an exclusive measurement
window. Retain every valid row, failed candidate and control separately. Declare
sample size and any repeat/combination rule before inspecting results; a repeat
requested after a failure is supplemental evidence, not permission to replace
that failure. Changes to source, protocol or analysis require explicit provenance.

Allocation accounting must include the work being compared. The
[fixture](../../misc/extension-contention/ExtensionRuntimeBenchmark.scala) keeps
producers alive through warmup and measurement samples and requires total
allocation to include producer-local deltas. The worker pool also remains alive.
Earlier post-join measurements are provisional for allocation. A thread-local
counter is suitable only with demonstrated work locality. Correct delivery counts,
ordering, bounded backlog, successful teardown and zero histogram overflow are
prerequisites. Preserve source/artifact and recording hashes; keep raw recordings
that may contain environment metadata outside the repository.

Two measurement errors are especially easy to repeat. Sampling the same offset
in every batch can repeatedly hit the same part of the protocol. For the
chunk decoder, use the same predeclared stratified sampler on both versions
and keep results from different samplers separate. A small source change can
also change which allocations the JIT eliminates. The presentation-store
snapshot fixture saw slow and fast baseline compilations. Preserve each trial's
range before attributing a speedup to the candidate.
[Latency sampling diagnostic](../../misc/transport-state-machines/diagnostics/latency-stride-v6/) ·
[Presentation-store comparison](../../misc/presentation-cost/README.md)

## Comparison of `d806539c` with original main

The comparison of `d806539c` with original main `3d8c6853` used 24 measured
trials. The record includes all 24 and the six later contended trials at full
precision. [Evidence](../../misc/extension-contention/evidence/2026-11-17-final-main.json)

| First full comparison | Throughput / main | Delivery p99 / main | Allocation / main | Gate |
| --- | ---: | ---: | ---: | --- |
| Independent views | 98.33% | 109.16% | 107.40% | pass |
| Four writers, one view | 93.50% | 99.89% | 106.80% | **fail** |
| Eight writers, eight extensions | 100.06% | 100.42% | 106.46% | pass |
| Sustained backlog | 99.36% | 102.04% | 106.69% | pass |

The original full gate **failed**. The contended cell's three candidate trials
were all below its three reference trials. An unchanged-classpath three-pair
repeat of that cell measured **99.23%** throughput and passed its three numerical
guides; its ranges overlapped. Pooling all six trials per version gives **95.19%**
throughput, only 0.19 percentage points above the threshold. This is a supplemental
statistic calculated after the failure, not a fresh full comparison. Neither the
repeat nor pooling erases the first result, changes the threshold, or records a
waiver. **Aggregate acceptance is not established.**

Both collections used five-second warmup and measurement periods on a shared
machine; host load differed between them. Three-trial medians and a narrow pooled
margin do not establish robust equivalence. The initial isolated p99 was also
close to its limit. All observations remain retained; none is discarded as an
outlier. The recovered manifest records selected class hashes, not a complete
frozen-classpath audit.

## Comparison of `d057488e` with original main

A fresh comparison of `d057488e` with the same original main used 24 new trials.
It is not pooled with the `d806539c` rows. `ExtensionRuntime` and `Effect` class
files match that earlier candidate. `Queue` differs, and this fixture does not
call the queue or pool edits. [Evidence](../../misc/extension-contention/evidence/2026-11-18-d057488e-main.json)

| `d057488e` / main | Throughput / main | Delivery p99 / main | Allocation / main | Gate |
| --- | ---: | ---: | ---: | --- |
| Independent views | 100.55% | 93.11% | 106.76% | pass |
| Four writers, one view | 96.64% | 118.39% | 106.81% | **fail** |
| Eight writers, eight extensions | 98.30% | 103.02% | 106.43% | pass |
| Sustained backlog | 98.27% | 98.94% | 106.68% | pass |

The fresh gate **failed** on contended delivery p99: 0.972 versus 0.821 ms.
Every candidate trial, 0.944–1.031 ms, is above every main trial, 0.743–0.902 ms.
That cell's throughput passes at 96.64% (2,294,847 versus 2,374,691
notifications/second) and its allocation passes at 1,130.16 versus 1,058.05
bytes/notification. The other three workloads pass. All 24 trials delivered
correctly. Eight two-second smoke rows are excluded from the metrics.

Collected 2026-11-18 18:42:13Z–18:46:58Z on Zulu 21.0.8. Load averages were
6.88, 5.29, and 5.59 at the start and 9.04, 9.45, and 7.63 at the end. The
same three-trial median rule applies. **Aggregate acceptance is not established.**

## Scope and consequences

The [historical corrected pilot](../../misc/extension-contention/evidence/2026-11-17-live-allocation/)
passed at its recorded source, but cannot certify the final rollout. The
[canonical Future-instance comparison](../../misc/effect-instance-runtime-cost/README.md)
uses compact7 `5d71d676` as its reference, not original main. The
[stream comparison](../../misc/stream-operator-cost/v3/README.md) covers compatible
public paths and has no delivery-p99 gate. These results remain useful within
those scopes; none substitutes for final aggregate acceptance.

The [fixture guide](../../misc/extension-contention/README.md) contains reproduction
commands, sampling details and the compatible revisions for historical diagnostics.
