# v5 bounded performance check

Run on 2026-10-11, within the requested one-hour limit. Work started at
17:07:22 UTC; collection and process cleanup ended at 17:56:01, and analysis
completed at 17:59:41. No further measurements were started.

**Result: inconclusive for the zero-regression release requirement.** Provider
latency averages were close to v4, but baseline-versus-baseline variation was
too large to establish no slowdown. Retained heap showed a small increase that
needs to remain visible. Browser measurements were unavailable because of
collector failures. PR #9 remains unmerged and v5 has not been released.

## Compared artifacts and scope

- Baseline: released `4.0.0`, commit
  `53be8c017d46423feb396d91e3b4cb67c853524b`.
- Candidate: `5.0.0-SNAPSHOT`, commit
  `9e5b3011a088526096cf7585e60415ddb45e1640`.
- Identical public-API probe against frozen JAR copies. Only the JDBC ownership
  constructor adapter differs between versions. Artifact and source hashes are
  preserved in the [provider protocol](../misc/browser-auth-v5-performance/jvm-protocol.json).
- Exclusive in-memory and PostgreSQL providers, one process at a time, serial
  operations, JDK 21.0.8, `-Xms256m -Xmx256m -XX:ActiveProcessorCount=2`.
- Fixed time exercises the exclusive acknowledged-clock cache. PostgreSQL uses
  fresh physical connections to an owned local test database. These conditions
  do not represent advancing clocks, a production connection pool, shared
  ownership, clustered operation, or coordinator/browser performance.

There were **50 successful JVM processes and 118 workload observations**.
Both providers completed six alternating AB/BA latency comparison pairs and
two A/A calibration pairs. In-memory allocation/retention completed three
comparison and two calibration pairs. PostgreSQL allocation/retention completed
two comparison and two calibration pairs; the final planned comparison pair
was not started because it could cross the collection deadline. All observations
were retained, with the skipped pair recorded explicitly.

## Observations

Values below are means of process-level observations, not pooled percentiles.
Protected work includes a transaction, counter verification and authorization.
Reconnect includes claiming a successor, releasing the old owner, and checking
that the old owner is denied and the successor is authorized.

| Provider and metric | v4 mean | v5 mean |
| --- | ---: | ---: |
| In-memory protected operation p50 | 6.028 µs | 5.938 µs |
| In-memory protected operation p99 | 14.389 µs | 13.951 µs |
| In-memory reconnect p50 | 8.576 µs | 8.445 µs |
| In-memory reconnect p99 | 21.076 µs | 21.437 µs |
| PostgreSQL protected operation p50 | 6.748 ms | 6.729 ms |
| PostgreSQL protected operation p99 | 8.769 ms | 8.314 ms |
| PostgreSQL reconnect p50 | 11.909 ms | 11.804 ms |
| PostgreSQL reconnect p99 | 14.389 ms | 13.937 ms |
| In-memory allocated bytes / protected operation | 7,941 | 7,908 |
| In-memory allocated bytes / reconnect | 5,976 | 5,956 |
| In-memory retained heap after 500 distinct views | 5,525,600 B | 5,538,340 B |
| PostgreSQL allocated bytes / protected operation, partial | 339,717 | 339,787 |
| PostgreSQL allocated bytes / reconnect, partial | 630,907 | 630,878 |
| PostgreSQL retained heap after 50 distinct views, partial | 7,503,920 B | 7,527,120 B |

All 16 latency calibration metrics exceeded the predeclared 1% mean-drift
limit. Favorable latency averages therefore do not establish an improvement or
absence of regression. In-memory allocation intervals crossed zero. In-memory
retained heap averaged **12,740 bytes higher (+0.231% paired change)**; its
exploratory interval was +0.064% to +0.449%. Three pairs and two calibration
pairs are insufficient to treat this as a confirmed population regression.
PostgreSQL memory values are explicitly partial; no comparison interval is
reported for the missing pair.

Allocation and retention were measured separately from latency. Retained Java
heap was observed after 30 seconds of quiet and two observed full GCs at each
boundary. Exact retained view counts were asserted; these records are expected
tombstones. This measures bounded retained history, not a memory plateau or
post-close reclamation. Native memory, database memory and browser memory are
not included. Correctness assertions passed and no JDBC connections remained
active at measured boundaries.

## Browser collection and decision

Three browser attempts failed during warmup, before producing any numeric
samples. The collector submitted events before the protocol acknowledgement,
then failed to observe acknowledgements carried in binary WebSocket frames.
The final attempt observed the committed increment but missed its acknowledgement.
These are collector failures, not evidence of a Spoonbill regression. Failures
were retained and retries stopped under the repository's three-attempt rule.

The [full analysis](../misc/browser-auth-v5-performance/provider-summary.md)
includes every primary metric, exploratory intervals, calibration results and
missing observations. Its `inconclusive-collection-integrity` status includes
the explicitly skipped final pair; it does not mean completed rows were corrupt.
The original 94-cell historical acceptance manifest and its baseline pins remain
unchanged. This bounded check adds useful provider evidence, but does not satisfy
the full latency/memory acceptance requirement. No performance waiver is assumed.

## Verification and evidence

Commands ran in the repository Nix environment:

- `nix develop --no-write-lock-file --command bash /tmp/spoonbill-perf-20261011-170722/run-provider.sh`
  — collection completed, with the final pair explicitly skipped at the deadline.
- `node --check analyze-provider.mjs` — passed inside that environment.
- `node analyze-provider.mjs jvm-protocol.json provider-results.jsonl provider-runs.jsonl provider-summary browser-results.jsonl browser-results-attempt2.jsonl browser-results-attempt3.jsonl`
  — completed from the artifact directory; exit 1 intentionally reports the
  inconclusive performance gate, not an analyzer crash.
- Scoped process inspection — no remaining benchmark JVMs or owned PostgreSQL
  processes after cleanup.

[Raw provider observations](../misc/browser-auth-v5-performance/provider-results.jsonl),
[run records](../misc/browser-auth-v5-performance/provider-runs.jsonl), source,
protocols and analysis are retained in `misc/browser-auth-v5-performance/`.
The local frozen JARs, detailed logs and original browser failure records remain
under `/tmp/spoonbill-perf-20261011-170722/`.
