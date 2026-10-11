# Clustered browser authentication: local snapshot validation

This report covers the local `4.1.0-SNAPSHOT` changes based on released 4.0.0
(`53be8c017d46423feb396d91e3b4cb67c853524b`). It is not a release or performance
acceptance report. Integration and operational limits are documented in the
[shared-store guide](clustered-browser-authentication.md).

## Correctness checks

Run inside the repository's Nix environment, with the PostgreSQL suites using
`scripts/with-test-postgres.sh` and its owned disposable database:

| Command | Result |
| --- | --- |
| `sbt 'browserAuthExample/compile'` | Pass; public shared-provider example compiles. |
| `sbt 'core/test'` | Pass; 23 tests, including pure-core classpath checks. |
| `sbt 'spoonbill/testOnly spoonbill.internal.ClusteredOutputAdmissionSpec spoonbill.auth.* spoonbill.snapshot.* spoonbill.server.internal.services.* spoonbill.state.GuardedStateManagerSpec'` | Pass; 118 tests. |
| `sbt 'securityJdbc/test'` | Pass; 209 tests against real PostgreSQL, none canceled. |
| `sbt 'browserAuthExample/test'` | Pass; 7 Future/IO application tests. |
| `node scripts/check-core-purity.mjs` | Pass; six core source files, no forbidden APIs. |
| `node scripts/export-browser-auth-schema.mjs --check` | Pass; 11 statements / eight tables, no schema drift. |
| `node --check misc/clustered-browser-auth-tests.cjs` and `bash -n scripts/test-clustered-browser-auth.sh` | Pass. |
| `git diff --check` | Pass. |

The 357 Scala tests include exact owner takeover races, delayed acquisition,
stale writes/output/cleanup, database unavailability followed by takeover,
unknown output settlement, cross-provider ceremony recovery and expiry,
snapshot revision fencing, schema conversion and incompatible-schema refusal.
The output test pauses between preliminary authorization and final admission;
the download test materializes a chunk after authority has been lost.

The browser command is:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh
```

Chromium and WebKit passed the separate-JVM fixture with shared PostgreSQL.
The final fixture requires `VIEW_READY` on the newly authenticated socket before
submitting actions. An earlier WebKit run timed out on draft acknowledgement
while publication was running; its cause was not isolated. The quiet run passed
with stricter readiness tracking, unchanged timeouts and no runtime relaxation.

The fixture covers an HTTP bootstrap on A with its first WebSocket on B,
factor-challenge handoff, acknowledged draft and selection restoration,
overlapping snapshot writers using mutually readable versions 1 and 2, paused
old-process cleanup, graceful shutdown, abrupt process loss, and a committed
business increment whose response is discarded. Reconnect restores current
domain state without replaying that increment or changing the valid session.
Logout clears authentication. Database-partition behavior is exercised through
a provider transaction fault; this is not a claim to simulate every network
partition or PostgreSQL failover configuration.

## Source surface and performance limits

All new production behavior is Scala. Shipped JavaScript and generated protocol
sources are unchanged; the new JavaScript is a test-only routing/failure fixture.
The pure core gains only immutable capability metadata and no runtime dependency.

Using the existing `inventory` function on **all seven** production Scala files
in `examples/browser-auth/src/main/scala` gives 60,877 bytes and 1,290 nonblank
physical lines, including comments. The preserved v4 report records 56,803 bytes
and 1,206 lines: the complete application delta is **+4,074 bytes / +84 lines**.
This includes both providers, entry points, policy and the new draft/schema
configuration; it excludes library implementation, tests and documentation.
No application-specific ownership or transport adapter was added. There is no
additional SQL migration in this change. Historical baseline reports and pins
have not been rewritten.

No latency, allocation or retained-heap benchmark was run. Shared mode requires
additional authoritative database work: two transaction stages for claim and
one admission transaction per protected outgoing frame, plus selected snapshot
commits. Download chunks are copied before admission so mutable producer buffers
cannot change an admitted payload. Existing exclusive mode retains its output
effect chain and does not acquire those shared-mode transactions or chunk copies.
Correctness results do not establish zero performance regression.

## Consumer test boundaries

`nix develop --no-write-lock-file --command sbt publishLocal` passed and published
all 24 library JARs at `4.1.0-SNAPSHOT` to the local Ivy repository. A separate
Scala 3.3.8 consumer then passed `sbt compile` using only those published
dependencies (`spoonbill-security-jdbc`, `spoonbill-pekko`, `spoonbill-ce3`). It
exercises the generic provider/coordinator, compatibility decoder, projection
factory, native IO specialization and native Scala 3 Pekko route. No repository
source dependency or private API is used by that probe.

The local snapshot is intended for an application migration test. Drain released
4.0.0 exclusive writers before enabling shared mode; do not overlap those two
contracts. Update all Spoonbill modules together and recompile.

Only explicitly submitted and acknowledged non-sensitive presentation survives.
Private component state must be lifted into the projected application state;
unsubmitted DOM typing is not automatically durable. Ordinary business commands
are not replayed on reconnect. Application retries still require a durable
idempotency key/result or the existing operation protocol.

Recovery depends on reachable authoritative storage and bounded pool, lock and
transaction waits. No automatic lease reaper was added: abandoned distinct views
remain bounded retained records, while the same view can immediately take over.
