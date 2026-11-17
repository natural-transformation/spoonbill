# Trailer allocation and CPU attribution

These profiled runs are not acceptance measurements. They use the unchanged
baseline and candidate-6 production/fixture sources archived under
`../../evidence/chunked-v6`, with a fresh JVM, two-second warmup/measurement,
256 MiB heap, G1 and eight active processors. The attached JFC enables only
allocation and execution samples; event-count reports confirm only those events
and JFR's own metadata/checkpoints were recorded.

The candidate recording identifies `ChunkedBodyDecoder$State` allocation from
`Cell.decode`'s rebuilt input snapshot, and repeated local StringBuilder work.
Its leading parser CPU site is append/capacity handling. The next implementation
therefore retains immutable framing state between metadata chunks and gives the
checked scan a stable local builder; the payload cursor stays scalar.

Allocation weights and CPU sample counts are estimates, not exact object totals
or throughput comparisons. The raw benchmark output is retained but is excluded
from every acceptance summary. Raw JFR recordings are retained; regenerate the
decoded JSON and summaries through the repository Nix shell:

The `.txt.gz` logs preserve the tool's original whitespace byte-for-byte.

```sh
jfr print --json --events jdk.ObjectAllocationSample,jdk.ExecutionSample model.jfr > model.events.json
node analyze-jfr-diagnostic.mjs model.events.json model.summary.json
```

Repeat for `baseline`. The source line references are to the archived candidate
6 and baseline files, not whichever parser implementation is currently selected.
