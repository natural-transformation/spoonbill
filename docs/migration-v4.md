# Migrating to Spoonbill 4

Use the same 4.x version for every Spoonbill module and recompile the application,
transport integrations and custom providers. This checkout builds
`4.0.0-SNAPSHOT`; a changelog entry does not mean artifacts have been published.

## Modules and Scala 3 packaging

`spoonbill-core` contains immutable security identifiers, generations,
browser-slot/view/operation decisions and browser ceremony/configuration models.
Their existing `spoonbill.security` package names remain unchanged. New browser
models use `spoonbill.auth`. The ordinary `spoonbill` dependency includes core
transitively; logic-only consumers can depend on core directly.

`spoonbill` contains the coordinator, effectful providers and bounded presentation
store. PostgreSQL integration remains optional in `spoonbill-security-jdbc`.
Use the native Scala 3 `spoonbill-pekko` artifact without a
`CrossVersion.for3Use2_13` override. The Pekko integration now depends on native
Scala 3 Pekko modules. Align all related versions in downstream dependency graphs.

The effect abstraction, JDBC, clocks, entropy, cryptographic custody and live
capabilities remain outside core. Moving a type to core does not turn its value
into proof that a transaction committed.

## Replace hand-written browser integration

For applications adopting the coordinator, replace separate browser glue with
one `BrowserAuthentication[F, S, P]` instance:

| Existing responsibility | v4 integration |
| --- | --- |
| Separate HTTP, socket and action authority wiring | `browser.configure(base)` and `browser.authority` |
| Custom revocable browser presentation storage | The coordinator's `BoundedPresentationStore` |
| Browser-cookie generation/generation mapping in application code | Provider `capture`, `claim`, authorization and release |
| Ceremony lookup treated as verified proof | `browser.begin` / `resume` return issuer-owned `BrowserCeremony` |
| Manual browser-session and completion assembly | Concrete provider `prepare(ceremony)` joins host writes |
| Raw completion outcome construction | `browser.complete(CommittedAuthentication)` |
| Custom lost-response/restart recovery lookup | `browser.recover` and `BrowserContinuation` |

Keep account lookup, off-transaction proof computation, current host policy,
factor consumption, host-session issuance and domain audit in application code.
The [authentication guide](browser-authentication.md) and
[two-provider example](../examples/browser-auth/src/main/scala/example/browserauth/)
show the boundary.

The shared example is effect-polymorphic: `Accounts[F]`, `Application[F]` and
`MemoryAccounts[F]` run natively in either IO or Future. Start `LazyMain` for the
managed IO example; `Main` selects Future memory or PostgreSQL. For existing
example callers, add the corresponding `[Future]` or `[IO]` type arguments.

Leave `stateStorage`, `sessionAccessControl`, `authenticationCompletion` and
`browserSecurityInstallation` unset in the configuration passed to `configure`.
Do not replace those fields after installation. Configuration validation rejects
partial replacement, mismatched scopes, unsupported provider capabilities and
guarded WebSocket compression. Use the official service/transport construction
path so those checks run.

For an ordinary HTTP form such as the framework logout POST, add
`ActionForms.nativeSubmit[F, S, M]` to the form. The browser bridge otherwise
intercepts form submission for application events. Keep endpoint origin/CSRF and
authorization checks; this attribute only opts into native submission. Typed
`ActionForms.onSubmit` retains precedence when both attributes are present.

The lower-level JDBC browser APIs remain available. New `authorize` and
`authorizeIn` operations resolve a typed host principal and current browser/view
fences on one connection; do not combine a successful Boolean check with a later
principal lookup. `authorizeIn`, `activateIn` and `logoutIn` join a supplied
transaction and throw on refusal. The caller must roll back the entire operation.

## Transaction callbacks and uncertain outcomes

The portable transactional interface is
`TransactionalBrowserPersistence[F, G, P] { type Tx }`. Each concrete provider
has an instance-dependent transaction type. Migrate JDBC callback code from a
raw connection parameter to `tx.connection`; memory callbacks use `tx.hostState`
and `tx.modify`. Access outside the callback or on another thread fails. Do not
retain the raw JDBC connection obtained from a handle.

Generate transport credentials before entering the transaction. Return a
`HostSessionPrepared` from the callback after proof consumption, host-session
creation and audit writes. Rejection must throw or fail the transaction program;
returning an ordinary `Left` does not request rollback. Existing host runners
must report actual outermost settlement and join all callback work.

Only acknowledged commit yields `CommittedAuthentication`. A lost acknowledgment
must preserve the original ceremony ID and offer recovery without another proof
submission. Keep recovery available after acknowledged preparation too: the
WebSocket completion command can be lost. A recovered receipt permits the
delivery workflow only; it does not repeat the host callback or reconstruct an
operation-execution authority.

## Deployment and lifecycle

JDBC applications must explicitly migrate the framework tables before serving
requests, supply a restart-stable credential vault and account for independent
durable clock checkpoints. Configure a reserved clock data source or sufficient
connection-pool headroom. Set `exclusiveAuthorityWriters = true` only when the
application actually enforces the coordinated writer/ownership contract. The
memory provider intentionally loses authority after process restart.
Use the [deployment guide](browser-auth-v4-deployment.md) for the shipped SQL
migration and the exact writer, connection-pool and credential-key contract.

Own the maintenance timer, proof workers and provider lifetime. Await admitted
work and provider release before closing executors or pools. Capacity limits do
not authorize eviction of replay or negative-outcome fences. Sensitive disclosure
and durable presentation snapshots remain explicit opt-ins with their own
authorization contracts.

## Browser protocol and verification

The Scala declarations generate the client protocol table. After changing wire
declarations, run `spoonbill/generateBrowserProtocol` through Nix and commit the
generated module; resource assembly rejects stale output. Browser-local stale
frame checks, departure cleanup and DOM operation checks remain in the client.
See [protocol generation](client-protocol-generation.md).

Validate migration against password-only and challenged login, same-view
reconnect, protected rendering/actions, logout, stale credentials, rollback and
lost-response recovery. For JDBC, also exercise recovery after process restart.
The shared provider conformance suite and example browser tests cover these
boundaries; consult the current test results before making release claims.
Neither source reduction nor a correctness pass establishes latency or memory
acceptance.
