# Proposal: simpler browser authentication integration

Status: proposed; no implementation or release commitment. Based on Spoonbill 3.0.0, source revision `ab95a4fe65399814931c6ddd7f29dd68f33bbc5c`.

## Outcome

An application should be able to adopt Spoonbill's browser authentication with less production integration code, without increasing latency or memory usage for equivalent behavior. It should use the official transport adapter and supply its domain authentication policy and persistence integration. It should not need to reproduce session/view coordination, credential-delivery plumbing, or revocable presentation storage.

Add one small coordinator over existing APIs, a portable persistence contract, and the missing storage capability. Preserve the existing typed actions, sensitive disclosure, snapshots, and WebSocket lifecycle. This proposal does not introduce another action DSL or an account-management framework.

The acceptance gates are **no net growth in consumer production integration code**, **no measured latency or memory regression**, and **preservation of authorization and transaction guarantees**. Shorter examples alone do not satisfy those gates.

## Current limitations

The following are properties of the current library, not reports about a particular application:

| Current surface | Integration cost or limitation | Proposed change |
| --- | --- | --- |
| [`SessionAccessControl`](../modules/spoonbill/src/main/scala/korolev/server/SessionAccessControl.scala), [`SessionAuthority` and `Actions`](../modules/spoonbill/src/main/scala/korolev/action/Action.scala), and [`AuthenticationCompletionConfig`](../modules/spoonbill/src/main/scala/korolev/server/AuthenticationCompletionConfig.scala) are separate contracts. | Applications must connect the same browser, host session, and view authority across several entry points. | One coordinator installs a consistent set of existing contracts. |
| [`JdbcBrowserSessions`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSessions.scala) exposes separate view validation and browser-session validation. Its [`HostSessionHooks`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSession.scala) returns a Boolean for current host authority. | Resolving a typed host principal can require additional host work and repeated validation. | A coherent authorization operation returns a freshly checked principal and view fence. |
| Browser preparation already joins a host transaction, but its low-level inputs include raw UUIDs, a generation, and credential digests. | Applications must assemble ceremony tracking, completion delivery, and host-protocol translation themselves. | Server-owned ceremony and preparation capabilities carry the invariants across those boundaries. |
| [`StateStorage.ephemeral`](../modules/spoonbill/src/main/scala/korolev/state/StateStorage.scala) retains disconnected presentations, without an owner/epoch-aware invalidation contract. | Applications needing permanent invalidation on authority loss must provide additional storage logic. | A bounded presentation store distinguishes disconnect, takeover, and invalidation. |
| [`StateManager.cached.read`](../modules/spoonbill/src/main/scala/korolev/state/StateManager.scala) obtains a snapshot of the entire node map. | An individual read scales with the map rather than the requested node. | Direct node lookup; full copying only for explicit snapshots. |
| The [Pekko adapter dependencies](../build.sbt) use `for3Use2_13`, including in the published Scala 3 adapter. | Applications using native Scala 3 Pekko artifacts cannot simply combine both variants. | Publish and test a supported native Scala 3 dependency graph. |

The existing [WebSocket lifecycle contract](websocket-lifecycle-release-note.md) already handles owned setup, terminal responses, cancellation, and release. Reuse it rather than moving those responsibilities into applications.

## Ownership and module boundaries

| Owner | Responsibilities |
| --- | --- |
| Spoonbill core | Browser lineage and generation rules; ceremony lifecycle; connection/view ownership; action admission and output checks; completion protocol; ephemeral presentation lifecycle. |
| Application | Account model; password and factor verification; enrollment/recovery policy; domain authorization; host-session semantics; navigation; audit requirements and deployment decisions. |
| Persistence provider | Atomic operations, conditional writes, transaction enlistment, durable records, expiry, and error classification needed by the core contract. |
| Optional JDBC module | One provider for a documented SQL dialect, host transaction integration, lock ordering, and efficient combined operations. |
| Official transport adapter | HTTP/WebSocket adaptation, setup ownership, cancellation, streaming, and exactly-once resource release. |

Core APIs must contain no JDBC `Connection`, `DataSource`, SQL, or assumptions about relational storage. Provider transaction contexts are abstract types; JDBC types appear only in the JDBC module. A provider may use an actor, another database, or a remote authority service if it satisfies the same contract.

