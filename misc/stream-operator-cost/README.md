# Stream operator compatibility cost screen

This experiment compares pre-operator `570d3678` with operator implementation
`fb67724b` plus laws `3fa0f496`. It does not change production and does not claim
that the old and new operator contracts are equivalent.

Only overlapping public behavior is measured:

| Cell | One round | Excluded incompatible cases |
| --- | --- | --- |
| Hub | Two live subscribers both pull before one controlled upstream value is published; both receive that exact value. Capacity is 128. | Slow subscribers, overflow/backpressure and late subscription policies. |
| Sort | Pull the even rack, publish its next even value, then pull the odd rack and publish its next odd value. | Wrong-rack demand stalls, queued-rack overwrite and overflow. |
| Buffer | Pull a singleton batch with zero duration and capacity 16, then publish its exact value. | Delayed batching and concurrent pending batches. |

The source is a one-pending-Promise stream, not an always-ready source that lets
one implementation consume ahead. Every delivered value and its order are
checked. A Hub or sort round delivers two values; a buffer round delivers one.
Reports include both rounds and delivered values, so allocation units are explicit.
The source Promise, validation and ordinary Future overhead are common fixture
costs and remain included; these are not bare model-transition timings.

## Allocation and lifetime boundaries

Measurement runs on one live main thread using Future/parasitic execution and
`getCurrentThreadAllocatedBytes`. Timed source and delivery callbacks assert that
same thread. Separate deterministic smoke uses a checked FutureEffect to verify
all delay/map/flatMap continuations on the owner thread; timed runs do not carry
those per-continuation checking wrappers. The known-allocation control publishes
4,096 escaping 4,096-byte arrays to a volatile sink and requires at least 16MiB
of counted allocation.

Fixture construction, teardown and cleanup waits are outside timing. Hub
teardown first drives EOF through both still-live subscribers, awaits their
terminal pulls, then detaches them. Sort/buffer teardown awaits source
cancellation and all fibers started through Effect.start, including asynchronous
cleanup and the old eager producer. The source must have no pending read and
exactly one cancellation. A smoke must produce valid JSON, empty stderr and pass
all assertions before any timed comparison.

The first fixture smoke is retained as superseded control evidence: its delivery
checks passed, but Hub teardown detached subscribers before source close and did
not establish owned-reader EOF completion. The corrected v2 fixture strengthens
that boundary and adds continuation-thread checks; production did not change.

## Reproduction

Use the repository Nix flake. Preparation temporarily selects only this fixture
as effect Test source; it does not edit a checkout's build files.

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/stream-operator-cost/prepare.sh /path/to/before /tmp/stream-cost/before.txt
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/stream-operator-cost/prepare.sh /path/to/after /tmp/stream-cost/after.txt
nix develop --no-write-lock-file --command bash misc/stream-operator-cost/run.sh \
  /tmp/stream-cost/before.txt /tmp/stream-cost/after.txt /tmp/stream-cost/new-screen 3 2 2
nix develop --no-write-lock-file --command node misc/stream-operator-cost/analyze.mjs \
  /tmp/stream-cost/new-screen 3
