# Issuer-bound one-use operations

`OneUseAuthorityScope` is a distinct capability contract alongside durable
operation preparation. It does not replace durable grants. Unused receipts and
authorities exist only in the issuing process and cannot survive its restart.
Only operation outcomes and negative execution fences are persisted.

## Issuance and admission

The trusted provider calls `verified` only after establishing the required fresh
evidence. `conditional` admits one attempt whose proof verification and consumption
occur in the protected host transaction. These are provider ports, not browser
endpoints or recovery APIs. The provider must prevent a replayed proof from being
accepted repeatedly to mint fresh evidence objects.

The scope generates grant and invocation IDs internally and fixes the full
binding, canonical intent digest, deadline and preparation kind in a canonical
receipt. Every alias shares the receipt's redemption cell. `issue` can redeem
that receipt once, producing one nonserializable `ExecutionAuthority` whose
aliases share an execution claim cell. References, IDs, definitions, status
records and copied metadata cannot create a receipt or restore an authority.

`executeIssued` claims the authority before dispatching a transaction. Dispatch
failure, cancellation, rollback and commit uncertainty never undo this claim.
Neither the transaction executor nor its caller may automatically retry an
admitted transaction body.

JDBC transaction wrappers return a connection to its pool only after an
acknowledged commit/rollback or successful JDBC `abort`. Abort uses a synchronous
executor. A failed rollback is an unresolved final disposition (`CommitUnknown`),
not a definite rejection or rollback; browser/snapshot APIs report `StorageFailure`.
After failed abort, the wrapper deliberately does not close, reset or reuse the
connection, because pool return could commit unfinished work. The connection and
any remaining locks require pool eviction or administrative recovery. Status
reconciliation still acquires the normal fences; it cannot declare a negative
outcome while that transaction remains active. Cleanup errors after acknowledged
commit/rollback do not erase the established result.

Outstanding receipts and authorities share a bounded lease list. Admission
releases the capacity slot; expired leases are pruned on issuance and cannot be
revived by a clock moving backward. Capacity exhaustion fails closed.

## Host transaction requirements

The store's `readForDecision` protects its identity observation, including
absence. A pessimistic adapter acquires ordered identity fences before host
locks, retaining them through commit. A conditional/optimistic adapter may
instead provide equivalent serializable conflict validation at commit, aborting
conflicts without replaying the transaction program. Any existing exact record
forbids execution. Conflicting aliases or metadata are rejected. Only a live
authority with no existing outcome may enter the host program.

The host must acquire its authoritative locks, check current session, security
generation and any issuer epoch, verify resource policy, and consume conditional
proof evidence in the same transaction as the mutation. Equivalent conditional
validation can establish these decisions at commit. Expiry is checked after
the protected read, after the joined host program, and after the awaited outcome
write. The `Committed` record is created in that transaction; results are returned
only after acknowledged outer commit. An acknowledgement delayed beyond expiry
still reports the actual disposition; it does not permit another attempt or
prove rollback. No sensitive result is stored for later replay.

`TransactionExecutor[F, G, Tx]` joins the complete `G` program before committing
and returns its acknowledged result in `F`. `DurableOperationStore[G, Tx]`
operations and host callbacks compose in `G`. The synchronous `Direct` program
uses ordinary calls and `try/finally`; native async clients can use their own
transaction effect without blocking. There is one protocol implementation.

Use only the supplied transaction. Never commit, close, await a separate async
operation, detach work, perform independent effects, or return a denial as an
ordinary successful value. Fail the transaction program with a typed protocol
rejection so the whole transaction rolls back. The scope remains live until the
joined program terminates and closes on success, failure or actual native
cancellation of `G`. Cancellation of the outer `F` result observer may leave a
`Direct` blocking worker running. That worker retains its transaction and scope
until completion, owns cleanup and may commit; the cancelled observer receives
no success value. Never force early resource disposal or infer a negative outcome
from that observation cancellation.
`ThreadConfined` requires the entering thread; `Serialized` allows thread hops
only when the adapter serializes all native transaction operations. Neither
policy makes retained raw transaction access safe.

