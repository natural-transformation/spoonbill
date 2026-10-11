# Migrating and testing 5.0.0-SNAPSHOT

This development snapshot selects a **breaking major version** for clustered
browser authentication. The earlier `4.1.0-SNAPSHOT` added public signatures that
were not binary-compatible with 4.0.0. The [consumer feedback](snapshot-4.1.0-consumer-feedback.md)
and [earlier migration guide](migration-testing-4.1.0-snapshot.md) remain historical
records. Recompile all consumers for v5; there is no promise that an old compiled
caller can substitute these JARs without recompilation.

The snapshot is now published locally and ready to test. All 374 focused Scala
tests, both expanded browser suites, and the independent published-artifact
consumer compile passed. See the [v5 validation report](browser-auth-v5-validation.md)
for exact commands, artifact hashes and the bounded admission comparison.

Custom runtime integrations must also update the trusted output-admission SPI:
`SessionOutputAdmission[F, S]` receives the immutable output plus its produced
and current presentation. The standard `BrowserAuthentication` installation
supplies this implementation; ordinary applications do not need to reproduce it.

## Select the local artifacts

After `publishLocal` has completed in the Spoonbill checkout, use one version
for **all** Spoonbill dependencies in the application:

```scala
val spoonbillVersion = "5.0.0-SNAPSHOT"
libraryDependencies ++= Seq(
  "com.natural-transformation" %% "spoonbill-pekko" % spoonbillVersion,
  "com.natural-transformation" %% "spoonbill-security-jdbc" % spoonbillVersion
)
```

Keep other Spoonbill modules the application uses at that same version. Standard
sbt resolution includes the local Ivy repository; custom resolver lists must
include `Resolver.defaultLocal`. Reload sbt and recompile through the consuming
repository's Nix environment. No Maven Central publication is implied.

To refresh the local snapshot in this repository:

```sh
nix develop --no-write-lock-file --command sbt publishLocal
```

## Replace Boolean ownership with the explicit mode

```scala
import spoonbill.auth.BrowserOwnership

val provider = new JdbcBrowserPersistence[F, Principal](
  source = applicationDataSource,
  blockingContext = jdbcExecutor,
  realm = realm,
  namespace = cookieNamespace,
  clock = clock,
  host = hostSessions,
  vault = credentialVault,
  ownership = BrowserOwnership.SharedViews,
  clockSource = Some(reservedClockDataSource)
)
```

| Previous configuration | v5 configuration |
| --- | --- |
| `exclusiveAuthorityWriters = true` | `ownership = BrowserOwnership.ExclusiveScope` |
| `exclusiveAuthorityWriters = false` in the local 4.1 snapshot | `ownership = BrowserOwnership.SharedViews` |
| Custom provider capability ownership Booleans | One `BrowserOwnership` value in `BrowserProviderCapabilities` |

`ExclusiveScope` requires one live provider/coordinator for the entire realm and
cookie namespace. `SharedViews` permits overlapping processes while the database
fences each physical view. An enum value does not establish backend guarantees:
custom providers must implement and test the corresponding receiver and output
admission contract. Memory state cannot become a clustered backend by changing
its advertised mode.

Drain every existing exclusive writer before enabling shared mode. Released
4.0.0 nodes cannot overlap shared-mode nodes. Keep the database, cookies, host
policy, vault keys and public origin consistent across the new nodes. The
framework SQL schema is unchanged from 4.0.0; existing installations do not need
a new Spoonbill DDL migration. Do not reset security generations or histories.

## Persist selected presentation

Use the ordinary coordinator with an explicit projection:

```scala
import spoonbill.auth.*
import spoonbill.snapshot.*

case class SavedUi(draft: String, selectedItem: String) derives StateSchema
val format = new SnapshotFormat[SavedUi](
  SchemaId.parse("application.workspace").toOption.get,
  SchemaVersion.fromInt(1).toOption.get
)

val browser = new BrowserAuthentication[F, Page, Principal](
  provider, presentation, configuration,
  snapshots = provider.projectedSnapshots[Page, SavedUi](
    format,
    page => SavedUi(page.draft, page.selectedItem),
    (fresh, saved) => fresh.copy(draft = saved.draft, selectedItem = saved.selectedItem)
  )
)
val serviceConfig = browser.configure(baseConfig)
```

Keep authority, credentials and current business data in freshly loaded state.
The projection is ordinary non-sensitive presentation. Lift recoverable
component state into the projected application state; private component state
remains ephemeral. A saved indicator must be part of the committed transition.
Unsubmitted browser typing is not durable; add explicit capture or autosave if
it must survive. The JDBC helper persists authenticated views; anonymous
authentication continuation uses durable ceremony records instead.

