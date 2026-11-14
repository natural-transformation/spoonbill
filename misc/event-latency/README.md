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
  server-log error summary.
- `client/budget.mjs`: the recovery plan's healthy commit budget, asserted from
  the counter deltas of the snapshot modes. Startup (sign-in, page load, session
  open and the initial publication of the empty view) is reported apart from
  the steady-state saves: one `snapshotSave` transaction per commit inline;
  two (reserve, publish), one payload put and no status lookup per
  reference-backed commit. A run that misses the budget exits with status 1.
- `client/pgdelay.mjs`: a loopback PostgreSQL proxy that adds a fixed delay per
  client-to-server message and counts protocol round trips (Sync and Query).
- `client/hold.mjs` and `memory.sh`: live heap per open, clicked session.

The client does not render; browser DOM application time is not included.

## Run

Inside the repository's Nix environment, from the repository root:

```sh
nix develop --no-write-lock-file --command bash misc/event-latency/prepare.sh
# run.sh <v1|v8|local> <plain|memory|jdbc|jdbc-concurrent|jdbc-snapshot|jdbc-reference|plain-extensions|memory-extensions> <users> <clicks> <warmup> [thinkMs]
nix develop --no-write-lock-file --command bash misc/event-latency/run.sh local memory 1 200 50
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
