# Transport state-machine measurements

The text fixture compares the actual `WebSocketLimits.TextAccumulator` before
and after its pure per-chunk extraction. The baseline source is unchanged from
`3d8c6853` and was compiled at `e733c945`. Every comparison retains three fresh
JVM trials per version/workload, alternating order, with two-second warmup and
measurement, G1, a 256 MiB heap and `ActiveProcessorCount=8`.

| Workload | Input per message |
| --- | --- |
| short | One four-character ASCII chunk |
| ascii | One 1,024-character ASCII chunk |
| unicode | One chunk of 128 lambda/supplementary-character pairs |
| fragmented | Sixteen 64-character ASCII chunks |

The fixture publishes completed strings to a volatile sink and checks their
contents. Allocation is the benchmark thread's JDK allocation counter, including
the accumulator, builder, and returned string; it is neither retained heap nor
whole-JVM allocation. One operation in 256 is timed. Nanosecond timer granularity
and measurement overhead limit tiny-operation p99 interpretation: the short
cell's sampled p99 is 42 ns in both versions. These are shared-machine synthetic
observations with no confidence interval or application-wide performance claim.
JIT allocation elimination and surrounding call sites can change the totals.

All five designs and all 120 rows are retained under `evidence/`. Versions 1–4
missed at least one 95% throughput / 110% allocation / 110% sampled-p99 guide.
Version 5 meets all four cells against its freshly paired baseline:

| Workload | Throughput / baseline | Allocation / baseline | Sampled p99 / baseline |
| --- | ---: | ---: | ---: |
| short | 101.0% | 100.0% | 100.0% |
| ascii | 201.8% | 100.0% | 54.5% |
| unicode | 101.0% | 97.3% | 100.0% |
| fragmented | 181.3% | 97.8% | 53.4% |

Versions 1–2 allocated storage for one million samples. Before collecting version
3, inspection found that faster short-message trials could exhaust that storage.
Versions 3–5 use four million slots, allocated before the allocation window, for
both baseline and candidate. Each retained directory includes its original
fixture source. Do not compare absolute rates across those fixture versions;
each candidate is evaluated against its own matching baseline. No failed or
unfavorable measured trial was replaced.

Production decisions remain pure. The thread-confined accumulator stores scalar
fields so model results need not escape each append. Compact ASCII and complete
UTF-16 counters avoid normal-path result objects. Incomplete, invalid, and
over-budget input uses the checked immutable model, preserving consumed-prefix
state and append-after-validation ordering. Generated tests compare compact and
checked decisions, including malformed and mixed chunks. There is no allocation
per character and no narrowed or packed `Long` state.

To reproduce, prepare each checkout through its own flake using this fixture:

```sh
cd /path/to/baseline
nix develop --no-write-lock-file --command bash /path/to/selected/misc/transport-state-machines/prepare-text.sh \
  /path/to/baseline /tmp/text/baseline.cp
cd /path/to/selected
nix develop --no-write-lock-file --command bash /path/to/selected/misc/transport-state-machines/prepare-text.sh \
  /path/to/selected /tmp/text/selected.cp
nix develop --no-write-lock-file --command bash misc/transport-state-machines/run-text.sh \
  /tmp/text/baseline.cp /tmp/text/selected.cp /tmp/text/comparison 3 2
nix develop --no-write-lock-file --command node misc/transport-state-machines/analyze-text.mjs \
  /tmp/text/comparison
```

Use separate compiled directories, and stop other builds during collection. The
recorded run froze the baseline and candidate class directories before measuring.
Output directories must be new; the analyzer requires all 24 rows, verifies
matching settings and reports medians/ranges. Source patches and hashes preserve
the rejected designs; the selected implementation is in the main source tree.

