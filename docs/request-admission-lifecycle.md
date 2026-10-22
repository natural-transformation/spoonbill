# Browser admission, retention, and recovery

The built-in memory and PostgreSQL providers reclaim inactive browser metadata
without resetting the authority of a previously issued identity, view, or
ceremony. Reclamation combines finite retention windows, serialized writer
exclusion, permanent fencing floors, and nonreused issuance identifiers. Expiry
alone is never permission to repeat host proof consumption.

## Admission and finite budgets

`BrowserPersistence.inspectPrincipal` does not create a binding for an unknown,
well-formed binding cookie when no session credential is supplied. It returns
anonymous presentation only. A supplied credential and every existing binding
still undergo authoritative checks; observation does not activate a pending
credential or issue a capability. `BrowserAuthentication` uses this path for
HTTP authorization and principal lookup. Shared HTTP bootstrap checks
presentation before capturing/registering its durable view and checks it again
after capture. A successful shared bootstrap still consumes bounded admission.

The default SPI implementation delegates to `capture` for compatibility.
Third-party providers must implement and test their own nonallocating observation,
retention, and capacity reporting; these built-in guarantees do not follow merely
from implementing the interface.

`BrowserPersistenceLimits` controls these defaults per provider scope:

| Capacity | Default |
| --- | --- |
| Retained bindings | 32,768 |
| Anonymous bindings, including pending ordinary sign-in | 1,024 |
| Retained views | 131,072 |
| Active views per binding | 16 |
| Retained ceremonies | 32,768 |
| Retained ceremonies per binding | 16 |
| Delivery materials | 1,024 |
| Delivery attempts | 3 |
| Pending provider operations | 256 |
| Ceremony lifetime | 120 seconds |
| Delivery lifetime | 60 seconds |

The anonymous quota counts slots without a current session. New anonymous
bindings cannot consume the entire default global binding budget. Existing
identities are not evicted when a limit is reached. Authentication, replacement,
view ownership, and host transactions retain their normal authority checks.
`ceremoniesPerBinding` counts currently retained attempts, including expired
attempts whose retention grace or dependencies have not finished. It is not a
login-attempt rate limiter.

`BrowserPersistenceLimits.retention` is a `BrowserRetentionPolicy`:

| Policy field | Default | Meaning |
| --- | --- | --- |
| `idleBindingSeconds` | 600 | Minimum inactivity before an unreferenced anonymous binding can be removed |
| `idleViewSeconds` | 3,600 | Renewable owner lease and retention window for an unowned/bootstrap view |
| `historySeconds` | 120 | Additional grace after ceremony/session expiry before eligible history is removed |
| `maxSessionSeconds` | 86,400 | Maximum browser-session lifetime accepted from host preparation |
| `workIntervalSeconds` | 30 | Shared JDBC cadence for aggregate capacity samples and the upper interval for full retention sweeps |

Each retention window must be between one second and one year. Host preparation
that requests a browser session beyond the configured maximum fails without
committing its host mutation. The host's account and session data remain
application-owned; the browser authority deadline does not extend host authority.

## Why deletion does not revive old authority

A provider retains a scope-wide binding-generation floor and view-epoch floor.
Deleting a record raises its floor above the removed generation or epoch in the
same serialized operation. Recreating the same raw binding cookie or view key
starts at the current floor, so an old captured identity, view, delayed release,
or protected writer cannot match the recreated authority. A replayed binding
cookie can obtain a fresh anonymous identity; it cannot recover the retired
identity or its authenticated session.

Ceremony IDs and completion/attempt IDs share a monotonically allocated,
nonreused sequence under a scope-specific UUID prefix. The JDBC prefix is chosen
to avoid existing legacy ceremony and attempt prefixes before reclamation starts.
Sequence allocation persists with its transaction. Missing retired attempts
cannot become new attempts with the same identifier. Captured immutable ceremony
metadata and deadlines remain checked before preparation; a missing or expired
record never authorizes rerunning the host callback.

