# Focused ownership cost comparison

The identical fixture exercises production `ComponentOwnership` admission/release,
snapshot capture plus validation, and `GuardedStateManager.write` with a no-I/O
underlying store. Lease and guarded-write cells use one and four writers; snapshot
capture uses one. Every measured operation completes before the elapsed/allocation
window ends, and ownership drains afterward. Producers remain alive behind a
release latch until the final allocation sample; this applies to warmup too.
Each producer records its own allocation delta, and every row requires the JVM
total delta to cover that sum and all producers to be alive at sampling.
No implementation-specific model API
or field is used, so the fixture compiles against both pre-refactor and extracted
interpreters.

This is a synthetic hot-operation comparison, not server latency or an application
throughput prediction. JVM-wide allocation includes fixture overhead. Time is
sampled per 128-operation batch; throughput includes the final batches draining.
Each independent JVM uses G1, a fixed 256 MiB heap and ActiveProcessorCount=8. That
flag is not CPU affinity. The default is three alternating paired trials with two
seconds of warmup and three measured seconds. Preserve all trials. Do not collect
while other builds or benchmarks run.

Prepare through the repository Nix flake; the source file comes from this directory
for both variants. Preparation changes only temporary sbt source settings, not
checkout source/build files. The emitted classpath references compiled directories;
do not rebuild a measured checkout during collection.

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/ownership-cost/prepare.sh /path/to/before /tmp/ownership-cost/before.txt
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash misc/ownership-cost/prepare.sh /path/to/after /tmp/ownership-cost/after.txt
nix develop --no-write-lock-file --command bash misc/ownership-cost/run.sh \
  /tmp/ownership-cost/before.txt /tmp/ownership-cost/after.txt /tmp/ownership-cost/comparison
```

Record exact source revisions and hashes with the resulting evidence.

## Allocation boundary correction

The retained v1 rows sampled the JVM-wide counter after producer `join`. Their
allocation observations are provisional and must not decide acceptance. The
[OpenJDK 21.0.8 accounting implementation](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.8%2B9/src/hotspot/share/services/management.cpp#L1928)
permits temporary undercount during native thread removal/accounting; its
high-water mark can mask that gap. A near-zero row therefore does not, by itself,
establish escape-analysis elimination. Throughput timing is a separate observation.

Protocol v2 keeps producers alive through both warmup and measured samples and
cross-checks the JVM total against their self counters. `--counter-check` runs a
known-allocation control: four producers allocate 1,024 escaping 4,096-byte arrays
each, publishing every array to a volatile sink. Self counters must report at
least the 16 MiB of payload, JVM total must cover the self-counter sum, and all
four producers must remain alive at that sample.

```
nix develop --no-write-lock-file --command bash -c '
  java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
    -cp "$(cat /tmp/ownership-cost/before.txt)" \
    spoonbill.internal.OwnershipBenchmark --counter-check
'
nix develop --no-write-lock-file --command node misc/ownership-cost/analyze.mjs \
  /tmp/ownership-cost/comparison
```

The analyzer validates live-producer and self-counter checks for v2, and labels
v1 allocation provisional. It reports ranges and omits ratios against near-zero
allocation baselines. The v1 fixture is archived under `evidence/`; pass its path
as an optional third argument to `prepare.sh` to reproduce that older protocol.

## Recorded design comparisons

All original rows are retained. Candidate patches apply to `5605813d`; every
comparison uses the original interpreter at `e733c945` as its baseline. Exact
revisions, fixture/patch/raw-data hashes and limitations accompany each directory.
The original wrapper and first compact runs used v1; their allocation observations
are provisional. A separate v2 wrapper witness confirms 72.014 producer bytes per
lease operation (diagnostic, not a full acceptance comparison). All five v2 known
allocation controls report 16,842,752 producer bytes, exceeding the 16 MiB payload.

| Candidate and protocol | Full comparison outcome |
| --- | --- |
| Case-class wrappers, v1 | Retained original evidence; allocation provisional |
| Packed state, v1 | Retained original evidence; allocation provisional |
| Packed state, v2 | Rejected: single-writer lease throughput 0.696× |
| Shallow owner transitions, v2 | Rejected: four-writer guarded throughput 0.943× |
| Shallow owner and drain transitions, v2 | Passed all five observed median gates |

The two targeted screens are preserved separately and are not acceptance runs.
The final 30-row comparison in `evidence/2026-11-17-guard-shallow-v2` observed:

| Operation | Writers | Throughput ratio | Allocation ratio |
| --- | ---: | ---: | ---: |
| Lease admit/release | 1 | 0.990× | Producer bytes 0 → 0 |
| Lease admit/release | 4 | 1.004× | Producer bytes 0 → 0 |
| Snapshot capture/validation | 1 | 1.080× | 0.922× |
| Guarded write | 1 | 0.993× | 1.000× |
| Guarded write | 4 | 1.045× | 0.966× |

The gate is at least 95% baseline throughput and at most 110% allocation in every
cell. Near-zero JVM housekeeping allocation has no useful ratio; the producer
self counters establish zero lease bytes in both variants. Every warmup and
measured v2 row passes the alive-worker and global-versus-self checks. These are
three-trial medians, not a confidence claim: final four-writer guarded throughput
ranges from 5.589 to 6.893 million operations/second in the candidate. The failed
preceding matrix remains a failed experiment and is not replaced by the screen.

To reproduce a candidate, create a checkout at its recorded base and apply the
recorded zero-context patch with `git apply --unidiff-zero`, then prepare both
classpaths using the same current fixture.
Analyze screens with a third argument (`leases:1` or `guarded:4`); omit it for the
complete five-cell matrix.
