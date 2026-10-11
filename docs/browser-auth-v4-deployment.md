# PostgreSQL deployment and v4 cutover

This procedure applies to the v4 `JdbcBrowserPersistence` coordinator integration.
It is an explicit deployment procedure, never a request-time migration. The
example's `--initialize`, synthetic users, public vault key and loopback database
are development fixtures and must not be used for production users.

## Supported boundary

| Component | Initial v4 target |
| --- | --- |
| Spoonbill modules | Matching 4.x versions; this checkout is `4.0.0-SNAPSHOT`, not a publication claim |
| Scala | 3.3.8 |
| Native Scala 3 Pekko / Pekko HTTP | 1.7.0 / 1.4.0, including testkits on the same `_3` variant |
| JDBC dialect / validation engine | PostgreSQL 14; PostgreSQL-specific advisory locks, `ON CONFLICT`, and `RETURNING` are required |
| Test/example JDBC driver | PostgreSQL JDBC 42.7.13 |

The [independent consumer fixture](../misc/pekko-native-consumer/README.md)
checks publication metadata and coexistence with native typed actors and
persistence. Additional dependency pairs need that fixture and transport suites
before being declared supported. Binary exclusions that swap `_2.13` and `_3`
are not a migration strategy.

**One live owning provider/coordinator instance per database authority scope
(`realm`, `cookieNamespace`) is required.** `exclusiveAuthorityWriters = true`
asserts this operational fact; it does not elect a leader or fence another
process. Drain and stop the previous owner before starting its replacement.
The advisory lock serializes framework transactions but does not make multiple
independently cached clock writers supported. The durable clock may reuse an
acknowledged local maximum under this exclusive-owner contract. A fresh owner
consults storage; an overlapping old owner must not keep using an older cache.

All host authority mutations must obey the documented host/slot/view lock order
and policy-version contract. No application writer may reset slot generations,
view epochs, proof use, ceremony outcomes, or clock maxima. Independent services
that cannot honor this authority domain require a different conforming provider,
not just the Boolean configuration flag.

## Explicit schema artifact

The JDBC artifact includes
[V4__browser_auth.sql](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V4__browser_auth.sql).
Its non-default resource directory deliberately avoids automatic discovery by
Flyway's `db/migration` convention. Include it explicitly in the application's
reviewed deployment migrations.

It contains exactly the same 11 DDL statements as the three runtime initialization
methods, in dependency order: durable clock, browser authority/snapshots, then
ceremony and delivery material. It creates eight framework tables, two indexes,
and adds the existing snapshot-revision column when absent. It does not create
host accounts, factors, sessions, proof-use records, audit tables or an outbox.
Those remain application migrations participating in the same host transaction.

| Tables | Security role / retention |
| --- | --- |
| `spoonbill_auth_clock` | Durable highest observed time; never reset on rollback or restart |
| `spoonbill_browser_slot` | Browser lineage and monotonically advancing generation |
| `spoonbill_browser_session`, `spoonbill_browser_completion` | Host-session binding, activation and acknowledgment history |
| `spoonbill_browser_view`, `spoonbill_view_snapshot` | Ownership/revision fences and optional presentation snapshots |
| `spoonbill_auth_ceremony` | Original ceremony, captured generation, fixed attempt, challenge association and positive/negative settlement |
| `spoonbill_auth_material` | Encrypted, bounded, expiring delivery material; disposable after authoritative retirement |

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
  --single-transaction --file \
  modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V4__browser_auth.sql
```

`MIGRATION_DATABASE_URL` is a PostgreSQL/psql connection URI, not a JDBC URL.
Use the deployment's established credential mechanism and avoid storing it in
logs or command transcripts. An application migration tool may execute the same
SQL under its own transaction and version bookkeeping instead.

The checked-in drift guard reads the literal runtime DDL; it does not contact a
database or execute a migration:

```sh
nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check
```

After a reviewed schema change, `--write` regenerates the artifact. Keep its
diff and the application deployment migration together. Runtime `initialize`
remains an explicit convenience for owned setup; no HTTP/WebSocket path invokes
it automatically.

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
