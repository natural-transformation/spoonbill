# PostgreSQL deployment and v4 cutover

This procedure applies to the v4 `JdbcBrowserPersistence` coordinator integration.
It is an explicit deployment procedure, never a request-time migration. The
example's `--initialize`, synthetic users, public vault key and loopback database
are development fixtures and must not be used for production users.

## Supported boundary

| Component | Supported boundary |
| --- | --- |
| Spoonbill modules | Matching versions throughout; use 5.1.0 for replacement deployments |
| Scala | 3.3.8 |
| Native Scala 3 Pekko / Pekko HTTP | 1.7.0 / 1.4.0, including testkits on the same `_3` variant |
| JDBC dialect / validation engine | PostgreSQL 14; PostgreSQL-specific advisory locks, `ON CONFLICT`, and `RETURNING` are required |
| Test/example JDBC driver | PostgreSQL JDBC 42.7.13 |

The [independent consumer fixture](../misc/pekko-native-consumer/README.md)
checks publication metadata and coexistence with native typed actors and
persistence. Additional dependency pairs need that fixture and transport suites
before being declared supported. Binary exclusions that swap `_2.13` and `_3`
are not a migration strategy.

**Exclusive mode requires one live owning provider/coordinator instance per database authority scope
(`realm`, `cookieNamespace`).** In v5, `ownership = BrowserOwnership.ExclusiveScope`
asserts this operational fact; it does not elect a leader or fence another
process. Drain and stop the previous owner before starting its replacement.
The advisory lock serializes framework transactions but does not make multiple
independently cached clock writers supported. The durable clock may reuse an
acknowledged local maximum under this exclusive-owner contract. A fresh owner
consults storage; an overlapping old owner must not keep using an older cache.

This checkout also supports explicit shared PostgreSQL mode with
`ownership = BrowserOwnership.SharedViews`, per-view database ownership and durable
presentation projections. See [shared-store deployment and migration](clustered-browser-authentication.md)
before enabling it. Released 4.0.0 exclusive writers cannot participate in that
overlap; drain them before the initial shared-mode cutover. The cutover sequence
below describes the original major/exclusive migration.

All host authority mutations must obey the documented host/slot/view lock order
and policy-version contract. No application writer may reset slot generations,
view epochs, proof use, ceremony outcomes, or clock maxima. Independent services
that cannot honor this authority domain require a different conforming provider,
not just a configuration setting. The released v4 constructor used
`exclusiveAuthorityWriters = true`; see the [v5 source migration](migration-testing-5.0.0-snapshot.md).

## Explicit schema artifact

The JDBC artifact includes the frozen
[V4__browser_auth.sql](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V4__browser_auth.sql)
and the forward
[V5__authenticated_session_replacement.sql](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V5__authenticated_session_replacement.sql).
Their non-default resource directory deliberately avoids automatic discovery by
Flyway's `db/migration` convention. Include them explicitly in the application's
reviewed deployment migrations, in version order. Existing V4 deployments apply
only V5; fresh installations apply V4 followed by V5.

V4 remains byte-for-byte unchanged: 11 DDL statements, eight framework tables,
two indexes and the existing snapshot-revision alteration. V5 adds the replacement
table, reservation uniqueness index and successor lookup index. Together the
14 statements create the same nine framework tables and four explicit indexes
as runtime initialization. Host accounts, factors, sessions, proof-use records,
audit tables and outboxes remain application migrations participating in the
same host transaction.

| Tables | Security role / retention |
| --- | --- |
| `spoonbill_auth_clock` | Durable highest observed time; never reset on rollback or restart |
| `spoonbill_browser_slot` | Browser lineage and monotonically advancing generation |
| `spoonbill_browser_session`, `spoonbill_browser_completion` | Host-session binding, activation and acknowledgment history |
| `spoonbill_browser_view`, `spoonbill_view_snapshot` | Ownership/revision fences and optional presentation snapshots |
| `spoonbill_auth_ceremony` | Original ceremony, captured generation, fixed attempt, challenge association and positive/negative settlement |
| `spoonbill_auth_material` | Encrypted, bounded, expiring delivery material; disposable after authoritative retirement |
| `spoonbill_browser_replacement` | Explicit replacement kind, exact predecessor/owner linkage, successor reservation and terminal fence; ordinary rows never infer replacement from purpose |