Application and browser records need one coherent authority; avoid creating a second independent account/session system in Spoonbill. Integrate the application's session issuance through the provider boundary. Initially preserve the current completion credential format: exactly 43 base64url characters, with the trusted issuer responsible for cryptographic entropy. Arbitrary existing token encodings are not automatically compatible; supporting them would require a separately reviewed credential-encoding contract. Host credentials stay in trusted transport/persistence code, never in UI state or action results.

## 1. One browser authentication coordinator

Propose `BrowserAuthentication[F, S, P]`, where `P` is the application's server-resolved principal. It composes the current session access control, action authority, completion configuration, and presentation storage. A guarded configuration must install them together, with one authority instance and consistent origin, realm, cookie, and ownership settings. Partial security installation or conflicting overrides must be rejected before serving requests.

Keep ordinary `Actions`, `UiOutcome`, `SensitiveRegion`, and snapshot APIs. The coordinator should provide only what is missing: begin/resume a ceremony, convert a committed completion to the existing UI outcome, and configure the existing service. Sensitive disclosure remains opt-in and default-deny; it does not inherit permission merely from an authenticated principal.

Illustrative proposed usage, not an API available in 3.0.0 or a compiled example:

```scala
val browser = BrowserAuthentication[F, PageState, Principal](
  persistence = authPersistence,
  presentation = pageAccess,
  config = browserConfig
)

val actions = new Actions[F, PageState, Principal]

val signIn = actions.public(signInName, credentialsSchema, signInPolicy) {
  (input, context) =>
    for
      ceremony <- browser.begin(context)
      result   <- accounts.signIn(input, ceremony)
    yield result match
      case SignIn.Completed(receipt) => browser.complete(receipt)
      case SignIn.Challenge(prompt)  => showChallenge(prompt)
      case SignIn.Rejected(reason)   => showSignInError(reason)
}

val serviceConfig = browser.configure(baseConfig)
// Pass serviceConfig to the existing official transport adapter.
```

`accounts`, the `SignIn` result, and the rendering functions belong to the application. `Completed` contains a committed preparation receipt, not a bearer token or an application-created proof. `configure` wires the same authority into guarded requests and action dispatch. The example's public policy does not replace the runtime's origin, binding, input-size, or admission checks. Application policy/provider callbacks must enforce authentication rate limits; 3.0.0 does not supply general authentication rate limiting.

For a factor challenge, the first `begin` captures the ceremony before any credential verification. The next submission resumes that same server-owned ceremony through its connection/binding checks; it does not call `begin` again to obtain a newer generation. The rendered prompt contains only permitted presentation data and an opaque lookup handle.

Before adopting these names, implement one complete compiling generic example, including its provider and host transaction code. Count all that code when assessing simplicity; do not hide complexity behind undeclared helpers in documentation.

## 2. Server-owned ceremonies and transaction-safe preparation

Reuse existing opaque IDs and version types from [`Identifiers`](../modules/spoonbill/src/main/scala/korolev/security/Identifiers.scala). Introduce only the missing distinctions:

- An opaque `CeremonyId` identifies a lookup. A non-serializable `BrowserCeremony` capability contains server-resolved lineage, captured slot generation, purpose, expiry, and browser binding. Each use also requires a current connection/view authorization.
- A preparation result is scoped to its provider transaction. A committed completion is a distinct type issued only after commit has been confirmed.
- The existing completion ID remains a delivery handle, not authority to activate a session. Do not accept a raw UUID where a verified ceremony or committed preparation is required.
- Sealed result types distinguish challenge continuation, rejection, committed preparation, rollback, and unknown commit outcome. Keep application factor/challenge models outside the framework.

Use private constructors, opaque IDs, and provider-dependent transaction types to prevent accidental mixing of session/view/ceremony identifiers or transaction contexts. Constructors needed by third-party trusted providers belong to their explicit SPI, not to the UI-facing API. Avoid a generic state-machine type hierarchy where a small closed ADT is sufficient.

Scala 3 does not provide linear ownership or prove that a transaction committed. The implementation must still check single consumption, expiry, generation, owner, and transaction state at runtime. Transaction capabilities expire when their transaction closes; connection/view capabilities expire on release or takeover. A ceremony may resume through a fresh authorized connection, but retains its original browser lineage, generation, purpose, and expiry. Resuming cannot refresh those values. A typed principal is not permanently valid authorization.

