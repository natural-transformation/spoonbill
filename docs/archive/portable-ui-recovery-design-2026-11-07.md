> **Historical design archive — not current API documentation.** This preserves
> the 2026-11-07 design and its status annotations through 2026-11-09. Its Scala
> sketches were not compiled as documentation examples and differ from the
> implemented public API (for example, `SnapshotReferenceSupport.bind` now
> requires a `SnapshotSchemaPolicy`). Do not copy these sketches into an adapter.
> Use the [current status and API sources](../portable-ui-recovery-plan.md),
> [adapter qualification guide](../recovery-adapter-qualification.md) and
> [release/rollout runbook](../portable-recovery-rollout.md) for current work.

# Portable UI recovery

Status: implementation design, revision 3; status updated 2026-11-09 after
merged PRs #25 and #26. Review baseline: 2026-11-07.
R0 and R1 are implemented (public API in `spoonbill.recovery`, JDBC capability
and V7 migration). R2 code and local packaging are implemented, including
`spoonbill-recovery-testkit`, the independent adapter consumer and qualification
guide; public release and independent consumption of the released kit remain
pending. R3's `reference-payloads` two-JVM scenario and authenticated snapshot
latency harness are implemented, with measurements recorded in the
[latency report](../event-latency-performance.md#authenticated-views-with-durable-snapshots-inline-and-reference-backed).

Baseline: Spoonbill 9.0.0 at `3bb6d2148abeeb480397a7f354bc56ba0e97269d`.
PairTime reference: `f5f1493fbab56a3b70577964b270f80036856758`, using Spoonbill 7.0.0.
This revision addresses the historical plan review dated 2026-11-07.
The interface sketches below preserve the design baseline; consult the public
`spoonbill.recovery` sources for the implemented signatures. The implementation
is recorded under `Unreleased`, not as a completed public release.

## 1. Outcome and product direction

Give applications a generic extension for recoverable presentation storage while
preserving current authority, view ownership and revision guarantees. Users can
implement storage in their chosen database through public interfaces. This is
not a commitment to ship Cassandra, Redis or another named database adapter.

The first increment selects **immutable payload storage with provider-controlled
reference publication**. A small payload adapter does not implement login,
session replacement or ownership election. The existing authority provider keeps
those responsibilities and atomically publishes the reference under its fences.

The reference-backed path is opt-in for externally governed storage or payloads
whose placement/size justifies it. Existing inline PostgreSQL snapshots remain
the default, especially for small drafts. Generic storage choice does not require
every application to pay the cost of external payload publication.

Publication is a provider-neutral optional capability. PostgreSQL is the first
implementation, not a type-level requirement. A non-PostgreSQL authority provider
can implement the same capability alongside `BrowserPersistence`. A payload-only
adapter changes where presentation bytes live; it does not, by itself, remove
PostgreSQL from a deployment that still uses the JDBC authority provider.

Spoonbill's focus remains stateful Scala interfaces with explicit authority,
recovery and resource lifetimes. Keep the domain model and effect runtime with
the application. Generic interfaces should simplify this model, not introduce a
prescribed ORM or automatic persistence of entire application objects.

The promise of no database-specific plumbing in application handlers applies to
**presentation recovery**. Protected domain writes retain their own transaction
and authority/fencing obligations, especially across stores.

## 2. Shipped foundation and chosen scope

| Area | Shipped behavior and implication |
| --- | --- |
| [ViewSnapshotStore](../../modules/spoonbill/src/main/scala/korolev/snapshot/ViewSnapshots.scala) | Bound load/save/reset API; writes compare revision and owner epoch atomically at the storage boundary. |
| [ViewSnapshotSession.projected](../../modules/spoonbill/src/main/scala/korolev/snapshot/ViewSnapshotSession.scala) | Restores an explicit projection over fresh state. Its session has initialize/commit, not takeover/retire hooks. |
| [BrowserAuthentication](../../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala) | Calls a pure `BrowserView[P] => Option[ViewSnapshotSession[F, S]]` constructor after claiming a view. The constructor is not a distributed attachment transaction. |
| [JdbcBrowserSnapshots](../../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSnapshots.scala) | Runs snapshot operations inside `provider.transact(view)`, keeping authority and ownership checks in the provider's transaction. |
| [BrowserPersistence](../../modules/spoonbill/src/main/scala/korolev/auth/BrowserPersistence.scala) | Existing full authority-provider SPI, including claim, authorize, release, output admission and ceremonies. |
| `StateStorage.fromRepository` | Cached serialized-node callbacks, without the full shared-owner/authority protocol. It is not a replacement for guarded recovery. |

Choose these boundaries for R1/R2:

1. A generic payload adapter stores immutable bytes and reports its durability/failure
   outcomes. It never elects a view owner or accepts browser authority.
2. An optional provider capability owns reference publication, read protection,
   operation reconciliation and retention metadata in the same atomic domain as
   its ownership decisions. JDBC implements it as the reference provider.
3. A generic composition helper builds the existing snapshot-session factory from
   those two capabilities and the application's format/project/restore functions.

Keep `spoonbill-security-jdbc`, `JdbcBrowserPersistence` and its existing
`projectedSnapshots` API supported. Their present inline-storage path remains
available; opting into external payloads must be explicit. The proposal does not
remove PostgreSQL-specific transaction capabilities or force existing users to migrate.

The full-provider SPI remains available for users replacing authority storage too;
implementing additional production authority providers is outside this increment.
An independent snapshot store notified after takeover would have a two-store
coordination gap. Do not advertise that simpler arrangement as equivalent shared
ownership. Other coordinated protocols are possible, but outside the selected design;
this plan does not introduce a weaker cache tier as an implicit fallback.

## 3. PairTime evidence and migration limits

PairTime is a design reference, not an immediate release gate. Paths below are
plain references under its pinned commit, so this document remains readable
without a sibling checkout:

- `nt-ui/src/main/scala/com/nt/front/UiWebService.scala`: installs
  `provider.projectedSnapshots(format, project, restore)`.
- `nt-ui/src/main/scala/com/nt/auth/browser/PairTimeBrowserRuntime.scala`:
  constructs the JDBC provider in `SharedViews` mode.
- `nt-ui/src/main/scala/com/nt/front/auth/BrowserPresentationSnapshot.scala`:
  selects bounded draft/navigation values, excludes authority and credentials,
  restores onto fresh data and retains `pendingDraft` during asynchronous loading.
- `nt-ui/docs/adr/ADR-0004-coordinated-browser-authentication.md`: describes
  PostgreSQL recovery of acknowledged projected inputs, not arbitrary process
  state or unsent browser edits.

Preserve the application's matching user/resource/form checks, explicit navigation
abandonment and rejection of unrelated delayed loader results. Domain event
persistence and the retired Pekko UI cache are separate from this recovery path.

Pending-restoration helpers and sealed-ADT schema helpers can be later ergonomic
increments. PairTime's `BrowserDraft` currently uses a record of optional variants
under `pairtime.browser-draft`, version 1. If a helper preserves that exact format,
no schema change is required. If its encoding changes, require a new version,
retain the old decoder and register a bounded pure conversion using:

```scala
newFormat.reading(previousFormat)(migrate)
```

Deploy compatible readers before switching writers; a new reader accepting old
data does not make old readers understand new data. Loading must not silently
rewrite or erase the only recoverable draft. See [schema compatibility](../clustered-browser-authentication.md#what-is-durable).

## 4. Application-facing contract and data exposure

Application code supplies `SnapshotFormat[D]`, pure bounded `S => D` and
`(S, D) => S` functions, and fresh domain loading/permission checks. It selects
an adapter and an authority provider exposing the reference capability. The
adapter may come from Spoonbill, the application or another library.

The application also schedules the recovery binding's `maintenance()` beside
`browser.maintenance()` and closes its owned binding during shutdown. The two
maintenance operations have different responsibilities; external payload I/O
must not be inserted into the browser coordinator's gate turn.

Keep the existing `BrowserAuthentication.snapshots` constructor parameter's
shape. Add the composition helper and optional provider capability rather than
new required methods on every `BrowserPersistence` implementation. Provider
binding remains pure; I/O starts inside snapshot or maintenance operations.
The helper returns the factory plus an explicit lifecycle, not a bare factory:

```scala
def projectedWithPayloads[F[_]: Effect, P, S, D](
  provider: SnapshotReferenceSupport[F, P],
  payloads: SnapshotPayloadStore[F],
  format: SnapshotFormat[D],
  project: S => D,
  restore: (S, D) => S,
  settings: PayloadRecoverySettings
): PayloadSnapshotBinding[F, P, S]

trait PayloadSnapshotBinding[F[_], P, S]:
  def snapshots: BrowserView[P] => Option[ViewSnapshotSession[F, S]]
  def maintenance(): F[Unit]
  def close(): F[Unit]
```

Pass `binding.snapshots` to the unchanged coordinator constructor parameter.
Settings define validated batch/concurrency limits, retry cadence and timeouts;
timeouts limit observation, not the physical lifetime of remote work. Stop request
admission and drain admitted browser snapshot writes before closing the binding.
Closing stops new binding-owned work and releases local resources; it does not
implicitly close a shared provider/store or await uncancellable I/O forever.
Unsettled durable obligations remain recoverable by another maintenance instance.

Only authenticated views participate initially, matching the current JDBC helper.
Anonymous drafts, autosave of browser-only edits and private component-state
durability need separate explicit contracts. Persist only selected presentation;
never serialize live capabilities, callbacks, DOM objects or authentication secrets.

Data exposure is intentionally narrower than `BrowserView`:

- The trusted authority provider binds the view and validates current authority.
  Public view fields and stored metadata are identifiers, not credentials or
  evidence that authority is still current.
- The payload adapter receives a fresh opaque payload ID, bounded owned bytes
  and integrity/length information. It receives no `BrowserView`, raw cookies,
  raw browser binding, principal or raw subject identifier.
- Subject/browser identity metadata stays in the provider. Retain the JDBC
  binding's digesting or a documented stronger pseudonymization scheme; do not
  expose raw identity text through new generic keys. Digests are pseudonymous,
  not necessarily anonymous.
- Selected draft bytes can still contain application personal data. The adapter
  is a trusted storage participant; metadata minimization does not make payloads
  public or remove host confidentiality/access-control requirements.

Payload confidentiality is the deployment's responsibility in this increment,
including transport, storage access and any encryption/key management it requires.
The binding does not add envelope encryption. An integrity digest is not encryption;
an encryption wrapper would need its own key-rotation and recovery contract.

## 5. Publication, reads and retirement

### Publication

1. The generic binding creates a stable operation ID and a fresh payload reference.
   The provider durably reserves that exact operation/reference under current
   identity, owner epoch, expected revision and save/reset intent. Resolve an
   uncertain reservation before uploading; do not create a new attempt blindly.
2. The payload adapter stores immutable bytes. Its successful result means the
   declared storage durability point has been reached, not merely local enqueue.
3. The provider rechecks authority, owner epoch, expected revision, reservation
   validity and exact reference before atomically publishing and recording the
   operation outcome. Takeover and retention decisions use that same atomic domain.
4. Save returns success only after both storage durability and acknowledged
   reference publication. A timeout may mean committed; reconcile the same
   operation ID, without overwriting a newer revision or replaying business work.

References are unique to a publication attempt; do not share one payload ID across
views/revisions or reuse it after retirement. A retry of the same operation must
match its original reference/metadata. Reset is a distinct checked intent, limited
to the existing identity/schema-reset rules; it cannot bypass corruption checks.

### Commit cost budget

Snapshot-enabled top-level writes wait for persistence before updating state;
`NoUpdate` does not commit a snapshot. The following is the R1 budget for a healthy,
initialized session's successful save/reset, with no retry or conflict:

| Path | Snapshot-provider transactions | Logical payload operations |
| --- | --- | --- |
| Existing inline JDBC | 1 | Payload written inside that transaction. |
| Reference-backed JDBC | 2: reserve, publish | 1 durable `put`; no `get`, `delete` or status lookup on this success path. |

Record superseded-reference retirement/cleanup intent within publication rather
than adding another foreground transaction. These counts are not total click SQL
statements or network round trips: separately count authority, domain, clock,
initialization, read-pin, retry and maintenance work, plus operations internal to
the payload adapter. A fresh empty view also publishes an initial projection;
measure startup separately. Any increase to the healthy commit budget requires
an explicit design decision and evidence, not an unreported extra lookup.

The payload write remains on the saving view's critical path. Do not hide its
latency by acknowledging early, replaying effects or coalescing committed revisions
without a separately reviewed change to the durability contract.

### Reads

The provider returns the current reference under a bounded read pin. Fetch and
validate its bytes, schema and integrity, then revalidate the pin's reference,
revision, authority and owner before adopting the result. Release the pin on all
outcomes. An expired pin cannot authorize output; current protected-output checks
still apply after restoration. Missing/corrupt referenced bytes are failures,
not an empty snapshot or permission to silently overwrite it.

### Retirement and garbage collection

The provider is the liveness source. Its authoritative records distinguish staged,
published, read-protected and retired references. Publish and retire must serialize:
once a reference is eligible for deletion, no late publication can make it live.

When a view is reclaimed or a revision becomes obsolete, record a durable cleanup
obligation in the same transaction that removes/retires the reference. Maintenance
claims bounded batches of deletion work; the adapter deletes idempotently and the
provider records completion. A crash between these steps must retain retryable work.
An enumeration of currently live references or a fire-and-forget callback is not
sufficient: both can race publication or lose cleanup intent.

Protect in-flight writes and read pins. Final physical deletion has two acceptable
bases, supplied by the payload adapter's qualified store semantics:

- **Durable retirement fence:** retirement and write admission serialize at the
  store, so delayed or retried puts cannot recreate a retired payload. Checking
  a tombstone and writing later in separate operations does not satisfy this rule.
- **Verified quiescence:** further admission is irrevocably closed, all admitted
  writes have settled, and a final delete completes afterward. A process-local
  counter does not establish this across crashed or paused writers.

If neither is established, deletion remains `Uncertain`, even if a native delete
currently finds no object. Keep the charged cleanup obligation and retirement
metadata, expose backlog size/age, and apply backpressure at capacity. A reservation
deadline can stop further logical admission or schedule a retry; abandoning a
caller, waiting a duration or issuing two deletes cannot establish physical finality.
Any bounded-write-lifetime proof must cover delayed admission, execution and retries.

Do not reclaim a retirement fence until further admission is irrevocably closed
and all already-admitted writes have settled. Never-reused references prevent
renewed publication, but do not by themselves bound physical orphan retention.
If quiescence cannot be proved, retain the fence itself and its accounting, and
reject new work at the configured bound.
Unknown publication remains protected until resolved. Metadata floors/retirement
rules must prevent stale tokens from becoming valid after records are reclaimed.

Bound reservations, pins, payload size and cleanup work. Under permanent outages,
cleanup progress is not guaranteed: retain the necessary safety metadata, apply
backpressure/fail closed at capacity and report the condition. Do not promise
unbounded admission together with bounded retention during an indefinite outage.

### Maintenance scheduling and claims

`binding.maintenance()` performs short claim/complete/retry provider operations
and invokes adapter deletes **outside both provider transactions and coordinator
gate turns**. The same exclusion applies to upload/download I/O. Do not extend
`BrowserAuthentication.maintenance()` by doing external deletes inside its first
gate turn. Configure finite batches and in-flight deletes; overlapping sweeps on
one binding must coalesce or share those bounds, not multiply them.

Multiple nodes may claim different retired references concurrently. A claim has
a provider-issued ID, generation and lease deadline; at most one current claim
per reference owns metadata completion. After expiry another worker may reclaim
it. A former worker's delete can still finish safely against the immutable retired
reference, but its stale completion cannot clear the newer claim or obligation.
Repeated completion of the same settled claim is idempotent. Timeouts leave work
retryable, with bounded backoff and diagnostics rather than a tight retry loop.

Claim expiry transfers metadata ownership, not physical cancellation. Keep
unresolved delete attempts accounted for across reclaims, and cap further retry
admission while those attempts remain uncertain; timeout alone cannot release
that capacity. Recovery may require adapter-specific settlement evidence. State
the actual guarantee as bounded per-binding admitted work and retry rate, with
outstanding uncertainty visible and charged. Do not infer a hard physical
cluster-wide I/O bound from local counters or lease expiry.

Operator reconciliation may inspect retired operation status and retry physical
deletion through these same rules. It cannot turn an absent object or elapsed
time into proof that an unknown write cannot later arrive.

### Deployment and rollback

These operations require new provider-side durable metadata and an explicit
schema migration for the optional reference-backed mode. Choose its migration
version and rollout/rollback rules during R1. Do not silently convert existing
inline snapshots or enable new retention against incompatible writers.

Incompatible readers, writers **and retention workers** must not reach data whose
scope enables reference-backed publication. Drain the affected deployment before
the first such writer starts unless an alternative rollout proves isolation by
realm, namespace and schema/access permissions. Request routing alone does not
isolate an old retention sweep. Inline/empty views do not automatically become
reference-backed, but that does not justify leaving an incompatible cleanup worker
with access to their shared scope. Rollback requires compatible readers and
retention logic, or an explicit migration back to inline data before reintroducing
old binaries; pointing an old deployment at reference data is not a rollback plan.

## 6. Illustrative public interfaces

These sketches fix responsibility and outcome shapes for R0 prototyping, not final
names or binary signatures. Auxiliary types are deliberately schematic and the
snippets have not been compiled. IDs/tickets/pins/claims are distinct checked
types; trusted provider factories must be available to external implementations
without exposing raw credentials. `SnapshotBytes` denotes bounded immutable/owned
bytes; raw mutable arrays are not an ownership contract.
`PayloadRef` fixes the opaque payload ID, expected integrity digest and byte length;
its metadata cannot be changed between reservation, upload, publication and read.

### Payload adapter

```scala
enum PayloadWrite:
  case Durable, Conflict, Uncertain, Unavailable

enum PayloadRead:
  case Found(bytes: SnapshotBytes)
  case Missing, Unavailable

enum CleanupBasis:
  case DurableRetirementFence, VerifiedQuiescence, Unproven

enum PayloadDelete:
  case FinalDeleted, Uncertain, Unavailable

trait SnapshotPayloadStore[F[_]]:
  def cleanupBasis: CleanupBasis
  def put(ref: PayloadRef, bytes: SnapshotBytes): F[PayloadWrite]
  def get(ref: PayloadRef): F[PayloadRead]
  def delete(ref: PayloadRef): F[PayloadDelete]
```

`put` is immutable: a duplicate may succeed only for the same bytes; conflicting
content must not overwrite the key. The binding checks bounded length/integrity
on reads. `delete` is invoked only by the maintenance path after a provider-issued
eligible claim, not by ordinary application handlers. An uncertain delete is
retried under the retained cleanup obligation.

`FinalDeleted` has exactly the finality meaning in section 5: durable absence
against pending, delayed and retried uploads, including callers paused before
submission. The adapter establishes its declared fencing or quiescence basis;
the coordinator records the result under the current garbage claim. `Unproven`
adapters cannot report `FinalDeleted`. They may implement ordinary deletion but
must return `Uncertain`, retaining charged obligations and admitting work only
within explicit backlog limits. This does not promise autonomous bounded-retention
service for arbitrary object-store CRUD.

Qualification must describe admission/retry control, restart behavior, fence
retention/reclamation and extra storage operations. The capability declaration
is a trusted SPI claim that tests must verify, not proof by itself. No caller-side
deadline or repeated-delete heuristic upgrades `Uncertain` into finality. The
framework creates no new upload after retirement and never reuses payload IDs.

### Provider publication capability

```scala
trait SnapshotReferenceSupport[F[_], P]:
  def bind(view: BrowserView[P]): Option[BoundSnapshotReferences[F]]
  def maintenance: SnapshotReferenceMaintenance[F]

trait BoundSnapshotReferences[F[_]]:
  def ownerEpoch: ViewOwnershipEpoch
  def acquireRead(): F[ReferenceLoad]
  def finishRead(pin: ReadPin): F[Either[ViewSnapshotError, Unit]]
  def releaseRead(pin: ReadPin): F[Unit]
  def reserve(
    operation: PublicationId,
    expected: ViewRevision,
    ref: PayloadRef,
    schema: SnapshotSchema,
    intent: PublicationIntent
  ): F[ReservationOutcome]
  def publish(ticket: PublicationTicket): F[PublicationOutcome]
  def status(operation: PublicationId): F[PublicationStatus]

enum PublicationStatus:
  case Prepared(ticket: PublicationTicket)
  case Published(revision: ViewRevision)
  case NotPublished, Unresolved

enum ClaimUpdate:
  case Applied, AlreadyApplied, StaleClaim

trait SnapshotReferenceMaintenance[F[_]]:
  def claimGarbage(limit: Int): F[Vector[GarbageClaim]]
  def completeDelete(claim: GarbageClaim): F[ClaimUpdate]
  def retryDelete(claim: GarbageClaim): F[ClaimUpdate]
```

The bound session captures the owner epoch from trusted provider state; operations
that grant presentation access or publish data check it again at their admission
boundary. Releasing the caller's own pin and reconciling the disposition of its
original publication must remain possible through restricted bookkeeping paths
after takeover/revocation. These paths return no payload or successor authority
and cannot admit a new publication. Provider maintenance must resolve outstanding
records even after the original bound session closes. `ReferenceLoad` must
distinguish empty, pinned reference, reset-required and rejected/storage outcomes.
`PublicationIntent` distinguishes ordinary save from permitted reset.
`ReservationOutcome` distinguishes a prepared ticket, rejection and uncertainty.
`PublicationOutcome` distinguishes acknowledged publication with its revision,
rejection and uncertainty. `PublicationStatus.Published` returns the original
committed revision so reconciliation can advance the session's expected revision.
It does not claim that revision is still current or grant successor authority;
later access and CAS still enforce those boundaries. Status must not infer
`NotPublished` from absence while an earlier operation may still commit.

`completeDelete` is called only after `FinalDeleted`; it must match the claim's
provider-issued identity and generation. `retryDelete` retains the obligation and
sets bounded retry eligibility. Stale claims change neither a successor claim
nor completion state. Claim errors/uncertainty leave durable work available for
reconciliation. Leases, batch and concurrency settings belong to the provider and
binding settings, with no raw credentials in claims.

The ticket fixes operation, reference, expected revision, captured owner and
identity/schema bindings. It is not domain execution authority. `publish` cannot
substitute another reference or adopt the new owner's identity. No external
payload-store I/O occurs inside a coordinator gate turn or provider transaction.

This is a separate optional capability, not a new mandatory method on
`BrowserPersistence`. The JDBC implementation keeps atomicity with its view row;
other full authority providers can implement equivalent semantics without JDBC.
Only the payload adapter is small and independent of authentication machinery.

### Conformance fixture

```scala
enum RecoveryFault:
  case AfterReservationBeforeReply, BeforePayloadDispatch
  case BeforePayloadDurability, AfterPayloadDurabilityBeforePublish
  case AfterPublishBeforeReply, BeforeRetirement, AfterRetirementBeforeDelete
  case AfterGarbageClaimBeforeDelete, AfterDeleteBeforeComplete

trait RecoveryGate[F[_]]:
  def reached: F[Unit]
  def resume(decision: FaultDecision): F[Unit]

trait RecoveryFixture[F[_], D]:
  type View
  def open(): F[View]
  def takeOver(previous: View): F[View]
  def revoke(view: View): F[Unit]
  def rotateIdentity(view: View): F[View]
  def advanceClock(by: FiniteDuration): F[Unit]
  def reclaimView(view: View): F[Unit]
  def store(view: View): ViewSnapshotStore[F, D]
  def gate(point: RecoveryFault): F[RecoveryGate[F]]
  def observe(view: View): F[RecoveryObservation]
  def close(): F[Unit]
```

Fault decisions distinguish normal progress, a failure before commit and a lost
reply after an actual commit; each fixture must implement only meaningful choices
for its gate. Observations expose test-safe revisions, operation status, references,
pins and cleanup counts, not production credentials or payload-bearing diagnostics.
The fixture must also control delayed uploads/deletes for retention races.

R0 compiles the sketches into executable contracts and separate packages for the
generic API, reference fixtures and an independently authored payload test adapter.
That external adapter must not import JDBC or private Spoonbill internals.

Use an explicitly extended in-memory reference implementation or test model,
based on `InMemoryBrowserPersistence`, as the first deterministic law host. Add
modeled reference, pin, operation and garbage-claim records with controlled fault
gates. Its existing `durable = false` and `ExclusiveScope` declarations do not
establish shared-view output admission, crash durability or two-process recovery.
Do not relabel that production capability merely to make a test pass. Keep real
PostgreSQL tests, with controlled gates too, and the two-JVM browser layer.

### Published conformance artifact

R2 delivers a new **`spoonbill-recovery-testkit`** artifact from
`modules/recovery-testkit`, under `com.natural-transformation`. It is an ordinary
published library: the public payload laws, `RecoveryFixture` contracts and runner
support live in its main sources. Adapter projects consume it in test scope. This
module and coordinate are implemented in the working tree, but are not available
in Spoonbill 9.0.0; public release evidence is still pending.

The kit depends on public recovery APIs, not JDBC/private implementation classes
or the unrelated UI testkit. If a runner exposes ScalaTest types, its required
framework dependencies must be available to the published main classes; placing
them only in the kit's own `Test` configuration is insufficient.

Before release, demonstrate an independent adapter project resolving a locally
published candidate of the kit, compiling and running its laws without copying
`misc` sources. R2's delivery gate includes the ordinary released artifact and
documented matching-version coordinates; publication remains an explicitly
authorized release action. The independent consumer now supplies the local
packaging check. Passing it after `publishLocal` does not prove that a released
kit resolves from a public repository without locally published artifacts.

## 7. Recovery behavior and application boundaries

Recover a committed, permitted projection over fresh domain data and permissions.
Preserve pending drafts through loading; stale user/resource responses cannot
consume them. User navigation explicitly abandons pending restoration. Unsupported
schemas and malformed data fail without erasing the recoverable record.

Store success, runtime output admission, browser processing and human receipt are
different boundaries. Store conformance checks the declared durability/publication
point. Runtime/browser tests establish when the UI may show a saved acknowledgement.
The current JDBC contract waits for snapshot commit and output admission; retain
that behavior. No business-effect replay, durable timer scheduling or media-session
recovery is implied.

`provider.transact(view)` can protect compatible domain work performed in its
supplied transaction. Business mutations in another store still need receiver-side
authority/fencing and their own transaction/deduplication protocol. The new payload
SPI neither supplies cross-store business atomicity nor removes that application
responsibility. Its automatic plumbing covers presentation snapshots only.

## 8. Implementation sequence and ownership

Current status: R0/R1 implemented in PR #25; R2 implementation and local
packaging complete, public release proof pending; R3 example implemented in
PR #25 and measurement harness/results in PR #26. The exit conditions below
remain the qualification contract, including adapter-specific production proof.

| Stage | Deliverable | Exit condition and owner |
| --- | --- | --- |
| R0 | Executable interfaces and law suite on an extended in-memory model; retained PairTime-shaped projection cases. | Framework maintainers demonstrate controlled takeover, operation-status, read-pin, garbage-claim and late-admission races. The model is not durability/shared-deployment evidence. |
| R1 | Generic binding with snapshots/maintenance/close; optional publication capability; JDBC metadata, retention and migration. | Preserve inline/default APIs; pass real PostgreSQL conformance and the healthy commit operation budget; define worker-claim and rollout isolation behavior. The coordinator's existing `snapshots` parameter stays unchanged. |
| R2 | Published `spoonbill-recovery-testkit`, independently authored non-JDBC payload test adapter, qualification guide. | An independent project resolves the released kit as a test dependency and runs laws without copied sources, JDBC or private APIs. A locally published candidate validates packaging before the authorized release. |
| R3 | Browser-auth example runtime acceptance plus authenticated snapshot latency comparisons. | Extend the two-JVM fixture to exercise reference publication with a shared payload store, and add inline/reference benchmark modes with operation counts and latency distributions. Browser acknowledgement, handoff, cleanup and outage behavior are demonstrated. |
| Follow-on | Optional PairTime adoption and optional schema-helper changes. | PairTime maintainers own migration and consumer rollout; neither is silently required to finish R0–R3. |

The R3 shared payload test implementation can use PostgreSQL in the disposable
fixture. Ensure it exercises payload staging and reference publication as separate
operations, with fault gates, rather than bypassing the new protocol through the
old inline snapshot path. An in-process non-JDBC fake proves API substitution
and modeled laws only; it cannot supply two-JVM durability evidence.

PairTime adoption needs an explicit plan: upgrade its 7.0.0 integration across
the [8.0/V6 writer-drain and schema requirements](../clustered-browser-authentication.md#upgrading-existing-deployments-to-800),
then align modules and recompile for the [9.0 configuration change](../../CHANGELOG.md).
Schema encoding changes additionally need the rollout in section 3. These are
consumer-maintainer responsibilities, not actions authorized by this documentation.

No stage commits to a named production database adapter. Replacing the authority
provider remains possible through generic SPIs but is not demonstrated by the
non-JDBC payload test. Preserve PostgreSQL's full optional module and current APIs.

## 9. Evidence layers and acceptance

Use [BrowserPersistenceConformance](../../misc/browser-auth-conformance/BrowserPersistenceConformance.scala)
as a fixture pattern, not as an existing snapshot suite. Its ceremony/delivery
cases do not establish recovery. [JdbcViewSnapshotsSpec](../../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcViewSnapshotsSpec.scala)
supplies race/schema cases but currently uses JDBC-specific low-level fixtures.
[ViewSnapshotSessionSpec](../../modules/spoonbill/src/test/scala/korolev/snapshot/ViewSnapshotSessionSpec.scala)
supplies projection laws. R0 added the fixture operations described in section 6
to make these cases portable through `spoonbill-recovery-testkit`.

| Scenario | Required observation | Evidence owner/layer |
| --- | --- | --- |
| Payload save acknowledgement | Success only after the adapter's declared durability point; immutable conflicts reject. | Payload suite; adapter author proves its actual datastore profile. |
| Takeover/revision conflict | Old owner cannot publish; new revision is not overwritten; old cleanup cannot retire successor data. | Coordinated law suite and real provider integration. |
| Save on A, terminate A, reconnect to B | B restores committed presentation over fresh authorized state; saved UI acknowledgement follows publication/output admission. | R3 two-JVM/runtime/browser fixture. |
| Revoke during payload fetch | Finish/adoption/output reject stale authority even if bytes were fetched successfully. | Provider and runtime/browser tests. |
| Retired owner performs bookkeeping | Its own pin can be released and original operation status reconciled; no successor payload or new publication is authorized. | Coordinated law suite and provider maintenance tests. |
| Upload/publish reply lost | Reconcile the original operation; no unsafe retry, stale overwrite or domain replay. | Typed fault-gate suite plus actual provider settlement tests. |
| Reservation reply lost | Recover the original ticket/status before upload; no new reservation is created blindly. | `AfterReservationBeforeReply` gate and provider status tests. |
| Publication reply lost | Reconciliation returns the committed revision; subsequent commits use the right expected revision while current ownership is still checked. | Status and runtime integration tests. |
| Provider reclaims a view with external payload | Durable retirement survives process failure; cleanup retries; reference cannot be republished. | Reference/GC integration with payload adapter. |
| GC races upload, publish or read | Pins/reservations protect eligible work; delayed writes are settled or dominated before cleanup completes. | Deterministic races and adapter-specific late-write/deletion tests. |
| Delete overtakes a paused/delayed upload | Only a proven retirement fence or quiescence permits `FinalDeleted`; advancing a deadline and repeating deletes alone must not complete cleanup. | Payload adapter qualification and `BeforePayloadDispatch` gate. |
| Delete succeeds but completion reply/work is lost | Durable obligation permits idempotent re-delete/reconciliation; no live payload is reclaimed or cleanup intent lost. | `AfterDeleteBeforeComplete` gate. |
| Garbage claim expires while delete is running | Another worker may reclaim metadata; old completion cannot clear its claim; per-binding work/retry limits hold and unresolved attempts remain charged before new retry admission. No physical cluster-wide bound is inferred. | Modeled and real provider lease races. |
| Retirement marker reclamation | Retain the fence and its accounting until admission is irrevocably closed and all admitted writes settle; non-reuse or a time window alone is insufficient. | Adapter-specific retirement/retention qualification. |
| Datastore restart/failover/partition | Preserve the advertised authority/durability model, or reject the configuration. | Each real provider/adapter author; not proved by the generic fake. |
| Schema change, corrupt/missing payload | Explicit failure or compatible reader migration; no silent reset/data loss. | Format and store suite; overlapping-version browser fixture. |
| Async loading/navigation | Pending draft survives loading; wrong user/resource responses are ignored; explicit abandonment wins. | Runtime/consumer fixture. |
| Capacity and outages | Bound admitted work and diagnostics; retain safety metadata, apply backpressure and report stuck cleanup. | Protocol and actual deployment qualification. |
| Maintenance under slow payload deletion | Deletes execute outside coordinator turns/transactions; unrelated clicks progress subject to ordinary executor/storage limits. | Runtime concurrency and latency tests. |
| Mixed-version rollout | Incompatible readers, writers and retention workers cannot access a reference-enabled scope; inline mode remains unaffected where properly isolated. | Deployment/migration fixture. |

Existing two-process baseline command, from this repository:

```bash
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh
```

It builds the public example, starts two JVMs and disposable PostgreSQL when no
test database is supplied, and uses pinned Chromium/WebKit. Its default `all`
selection now includes the `reference-payloads` scenario added in PR #25; this
exercises the reference-backed path and its faults alongside the inline baseline.

### Performance acceptance

The [click latency harness](../../misc/event-latency/README.md) now includes authenticated
`jdbc-snapshot` and `jdbc-reference` modes, added in PR #26. Their measurements
and operation budgets are recorded in the
[latency report](../event-latency-performance.md#authenticated-views-with-durable-snapshots-inline-and-reference-backed).
The older guarded modes use anonymous views and omit snapshot configuration;
their 243-to-47 round-trip comparison remains authority/guard evidence, not a
snapshot-storage baseline.

Use the same projection, payload sizes, authority/ownership settings, connection
pools, concurrency and maintenance schedule in both modes. Declare store latency
and failure-injection settings. Assert the section 5 success-path operation budget
deterministically, then record provider transactions, SQL/network round trips,
payload operations/bytes and click p50/p95/p99. Report initial empty-view publication,
restoration, conflicts/reconciliation and cleanup separately from steady-state saves.

The current harness measures click-to-patch/ready time, not browser DOM application
or paint. Keep native browser acknowledgement checks as a separate evidence layer.
The R3 report records the measured inline/external difference and slow-store impact;
it does not establish a generic millisecond SLO beyond its declared workload/deployment.
Inline storage remains the default; do not conceal external-store latency by
weakening acknowledgement or durability.

Use disposable stores and the repository Nix environment. New fixture/test names
and exact commands must be recorded when implemented. A fake, a real database
adapter and a browser harness each establish different properties; do not combine
their claims into an untested end-to-end guarantee.

## 10. Completion and renderer boundary

This work belongs in Spoonbill. Avocet must not acquire storage clients,
authentication policy or distributed coordination; Avocet saved render buffers
are not portable presentation snapshots. M3/M4 renderer features are not prerequisites.

Completion requires the payload SPI, optional provider-neutral publication
capability, working JDBC reference, durable retention/maintenance protocol,
external-adapter example, published conformance kit and R3 runtime/performance
evidence. Keep existing PostgreSQL consumers
working without a forced external-storage migration.

The review is historical input, not an implementation result. The original
design revision chose the initial extension shape without claiming it was the
only possible distributed design; that document revision itself added no code,
migration or runtime evidence. The implementation status above and the
[remaining-work checklist](../remaining-work-after-portable-recovery.md) record
subsequent delivery and validation. PairTime adoption and application deployment
remain separate work.
