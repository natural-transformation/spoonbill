# Bounded presentation operations

The same public-API fixture compares fixed-clock reads, advancing-clock reads,
ordinary writes, fenced snapshot reads and release/claim/publication cycles. It
checks retained node values and final active/pending/node counts. The fixture
publishes each store to an escaping reference so synchronization remains visible
to the JIT. All operations execute on the still-live measuring thread; allocation
uses that thread's counter, with a 64 KiB escaping-allocation control before each
round. Controls and latency storage are outside the measured allocation delta.

Prepare both revisions through the repository-local Nix flake:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/presentation-cost/prepare.sh <checkout> <new-frozen-directory> <classpath-file>
```

The script copies every classpath directory and locally built jar into a new
frozen directory. Subsequent builds cannot silently replace the measured classes.
External dependency jars remain at their resolved cache/store locations. Record
the exact source/fixture hashes and revisions alongside every comparison.

```
nix develop --no-write-lock-file --command bash misc/presentation-cost/run.sh \
  <baseline-classpath> <candidate-classpath> <new-results-directory> 3 3 2
nix develop --no-write-lock-file --command node misc/presentation-cost/analyze.mjs \
  <results-directory>
```

Each trial is a fresh fixed-heap G1 JVM with an eight-processor hint; order
alternates. Latency samples one operation per 512 and includes timer overhead,
so small p99 differences may reflect clock granularity. The guide is at least
95% throughput and at most 110% allocation and sampled p99 on three-trial medians
for every workload. Near-zero allocation uses a below-0.001-byte absolute guide.
These are synthetic local operations on a shared developer machine, not an
application latency guarantee. Preserve failed designs and fixture attempts.

An optional final runner argument selects comma-separated workloads for a
screen; pass the same selection as the analyzer's third argument. Screens and
one-trial correctness smoke runs cannot report full acceptance.

## Retained designs

| Design | Change | Result |
| --- | --- | --- |
| 1 | Generic immutable entry and named access decision | Snapshot throughput/allocation screen rejected |
| 2 | Primitive ownership queries | Snapshot screen still rejected |
| 3 | Concrete node-map field | Snapshot screen still rejected |
| 4 | Compile-time inline interpreter wrappers/body arguments | Screen passed; full matrix rejected snapshot throughput |
| 5 | Inline wrappers plus original conditional clock publication | All five full-comparison guides passed |

[The accepted full comparison](evidence/v5-full/summary.json) retains all 30 rows,
ranges and provenance (`trials.jsonl` and `provenance.json`). Intermediate screens
and variant source copies from rejected designs 1–4 were pruned before merge.

During investigation, JFR and `javap` diagnostics distinguished JIT/escape behavior
from acceptance runs: JFR identified a retained snapshot-construction lambda, and
`javap` confirmed direct checks after Scala wrapper inlining. Diagnostic JVM flags
and timing were never acceptance conditions.

Baseline compilation modes varied across independent JVMs. In particular, the
fifth snapshot screen's slow baseline must not be described as a universal 2×
improvement. The final complete comparison observes snapshot throughput 99.7%
of its paired fast baseline, equal allocation and equal quantized p99. The other
four workloads also pass; all evidence remains available for review.
