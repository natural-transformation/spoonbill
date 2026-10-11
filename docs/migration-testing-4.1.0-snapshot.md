# Migrating and testing 4.1.0-SNAPSHOT

The snapshot is ready for local application testing. All 24 library modules have
been published with `sbt publishLocal`; 357 focused Scala tests, the Chromium and
WebKit two-process fixtures, and a separate published-artifact consumer compile
passed. See the [validation report](clustered-browser-authentication-validation.md)
for commands, evidence and limits. This version is not published to Maven Central.

## 1. Select the local snapshot

Update **all** Spoonbill dependencies to `4.1.0-SNAPSHOT` and recompile the
application. For example, keep whichever modules the application already uses:

```scala
val spoonbillVersion = "4.1.0-SNAPSHOT"

libraryDependencies ++= Seq(
  "com.natural-transformation" %% "spoonbill-pekko" % spoonbillVersion,
  "com.natural-transformation" %% "spoonbill-security-jdbc" % spoonbillVersion,
  "com.natural-transformation" %% "spoonbill-ce3" % spoonbillVersion
)
```

Standard sbt resolution includes the local Ivy repository. If the application
overrides its resolvers, include `Resolver.defaultLocal`. Restart or reload its
sbt session after changing the version, then run its compile/tests through its
own Nix environment. Check that no explicit dependency or override still pins
a Spoonbill module to 4.0.0. The independent consumer was compiled with Scala
3.3.8.

To refresh the snapshot after later Spoonbill changes, run from this repository:

```sh
nix develop --no-write-lock-file --command sbt publishLocal
```

## 2. Enable shared PostgreSQL ownership

Use one local provider and coordinator per web process. Keep the same database
schema, realm, cookie namespace/names, public origin, host policy and credential
vault keys across all participating nodes. The shared origin is the browser's
public address, not each node's private bind address.

Change the existing JDBC provider construction to select shared mode:

```scala
val provider = new JdbcBrowserPersistence[F, Principal](
  source = applicationDataSource,
  blockingContext = jdbcExecutor,
  realm = realm,
  namespace = cookieNamespace,
  clock = clock,
  host = hostSessions,
  vault = credentialVault,
  exclusiveAuthorityWriters = false,
  clockSource = Some(reservedClockDataSource)
)
```

The names on the right refer to existing application configuration. Both data
sources must observe the same authority schema. Reserve clock connection
capacity: the clock commits independently while a host transaction is open.
Alternatively, size and bound the shared pool so every admitted host transaction
can obtain that additional connection without exhausting the pool.

Continue using the ordinary `BrowserAuthentication` installation. Keep proof
consumption, host-session creation and transactional audit/outbox writes inside
the existing provider transaction callback. Do not serialize live capabilities,
send JDBC handles between nodes, or wrap preparation in a separate outer
transaction. Shared mode does not require an actor singleton or a new remoting
layer.

The 4.0.0 SQL schema is unchanged by this snapshot. Existing initialized v4
deployments do not need an additional Spoonbill migration. New databases still
need the explicit v4 schema installation; do not run DDL on request paths.

## 3. Declare recoverable presentation

Authentication persistence does not automatically persist UI state. Select the
non-sensitive application fields that should survive a connection moving to
another process:

```scala
import spoonbill.auth.*
import spoonbill.snapshot.*

case class SavedUi(draft: String, selectedItem: String) derives StateSchema

val savedUiFormat = new SnapshotFormat[SavedUi](
  SchemaId.parse("application.workspace").toOption.get,
  SchemaVersion.fromInt(1).toOption.get,
  SnapshotLimits.create(maxUtf8Bytes = 32768, maxStringUtf8Bytes = 16384).toOption.get
)

val browser = new BrowserAuthentication[F, Page, Principal](
  provider,
  presentation,
  configuration,
  snapshots = provider.projectedSnapshots[Page, SavedUi](
    savedUiFormat,
    page => SavedUi(page.draft, page.selectedItem),
    (fresh, saved) => fresh.copy(draft = saved.draft, selectedItem = saved.selectedItem)
  )
)

val serviceConfig = browser.configure(baseConfig)
```

Keep current authority and business data in the freshly loaded state. Restore
only the selected presentation fields onto it. Do not snapshot credentials,
principals, ceremony capabilities, transient sensitive disclosures or an old
business result as a substitute for reloading domain truth. Lift recoverable
component state into the application state being projected; private component
state remains ephemeral. This helper persists authenticated views; anonymous
authentication challenges recover through the coordinator's ceremony records.

A top-level transition is persisted before its resulting UI update is released.
Make the application's saved indicator part of that transition. Seeing the new
indicator is the browser-visible acknowledgement used by the reference test.
Typing that has not been submitted is not durable. Add explicit draft capture
or autosave events if that work must survive too.

Recovery is per existing view. To test it, preserve the tab and interrupt its
socket; a full page reload creates a new view ID. Cross-page or cross-tab drafts
require an application-level durable draft model.

## 4. Cut over safely

