# Queue lifecycle comparison

**Allocation audit:** the original fixture sampled the JVM-wide allocation total
after worker termination. JDK 21 can undercount terminated threads, so its
contended allocation rows are provisional and are not allocation proof.
Roundtrip and batch use the still-live main thread and are unaffected by that
boundary. The replacement fixture is being prepared to hold warmup and measured
workers alive, check worker/main self-allocation lower bounds and escaping
allocation controls, and reject inconsistent totals. Fresh contended evidence
will replace the old allocation claim; every original row remains retained.

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

[The 2026-11-17 comparison](evidence/2026-11-17/) retains all 18 rows against
baseline `57d7a8b4`. Candidate/baseline median throughput was 1.032× for roundtrip,
1.259× for batch and 1.065× for contention; main-thread allocation per value was
0.944× and 0.934×. The old contended 0.913× allocation ratio is provisional under
the audit above. Every correctness assertion passed. See provenance for exact
source identities, the formatting-only distinction, and validation logs.