A provider-owned random 256-bit issuance key derives the browser cookie with
HMAC-SHA256 over a domain-separated context containing the host-supplied secret,
provider scope, binding digest, and unique attempt ID. The browser cookie is
**different from `HostSessionPrepared.credential`**. Applications must use the
actual delivered cookie, not copy the preparation secret into a browser request.
Even reuse of previously exposed host preparation material after history removal
cannot recreate the retired cookie or derive its successor from public inputs.
Preparation also rejects supplied material matching a still-retained browser
credential. Provider keys and plaintext derivation buffers are not logged.
The memory provider wipes its key when shutdown has drained admitted work.

Counters and fences never wrap, reset, or silently recycle on overflow. Exhausted
issuance is refused; a saturated fence is retained or refuses its transition.
Operators must not reset the permanent metadata to recover capacity.

## Eligibility, leases, and concurrent writers

Admission paths check whether reclamation is due before allocating bindings,
views, or ceremonies. Explicit maintenance also reclaims eligible records:

- Expired delivery material is disposable. Sessions and their completion records
  remain through session expiry and the history grace, including dependencies
  that still require them.
- Expired ceremonies are removed only after their grace and required completion,
  session, material, and replacement dependencies are retired. A live replacement
  successor keeps its required linkage and verified completion metadata even
  after the original ceremony deadline. Dependencies may extend retention beyond
  an individual record's grace period.
- Owner activity renews a view lease only while the owner and unexpired lease are
  still authoritative. An expired owner cannot renew itself back into authority.
  Unowned views become removable after their retention window; JDBC removes a
  dependent presentation snapshot before removing its view.
- An inactive binding is removable only when it has no current/pending authority
  and no remaining view, session, ceremony, or replacement dependency.

When an active session expires, its slot advances its generation and loses current
authority. If an abandoned committed replacement's successor expires, retirement
also advances the slot and clears current authority. It **never restores the
predecessor**. A predecessor's expiry alone does not discard an otherwise usable
pending successor.

Memory reclamation holds the same monitor as its immutable host/browser
transaction. JDBC reclamation and provider transactions hold the same realm and
namespace advisory transaction lock. A transaction already running must settle
before cleanup can establish eligibility. A delayed preparation that starts after
cleanup finds a missing or stale record before its host callback can run.
Shared-node operations and restart use durable floors, issuance state, and the
nondecreasing persisted clock. A backward wall-clock sample cannot restore expired
authority. Exclusive ownership still requires one live provider instance; shared
ownership must be selected explicitly for overlapping nodes.

An enrolled JDBC scope rejects independent lower-level
`JdbcBrowserSessions` creation and protected operations that would bypass managed
lifecycle checks. Reduction-only logout, revocation, and release remain available.
Use `JdbcBrowserPersistence` and its transaction boundary for admitted authority;
do not mix raw SQL writers or unmanaged creation paths into the scope.

Host proof-use records, host sessions, audit records, and outboxes are **not**
pruned by this policy. Their durable replay, uniqueness, mutation, and retention
contracts remain the application's responsibility. The framework does not repeat
a host callback to repair an unknown commit; it reconciles the original attempt
under writer exclusion.

Local presentation history is bounded too. After authoritative checks, the
coordinator sweeps expired presentations and forgets only history with no retained
entry, then removes the matching obsolete authority stamps. Active and
reconnectable presentations retain their history. Managers, snapshots, and leases
have separate identity tokens, so references to an old entry cannot read, write,
publish, release, or invalidate a new entry even if its view key and fence match.
The public presentation-store API retains its original lifetime-history behavior;
only the guarded coordinator invokes the private reclamation operation.
JDBC also bounds local ownership-cleanup bookkeeping at
`retainedViews + pendingJobs`; the extra slots permit transient ownership handoff
while database view limits still apply. Reconciliation forgets only ownership
already proven lost in committed state, so a failed cleanup transaction cannot
make shutdown forget a resource it still owns.

