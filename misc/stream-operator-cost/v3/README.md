# Closed Future API compatibility-path comparison

Protocol `stream-compat-v3` keeps the three public workloads, warmup, delivery
checks, live measuring-thread allocation counter, escaping control and teardown
contract of v2. The old fixture and evidence remain unchanged. New rows must not
be substituted into old summaries.

The shared `StreamOperatorBenchmarkV3.scala` is compiled byte-for-byte on all
three sources. `LegacyBackend.scala` supplies the original and compact7 effects
using the existing tracked/checked subclasses. `FactoryBackend.scala` supplies
the candidate through its genuine built-in `Effect.future` factory and the
supported instrumentation hooks. Exactly one adapter is compiled per classpath.
There is no generic wrapper transferring permission to use a fast interpreter.
The shared body stores one `Effect[Future]` from that adapter before timing.

The start hooks record every returned child's completion; after timing the
fixture awaits source cancellation and joins each recorded child, checking that
all issued starts were recorded and joined. Hub drives EOF through live demand
before detaching its two subscribers. Source pending-read and cancellation counts
are still checked. A source cancellation acknowledgement alone is insufficient.
The smoke-only continuation hook checks the measuring thread; every timed source
read, publication and delivery retains its own thread assertion. Setup, start
recording and teardown are outside the measured loop. Failed starts fail controls
instead of silently losing the teardown ownership check.

Use the applicable checkout's `nix develop` for compilation, controls and timing.
`prepare.sh CHECKOUT OUTPUT legacy|factory` compiles the common fixture and one
adapter and exports a classpath. Compile into new isolated checkouts: never
rebuild the old v2 frozen class directories. Run `--smoke` and
`--allocation-control` with each classpath before freezing its sources/classes.
The candidate's production correctness validation and explicit exclusive timing
permission are prerequisites for running `run.sh`.

`run.sh ORIGINAL_CP CURRENT_CP CANDIDATE_CP NEW_DIRECTORY [WARM] [MEASURED]`
collects 27 rows: three independent JVMs per variant per cell, with cyclic order
original/current/candidate, current/candidate/original,
candidate/original/current. Defaults remain two seconds warmup and two seconds
measurement. JVM heap, collector and processor flags match v2.
`analyze.mjs DIRECTORY` checks every row and emits medians, ranges and separate
candidate/original and candidate/current ratios. Both comparisons must reach
0.95 throughput and at most 1.10 allocation in every cell. It does not measure
p99 or establish whole-application acceptance. All rows, controls, source hashes,
class manifests and unsuccessful attempts must be retained; no favorable-row
selection or comparison with historical medians substitutes for this screen.

## Recorded comparison

The corrected candidate source is compact7 `5d71d676` plus
[`evidence/api-v2/candidate.patch`](evidence/api-v2/candidate.patch), with
Effect SHA256 `3524d05c796d0232212683755c6a3af3ea5f3df4551b6a4b1be3a49f7ac0c700`
and runtime SHA256 `92017bb7294b3fc26375e57b96a4a072aabc966072ee993b3862a17ca5f90587`.
The original reference is `570d3678`; the current reference is `5d71d676`.
All three used the identical shared fixture source and seven identical compiled
benchmark class files. Their version-specific adapters are explicitly recorded.

The exclusive 27-row comparison passed both predeclared median guides:

| Cell | Candidate / compact7 throughput | Candidate / compact7 allocation | Candidate / original throughput | Candidate / original allocation |
| --- | ---: | ---: | ---: | ---: |
| Hub | 0.9959430329192621 | 0.9999741371781887 | 3.443026961492535 | 0.2607079874796727 |
| Sort | 0.9648483225062349 | 0.999968668439799 | 0.9577426787328345 | 0.6446138607889794 |
| Zero-duration buffer | 0.9955452594021728 | 0.9999887939098713 | 1.4312789025787531 | 0.47738830756936734 |

Sort is close to the throughput threshold: its median is 20,793,386.2273
deliveries/s against compact7's 21,550,937.8441 and the original's
21,710,827.6461. Candidate trials range 20,420,665.3751–21,789,814.9245;
compact7 ranges 21,533,994.7684–21,782,047.7996 and original
21,447,196.3563–22,136,225.4562. The guide uses medians, not every possible
cross-pair of trials; this result does not establish zero regression. One
candidate sort trial allocated 248.027 bytes/delivery while the other two were
232.02; the median is 232.0203 against compact7's 232.0276. No row was omitted.

[`evidence/api-v2`](evidence/api-v2) preserves every row and its stderr, the
summary, exact source snapshots/patch, classpath and class/JAR manifests, and all
controls. Original/current source snapshots are shared with the retained
[`api-v1`](evidence/api-v1) preparation pack. That earlier candidate was never
timed: review removed unnecessary no-op hooks and a completion map from the
canonical default instance before the corrected freeze. Its sources, bytecode
hashes and successful controls remain retained. All 886 class/JAR hashes were
unchanged after collection. Each allocation control observed 16,842,752 bytes
against a required minimum of 16,777,216; all source, delivery, continuation,
EOF and started-child join checks passed.

This fixture exercises the explicitly instrumented built-in factory, whose hooks
run outside the measured loop. It does not measure canonical-default `start`
overhead, native effects, p99, or the final aggregate gate. The runtime fixture
comparison is a separate check. These are three-observation medians and full
ranges, not confidence intervals.
