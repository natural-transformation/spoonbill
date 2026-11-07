# Review: Portable UI recovery plan

Updated: 2026-11-07, after reviewing revision 2 of the plan and after the plan
author's response to that review. The response accepted findings 1, 3, 4 and 5
with extensions and qualified findings 2, 6 and 7; each finding below is in its
current form. The original review of the 2026-11-06 draft follows as a record;
its line references point at that draft.

Reviewed document: [Portable UI recovery](portable-ui-recovery-plan.md),
revision 2, together with the uncommitted README and
[shared PostgreSQL guide](clustered-browser-authentication.md) edits that link
to it.

Baselines: unchanged. Spoonbill 9.0.0 at
`3bb6d2148abeeb480397a7f354bc56ba0e97269d`; PairTime at
`f5f1493fbab56a3b70577964b270f80036856758`, pinned to Spoonbill 7.0.0.

## Assessment of revision 2

Revision 2 resolves all eleven findings of the first review. It commits to
immutable payload storage with provider-controlled reference publication, keeps
the full-provider SPI as the other extension path, refuses the two-store cache
tier as an implicit fallback, adds illustrative interfaces, moves R3 onto the
browser-auth example and makes the PairTime upgrade an owned follow-on. The
revised shipped-foundation table, the PairTime paths, the upgrade links and the
fixture command were checked against the code and the guide and are accurate.
The direction is sound and ready for R0 prototyping.

Reviewing the new protocol and sketches surfaces seven further findings. None
blocks the direction. Three are P2 and should be settled in the plan before R0
compiles the sketches, because they change interface shapes or exit conditions.
Four are P3 clarifications. The first remedy offered for finding 2, a deadline
with two deletes, is withdrawn: a timing bound is not a correctness guarantee,
and the finding now asks the plan to settle the cleanup contract on store
semantics.

### Resolution of the first review

| # | First-review finding | Revision 2 |
| --- | --- | --- |
| 1 | [P1] Choose the adapter shape | Resolved. Sections 1 and 2 select payload storage with provider-controlled publication, keep the full-provider SPI, and refuse the two-store tier as an implicit fallback. |
| 2 | [P1] Add an interface sketch | Resolved. Sections 4 and 6 sketch the composition helper, payload adapter, provider capability and fixture. The `snapshots` parameter shape is kept. Adapters see no `BrowserView`, raw binding or subject. |
| 3 | [P1] R3 cannot be met as written | Resolved. R3 runs on the browser-auth example with a payload store both JVMs can reach. PairTime adoption is an owned follow-on with the 8.0 and 9.0 upgrade steps named. |
| 4 | [P2] R0 extracts from a JDBC-shaped spec | Resolved. Section 6 defines `RecoveryFixture` with take-over, revoke, rotate, clock, reclaim and fault gates. Storage and UI acknowledgement are tested separately. |
| 5 | [P2] Garbage collection needs a liveness source | Resolved. Section 5 makes the provider the liveness source with durable cleanup obligations and a maintenance claim API. Finding 2 below concerns how delete finality is settled. |
| 6 | [P2] Scope the "no plumbing" promise | Resolved. Sections 1 and 7 limit the promise to presentation recovery. |
| 7 | [P2] Sealed-ADT helper changes the wire format | Resolved. Section 3 requires a version bump, retained decoder and registered conversion when the encoding changes. |
| 8 | Editorial | Resolved. The README now describes the proposal, the Eezo sentence is gone, PairTime paths are plain, section 4 separates the adapter from what the application supplies, and the scenario table has an evidence-owner column. |

### 1. [P2] Budget the per-commit cost of the reference-backed path