Historical source patches have zero context to avoid trailing whitespace from
blank context lines. Apply them to an isolated checkout at `e733c945` with
`git apply --unidiff-zero /path/to/evidence/text-scanner-vN/implementation.patch`.
Both reproduced production files were checked against each directory's recorded
SHA-256 source hashes; `patch-hashes.txt` records the regenerated patch identity.
To use that version's original sampling capacity, pass its archived
`TextAccumulatorBenchmark.scala` as the third argument to `prepare-text.sh` for
both checkouts. The source bytes and measurement rows did not change when the
patches were regenerated.

## Coupled standalone input

`SetupInputPullBenchmark` isolates the direct coupled-input branch using one or
128 escaped handoffs and a shared completed upstream future. The wrapper must
return that exact future. This excludes transport, effect execution, and setup
cost; it tests whether the pure phase decision adds work to steady pulls.

The [retained input evidence](evidence/input-handoff/) contains twelve fresh JVM
trials with two-second warmup/measurement and the same heap/GC/processor settings
as above. One pull in 1,024 is timed; the main thread's allocation counter remains
live throughout. Both versions measured zero bytes per pull. The model's median
throughput was 96.01% of baseline for one source and 96.16% for 128, with sampled
p99 42 ns in both versions. Both meet the 95/110/110 guide, but show a small
throughput cost and no timing confidence interval; tiny p99 remains quantized.

The first baseline attempt exceeded sample capacity and produced no valid row.
Its source, stderr and a separate diagnostic are retained in `failed-fixture/`.
A non-timed check confirmed direct future identity; a one-second diagnostic then
observed about 636 million pulls/s and 2.485 million samples, explaining why a
two-second run exhausted four million slots at stride 256. Sampling changed to
1,024 for both versions, and counter-storage initialization moved before the
allocation window. Neither failed nor diagnostic timing enters acceptance.

Prepare separate checkouts using `prepare-input.sh` (same arguments as the text
preparer), using the pre-extraction `f3d9d5a0` source as baseline. Then run:

```sh
nix develop --no-write-lock-file --command bash misc/transport-state-machines/run-input.sh \
  /tmp/input/baseline.cp /tmp/input/model.cp /tmp/input/comparison
nix develop --no-write-lock-file --command node misc/transport-state-machines/analyze-input.mjs \
  /tmp/input/comparison
```

The analyzer requires all twelve rows and identical source-count, duration and
sampling settings. No production source changed during the accepted comparison.

## Frontend frames and chunked bodies

The [source checkpoint](evidence/transport-v2-source/provenance.json) records exact
baseline (`1f06cd3a`) and candidate source hashes. Its `baseline-source` and
`model-source` directories contain complete source files, including new models.
Copy the selected directory over an isolated checkout at that baseline to recover
the corresponding candidate. No dependencies or build settings differed. Runtime
class directories were copied before collection and were not rebuilt during it.

`FrontendFrameBenchmark` measures real guarded-frontend send-to-pull paths:
ordinary focus output, a property patch with a view revision, a render patch with
a producer guard, and an admitted user action producing output. The runtime uses
the existing synchronous Future interpreter. Each result is drained and checked;
the guarded consumer queues are live. This measures an uncontended path, not
network transport, multi-client contention, or browser rendering.

All [24 frontend rows](evidence/frontend-v2/) pass the guide:

| Workload | Throughput / baseline | Allocation / baseline | Sampled p99 / baseline |
| --- | ---: | ---: | ---: |
| control | 97.32% | 100.33% | 108.93% |
| property | 95.70% | 102.26% | 106.15% |
| patch | 100.09% | 103.05% | 108.40% |
| action | 99.90% | 99.61% | 105.18% |

`ChunkedBodyBenchmark` uses the actual `Http11.decodeChunkedBody` stream. It
consumes payload slices without joining them, checks size and boundary bytes,
and includes the decoder/Future overhead. Its workloads are a one-byte payload,
an 8 KiB payload, bytewise input for a 64-byte payload, and split extensions and
trailers. The [first 24 chunked rows](evidence/chunked-v1/) are rejected evidence:
fragmented throughput was 94.67%; trailers reached 84.35% throughput, 110.26%
allocation and 115.93% sampled p99. No trial was discarded or replaced.