The lifecycle is:

1. **Begin:** resolve the real connection and browser binding, capture the current generation before password/factor verification, and allocate a bounded, expiring ceremony. Capture is not authentication.
2. **Verify:** the host performs expensive proof computation without holding a database transaction across browser interaction or slow password hashing. Subsequent proof consumption must still verify the current host policy and proof state.
3. **Prepare atomically:** enlist Spoonbill in the host-owned transaction that consumes a one-use proof, creates/prepares the host session and completion, and writes required transactional audit/outbox records. Check the originally captured generation under the appropriate lock or conditional write. A failure rolls back the entire unit; do not silently refresh the ceremony or independently retry proof consumption.
4. **Confirm commit:** expose a committed completion only after success is known. For an unknown commit outcome, reconcile the same idempotency key and authority in storage; do not issue a second proof-consuming operation or claim rollback.
5. **Deliver:** the framework's small same-origin HTTP completion exchange validates the binding and delivers the HttpOnly cookie. Retry is bounded and idempotent for the same valid completion; the handle alone is insufficient.
6. **Activate:** only a fresh physical WebSocket handshake can activate the prepared session. An already-open socket keeps its captured identity even if the browser receives a different cookie.

Logout must fence pending ceremonies and prepared sessions as well as the active session. Repeated delivery, activation, and acknowledgment have distinct replay rules. Preserve the [one-use authority contract](one-use-authority-contract.md), including failure and cancellation behavior.

The coordinator must not commit, roll back, close, or retry the application's transaction independently. A provider's outer transaction runner performs settlement and only then promotes a transaction-local preparation to a committed receipt. Apps with an existing transaction runner need an enlistment API with the same verified settlement contract, not a second nested transaction.

A provider capability must state whether proof consumption and browser preparation can be atomic with the host store. Do not infer this merely because both implement an interface. If the data is split across unrelated stores, the simple integration is unavailable unless a separately specified protocol satisfies the same guarantees. Fail configuration explicitly rather than silently weakening one-use or logout semantics. Building a distributed transaction system is outside this proposal.

Keep credential storage and erasure rules explicit: no bearer credential in presentation, snapshots, ordinary result caches, exceptions, or logs; only bounded, protected delivery material where the delivery protocol requires it. Durable multistep ceremonies store only necessary server-side data with expiry and cleanup. They must not require an application-specific encrypted-secret translation table just to adapt browser binding to a legacy completion protocol.

## 3. Coherent authorization with a typed host principal

Add one provider operation that checks browser session, captured connection identity, current host policy, and view owner/epoch together, returning a freshly resolved `P` and the relevant fence. Anonymous connections also require browser/view fencing, even though no authenticated principal is returned.

Keep the provider operation semantic: it validates one authority boundary. It does not expose a generic database query builder. Its implementation may use a transaction, an atomic conditional operation, or an equivalent serialized authority service.

For the JDBC implementation:

- Use one connection and a short transaction for a coherent validation, with the established host → browser slot → view lock order.
- Let a typed host hook resolve the principal using that connection and the required host locks. Avoid a Boolean host hook followed by a separate principal lookup.
- Recheck expiry, host policy, slot generation, and owner after lock waits where required. Preserve every security check even where this means repeated reads.
- Measure transactions, pool acquisitions, SQL statements, and network round trips separately. One transaction does not mean one SQL statement or one round trip.
- Keep blocking JDBC work on the configured blocking executor and keep browser RPC, unrelated network calls, and long computation outside transactions.

The coordinator should use this operation for the existing guard and action-authority contracts, removing duplicate resolution within the same authorization boundary. Admission, post-effect output, sensitive disclosure, snapshot access, and business writes remain distinct boundaries. A check made before an asynchronous gap cannot be reused to authorize later output or a domain mutation.

Domain effects still require host authorization inside their own authoritative transaction or operation. Cancellation/output suppression cannot undo a committed effect. No cache TTL, connection-local principal, or presentation field substitutes for current authority. Any future caching design must specify and test its revocation semantics separately; it is not part of this simplification.

## 4. Bounded presentation storage with ownership fencing

