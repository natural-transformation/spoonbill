# Migrating to Spoonbill 5

Spoonbill 5 adds shared PostgreSQL browser authentication for overlapping web
processes, recoverable selected presentation, and administrative owner retirement.
This is a breaking release: update every Spoonbill module together and recompile
the application and custom integrations. Old compiled 4.x callers are not binary
compatible. Use the repository's `5.0.0-SNAPSHOT` artifacts until the release is
published.

## Select explicit ownership

Replace the JDBC provider's `exclusiveAuthorityWriters` Boolean:

```scala
import spoonbill.auth.BrowserOwnership

// One provider/coordinator for the entire authority scope:
ownership = BrowserOwnership.ExclusiveScope

// Multiple web processes sharing one authoritative PostgreSQL database:
ownership = BrowserOwnership.SharedViews
```

The provider constructor now requires `ownership`. Custom providers replace the
ownership Boolean fields in `BrowserProviderCapabilities` with one
`BrowserOwnership` value. Advertising a mode still requires implementing and
testing its storage and output guarantees; it does not elect an owner or make
an in-memory provider distributed.

Drain all exclusive-mode writers before the initial switch to shared mode.
Released 4.0.0 exclusive nodes cannot overlap shared v5 nodes. Retain the same
database/schema, realm, cookie configuration, public origin, host policy and
credential-vault keys. The framework SQL schema is unchanged from v4.

## Recover selected presentation

Applications keep their existing host authentication policy and transactional
proof/session/audit work. Add an explicit non-sensitive projection through
`provider.projectedSnapshots(format, project, restore)` and supply that factory
to `BrowserAuthentication`'s `snapshots` argument.

Restore only selected presentation onto freshly authorized application state.
Keep credentials, principals, capabilities, sensitive disclosures and current
business results out of the projection. Lift recoverable component fields into
the projected top-level state. Unsubmitted browser typing is not automatically
durable; acknowledge a save only through the resulting committed UI transition.
Recovery preserves an existing view, while full page reloads create new views.

Unsupported schemas now fail with
`ViewSnapshotException(ViewSnapshotError.SchemaIncompatible)` and preserve the
stored draft. Use `SnapshotFormat.reading(previousFormat)(convert)` for explicit
pure compatibility conversions. Every overlapping application version must read
all formats still being written; deploy compatible readers before switching
writers. Failed initialization cannot subsequently commit. One successful
initialization is permitted per snapshot session.

## Custom output integrations

Ordinary applications continue to use `browser.configure(baseConfig)`.
Custom guard implementations must migrate to `SessionOutputAdmission[F, S]`,
whose `apply` receives `(output, produced, current)`. Admission must validate
both immutable presentations and the captured identity/owner against one fresh
authority observation. It applies only to that already materialized output.
Bytes admitted before takeover can still arrive afterward; permission cannot
be reused for another frame.

`BrowserPresentation` predicates, projection callbacks and schema conversions
must be pure, bounded and repeatable. Shared-mode presentation predicates can
run inside the authoritative admission transaction and must not perform I/O.

## Operations and recovery

Reserve database connection capacity for independent durable-clock observations
and use bounded pool, connection, statement, lock and idle-transaction waits.
Protected work fails closed when authoritative storage is unavailable. Full
performance acceptance remains open; the reduced output transaction count is
not a latency, allocation or retained-memory guarantee.

Administrators can inspect aggregate `usage()` and bounded `ownedViewPage()`
results. `retireView(target)(authorize)` requires host administrator authorization
inside the supplied transaction and compares the exact observed owner/epoch.
Success frees active capacity while preserving the snapshot and security history;
a stale target returns `Right(false)` without removing its successor. An
authorized stale attempt may still commit its audit. Reconcile unknown commits
before retrying host effects. The library installs no administrative endpoint.

There is no automatic abandoned-owner reaper or history compactor. Plan capacity
and safe scope retirement explicitly. Ordinary business actions are not replayed
on reconnect; deliberate retries require domain idempotency or the existing
operation protocol. `transact(view)` alone is not a deduplication API.

For complete configuration and test scenarios, see the
[shared-store guide](clustered-browser-authentication.md),
[snapshot testing guide](migration-testing-5.0.0-snapshot.md), and
[validation report](browser-auth-v5-validation.md).
