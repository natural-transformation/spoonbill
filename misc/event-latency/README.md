# End-to-end click latency

This harness measures what a user waits for: the time from sending a DOM click
until the DOM patch carrying the new state arrives. The same counter application
is compiled against Spoonbill **1.1.1**, the released **8.0.1** and the working
tree (`local`). Results and their interpretation are in the
[event latency report](../../docs/event-latency-performance.md).

- `shared/plain`: a button and 50 text rows that all change on every click
  (`-Dbench.rows` changes the row count). The handler calls `access.transition`.
- `guarded`: the same page behind the real `BrowserAuthentication` coordinator
  with the in-memory or exclusive JDBC provider. Views are anonymous, so no login
  is needed, but every authorization check runs. `BENCH_MAINTENANCE_MS` sets the
  maintenance cadence (default 30000).
- `local-only`: `jdbc-concurrent` mode, the working tree's JDBC provider with
  concurrent checks enabled (`BENCH_CAP` concurrent checks, default 8). The
  server reports the coordinator's counters at `/bench-stats`, which `run.sh`
  includes as `coordinator`.
- `local-only`, `ExtensionMain`: the `plain-extensions` and `memory-extensions`
  modes add `BENCH_EXTENSIONS` extensions (default 3) to the unguarded and the
  in-memory guarded app. Each extension counts the states it receives, and
  `/bench-stats` reports the total as `statesDelivered`. Compare a run with its
  `plain` or `memory` twin to see what extensions add to a click.
- `local-only`, `SnapshotMain`: the `jdbc-snapshot` and `jdbc-reference` modes
  serve authenticated views whose presentation is a durable projection of the
  count plus `BENCH_PAYLOAD_BYTES` of padding (default 1024). `jdbc-snapshot`
  saves it inline (`persistence.projectedSnapshots`); `jdbc-reference`
  publishes payload references (`PayloadRecovery.projectedWithPayloads` with
  `persistence.snapshotReferences()`) and stores the bytes in the bench-only
  `bench_payload` table through an adapter modelled on the example's
  `JdbcPayloadStore`, with `BENCH_PAYLOAD_DELAY_MS` of extra latency per put and
  get. Both modes share one pool, blocking executor, ownership setting and
  maintenance schedule; the binding's maintenance runs after the browser's.
  `POST /bench-login` signs a client in through the provider SPI (capture,
  claim, begin, prepare with a synthetic host that resolves every session) and
  answers with the binding cookie and the completion id, which the framework's
  `POST /auth/complete` turns into the session cookie, as for a browser.
  `/bench-stats` reports the snapshot session's initializations and commits
  with their server-side latency, the provider's transactions classified by
  their SQL (`snapshotSave`, `snapshotRead`, `cleanup`, `clock`, `authority`),
  the binding's recovery counters and the payload store's counters.
- `local-only`, `SharedMain`: the `jdbc-shared` mode serves authenticated
  views as a provider that shares views across nodes does:
  `BrowserOwnership.SharedViews`, separate warmed pools for provider work and
  the durable clock, and a host that keeps one row per session in the bench
  table `bench_host_session`. Sign-in inserts that row inside the preparation
  transaction, and every `resolve` locks and reads it (`FOR UPDATE`). The
  coordinator gets the provider's own inline snapshot session, unwrapped. The
  mode runs under `scripts/with-test-postgres.sh` with
  `SPOONBILL_BENCH_SQL_PROFILE=1`, which logs every executed statement with
  its backend and application name and no bind values. Each pool, and the
  control connection that writes log markers, has its own application name.
  The run has two phases:
  - **Observation budget.** With maintenance paused and no client clicking, a
    fixture signs in, activates its session, claims an active view and warms
    up. It then calls the provider's `authorize(view)` once at a time
    (`BENCH_SHARED_OBSERVATIONS`, default 20, after `BENCH_SHARED_PROBE_WARMUP`,
    default 10). Control-connection markers bracket each settled call. Once
    the last marker is in the log, the statements between each pair are
    counted per pool, including the driver's `SET`, `SHOW`, `BEGIN`, `COMMIT`
    and `ROLLBACK`; empty validation pings are reported apart. The report
    gives host resolves, clock commits, isolation `SET`s and statements per
    observation, every statement shape, and the first observation's statements
    in order. The budget is eight framework statements (realm lock, retention
    row, token row twice, slot, view and two last-seen renewals), the host's
    statements for each resolve (one row lock, plus the fixed policy lock with
    `BENCH_SHARED_LOCK=global`), the provider transaction's `BEGIN` and
    `COMMIT`, one isolation `SET` per transaction on a pool that is not
    certified, and the clock checkpoints. With the legacy host a shared clock
    commits a checkpoint for every time sample, five per observation of three
    statements each (`BEGIN`, the upsert and `COMMIT`), so the budget is 33
    statements with two resolves. The held host is resolved once and the
    check takes one row-locked checkpoint of four statements (`BEGIN`, the
    locking read, the update and `COMMIT`): 17 statements, or 15 with both
    pools certified. A missing marker, a failed check, an unexpected number of
    resolves or checkpoints, or any one observation over budget fails the run.
  - **Click cost.** The client signs in, opens every user, warms up and waits
    at a barrier; the counters are taken there and again once the measured
    clicks' outstanding server work has drained. `clicks` reports completed
    clicks, measured seconds, clicks per second, click-to-patch percentiles,
    and per click the provider transactions (each one observes the view once:
    a check, an output admission or a snapshot save), snapshot saves, clock
    checkpoints, host resolves and protocol round trips, with rollbacks and
    reconnects.

  Switches: `BENCH_SHARED_HOST` is `legacy` (`resolve` twice per check) or
  `held` (`JdbcHeldHostSessions`: the same locks and read, once per check);
  `BENCH_SHARED_ISOLATION` is `neither`, `work`, `clock` or `both`, the pools
  passed through `JdbcIsolation.readCommittedOnCheckout`; `BENCH_SHARED_LOCK`
  is `session` (the row lock only) or `global` (a fixed transaction-scoped
  advisory lock first, as a host with one policy lock takes);
  `BENCH_SHARED_PROJECTION` is `constant` (the projection omits the count) or
  `changing`; `BENCH_SHARED_SNAPSHOTS` is `ordinary`
  (`persistence.projectedSnapshots`, a save per top-level write) or `guarded`
  (`persistence.projectedSnapshotsIfChanged`: one observation per top-level
  write, and a save only when the encoded projection changed).
  `BENCH_SHARED_CAP` keeps its default, `0`; the server refuses other values
  until the provider supports them.