```

The short screen uses three alternating pairs per cell, two seconds warmup and
two seconds measurement, in independent JVMs. Flags are G1, fixed 256MiB heap and
ActiveProcessorCount=8; that is not CPU affinity. Collection requires an exclusive
no-build/profiling window. Keep all rows and frozen source/classpath evidence.
Medians and ranges from three trials are observations, not confidence intervals,
and this screen cannot establish whole-application latency or semantic equivalence.

## Observed short-screen cost

All 18 rows passed public count/order/thread assertions and complete teardown;
all stderr files were empty. The following are median observations from the
same three-pair screen, not a claim of full semantic equivalence:

| Cell | Deliveries/s before → after | Throughput ratio | Bytes/delivery before → after | Allocation ratio |
| --- | ---: | ---: | ---: | ---: |
| Hub | 7,459,549 → 2,304,065 | 0.309× | 768.02 → 2,392.03 | 3.114× |
| Sort | 21,929,675 → 1,626,776 | 0.074× | 359.94 → 3,255.40 | 9.044× |
| Zero-duration buffer | 10,010,708 → 1,385,309 | 0.138× | 704.02 → 3,913.40 | 5.559× |

The new steady per-item paths are substantially more expensive even when the
legacy implementation delivers correctly. This cost does not satisfy the
rollout's performance objective. No production optimization is included here.
Both allocation controls reported 16,842,752 bytes for 16,777,216 escaping payload
bytes. Exact sources, frozen classpath/class-file manifests, rows and ranges are
under `evidence/`.

## Diagnostic allocation attribution

Separate JFR runs cover baseline sort and all three new operator cells, with
2s warmup plus 3s execution. They are diagnostic runs, not acceptance timing.
Analysis selects main-thread ObjectAllocationSample events whose stack includes
`StreamOperatorBenchmark$Work.run`; this includes warmup and measurement rounds,
not setup, teardown or the measurement harness outside the round. Sample weights
are not an exact per-class byte census and are not multiplied into measured
bytes/delivery as if they were exact.

The primary steady view additionally selects the frozen fixture's `main` call
site at line 217, which is the measured 3s call. Line216 is warmup. Every selected
round sample has its main frame, so there is no unclassified interval or hidden
warmup estimate. Both views remain available; the pooled tables below were the
first diagnostic summary and are labeled accordingly.

| Measured-only profile | Samples | Classified weighted bytes | Unclassified weighted bytes |
| --- | ---: | ---: | ---: |
| Baseline sort | 900 | 22,768,021,872 | 0 |
| New Hub | 872 | 16,730,470,760 | 0 |
| New sort | 901 | 15,338,840,408 | 0 |
| New buffer | 894 | 15,088,760,760 | 0 |

| Measured-only profile | Future objects | Held copies | Runtime origin | Protect origin | Routing-model origin | Fixture origin |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| New Hub | 36.67% | 18.16% | 57.11% | 19.92% | 10.61% | 12.35% |
| New sort | 30.22% | 20.50% | 48.09% | 12.72% | 22.72% | 16.46% |
| New buffer | 33.95% | 2.86% | 47.80% | 19.78% | 16.36% | 9.89% |

The remaining 6.17% of measured buffer origin is its buffer wrapper. Object and
origin columns overlap and must not be added. Steady baseline sort objects are
Long boxing 33.54%, result/Option wrappers 30.61%, Effect closures 12.99%, fixture
closures 9.74% and Future objects 13.11%. Future objects divide 10.19% to nearest
legacy origin and 2.92% to fixture origin. Its nearest legacy sites are
Stream.scala line 275: 16.18%, line 290: 7.01%, line 297: 3.33%; nearest fixture sites include
Input.publish 39.73%, Work.expect 12.66%, validation callback 11.92% and Input.pull 9.17%.
Legacy Stream ancestry overlaps 9.17% classified as fixture because the nearer
allocating source frame is in the fixture; 26.52% has nearest legacy origin.
Thus legacy Stream allocation is not hidden in an unexplained residual.

### Pooled warmup and measurement view

The recordings retain 256-frame stacks. The first `jfr print` export used its
5-frame presentation default and could not reliably locate Work.run. The same
recordings were re-exported with `--stack-depth 128`; no selected/main stack is
truncated (maximum exported depths 73/92/92/119). No profiles were rerun to select
better numbers. Exact coverage and residual weights are preserved:

| Profile | Selected weighted bytes | Classified object weight | Residual object weight | Excluded main weight |
| --- | ---: | ---: | ---: | ---: |
| Baseline sort | 35,046,937,496 | 35,045,249,784 | 1,687,712 | 37,050,040 |
| New Hub | 26,258,641,816 | 26,256,550,880 | 2,090,936 | 37,687,504 |
| New sort | 24,818,034,200 | 24,816,410,840 | 1,623,360 | 38,540,264 |
| New buffer | 23,940,963,440 | 23,939,651,136 | 1,312,304 | 38,713,616 |

The excluded column contains startup/housekeeping/teardown samples without the
round ancestry, not hidden deep stacks. Object residuals are below 0.009% in every
profile. `attribute-allocations.mjs` separately reports nearest-domain source
attribution, object categories, top sites and representative stacks.

| New profile | Future promise/transform/link objects | Held copies | Collections/backing arrays | Nearest protect-scope origin | Nearest routing-model origin |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hub | 37.54% | 16.11% | 18.37% | 23.29% | 10.85% |
| Sort | 34.31% | 13.45% | 7.02% | 20.34% | 23.07% |
| Buffer | 30.18% | 4.90% | 15.08% | 19.60% | 18.25% |

These columns describe different views and must not be summed: a Future object
can be attributed to a protect or routing-model call site. New sort's Step objects
alone account for 5.38% of sampled weight. The larger burden also includes Future
scope machinery, interpreter Held/notification records, closures and collections;
removing a single model wrapper would not explain the entire regression.

Baseline sort is shown beside the candidate rather than omitted. Its sampled
objects are Long boxing 32.45%, Option/result wrappers 27.74%, Effect closures 16.09%,
fixture closures 13.21%, and Future promise/transform objects 10.50%. Future objects
split 8.16% to nearest legacy-operator origin and 2.34% to fixture origin. Top sites
are fixture Input.publish 36.75%, legacy Stream.pull line 275: 18.63%, fixture
Work.expect 15.55%, its validation callback 11.52%, fixture Input.pull 6.44%, legacy
pull line 290: 5.62%, and legacy callback line 297: 5.49%.

The nearest-domain rule assigns allocations to the first relevant framework or
fixture frame after generic Future/library frames. Legacy Stream ancestry occurs
in 6.44% of baseline weight classified as fixture because a nearer source or
validation frame allocates there; 29.74% has nearest legacy origin. Legacy
allocator frames are not deliberately folded into a residual bucket. The common
controlled-source/validation overhead is therefore substantial in the cheap old
sort path and must remain visible when interpreting these ratios.

Only allocation attribution and representative source frames are included in the
repository. Raw JFR recordings and deep allocation-event exports remain at the
exact local paths/hashes recorded in `evidence/allocation-profiles/provenance.json`.
This avoids distributing unrelated recording metadata. Reproduction of attribution:

```
nix develop --no-write-lock-file --command jfr print --json --stack-depth 128 \
  --events jdk.ObjectAllocationSample /path/to/profile.jfr > /tmp/profile.allocations.json