Both fixtures use three fresh JVM trials per variant/workload, alternating order,
two-second warmup and measurement, 256 MiB heap, G1 and eight active processors.
The allocating thread stays alive throughout its own allocation snapshots.
Frontend samples one operation in 256; chunked samples one in 64. These synthetic
results have no confidence interval, and their small p99 values remain quantized.
`analyze-cost.mjs` checks all 24 rows, matching settings, positive live allocation
and correctness before computing medians/ranges and the 95/110/110 guide.

Use `prepare-frontend.sh` or `prepare-chunked.sh` with the checkout, output
classpath and archived fixture path. Run each preparer through that checkout's
flake. Then, with every other build/test/benchmark stopped, run the corresponding
`run-frontend.sh` or `run-chunked.sh` through Nix with baseline classpath, candidate
classpath and a new output directory. Analyze through Nix with
`node analyze-cost.mjs OUTPUT frontend` or `node analyze-cost.mjs OUTPUT chunked`.
Set `env -u JAVA_HOME SBT_NATIVE_CLIENT=false` for each Nix invocation, as in the
project validation commands. The archived scripts and analyzer are the versions
used for these rows. The first unmeasured frontend draft was superseded during
review before collection; it produced no rows.

The [second chunk design](evidence/chunked-v2/) hoists the immutable forbidden
trailer-name set and bypasses framing setup for payload-only chunks. Its trailer
cell improves to 104.15% throughput / 88.04% allocation / 93.02% p99, but the full
candidate remains rejected: fragmented input reaches 90.30% throughput and 110.18%
allocation, and large-input sampled p99 is 149.95%. All three fresh matched trials
remain; the wide large-input p99 spread is reported, not filtered away.

## Zio-http frame deadline

`WebSocketFrameDeadlineBenchmark` runs the actual observer in an `EmbeddedChannel`,
including timer registration/cancellation and buffer forwarding/release. The clock
is fixed so valid complete input cannot expire during timing. It measures one
small frame, one 8 KiB frame, a split extended masked header plus its payload, or
32 coalesced small frames. The raw `messagesPerSecond` field counts fixture
operations; the coalesced operation contains 32 wire frames. Payload arrays are
reused and the observer must forward the same `ByteBuf` without moving indexes.
This excludes network IO, TLS and the downstream WebSocket decoder.

All [24 first-design rows](evidence/frame-v1/) are rejected: whole-frame throughput
improves, but allocation is 227.78%–295.12% of baseline; the fragmented cell also
misses throughput and p99. The source snapshots, exact hashes, fixture, derived
runtime-jar classpath freeze, runner, JVM version and summaries are retained.
The local derived jar was copied alongside class directories so later builds
cannot replace the measured implementation. `prepare-frame.sh`, `run-frame.sh`
and `analyze-cost.mjs OUTPUT frame` use the same Nix-only and exclusive-window
workflow described above. `freeze-frame.mjs` freezes both directories and local
target jars; downloaded immutable dependencies keep their cache paths.

The [second frame design](evidence/frame-v2/) keeps event-loop-owned scalar
storage and invokes the same pure scan inline. Immutable model input/results do
not escape the scan boundary; no per-byte event or packed/narrowed counter is
introduced. The checked timer state and scalar timer decision share their
absolute-deadline predicate. All four cells meet the guide:

| Workload | Throughput / baseline | Allocation / baseline | Sampled p99 / baseline |
| --- | ---: | ---: | ---: |
| small | 114.52% | 100.00% | 83.60% |
| large | 136.35% | 100.00% | 71.58% |
| fragmented | 95.07% | 100.00% | 100.09% |
| coalesced | 136.88% | 100.00% | 73.33% |

The fragmented throughput margin is narrow (95.0677% against the 95% guide),
and there is no timing confidence interval. All three trials are retained. The
allocation counts are exactly the same medians as baseline: 144, 144, 656 and
2,624 bytes per fixture operation. This supports the measured synthetic gate,
not an allocation claim about a different JVM, downstream decoder or network.

