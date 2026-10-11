# Browser authentication v5 snapshot validation

Validated locally on 2026-10-11. `5.0.0-SNAPSHOT` is ready for the next consumer
integration test. This is a breaking version and is published only to local Ivy.
Follow the [migration/testing guide](migration-testing-5.0.0-snapshot.md).

## Changes since the tested 4.1 snapshot

- `BrowserOwnership.ExclusiveScope` / `SharedViews` replaces ownership Booleans.
  Core remains pure; the additional administrative models contain only values.
- Ordinary coordinator output checks the immutable producer/current presentation
  and current owner in one authoritative admission transaction. No authority is
  cached across outputs. Sensitive checks and frozen download chunks remain.
- Snapshot initialization becomes ready only after restoration succeeds. Failed
  restoration cannot commit; concurrent/repeated successful initialization is
  rejected without resetting the accepted revision.
- Snapshot setup emits one redacted diagnostic after cleanup and preserves the
  original error, including when the application reporter itself throws.
- Administrative usage and bounded owner discovery support host-authorized
  retirement of an exact owner/epoch. Retirement advances the fence, frees
  active capacity and preserves snapshots and retained history.

## Correctness and publication

Commands ran through the repository's `nix develop --no-write-lock-file` shell.
Database suites used `scripts/with-test-postgres.sh` and owned disposable storage.

| Check | Result |
| --- | --- |
| `sbt 'core/test'` | Pass: 23 tests. |
| `sbt 'spoonbill/testOnly spoonbill.internal.ClusteredOutputAdmissionSpec spoonbill.auth.* spoonbill.snapshot.* spoonbill.server.internal.services.* spoonbill.state.GuardsSpec'` | Pass: 130 tests. |
| `sbt 'securityJdbc/test'` | Pass: 213 tests against PostgreSQL; none canceled. |
| Additional ordinary-output protocol comparison described below | Pass: one additional test, including stale-owner rejection. |
| `sbt 'browserAuthExample/test'` | Pass: seven Future/IO example tests. |
| `sbt 'zio2/Test/compile'` | Pass after ownership-mode migration. |
| `node scripts/check-core-purity.mjs` | Pass: seven source files, no forbidden APIs. |
| `node scripts/export-browser-auth-schema.mjs --check` | Pass: 11 statements / eight tables, unchanged schema. |
| Browser harness `node --check` / `bash -n` | Pass. |
| `git diff --check` | Pass. |
| `sbt publishLocal` | Pass; all 24 library JARs verified at `5.0.0-SNAPSHOT`. |
| Independent published-artifact consumer `sbt compile` | Pass; generic F, native IO, native Scala 3 Pekko, projections, compatibility readers and caller-authorized administration. |

There were **374 distinct focused Scala tests**, with no failures or canceled
tests in the completed runs. The independent consumer references published
dependencies only; it imports no private APIs or repository source projects.

The v5 core JAR SHA-256 is
`7d524c7af54359fa716cddae8a719e8a500e41556459443f8e8fd178d76d02f9`.
The previous 4.1 core remains unchanged at
`b09858b0e443cce17fd29e53fb71653a5083eb2d0e4dbcb3ccbc9ac7d53c7a97`.
Historical reports, benchmark pins and 4.1 artifacts were preserved.

## Release preflight

The independent [v5 consumer feedback](snapshot-5.0.0-consumer-feedback.md)
adds 56 passing focused tests and all 13 consumer scenarios in both browser
engines. It reports functional acceptance, with full performance acceptance
still open.

Subsequent source review restored the coordinator's captured-identity comparison
inside combined output admission. JDBC already enforced that comparison; the
additional guard also rejects a custom shared provider that returns a changed
identity for an old connection. The regression test verifies rejection, cleanup
and one authority observation without adding a database round trip.

After that fix, the full CI aggregate passed locally: **876 ScalaTest tests and
37 ZIO tests**, with no failures or cancellations. The 81 Node correctness tests
also passed. The command was:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