Recovery preserves an **existing view**. Test by keeping the tab and interrupting
its socket; a full page reload creates another view. Cross-page or cross-tab
drafts require an application-level durable model.

Across overlapping application versions, keep the same wire protocol and
mutually readable snapshot formats. Register a prior format with
`newFormat.reading(previousFormat)(old => Right(convert(old)))`. A new reader
accepting old bytes does not make an old reader accept new writes. Deploy mutual
readers before switching writers. Incompatible restoration raises
`ViewSnapshotException(ViewSnapshotError.SchemaIncompatible)` without overwriting
the stored projection; route that same view back to a compatible node. Do not
silently replace the snapshot with empty state.

## Exercise the application integration

Use two real application processes, isolated shared PostgreSQL and one browser
origin. Preserve the tab and distinguish acknowledged work from unsubmitted
input or a response that never reached the browser.

| Scenario | Required observation |
| --- | --- |
| Split initial routing | Initial HTML on A and first socket on B reaches `VIEW_READY`; an unknown view ID does not. |
| Handoff and draft | Acknowledged draft/selection survives A→B with the same valid session. |
| Paused, graceful and killed owners | B can recover; resumed old writes and cleanup cannot replace B's current fence. |
| Lost authentication response | Recover the original password/factor ceremony after committed WebSocket output is lost; proof, session and audit effects remain exactly once. |
| Lost delivery response | Retry the bounded original completion handle after an HTTP reply is lost; no password/factor callback is replayed. |
| Lost business response | Reconnect, load domain truth and verify the committed operation was not automatically repeated. |
| Incompatible schema | Observe the exact refusal, verify stored revision/format/payload remain unchanged, and restore the same view on a compatible node. |
| Revocation during takeover | No new interactive protected view or protected HTTP response accepts the revoked session. |
| Capacity | An excess distinct view is refused; closing a current view releases active capacity without removing replay history. |
| Database failure | A disconnected node cannot commit protected work or admit new output from cached authority. |

The supplied generic fixture runs the first nine categories through public APIs,
two JVMs and Nix-managed Chromium/WebKit:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh
```

`SPOONBILL_AUTH_ENGINES=chromium` or `webkit` selects one engine. Run engines
sequentially after compilation is quiet. The loopback fixture alone enables
incompatible-reader diagnostics and uses synthetic data; it adds no shipped
client JavaScript or production test endpoint. Provider tests additionally cover
precise transaction, owner-epoch, storage-failure and output-admission races.

## Operational and acceptance limits

Retain a reserved clock connection or sufficient bounded pool headroom. Set
connection, statement, lock and idle-transaction timeouts: a paused client can
otherwise hold database locks indefinitely. The reference uses 10-second idle,
12-second lock and 15-second statement limits with a 25-second browser recovery
assertion. Availability during database outage or partition is not guaranteed;
protected work fails closed.

Ordinary business actions are not automatically replayed. Deliberate retries
need a durable domain idempotency key/result in the same transaction, the
existing operation protocol, or an appropriate outbox for external effects.
`provider.transact(view)` provides authorization and a transaction, not arbitrary
command deduplication.

There is no background reaper for abandoned distinct views. Same-view takeover
does not release an unbounded collection of abandoned view IDs; retained and
active limits still apply. Plan safe scope retirement before exhaustion and
never delete replay/fencing history to simulate recovery.

Administrators can inspect `provider.usage()` and page through
`provider.ownedViewPage(after, limit)` (at most 1,000 records per page). Restrict
who may read this inventory. An authorized operator can supply the observed
owner record to `provider.retireView(target)(authorize)`; the application
authorization/audit callback runs in the same JDBC transaction. It retires only
that exact owner/epoch, so a takeover makes a delayed request return `Right(false)`.
Successful retirement retains snapshots and security history while advancing
the fence. A `CommitUnknown` result requires reconciliation, not blind retry.
No administrative HTTP endpoint is installed by the library.

`BrowserPresentation` callbacks, projection callbacks and compatibility
conversions must be pure, bounded and repeatable. In shared mode the `allowed`
predicate can run inside a database admission transaction; it must not perform
I/O, block or mutate application state.

Ordinary DOM, route, RPC and completion output performs fresh owner and
produced/current presentation validation with immutable output admission,
removing duplicate authorization transactions. Purpose-specific sensitive checks remain
separate, and downloads remain frozen and validated. Snapshot commits, ownership
acquisition and realm serialization still perform database work. No latency,
allocation or retained-heap acceptance result follows from these correctness
tests; historical performance baselines remain unchanged.
