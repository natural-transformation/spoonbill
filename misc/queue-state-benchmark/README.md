# Queue lifecycle comparison

**Allocation audit:** the original fixture sampled the JVM-wide allocation total
after worker termination. JDK 21 can undercount terminated threads, so its
contended allocation rows are provisional and are not allocation proof.
Roundtrip and batch use the still-live main thread and are unaffected by that
boundary. The current `QueueLiveAllocationBenchmark` holds warmup and measured
workers alive, checks worker/main self-allocation lower bounds and escaping
allocation controls, and rejects inconsistent totals. The corrected six-trial
[contended evidence](evidence/2026-11-17-live-contended/) replaces the old allocation
claim; every original row and the historical fixture remain retained.

Compile the same fixture against baseline and candidate checkouts through the
repository-local Nix flake using `prepare.sh <checkout> <classpath-file>`, then
run `run.sh <baseline-classpath> <candidate-classpath> <new-output-directory>`.
Regenerate the summary using `node analyze.mjs <output-directory>` in the same
Nix shell. The analyzer also accepts a retained combined `trials.jsonl` file.
The default is three seconds each of warmup and measurement, three independent
JVM trials per workload/version, alternating pair order, G1, 256 MiB heap and
eight active processors. Stop other builds and measurements during collection.

Roundtrip measures an offer/pull pair; batch measures 64 offers and a bulk drain;
contended uses four producers, one consumer, and a bounded 1,024-element queue.
Every workload checks delivered values or ordering and counts. Throughput counts
accepted/delivered values, including final drain. JVM-wide allocation includes
fixture overhead. These synthetic observations do not establish application
throughput, and three trials do not provide a confidence interval. Preserve
every row, source identity, fixture hash and environment with any comparison.

Pass a fifth argument `contended` to `run.sh` and a second argument `contended`
to `analyze.mjs` for the six-trial focused comparison. Empty/duplicate workloads
are rejected to prevent accidental replacement of trial files. The analyzer
rejects inconsistent self/global counters and mixed legacy/current protocols;
run `node --test analyze-tests.mjs` through Nix to check those safeguards.

The corrected contended medians are 1,505,573 versus 1,480,821 values/second and
5,963.923 versus 6,237.606 bytes/value: ratios 1.0167 and 0.9561. Ten warmup/measured
threads stayed alive at each snapshot, and global totals exceeded measured
worker/main self totals by only 232–264 bytes. Every thread's escaping 64 KiB
control and every delivery/order check passed. Ranges overlap; these are
observations, not a statistical-significance or allocation-site claim.

[The 2026-11-17 comparison](evidence/2026-11-17/) retains all 18 rows against
baseline `57d7a8b4`. Candidate/baseline median throughput was 1.032× for roundtrip,
1.259× for batch and 1.065× for contention; main-thread allocation per value was
0.944× and 0.934×. The old contended 0.913× allocation ratio is provisional under
the audit above. Every correctness assertion passed. See provenance for exact
source identities, the formatting-only distinction, and validation logs.
