# Portable UI recovery

Updated: 2026-11-09. Portable recovery is implemented in merged PRs
[#25](https://github.com/natural-transformation/spoonbill/pull/25) and
[#26](https://github.com/natural-transformation/spoonbill/pull/26) and is included
in Spoonbill **10.0.0**. It is not available in Spoonbill 9.0.0. See the
[10.0 migration guide](migration-v10.md) before upgrading.

## Current status

- R0/R1: public recovery contracts, the generic binding, JDBC reference
  publication and V7 migration are implemented. Inline PostgreSQL snapshots
  remain the default; external payload storage is opt-in.
- R2: `spoonbill-recovery-testkit`, an independent non-JDBC adapter consumer and
  qualification guidance are implemented. Local publication and consumer-law
  checks validate packaging; resolution of an actual public release remains a
  release gate.
- R3: the two-JVM `reference-payloads` browser scenario and authenticated
  inline/reference latency harness are implemented. See the
  [latency evidence](event-latency-performance.md#authenticated-views-with-durable-snapshots-inline-and-reference-backed).

The [completion checklist](remaining-work-after-portable-recovery.md) records
local validation and outstanding release/deployment gates. Production adapters
still need storage-specific qualification; PairTime adoption and application
rollout are separate work.

## Implemented public API

The package is `spoonbill.recovery`; the source files below are authoritative.
The following declarations are excerpts of the implemented signatures, not a
standalone application example:

```scala
// PayloadRecovery.projectedWithPayloads
def projectedWithPayloads[F[_]: Effect, P, S, D](
  provider: SnapshotReferenceSupport[F, P],
  payloads: SnapshotPayloadStore[F],
  format: SnapshotFormat[D],
  project: S => D,
  restore: (S, D) => S,
  settings: PayloadRecoverySettings = PayloadRecoverySettings()
): PayloadSnapshotBinding[F, P, S]

// SnapshotReferenceSupport
def bind(view: BrowserView[P], policy: SnapshotSchemaPolicy): Option[BoundSnapshotReferences[F]]
def maintenance: SnapshotReferenceMaintenance[F]
```

| Public source | Contract |
| --- | --- |
| [PayloadRecovery.scala](../modules/spoonbill/src/main/scala/korolev/recovery/PayloadRecovery.scala) | `PayloadRecovery.projectedWithPayloads` composes the provider and payload adapter. `PayloadSnapshotBinding` exposes `snapshots`, `maintenance()`, `close()`, `statistics` and `backlog()`. |
| [PayloadStore.scala](../modules/spoonbill/src/main/scala/korolev/recovery/PayloadStore.scala) | Immutable bytes/references, `SnapshotPayloadStore`, write/read/delete outcomes and the adapter's declared cleanup basis. |
| [SnapshotReferences.scala](../modules/spoonbill/src/main/scala/korolev/recovery/SnapshotReferences.scala) | Optional `SnapshotReferenceSupport`, schema policy, bound publication/read operations and maintenance claims/backlog. |
| [Public recovery testkit](../modules/recovery-testkit/src/main/scala/spoonbill/recovery/testkit) | Payload and coordinated recovery laws for adapter/provider projects, published as `spoonbill-recovery-testkit`. |

Pass `binding.snapshots` to `BrowserAuthentication`, schedule both browser and
binding maintenance, and drain admitted writes before closing the binding.
Snapshot acknowledgement requires durable payload storage and provider-fenced
publication. Missing/corrupt payloads fail closed. Deletion requires qualified
retirement fencing or quiescence; deadlines alone do not prove finality.

## Integration, qualification and rollout

- [Clustered browser authentication](clustered-browser-authentication.md#external-payload-storage): application wiring, binding lifecycle and reproducible browser fixture.
- [Adapter qualification](recovery-adapter-qualification.md): cleanup, durability, retention and production evidence requirements.
- [Independent consumer](../misc/recovery-adapter-consumer/README.md): matching-version coordinates, local packaging checks and public-release verification.
- [Release and rollout](portable-recovery-rollout.md): version decision, V7 migration, deployment inputs and runtime acceptance.
- [Request admission lifecycle](request-admission-lifecycle.md#postgresql-v7-snapshot-references-migration): authoritative V7 migration and rollback contract.
- [Remaining work and verification](remaining-work-after-portable-recovery.md): checklist, evidence, review map and rerunnable commands.

The [2026-11-07 design archive](archive/portable-ui-recovery-design-2026-11-07.md)
preserves the historical rationale and uncompiled interface sketches. It is not
current integration guidance.
