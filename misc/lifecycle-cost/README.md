# Coordinator and render lifecycle cost

This synthetic fixture compares the same production operations on frozen baseline
and extracted-interpreter checkouts. It measures coordinator write turns and
same-key read turns with one/four producers, accepted timer renders with 0, 1 and
1,024 declarations, and an aborted single-declaration timer render. Timer clocks
are explicit and fixed; this measures render bookkeeping, not timer wake delivery.
Coordinator bodies assert exclusive ownership for the shared key. Timer teardown
asserts retained declarations, slots and physical scheduled handles before closing.

The allocation protocol follows `../ownership-cost` v2: producers remain alive
until both warmup and measured global counter samples, each records its self
allocation delta, and the global delta must cover their sum. Known controls use
four producers publishing 1,024 escaping 4,096-byte arrays each. Throughput includes
complete batches of 128 settled operations; fixture creation and final cleanup
are outside the timed window. Each JVM uses G1, 256 MiB heap and
`ActiveProcessorCount=8`, which is not CPU affinity. This is not server throughput
or tail-latency evidence.

Use the repository Nix flake for compilation and collection:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/lifecycle-cost/prepare.sh /path/to/before /tmp/lifecycle/before.txt
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/lifecycle-cost/prepare.sh /path/to/after /tmp/lifecycle/after.txt
nix develop --no-write-lock-file --command bash misc/lifecycle-cost/run.sh \
  /tmp/lifecycle/before.txt /tmp/lifecycle/after.txt /tmp/lifecycle/new-results
nix develop --no-write-lock-file --command node misc/lifecycle-cost/analyze.mjs \
  /tmp/lifecycle/new-results
```

The full matrix is three alternating pairs per cell, two seconds of warmup and
three measured seconds: 48 independent JVM rows. Preserve all rows and failed
candidates. Require at least 95% baseline throughput and at most 110% allocation
in each cell. Medians/ranges of three trials are observations, not confidence
intervals. Do not rebuild a frozen classpath or run other builds during matched
collection.

The initial one-second warmup/measurement smoke and known-allocation controls ran
while other development builds could run. They establish correctness/counter
invariants only; their timing or allocation ratios must not guide acceptance.

## Initial matched result

The 2026-11-17 full matrix retained in `evidence/2026-11-17-initial` passed all
correctness/live-worker/counter assertions. Timer-render extraction passed every
observed median gate; the coordinator candidate failed and remains rejected.

| Cell | Producers | Throughput ratio | Allocation ratio |
| --- | ---: | ---: | ---: |
| Coordinator write | 1 | 1.063× | 1.056× |
| Coordinator write | 4 | 0.755× | 1.153× |
| Coordinator same-key read | 1 | 1.067× | 1.127× |
| Coordinator same-key read | 4 | 0.884× | 1.117× |
| Accepted render, zero declarations | 1 | 1.068× | 0.955× |
| Accepted render, one declaration | 1 | 1.042× | 0.922× |
| Accepted render, 1,024 declarations | 1 | 0.997× | 1.000× |
| Aborted render, one declaration | 1 | 1.044× | 0.976× |

Each candidate's zero-context patch is retained, including the rejected
coordinator implementation. Apply with `git apply --unidiff-zero` to the recorded
base. The two changes affect separate cells; the accepted source commit adopts
only timer render state. Full ranges and absolute allocation remain in the JSON
summary; no failed cell is replaced by its smoke result. Both known-allocation
controls report 16,842,752 producer bytes for 16,777,216 payload bytes.

## Coordinator refinements

The second design kept callback-bearing queue entries in the interpreter and
published pure occupancy/admission decisions. Its complete 24-row comparison is
retained in `evidence/2026-11-17-coordinator-compact`: write cells passed, but
read-one throughput was 0.913× and read-four throughput/allocation were
0.911×/1.104×. That failed gate remains rejected.

The third design uses cached Idle/Writing tags or the immutable read-key Set
directly as occupancy, and matches completion without an intermediate tuple.
After a passing 12-row read screen, a fresh complete 24-row coordinator comparison
in `evidence/2026-11-17-coordinator-readset` observed:

| Cell | Producers | Throughput ratio | Allocation ratio |
| --- | ---: | ---: | ---: |
| Coordinator write | 1 | 0.992× | 0.958× |
| Coordinator write | 4 | 1.008× | 0.953× |
| Coordinator same-key read | 1 | 1.102× | 0.937× |
| Coordinator same-key read | 4 | 0.985× | 0.980× |

All four observed median gates pass. Candidate read-four throughput ranged from
3.444 to 3.766 million operations/second; the full summary retains all ranges and
absolute bytes. Every row passes the warmup/measured live-worker counter checks.
The unchanged timer source was accepted by the earlier eight-cell comparison;
this complete coordinator gate does not substitute its earlier targeted screen.

Select coordinator-only runs with the optional seventh argument `"write read"`
after repeat/warmup/measurement durations. Analyze them with arguments
`3 write:1,write:4,read:1,read:4 "full four-cell coordinator gate"`; a read-only
screen uses `3 read:1,read:4` and retains the default screen label. The initial
pack's provenance names its original tooling revision because these optional
selectors were added later. Every production variant remains reproducible from
its recorded base and zero-context patch; no frozen classpath was rebuilt during
measurement.
