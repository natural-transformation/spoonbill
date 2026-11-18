# Transport state-machine measurements

This directory contains matched public-path fixtures, selected comparisons and
retained rejected experiments. Architecture and behavioral boundaries are in
[ADR 0005](../../docs/adr/0005-transport-deadlines-and-framing.md); frontend ownership
is in [ADR 0003](../../docs/adr/0003-session-and-security-ownership.md).

## Fixtures and measurement scope

| Script family | Fixture and workloads | Included / excluded work |
| --- | --- | --- |
| `text` | `TextAccumulatorBenchmark`: short ASCII, 1 KiB ASCII, Unicode pairs, fragmented ASCII | Actual accumulator, builder and returned string; no transport |
| `input` | `SetupInputPullBenchmark`: one or 128 coupled sources | Direct pull and exact upstream Future identity; no setup or transport |
| `frontend` | `FrontendFrameBenchmark`: control, property, render patch, action | Guarded send-to-pull path with live consumers; no network or browser rendering |
| `chunked` | `ChunkedBodyBenchmark`: small, 8 KiB, bytewise input, extensions/trailers | Actual `Http11.decodeChunkedBody` and consumed payload slices; no payload concatenation |
| `frame` | `WebSocketFrameDeadlineBenchmark`: small, 8 KiB, split header, 32 coalesced frames | Netty `EmbeddedChannel`, timers and exact buffer forwarding; no TLS/network/downstream decoder |

Comparisons use three fresh JVMs per variant/workload, alternating order,
two-second warmup and measurement, G1, a 256 MiB heap and eight active processors.
The guide requires every cell's median throughput to be at least 95% of its
paired baseline, with allocation and sampled p99 at most 110%, and correctness
checks passing. Allocation is the live benchmark thread's counter, not retained
heap or whole-process allocation. Frame operations may contain multiple frames.
These are synthetic observations without confidence intervals; tiny sampled p99
values include timer overhead and can be quantized to 42 ns.

## Reproduction

Use isolated baseline/candidate checkouts and the same fixture bytes for both.
Run each preparer through its checkout's flake. All preparers accept
`CHECKOUT OUTPUT_CLASSPATH [FIXTURE_SOURCE]`; runners accept
`BASELINE_CLASSPATH CANDIDATE_CLASSPATH NEW_OUTPUT_DIRECTORY`. Only `run-text.sh`
also accepts optional repetitions and seconds; its defaults match the above.

For example, after selecting the source/fixture pair described below:

```sh
transport_tools=/path/to/selected/misc/transport-state-machines
transport_before=/path/to/baseline
transport_after=/path/to/candidate
transport_output=/tmp/transport-comparison
transport_fixture="$transport_tools/ChunkedBodyBenchmark.scala"

cd "$transport_before"
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash "$transport_tools/prepare-chunked.sh" "$transport_before" \
  "$transport_output/baseline.cp" "$transport_fixture"
cd "$transport_after"
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash "$transport_tools/prepare-chunked.sh" "$transport_after" \
  "$transport_output/candidate.cp" "$transport_fixture"

# Freeze compiled directories and locally derived jars before measurement.
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  node "$transport_tools/freeze-chunked.mjs" \
  "$transport_output/baseline.cp" "$transport_output/candidate.cp" \
  "$transport_before" "$transport_after" "$transport_output/frozen"

# Stop all other builds/tests/benchmarks for collection.
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash "$transport_tools/run-chunked.sh" \
  "$transport_output/frozen/baseline.classpath" "$transport_output/frozen/model.classpath" \
  "$transport_output/results"
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  node "$transport_tools/analyze-cost.mjs" "$transport_output/results" chunked
```

Use the corresponding `prepare-`, `run-` and analyzer for another family:

| Family | Analyzer | Compiled-source isolation |
| --- | --- | --- |
| text | `analyze-text.mjs OUTPUT` | Dedicated unchanged compiled checkouts |
| input | `analyze-input.mjs OUTPUT` | Dedicated unchanged compiled checkouts |
| frontend | `analyze-cost.mjs OUTPUT frontend` | Dedicated unchanged compiled checkouts |
| chunked | `analyze-cost.mjs OUTPUT chunked` | `freeze-chunked.mjs` as above |
| frame | `analyze-cost.mjs OUTPUT frame` | `freeze-frame.mjs`, same arguments as chunked |

Do not rebuild either measured checkout during collection. Frame freezing must
include its local derived runtime jar, not just class directories. Freeze helpers
record the historical baseline identity `1f06cd3a`; use that baseline for historical
reproduction and explicitly correct provenance for a different comparison.
The candidate checkout's fixture, runner and analyzer must be the versions used
for collection: freeze helpers copy and hash those files from that checkout.
The analyzers require every matched row (24, or 12 for input), check settings and
correctness, and write medians/ranges to `summary.json`. Analyze new output or a
copy of archived data, preserving original evidence unchanged.

### Source and fixture selection