Add an ephemeral presentation store integrated with the coordinator and existing [`ViewFence`](../modules/spoonbill/src/main/scala/korolev/security/ViewOwnership.scala). Device IDs and view lookup IDs alone confer no authority.

The store must distinguish:

| Event | Required behavior |
| --- | --- |
| Ordinary disconnect | Retain non-sensitive presentation within explicit reconnect TTL and capacity limits. |
| Valid ownership takeover | Attach the current owner/epoch atomically; an older owner's close cannot remove the successor. |
| Authorization loss | Permanently detach the matching revoked presentation; a stale manager cannot restore or republish it. |
| Late asynchronous setup result | Publish only if its owner claim is still current; otherwise release it. |
| Capacity or expiry eviction | Remove state and associated metadata together; subsequent requests follow the explicit fresh-view/reload path. |

Apply fencing to reads/writes and manager publication, not only final cleanup. Define the race between invalidation and reacquisition with a single ownership rule. A fresh authorized owner may build a new presentation; it must not inherit the revoked manager. Unknown IDs cannot create arbitrary durable view rows during resume.

Bound active views, disconnected views, HTTP bootstrap entries, pending acquisitions, and auxiliary ownership metadata. Avoid unbounded tombstone or lease maps. Distinguish disposable presentation metadata from durable replay/fencing records: cleanup must never reset or reuse an identifier, generation, or epoch so that an old capability becomes valid again. Define safe retirement or fail-closed allocation limits for that history. Specify behavior at each limit and release resources on cancellation. Node loss and distributed owner reclamation remain explicit provider policies; a local TTL is not a distributed authority mechanism.

Make an individual node read constant-time with respect to the number of presentation nodes. For serialized values, any defensive copy is limited to that node's bytes. Explicit snapshots may copy all nodes and must retain their existing isolation semantics. Preserve immutable-state expectations for the object-backed manager; do not return mutable internals to save allocations.

Ephemeral storage contains ordinary presentation only. Sensitive disclosures keep their separate lifetime and authorization rules and are never retained for reconnect. Durable snapshots remain a separately selected provider/schema with current identity and owner checks.

## 5. Use the official transport without dependency workarounds

The current [`spoonbill.pekko.pekkoHttpService`](../interop/pekko/src/main/scala/korolev/pekko/package.scala) already owns the transport behavior. Applications should only wrap/configure it for application concerns, rather than copy its publishers, subscribers, setup timeout, or release logic.

Publish a supported native Scala 3 Pekko adapter graph. Compile and test against a declared minimum Pekko/HTTP pair, and test the supported upgrade matrix. Keep test dependencies on the same artifact variant. Verify the published POM and downstream dependency resolution, not just compilation within this repository.

Do not recommend dependency exclusions that replace `_2.13` with `_3` binaries or silently downgrade the adapter's compile-time dependency versions without compatibility evidence. A consumer should not have to upgrade unrelated actor or persistence modules simply to avoid a copied HTTP bridge. Assess source, binary, and transitive dependency compatibility before deciding whether this needs another artifact or a version change; do not assume a patch release is sufficient.

Document normal typed/classic actor-system interoperation, preservation of explicitly configured reporters, and route wrappers for trusted network metadata. Strip browser-supplied copies before injecting server-derived metadata, consistently for HTTP and WebSocket requests. Such metadata supports application policy; it does not become authentication proof. Introduce a new transport hook only if the existing route/configuration extension points cannot express a concrete need.

## Acceptance and measurement

### Application simplicity

Use a generic, checked-in reference application with password login, an optional factor challenge, logout, reconnect, and a protected action. Include host policy, provider integration, and the full service setup. Add a minimal non-JDBC provider example to prove that JDBC types and transaction assumptions have not leaked into core.

Compare equivalent before/after behavior against a fixed baseline. Record application production lines added/removed, integration files, custom adapters, and public configuration points. Count wrappers and helpers wherever they are moved. Report one-time migration code separately and also include it in the total production-code delta. Tests, fixtures, and documentation are separate totals; they cannot obscure runtime growth.

The ordinary integration must have no net production-code increase. Do not achieve this through compressed formatting, removing comments needed for safe use, omitting error handling, or weakening behavior. If migration code temporarily increases the total, report that as a failed no-growth gate and specify its removal point rather than declaring success. Avoid permanent dual backends in the final application path.

