# ADR 0003 — Session and security ownership

Status: adopted in the runtime and persistence providers.

## Context

A browser connection, application, component, local presentation and durable
browser view do not share a lifetime. A replacement may acquire authority while
an old owner is draining; a provider release may fail after local access has
already been revoked. Treating these situations as one `closed` flag permits
stale publication, premature resource release or accidental revival.

## Decision

Model lifecycle and admission decisions explicitly, but keep resource handles,
callbacks, credentials, plaintext and effect execution in their interpreters.
Pure transitions receive observations made at the existing effect boundaries.
Committing a transition does not itself prove that cleanup or durable release
has completed. Preserve independent state when it has a different owner,
publication boundary or failure contract.

### Session teardown has an owner and an ordered drain

Guarded retirement stops extension delivery and component admission before
denying session access. It then closes the frontend, drains admitted writes,
destroys the application, closes the guard, destroys extensions, and removes
storage/application ownership before releasing the attachment. An unfinished
write drain holds its stage and attachment; a timeout must not turn it into
permission to release resources. Repeat cleanup requests do not join the
current owner. An unisolated teardown failure is terminal for that cleanup.

Legacy retirement remains a separate protocol: disconnection is not retirement,
and rollback and disconnected-watcher retirement have different restoration
work. Both session paths close the frontend before destroying the application.
The frontend owns browser consumers; the application owns its state queue and
components. See [session stages](../../modules/spoonbill/src/main/scala/korolev/server/internal/services/GuardedSessionMachine.scala),
[their interpreter](../../modules/spoonbill/src/main/scala/korolev/server/internal/services/SessionsService.scala)
and [lifetime tests](../../modules/spoonbill/src/test/scala/korolev/server/internal/services/SessionLifetimeSpec.scala).

A guard acquired by a detached operation registers ownership before publishing
its result. Teardown takes or seals that ownership slot. A guard arriving after
the slot is sealed is closed, even if its original waiter was canceled. This
belongs to the resource-owning caller, not a generic timeout helper; see
[native cancellation tests](../../interop/ce3/src/test/scala/spoonbill/server/internal/services/GuardedOpenCancellationSpec.scala).

Session registry release checks the exact attachment owner. A failed storage
removal survives attachment release and must be retried under the next
attachment fence before restoration. Application handles and asynchronous
publication stay in `AsyncTable`, outside the
[metadata registry](../../modules/spoonbill/src/main/scala/korolev/server/internal/services/SessionRegistryMachine.scala).

### Application startup and component replacement use different retry rules

Application startup has one owner and a shared completion observed by that owner
and its joiners. Failed startup is not retried on the same partially initialized
instance. Destruction rejects new startup and fences delayed publication;
canceling a joining caller cannot settle another caller's startup. Already
admitted effects may overlap destruction; application effects do not run under
a lifecycle lock. See [startup implementation](../../modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala)
and [startup tests](../../modules/spoonbill/src/test/scala/korolev/internal/ApplicationLifecycleSpec.scala).

Components instead permit a failed loader to retry while its owner remains open.
Only one initial loader is admitted; placeholders do not advance the parameters
of the actual rendered baseline. A ready baseline may coexist with an in-flight
refresh. Component incarnation and lease checks reject late writes and preserve
predecessor failure through replacement. An accepted render remains the next
diff baseline even if subsequent obsolete-binding cleanup fails. These are
separate contracts, not additional application-startup states; see
[component ownership](../../modules/spoonbill/src/main/scala/korolev/internal/ComponentOwnership.scala),
[loader tests](../../modules/spoonbill/src/test/scala/korolev/internal/ComponentBaselineSpec.scala)
and [render tests](../../modules/spoonbill/src/test/scala/korolev/internal/RenderLifecycleSpec.scala).

### Frontend publication is fenced before cleanup is claimed

Every frontend close publishes its output fence before checking the once-only
cleanup claim, including a competing or recursive close. The owner attempts all
ordered cleanup steps after nonfatal failures and returns the first failure;
repeat callers do not join it. Download cancellation is launched without joining
finalizers that may await their consumers.

Publication keeps authority checks and queue offers at their existing boundaries.
An inline consumer may close or publish recursively during an offer, so acceptance
updates the latest connection state rather than restoring a captured state.
Navigation/departure fences remain independent of the publication monitor.
See [frontend implementation](../../modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala),
[close tests](../../modules/spoonbill/src/test/scala/korolev/internal/FrontendCloseSpec.scala)
and [publication reentry tests](../../modules/spoonbill/src/test/scala/korolev/internal/FrontendPublicationReentrySpec.scala).

Sensitive presentation metadata follows the same separation: plaintext and
cancellation handles remain interpreter-owned. Retirement detaches them before
callbacks; browser clear is queued before outcome notification. A model transition
or CAS retry must never invoke a cancellation or completion callback. See
[sensitive runtime](../../modules/spoonbill/src/main/scala/korolev/internal/SensitiveRuntime.scala)
and [concurrency tests](../../modules/spoonbill/src/test/scala/korolev/internal/SensitiveRuntimeConcurrencySpec.scala).