## Required application maintenance and observability

The application must schedule **`browser.maintenance()`** for each live
`BrowserAuthentication` coordinator. Construction starts no scheduler. Calling
only `provider.maintenance()` does not also detach expired local guards or reclaim
the coordinator's local presentation bookkeeping.

With the default windows, a nonoverlapping maintenance tick every 30 seconds is a
reasonable starting point. Choose an interval comfortably below the shortest
configured owner lease and recovery window, account for scheduler/database delays,
and monitor failed or delayed ticks. Coordinator maintenance revalidates attached
owners, detaches expired authority, renews valid owners, reclaims absent local
history, and observes capacity. Stop the application's scheduler during shutdown
and await coordinator closure. Tiny test windows require correspondingly frequent
ticks; a connection whose owner lease expires must reload or reconnect through
fresh admission rather than revive its old guard.

Full JDBC retention sweeps share a durable cadence across all nodes in the same
realm/namespace. The normal interval is the minimum of `workIntervalSeconds` and
the configured idle-binding, idle-view, and history windows. A refused admission
may request one pressure sweep and retry its exact admission checks once; pressure
sweeps are limited to one attempt per second per scope. A full quota can therefore
briefly refuse admission until a scheduled or pressure sweep runs. Existing
authority is checked freshly and remains subject to its actual deadline.

Aggregate JDBC capacity sampling runs at most once per `workIntervalSeconds`
across the scope. Unsampled requests do not acquire the realm advisory lock for
monitoring and do not perform grouped capacity scans. `usage()` remains an
explicit, fresh administrative query. No authorization result is cached, and
fresh admission counts continue enforcing every quota even between samples.

`spoonbill_browser_work` holds the shared sweep/sample reservations. They commit
independently through the reserved clock data source, so a denied admission or
rolled-back sweep cannot reset its backoff and repeatedly trigger a full scan.
A reservation records an attempted job, not successful cleanup. Failures may
delay another normal attempt until the interval, or a pressure retry until one
second; they never certify settlement or grant authority. The durable clock
prevents backward samples from reopening the cadence, including after restart.
Clock and cadence connections are used sequentially, preserving the existing
domain-transaction plus one reserved-connection pool requirement. The reserved
clock data source's role also needs SELECT/INSERT/UPDATE on
`spoonbill_browser_work`; it does not need domain-row mutation privileges for
cadence reservations. Retention-aware
nodes sharing a scope should use consistent limits and cadence settings.

Each JDBC provider separately reconciles its process-local ownership bookkeeping
during its own maintenance and before a claim when that bookkeeping reaches its
bound. Losing the shared sweep reservation never skips this local-capacity
recovery. Reconciliation precedes transaction-local expiry and forgets only
ownership already lost in committed authoritative state, including uncertain
claims after lost commit acknowledgements. Ordinary public GETs do not add a
routine ownership scan; full database sweeps remain on the shared cadence.

Both built-in providers expose administrative `usage()` and implement the common
`capacity` observation SPI. Export counts of retained and anonymous bindings,
retained/active views, retained ceremonies, delivery material, and per-binding
maxima. These observations do not grant authority and should not be exposed as
an unauthenticated endpoint. `usage.warnings()` defaults to 80%; each result
includes the resource, used count, limit, and `exhausted` flag.