### Correctness and security

Provide a reusable provider conformance suite, including deterministic concurrency barriers and fault injection:

- Successful password-only and multistep login; a challenge resumes the original ceremony and generation.
- Logout during proof verification or lock waits; logout after preparation and before activation; all reject stale authority.
- Repeated completion delivery and activation; mismatched binding/origin/purpose; stale handles; current-connection identity cannot change through cookie delivery.
- Proof replay, host-policy change, transaction rollback, unknown commit, and cancellation at each settlement boundary; no partial factor/session/browser/audit commit.
- Reconnect takeover followed by old-owner close or write; delayed setup publication; invalidation racing reconnect; no state resurrection.
- Anonymous fencing, expired sessions, protected HTTP rendering, missing-view recovery, and fresh public presentation after denial.
- Authority changes while an action is queued/running, during sensitive delivery, or before output/snapshot publication; effects and output retain their distinct guarantees.
- Capacity, expiry, storage failure, cleanup retry, and shutdown; metadata and resources remain bounded.

Retain the existing v3 transport lifecycle suites. Add compile-negative tests for mixing typed IDs, passing raw completion IDs to capability APIs, and crossing provider transaction contexts. These complement runtime tests; they do not establish freshness or linear use.

### Performance

Use the already selected, pinned comparison baseline and a controlled environment. Do not switch the historical baseline to a newer release. For this feature, also measure the same application against its existing integration with equivalent security behavior and data sizes.

Measure cold setup, login/completion/activation, protected events, reconnect, logout, and multi-node presentation reads. Report end-to-end p50/p95/p99 latency, throughput at fixed concurrency, allocations, peak and steady-state retained heap after churn, pool acquisitions, database transactions/statements/round trips, and lock contention. Include browser/runtime costs where the claim concerns user-visible performance.

The release gate is no latency or memory regression for each equivalent workload, within a predeclared measurement uncertainty budget, with no unexplained extra database operations. Noise is an inconclusive result requiring investigation; it is not permission to average a slow path away. Claim improvements only where results establish them. Deterministic CI checks can assert operation counts, direct-read behavior, resource bounds, and absence of leaks; wall-clock microbenchmarks alone are not reliable CI correctness tests.

Run development, dependency resolution, builds, and validation through the repository's [Nix flake](../flake.nix). Consumers using Nix must regenerate their dependency locks through their supported generator before validating CI builds.

## Delivery sequence and review points

1. **Confirm the boundary with a complete example.** Freeze the reference baseline and invariants; review coordinator usage, provider contracts, transaction settlement, and predicted code removal. Reject abstractions that merely rename application glue.
2. **Deliver independent reuse improvements.** Validate native Scala 3 adapter packaging and the owner-fenced store/direct reads with focused lifecycle and allocation tests. Reuse the existing v3 transport contract.
3. **Implement the coordinator and typed provider contract.** Add ceremony preparation and coherent authorization, a JDBC provider, a minimal non-JDBC example, and provider conformance tests. Review races and rollback/unknown-commit semantics before consumer cutover.
4. **Migrate the reference integration to one backend.** Remove obsolete ceremony translation, copied transport, and redundant validators/storage. Keep historical migration tests outside request-path dispatch. Recount all production code and measure performance.
5. **Review release readiness.** Publish the API/compatibility and measurement results only after the gates pass. Assign versioning based on actual compatibility changes. Database migrations and application deployment remain explicit release steps, never implicit request-time behavior.

Application-specific migrations can validate the proposal privately. Public examples, fixtures, reports, and release notes must use synthetic data and generic workflows without consumer names, private paths, or identifiable production patterns.

## Non-goals

This work does not add built-in password/TOTP policy, redesign typed actions, make browser-submitted IDs authoritative, add automatic production schema creation, solve general distributed transactions or node-failure reclamation, or replace the existing WebSocket lifecycle.

Keep proof submission and ordinary interactions on WebSocket. Keep the small HTTP exchange needed for HttpOnly cookie delivery and normal initial page/upgrade requests. Native HTTP forms are not required by this proposal. Do not add HTTP routes for each authentication action or claim that WebSocket frames can set HttpOnly cookies.

Advanced applications may continue using the lower-level interfaces. The coordinator should make the common secure integration small while keeping provider guarantees and application policy explicit.
