# Actual JDBC wire diagnostic

**The default strict mode rejects the pinned driver's overlapping transaction
preamble.** The explicit `--pipelined-cycles` observer counts protocol cycles only;
physical round-trip metrics remain null. Neither mode is a performance gate.

This probe runs the already compiled `JdbcReferenceHostSpec` and
`JdbcBrowserSecuritySpec` against the disposable PostgreSQL instance through the
[owned wire proxy](postgres-wire-proxy.md). It observes actual PostgreSQL driver
Query/Sync → ReadyForQuery cycles and separates startup exchanges. It counts
neither SQL statements nor TCP packets. The proxy never decodes or retains SQL,
authentication payloads, parameters or results.

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  node misc/browser-auth-baseline/performance/reference-wire-probe.mjs \
  modules/security-jdbc/target/streams/test/fullClasspath/_global/streams/export \
  /absolute/path/to/NEW-wire-diagnostic.jsonl --pipelined-cycles
```

The classpath must already exist and contain the compiled suites. This command
does not invoke SBT. The producing build remains a separate managed step. Every
classpath entry is content-hashed before/after execution, and both suite source
files and collector/proxy/harness sources are hashed and rechecked. This is the
**test classpath identity**, not the
reference application capture's artifact hash. Source hashes do not themselves
prove compiler/source correspondence; retain the producing build evidence.

Only the disposable wrapper's explicit `127.0.0.1` port, database `postgres`, and
synthetic `spoonbill_test` trust user are accepted. Extra URL options and password
settings fail closed. The forwarded JDBC URL explicitly disables SSL and GSS
encryption; encrypted, COPY, Flush and cancel protocols remain
unsupported by the proxy and make the observation inconclusive. The target URL
and wire payloads never enter the structured report.

Without the flag, strict sequential behavior remains unchanged. With the flag,
the collector explicitly requests a fixed 32-slot pending-cycle FIFO per
connection. The selected mode/bound enter the work-contract identity and report.
The JDBC driver's query mode is unchanged. Completed cycles use
`completedProtocolSyncCycles`; `syncExchanges` becomes null on overlap, and
`physicalRoundTrips` stays null. `complete` means only that the conformance suites
and the configured protocol observation completed.

The outer `run.mjs.observe` owns a detached collector process group. Inside it,
the asynchronous proxy and one Java process run together; Java inherits the
group and these suites use threads rather than child processes. Java is bounded
to nine minutes, its stdout/stderr to 4 MiB each, and the outer group to ten
minutes. The proxy allows 32 connections and a ten-second socket idle timeout.
After Java closes, the collector waits up to five seconds for socket settlement
before closing its listener. Forced socket closure cannot turn unsettled work
into successful completion. Exit status, both completed suites, positive test
count, no ignored/canceled/pending tests, and positive actual startup/sync counts
are required. Unsupported/missing observations remain inconclusive.

The destination and `.private-processes.jsonl` sidecar are created exclusively
with owner-only permissions. The structured destination contains safe counts,
hashes, scope and completion status. The private sidecar preserves bounded raw
ScalaTest/JVM logs, including failures; those logs can contain test diagnostics
and local paths. **Do not publish the private sidecar.** An interrupted or failed
probe preserves partial output and a failure record; there is no automatic retry
or replacement of a failed run. SIGKILL cannot finalize an interrupted report.

This is a whole correctness-fixture diagnostic. It includes fixture schema and
account setup, maintenance, durable clock checkpoints, control/diagnostic SQL,
ordinary transactions and intentional fault/recovery paths. It is not an isolated
login measurement, a manifest cell, a deterministic operation ceiling, or a
calibration/comparison run. No count may be copied into per-login budgets. HTTP,
WebSocket and browser events are absent from this scope. A `complete` diagnostic
means the specified conformance work and physical protocol observation finished;
`acceptanceEvidence` remains false.

The browser wire pilot is deferred: the current native fixture has detached
restart JVMs and GNU timeout process-group boundaries, and its libpq control URL
needs separate handling from JDBC's `gssEncMode` option. A properly owned browser
supervisor is required before claiming bounded collection of that entire tree.

## Observed default-driver limitation

The first disposable PostgreSQL 14.20 / PgJDBC 42.7.13 run on 2026-10-10 was
**inconclusive**: all 72 tests ran, 3 passed and 69 failed after the strict proxy
refused 69 overlapping exchanges. All 240 accepted connections closed; no payload
was retained. Both accepted exchange metrics are null. The partial cycle totals
are not valid whole-fixture measurements and do not establish a performance
regression in the reference host. Raw failure records remain private and local.

The pinned driver's [transaction preamble](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.13/pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java#L675)
sends an implicit `BEGIN` using simple-query protocol. Its
[execute path](https://github.com/pgjdbc/pgjdbc/blob/REL42.7.13/pgjdbc/src/main/java/org/postgresql/core/v3/QueryExecutorImpl.java#L405)
then sends the user query before processing the earlier response. Sequential
JDBC calls therefore do not imply only one pending wire synchronization cycle.
Changing to simple-query mode would still permit `BEGIN` and user-query messages
before the first response; no blind changed-mode retry was performed.

The separately opted-in bounded pending-cycle observer now supplies that narrow
extension while retaining strict defaults. Fragmented/coalesced mixed-cycle,
queue-overflow, missing-response/error/close and physical PostgreSQL tests cover
its interpretation. The original strict failure is preserved; selecting this
new observer is not a retry of unchanged instrumentation and does not change the
driver mode. Physical round-trip measurement and per-cell budgets remain absent.

The new observer was then validated against the **same test-classpath artifact**
(`f8b33c9e44a6384b33d7b5a0343750b8438cf20eba2e3523d0bf9eb0fd9f08e1`)
and unchanged driver configuration: **72/72 conformance tests passed** in the
separate opt-in diagnostic. It observed 1,167 completed startup cycles and 11,324
completed protocol synchronization cycles. All 1,167 connections closed, with
zero pending cycles and zero retained header/queue buffers; diagnostics were
empty. Peak pending occupancy was two per connection and five across the proxy.
`overlapObserved` was true, and `syncExchanges`/`physicalRoundTrips` remained null.
These whole-fixture observations are neither repeatability guarantees nor
per-operation budgets. The original strict failure remains retained separately.

Focused fake-peer tests cover the orchestration independently of PostgreSQL;
they are never benchmark evidence:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/reference-wire-probe.test.mjs
```
