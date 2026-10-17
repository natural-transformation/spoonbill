# Shared PostgreSQL browser authentication

This checkout adds an opt-in shared-store mode to the ordinary
`JdbcBrowserPersistence` / `BrowserAuthentication` integration. Multiple web
processes can use the same realm, cookie namespace, host database and credential
vault. Each physical view has one current database owner. A reconnect claims a
new epoch, validates current host authority and restores the application's
explicit presentation projection. No actor system or remote authority service
is required by core.

Shared-store mode is available in 5.0.0 and later; these measurements are not
a performance acceptance claim. Existing single-owner applications keep their
explicit exclusive mode and do not acquire the clustered snapshot cost unless
they opt into it.

For application testing, start with the historical
[5.0.0-SNAPSHOT migration and testing guide](migration-testing-5.0.0-snapshot.md).
The [v5 validation report](browser-auth-v5-validation.md) records its checks
and bounded output-admission measurements.
The [4.1 snapshot validation report](clustered-browser-authentication-validation.md)
and [consumer feedback](snapshot-4.1.0-consumer-feedback.md) remain historical
records; the major version explicitly permits the identified ABI change.

## Application integration

Configure each web node with `ownership = BrowserOwnership.SharedViews`. This selects
shared expiry observations and fenced view operations. Use
`BrowserOwnership.ExclusiveScope` only when the whole scope has one live owner.
The closed enum replaces the Boolean assertion and conflicting capability flags.
Keep the same database
schema, realm, cookie names, origins, vault keys and host policy on every node.
All authority writes still obey the JDBC provider's transaction/lock contract.

Declare the fields that may survive a reconnect. Deriving `StateSchema` does not
make arbitrary application state safe to store: choose only non-sensitive
presentation, and keep authority and transaction objects out of the projection.

```scala
import spoonbill.snapshot.*
import spoonbill.auth.*

case class Draft(text: String, selectedItem: String) derives StateSchema

val format = new SnapshotFormat[Draft](
  SchemaId.parse("workspace.draft").toOption.get,
  SchemaVersion.fromInt(1).toOption.get
)

val browser = new BrowserAuthentication[F, Page, Principal](
  provider,
  presentation,
  configuration,
  snapshots = provider.projectedSnapshots[Page, Draft](
    format,
    page => Draft(page.draft, page.selectedItem),
    (fresh, saved) => fresh.copy(draft = saved.text, selectedItem = saved.selectedItem)
  )
)
```

Here `provider` is the application's `JdbcBrowserPersistence[F, Principal]` and
`Page` is its state type. `snapshots` is an ordinary public coordinator argument.
The helper issues a store bound to the current authenticated view; it never
requires an application to extract or serialize live capabilities. Anonymous
views restore authentication continuation through the coordinator but do not
persist arbitrary anonymous drafts with this helper.

The [reference application](../examples/browser-auth/src/main/scala/example/browserauth/)
uses the same generic Scala UI for memory and JDBC providers. Its clustered mode
adds a bounded draft/selection schema and the factory call above. It reloads the
business counter from host storage before overlaying presentation, so a lost
response cannot turn an old presentation counter into current domain truth.
There is no application authentication JavaScript.

## Custom session guard initialization

The unreleased recovery changes introduce a source-breaking contract for custom
`SessionGuard` implementations. Every guard must implement
`applicationStateInitialization: ApplicationStateInitialization[F]` and select
one of these cases:

- `Retained()` adopts the existing local application baseline when there is no
  snapshot session. Ordinary HTTP bootstraps and retained reconnects use this case.
- `Reconstructed(() => acknowledge())` requests loading the validated application
  route even without a snapshot. Its required callback constructs the completion
  effect only when the runtime invokes it after authorizing and adopting the
  baseline. A reconstructed guard must discard unfinished presentation on close
  until this acknowledgement has completed.

The callback is a function returning `F[Unit]`, so constructing the guard cannot
start it early for an eager effect such as `Future`. Do not precompute that effect
outside the callback. Snapshot restoration remains independent: a configured
snapshot session still starts from freshly loaded and authorized application
state for either case.

The ordinary `BrowserAuthentication` coordinator supplies this contract. Custom
implementers must choose explicitly; there is no default Boolean or optional
completion callback. Recompile custom integrations for the next major release.

## Ownership and output

HTTP registration creates a durable browser-view admission record, so the first
WebSocket can reach another node. An arbitrary browser-supplied view ID does not
create authority. An admitted handshake resolves current credentials, claims a
monotonically advancing owner epoch and receives fresh process-local capability
objects. Previously admitted sockets keep their captured identity; changing a
cookie cannot promote one.

The database serializes ownership takeover with host transactions, snapshot
commits and protected output admission. A stale owner cannot commit a host
mutation or overwrite a snapshot after takeover. Cleanup includes its captured
owner and epoch, so a delayed old close cannot remove a successor. A paused
claim cannot blindly replace an owner that advanced after the claim's admission.

