# Portable recovery release and rollout

This is the release-order checklist for Spoonbill 10.0.x; the current patch is 10.0.1.
The rehearsal target is Spoonbill's loopback browser-auth example with disposable
PostgreSQL. Local tests, `publishLocal` and two-JVM handoffs are not a release or
an application deployment. See the [local evidence and rerun commands](remaining-work-after-portable-recovery.md)
for completed checks; PairTime and other applications require their own plan.

## Release gate

Release **10.0.1** with matching versions for all Spoonbill modules, including
`spoonbill-recovery-testkit`. Portable recovery is absent from 9.0.0, and Avocet
2.0.0 changes rendering lifecycle/name validation and invalidates Avocet 1's
unversioned development render buffers. The 10.0.0 major release communicated that
consumers must recompile and review custom rendering integrations. See the
[10.0 migration guide](migration-v10.md) and [Avocet upgrade notes](remaining-work-after-portable-recovery.md#avocet-upgrade-notes)
for the narrowly scoped development-cache migration; PostgreSQL presentation
snapshots use the application's separate codec.

[build.sbt](../build.sbt) sets `BaseVersion` to `10.0.1`; development builds,
both [recovery](../misc/recovery-adapter-consumer/consumer.sbt) and
[Pekko](../misc/pekko-native-consumer/consumer.sbt) consumer defaults, and
[Scala CI](../.github/workflows/scala.yml) artifact paths use `10.0.1-SNAPSHOT`.
Release CI supplies `RELEASE_VERSION=10.0.1` for stable artifacts. Future version
changes must keep these defaults and verification paths aligned.

Before publication, record the selected version, repository commit, module list,
consumer compatibility results and release owner. After authorized publication,
run the [Maven Central-only independent consumer check](../misc/recovery-adapter-consumer/README.md)
with that exact version and retain its resolved coordinates/repository and test
result. Local artifact resolution cannot satisfy that gate.

The [GitHub release](https://github.com/natural-transformation/spoonbill/releases/tag/v10.0.1)
records the immutable tag and publication/verification evidence. Publishing that
release triggers the existing workflow; do not also dispatch the same version
manually, since Maven Central versions are immutable.

The V7 cutover below was introduced by 10.0.0. An application already on 10.0.0
with V7 installed needs no additional schema migration for 10.0.1; see the
[patch upgrade notes](migration-v10.md#upgrading-from-1000-to-1001).

## Local migration rehearsal

The existing [JDBC schema-parity test](../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcBrowserPersistenceSpec.scala)
compares V4–V7 installation with runtime initialization. The
[snapshot-reference tests](../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcSnapshotReferencesSpec.scala)
cover inline-to-reference publication, stale inline writes, fail-closed inline
reads of reference revisions, and retention cleanup. The [browser fixture](clustered-browser-authentication.md#reproducible-correctness-fixture)
covers independent JVM handoffs in inline and reference modes. These checks use
the current implementation; they do not execute an old serving binary.

The focused `preserve a populated V6 inline snapshot through V7 and fence its
next inline revision` test creates V4–V6 state with an encoded inline snapshot,
applies the actual V7 SQL transaction, compares every legacy slot/view/snapshot
column, verifies the null pointer, and restores/commits through current storage
code with stale-revision rejection. Its execution result belongs in the release
evidence. This is a SQL migration and storage compatibility rehearsal, not an old
server binary upgrade; the separate snapshot-reference test establishes subsequent
inline-to-reference publication. Run dependency-backed checks only through the repository
`flake.nix`, using the [verification commands](remaining-work-after-portable-recovery.md#local-verification).

## Deployment inputs and approval

Before any actual rollout, the application owner supplies:

| Input | Required decision |
| --- | --- |
| Target | Application/environment, database/schema, realm/cookie namespace, all serving nodes and background retention workers |
| Artifacts | Released matching versions and exact forward-recovery build; compatible projection codec/readers |
| Access and state | Migration role, runtime/clock roles, host transaction/pool configuration, vault/key continuity and authority-state preservation |
| Operations | Drain/start owner, maintenance and shutdown owner, observation/alert destination and acceptance thresholds |
| Storage | Inline or reference mode; for reference mode, qualified adapter, confidentiality, retry/admission limits and physical fence-storage budget |
| Change window | Expected interruption, affected scope, approved release/deployment actions and any material cost |

Resolve these inputs before requesting approval for the concrete publication or
deployment. A local rehearsal authorizes neither external action. Do not use the
synthetic example's public accounts/vault key for an application rollout. Its
configuration is documented in the [clustered example guide](clustered-browser-authentication.md#reproducible-correctness-fixture);
`SPOONBILL_AUTH_PROVIDER=jdbc`, `SPOONBILL_AUTH_CLUSTERED=true` and
`SPOONBILL_AUTH_SNAPSHOT_STORAGE=inline|reference` select its two paths.

## V7 execution order

Follow the authoritative [V6/V7 migration and rollback contract](request-admission-lifecycle.md#postgresql-v7-snapshot-references-migration).
The ordered acceptance points are:

1. Stop new admission and drain admitted work; stop **every incompatible serving,
   writing and retention binary** for the scope. Routing traffic away is not
   sufficient. Preserve authority clocks, floors, issuance metadata and vault
   decryptability; restoring stale authority data is not a rollback strategy.
2. With V4–V6 installed, apply [V7 SQL](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V7__snapshot_references.sql)
   in one transaction through the migration role, before V7-aware nodes start.
   Every JDBC deployment needs V7, including inline-only deployments, because
   their writer clears the new pointer column. Request handling never migrates.
3. Verify the reference/read-pin tables and nullable `payload_operation` column,
   and compare existing inline snapshot bytes/revisions to their pre-migration
   values. Inline/empty views remain inline until a reference writer
   publishes. Confirm restore and an acknowledged next revision with the chosen
   codec before reopening normal admission.
4. Start only compatible readers/writers/retention workers with consistent scope
   and limits. For reference mode, verify a published pointer resolves to exact
   bytes, a takeover restores the acknowledged projection, and superseded or
   abandoned payloads settle through scheduled maintenance. Missing/corrupt bytes
   must fail closed, never reset the view.
5. If verification fails, keep admission stopped and repair forward using a
   compatible build. Returning to older binaries requires an explicit, reviewed
   migration of reference-backed views to inline data first, while preserving
   V6 fencing requirements. There is no automatic down migration. Never drop
   uncertain retired references or reset claim generations; retry deletion
   through the maintenance protocol.

## Runtime acceptance

Schedule both `browser.maintenance()` and `binding.maintenance()` for reference
mode. The example uses a nonoverlapping one-second tick and shuts down on a
failed tick; applications choose cadence against their lease/recovery windows
and alert on failed or delayed ticks. Stop scheduling/admission, drain admitted
snapshot writes, then close the browser and binding. `binding.close()` does not
await an in-flight adapter call or close the shared provider/store; the owner
must manage those resources. See the [binding lifecycle](clustered-browser-authentication.md#external-payload-storage)
and [coordinator maintenance contract](request-admission-lifecycle.md#required-application-maintenance-and-observability).

Configure `SnapshotReferenceSettings` (`reservationSeconds`, `readPinSeconds`,
`claimLeaseSeconds`, `retryBackoffSeconds`, `maxOpenObligations`),
`PayloadRecoverySettings.maintenanceBatch`, and `BrowserPersistenceLimits.retention`
for the application. Monitor `binding.backlog()` fields `open`, `uncertain`,
`staged` and `oldestRetiredAt`, binding statistics, and application-observed
reservation refusals. Alert before `open + staged` reaches `maxOpenObligations`,
and on aging unresolved obligations or repeated uncertain/downgraded deletes.
The API supplies observations; the application supplies alert thresholds and
delivery. Restore maintenance/store health before increasing limits.

Track adapter-owned physical payload and fence storage separately. The provider
bound covers open obligations/reservations, not permanent markers after
`FinalDeleted`. The example JDBC and file adapters retain tombstones permanently;
their successful cleanup does not establish bounded cumulative marker storage.
An adapter must enforce any claimed storage bound and retain fences/accounting
until closed admission and settled writes prove safe reclamation. Complete the
[adapter qualification](recovery-adapter-qualification.md) before production use;
local laws do not prove cross-process/crash durability. No Cassandra or Redis
adapter support is implied by this runbook.
