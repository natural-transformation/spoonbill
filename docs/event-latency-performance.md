# Event latency: Spoonbill 1.x, 8.0.1 and the current change

Measured 2026-11-03 on one Apple-silicon machine (16 cores, JDK 21, PostgreSQL 14),
with the [end-to-end click harness](../misc/event-latency/README.md); the
[authenticated snapshot modes](#authenticated-views-with-durable-snapshots-inline-and-reference-backed)
were measured on 2026-11-08 on the same machine.

**Result.** Unguarded and in-memory guarded apps were never the problem: a click
takes about 1 ms in 1.1.1, 8.0.1 and now. The lag came from guarded sessions with
the JDBC provider. A single click made **243 PostgreSQL round trips** in 8.0.1. It
now makes **47**. With a 0.25 ms database delay, one user's click went from
75.6 ms to 18.3 ms (p50). Sixteen concurrent users went from 937 ms to about
32 ms. No security check was removed from a boundary, and no authorization is
cached.

## What changed since 1.x

Spoonbill 1.x had no browser authentication. Every design change below came
with the guarded runtime (2.0) or its providers (4.0, 5.0). They are listed by
cost.

1. **Authorization amplification.** One guarded click that changes state
   observed authority **14 times**. Several checks repeated the one just before
   them with no application code in between: the action queue and then the
   action itself, the action and then the first handler, a frame's enqueue and
   then its release, and the current and then the produced presentation of the
   same frame. Guarded sessions without extensions also authorized every state
   change, only to call no callback.
2. **Each observation is expensive.** It takes a turn of the process-wide
   `BrowserCoordinatorGate`, arms a 10-second bounded wait and forks the guard
   call. With JDBC it is a full write transaction under the realm advisory lock:
   about nine round trips (isolation, lock, retention row, slot, view, two
   `last_seen` renewals, commit).
3. **The durable JDBC clock commits once per time sample.** The JDK clock is
   sub-millisecond, so nearly every `now()` was newer than the checkpoint and
   committed another transaction. One authorization samples time several times.
4. **Timer retention.** The scheduler used `java.util.Timer`, which keeps a
   canceled task, and the callback it holds, until its original due time. Each
   bounded wait (10 s), idle timeout (60 s) and RPC deadline therefore stayed
   live. A busy guarded server held over 10,000 timer objects.
5. **Client mutation observer.** The browser bridge observed every DOM mutation
   of the whole document, even with no sensitive region shown.

The global serialization of guarded authorization (the coordinator gate and the
JDBC realm advisory lock) is a documented security design. It is unchanged; see
[remaining limits](#remaining-limits).

## Changes

- **One observation per boundary.** A click now observes authority at its five
  boundaries: event admission, the transition's read, the transition's write,
  the render's snapshot and the patch's release. `GuardedEventObservationSpec`
  pins this. Each observation is fresh; nothing is reused across boundaries.
  A custom `SessionGuard` that does not combine presentations sees 7
  `authorize` calls, one per presentation checked.
  - The action queue's admission check also covers the first handler. A later
    bubbling handler starts after earlier user effects, so it checks again.
  - A write checks the current and the candidate presentation in one
    observation (`SessionGuard.authorizeEach`, internal). A snapshot checks the
    current state before reading the snapshot, and checks again only when a
    concurrent write replaced the root.
  - Single-owner output is checked once, at release, for both the current and
    the produced presentation. This is the rule shared-ownership output admission
    already followed. A rendered frame's presentation was already authorized by
    the guarded snapshot it was rendered from, so handlers are never published
    for an unauthorized render.
- **Clock checkpoint lease.** An exclusive `JdbcBrowserClock` commits each
  checkpoint one second ahead of the time it returns, so at most one clock
  transaction runs per second, and concurrent callers share it. Returned times
  never exceed an acknowledged checkpoint, so a restart cannot revive observed
  expiry. After a quick restart time can run up to one second ahead, which
  expires authority early, not late. Shared clock writers still consult storage
  on every observation.
- **Remove-on-cancel scheduler.** The scheduler now uses one
  `ScheduledThreadPoolExecutor` thread with remove-on-cancel. The public API and
  the non-daemon thread are unchanged.
- **Smaller per-event costs.** Event-id regexes are compiled once, and queued
  user actions use a local counter instead of `UUID.randomUUID()`. The client
  observes document mutations only while a sensitive region is shown.

## Results

`p50`/`p99` are click-to-patch milliseconds. Users click in a closed loop; the
multi-user runs wait 100–200 ms between clicks. The client does not render, so
browser DOM time (well under 1 ms for this patch) is not included.

### Unguarded and in-memory guarded

| Variant | 1 user p50 / p99 | 16 users p50 / p99 | Live timer objects | Heap per session |
| --- | ---: | ---: | ---: | ---: |
| 1.1.1, unguarded | 0.72 / 1.75 | 1.43 / 9.35 | 711 | 146 KB |
| 8.0.1, unguarded | 0.89 / 2.24 | 1.85 / 11.26 | 711 | 151 KB |
| current, unguarded | 0.80 / 1.75 | 1.66 / 11.97 | 10 | 148 KB |
| 8.0.1, guarded memory | 1.11 / 2.47 | 1.94 / 10.32 | 10,571 | 156 KB |
| current, guarded memory | 0.98 / 2.56 | 2.56 / 11.23 | 10 | 151 KB |

All of these stay far below 100 ms. Multi-user differences are within run noise:
the single-threaded client dominates them. Timer objects were counted after the
single-user run.

### Guarded JDBC (exclusive ownership)

The delay is added to every client-to-server PostgreSQL message by a loopback
proxy; 0.25 ms is a typical same-datacenter database.

| Users | Delay | Round trips per click | 8.0.1 p50 / p99 | Current p50 / p99 | Clicks/s (8.0.1 → current) |
| ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | none | 243 → 47 | 16.4 / 19.5 | 5.5 / 8.3 | 41 → 93 |
| 1 | 0.25 ms | 246 → 48 | 75.6 / 101.2 | 18.3 / 20.1 | 10 → 36 |
| 1 | 1 ms | 255 → 48 | 254.4 / 262.2 | 55.1 / 63.1 | 3 → 14 |
| 8 | 0.25 ms | 254 → 48 | 366.6 / 458.5 | 23.6 / 50.6 | 11 → 28 |
| 16 | 0.25 ms | 269 → 48 | 936.7 / 1095.2 | 31–34 / 50–80 | 10 → 52 |
| 24 | 0.25 ms | — → 48 | not run | 149.2 / 190.2 | → 54 |

Three 16-user runs of the current build gave p99 values of 50, 58 and 80 ms.

### Guarded JDBC with concurrent checks

The [concurrent authorization design](concurrent-authorization-design.md) adds
an opt-in for the JDBC provider: checks run in concurrent coordinator read
turns, one at a time per browser binding, and hold the realm lock in shared
mode. Its acceptance benchmark ran in both modes: 8, 16, 24 and 48 users, a
0.25 ms database delay, maintenance every second, 60 measured clicks per user
after 10 warmup clicks, 200 ms of think time, and a cap of 16 concurrent checks.
Two passes ran back to back on a shared machine whose load average fell from 69
to 10 during the first pass and from 48 to 12 during the second; the exclusive
run came first in each pair, so it saw the higher load. Latencies are
click-to-patch milliseconds.

| Users | Exclusive p50 / p99, pass 1 and 2 | Exclusive clicks/s | Concurrent p50 / p99, pass 1 and 2 | Concurrent clicks/s |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 29.1 / 94.6, 21.6 / 2822.2 | 27, 9 | 21.1 / 37.2, 22.4 / 35.1 | 29, 29 |
| 16 | 97.6 / 240.0, 78.5 / 139.4 | 42, 44 | 30.5 / 35.7, 29.8 / 42.0 | 56, 56 |
| 24 | 237.9 / 369.0, 243.3 / 297.0 | 43, 44 | 36.3 / 46.7, 36.4 / 44.3 | 81, 81 |
| 48 | 754.8 / 858.6, 756.5 / 861.4 | 40, 41 | 20.1 / 52.5, 16.6 / 32.8 | 163, 165 |

Round trips per click: 50 to 57 exclusive, 45 concurrent. Reconnects and
queue-bound rejections: none in any run. Checks that waited for their binding:
20 to 88 per run, where maintenance revalidation met a click's check on the
same binding. The second pass's 8-user exclusive run stalled under load; its
p50 matches the first pass.

Against the design's acceptance criteria for what was measured: p99 click
latency stayed under 100 ms with concurrent checks at every load, between 33
and 53 ms, and successful clicks per second never fell below the exclusive
baseline's. The exclusive mode saturates at about 40 to 44 clicks per second
with maintenance every second, as the serialized design predicts; the
concurrent mode is bounded by the clients' think time, and 48 users produce
163 clicks per second at a p50 of 17 to 20 ms. Not measured here: the write
mix (sign-ins, logout, `transact`) and a hot account, so writer latency under
load is still open; authenticated users with durable snapshots are measured in
the next section. The coordinator's cumulative writer wait, reported as `writerWaitMs`, is
dominated by the simultaneous session opens at the start of a run, which are
exclusive turns queued behind one another and grow with the square of the user
count.

### Authenticated views with durable snapshots: inline and reference-backed

[External payload storage](clustered-browser-authentication.md#external-payload-storage)
adds an opt-in path for snapshot payloads beside the inline PostgreSQL
snapshots. Its budget for a healthy save in an initialized session is one
snapshot-provider transaction inline, against two provider transactions
(reserve, publish) plus one durable payload put and no status lookup when the
payload lives behind a published reference. The harness's `jdbc-snapshot` and
`jdbc-reference` modes measure what each costs a click on authenticated views,
with everything else equal: the same counter page, the same JDBC provider in
exclusive ownership without concurrent checks, one pool of 32 connections, one
blocking executor of 16 threads, the default retention policy and the same
maintenance schedule. The projection is the count plus a padding string of
1 KB or 64 KB. The payload adapter is modelled on the example's
`JdbcPayloadStore` and writes to a bench-only table in the same PostgreSQL
database, so a reference-backed save reaches the same server as the provider's
rows, through the same delaying proxy; `BENCH_PAYLOAD_DELAY_MS` adds latency to
the adapter alone, for a store slower than that database.

The client signs in before it loads the page: `POST /bench-login` provisions a
session through the provider SPI (capture, claim, begin and prepare, with a
synthetic host that resolves every session id), the framework's
`/auth/complete` delivers the session cookie, and the first handshake activates
it. Every user is opened before anyone clicks, and the server's counters are
read at that boundary, so the initial publication of the empty view is reported
apart from the steady-state saves. The run asserts the budget from counter
deltas instead of inferring it from latency. The server classifies every
committed provider transaction by its SQL: a save, reservation or publication;
a snapshot read, pin or status; cleanup of retired references; a clock
checkpoint; and everything else, which is an authority check, claim, capture,
ceremony or retention sweep. The binding reports its reservations, publications,
status lookups, uploads and downloads, and the adapter its puts, gets and bytes.
A run that misses the budget exits with status 1; every run below met it
exactly.

Measured 2026-11-08 on the same machine, one pass per cell, with a 0.25 ms
database delay, 60 measured clicks per user after 10 warmup clicks, 200 ms of
think time for 8 and 16 users and a closed loop for 1 user (200 clicks after 50).
The machine was shared: its load average was between 4 and 12 throughout, so
differences under about 10 percent between cells are noise. Latencies are
click-to-patch milliseconds; the anonymous `jdbc` mode of the same build is the
baseline. Provider transactions per click are the five authority checks plus the
save path; the budget columns are exact counts, the rest are averages.

| Users | Mode | Payload | p50 / p95 / p99 | Clicks/s | Round trips per click | Save transactions per click | Payload puts per click |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | anonymous `jdbc` | — | 20.4 / 21.6 / 22.8 | 36 | 46 | 0 | 0 |
| 1 | `jdbc-snapshot` | 1 KB | 32.4 / 35.0 / 36.2 | 22 | 70 | 1 | 0 |
| 1 | `jdbc-reference` | 1 KB | 42.7 / 45.9 / 47.7 | 17 | 92 | 2 | 1 |
| 1 | `jdbc-snapshot` | 64 KB | 33.2 / 34.9 / 35.4 | 22 | 70 | 1 | 0 |
| 1 | `jdbc-reference` | 64 KB | 43.2 / 48.9 / 50.3 | 17 | 92 | 2 | 1 |
| 8 | anonymous `jdbc` | — | 22.4 / 34.8 / 39.7 | 28 | 48 | 0 | 0 |
| 8 | `jdbc-snapshot` | 1 KB | 44.7 / 63.0 / 88.7 | 24 | 70 | 1 | 0 |
| 8 | `jdbc-reference` | 1 KB | 86.1 / 116.4 / 129.6 | 21 | 92 | 2 | 1 |
| 8 | `jdbc-snapshot` | 64 KB | 52.2 / 75.1 / 93.1 | 24 | 70 | 1 | 0 |
| 8 | `jdbc-reference` | 64 KB | 81.6 / 108.5 / 116.9 | 22 | 92 | 2 | 1 |
| 16 | anonymous `jdbc` | — | 95.5 / 118.8 / 127.1 | 42 | 48 | 0 | 0 |
| 16 | `jdbc-snapshot` | 1 KB | 255.3 / 289.3 / 310.6 | 27 | 70 | 1 | 0 |
| 16 | `jdbc-reference` | 1 KB | 365.0 / 405.4 / 427.7 | 22 | 93 | 2 | 1 |
| 16 | `jdbc-snapshot` | 64 KB | 257.7 / 295.1 / 317.9 | 27 | 70 | 1 | 0 |
| 16 | `jdbc-reference` | 64 KB | 372.4 / 417.5 / 462.3 | 22 | 93 | 2 | 1 |

Status lookups, downloads, failures, rollbacks and client reconnects were zero
in every run of this table. The save path's own cost, measured on the server
from the coordinator's commit call to its completion (1 KB):

| Users | Inline commit p50 / p95 / p99 | Reference commit p50 / p95 / p99 | Statements per save transaction (inline, reference) |
| ---: | ---: | ---: | ---: |
| 1 | 6.8 / 7.8 / 9.1 | 16.9 / 18.3 / 20.1 | 12, 13.5 |
| 8 | 6.9 / 11.1 / 16.1 | 21.9 / 27.5 / 45.5 | 12, 13.5 |
| 16 | 10.5 / 15.3 / 15.8 | 27.0 / 46.7 / 49.3 | 12, 13.5 |

Startup, per session, counted from the first sign-in request to the ready
frame. Both modes spend the same 31 authority transactions on sign-in, page
load and the handshake; the snapshot session then reads the empty view and
publishes its first projection.

| Mode | Snapshot transactions | Payload puts | Round trips | Open p50 for 1 / 8 / 16 users | Server-side initialize p50 for 1 / 8 / 16 users |
| --- | ---: | ---: | ---: | ---: | ---: |
| anonymous `jdbc` | 0 | 0 | — | 378 / 844 / 1400 | — |
| `jdbc-snapshot` | 1 read, 1 save | 0 | 347 to 398 | 532 / 1436 / 2552 | 25.6 / 42.5 / 33.5 |
| `jdbc-reference` | 1 read, 2 saves | 1 | 369 to 420 | 536 / 1619 / 2748 | 43.3 / 79.3 / 87.0 |

Three further scenarios, all at 1 KB:

| Scenario | Mode | p50 / p95 / p99 | Round trips per click | What else happened |
| --- | --- | ---: | ---: | --- |
| Maintenance every second, 16 users | `jdbc-snapshot` | 300.2 / 350.7 / 377.0 | 76 | 1 cleanup transaction |
| Maintenance every second, 16 users | `jdbc-reference` | 441.7 / 488.9 / 510.0 | 109 | 1164 cleanup transactions and 1120 payload deletes: one claim, delete and completion per superseded reference |
| Reconnect after warmup, 8 users | `jdbc-snapshot` | 44.3 / 67.1 / 192.4 | 73 | 8 restorations, p50 517 ms, each one snapshot read |
| Reconnect after warmup, 8 users | `jdbc-reference` | 81.7 / 143.0 / 227.0 | 95 | 8 restorations, p50 572 ms, each two snapshot reads and one download |
| Payload store 2 ms slower, 8 users | `jdbc-reference` | 83.2 / 141.9 / 229.0 | 92 | server commit p50 24.3 ms against 21.9 ms |

What the numbers say:

- **The budget holds.** In all 17 snapshot runs an inline save was exactly one
  provider transaction, and a reference-backed save exactly two transactions and
  one durable put, with no status lookup. The five authority checks per click
  are unchanged from the anonymous mode in both.
- **Inline costs one more transaction, references two more and a payload
  write.** The inline save adds 12 statements and about 24 round trips to a
  click; references add another 22 round trips. Alone, one user pays 12 ms for
  inline snapshots over the anonymous click and another 10 ms for references at
  a 0.25 ms database delay, which matches the server-side save cost of 6.8
  against 16.9 ms.
- **Under load the price is queueing, not round trips.** The exclusive realm
  lock and the coordinator's exclusive turns serialize every provider
  transaction, so each transaction a click adds waits behind every other user's.
  The reference path costs 41 ms more than inline at 8 users and 110 ms more at
  16, where the realm saturates at 22 to 27 clicks per second against 42
  anonymous. Payload size made no measurable difference between 1 KB and 64 KB
  in either mode: the bytes travel inside one statement.
- **Cleanup is visible once maintenance runs often.** With maintenance every
  second, every superseded reference is claimed, deleted and completed within
  the run, one short bookkeeping transaction and one adapter delete per click,
  and 16 users pay 77 ms more at the median than with the default cadence
  (inline pays 45 ms more, for the revalidation turns alone).
- **Restoration takes about half a second here** for a same-view reconnect of
  8 simultaneous users, dominated by the new attachment's claim and checks; the
  payload fetch costs references 55 ms more. The reconnect must carry the page
  location, as the browser bridge does, or the server asks for a reload.
- **A slower store charges only the saving click.** Two extra milliseconds per
  put moved the server-side commit by about the same amount and the click
  median not at all; the put runs outside the realm lock, so other users are not
  held up by it. Tail latencies rose, within what one pass can tell.

Caveats: the synthetic host adds no SQL to a check, so a real host's session
lookup comes on top; the payload table shares the database and the pool with
the provider, so this is not an external store's network cost; one node served
everything, so restoration never crossed a process; every cell is one pass on a
loaded shared machine; and the client does not render.

### Extensions

Measured 2026-11-14 for the [extension contract](extension-contract-design.md),
on the same machine. The base is 10.0.1. The change is the extension runtime.
The `plain-extensions` and `memory-extensions` modes add three extensions that
receive every state change. Each cell is the median over the runs of p50 / p99,
in milliseconds from click to patch. The two builds ran in turns.

| Mode | Users | Runs | 10.0.1 | With the extension runtime |
| --- | ---: | ---: | ---: | ---: |
| `plain` | 1 | 3 | 0.44 / 1.47 | 0.41 / 1.07 |
| `memory` | 1 | 3 | 0.49 / 1.13 | 0.47 / 1.05 |
| `plain-extensions` | 1 | 3 | 0.40 / 0.85 | 0.43 / 1.11 |
| `memory-extensions` | 1 | 3 | 0.48 / 1.11 | 0.46 / 1.03 |
| `plain-extensions` | 16 | 2 | 1.30 / 5.83 | 1.14 / 6.17 |
| `memory` | 16 | 6 | 1.12 / 5.79 | 1.14 / 5.74 |
| `memory-extensions` | 16 | 6 | 1.08 / 6.11 | 1.17 / 5.28 |

No cell differs by more than the spread between the runs of one build. With 16
users, single runs ranged from 1.0 to 1.9 ms at p50 in both builds. A one-user
run made 2,000 clicks after 300 warmup clicks. A 16-user run made 300 or 500
clicks per user.

Extensions are not on the click path. Delivery runs on its own lane, off the
thread that writes the state, and rendering does not wait for it. A guarded
session makes one more authority observation per delivered state, as in
10.0.1. With the in-memory provider that is not visible here. With JDBC it is
one more provider transaction per state change; that was not measured.

## Remaining limits

Without the opt-in, guarded JDBC authorization is serialized per realm, as
documented: by the coordinator gate in each process and by the realm advisory
lock (plus the retention row lock) in PostgreSQL, with a ceiling of about 40 to
54 clicks per second per realm at a 0.25 ms database delay depending on the
maintenance cadence. The
[concurrent-checks opt-in](browser-authentication.md#concurrent-authority-checks)
lifts it for `ExclusiveScope` deployments, as the table above shows. What
remains:

1. The write mix and a hot account are not measured. The harness signs in
   through its own provisioning route, so sign-in itself is not a measured
   workload, and its synthetic host adds no SQL to a check.
2. Cutting the authorization transaction's round trips, for example by
   combining the view and slot checks with their lease renewals, the design's
   phase 2.
3. Authorizing concurrent views in one provider transaction was considered and
   not pursued; it keeps the serialization.

Shared-ownership (`SharedViews`) deployments cannot opt in: their clock
observations still commit on every call.

## Reproduce

See the [harness README](../misc/event-latency/README.md). The commands used:

```sh
nix develop --no-write-lock-file --command bash misc/event-latency/prepare.sh
nix develop --no-write-lock-file --command bash misc/event-latency/run.sh local memory 1 300 50
nix develop --no-write-lock-file --command bash misc/event-latency/memory.sh local memory 200
# The extension modes: three extensions that receive every state change.
nix develop --no-write-lock-file --command bash misc/event-latency/run.sh local plain-extensions 1 2000 300
nix develop --no-write-lock-file --command bash misc/event-latency/run.sh local memory-extensions 16 500 150
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 bash misc/event-latency/run.sh local jdbc 16 60 10 200
# The concurrent-checks matrix: both modes, maintenance every second, cap 16.
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_MAINTENANCE_MS=1000 BENCH_CAP=16 \
  bash misc/event-latency/run.sh local jdbc-concurrent 48 60 10 200
# The snapshot matrix: both modes at 1 KB and 64 KB for 1, 8 and 16 users;
# the run exits with status 1 when the commit budget is missed.
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_PAYLOAD_BYTES=1024 \
  bash misc/event-latency/run.sh local jdbc-snapshot 8 60 10 200
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_PAYLOAD_BYTES=65536 \
  bash misc/event-latency/run.sh local jdbc-reference 16 60 10 200
# Restoration, maintenance every second and a slower payload store.
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_RECONNECT=1 \
  bash misc/event-latency/run.sh local jdbc-reference 8 60 10 200
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_MAINTENANCE_MS=1000 BENCH_PAYLOAD_DELAY_MS=2 \
  bash misc/event-latency/run.sh local jdbc-reference 16 60 10 200
```