The adapter must provide native finalization for `TransactionProgram.guarantee`
and database cleanup in its executor. The protocol guards evaluation as well as
construction, so re-evaluating a lazy transaction program cannot replay host
work. Callback exceptions in `map` and `flatMap` must be captured as program
failures. The optional ZIO2 adapter implements this with `attempt` and `ensuring`.
Cancellation or abandonment of an observer never establishes a negative outcome;
in particular, Future observation has no native cancellation mechanism.

The atomic domain must cover business mutation, exact identity and grant aliases,
retained capacity and outcome/negative fences. A document or conditional store
qualifies only when all required decisions share that atomic boundary. Separate
status writes or a separate outcome database cannot supply these guarantees.

## Recovery, shutdown and process failure

An absent outcome is unresolved, not proof of failure. Reconciliation uses the
same protected decision: it observes a committed outcome or commits a durable
`NotCommitted` record while excluding every earlier uncommitted writer. A
pessimistic adapter waits for the writer; a conditional adapter must invalidate
its stale commit. That negative record also excludes a claimed authority that
has not reached its transaction yet. Recovery returns status only and cannot
produce another executable authority. Construct a fresh reconciliation call
for each status observation rather than re-evaluating one lazy transaction.

Closing an issuer linearizes against admission and denies unadmitted authority.
It does not abort admitted transactions or revoke an older partitioned issuer on
another node. Restart likewise does not prove that an old database transaction
cannot still commit. Current host authority/epoch checks and database execution
fences remain mandatory.

The existing durable preparation protocol remains available when a committed
preparation record is required before execution. Both exact-attempt APIs use
`ConsumeOnAttempt`; neither API implements reusable-grant release semantics.

## Verification

`spoonbill.security.OneUseAuthoritySpec` exercises alias races, single evidence
redemption, foreign scopes, dispatch and callback failure, late execution after
negative recovery, held writer commit/rollback, expiry after locking, ambiguous
commit, bounded capacity, shutdown and the absence of reconstruction APIs.
`spoonbill.security.OperationTransactionsSpec` covers the separate durable
preparation contract, including commit-gated permits and scope escape.
`spoonbill.security.AsyncOperationTransactionsSpec` covers suspended native-style
reads, host programs and writes, thread hopping, acknowledged results and both
sides of a conditional negative-fence race without JDBC. The ZIO2
`spoonbill.zio.OperationTransactionsSpec` covers native interruption, cleanup,
deferred admission, lazy program replay and typed rejection after effects.

These are core conformance fixtures. Production store adapters must additionally
prove their database exclusion, exact identity retention, and mutation/outcome
atomicity against their actual database engine.

`JdbcOperationOutcomes` supplies the PostgreSQL adapter without opening its own
connection or transaction. Its single retained table stores exact immutable
metadata, including the deadline's seconds and nanoseconds, and a terminal or
in-progress status. A unique grant ID and invocation primary key prevent aliases.
Capacity is enforced per realm and subject; expired and terminal records remain.

The issuer-bound write path uses three explicit frontend SQL executions: one
dependent materialized CTE query for ordered subject/grant/invocation advisory
locks, one separate fresh identity read after any lock wait, and one guarded
outcome insert/update. Isolation checks and capacity checks share these existing
statements. Host SQL and transaction setup/commit work must be counted separately.
The database still performs three advisory lock operations and retained-capacity
work; fewer client/server exchanges are not a claim of equivalent CPU savings.

`spoonbill.security.jdbc.JdbcOperationOutcomesSpec` checks this budget alongside
actual host SQL and exercises the physical PostgreSQL lock order, snapshot
freshness, rollback, commit uncertainty, capacity and terminal identity retention.
These tests require an explicit disposable loopback PostgreSQL test URL.
