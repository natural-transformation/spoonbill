# Concurrent guarded authorization: design

Status: revision 5, 2026-11-03. Steps 1 to 5 of the implementation plan, the
maintenance split, the gate's read turns, the coordinator's read paths, the
JDBC provider's opt-in, the clock's background renewal and the docs, are
implemented in the working tree, and the benchmark ran for the anonymous click
path; see [measured](#measured). It
follows the [event latency work](event-latency-performance.md) and folds in
four reviews and a writer trace for open question 8; see
[review record](#review-record).

## Summary

Guarded JDBC apps stop scaling at about **54 clicks per second per realm**
(0.25 ms database delay). Past that, clicks queue: 24 users see p50 149 ms. The
cause is serialization of authority *checks*. Each check holds the process-wide
coordinator gate and the realm's exclusive advisory lock. Checks never change
authority, so they only need to exclude writers, not each other.

The proposal, for the JDBC provider with `ExclusiveScope` ownership only, and
off by default:

1. **Explicit opt-in** per deployment. It requires a reserved, bounded clock
   connection and a cap on concurrent checks, and it refuses `SharedViews`.
2. **Reader/writer coordinator gate.** Checks take concurrent, capped *read
   turns*. Every other coordinator operation keeps its exclusive turn. Over the
   cap, checks queue; the existing queue bound stays the only rejection point.
   A denied view stays denied.
3. **Shared realm lock for checks.** JDBC checks take the realm advisory lock in
   shared mode. Every writer keeps the exclusive lock. A trace of every writer
   found no invariant that needs checks under any realm lock; dropping it is
   [open question 8](#open-questions).
4. **Maintenance in short turns.** Per-view revalidation leaves the long
   maintenance turn and gets its own small budget. This also removes a latency
   stall that exists today.
5. **Background clock renewal.** In the normal case no check performs clock
   I/O inside its transaction; only an expired lease falls back to today's
   serialized renewal.

Freshness is unchanged: every check is still a new provider transaction, and
nothing is cached. A synthetic database test shows the shared lock lifting the
check ceiling from about 400 to 6,600 transactions per second.

## Goal and limits

- Keep click latency flat as concurrent users grow, up to database capacity.
- Keep every security property: no stale authority, no check that succeeds
  after a revocation it should observe, final denials, no writer starvation.
- No schema change, no caching, no change to writer operations, and no
  behavior change for deployments that do not opt in.
- Not covered: `SharedViews` deployments (their clock is a cluster-wide hot row;
  see [the durable clock](#5-durable-clock)), the in-memory provider (no gain:
  its monitor serializes calls anyway), and fewer round trips per check
  (phase 2).

## Current model

| Layer | What serializes | Held during |
| --- | --- | --- |
| Coordinator ([`BrowserCoordinatorGate`](../modules/spoonbill/src/main/scala/korolev/auth/BrowserCoordinatorGate.scala)) | One operation at a time per process | The whole provider call, including its database transaction |
| JDBC transaction ([`JdbcBrowserPersistence.transaction`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserPersistence.scala)) | `pg_advisory_xact_lock` on the realm key, then the retention row `FOR UPDATE` | Every provider transaction until commit |
| Rows | Host rows (inside `host.resolve`), then slot `FOR UPDATE`, then view `FOR UPDATE` | Until commit |

The documented lock order is realm, ceremony, host, slot, view. One anonymous
check (`current`) takes about nine round trips while both serializers are held.
An authenticated check also resolves the host session twice; the example host
locks the account and session rows `FOR UPDATE` each time. A click makes five
checks. Every provider transaction runs at read committed, so a row lock taken
after a wait returns the committed row, not a stale snapshot.

**A stall that exists today.** `maintenance()` is one gate turn. It detaches
retiring views, runs provider maintenance, then checks every owned view in
sequence. Every other operation, including every click's checks, waits for the
whole sweep. The examples run maintenance every second; the docs suggest 30
seconds. At about 5 ms per check, 1,000 owned views block the realm for 5
seconds per sweep.

**What a failed check costs today.** The session service treats any failure of
a guard check as a denial: it marks the session dead and closes the connection.
That includes the gate's capacity rejection and the ten-second bound on each
guard call, not only a revoked authority. The client then reconnects. A
reconnect is one exclusive gate turn with two or three exclusive provider
transactions: `capture` with a possible reap, `claim`, and `authorize`. So a
rejected check costs more than the check it shed.

### Checks and writers

**Checks.** These verify authority without changing authority or ownership:

- `authorize`, `admitOutput`, `admitOutputIf`: about 5 per click.
- `inspectPrincipal` (HTTP page loads) and `pending` (connection setup).

They still write. They renew the view and slot `last_seen` leases. They may
commit a durable-clock checkpoint on a separate connection. `pending` locks the
ceremony row. They also do whatever host `resolve` does, such as the example's
row locks.

**Everything else keeps its exclusive lock,** and, where it has one today, its
exclusive turn. These operations change authority, ownership, ceremonies,
delivery or retention, or are rare administrative scans:

- `capture` (activation), `registerView`, `claim`, `release`, `retireView`
- ceremonies (`begin`, `bindChallenge`, `resume`, `prepare`, `recover`)
- replacement operations, `deliver`, `logout`
- `maintenance`, reaping, capacity sampling
- the ungated host calls `transact` (host business writes),
  `withSuccessorCompletion`, `usage` and `ownedViewPage`. They take no
  coordinator turn today, only the exclusive database lock, so gate fairness
  never applies to them; the database lock orders them against checks.

## Proposal

### 1. Explicit opt-in

Concurrent checks change two contracts: provider calls become concurrent, and
host code (`JdbcHostSessions.resolve`, a host `existingRunner`, snapshot identity
checks) runs concurrently. So it must be the deployment's choice:

- A new `JdbcBrowserPersistence` constructor overload (binary compatible) takes a
  `JdbcConcurrentChecks` setting: the reserved clock `DataSource` and a cap on
  concurrent checks. Without it, behavior is exactly today's. The maintenance
  budget is fixed at one, because the sweep revalidates one view at a time.
- The overload refuses `BrowserOwnership.SharedViews` with a `require`; the
  opt-in applies to `ExclusiveScope` ownership only.
- The coordinator reads the setting through an internal SPI method on
  `BrowserPersistence`, `checkConcurrency`, which returns `None` by default.
  `JdbcBrowserPersistence` overrides it to return the configured setting,
  including the cap. Third-party providers inherit `None`, so they never opt in
  by accident.
- Two simpler mechanisms do not work. A final class cannot mix in a marker trait
  for only some of its instances. A new `BrowserProviderCapabilities` field
  breaks binary compatibility, because that is a final case class. A trait
  method with a default has neither problem.
- **Sizing rule**, documented with the overload: the cap, plus one for
  maintenance, plus one exclusive writer, must fit in the main pool; the clock
  source is separate. The gate's read cap and the provider's check budget are
  both the cap plus one, because foreground checks and maintenance
  revalidation enter the provider through the same admission, so the budget
  never rejects a check the gate admitted.
- The changelog lists it as a behavior change for opted-in deployments.
- The clock source's own timeouts are part of the requirement; see
  [the durable clock](#5-durable-clock).

### 2. Reader/writer coordinator gate

Add `read(key)(operation)` to `BrowserCoordinatorGate`. The key is the browser
binding.

- A read turn runs concurrently with other read turns and never with a write
  turn. The existing ordinary, cleanup and close turns are write turns.
- Turns are granted first come, first served. A waiting writer blocks every
  later reader, so writers cannot starve. Consecutive waiting readers are
  granted together, up to the cap; the rest stay queued and are never rejected
  at grant time.
- **Running reads are capped** (the configured limit). It must stay below the
  provider's check budget and the main pool size. Today the gate only bounds
  queued turns, so nothing would otherwise limit checks inside transactions.
- **One running check per browser binding.** Further checks for that binding
  wait in the process, not in the database. Checks for one binding already
  serialize on its slot row. Waiting in the process keeps them from holding a
  connection and the shared lock while queued behind each other. A read waiting
  for its binding holds no running-read slot. HTTP checks are keyed by the
  binding cookie.
- **Over the cap, a read waits in the gate's queue.** The existing queue bound
  (`maxPendingOperations`) stays the only rejection point, with today's
  semantics. The cap limits database concurrency; the queue bound limits
  overload. Rejecting at the cap was considered and dropped: a rejected check
  closes the session, and the reconnect is a write turn that drains every
  running read, so rejection under load adds load instead of shedding it. See
  [open question 3](#open-questions).
- The same uncancelable execution and caller-effect execution apply. Close waits
  for running reads and rejects new ones. Reentry stays unsupported, as today:
  the gate detects nothing, and a read started inside any turn would wait
  forever behind a queued writer that is itself waiting for that turn.
- **Publication.** Read turns run on other threads. The retiring flag becomes an
  `AtomicBoolean`. The attachment table is published to read turns by the
  gate's monitor hand-off on grant and by the effect runtime's asynchronous
  boundary; read turns never mutate it.
- **Observability.** The gate counts running reads, queued reads, per-binding
  waits, queue-bound rejections, and writer wait time. The benchmark's
  acceptance criteria and the operator sizing the cap both need them.

`BrowserAuthentication` uses read turns only for these checks:

- the guard's `authorize` and `authorizeEach`
- shared output admission
- action authority `resolve` and `revalidate`
- `currentView`, `authorizeHttp` and `currentPrincipal`

**A denied view stays denied.** Today a failed check detaches the view in the
same turn, so every later operation finds no attachment. A read turn cannot
detach, because detaching mutates the attachment table and releases provider
ownership. Instead:

1. A read turn whose check fails for any reason marks the view retiring: a
   revoked authority, a disallowed presentation, or a transient failure such as
   a storage failure or pool timeout. Then it denies. A deadlock or
   serialization abort is retried once inside the provider before it counts as
   a failure; see [the shared realm lock](#4-shared-realm-lock-for-jdbc-checks).
2. **Every turn, read or write, denies a retiring view on entry.** Denial is
   therefore final at once, as today, even when the provider would accept a
   later check. Per-binding serialization means no other check of the same view
   runs during a check, so a read turn needs no second look at the flag after
   its provider call.
3. The detach is scheduled after the read turn has ended, as a write turn. No
   turn ever waits for it. Detach is already idempotent. A detach scheduled
   after close is rejected by the gate; that is harmless, because close
   detaches every owned view itself. The read turn and its detach share one
   cancellation mask, so a cancellation requested during the check cannot skip
   the detach the view is owed; the caller then sees the check's outcome, and
   the cancellation takes effect, if at all, only after the view is settled.
4. **A reconnect never inherits a denied presentation.** A same-identity
   reconnect claims the presentation store entry before it detaches the old view.
   That claim moves the entry to the new lease (`BoundedPresentationStore.claim`),
   and by design the old lease's invalidation then does nothing. A reconnect
   queued ahead of the deferred detach would therefore keep a presentation that
   today's inline detach discards. So `acquire` treats a retiring predecessor
   like a changed identity: it detaches the predecessor first, invalidating its
   lease while that lease still owns the entry, and the successor starts from a
   fresh presentation.

Write turns keep today's `check`, which detaches inline. That covers `begin`,
`resume`, `recover`, the replacement operations, `currentInvocation`,
`connected` and sensitive authorization. Maintenance stops calling it inside its
own turn; see the next section.

### 3. Maintenance in short turns

Split `maintenance()`:

- A first write turn detaches retiring views and runs provider maintenance.
  Today the sweep also retries a failed release, because a check of an owned
  but detached view fails and detaches again; the retiring loop at the start
  of this turn now covers that.
- Each owned view's revalidation is then its own short turn: a read turn when
  concurrent checks are enabled, otherwise an ordinary turn with today's inline
  detach. A view detached between the snapshot and its turn is skipped, and a
  sweep that overlaps close ends quietly.
- A last write turn reclaims local history and samples capacity, after the
  revalidations as today, so a view denied during the sweep has its history
  reclaimed in the same sweep.
- **Revalidation has its own slot**: the sweep revalidates one view at a
  time, and the gate's read cap is the configured cap plus one. A sweep of a
  thousand views therefore never occupies the click cap. The slot is not
  reserved; clicks may take it and the sweep then waits its turn.

Clicks no longer wait for the whole sweep. This part does not depend on
concurrent checks. Even with exclusive checks, short per-view turns let clicks
interleave, so it can ship first as a fix for today's stall. It needs no API
change, so it can ship as a patch release.

### 4. Shared realm lock for JDBC checks

Add `readTransaction` and use it for the five checks.

- Its first statement takes `pg_advisory_xact_lock_shared` on the same realm key.
- It skips `retention.enable` once the realm is known to be enrolled.
  Enrollment inserts the retention row once, and that row is never deleted.
- The enrollment flag is set only when a transaction observes the row already
  present. It is never set on insert, because a rejected transaction rolls the
  insert back together with its legacy-session shortening.
- The choice between shared and exclusive is made before the transaction opens.
  Upgrading inside a transaction would let two concurrent first checks deadlock.
  Until enrollment is known, a check uses today's exclusive transaction.
- Writers are unchanged: exclusive lock, then `enable`.
- Renewals use `last_seen = GREATEST(last_seen, ?)`. This is defensive: the slot
  and view rows are locked before the time is sampled and the clock is
  monotonic per instance, so a lease cannot move backward today. It costs
  nothing and documents the intent.
- **Retry on abort.** `readTransaction` retries once, in a new transaction, on a
  deadlock abort (SQLSTATE `40P01`). Today checks never conflict with each
  other; under this design a host that locks inconsistently can deadlock two
  checks, and without the retry an innocent browser would be denied and
  reconnected. A retry is a later check, so it changes nothing about
  freshness. Every other failure still fails closed. Under read committed a
  serialization failure (`40001`) does not occur, so it is not retried. The
  database detects a deadlock only after its deadlock timeout, one second by
  default, so the retried check pays about that much latency.
- **The abort must survive the error pipeline.** Today the SQLSTATE is lost at
  three layers: session authorization turns any non-auth exception into a
  storage failure without its cause, the transaction executor turns remaining
  exceptions into a generic rolled-back result, and the transaction runner
  turns a commit failure into the unresolved outcome. So the retry cannot wrap
  today's results. Session authorization keeps the `SQLException` as the cause
  of its storage failure. `readTransaction` recognizes the deadlock SQLSTATE
  from that cause inside the transaction body, records it, and retries only
  when the runner then reports a settled rollback. An unresolved commit is
  never retried, and neither is a generic failure. A host-supplied
  `existingRunner` needs no new contract beyond the existing distinction
  between a rolled-back and an unresolved outcome; a runner that cannot make
  it runs checks without the retry.
- **Is any realm lock needed in checks?** No invariant was found that needs
  one; see [the trace](#trace-for-open-question-8) below. The shared lock is
  what makes every writer pause every check and turns a hot-account convoy
  into a realm-wide stall. The design keeps the shared lock until
  [open question 8](#open-questions) is decided, because the measurement and
  the earlier reviews cover that variant.

#### Trace for open question 8

Every provider writer was read for an invariant that needs checks excluded from
the realm: capture and activation, claim, release and view retirement, the
ceremonies (begin, bind, prepare, recover, deliver), the replacement operations
including completion and successor completion, logout, revocation, snapshots,
and maintenance with retirement and reaping. None was found. What that rests on:

- **A check's only writes are the two lease renewals**, under the slot and view
  row locks it already holds. A renewal can only make a view or slot look more
  alive. No writer counts or aggregates anything a check changes.
- **Every writer that changes a session, slot or view row** either locks the
  slot row first (activation, logout, revocation, replacement preparation,
  claim) or updates single rows or predicates under row locks (release, view
  retirement, reaping, the closing cleanup). A check locks the slot and view
  rows and re-reads the session row after them at read committed, so each
  such writer orders against it at row scope.
- **The unmanaged snapshot path** already performs the same host, slot and view
  verification with no realm lock today, concurrently with every writer.
- **One unlocked read of `last_seen`** exists: replacement preparation inspects
  the owner lease before host work, to fail fast without reversing the lock
  order. A concurrent check can renew the lease right after that read. The only
  effect is a spurious `Expired` rejection of the preparation in that race, at
  the idle boundary; the locked renewal later in the same transaction remains
  the authoritative check.
- **Lock order.** Every writer locks the retention row inside `enable`, then
  ceremony, host, slot, view, then material. Checks lock ceremony (for
  `pending`), host, slot, view, and never the retention row or material, so
  those cannot form a cycle with a check. One reverse case exists: the writers
  that reap inside their transaction before resolving the host (capture, claim,
  begin) lock expired slot and view rows before host rows. A cycle needs a
  check of that binding whose session expires between its own time sample and
  the reap's, while it holds host rows. Then PostgreSQL aborts one side; the
  check's retry fails closed on the expired session, and the writer's caller
  retries. This cannot happen with the shared lock, so it is the one new
  failure mode of dropping it, and it is narrow: reaping inside writers runs
  once per sweep interval, and the window is the gap between two time samples.

If the lock is dropped: this section reduces to `readTransaction` without the
lock statement; the database-level writer pause and the mixed-version point go
away, while the gate's write turns still pause reads in the process; the
enrollment rule stays, and the enrollment flag must be atomic because checks
then set and read it concurrently; point 5 of the security argument gains the
reverse case above; and three statements in the user docs change:
`request-admission-lifecycle.md` says provider transactions hold the realm
lock, `clustered-browser-authentication.md` says the pending-state probe is
serialized through it, and `browser-authentication.md` says realm-level locking
serializes provider admission.

### 5. Durable clock

Fixed in the working tree, with regression tests. These defects were in the
checkpoint lease from the latency change, not in released code:

- **Overlapping renewals.** Two callers crossing a lease together could each
  commit a lease. The slower one adopted the other's later lease and returned a
  time about one second ahead. An exclusive writer now serializes its renewals
  per instance, and callers that arrive during a renewal reuse it. The exact
  first checkpoint completes before any caller samples time.
- **Stale time after waiting.** A waiting caller returned the time it sampled
  before it waited. Expiry was then judged at a moment already past, against the
  rule in `JdbcBrowserSessions` that time is sampled after the last wait. A
  caller now samples again after waiting.
- **Unbounded lock waits.** Callers waited for a renewal on a JVM monitor held
  around a JDBC call: no time limit, and it pins virtual-thread carriers on
  JDK 21. It is now a `ReentrantLock`. A caller waits at most five seconds to
  acquire it, then fails closed. A caller that waited across a failed renewal
  fails with it, instead of every waiter retrying in turn during an outage.

**What the five seconds do not bound.** They bound only the wait for the lock.
The caller holding the lock runs connection acquisition, the upsert and the
commit, and only the clock source's own timeouts bound those. That caller is
usually inside a provider transaction, which keeps holding its realm lock
meanwhile. Today that lock is exclusive. Under this design it is shared, and a
writer queued behind it holds back every later check. This exposure predates the
lease change. Opted-in deployments must therefore bound the clock source itself:

- a pool connection timeout of a few seconds on the clock `DataSource`
- `statement_timeout` and `lock_timeout` on the clock's connections
- a driver socket timeout (`socketTimeout` for PostgreSQL JDBC)

Proposed: **background renewal.** A renewal task owned by the provider, on its
blocking context, renews the lease once the wall clock is within half a lease
of the checkpoint. It decides under the renewal lock and from the wall clock
alone, so a renewal completed meanwhile is neither repeated nor adopted as
observed time, and a future checkpoint adopted after a restart is not extended
until wall time approaches it; repeated restarts therefore stay within one
lease of wall time. In the normal case no provider transaction then performs
clock I/O. When a lease has
actually expired, because the task stalled or failed, callers still renew
through today's serialized path inside their own transaction; that fallback
keeps the exposure above, so the clock-source timeouts remain required. The
provider's `close` stops the task. This removes the once-per-second pause and,
outside the fallback, keeps a slow renewal from holding the realm lock and a
hot account's host rows. The clock source's timeouts then protect the renewal
task in the normal case and a check only in the fallback.

The alternative is caller-driven refresh ahead: a caller in the second half of a
lease tries to renew without waiting (`tryLock()`), and others keep returning
covered times. It is smaller, but one check per second still performs clock I/O
inside its transaction, holding the realm lock and, for an authenticated check,
the host account row. That is the stall exposure above, so the design prefers
the task; see [open question 5](#open-questions).

`SharedViews` is out of scope. Its clock upserts one row on every observation,
and that row is shared by the whole cluster, so its checks would serialize on
the clock row. It needs its own design, such as one observation per provider
transaction.

### 6. Optional phase 2: fewer round trips per check

Merge the view read with its lease renewal, and the slot read with its
renewal, into `UPDATE … RETURNING` statements. A failure would run one
diagnostic read to keep today's error codes. That cuts an anonymous check from
about 8 round trips to about 5. It must keep the session re-read after the row
locks and the late time sample. It is independent of the other parts.

## Why security is unchanged

1. **Linearizable with every revocation.** Two mechanisms carry this:
   - **Row locks and the re-read.** A check locks its slot and view rows
     `FOR UPDATE`. It re-reads the session row after those locks and samples time
     after its last wait. Transactions run at read committed, so a lock taken
     after a wait returns the committed row. Every writer that changes those
     rows or the host session therefore orders against the check. This includes
     writers that take no realm lock, such as unmanaged `logout`, `revoke` and
     `releaseView`, `withSnapshotView`, and host revocations through host row
     locks. The argument does not depend on the realm lock, so it holds
     unchanged.
   - **The realm lock.** On top of that, managed writers take the realm lock
     exclusively. Logout, takeover, replacement and reaping therefore also wait
     for running checks and block new ones, and cross-row invariants (capacity,
     reaping) see no check in flight.
2. **Final denials.** A denial marks the view retiring, and every turn denies a
   retiring view on entry. Nothing that starts after a denial can succeed, and a
   reconnect discards the retiring view's presentation before claiming it.
3. **No starvation.** PostgreSQL queues a later shared request behind a waiting
   exclusive one (measured), and the gate is first come, first served.
4. **Fresh every time.** Each check is still its own transaction. No result is
   reused across checks, frames or boundaries. The deadlock retry is a new
   transaction, so it is a later check.
5. **No new deadlocks between Spoonbill locks.** Managed writers take the realm
   lock before any row lock. Checks take row locks in the documented order:
   host, slot, view, after the ceremony for `pending`. The reviewer found no
   check-against-check deadlock in Spoonbill's own locks.
6. **Consistent local state.** Read turns never overlap a write turn and only
   set the retiring flag, which is atomic and only goes from false to true.
   Detach runs only in write turns.
7. **Mixed versions.** Older nodes take the exclusive lock for checks, which
   conflicts with the shared lock. The retention row lock arrived after the
   advisory lock, so every older node takes the advisory lock.

## Verified assumptions

| Assumption | How it was checked |
| --- | --- |
| PostgreSQL does not starve a waiting exclusive request | Measured: a shared request made after a queued exclusive one waited until that writer had acquired and released the lock |
| Checks change no authority, and their writes are the ones listed above | Traced `current`, `authorizeIn`, `checkSession`, `pending`, `pendingReplacement`; confirmed by the reviewer |
| A check's only writes are the two lease renewals | Third review: traced `authorizeIn`, `current`, `renewOwnerLease` and `touchBinding`; `pending` writes nothing |
| Linearizability does not rest on the realm lock alone | Reviewer: `withSnapshotView` and unmanaged writers skip it; the row locks and re-read order them |
| Transactions run at read committed | `JdbcTransactions` sets it on every connection |
| The retention row is never deleted, and checks read none of its values | Traced `JdbcBrowserRetention`; confirmed by the reviewer |
| No check runs inside another provider transaction or coordinator turn | Traced the action dispatcher, sensitive revalidation and host callbacks |
| The SPI does not promise concurrent calls are safe | Read the `BrowserPersistence` contract; it is silent, so concurrency is opt-in |
| The in-memory provider serializes every check | Reviewer: one monitor covers each call |
| The retiring flag makes session close discard the presentation | Reviewer: confirmed in the guard's close path |
| A failed guard check closes the session, including a gate rejection and the ten-second bound | Third review: traced `SessionsService.authorize` to `deny` |
| A reconnect is a write turn with exclusive provider transactions | Third review: traced `acquire`: `capture` with a possible reap, `claim`, `authorize` |
| The example host locks the account row `FOR UPDATE` on every check | Third review: `JdbcAccounts.resolve` |
| Foreground checks and maintenance revalidation share one provider admission | Fourth review: `check` and `maintenance` both call `authorize`, which enters `run` |
| The SQLSTATE of an abort is lost in today's error pipeline | Fourth review: `authorizeIn`, `JdbcTransactionExecutor`, `JdbcTransactions` |
| No writer needs checks excluded under the realm lock | Traced every provider writer for open question 8; see the trace in section 4 |
| The shared lock lifts the database ceiling | Measured with the synthetic test below |

## Costs and risks

- **Host code runs concurrently.** Hosts that opt in must make `resolve` safe for
  concurrent calls and lock rows in one global order. A host that locks
  inconsistently can deadlock two checks; PostgreSQL aborts one, the provider
  retries it once, and a second abort fails closed. The example complies:
  account, then session.
- **Hot accounts can convoy.** Checks for one account queue on its host row
  while holding the shared lock. A writer that queues behind them then pauses
  every other user until they finish. Per-binding serialization removes this
  for one browser. Across browsers of one account it needs `FOR SHARE` in
  checks; see [open questions](#open-questions). Dropping the realm lock from
  checks keeps the convoy off the database lock, but a gated writer queued
  behind the convoyed read turns still stalls later reads through the gate.
- **Writers pause checks.** Every `transact` drains running checks and blocks new
  ones for its duration, through the database lock, not the gate. This is
  milliseconds, but it is a pause, and a `transact` waiting for the exclusive
  lock blocks every later shared request while the gate keeps admitting reads
  up to the cap, which then wait on their connections.
- **A rejection ends the session.** A queue-bound rejection, like any failed
  check, closes the session, and the reconnect costs a write turn. The queue
  bound is an overload limit, not a load shedder; its rejections must be rare
  and visible in the counters.
- **Pool and threads.** Each running check holds a connection and a blocking
  thread. The cap must stay below both; see the sizing rule in
  [the opt-in](#1-explicit-opt-in). The reserved clock source is required,
  because a check that commits a clock checkpoint needs a second connection
  while it holds the first. The opt-in enforces this. An exclusive writer needs
  one clock connection per process. The clock source's own timeouts must be
  bounded as well, because a stalled renewal keeps its caller's realm lock.
- **Separate admission budgets.** The provider's single `pendingJobs` budget
  (default 256) is shared by checks and by ungated host calls (`transact`,
  `withSuccessorCompletion`, `usage`, `ownedViewPage`). Checks get their own
  budget, so they cannot make host business writes fail with capacity errors.
  That budget is the cap plus the maintenance budget; see
  [the opt-in](#1-explicit-opt-in).
- **More concurrency exposes latent races.** Three were found in the clock
  during this review and fixed; the tests below target the rest.

## Expected gain

Synthetic database test, no Spoonbill code changed. Workers run anonymous,
authorization-shaped transactions (isolation, realm lock, slot and view row
locks, two renewals, commit) through a 0.25 ms delay proxy. The exclusive
column matches today's provider (exclusive lock plus the retention row).

| Workers | Exclusive tx/s (p50) | Shared tx/s (p50) |
| ---: | ---: | ---: |
| 1 | 295 (3.3 ms) | 345 (2.9 ms) |
| 4 | 399 (9.9 ms) | 1,713 (2.3 ms) |
| 16 | 402 (39.9 ms) | 5,470 (2.8 ms) |
| 32 | 411 (79.4 ms) | 6,629 (4.8 ms) |

At five checks per click this is about 80 clicks per second today and about
1,300 with shared checks, as an upper bound. The test covers only anonymous
checks. It has no host row locks, no writer mix and no maintenance, and it does
not apply to `SharedViews`. The variant without a realm lock in checks (open
question 8) was not measured; it cannot be slower than the shared lock. The
benchmark in the [test plan](#test-plan) must measure those, together with
throughput, rejections and writer latency, before any performance claim is made.

### Measured

The end-to-end harness ran both modes with maintenance every second, a 0.25 ms
database delay, 200 ms of think time and a cap of 16, in two passes on a
shared machine; the [latency report](event-latency-performance.md) has the
conditions. Click-to-patch milliseconds:

| Users | Exclusive p50 / p99, pass 1 and 2 | Exclusive clicks/s | Concurrent p50 / p99, pass 1 and 2 | Concurrent clicks/s |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 29.1 / 94.6, 21.6 / 2822.2 | 27, 9 | 21.1 / 37.2, 22.4 / 35.1 | 29, 29 |
| 16 | 97.6 / 240.0, 78.5 / 139.4 | 42, 44 | 30.5 / 35.7, 29.8 / 42.0 | 56, 56 |
| 24 | 237.9 / 369.0, 243.3 / 297.0 | 43, 44 | 36.3 / 46.7, 36.4 / 44.3 | 81, 81 |
| 48 | 754.8 / 858.6, 756.5 / 861.4 | 40, 41 | 20.1 / 52.5, 16.6 / 32.8 | 163, 165 |

No reconnects and no queue-bound rejections in any run; 45 round trips per
click with concurrent checks. Of the acceptance criteria, p99 under 100 ms and
throughput no lower than the baseline's hold at every load, and the rejection
and reconnect rates are zero. Writer latency, the write mix and the hot account
are not measured: the harness client has no sign-in flow.

## Alternatives considered

- **Cache authorization results.** Rejected: it lowers freshness, which is a
  security reduction.
- **Batch concurrent checks into one exclusive transaction.** This keeps the
  serialization and adds failure isolation inside the batch. Gain is about 2×.
- **Per-view advisory locks.** A larger redesign. Capacity checks and reaping
  still need realm scope.
- **Only fewer round trips (phase 2).** About 1.6×, still serialized. It is
  complementary, not a replacement.
- **Reject reads over the cap.** Rejected: a rejected check closes the session,
  and the reconnect is a write turn, so it adds load under load.

## Implementation plan

1. **Maintenance split**, with its revalidation budget. Ship first; it fixes
   today's stall without the rest and needs no API change. Done in the working
   tree, without the budget, which exists only with concurrent checks.
2. **`BrowserCoordinatorGate`:** read turns keyed by binding, first come first
   served and writer-fair, with the running-read cap, per-binding
   serialization, queueing over the cap, the counters, and the same close
   semantics. Done in the working tree; nothing uses read turns until step 3.
3. **`BrowserPersistence` and `BrowserAuthentication`:**
   - the internal `checkConcurrency` method, `None` by default
   - read paths for the checks, used only when the provider returns a setting
   - final retiring denials on entry in every turn, an atomic retiring flag,
     and a detach scheduled after the read turn; write turns keep today's
     `check`
   - `acquire` detaching a retiring predecessor before `store.claim`
   Done in the working tree; no provider returns a setting yet.
4. **`JdbcBrowserPersistence` and `JdbcBrowserRetention`:**
   - the `JdbcConcurrentChecks` overload, refusing `SharedViews`, with the
     `checkConcurrency` override
   - `readTransaction`, chosen before the transaction opens, with one retry on
     a settled deadlock abort, and the abort's cause kept by session
     authorization
   - the enrollment flag, `GREATEST` renewals and a separate check budget
   Done in the working tree; the deadlock retry is tested against a real
   PostgreSQL deadlock.
5. **`JdbcBrowserClock`:** serialized renewals, resampling and the bounded lock
   wait are done. The background renewal task remains, with today's serialized
   path as the fallback. The clock source's required timeouts are documented.
   Done in the working tree: `renewAhead` renews only after an observation and
   when at most half a lease remains, never waiting for a renewal in progress;
   the provider runs it from a daemon thread every quarter lease when
   concurrent checks are enabled, and stops it in `close`.
6. **Docs:** the locking contract (`browser-authentication.md`,
   `request-admission-lifecycle.md`, `clustered-browser-authentication.md`), the
   host concurrency and lock-order rules, the sizing rule, what a rejection
   costs, and the changelog. Done in the working tree: a "Concurrent authority
   checks" section in `browser-authentication.md`, and the shared-lock
   sentences in the other two.

## Test plan

- **Gate:**
  - concurrent reads, and a write excludes reads
  - a waiting writer blocks later readers
  - the running-read cap: a read past the cap waits and is granted when a slot
    frees; only the queue bound rejects
  - readers queued behind a writer are granted up to the cap and the rest stay
    queued
  - per-binding serialization, and a waiting read holds no cap slot
  - cancellation, and close while reads run
- **Budgets:** with the cap and the maintenance budget both saturated, no
  gate-admitted check receives a capacity rejection from the provider.
- **Final denials:**
  - after a presentation denial or a transient failure in one read turn, a
    concurrent `resolve` or output admission for the same view is denied
  - the presentation is discarded on close
  - a same-identity reconnect queued ahead of the deferred detach starts from a
    fresh presentation, not the denied one
  - a queue-bound rejection does not mark the view retiring, and the reconnect
    reuses the presentation
  - a detach deferred past close is rejected by the gate, and close still
    releases the view
- **JDBC interleavings**, with a hook that holds a check inside its transaction:
  - logout, takeover, replacement and reaping started during a check wait for it
  - a check started after one of them commits fails
  - repeat with unmanaged writers and a host revocation through host rows
- **Enrollment:** the first check in an unenrolled realm enrolls through the
  exclusive path, and a rejected first check leaves the flag unset.
- **Deadlock:** concurrent checks of views sharing a binding or a host account;
  a real database deadlock through host resolution and row locking, not an
  injected result, is retried once and succeeds, and a second abort fails
  closed; a commit-time failure is not retried.
- **Opt-in:** the overload refuses a missing reserved clock source and refuses
  `SharedViews`. Without the overload, `checkConcurrency` is `None` and every
  check takes today's path.
- **Pool exhaustion:** with the reserved clock source, checks at the cap behind a
  queued writer complete across a lease boundary.
- **Stalled renewal owner:** the lock holder blocks in connection acquisition
  past the lock-wait bound. Waiting callers fail closed at the bound, and the
  owner then completes or fails on the clock source's own timeouts. An
  injectable bound keeps the test fast. With the background task, the same
  test covers a stalled task: callers at expiry fall back to the serialized
  path.
- **Maintenance:** clicks keep their latency during a sweep of many views, and
  the sweep never holds more than its budget of running reads.
- **Existing suites:** conformance and clustered suites, and the Chromium/WebKit
  browser fixtures.
- **Benchmark:** `misc/event-latency` extended with authenticated users, a write
  mix (sign-ins, `transact`), maintenance at a one-second cadence, and a hot
  account with many browsers. The client must count rejected checks and
  reconnects instead of stopping on a closed socket. Run 8, 16, 24 and 48 users
  at a 0.25 ms database delay, against exclusive checks at the same load as the
  baseline, with the shared lock and, if open question 8 is adopted, without
  it. Queueing can hide overload as latency, so acceptance needs all of these:
  - p99 click latency under 100 ms
  - successful clicks per second no lower than the baseline's
  - no queue-bound rejections or reconnects at any of these loads, with both
    rates reported, and the queue depth reported
  - writer latency (sign-in, logout, `transact`) no worse than the baseline's

## Review record

Four reviews checked the design against the code. Every finding was verified
in the code before being accepted.

### Implementation review (working tree)

Recorded in
[concurrent-authorization-implementation-review.md](concurrent-authorization-implementation-review.md),
against the uncommitted implementation. Two findings were reproduced by
failing tests, the third by inspection; all three are fixed.

| Finding | Severity | Resolution |
| --- | --- | --- |
| A cancellation requested during a check in a read turn could skip the deferred detach, leaving the denied view owned with a usable lease until maintenance | P2 | The read turn and its settlement share one cancellation mask; a Cats Effect regression test in `interop/ce3` |
| Background renewal extended an adopted future checkpoint without wall-clock progress, so repeated restarts at a fixed clock pushed observed time further ahead each time | P2 | `renewAhead` decides from the wall clock alone and renews one lease ahead of it; a restart regression test |
| Background renewal sampled time before taking the lock, so a foreground renewal in between made it adopt the newer checkpoint as observed time | P2 | The decision and sample happen under the lock; the in-progress test asserts no adoption |

### Fourth review (revision 4)

Recorded in [concurrent-authorization-design-review.md](concurrent-authorization-design-review.md).

| Finding | Severity | Resolution |
| --- | --- | --- |
| Maintenance revalidation enters the same provider admission as foreground checks, so a check budget equal to the cap can reject a gate-admitted check during a sweep | P2 | The check budget is the cap plus the maintenance budget; the sizing rule updated; a saturation test added |
| The retry on SQLSTATE aborts cannot be built on today's results: session authorization, the executor and the runner all discard the SQLSTATE | P2 | The error path specified: the cause is kept, the abort is recorded inside the body, and only a settled rollback is retried; custom runners and commit failures covered; `40001` dropped as moot under read committed; the deadlock-timeout latency stated |
| Dropping the realm lock does not remove the gate's write-turn pauses or the convoy through them | Clarification | Open question 8, the trace and the convoy cost scoped to ungated and cross-node writers |
| Background renewal keeps a fallback with clock I/O inside a transaction | Clarification | The summary, section 5 and open question 5 say "in the normal case" and name the fallback |
| `currentPrincipal` performs the same check as `authorizeHttp` but was not a read turn (found while folding in the review) | Omission | Added to the read-turn list |

### Third review (revision 3)

| Finding | Severity | Resolution |
| --- | --- | --- |
| Rejecting a read at the cap closes the session, and the reconnect is a write turn with exclusive provider transactions, so rejection under load adds load | P1 | Reads queue over the cap; the queue bound is the only rejection point; the cost of a rejection stated; the recommendation for open question 3 changed |
| No invariant was found that needs checks under the realm lock, and the shared lock causes the writer-pause and realm-wide convoy costs | P2, design question | Open question 8 added; the trace of every writer, completed in revision 4, found none and one narrow deadlock race; the question recommends dropping the lock |
| New transient failures (deadlocks between checks, clock lock timeouts, pool timeouts at the cap) would become final denials and reconnects | P2 | `readTransaction` retries once on a deadlock or serialization abort; every other failure fails closed |
| Caller-driven refresh ahead keeps clock I/O inside a transaction that holds the realm lock and host rows | P2 | Background renewal is the proposal; refresh ahead is the alternative; the recommendation for open question 5 changed |
| Maintenance revalidation as read turns would compete with clicks for the cap and per-binding slots | P2 | A separate revalidation budget |
| The current-model section had the sweep order reversed; the sweep iterates owned views and retries failed releases through `check` | Correction | Order fixed; the write turn's retiring loop covers the retry |
| `transact` and `withSuccessorCompletion` were described as keeping an exclusive turn; they take no turn, only the database lock | Correction | Reworded; gate fairness does not cover them |
| Gate gaps: per-binding waiters and cap slots, readers behind a writer at the cap, flag publication, detach after close, reentry | Clarification | Each stated in section 2 |
| The post-call retiring re-check is redundant under per-binding serialization | Simplification | Dropped |
| Read committed was an unstated assumption of the row-lock argument; `GREATEST` was presented as a fix | Clarification | Both stated |
| No sizing rule and no counters for the cap | Clarification | Rule and counters added |
| Untested claims: a rejection keeps the presentation; a detach after close | Test gap | Tests added |

### Second review (revision 2)

| Finding | Severity | Resolution |
| --- | --- | --- |
| A same-identity reconnect queued ahead of the deferred detach inherits the denied view's presentation: `store.claim` moves the entry first, and the old lease's invalidation then does nothing | P2 | `acquire` detaches a retiring predecessor before claiming; the queued-reconnect test added |
| The five-second bound covers only the lock wait; the renewal's connection acquisition, SQL and commit are bounded only by the clock source, while the holder keeps its realm lock | P2 (overstated, not new) | Bounds stated separately; clock-source timeouts required; stalled-owner test added; background renewal listed |
| A final class cannot mix in a marker for only some instances | Clarification | Internal `checkConcurrency` SPI method with a `None` default; `SharedViews` refused |
| Rejection at the cap makes p99 latency alone misleading | Clarification | Acceptance adds successful throughput, rejection and reconnect rates, and writer latency |

### First review (revision 1)

An independent adversarial review.

| Finding | Severity | Resolution |
| --- | --- | --- |
| A clock checkpoint could stall all checks when no reserved clock source exists, until the pool times out | High | Opt-in requires the reserved clock source; running checks are capped; refresh ahead proposed |
| Deferred detach made denials non-final: presentation denials and transient failures do not make later checks fail | Medium | Every turn denies a retiring view on entry; any failed check sets retiring |
| Awaiting the detach inside a turn would deadlock; `check` is also used by write turns | Medium | Two `check` versions; the detach is scheduled after the read turn and never awaited |
| Opting in per provider class would make host code concurrent without the host agreeing | Medium | Explicit, off-by-default opt-in per deployment |
| Checks and ungated host calls share one admission budget | Medium-low | Separate check budget, and the cap keeps checks under it |
| Maintenance is one long write turn; the latency gain was measured only for anonymous checks | Medium-low | Maintenance split; benchmark extended; claims bounded |
| Clock: stale time after waiting, an unbounded monitor around JDBC, and a missing test | Low-medium | Fixed in the working tree, with tests that fail on the earlier code |
| The enrollment flag must never be set on insert, and the shared or exclusive choice must precede the transaction | Low | Both made explicit |
| The linearizability argument rested on the realm lock alone | Correction | Restated on the row locks, the re-read and the late time sample |

## Open questions

Each has a recommendation; the design above assumes it, except question 8.

1. **Opt-in shape.** A `JdbcBrowserPersistence` constructor overload with
   `JdbcConcurrentChecks(clockSource, maxConcurrentChecks)`, read by the
   coordinator through the internal `checkConcurrency` method? Recommended. The alternative is a flag on
   `BrowserConfiguration`, but the requirements are provider-specific.
2. **Host lock contract.** Recommended: document that `resolve` runs
   concurrently and must lock in one global order, and add an optional
   check-only host hook so a host can take `FOR SHARE` during checks. The hook
   removes cross-browser convoys on hot accounts; the example host locks the
   account row `FOR UPDATE` on every check, which is exactly that convoy. The
   alternative is documentation only.
3. **Over the cap: reject or queue?** Recommended: queue, and keep the existing
   queue bound as the only rejection point. Revision 3 recommended rejecting;
   the third review showed that a rejected check closes the session and that
   the reconnect is a write turn, so rejecting under load adds load. Queueing
   does not hide overload: the queue bound still rejects, and the counters show
   queue depth.
4. **Ship the maintenance split first, on its own?** Recommended. It fixes
   today's stall, does not depend on the rest, and needs no API change.
5. **Clock renewal: background task or caller-driven refresh ahead?**
   Recommended: the background task now. In the normal case it keeps clock I/O
   out of provider transactions, which is the stall exposure the design
   identifies; only an expired lease still renews inside a check. Refresh
   ahead is smaller but leaves one renewal per second inside a check that holds
   the realm lock and host rows. Revision 3 recommended refresh ahead first.
6. **`SharedViews` out of scope?** Recommended. Its clock needs its own design
   first.
7. **Release as 8.1.0?** Recommended. Opting in changes the provider concurrency
   contract; without the opt-in nothing changes.
8. **Keep any realm lock in checks?** Recommended: drop it. The
   [trace](#trace-for-open-question-8) covered every provider writer, including
   replacement completion and `withSuccessorCompletion`, and found no invariant
   that needs checks excluded. Checks would then order against writers at row
   scope only, which is the guarantee the security argument already rests on
   and which the unmanaged snapshot path already relies on. Ungated writers
   such as `transact`, and writers on other nodes, would no longer pause every
   check, because the advisory lock is realm-wide while the gate is per
   process. Gated writers still drain the gate's read turns, and a gated writer
   queued behind read turns convoyed on a hot account still stalls later reads,
   so the convoy fix stays with per-binding serialization and the `FOR SHARE`
   hook of question 2. The mixed-version point would go away. The costs are one
   narrow deadlock
   race with writers that reap before resolving the host, resolved by the
   database and the check retry, and a spurious `Expired` on a replacement
   preparation in a race at the idle boundary. The design above keeps the
   shared lock until this is decided, because the measurement and the earlier
   reviews cover that variant; the trace lists what adopting it changes.