WebSocket output is admitted as an already constructed immutable message under the same
database authority boundary. Bytes admitted before takeover may arrive later;
network buffers cannot be recalled. A resumed old process must obtain fresh
admission for each later output, and the old epoch is rejected. This includes
sensitive and completion messages and immutable download chunks. Protected HTTP
rendering first constructs its immutable response and then performs a final
database check of request credentials and host authority; it has no physical
view epoch at that point. These guarantees depend on every
receiver using the supplied guarded path and every host mutation honoring the
provider's transaction contract.

No process lease or heartbeat expiry is needed for database takeover. With the
database reachable, recovery takes the browser reconnect delay plus bounded
application/database work. There is no unconditional wall-clock availability
guarantee during a database outage or partition. Nodes that cannot validate
current authority fail closed; they do not serve from an authorization cache.
Shared clock observations retain the database maximum across node changes and
clock rollback.

Same-view reconnect can replace a crashed owner immediately; this does not add
background lease reaping for abandoned distinct views. Their durable owner rows
continue to count against `activeViewsPerBinding` until that view is explicitly
released or taken over and closed. Configure and monitor that bound; capacity
pressure fails closed rather than recycling an old view's security history.

For explicit operator recovery, the provider exposes `usage()` and bounded
`ownedViewPage(after, limit)` inventory. After application authorization,
`retireView(target)(authorize)` conditionally retires the exact observed owner
and epoch. A newer owner makes the old target a no-op. Authorization and any
audit write join the retirement transaction; an uncertain commit must be
reconciled before retry. Retirement advances the ownership fence and preserves
snapshots and replay history. These are administration APIs, not public HTTP
endpoints or an automatic lease-reaping policy. Applications must restrict
inventory visibility and retirement authority.

## What is durable

A presentation transition is acknowledged only after its snapshot transaction
commits and its resulting UI update is admitted. In the example, seeing the new
**Saved draft** text is the acknowledgement. A value typed into the browser but
never submitted to the server has not reached that point. Capture such changes
explicitly if the application needs autosave; component-local state must be
lifted into the projected application state. The helper sees only top-level
application state; private component state remains ephemeral.

The reconnect can reconstruct the document and transport while restoring the
same acknowledged draft and selection. Normal disconnect and old-owner cleanup
retain the durable projection. Fresh host/session/generation checks precede
restoration; logout or revocation prevents an old projection from supplying
authority or restoring protected state.

Snapshot revisions are optimistic write fences. Concurrent or stale writers
cannot overwrite a newer revision. Schema incompatibility refuses restoration
without silently replacing a recoverable draft. A new reader may explicitly
register a previous format using `format.reading(previousFormat)(migrate)`;
migration is a pure function returning `Either[SnapshotError, NewProjection]`.
Identity changes still discard the old identity's presentation through the
fenced reset path.

For overlapping application versions, keep one mutually readable snapshot
format and the same client protocol. A reader accepting the old format does
not imply the old reader accepts the new writer's format. Use an expand/read
phase followed by a write-format switch only after all remaining readers are
compatible, or route incompatible versions separately without erasing drafts.
Arbitrary historical protocol versions are not supported by this contract.

## Transactions, recovery and shutdown

Host proof consumption, session preparation, audit and transactional outbox work
remain in one local JDBC transaction. Recover the original authentication
ceremony after uncertain commit or a lost completion response; do not invoke
its proof callback as a new attempt. Vault keys needed to deliver committed
material must remain available across the node change.

Ordinary business actions are **not automatically replayed** after reconnect.
`provider.transact(view)` supplies fresh authorization and atomic database work;
it does not deduplicate arbitrary application invocations. For retryable
business commands, persist a domain idempotency key/result in the same
transaction, or use the existing operation protocol. External effects need an
outbox or their own idempotency contract. The reference deliberately loses the
response to a committed increment, reconnects, reloads its current count and
checks that no increment was replayed.

For planned replacement, stop routing new connections to the retiring node,
stop application admission and maintenance, settle admitted host work, close its
transports/coordinator, and release its pools/executors. Healthy nodes may serve
other views throughout. A replacement connection may take over a view earlier;
the old node's later work and cleanup are then fenced. An abrupt process loss
uses the same takeover path without relying on a graceful release.

All nodes need bounded executor/pool waits and database timeouts. The existing
clock connection headroom, vault retention and negative/replay history limits
still apply; see [deployment operations](browser-auth-v4-deployment.md). Shared
operation does not permit resetting generations, clearing retained history, or
restoring an older database under the same live authority scope.

The reference sets a 10-second PostgreSQL `idle_in_transaction_session_timeout`,
12-second lock timeout and 15-second statement timeout. A paused process can
otherwise hold a transaction lock indefinitely even when no statement is
running. Its browser fixture allows 25 seconds for recovery under its disposable
local database; production recovery budgets must include the configured lock,
transaction, pool and reconnect limits.

Replacement-enabled rendered GETs with a valid binding perform a pending-state
probe. JDBC currently serializes it through the realm-and-namespace advisory
lock; an empty lookup stops before ceremony/slot row locks, while matching
state requires authoritative checks. The local coordinator also serializes
these operations. This is a capacity consideration for simultaneous reloads or
node-restart reconnects, not a measured throughput guarantee.

