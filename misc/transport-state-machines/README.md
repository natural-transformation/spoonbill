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