nix develop --no-write-lock-file --command node misc/stream-operator-cost/attribute-allocations.mjs \
  /tmp/profile.allocations.json /tmp/profile.attribution.json 217
```

The optional final `217` selects the measured call site in this exact frozen fixture.
Omit it for pooled warmup/measurement attribution; recheck source lines if the
fixture changes.

## Compact candidate: same matched screen

The compact two-file candidate was frozen at base `60aa81eb` with the exact
released source hashes and patch recorded under `evidence/compact-v2`. Effect
production sources at that base match `3fa0f496`; only StreamRoutingMachine and
StreamRoutingRuntime differ. The original baseline class-file manifest and the
fixture/runner hashes were verified unchanged before collection. Both variants
passed fresh deterministic smoke, continuation-thread, live-subscriber EOF,
started-fiber cleanup and escaping-allocation controls.

The same 18-row 2s+2s three-pair screen observed:

| Cell | Throughput ratio | Allocation ratio | Bytes/delivery before → compact |
| --- | ---: | ---: | ---: |
| Hub | 2.625470596286163 | 0.3178588565437168 | 768.0212286974394 → 244.12234955506852 |
| Sort | **0.5683397327969909** | 0.889172853069413 | 359.9586616455497 → 320.06547016242087 |
| Zero-duration buffer | 1.0608066839041392 | 0.6479717891333839 | 704.1132626322316 → 456.24553054035135 |

The complete candidate is not accepted: sort throughput remains below the 0.95
comparison guide. Its median fell from 21,942,476.808201604 to 12,470,781.406077469
deliveries/second; compact trials ranged 12,405,945.180661935–13,239,656.559188062.
The allocation improvement does not erase that throughput miss. This suggests a
remaining CPU/dispatch/synchronization cost rather than aggregate allocation
pressure alone; the exact cause has not been profiled for this compact variant.
No production optimization or claim of whole-contract equivalence is made here.
All earlier sources, controls, regressions and profile evidence remain preserved.