- **Text:** the unchanged baseline was compiled at `e733c945` (production source
  from `3d8c6853`). [Selected v5](evidence/text-scanner-v5/) retains its baseline,
  source and environment hashes alongside `summary.json`.
- **Input:** use pre-extraction `f3d9d5a0` and the selected `SetupInput` source,
  checking the [baseline](evidence/input-handoff/baseline-hashes.txt) and
  [candidate hashes](evidence/input-handoff/source-hashes.txt). The current
  `SetupInputPullBenchmark.scala` uses stride 1,024 and initializes sample storage
  outside the allocation window.
- **Frontend:** measured against baseline `1f06cd3a` with `FrontendFrameBenchmark.scala`;
  the selected result is in [frontend v2](evidence/frontend-v2/summary.json).
- **Chunked/frame:** measured against `1f06cd3a` checkouts. The selected manifests
  and summaries are [chunked v8](evidence/chunked-v8/provenance.json) and
  [frame v2](evidence/frame-v2/provenance.json). Verify hashes before compiling.

## Selected summaries

Each value below is a candidate/baseline median percentage. The linked JSON
retains each cell's absolute medians and full trial ranges; these local guides
do not replace the rollout's final aggregate comparison.

### Text accumulator

[Selected v5](evidence/text-scanner-v5/summary.json):

| Workload | Throughput | Allocation | Sampled p99 |
| --- | ---: | ---: | ---: |
| short | 101.0% | 100.0% | 100.0% |
| ascii | 201.8% | 100.0% | 54.5% |
| unicode | 101.0% | 97.3% | 100.0% |
| fragmented | 181.3% | 97.8% | 53.4% |

### Coupled standalone input

[Selected input comparison](evidence/input-handoff/summary.json): one/128-source
throughput is 96.01%/96.16%. Both versions allocate zero bytes per pull and have
42 ns sampled p99. This is a small direct-pull throughput cost, not an end-to-end
transport result.

### Frontend frames and chunked bodies

[Frontend v2](evidence/frontend-v2/summary.json):

| Workload | Throughput | Allocation | Sampled p99 |
| --- | ---: | ---: | ---: |
| control | 97.32% | 100.33% | 108.93% |
| property | 95.70% | 102.26% | 106.15% |
| patch | 100.09% | 103.05% | 108.40% |
| action | 99.90% | 99.61% | 105.18% |

[Chunked v8](evidence/chunked-v8/summary.json):

| Workload | Throughput | Allocation | Sampled p99 |
| --- | ---: | ---: | ---: |
| small | 151.19% | 92.00% | 66.72% |
| large | 99.77% | 98.56% | 97.02% |
| fragmented | 99.73% | 101.02% | 106.92% |
| trailers | 112.46% | 86.04% | 86.53% |

### Zio-http frame deadline

[Frame v2](evidence/frame-v2/summary.json):

| Workload | Throughput | Allocation | Sampled p99 |
| --- | ---: | ---: | ---: |
| small | 114.52% | 100.00% | 83.60% |
| large | 136.35% | 100.00% | 71.58% |
| fragmented | 95.07% | 100.00% | 100.09% |
| coalesced | 136.88% | 100.00% | 73.33% |

The fragmented cell passes narrowly: 95.0677% against the 95% throughput guide.
Allocation medians are 144/144/656/2,624 bytes per fixture operation, unchanged
from its paired baseline.

## Diagnostics and sampling notes

[Selected evidence](evidence/) retains the final summaries and provenance for text
(`text-scanner-v5`), coupled input (`input-handoff`), frontend (`frontend-v2`),
chunked framing (`chunked-v8`) and Netty frame deadlines (`frame-v2`). Superseded
intermediate iterations, duplicated source snapshots and raw JFR/trace dumps were
pruned before merge.

- **Input fixture stride:** initial smaller sample storage overflowed before a
  valid row, motivating the symmetric 1-in-1,024 stride in `SetupInputPullBenchmark.scala`.
- **Empty-tail attribution:** the decoder's empty `Fork` remainder measured
  0 bytes/call before extraction, 104 in the initial path, then 0 with the
  state-preserving empty-input path. Direct byte access alone did not fix the
  fragmented allocation cost.
- [Whole-trace latency diagnostic](diagnostics/latency-stride-v6/): compares
  aggregate p99 against all 64 fixed-stride offsets (`summary.json`). Timing every
  operation changes overhead; this diagnosed sample representativeness and is not
  a replacement acceptance result.
- **Trailer CPU/allocation profile:** JFR profiling identified framing snapshots
  and builder work on trailer chunks; profiling results were diagnostic, not
  benchmark trials.

Chunked versions 1–6 use fixed-offset sampling; versions 7–8 use the predeclared
matched stratified sampler: xorshift32 seed `0x6d2b79f5`, shifts 13/17/5, high six
bits selecting one operation per 64-operation batch. The analyzer checks sampler
identity and seed. Text/frontend/frame sample one operation in 256; input one in 1,024.
Do not compare absolute rates or tails across sampling protocols. Version 7's
fragmented p99 miss remains a failure; v8 changed delimiter handling before its
fresh full comparison.
