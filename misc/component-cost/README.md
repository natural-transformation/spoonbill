# Component baseline cost comparison

The fixture measures real ComponentInstance traversal with a DiffRenderContext,
DOM change serialization and accepted-buffer swap/reset. It compares stable and
changing parameters with one and 32 children, plus one pending initial loader.
A single producer matches the serialized component-render contract. Read-only
state and owners remain fixed, so one traversal snapshot is reused; snapshot
capture, instance construction and final cleanup are outside the timed window.
This is a focused component render-loop comparison, not full application or
network latency. Constructor overhead and asynchronous refresh completion are
not measured by these cells.

Both variants use the separately corrected loader baseline (`7d399a56`) so the
comparison isolates metadata extraction, rather than comparing different amounts
of duplicate loader work. The same source fixture and JVM flags apply to both.
Each scenario prebuilds the accepted DOM and both parameter shapes before timing.
Afterward it verifies render/placeholder/update counts, one pending loader, no
unexpected writes and no late write after retirement. All frontend incoming
racks have consumers, as in ApplicationInstance, and shutdown awaits them.
Unexpected warning/error reports fail the fixture.

Measurement uses the ownership v2 live-producer protocol: ready/start/done/release
latches, warmup and measured samples while the producer is alive, producer self
allocation versus JVM-total cross-checks, and separate known-allocation controls.
Every JVM uses G1, a 256 MiB fixed heap and ActiveProcessorCount=8 (not affinity).
The default is three alternating pairs in each cell, two seconds warmup and
three seconds measured: 30 independent JVM rows. Throughput counts complete
128-operation batches and includes their drain time. Keep every row and report
medians/ranges, not confidence intervals. The gate is at least 95% baseline
throughput and at most 110% allocation in every measured cell.

Run through the repository Nix flake:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/component-cost/prepare.sh /path/to/before /tmp/component-cost/before.txt
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/component-cost/prepare.sh /path/to/after /tmp/component-cost/after.txt
nix develop --no-write-lock-file --command bash misc/component-cost/run.sh \
  /tmp/component-cost/before.txt /tmp/component-cost/after.txt /tmp/component-cost/new-results
nix develop --no-write-lock-file --command node misc/component-cost/analyze.mjs \
  /tmp/component-cost/new-results
```

The initial v1 fixture did not arm the app-owned frontend racks. Its shutdown
exposed the already-confirmed Stream.sort EOF null-waiter defect in the chosen
baseline, logging errors despite the render/counter assertions passing. Those
stdout files are retained as invalid smoke evidence, not performance results.
V2 arms all racks symmetrically outside measurement and uses a strict reporter;
production is unchanged in both comparison checkouts. The later production
Stream.sort fix is independently owned by the effect rollout. Correctness-only
smoke/control runs may overlap development builds and never guide acceptance.

## Recorded matched result

The complete 30-row v2 comparison in `evidence/2026-11-17-model-v2` passed all five
observed median gates. Both production source files match the frozen candidate
byte for byte. Its zero-context patch applies to the recorded bugfix baseline
with `git apply --unidiff-zero`; source, fixture and raw-data hashes accompany
the evidence.

| Render cell | Throughput ratio | Allocation ratio |
| --- | ---: | ---: |
| Ready, stable parameters, one child | 0.999× | 1.000× |
| Ready, changing parameters, one child | 0.996× | 1.000× |
| Ready, stable parameters, 32 children | 1.003× | 1.000× |
| Ready, changing parameters, 32 children | 0.988× | 1.000× |
| Pending initial loader, one child | 0.983× | 1.029× |

Pending-loader allocation increased from 1,095.92 to 1,127.61 bytes per operation;
the representation does not imply identical whole-operation allocation. The
candidate changing-32 throughput range was 99,030–100,193 operations/second.
Every row passes strict reporter, stderr, operation and live-counter checks;
both known-allocation controls account for at least the 16 MiB escaping payload.
All valid controls and the invalid v1 smoke/stdout/source remain preserved.