`CREATE TABLE IF NOT EXISTS` is idempotent installation, **not** validation of an
arbitrary existing schema. Before applying it, compare any existing framework
tables, column types, constraints and indexes against this artifact. The current
v3 browser/snapshot tables are retained; v4 adds clock/ceremony/material tables.
An incompatible private fork or older schema requires an application-specific
reviewed migration, rather than assuming initialization repairs it.

Use a chosen, application-owned schema and a migration role that owns the
existing tables. The runtime role needs only the required table DML; it need not
create or alter tables. Both the main data source and reserved clock data source
must address the same chosen authority schema in this deployment.

For example, after explicitly creating and reviewing the `auth_v4` schema,
the following applies the DDL atomically through the repository's Nix environment.
Replace the target with the approved deployment database; this document does
not authorize executing it against any database:

```sh
PGOPTIONS='-c search_path=auth_v4' nix develop --no-write-lock-file --command \
  psql "$MIGRATION_DATABASE_URL" --no-psqlrc --set=ON_ERROR_STOP=1 \
  --single-transaction \
  --file modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V4__browser_auth.sql \
  --file modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V5__authenticated_session_replacement.sql
```

`MIGRATION_DATABASE_URL` is a PostgreSQL/psql connection URI, not a JDBC URL.
Use the deployment's established credential mechanism and avoid storing it in
logs or command transcripts. An application migration tool may execute the same
SQL under its own transaction and version bookkeeping instead.

The checked-in drift guard verifies V4's frozen checksum and the combined
V4/V5 coverage against literal runtime DDL; it does not contact a database or
execute a migration:

```sh
nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check
```

`--write` regenerates only the V5 delta and refuses a changed V4. V5 is
published with 5.1.0; preserve its applied bytes and use a new migration
version and updated drift guard for later schema changes. Do not
repair checksum history to accommodate an edited migration. Runtime `initialize`
remains an explicit convenience for owned setup; no HTTP/WebSocket path invokes
it automatically.

### Upgrade an existing browser-authentication deployment

Use the separate generated
[V5__authenticated_session_replacement.sql](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V5__authenticated_session_replacement.sql)
as the forward upgrade. V4 is preserved unchanged; do not replace an
already-applied migration or rewrite its checksum/history.
V5 adds replacement linkage, its reservation uniqueness index and a partial
`(realm, namespace, successor_session_id)` lookup index while preserving
ordinary ceremonies, outstanding ordinary ciphertext, clock checkpoints,
generations, owner epochs and replay history. Apply V5 in a single migration
transaction under the existing chosen schema. Request-time initialization is
not a schema-upgrade mechanism.

Drain **all** old serving binaries, settle admitted work, apply V5, start only
compatible binaries, and then enable replacement UI. Old binaries do not check
the new reservation fence; a mixed-version rollout with replacement enabled is
unsupported. Refresh browser pages onto the updated client bundle before enabling
the UI; old tabs do not understand the new fixed recovery procedure and the wire
protocol has no version negotiation. Existing ordinary delivery ciphertext remains readable. New
replacement ciphertext uses a separate versioned associated-data encoding bound
to realm, namespace, binding, attempt, transition kind and successor. Keep the
vault keys needed by both forms until their delivery retention has ended.

Do not downgrade by deleting replacement rows: expired or exhausted reservations
still fence competing completions, and committed outcomes are not rollback
evidence. Recovery or an explicit logout/reset must settle each pending browser
transition. Any later downgrade needs a separately reviewed procedure that
preserves these fences; this feature does not promise an automatic downgrade.

## Cutover sequence

1. Freeze the application/version/DDL identities, chosen schema, realm and cookie
   namespace, origin/cookie settings, vault key envelope, host migration, and
   rollback decision. Back up the database and the required key versions using
   the deployment's established process. Establish the sole writer externally.
2. Stop admitting new authentication/domain work to the old version. Drain
   admitted transactions and pending account work, stop maintenance admission,
   close physical sockets, release views, then stop the old provider and worker
   resources. Do not run old and new writers against the same authority scope.
3. Decide how outstanding preparations end. Either complete their old delivery
   window before shutdown, or explicitly invalidate the old scope and require
   fresh authentication. Do not import completion lookup IDs as v4 proof or
   manufacture committed receipts from an application callback result.
4. Under the drained writer boundary, apply the reviewed schema and host
   migrations in their designated transactions. Confirm constraints/indexes and
   permissions before opening traffic. No request should create missing tables.
