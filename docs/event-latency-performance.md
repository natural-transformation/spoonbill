# Event latency: Spoonbill 1.x, 8.0.1 and the current change

Measured 2026-11-03 on one Apple-silicon machine (16 cores, JDK 21, PostgreSQL 14),
with the [end-to-end click harness](../misc/event-latency/README.md).

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
163 clicks per second at a p50 of 17 to 20 ms. Not measured: the write mix
(sign-ins, logout, `transact`), authenticated users and a hot account, so
writer latency under load is still open; the harness client has no sign-in
flow. The coordinator's cumulative writer wait, reported as `writerWaitMs`, is
dominated by the simultaneous session opens at the start of a run, which are
exclusive turns queued behind one another and grow with the square of the user
count.

## Remaining limits

Without the opt-in, guarded JDBC authorization is serialized per realm, as
documented: by the coordinator gate in each process and by the realm advisory
lock (plus the retention row lock) in PostgreSQL, with a ceiling of about 40 to
54 clicks per second per realm at a 0.25 ms database delay depending on the
maintenance cadence. The
[concurrent-checks opt-in](browser-authentication.md#concurrent-authority-checks)
lifts it for `ExclusiveScope` deployments, as the table above shows. What
remains:

1. The write mix, authenticated users and a hot account are not measured; the
   harness client has no sign-in flow.
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
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 bash misc/event-latency/run.sh local jdbc 16 60 10 200
# The concurrent-checks matrix: both modes, maintenance every second, cap 16.
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env PG_DELAY_MS=0.25 BENCH_MAINTENANCE_MS=1000 BENCH_CAP=16 \
  bash misc/event-latency/run.sh local jdbc-concurrent 48 60 10 200
```