- `client/latency.mjs`: simulated browsers. Each loads the page, opens the
  WebSocket, waits until the view is ready and clicks in a closed loop. A click
  waits for the event-counter update, exactly as the browser bridge does. A
  closed socket is counted as a reconnect to the same view, not an abort. For
  the snapshot modes `run.sh` sets `BENCH_LOGIN=1` (sign in first) and
  `BENCH_PHASES=1` (open every user before anyone clicks and record the
  server's counters at that boundary); `BENCH_RECONNECT=1` makes every user
  drop its socket after warmup and reconnect to the same view, which restores
  the saved projection (`restoreMs`, timed from the accepted handshake to
  readiness). The reconnect sends the page location as the browser bridge
  does; a handshake that arrives before the server has cleaned up the old
  attachment is refused and retried, and those refusals appear in the run's
  server-log error summary. `BENCH_TRANSPORT=long-polling` makes every user
  poll instead, as a browser whose WebSocket is blocked does (unguarded modes
  only). A page that names a poll wait (`lpw:`) gets the protocol of long
  polling that resumes, with the page id, the cursor and the DOM revision;
  any other page gets the protocol before that change.
- `client/budget.mjs`: the recovery plan's healthy commit budget, asserted from
  the counter deltas of the snapshot modes. Startup (sign-in, page load, session
  open and the initial publication of the empty view) is reported apart from
  the steady-state saves: one `snapshotSave` transaction per commit inline;
  two (reserve, publish), one payload put and no status lookup per
  reference-backed commit. A run that misses the budget exits with status 1.
- `client/shared-report.mjs`: the `jdbc-shared` click phase's report, from the
  counters at the warmup boundary and after the measured work drained.
- `client/pgdelay.mjs`: a loopback PostgreSQL proxy that adds a fixed delay per
  client-to-server message and counts protocol round trips (Sync and Query).
- `client/hold.mjs` and `memory.sh`: live heap per open, clicked session.

The client does not render; browser DOM application time is not included.

## Run

Inside the repository's Nix environment, from the repository root:

```sh
nix develop --no-write-lock-file --command bash misc/event-latency/prepare.sh
# run.sh <v1|v8|local> <plain|memory|jdbc|jdbc-concurrent|jdbc-snapshot|jdbc-reference|jdbc-shared|plain-extensions|memory-extensions> <users> <clicks> <warmup> [thinkMs]
nix develop --no-write-lock-file --command bash misc/event-latency/run.sh local memory 1 200 50
nix develop --no-write-lock-file --command env BENCH_TRANSPORT=long-polling \
  bash misc/event-latency/run.sh local plain 1 200 50
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 bash misc/event-latency/run.sh local jdbc 8 60 10 200
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_CAP=8 BENCH_MAINTENANCE_MS=1000 \
  bash misc/event-latency/run.sh local jdbc-concurrent 16 60 10 200
# Authenticated snapshot modes: inline and reference-backed, same payload size.
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_PAYLOAD_BYTES=1024 BENCH_MAINTENANCE_MS=1000 \
  bash misc/event-latency/run.sh local jdbc-snapshot 8 60 10 200
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_PAYLOAD_BYTES=1024 BENCH_MAINTENANCE_MS=1000 \
  bash misc/event-latency/run.sh local jdbc-reference 8 60 10 200
# Shared views: the observation budget, then one and four users clicking.
nix develop --no-write-lock-file --command env SPOONBILL_BENCH_SQL_PROFILE=1 \
  bash scripts/with-test-postgres.sh env PG_DELAY_MS=0.25 bash misc/event-latency/run.sh local jdbc-shared 1 100 30
nix develop --no-write-lock-file --command env SPOONBILL_BENCH_SQL_PROFILE=1 \
  bash scripts/with-test-postgres.sh env PG_DELAY_MS=0.25 BENCH_SHARED_LOCK=global BENCH_SHARED_PROJECTION=changing \
  bash misc/event-latency/run.sh local jdbc-shared 4 50 20
# memory.sh <variant> <mode> <sessions>
nix develop --no-write-lock-file --command bash misc/event-latency/memory.sh local memory 200
```

Rebuild the working tree (`sbt pekko/compile securityJdbc/compile`) before
measuring `local` again; its classpath points at the repository's classes.
Each run prints one JSON line: click-to-patch (`patchMs`) and click-to-next-click
(`readyMs`) percentiles, PostgreSQL round trips per click (session setup and
warmup amortized), and timer objects still live after a full GC. The snapshot
modes add `openMs` (sign-in, page load and session open per user) and `budget`:
the startup and steady-state counter deltas, their per-session and per-click
ratios, the server-side `initializeMs` and `commitMs` distributions and the
list of budget violations, empty when the run passed. The guarded server uses a
fixed, benchmark-only credential key and a loopback origin; never reuse it
outside this harness.
