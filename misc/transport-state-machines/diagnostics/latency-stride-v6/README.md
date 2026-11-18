# Whole-trace stride diagnostic

These are diagnostics, not acceptance rows. Each fresh JVM warms one million
large-body decodes, then records every operation in one 131,072-operation trace.
Three baseline/candidate pairs alternate order. From each unchanged trace the
probe reports aggregate p99 and p99 for every one of the 64 possible stride
offsets (2,048 samples each). No trace, offset or row is selected or omitted.

| Summary (median of three traces) | Baseline | Candidate 6 |
| --- | ---: | ---: |
| Aggregate p99 | 1250 ns | 1208 ns |
| Legacy offset-zero p99 | 1041 ns | 1042 ns |
| Range over all offsets/trials | 1000–1833 ns | 959–1500 ns |

Aggregate p99 gives a 96.64% candidate/baseline ratio; offset zero gives 100.10%
from the same traces. Fixed-offset sampling is sensitive to phase and does not
reliably represent these tails. Timing every operation adds overhead, so this
does **not** prove the earlier regression was entirely artificial and does not
establish acceptance. Functional work may also have been active during this
diagnostic. All raw traces and every offset are retained.

The subsequent acceptance protocol was declared before collecting new rows:
one offset in each 64-operation batch, chosen from the high six bits of a
fixed-seed xorshift32 sequence (`0x6d2b79f5`, shifts 13/17/5). Both variants use
the same seed/algorithm. Low-bit LCG sampling and choosing favorable offsets are
excluded. Old fixed-offset rows remain unchanged in the evidence directories.

The `.trace.gz` format produced by the runner is a big-endian version integer (1),
count integer and `count` long nanosecond values. `analyze-latency-diagnostic.mjs`
re-reads raw traces and verifies every reported percentile. Compile the probe for
both checkouts using `prepare-chunked.sh` and run `run-latency-diagnostic.sh`
through Nix. `summary.json` and `java-version.txt` are retained here; raw `.trace.gz`
dumps were omitted before merge.