`BrowserConfiguration.capacityWarningPercent` defaults to 80 and accepts 1–99.
`BrowserAuthentication` reports threshold transitions using the configured
`Reporter.warning` and its optional `onCapacityWarning: BrowserCapacityWarning => Unit`
callback. The default callback is a no-op; connect it to the application's alert
system. Successful admission, ceremony admission, and maintenance request an
observation; JDBC emits new observations only when its shared sample is due.
The sampled coordinator emits a warning on crossing the threshold, on transition to
exhaustion, and on falling from exhaustion into the warning band. Unchanged
warning states are not emitted repeatedly. Falling below the threshold rearms a
future warning; it does not emit a separate recovery callback. Callback/reporting
failure must not grant authority or disable the provider's limits.
The callback runs on the coordinator's serialized path: keep it bounded,
nonblocking, and free of coordinator reentry. Do not wait for another coordinator
operation from the callback. If alert delivery needs asynchronous work, hand it
to an application-owned bounded queue. `Right(None)` from the common `capacity`
SPI means no new sample and preserves warning state: JDBC uses it between shared
samples, while a provider without capacity support always returns it. Implement
that SPI before expecting these alerts from a custom provider. JDBC warnings can
lag admission by the sampling interval and may be emitted by another serving
node; combine observations at the application's alert destination.

At a warning or capacity refusal:

1. Inspect anonymous demand, live owners, retained dependencies, and maintenance
   health separately. Apply ingress and ceremony rate limits to untrusted demand.
2. Run or restore the scheduled coordinator maintenance. Eligible inactive history
   recovers capacity; live owners and required authentication metadata remain.
   Retained capacity is not a promise to admit more simultaneous live work.
3. Use the authorized administrative owner-retirement API for an abandoned owner
   when appropriate. Do not manually delete session, ceremony, clock, or floor
   rows, clear cookies to evade limits, or retry a consumed proof.
4. If legitimate live demand exceeds the configured budget, review the relevant
   capacity and retention settings. Persistent database, vault, or settlement
   failures require repair before admitting new work; expired evidence cannot
   substitute for a known outcome.

No production alert destination or scheduler is installed by this change. The
application remains responsible for its task scheduling and deployment capacity.

## PostgreSQL V6 migration, restart, and rollback

Apply the forward
[V6 browser retention migration](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V6__browser_retention.sql)
after V4 and V5. V4/V5 migration bytes remain immutable. V6 adds the permanent
`spoonbill_browser_retention` metadata table, a separate shared work-cadence table,
and slot/view activity timestamps.
Existing activity timestamps initially use the database time of migration.
Eligibility still follows the durable nondecreasing authority clock; a previously
advanced clock can make those rows eligible sooner than a new wall-clock idle
window. Plan initial reclamation accordingly. Runtime schema initialization
remains an explicit bootstrap operation, not a request-time migration.

Drain and stop **every old serving/writing binary before** starting a
retention-aware provider against that scope. Apply the DDL transaction using the
migration role and preserve existing authority state. The first provider
transaction enrolls its scope under the realm lock, establishes the issuance
prefix/counter and encrypted key, and clamps legacy browser-session expiries to
at most `enrollment time + maxSessionSeconds`. Enrollment shortens browser
authority when necessary; it does not rewrite host identity stamps or extend host
session lifetime. Account for that possible forced reauthentication in rollout
planning. Retention-aware shared nodes may overlap after this cutover.

The issuance key is encrypted with the configured credential vault and associated
with the scope and identifier prefix. Preserve its decryptability together with
the prefix, counter, binding/view floors, and durable clock across restarts,
backup/restore, and retention-aware rollbacks. Restoring an earlier security
snapshot that loses consumed attempts or lowers these high-water marks can revive
old authority and is not a supported recovery operation. Missing or corrupt key
material fails issuance closed; never manufacture replacement metadata in place.

There is no automatic down migration and no supported mixed old/new writer
period. After reclamation starts, rollback may use only binaries that honor V6
fences and the same host transaction contract. An older binary can recreate
zero-generation or initial-epoch records after deletion and is unsafe even if
V6's tables remain present. Do not drop metadata, reset counters, or reopen a
pre-cutover privileged scope as a rollback shortcut. If a new scope is needed,
perform a separately reviewed drained transition with old-writer exclusion and
host-authority revocation.

The memory provider has no restart persistence: a new instance has a new issuer
and secret, rejects old authenticated cookies/capabilities, and requires login.
Its floors protect the lifetime of that instance; host-owned durable data remains
outside the memory provider's restart guarantee.