The analogous [third chunk design](evidence/chunked-v3/) remains rejected. Its
generic input/result snapshot path still allocates on fragmented payload slices
and loses throughput: 84.08%, 94.41%, 86.65% and 99.71% across its four workloads.
Fragmented allocation is 116.65% of baseline. This unsuccessful comparison and
all exact sources remain available alongside the earlier chunk attempts.

The [fourth chunk design](evidence/chunked-v4/) adds pure scalar payload decisions
and bounded simple-header spans with checked fallback. Small messages improve to
152.51% throughput, and trailers pass, but large sampled p99 (113.05%) and the
fragmented cell (92.66% throughput / 109.89% allocation / 116.62% p99) still reject
the candidate. All 24 rows and source snapshots are retained. Inspection then
identified allocating `BytesLikeOps` implicit wrappers on this path; the next
experiment uses direct typeclass reads/slices without changing the byte library.

The [fifth chunk comparison](evidence/chunked-v5/) rejects the wrapper hypothesis:
direct byte access leaves fragmented allocation at 72,976 bytes/operation and
misses throughput/p99 (92.94% / 112.92%). A focused
[empty-tail attribution](diagnostics/empty-tail/) instead measures 104 extra bytes
each time the driver returns an empty `Fork` remainder to the checked path.

The [sixth comparison](evidence/chunked-v6/) removes that cost: the probe returns
to zero bytes per empty input, and fragmented throughput/allocation/p99 meet the
guide at 98.04% / 101.71% / 102.00%. The complete candidate is still rejected:
large sampled p99 is 127.15% and trailer throughput is 94.67%. All 24 rows are
retained, including the large cell's bimodal 917/1166/1167 ns samples. A separate
whole-trace sampling diagnostic is being used to investigate those tails; no
favorable row or stride offset substitutes for acceptance.

The retained [whole-trace diagnostic](diagnostics/latency-stride-v6/) reports all
64 offsets from every baseline/candidate trace. It supports a fixed-offset
representativeness problem, while explicitly noting that timing every operation
changes overhead and is not acceptance evidence. The next paired protocol is
predeclared stratified sampling: xorshift32 seed `0x6d2b79f5`, shifts 13/17/5,
high six bits selecting one operation per 64-operation batch, identical on both
sides. The analyzer verifies sampler identity and seed alongside workload settings.
The independent [trailer profile](diagnostics/trailers-v6/) identifies rebuilt
framing snapshots and builder work. All original fixed-offset rows and both
diagnostics remain unchanged; new source/measurement results are separate.

The [seventh comparison](evidence/chunked-v7/) is the first full matched run of
that predeclared stratified protocol. Small, large and trailer cells pass, but
fragmented sampled p99 is 110.677% of baseline (throughput 96.93%, allocation
102.08%). It is rejected, not rounded into a pass. Every row remains. The next
source change avoids constructing/copying a framing builder when a lone CR or LF
can only inspect/retire an already immutable prefix; ordinary metadata keeps the
stable builder that the profile motivated.

The [eighth chunk comparison](evidence/chunked-v8/) passes every predeclared
workload under the same stratified protocol as v7:

| Workload | Throughput / baseline | Allocation / baseline | Sampled p99 / baseline |
| --- | ---: | ---: | ---: |
| small | 151.19% | 92.00% | 66.72% |
| large | 99.77% | 98.56% | 97.02% |
| fragmented | 99.73% | 101.02% | 106.92% |
| trailers | 112.46% | 86.04% | 86.53% |

All 192 chunk rows across all eight designs remain available. Versions 1–6 use
the original fixed-offset sampler; versions 7–8 use the predeclared matched
stratified sampler. Compare each candidate only with its own matching baseline;
do not compare absolute rates or tails across sampler protocols. The accepted
result has no confidence interval and is a synthetic public-decoder/Future-path
cost guide, not a universal latency guarantee. All three v8 trials, including
the spread in fragmented p99, are retained. Selected production source bytes are
verified against the frozen v8 snapshots before the implementation commit.
