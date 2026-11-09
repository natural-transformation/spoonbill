# Migrating to Spoonbill 10: Avocet 2 and portable UI recovery

Spoonbill **10.0.0** upgrades to Avocet **2.0.0** and adds opt-in external
snapshot payload storage. This guide covers upgrading from 9.0.0. Update every
Spoonbill module to `10.0.0` together and recompile all consumers, including
custom render contexts, effect instances, providers and recovery adapters. Align
any explicit Avocet dependencies with `2.0.0`. Upgrades from before 9 must also
account for `SpoonbillServiceConfig.timerSettings`; from before 8, follow the
[8.0 migration guide](migration-v8.md), and from before 7, the
[explicit state-update migration](migration-v7.md).

## Avocet rendering and development sessions

Server-side rendering rejects styles emitted after attributes. Emit styles
first in hand-written render contexts and dynamic DSL content that the optimizer
cannot reorder. Live rendering rejects nesting beyond 255 levels and more than
32767 sibling positions instead of allowing identifiers to wrap. Exercise SSR
and live updates for custom rendering code and unusually deep or wide documents.
A failed update retains the previously accepted DOM; the next successful render
diffs against that baseline.

Old unversioned Avocet development render buffers cannot be loaded. Spoonbill
quarantines invalid saved buffers beside the original file with an `.invalid`
suffix, reports the path in a server diagnostic, and reloads the affected legacy
session. Old connections stay rejected after reconnect or restart; a fresh page
creates a new session. If quarantine fails, the original file is retained and
the failure is reported. Application presentation snapshots and PostgreSQL
recovery data use a separate format: do not delete them as part of this upgrade.

## Required JDBC V7 cutover

**Every JDBC deployment requires V7, including inline-only deployments.** The
inline writer now clears the new snapshot pointer column. There is no supported
mixed pre-V7 serving, writing or retention period for the same scope.

1. Stop new admission, drain admitted work, and stop every older serving,
   writing and retention binary. Routing traffic away alone is insufficient.
   Preserve authority clocks, floors, issuance metadata and vault keys.
2. With V4, V5 and V6 already installed, apply the forward
   [V7 SQL](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V7__snapshot_references.sql)
   in one transaction through the application's migration role before starting
   the upgraded provider. Request handling does not migrate the schema.
3. Verify the reference/read-pin tables and nullable `payload_operation` column,
   and confirm existing inline snapshot bytes and revisions are unchanged.
   Check restore and an acknowledged next revision before reopening admission.
4. Start only compatible readers, writers and retention workers. Existing inline
   and empty views remain inline until a reference-backed writer publishes a
   new revision. Continue scheduling `browser.maintenance()` on every live
   coordinator.

Rollback requires readers and retention logic that understand references, or an
explicit migration of reference-backed views back to inline data before older
binaries return, while preserving V6 fences. Do not drop uncertain retired
references or reset claim generations. Follow the full
[V7 migration and rollback contract](request-admission-lifecycle.md#postgresql-v7-snapshot-references-migration)
and [rollout acceptance procedure](portable-recovery-rollout.md#v7-execution-order).

## Optional external payload storage

The PostgreSQL module, inline snapshot APIs and existing authentication
integration remain available and are the default. External payload storage is
opt-in through `spoonbill.recovery`: a `SnapshotPayloadStore` stores immutable
bytes, while `SnapshotReferenceSupport` keeps publication under provider
authority, ownership and revision checks. No named database adapter is shipped.

If opting in, qualify the adapter with the matching
`spoonbill-recovery-testkit` and compose it with
`PayloadRecovery.projectedWithPayloads`. The returned binding's `snapshots`
factory feeds the existing `BrowserAuthentication` parameter. Schedule both
`browser.maintenance()` and `binding.maintenance()`; construction starts no
maintenance timer. Monitor cleanup backlog and reservation capacity. Stop
scheduling and admission, drain admitted snapshot writes, then close the browser
and binding. The application still owns the shared provider/store resources.
See the [binding lifecycle](clustered-browser-authentication.md#external-payload-storage),
[adapter qualification guide](recovery-adapter-qualification.md), and
[runtime acceptance checklist](portable-recovery-rollout.md#runtime-acceptance).
