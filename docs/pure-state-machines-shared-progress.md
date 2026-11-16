# Shared protocol rollout

This records phase-2 candidate comparisons and validated units. It does not mark
later lifecycle, security, transport, effect, browser or testkit phases complete.
The owner authorized one coherent commit per unit in the existing PR. Effect
ordering, cancellation and scheduling remain interpreter responsibilities.

## Setup ownership

The Akka and Pekko `UpgradeOwnership` implementations were literal duplicates.
`zio.http.WebSocketSetup` has the same ownership transitions with different effect
policies. Their common table is now `internal.SetupOwnershipMachine`:

| Event | Waiting | Prepared | Attached | Abandoned |
| --- | --- | --- | --- | --- |
| Prepare | Retain incoming release; Prepared | Release incoming; unchanged | Release incoming; unchanged | Release incoming; unchanged |
| Attach | Refuse; unchanged | Transfer retained ownership; Attached | Refuse; unchanged | Refuse; unchanged |
| Abandon | Release input; Abandoned | Release input and retained response; Abandoned | No ownership cleanup | No ownership cleanup |

The interpreter keeps release thunks paired with machine state under the original
monitor. Akka/Pekko still throw on refused attachment and cancel the timer inside
the monitor; zio-http still returns false and interrupts its fiber outside the
monitor inside its original uninterruptible effect. All input/response release
ordering, failure recovery and late timer installation handling stay unchanged.

**Excluded from this shared machine:** standalone `SetupDeadline` has
`Pending(release?)`, `Attached(release)`, `Expired(release?)`, and `Finished`.
It retains cleanup ownership after attachment, repeated attach succeeds, and a
physical timer expiry defers cleanup to transport termination. Sharing the
four-state model would change those contracts. Its own transport lifecycle is a
later unit, not a missing copy of this machine.

Missing duplicate preparation and premature/repeated attach characterizations
were added before production changes. They passed on the original wrappers:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'pekko/testOnly *PekkoUpgradeOwnershipSpec' \
  'akka/testOnly *AkkaUpgradeOwnershipSpec' \
  'zioHttp/testOnly *WebSocketSetupSpec'
```

Result: 7 Pekko, 7 Akka and 4 zio-http tests passed. After extraction the same
suites passed, together with 2 `spoonbill/testOnly *SetupOwnershipMachineSpec`
cases covering the exhaustive transition table and generated ownership histories.

## Drain gate candidates

| Candidate | Admission and settlement | Disposition |
| --- | --- | --- |
| `GuardedStateManager.writes` | Unbounded writes; close rejects new writes and joins admitted writes; asynchronous waiters | Share count/close decisions; keep write ordering and callbacks in interpreter |
| `SocketCloseDispatcher` | Bounded reservations; stop rejects new work; executor shutdown when stopped and count reaches zero | Share bounded count/close decisions; reservation's two independent completion bits remain separate |
| `InMemoryBrowserPersistence` admission | Bounded operations; close rejects new work and blocks until zero before destroying provider state | Share count/close decisions without changing monitor wait/notify or transaction order |
| `JdbcBrowserPersistence` admission | Independently bounded ordinary, cleanup and check lanes; all stop together; close also settles owned views and replays cleanup result | Only per-lane count decisions are candidates; require lane-budget and repeated-close characterization before adoption |
| `ServerSocket.ActiveConnections` | `started` increments even after `stopped`; drain replays first failure | Different admission/error contract; own lifecycle machine |
| `BrowserCoordinatorGate` | FIFO readers/writers, per-key read exclusion, bounded pending turns, cleanup/close join | Not a count gate; own keyed scheduling lifecycle |

## Once candidates

`ComponentOwnership.Signal` and `SerialLane`'s close waiters have a replaying
completion-latch protocol, but FIFO versus LIFO waiter notification belongs to
their interpreters. They are candidates for a shared completion decision model.
`WebSocketResponse.releaseOnce` already has one explicit Idle/Running/Done state
and tests for construction, repeated execution, concurrent joining and effect
context. Retain this clear implementation rather than forcing it into a latch
that lacks execution ownership.

The effect-module and testkit audit owns the other candidates. `AsyncTable` allows
removal/recreation and has duplicate-put policies. `Queue.cancelSignal` does not
currently replay a prior cancellation to a new waiter. `Scheduler.JobHandler`
and `Stream.handleConsumed` have a single waiter; the latter signals EOF rather
than arbitrary termination. Pool disposal distinguishes claiming disposal from
settling cleanup. Those contracts are not interchangeable; suspected defects
need separate characterization/fixes before a refactor.

## Pending registry candidates

`OwnedBrowserRequests` and `SensitiveDisclosureScope` both need bounded key
ownership, take-once removal, permanent close admission and cancellation handles
installed after retirement. Their error mapping and effect order differ and
must remain in interpreters. Their per-entry late-cancellation protocol is a
separate comparison from their registry admission protocol.

`SensitiveRuntime` additionally owns region exclusivity, presentation phases,
three timer classes, plaintext retirement and clear-before-callback ordering.
Keep that security lifecycle out of a generic registry replacement. Frontend
user actions and downloads have different bounds, expiration and transfer rules;
they stay with the Frontend lifecycle audit rather than being treated as equivalent
pending-request registries.