1. Back up the test database and record the existing realm, cookies and vault
   configuration. Keep the authority data and keys throughout this migration.
2. Drain and stop **every exclusive-mode writer** before starting shared mode.
   Released 4.0.0 nodes must not overlap new shared-mode nodes. The same applies
   to a new binary still configured with `exclusiveAuthorityWriters = true`.
3. Start one shared-mode node and verify existing session cookies remain valid.
4. Start a second shared-mode node with the same configuration and database.
   The following shared-mode application rolls can overlap within the compatible
   snapshot and client-protocol window.

Set bounded connection, statement, lock and idle-transaction timeouts. A paused
process can otherwise hold a database transaction lock indefinitely. The
reference uses a 10-second PostgreSQL idle-transaction timeout, 12-second lock
timeout and 15-second statement timeout; choose values appropriate to the
application's bounded transactions and recovery budget. Database unavailability
must deny protected work. There is no unconditional recovery-time guarantee
during an outage.

For planned shutdown, stop routing new connections and admitting application
work to the retiring node, settle admitted transactions, close transports and
the coordinator, then close pools/executors. A new owner fences the old owner;
bytes already admitted before takeover may still arrive afterward.

## 5. Test in the application repository

Use isolated test storage and two separate application processes behind one
browser origin. Keep both processes alive for the overlap cases. A same-JVM
test or sequential restart alone is insufficient.

| Check | Procedure and expected result |
| --- | --- |
| Initial routing | Serve initial HTML from A and the first WebSocket from B. The page becomes interactive; unknown view IDs still fail. |
| Session continuity | Authenticate on A, route reconnects to B, then close the old socket. The same valid session remains authenticated without a login prompt. |
| Acknowledged work | Change selected navigation/items and submit a non-sensitive draft. Wait for its saved acknowledgement, reconnect to B, and verify the exact projected values. |
| Graceful roll | Remove A from new-connection routing and stop it gracefully. B restores the same view; A's late cleanup cannot erase B's state. |
| Paused or crashed node | Pause A, take over on B, then resume A; also terminate A abruptly in a separate case. Old writes/output are denied and B retains acknowledged presentation. |
| Revocation | Log out or revoke the host session while changing owners. B must deny revoked authority; an earlier snapshot cannot restore protected state. |
| Authentication recovery | Interrupt delivery after a prepared password/factor transaction. Recover the original ceremony on B and verify proof/session/audit effects occur once. |
| Lost business response | Commit a protected operation on A, discard its response, then reconnect to B. Reload domain truth and verify the operation was not automatically replayed. |
| Storage failure | Remove one node's test-database connectivity. It must not mutate or emit newly protected output. After B takes over, restoring A's connectivity must not revive its old fence. |
| Repeated handoff | Move the same view back and forth and close old transports. Verify configured admission/view limits remain effective and stale cleanup does not affect the current view. |

The library does **not** automatically replay ordinary business commands after
reconnect. For deliberate application retries, use a durable domain idempotency
key/result in the same transaction or the existing operation protocol.
`provider.transact(view)` by itself is not a deduplication API. External effects
need an outbox or their own idempotency contract.

There is no background lease reaper in this snapshot. A crashed owner's same
view can reconnect immediately, but abandoned distinct views continue to count
against configured retained/active limits until explicitly released or safely
retired. Capacity pressure fails closed; do not delete security history to
simulate successful recovery.

## 6. Check the rolling schema window

Keep the same wire protocol and mutually readable snapshot formats across
overlapping application versions. A format can explicitly read a prior one:

```scala
val compatible = newFormat.reading(previousFormat)(old => Right(convert(old)))
```

`convert` is a pure conversion to the new projection type. Loading compatible
data does not rewrite it; the next successful transition writes the configured
current format. Supporting old-to-new reads alone is insufficient while old
readers remain live. First deploy readers that accept both formats while keeping
the old write format, then switch writers after all remaining readers support it.

Test an unsupported schema too. Restoration must raise
`ViewSnapshotException(ViewSnapshotError.SchemaIncompatible)` and preserve the
stored draft. Route that view to a compatible node or complete the compatible
rollout; do not replace its snapshot with an empty value. Identity changes still
reset presentation under fresh authorization rather than expose another
identity's projection.

## Reference commands and feedback

The repository's complete separate-process browser fixture is runnable with:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh
```

It uses disposable PostgreSQL, two JVMs and Nix-managed Chromium/WebKit. The
application example uses only public APIs and contains no authentication
JavaScript. Its two nodes write mutually readable snapshot versions 1 and 2.

When reporting an application failure, include the phase, node transition,
whether the draft/action was acknowledged, dependency versions, and sanitized
exception/transaction outcome. Do not include cookies, credentials, vault keys
or real snapshot payloads.

No latency or memory acceptance benchmark has been run for shared mode. The
existing exclusive mode avoids its added per-frame database admission, while
shared durability necessarily performs additional database work. Treat this
snapshot's evidence as correctness and integration validation.
