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
- `client/latency.mjs`: simulated browsers. Each loads the page, opens the
  WebSocket, waits until the view is ready and clicks in a closed loop. A click
  waits for the event-counter update, exactly as the browser bridge does. A
  closed socket is counted as a reconnect to the same view, not an abort.
- `client/pgdelay.mjs`: a loopback PostgreSQL proxy that adds a fixed delay per
  client-to-server message and counts protocol round trips (Sync and Query).
- `client/hold.mjs` and `memory.sh`: live heap per open, clicked session.

The client does not render; browser DOM application time is not included.

## Run

Inside the repository's Nix environment, from the repository root:

```sh
nix develop --no-write-lock-file --command bash misc/event-latency/prepare.sh
# run.sh <v1|v8|local> <plain|memory|jdbc> <users> <clicks> <warmup> [thinkMs]
nix develop --no-write-lock-file --command bash misc/event-latency/run.sh local memory 1 200 50
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 bash misc/event-latency/run.sh local jdbc 8 60 10 200
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_CAP=8 BENCH_MAINTENANCE_MS=1000 \
  bash misc/event-latency/run.sh local jdbc-concurrent 16 60 10 200
# memory.sh <variant> <mode> <sessions>
nix develop --no-write-lock-file --command bash misc/event-latency/memory.sh local memory 200
```

Rebuild the working tree (`sbt pekko/compile securityJdbc/compile`) before
measuring `local` again; its classpath points at the repository's classes.
Each run prints one JSON line: click-to-patch (`patchMs`) and click-to-next-click
(`readyMs`) percentiles, PostgreSQL round trips per click (session setup and
warmup amortized), and timer objects still live after a full GC. The guarded
server uses a fixed, benchmark-only credential key and a loopback origin; never
reuse it outside this harness.