An eligible unexpected socket close awaits one recovery probe with a five-second
client timeout before reconnect backoff. The cap of three probes spans failed
reconnect cycles and resets on application readiness. Successful intentional
handoffs and departing pages skip this discovery. Aborting the client request
does not imply admitted database work was canceled. Reconnect is deliberately
ordered after the probe to avoid racing its cookie delivery. A cheaper lookup
or parallel reconnect would need separate race/authority tests; the sequential
performance evidence does not establish burst capacity.

## Migration from released 4.0.0

Authenticated replacement adds an additional compatibility boundary. Every
serving node must understand `spoonbill_browser_replacement` before this feature
is enabled. Drain older binaries and apply the explicit
[V5 forward upgrade](browser-auth-v4-deployment.md#upgrade-an-existing-browser-authentication-deployment);
ordinary snapshot-format compatibility alone does not make mixed replacement
binaries safe.

A committed replacement can be recovered by another compatible node from the
HttpOnly binding and retained reservation, even after its predecessor has been
revoked and the initiating process has died. HTTP recovery grants no application
owner. Its fresh activation handshake validates the same successor and advances
the slot once; subsequent application attachment acquires its own current view
owner. Stale cleanup remains bound to the old physical owner and cannot release
the successor. Credentials and sensitive recovery results never enter shared
presentation snapshots.

When delivery is exhausted or expired and the browser has no valid successor
cookie, show an access-denied page with the existing checked logout/reset path.
Do not re-execute the committed factor change. The reference application exposes
that reset form, and the multi-node fixture additionally drops the replacement
command after commit, kills its serving JVM, recovers on the other node, and
consumes the successor's encrypted result once.

The released 4.0.0 provider assumes one exclusive coordinator. Do not overlap
that implementation with shared mode. Drain every old exclusive writer before
opening the first shared node, retaining the database, cookies, compatible
projection schema and vault keys. Install the reviewed schema from this
checkout through the application's normal migration procedure. Subsequent
rolls may overlap shared-mode nodes within the format/protocol contract above.

The sample is intentionally synthetic and loopback-only. To run two copies,
provide the same disposable PostgreSQL/schema, set
`SPOONBILL_AUTH_CLUSTERED=true`, `SPOONBILL_AUTH_PROVIDER=jdbc` and one shared
`SPOONBILL_AUTH_ORIGIN` pointing at their proxy, then bind `Main` to different
ports. Only the first copy uses `--initialize`. Production applications supply
their own host schema, pool, credentials and vault policy.

`SPOONBILL_AUTH_SNAPSHOT_VERSION=1|2` selects either of two synthetic compatible
snapshot writers. Both explicitly register the other format as readable; their
projection fields are identical. This demonstrates a configured compatibility
window, not automatic compatibility for an arbitrary schema change.

The browser fixture additionally enables `SPOONBILL_AUTH_TEST_FIXTURES=true` and
temporarily starts version 3, an intentionally incompatible reader. The wrapper
reports the exact `SchemaIncompatible` error without snapshot contents and
rethrows it unchanged. Version 3 requires explicit fixture mode; it does not
enable a production error-suppression or reset path. The reference also exposes
`SPOONBILL_AUTH_MAX_ACTIVE_VIEWS` to demonstrate a small finite active-view bound.

## Reproducible correctness fixture

```sh
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh
```

The script builds the public example, starts disposable PostgreSQL when no test
database was supplied, starts two independent JVMs, and runs Chromium and
WebKit from the pinned Nix browser environment. `SPOONBILL_AUTH_ENGINES=chromium`
selects a focused single-engine run. An optional argument supplies an already
compiled runtime classpath file.

The fixture runs A writing snapshot version 1 and B writing version 2, with
mutually configured readers and the same current client wire protocol. The
byte-forwarding proxy only controls routing and failure injection. Cases
cover HTTP and first WebSocket on different nodes, a factor challenge moving
between nodes, original-ceremony recovery after lost WebSocket and HTTP delivery
responses, unknown-view refusal, acknowledged draft/selection recovery, a paused old process
resuming after takeover, graceful old-node cleanup, abrupt owner loss, a lost
business response, incompatible-reader refusal with unchanged snapshot bytes and
later same-view restoration, active-view capacity refusal, revocation during
takeover, and logout. Provider/runtime suites cover precise transaction,
output and revision races and incompatible snapshot formats. A TCP outage is
not proof of every possible network/database partition; the database-failure
contract remains fail closed.

This bounded correctness fixture contains no latency or memory benchmark. Shared
mode uses authoritative database output admission and splits view claim
admission/acquisition across two transactions. Its immutable-message admission
includes produced/current presentation and owner validation in one provider
operation for ordinary DOM, route, RPC and completion frames, avoiding an
additional adjacent authorization transaction. Purpose-specific sensitive
checks remain separate, and downloads remain frozen and validated. Explicit exclusive mode retains
its local publication boundary. The
historical performance baseline remains unchanged; clustered durability adds
database work and needs an equivalent durability baseline for performance
evaluation. No claim of zero measured overhead follows from a correctness pass.