The core module defines sensitive presentation metadata and error types.
The runtime owns the plaintext payload. They share the `spoonbill.sensitive`
package, so the purity guard checks the metadata definitions before allowing
individual named imports. A wildcard exception for that package would also
admit payload types. Consumers assembling JARs directly need matching core and
runtime versions. [Metadata definition](../../modules/core/src/main/scala/spoonbill/sensitive/SensitivePresentation.scala) ·
[Classpath boundary test](../../modules/core/src/test/scala/spoonbill/security/CoreBoundarySpec.scala)

On the client, navigation and departure have independent barriers. A completion
for one cannot reopen the other, and a late readiness event cannot reopen a
recovering view. Bridge installs its history handler before the sensitive guard.
Changing listener order can change which history event reaches the server.
The model owns the counters and consumed presentation IDs. The interpreter
owns DOM roots, clear operations and acknowledgement frames.
[Bridge decisions](../../modules/spoonbill/src/main/es6/bridge-machine.js) ·
[Browser behavior tests](../../misc/sensitive-client-tests.mjs)

### Local retirement precedes acknowledged provider release

Browser views distinguish Claimed, Prepared, Live, RetiringAttached,
RetiringDetached and Released. Initialization acknowledgement and the execution
claim for guard close are separate axes: initialization controls presentation
retention, not authority. Failed guard close permits a cleanup retry without
undoing retirement or restoring discarded presentation.

Origin validation precedes provider acquisition. Claims are indexed before local
preparation can fail; fresh authorization and `sameAuthority` precede local lease
publication. Retirement removes local attachment and releases or invalidates the
local lease before provider release. Ownership remains indexed until that release
is acknowledged. A read-turn denial fences new observations immediately and owes
a write-turn detach under cancellation protection. Retiring attached predecessors
cannot donate presentation to a reconnect.

These boundaries are implemented in [BrowserAuthentication](../../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala)
and [BrowserViewMachine](../../modules/spoonbill/src/main/scala/korolev/auth/BrowserViewMachine.scala),
with [failure tests](../../modules/spoonbill/src/test/scala/korolev/auth/BrowserAuthenticationFailureSpec.scala)
and [native cancellation coverage](../../interop/ce3/src/test/scala/spoonbill/auth/BrowserAuthenticationIOSpec.scala).

### Store transitions preserve security checkpoints

Authority checks, host callbacks, clock reads, capability issuance and SQL
transactions stay in their original order. They are not converted into deferred
commands or eagerly sampled inputs: rejected paths must not gain clock reads,
and publication already performed before a later failure must remain visible.

In-memory host transactions may roll back staged records without rolling back
observed time. Reclamation publishes removed records and advances fence floors
before invoking listeners. Record publication preserves frontiers advanced since
the host snapshot was staged. See [memory provider](../../modules/spoonbill/src/main/scala/korolev/auth/InMemoryBrowserPersistence.scala)
and [checkpoint tests](../../modules/spoonbill/src/test/scala/korolev/auth/InMemoryBrowserCheckpointSpec.scala).

The JDBC clock keeps observation and acknowledged durable coverage separate.
Sampling may advance observation even when persistence fails; only acknowledged
coverage permits reuse. Combining these frontiers would change failure and
restart semantics. Clock sampling, renewal locking, persistence and acknowledgement
remain interpreter checkpoints; see [JDBC clock](../../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserClock.scala)
and [clock tests](../../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcBrowserClockSpec.scala).

Operation admission is also distinct from durable outcome. Exact-intent checks
and consume-on-attempt authority precede transaction dispatch; cancellation or
storage failure does not prove a durable negative result. Recovery preserves
committed positive and negative outcomes and must fence earlier execution before
recording `NotCommitted`. It cannot turn a negative outcome into permission to
execute again. See [transaction contracts](../../modules/spoonbill/src/main/scala/korolev/security/transaction/OperationTransactions.scala)
and [durable outcome tests](../../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcOperationOutcomesSpec.scala).

Presentation and snapshot cleanup also require ownership checks, not merely a
matching key. A stale presentation token cannot purge its successor; a read pin
is removed only for its matching owner and epoch. Snapshot deletion has an
explicit cleanup claim with retry/replay rules, separate from publication.
See [presentation store](../../modules/spoonbill/src/main/scala/korolev/state/BoundedPresentationStore.scala),
[reference model](../../modules/spoonbill/src/main/scala/korolev/auth/SnapshotReferenceMachine.scala)
and [reference tests](../../modules/spoonbill/src/test/scala/korolev/recovery/ReferenceSnapshotStoreSpec.scala).

## Alternatives and consequences

A universal close state or completion latch would erase the distinction between
nonjoining teardown, drain completion and retryable provider release. One large
state record containing every resource would also conflate publication boundaries
and make credentials or callbacks available to pure transitions. Neither is used.

Existing serialized render state, asynchronous publication tables and one-use
lease guards remain where they already express the required protocol. Adding a
machine wrapper alone would not strengthen their guarantees. Conversely, failure
must not reopen retired ownership merely to make retries convenient.

Future changes must preserve both transition legality and effect order. Pure laws
can check stale-owner rejection and lifecycle edges; interpreter tests must also
exercise reentry, delayed completion, native cancellation and partial failure.
Changing a close operation's joining or retry behavior is a contract change,
not an interchangeable implementation detail.
