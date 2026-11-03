# Concurrent guarded authorization: review of revision 4

Date: 2026-11-03

Reviewed: [Concurrent guarded authorization: design](concurrent-authorization-design.md), revision 4, against the current working tree.

## Assessment

Revision 4 resolves the earlier retiring-predecessor reconnect issue and clarifies the clock timeout bounds. The maintenance split remains a sensible first change. Two design gaps should be resolved before implementing concurrent checks.

## Findings

### [P2] Account for maintenance reads in the provider admission budget

The [opt-in sizing rule](concurrent-authorization-design.md#1-explicit-opt-in), lines 129–132, sets the provider's check budget equal to the foreground cap. However, [maintenance revalidation](concurrent-authorization-design.md#3-maintenance-in-short-turns), lines 225–231, admits additional reads outside that cap. Both paths call the same `persistence.authorize` method.

With foreground cap **C**, one maintenance check plus **C** foreground checks requires **C+1** provider admissions. A gate-admitted check can therefore receive `Capacity`, retire its view, and trigger a reconnect—the failure the revised queueing policy aims to avoid.

The current call paths are visible in [`BrowserAuthentication.scala`](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala), lines 138–147 and 570–577. JDBC authorization enters the shared admission mechanism in [`JdbcBrowserPersistence.scala`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserPersistence.scala), lines 154–185 and 527.

**Recommendation:** Specify separate provider admission lanes for foreground and maintenance checks, or a combined **C+M** budget with appropriate reservations. Add a test that saturates both concurrency budgets and verifies that no gate-admitted foreground check receives a capacity rejection.

### [P2] Preserve abort classification before promising SQLSTATE retries

The [retry proposal](concurrent-authorization-design.md#4-shared-realm-lock-for-jdbc-checks), lines 256–261, promises retries for `40P01` and `40001`. The current error pipeline discards those distinctions:

- [`JdbcBrowserSessions.authorizeIn`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSessions.scala), lines 509–512, converts SQL exceptions from host resolution and row locking into `JdbcAuthException(StorageFailure)` without retaining their cause.
- [`JdbcTransactionExecutor`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcTransactionExecutor.scala), lines 23–26, maps remaining exceptions to generic `RolledBack`.
- [`JdbcTransactions`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcTransactions.scala), lines 53–56, maps commit failures to the supplied unresolved outcome, which is `CommitUnknown` for this executor.

Adding a retry around the existing transaction result cannot distinguish the specified aborts from other failures. A supplied `existingRunner` also needs a defined contract for reporting them.

**Recommendation:** Specify how confirmed retryable aborts survive these layers, including custom runners and commit-time failures. Retry only after known rollback; generic failures and uncertain settlement must remain nonretryable. Test actual SQLSTATE propagation through host resolution, row locking, and settlement, rather than only injecting a high-level retryable result.

## Clarify the performance claims

### Dropping the realm lock does not eliminate all global pauses

[Open question 8](concurrent-authorization-design.md#open-questions), lines 669–681, leaves the coordinator's exclusive write turns intact. Removing the database realm lock removes database-wide exclusion for ungated calls such as `transact`; gated writers still drain readers and block later reads. Scope the claimed reduction in writer pauses and hot-account convoys accordingly.

### Background renewal reduces transaction-local clock I/O; it does not eliminate it

The [clock proposal](concurrent-authorization-design.md#5-durable-clock), lines 349–355, retains today's serialized path as a fallback after lease expiry. That fallback still permits clock I/O inside a provider transaction. The summary and open question 5 should describe the normal-case improvement without claiming that all such I/O is eliminated.

## Verification and limits

- Read-only source and existing-test inspection using `rg`, `nl`, and `sed`: completed.
- Tests and benchmarks: not run; this review assesses an unimplemented design.
- No implementation files or design text were changed as part of this review.
- Findings refer to revision 4 and the working tree inspected on 2026-11-03; line numbers may move in later revisions.