Reference: [Publication](portable-ui-recovery-plan.md#publication), lines
152–163; [Implementation sequence and ownership](portable-ui-recovery-plan.md#8-implementation-sequence-and-ownership),
R1 and R3.

The snapshot session commits on every guarded state transition.
[SessionsService](../modules/spoonbill/src/main/scala/korolev/server/internal/services/SessionsService.scala)
line 309 hands the guard's snapshot session to the state manager, and the inline
JDBC path spends one provider transaction per commit. The reference-backed path
spends at least two provider transactions, reserve and publish, plus one payload
write. The plan forbids payload I/O while the publication transaction is open, so
they cannot be merged. Every click that changes state pays that cost, and a slow
payload store sits directly on the click path of its view. A fresh view pays it
too, because `ViewSnapshotSession.projected` publishes the initial projection
when the load is empty.

This repository just spent a release cutting guarded round trips from 243 to 47
per click, so the plan needs a stated budget. Count the successful snapshot
path's provider transactions and payload operations per commit and bound them in
R1. The existing [click latency harness](../misc/event-latency/README.md) cannot
supply the comparison as it stands: its guarded modes use anonymous views, and
anonymous views have no snapshot session, so it never commits a snapshot and its
current results are not snapshot-performance evidence. R3 should add an
authenticated inline-snapshot mode and an external-payload mode to the harness
and compare the two. State the intended use: large or externally governed
payloads, with inline storage remaining the default for small drafts.

### 2. [P2] Settle the cleanup contract; sections 5 and 6 disagree on delete finality

Reference: [Retirement and garbage collection](portable-ui-recovery-plan.md#retirement-and-garbage-collection),
lines 192–197; [Payload adapter](portable-ui-recovery-plan.md#payload-adapter),
lines 239–249.

Section 5 offers two ways to handle an upload that completes after a timeout.
Either the adapter's delete dominates late writes, or the provider keeps the
cleanup obligation until the writes settle and a final delete completes. Section
6 then makes the first the only option: `Deleted` must mean final absence against
every already-admitted upload, the adapter must fence such writes internally,
and it cannot delegate deletion finality back to the caller. A plain object store
or key-value store cannot promise that. It cannot see a client-abandoned write
from another process, and a late write after a delete resurrects the key. Under
section 6 as written such an adapter must answer `Uncertain` forever, cleanup
never completes, and the small payload adapter that section 1 promises does not
exist.

The remedy is not a timing rule. The first version of this finding proposed a
reservation deadline, an adapter-declared late-write bound and a second delete
after a quarantine. That is withdrawn. A process can check its deadline, pause,
and send the upload after both deletes; queued requests and client retries can
do the same; abandoning the caller does not revoke the write. A bounded-delay
argument is a correctness argument only when the bound provably covers all
delayed admission, execution and retries, and no plain object store documents
such a bound.

Settle the contract on store semantics instead:

- An adapter declares whether its store fences retirement: a write to a retired
  reference fails at the store, for example a tombstone written by delete
  together with a conditional put that refuses tombstoned keys. Only a fencing
  adapter may report `Deleted` as final.
- Proven write quiescence is the other acceptable basis. An adapter whose store
  can confirm that no write for the reference can still be admitted may delete
  without a tombstone.
- An adapter with neither reports `Uncertain`, and the provider keeps the
  obligation open. Open obligations are bounded and visible, backpressure
  applies at the bound, and cleanup is never declared complete on elapsed time.
  An operator reconciliation path may delete store objects that the provider has
  durably marked retired, because publish and retire serialize.

A deadline may still schedule the first delete attempt as a cost heuristic; it
never establishes the outcome. Where a fencing marker must itself be removed one
day, a late write after its removal creates an unreferenced orphan, not a live
payload, because references are never reused. The plan should name that
residual as a storage and retention concern, bounded and reported, so it is not
mistaken for a correctness exception.

### 3. [P2] The conformance kit must be a published artifact

Reference: [Conformance fixture](portable-ui-recovery-plan.md#conformance-fixture),
lines 335–337; [Implementation sequence and ownership](portable-ui-recovery-plan.md#8-implementation-sequence-and-ownership),
R2.

The R2 exit condition is an externally authored adapter that passes the payload
laws without private dependencies. Today's conformance sources are not an
artifact. [build.sbt](../build.sbt) lines 258 and 292 add
`misc/browser-auth-conformance` to two test projects as unmanaged source
directories, and nothing publishes them. An external author cannot depend on
that. Name the published module that carries the payload laws and the
`RecoveryFixture` contract, an ordinary published library consumed in test
scope, for example beside `spoonbill-testkit`, and make its publication part of
the R2 exit condition. Section 16 of the
[redesign plan](spoonbill-api-redesign-plan.md) is the precedent for stating
module disposition.

### 4. [P3] Name the maintenance entry point and keep payload I/O out of gate turns

Reference: [Retirement and garbage collection](portable-ui-recovery-plan.md#retirement-and-garbage-collection),
lines 185–188; [Provider publication capability](portable-ui-recovery-plan.md#provider-publication-capability),
`SnapshotReferenceMaintenance`.

The plan says maintenance claims bounded batches of deletion work but not who
runs it. Today
[BrowserAuthentication](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala)
line 634 runs provider maintenance inside a coordinator turn, and 8.1.0 split
that sweep into short turns so clicks stop waiting on it. A payload delete is
network I/O to another store and must not run inside a gate turn or a provider
transaction. State that the composition helper exposes its own maintenance
operation, scheduled by the application beside `browser.maintenance()`, which
runs adapter deletes outside turns. Add that scheduling obligation to section
4's list of what the application supplies. Define the claim lease: whether two
nodes may hold claims at once, what happens when a lease expires and another
node re-claims the work, and how a completion from the first node is treated
after that. Idempotent delete makes a double delete safe, but the metadata must
not record completion on a stale claim, and retries must stay bounded and
visible.

### 5. [P3] Close the gaps in fault points and status outcomes

Reference: [Conformance fixture](portable-ui-recovery-plan.md#conformance-fixture),
`RecoveryFault`; [Provider publication capability](portable-ui-recovery-plan.md#provider-publication-capability),
lines 288–292.

`RecoveryFault` has no point between a committed reservation and its lost
reply, yet publication step 1 requires resolving an uncertain reservation before
uploading. It also has no point between the adapter's delete and the provider's
`completeDelete`, which is the crash that makes idempotent re-delete necessary.
Add both. `PublicationStatus.Published` should carry the committed revision, as
`PublicationOutcome` does, so a binding that reconciles a lost publish reply can
advance the session's expected revision instead of failing closed on its next
commit.

### 6. [P3] Use the in-memory provider as the deterministic model, not as evidence

Reference: [Implementation sequence and ownership](portable-ui-recovery-plan.md#8-implementation-sequence-and-ownership),
R0.

R0 wants a deterministic suite that detects take-over, revision,
uncertain-publication, read-pin and GC races. Deterministic races need
controlled gates, which real PostgreSQL does not give by itself.
[InMemoryBrowserPersistence](../modules/spoonbill/src/main/scala/korolev/auth/InMemoryBrowserPersistence.scala)
already tracks per-view owner and epoch at line 119, advances the epoch on
re-claim at line 444 and fences stale views at lines 306 and 457, so it, or an
explicitly extended test model, can implement the reference capability and host
`RecoveryFixture` for the protocol laws. It declares `durable = false` and
`ExclusiveScope` at lines 87–88, so it models the protocol and proves nothing
about durability, shared-view output admission or two-process behavior. Keep
real PostgreSQL and the two-JVM fixture as the evidence layers, with controlled
gates in both the modeled and the database tests. Name the modeled host in R0 so
the first implementation of the capability is not the hardest one.

### 7. [P3] State the isolation rule for reference-enabled data and the confidentiality decision

Reference: [Retirement and garbage collection](portable-ui-recovery-plan.md#retirement-and-garbage-collection),
lines 204–207; [Application-facing contract and data exposure](portable-ui-recovery-plan.md#4-application-facing-contract-and-data-exposure),
lines 144–146.

Enabling reference-backed mode changes what a published revision contains. A
pre-R1 node reads payloads inline, so it fails closed on a view whose current
revision is reference-backed; views with inline or no snapshots are unaffected.
A pre-R1 retention worker is the larger risk:
[JdbcBrowserRetention](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserRetention.scala)
line 173 reclaims snapshot rows through the view rows it retires and knows
nothing about reference metadata or cleanup obligations, so it can orphan
external payloads without recording any obligation. State the rule:
incompatible readers, writers and cleanup workers must not reach
reference-enabled data. Draining the affected deployment before the first
reference-backed writer starts is the simple default, as the V5 and V6 upgrades
require; a scope isolated by realm, namespace and schema may permit another
rollout, and the plan should say what isolation that needs. Separately, moving
payloads from the trusted database to an external store widens the trust
boundary for draft text. Decide whether the binding offers envelope encryption
with a key held in the provider's trust domain, or state that payload
confidentiality is the deployment's responsibility. The current text implies
the second; say it.

### Review scope and verification for revision 2

Read-only comparison of revision 2, the README and guide edits, the build
definition, the in-memory and JDBC providers, the retention sweep, the session
service, the latency harness and the conformance sources, followed by the plan
author's response of 2026-11-07. No build or test was run; the plan states that
its sketches are uncompiled. No implementation file or the plan was changed.

## Original review of the 2026-11-06 draft

The sections below are unchanged from 2026-11-06. Their line references point at
that draft and no longer match revision 2, and revision 2 renamed most sections,
so those links open the plan without an anchor. The resolution table above maps each
finding to its outcome.

Date: 2026-11-06.

Reviewed document: [Portable UI recovery](portable-ui-recovery-plan.md), as
updated 2026-11-06, together with the uncommitted README and
[shared PostgreSQL guide](clustered-browser-authentication.md) edits that link
to it.

Baselines: Spoonbill 9.0.0 at `3bb6d2148abeeb480397a7f354bc56ba0e97269d`;
PairTime at `f5f1493fbab56a3b70577964b270f80036856758`, whose `nt-ui/build.sbt`
pins `SpoonbillVersion = "7.0.0"`.

### Assessment

The plan is well grounded. Its inventory of shipped seams, its reading of
PairTime and its acceptance scenarios match the code at both baselines. The gaps
are in the middle of the plan: it never decides what shape a custom adapter has,
so the stages and exit conditions cannot yet be checked against anything
concrete. Findings 1 to 3 block implementation planning. Findings 4 to 7 should
be resolved before R0 starts. Section 8 is editorial.

### 1. [P1] Choose the adapter shape; the two headline goals conflict

Reference: [Shipped foundation and current gaps](portable-ui-recovery-plan.md),
lines 47–49; [Storage and coordination boundaries](portable-ui-recovery-plan.md),
lines 133–134 and 147–157; [Implementation sequence](portable-ui-recovery-plan.md),
R2 exit condition at line 232.

Section 2 says users must not implement the entire authentication provider
merely to save drafts. Sections 5, 6 and 9 require custom adapters to deliver
the full shared-owner fence: atomic owner-epoch and revision comparison, stale
writers rejected after takeover, and old cleanup unable to delete a successor.
In the current code those two things are the same component.

- Every JDBC snapshot operation runs inside the provider's own transaction.
  [JdbcBrowserSnapshots](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSnapshots.scala)
  line 49 wraps load, save and reset in `provider.transact(view)`, which
  re-captures authority against the view's owner and epoch.
- The write fences on the provider's view row.
  [JdbcViewSnapshots](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcViewSnapshots.scala)
  lines 143–155 update `spoonbill_browser_view` only where owner, epoch and the
  expected snapshot revision still match.
- Takeover advances the epoch in that same row.
  [JdbcBrowserSessions](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSessions.scala)
  lines 606–620 claim a view by writing `epoch + 1`.
- The only generic seam is the factory the coordinator calls per claimed view at
  [BrowserAuthentication](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala)
  line 349. It is a pure constructor. The session it returns exposes
  `ownerEpoch`, `initialize` and `commit` only; there is no attach, takeover,
  release or retire callback.

A store that is not the provider can therefore compare revisions, but it can
never learn that its owner lost the view. That leaves three coherent shapes,
and the plan should name them:

1. A payload store behind a reference published in the provider's
   authoritative record. This is the publication protocol that section 5 marks
   "investigate". It is the only shape that satisfies both headline goals,
   because the provider keeps the owner fence and the adapter stores bytes.
2. A full provider through the existing
   [BrowserPersistence](../modules/spoonbill/src/main/scala/korolev/auth/BrowserPersistence.scala)
   SPI at line 134: `claim`, `authorize`, `release`, `admitOutput`,
   `registerView` and the ceremony operations. This exists today and is the
   thing section 2 says users should not need.
3. A snapshot-only adapter that keeps its own owner-epoch record and is told
   about claims through new coordinator hooks. Provider takeover and adapter
   attachment are then two writes in two stores, which section 5 already
   classifies as not atomic. By the plan's own rule in section 6 this is the
   weaker draft-cache tier.

Recommendation: commit to shape 1 as the R1 and R2 deliverable, state that
shape 2 is available today and out of scope, and keep shape 3 as the explicitly
weaker contract under its own name. Then rewrite the R2 exit condition. In
shape 1 the authority stays in the JDBC provider; only the payload adapter is
JDBC-free, and "without JDBC dependencies" should say that.

### 2. [P1] Add an interface sketch

Reference: [Public extension interface and adapter contract](portable-ui-recovery-plan.md),
lines 173–190.

Section 6 lists nouns: identity binding, owner epoch, expected revision,
projection format, outcomes and lifecycle. The
[API redesign plan](spoonbill-api-redesign-plan.md) carried Scala shapes marked
illustrative, and its review could check them. Without signatures, R1 and R2
cannot be reviewed and the R2 exit condition cannot be tested.

Add three signatures, marked illustrative and non-binding:

- the adapter trait that shape 1 implements, with its outcome type;
- the provider operation that publishes a payload reference under an expected
  revision and owner epoch, and where it lives: a new method on
  `BrowserPersistence` or a new optional trait;
- the conformance fixture trait from finding 4.

The sketch should also settle what an adapter is allowed to see.
[BrowserView](../modules/spoonbill/src/main/scala/korolev/auth/BrowserPersistence.scala)
at line 46 already exposes `qsid`, `ownerId`, `epoch`, the session id and the
slot generation, so a `SnapshotIdentity` can be built from public data today.
The JDBC binding digests the subject and the browser binding before anything is
stored. The generic binding must do the same, so that adapters never receive raw
cookie material or identifiable subject text. State whether the `snapshots`
constructor parameter of `BrowserAuthentication` keeps its current shape, since
9.0.0 already broke binary compatibility of another public constructor.

### 3. [P1] R3 cannot be met as written

Reference: [Implementation sequence](portable-ui-recovery-plan.md),
R3 at line 233; [Boundaries and completion](portable-ui-recovery-plan.md),
lines 284–286.

The R3 exit condition wants two things from one stage: substitution with a
non-JDBC test implementation, and two-node and failure cases that retain their
guarantees. An in-process fake such as the store in
[ViewSnapshotSessionSpec](../modules/spoonbill/src/test/scala/korolev/snapshot/ViewSnapshotSessionSpec.scala)
at line 19 cannot be shared by two JVMs. A two-node handoff therefore needs a
test store both JVMs can reach, or R3 must say that the non-JDBC implementation
proves the API shape only and the two-node evidence comes from the JDBC
reference.

R3 also depends on a consumer migration that the last paragraph of the plan
excludes. PairTime pins Spoonbill 7.0.0. Before it can take the release that
carries R1 it must cross the 8.0.0 V6 schema migration and writer drain in the
[8.0 upgrade section](clustered-browser-authentication.md#upgrading-existing-deployments-to-800)
and the 9.0.0 configuration break in the [changelog](../CHANGELOG.md). Either
list that upgrade as an R3 prerequisite with an owner, or run R3 against the
browser-auth example, whose
[JDBC accounts](../examples/browser-auth/src/main/scala/example/browserauth/JdbcAccounts.scala)
wiring at line 125 already uses `projectedSnapshots` and already has the
two-process fixture, and make the PairTime run a follow-on.

### 4. [P2] R0 extracts conformance from a JDBC-shaped spec

Reference: [Implementation sequence](portable-ui-recovery-plan.md),
R0 at line 230; [Conformance and acceptance](portable-ui-recovery-plan.md),
lines 244–249.

[JdbcViewSnapshotsSpec](../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcViewSnapshotsSpec.scala)
at line 100 drives the low-level `sessions.snapshots` path with a binding hash, a
token hash, a `HostSnapshotIdentity` hook and raw connections. The
coordinator-level `projectedSnapshots` path is exercised only by the two-process
browser fixture. The existing
[BrowserPersistenceConformance](../misc/browser-auth-conformance/BrowserPersistenceConformance.scala)
suite contains eight cases, all about ceremonies, delivery and logout, and none
about snapshots; the plan is right that it does not establish the snapshot cases.

A store-neutral suite needs a fixture contract, like the `Fixture` trait at
line 23 of that file, with operations the `ViewSnapshotStore` interface does not
have: take over the view under a new epoch, revoke or rotate the identity,
advance the clock, and inject a timeout between payload write and publication.
Name that fixture as an R0 deliverable; without it the R0 exit condition is not
testable. Also define "acknowledgement failures" in store terms, for example
that `save` must not return success before the store's declared durability
point, because UI acknowledgement is a renderer property that a store suite
cannot observe.

### 5. [P2] Garbage collection needs a liveness source

Reference: [Storage and coordination boundaries](portable-ui-recovery-plan.md),
step 4 at lines 156–157.

[JdbcBrowserRetention](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserRetention.scala)
at line 173 reclaims `spoonbill_view_snapshot` rows by joining the
`spoonbill_browser_view` rows it retires. In shape 1 the provider would reclaim
the reference and orphan the payload in the other store. "Never delete a
referenced revision" is then unachievable unless the payload store can ask
which references are live. A provider-side enumeration of live references, or a
retention hook invoked when a view is reclaimed, is part of the public contract,
not an adapter detail. Add it to step 4 and to the R2 kit, and add a scenario
row for "provider reclaims a view whose payload lives elsewhere".

### 6. [P2] Scope the "no plumbing in handlers" promise

Reference: [Outcome and product direction](portable-ui-recovery-plan.md),
lines 20–21; [Storage and coordination boundaries](portable-ui-recovery-plan.md),
lines 163–169.

Section 1 promises that application handlers carry no database-specific
ownership or distributed-lock plumbing. Section 5 then correctly says that
protected business data in another store must be fenced at its own mutation
boundary, which is exactly that plumbing. Both are true, for different data.
State that the promise covers presentation snapshots, that
`provider.transact(view)` covers domain data in the provider's database, and
that cross-store domain fencing stays with the application and is out of scope.

### 7. [P2] The sealed-ADT helper changes PairTime's wire format

Reference: [Lessons from PairTime](portable-ui-recovery-plan.md),
lines 82–86; [Implementation sequence](portable-ui-recovery-plan.md),
lines 239–240.

PairTime's `BrowserPresentationSnapshot` encodes its sealed `BrowserDraft` as a
record of optional variants and rejects invalid combinations, as the plan says.
Replacing that with a derived sum encoding is a schema change for
`pairtime.browser-draft`. It needs a version bump, a reader registered with
`format.reading(previous)(migrate)`, and the expand-then-switch window the
[guide](clustered-browser-authentication.md#what-is-durable) requires for
overlapping versions. Say so where the helper is scheduled, or it reads as free.

### 8. Editorial

- **README.** The added paragraph about strengthening the model rather than
  pursuing feature parity with another framework is internal rationale. Keep
  the link line; move the paragraph into the plan. The Eezo sentence in
  section 1, lines 28–30, names no concrete idea; list what is borrowed or cut
  it.
- **Sibling links.** The links to `../../pairtime/...` at lines 61, 62, 68 and
  88 break on GitHub and in any checkout without PairTime beside Spoonbill. The
  README now links to this plan, so use plain paths with the commit hash the
  plan already pins.
- **Section 4.** Item 4, lines 101–102, lists a recovery implementation
  "supplied by the framework" under what the application supplies. Give it its
  own line.
- **Scenario table.** The datastore restart/failover/partition row at line 257
  is the adapter author's obligation, as the R3 exit condition says. Mark it so
  in the table, since the R2 kit cannot ship it for an unknown store.

### What checks out

- Both baseline hashes match the checkouts, and `nt-ui/build.sbt` pins 7.0.0.
- The section 2 table matches the code. `ViewSnapshotStore` has `load`,
  revision-checked `save`, explicit `reset` and `ownerEpoch`.
  `ViewSnapshotSession.projected` overlays restored presentation onto fresh
  state. `BrowserAuthentication` takes the backend-independent `snapshots`
  factory. The binding behind `projectedSnapshots` is private to the JDBC module
  and runs through the provider's transaction. `StateStorage.fromRepository` is
  callback storage without authority checks or revision comparison.
- The PairTime description in section 3 matches `BrowserPresentationSnapshot`:
  the record-of-optionals codec, `pendingDraft`, `overlayTransition` and
  `cancelPending`, and the durability limits stated in ADR-0004.
- Avocet milestones M3 and M4 exist and are deferred, so the "does not depend
  on" statement in section 10 is consistent.

### Review scope and verification

This was a read-only comparison of the plan, the uncommitted README and guide
edits, the Spoonbill 9.0.0 sources and tests, the example application, the
conformance suites, and the PairTime consumer sources and ADR at the stated
revisions. No build or test was run because the plan proposes no code. No
implementation file or the plan was changed. PairTime paths in this review are
given as plain names under the pinned commit, not as links, because that
checkout is not part of this repository.