CI now runs the overlapping-JVM browser fixture by default and checks native
Pekko publication against v5 artifacts. The snapshot hashes above describe the
earlier independently tested snapshot; final release artifacts have their own
version and hashes. No additional performance benchmark ran during preflight.

## Separate-process browsers

Chromium and WebKit each passed the expanded fixture on their first v5 run,
sequentially, with separate JVMs, one shared PostgreSQL database and one browser
origin. Assertions and recovery timeouts were not relaxed.

```sh
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh
```

Coverage includes initial HTTP/socket routing to different nodes, unknown-view
refusal, factor-challenge handoff, original-ceremony recovery after committed
WebSocket output is lost, retry after lost HTTP completion delivery, and exactly
one corresponding proof/session/preparation audit. It also covers acknowledged
draft/selection restoration, mutually readable schema versions, incompatible
reader refusal with unchanged persisted revision/format/payload, same-view
restoration on a compatible reader, active-view capacity refusal, revocation
during takeover, paused-old-owner cleanup, graceful retirement, abrupt loss,
committed business-response loss without replay, and logout.

Precise output/transaction races and database-unavailability fencing are also
covered in the runtime/provider suites. This fixture does not establish all
possible database failover/partition behavior or integration of an application's
full UI, media or actor lifecycle.

## Bounded output-admission comparison

Run the deterministic PostgreSQL count test, optionally enabling its small
timing observation:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh env SPOONBILL_OUTPUT_ADMISSION_SMOKE=1 \
  sbt 'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec -- -z "reduce ordinary protected frame admission"'
```

The test uses the same authenticated view, provider, immutable output and
disposable database. It compares the preceding ordinary-output sequence (four
authorization calls plus output admission) with combined admission. The earlier
sequence is reproduced against the **current provider**, not a fixed historical
library binary. It excludes unrelated event handling, rendering and snapshot
commits. The fixture opens fresh connections instead of using an application
pool.

| Observation per ordinary frame | Preceding sequence | Combined admission |
| --- | ---: | ---: |
| Provider transactions | 5 | 1 |
| Independent durable-clock connections | 15 | 3 |
| Diagnostic p50 | 51.551 ms | 10.253 ms |
| Diagnostic p95 | 57.436 ms | 12.691 ms |
| Diagnostic p99 | 58.168 ms | 13.280 ms |

Counts are asserted deterministically. Timing used ten warmups per variant and
50 alternating samples each; no latency threshold was used. Both paths reject
the old owner after takeover. Runtime tests separately verify that the combined
path does not perform duplicate preliminary/admission checks and that both
producer and current presentation must be allowed.

This shows reduced work on the targeted path. It is not an end-to-end regression
gate, a scaling result, or an allocation/retained-heap measurement. Realm-level
transaction serialization remains. Full performance acceptance is still open.

## Source and client surface

All new production behavior is Scala. The complete emitted client is byte-for-byte
unchanged from the preserved v4 report: **27,514 raw / 10,090 gzip / 8,882 Brotli
bytes**, SHA-256
`135559855760677f4a3eb9fd4457cfef036b63c50445e7ad5ea6bfd4977a16a7`.
The example contains no application JavaScript; browser fault injection is
test-only JavaScript.

The existing inventory tool counts all seven production example Scala files,
including comments and the explicit fixture configuration: **62,433 bytes /
1,316 nonblank lines**. Compared with the recorded 4.1 snapshot this is +1,556
bytes / +26 lines; compared with v4 it is +5,630 bytes / +110 lines. Library
implementation, tests and documentation are separate from this application count.

## Remaining boundaries

Recompile consumers and update all modules together. Drain exclusive writers
before enabling shared mode. Preserve authority data, keys and compatible
presentation formats. Only explicitly acknowledged selected presentation is
durable; private component state and unsubmitted typing are not implicitly saved.

There is no automatic abandoned-owner reaper or retained-history compactor.
Administrative retirement releases an active owner only; snapshots and replay
fences remain. A stale retirement target cannot remove a successor. Administrative
authorization/audit effects require their own reconciliation after an unknown
commit. These operations install no public administrative endpoint.