5. Prefer a new v4 realm/cookie namespace and new cookie names for the initial
   major cutover. Expire the old cookies and fence old server authority as part
   of the application migration. Revoke old browser sessions and advance old
   slot/view fences without resetting or wrapping them; an exhausted scope stays
   closed. A cookie rename alone is not a server-side revocation or proof-use
   fence. Retain the old negative/replay and clock records.
6. Start the one new provider/coordinator, then its application. Verify public and
   protected HTTP rendering, password/factor login, bound cookie delivery,
   fresh-handshake activation, reconnect, logout, and old-cookie refusal against
   the deployed configuration before reopening traffic. Test restart recovery
   only within the new durable scope with the same keys and original attempt.

Same-origin completion remains HTTP and all proof submissions remain typed
WebSocket actions. An existing socket never adopts a cookie's later identity.
Do not preserve old privileged managers or carry sensitive disclosures through
cutover as ordinary presentation snapshots.

## Pool, key and history operations

Clock advancement commits on an independent connection even when the surrounding
host transaction later fails. Reserve a clock connection/data source or provide
at least `N + 1` usable connections for `N` simultaneously held domain
transactions. Bound executor admission, pool waits and database operations;
pool starvation must fail closed, not silently skip the checkpoint. Close waits
for already admitted settlement before releasing executors and pools.

Supply an actual deployment vault key policy. The single-key AES-GCM helper has
no rotation envelope; deployments needing rotation must implement
`JdbcCredentialVault` with authenticated key identification and retained decrypt
keys. Keep keys required by still-valid ciphertext across restart and rollback.
Never log token plaintext. Missing/corrupt key material must deny delivery,
not create a substitute session or repeat proof consumption.

The initial providers deliberately retain replay/fencing history to finite
limits. In particular, `ceremoniesPerBinding` (default **16**) counts all retained
ceremonies for that binding, including expired, failed and committed attempts;
it is not a sliding-rate-limit or TTL cache. Delivery-material maintenance
erases ciphertext but does not free ceremony/proof history. After its history
limit, that binding fails closed for new ceremonies. A later newly issued
binding can obtain its own history, but old binding/session/view history still
counts against global limits. Expiring a browser cookie does not delete its
durable records, and applications must not clear it automatically to bypass
their own rate limits.

Each replacement has one retained ceremony and shares those same limits:
`retainedCeremonies` defaults to **32,768 per realm/namespace**, and the
per-binding limit also includes replacements. Replacement linkage is retained
with the history; the successor index changes lookup access, not retention.
Do not prune a row merely because its generation is below the slot's current
generation: activation advances that generation while the live successor still
needs its linkage for `withSuccessorCompletion`. Ceremony expiry is not a proof
that host-owned results or successor metadata are no longer needed.

Monitor global history and owned-resource limits. Size them explicitly for the
deployment and plan a drained namespace cutover or separately proved retirement
policy before exhaustion. Do not add `DELETE expired ceremonies`, truncate a
table, or recycle an ID/generation to make capacity available: old capabilities
and unresolved writers must remain permanently fenced. The shipped v4 SQL
intentionally contains no history-deletion policy.

## Failed migration, rollback and restore

If the DDL transaction fails, roll back that migration transaction and keep
traffic closed; inspect its actual settlement before retrying. This is separate
from uncertain host authentication commits, which reconcile the original
ceremony/attempt after excluding a late writer. Neither case authorizes replaying
the host proof callback independently.

There is no destructive automatic down migration. Rolling back application code
does not undo already committed account/audit/domain effects and must not restore
earlier security state. Keep added framework tables, clock maxima, used-proof
rows and terminal ceremony outcomes. An old application version may restart only
after the new writer is drained and compatibility of host schema, key material,
cookie namespace and fences is established. Otherwise keep the new authority
scope closed and use a separately reviewed fresh scope with forced
reauthentication; never reopen the pre-cutover privileged sessions.

Do not restore an older database snapshot and resume the same namespace with
its earlier clock/generations. That can revive expired or revoked authority.
Disaster recovery must preserve all security maxima/fences or isolate a new
authority scope, revoke the old sessions at the host boundary, expire old
cookies and require new proof. Recovery of data availability is not evidence of
safe authority recovery.

These deployment and correctness requirements do not constitute a latency or
memory acceptance result; see the [v4 validation record](browser-auth-v4-validation.md).
