# Browser authentication integration: implementation plan for the next major release

Status: implementation and release preparation are tracked in the
[v4 validation report](browser-auth-v4-validation.md). This document records the
design and original acceptance contract; it does not itself claim a benchmark
pass or artifact publication.

Scope update, 2026-10-11: the maintainer requested no further performance testing
and authorized making PR #7 merge-ready within its current reference/tooling
scope. Merge that unpublished foundation on correctness evidence without waiting
for the original Phase 0 measurement programme. Defer profiling and benchmarks;
retain missing measurements as unknown/inconclusive. This scoped merge decision
does not claim a latency/memory pass or deliver the v4 coordinator. Subsequent
correctness-focused implementation can build on the reference; do not describe
unmeasured performance as validated. The original measurement contract below is
retained for when that work is explicitly resumed.

Implementation scope update, 2026-10-11: after merging PR #7, the maintainer
requested the complete feature in a new release-ready PR. Implement the remaining
feature, portability, migration and correctness work. Keep performance validation
to the subsequently authorized small local smoke checks; the larger measurement
programme remains deferred. Report deterministic bounds and bundle/source
accounting separately from unmeasured latency and memory acceptance.

Prepared: 2026-10-10. Feature baseline: Spoonbill 3.0.0, commit `ab95a4fe65399814931c6ddd7f29dd68f33bbc5c`.

Target: the next major release, provisionally 4.0.0. Breaking source, binary, provider, and configuration changes are permitted. Publication and production migration are separate release actions.

This plan implements the [browser authentication proposal](browser-auth-integration-proposal.md). Where it makes stronger decisions, this plan governs implementation: the domain core must be pure, caller APIs must support ordinary functional composition, and new features must not regress latency or memory usage. Public names below are proposed until the complete examples compile.

## 1. Outcome and non-negotiable constraints

An application supplies its account policy, proof verification, host-session integration, presentation policy, and infrastructure resources. Spoonbill supplies browser ceremonies, session/view coordination, completion delivery, ownership-fenced presentation storage, and official transport integration.

Completion requires all of the following:

1. A demonstrably pure domain core, enforced by module dependencies and tests.
2. One coherent browser authentication installation with explicit, validated configuration.
3. A complete generic reference application with password login, optional factor challenge, logout, reconnect, protected rendering, and a protected action.
4. A JDBC integration and a non-JDBC integration that satisfy the same authority and atomicity laws.
5. No net increase in equivalent consumer production integration code, including wrappers, provider glue, and migration code still required at runtime.
6. Preserved one-use proof, transaction settlement, revocation, sensitive disclosure, snapshot, and WebSocket lifecycle guarantees.
7. No latency or memory regression in any required equivalent workload. An improvement in one path cannot offset a regression in another.
8. A major-version migration guide, published-artifact verification, and reproducible correctness and performance reports.
9. A Scala-first implementation with no application-written authentication JavaScript, removal of identified client duplication, and a documented minimum browser adapter. Measure client source and delivered bundle changes separately; shifting work must not add latency or memory usage.

Breaking compatibility is available to obtain a coherent design. It is not a reason to weaken security, move side effects into the core, or accept performance regressions. Avoid permanent compatibility dispatch in normal request paths.

Out of scope: account management, password/factor algorithms, recovery policy, general authentication rate limiting, distributed transactions, automatic production schema creation, a new action DSL, and replacement of the v3 WebSocket lifecycle. Applications must still supply rate limiting and business authorization.

## 2. Baseline observations and reusable contracts

The current checkout matches the proposal's source revision. Relevant existing surfaces are:

| Existing surface | Reuse or change |
| --- | --- |
| `SessionAccessControl`, `SessionAuthority`, `AuthenticationCompletionConfig` | Preserve their distinct security boundaries; replace independently configurable installation with one validated integration. |
| `BrowserSessionSlot`, `ViewOwnership`, typed identifiers and versions | Reuse immutable decision models and identifier distinctions in the pure core. |
| `TransactionExecutor[F, G, Tx]`, `TransactionProgram[G]` | Reuse joined transaction execution, acknowledged outer settlement, cancellation, and no-retry laws. |
| `PendingPreparation`, `TransactionScope`, `OneUseAuthorityScope` | Reuse the scoped registration and settlement patterns in the runtime. These currently contain mutable claims, thread observations, clock calls, or random ID generation; they are not pure core implementations. |
| `JdbcBrowserSessions.prepareNew` and host hooks | Replace raw preparation assembly with provider capabilities; retain one host transaction and authoritative lock ordering. |
| `StateStorage.ephemeral` and `StateManager` | Add explicit invalidation/ownership semantics and bounded lifetimes; make node reads independent of total node count. |
| Official Pekko adapter | Retain transport ownership and release behavior; support and test native Scala 3 dependencies. |

Read the [one-use authority contract](one-use-authority-contract.md), [WebSocket lifecycle contract](websocket-lifecycle-release-note.md), and [3.0 migration guide](migration-3.0.md) as preserved behavior. Existing tests are regression assets, not proof that a new provider or API is correct.

## 3. Pure core and effectful runtime boundary

### 3.1 Module layout

Introduce a small published `spoonbill-core` module, with sbt project ID `core`, for the pure domain kernel. Keep the existing `spoonbill` artifact as the application-facing runtime, depending on `core` and the existing effect/transport-independent support modules. This makes the purity claim precise without pretending that a server, storage cache, or transaction executor is side-effect-free.

| Layer | Contents | Dependency rule |
| --- | --- | --- |
| `spoonbill-core` | Immutable identifiers, versions, records, errors, ceremony/slot/view transitions, authority comparison rules, pure configuration validation, and relevant pure action/presentation descriptions | No dependency on `spoonbill`, effect implementations, JDBC, HTTP servers, actors, logging, schedulers, or mutable stores. Prefer Scala/JDK immutable values only; audit any additional dependency. |
| Provider SPI | Abstract operation contracts, transaction/effect type parameters, provider capabilities, immutable requests/results | Interface definitions may live with core if they introduce no runtime dependency; implementations and live capability issuance may not. No public JDBC types. |
| `spoonbill` runtime | `BrowserAuthentication`, live connection/ceremony capabilities, one-use claims, effect composition, guarded service construction, bounded presentation store | Depends inward on core; effects are explicit and resource ownership is documented. |
| `spoonbill-security-jdbc` | PostgreSQL storage, host enlistment, blocking execution, locks, SQL error classification, optional completion-material storage | Depends on core/runtime SPI; JDBC types stay here and in host JDBC integration. |
| Effect and transport adapters | Native effect finalization, HTTP/WebSocket adaptation, entropy/clock/crypto integrations | Depend inward; never imported by core. |
| Examples and conformance fixtures | Synthetic host domain and complete integrations | Consume supported public APIs, with trusted SPI use limited to provider/host integration code. |

The extraction covers the existing security decision types used by this feature and separates mixed model/runtime files. It must not leave duplicate definitions of generation, ownership, or transaction decisions. Preserve package names where useful; a major release allows package changes when they clarify ownership. Do not turn this into an unrelated rewrite of all server internals.

### 3.2 Definition of purity

For core code, identical immutable inputs produce identical outputs and do not change caller-visible state. Expected rejection is represented as data. Core transitions receive `now`, generated identifiers, authoritative records, and policy facts as explicit values.

Core must not:

- Read clocks, entropy, environment variables, system properties, thread identity, files, sockets, databases, or global registries.
- Allocate or mutate live atomic cells, locks, caches, executors, leases, or resources.
- Run effects, spawn work, sleep, log, acquire connections, or invoke effectful callbacks.
- Expose mutable arrays or collections whose later mutation changes a decision value.
- Perform work in constructors or singleton initialization that depends on the environment.

Example shape, illustrative rather than an implemented API:

```scala
def decidePreparation(
  ceremony: CeremonyRecord,
  slot: BrowserSessionSlot,
  host: HostAuthorityFacts,
  now: Instant
): Either[PreparationDenied, PreparationDecision]
```

A `PreparationDecision` is data. It does not prove that a transaction ran, grant executable authority, or authorize a later operation. A provider must perform the authoritative read/decision/write atomically and revalidate after waits where required.

Time monotonicity, expiry latching, entropy generation, cryptographic work, capability consumption, and concurrent publication belong to effectful interpreters. Pure tests supply their observations explicitly. Do not move a mutable object behind an abstract type and call the core pure.

### 3.3 Purity without allocation-heavy machinery

Use small immutable records, opaque identifiers, closed ADTs, and direct functions. A read-only validation should return the decision/facts it needs, not copy an entire session or presentation graph. Reuse unchanged values where appropriate.

Do not introduce a general command AST, free-monad interpreter, event ledger, persistent whole-world state, or an extra asynchronous hop for every check. Functional caller code can compose existing `F` and transaction-local `G` directly. Runtime implementations may use encapsulated mutation and atomics for ownership and performance; those mechanisms stay outside core and remain covered by concurrency tests.

Enforce the boundary with build-graph checks, focused forbidden-API checks, and source review. Import scanning alone is insufficient: effectful calls hidden behind helpers must also be excluded. Property tests establish deterministic transitions and invariant preservation; they do not replace dependency inspection.

## 4. Public API and functional caller experience

### 4.1 Small application-facing surface

Keep ordinary typed `Actions`, `UiOutcome`, sensitive regions, and snapshot APIs. The coordinator exposes only:

| Operation | Meaning |
| --- | --- |
| Construct/validate configuration | Purely validate origins, realm, namespace, cookie settings, resource limits, and provider compatibility facts. |
| Acquire runtime | Allocate owned runtime resources in `F`, with explicit release through existing resource conventions. |
| Begin a ceremony | Capture current server-resolved browser lineage and generation before proof verification. |
| Resume a ceremony | Resolve an opaque lookup handle through fresh connection/view authorization without refreshing original authority. |
| Complete | Convert a committed delivery receipt to the existing completion UI outcome; never accept a bearer token or raw UUID. |
| Configure/build guarded service | Install one authority and its related contracts together. |

Acquisition of resources is separate from pure configuration. Constructors must not start timers, open connections, allocate externally registered resources, or run callbacks. Release is idempotent and owned by the enclosing application resource scope.

Use inference and provider-associated types to hide `G` and `Tx` from ordinary action handlers. Do not require application handlers to pass realm, binding digests, generations, owner epochs, or JDBC connections. Those details belong at the provider/host boundary.

### 4.2 Effects and transaction programs

Retain the distinction between outer application effect `F` and joined transaction program `G`. A provider associates its transaction type with its own instance; transaction-local capabilities also carry a live scope identity. Compile-time separation and runtime scope checks work together.

- JDBC may use the existing `Direct` transaction program inside one configured blocking operation. Do not allocate one `Future` or schedule one task per SQL statement.
- A native asynchronous provider composes all operations in `G`, with native finalizers and serialized transaction access.
- `map`, `flatMap`, and a for-comprehension are sufficient for host integration. Add only the minimal syntax absent from existing abstractions.
- Ordinary domain rejection is a typed result. Transactional rejection must abort the whole transaction; a `Left` returned as an ordinary successful transaction value must not accidentally commit partial writes.
- Infrastructure exceptions are translated at interpreter boundaries into stable errors. Diagnostic causes stay in trusted reporting code and contain no credentials.
- No caller-facing `unsafeRun`, detached fibers, `Await`, callback completion plumbing, manual commit flags, or unconditional retry loops.
- Preserve support for eager outer effects without claiming they become lazy. Work requiring scope ownership must be constructed inside the runner's protected callback. Use a lazy native effect in the principal functional example and exercise the existing Future semantics separately.

The framework owns browser invariants; the host retains its domain model. `Principal`, account identifiers, policies, challenge prompts, and authentication results need no Spoonbill inheritance hierarchy. A small host-provided opaque subject/policy binding may associate ceremony evidence without exposing account structure.

Keep the provider SPI semantic and small. Proposed operations below describe contracts, not final method signatures:

| Provider operation | Effect/atomicity boundary |
| --- | --- |
| Capture/resume ceremony | `F`; current binding/view checks and bounded ceremony registration, preserving captured facts. |
| Prepare completion | Joined `G` with the provider's scoped transaction; host proof/session/audit and browser/material writes commit together. |
| Reconcile preparation | `F` through an authoritative operation excluding earlier writers; returns disposition and, where justified, restricted delivery eligibility. |
| Authorize connection/view | `F`; one coherent current-authority observation, with typed principal and fence. |
| Retrieve delivery material | `F`; current bound authorization and bounded transport-only secret retrieval. |
| Activate/acknowledge or logout | `F`; each is an atomic authority transition, with explicitly different replay rules. |
| Claim/release/invalidate a view | `F`; conditional ownership changes with publication fences and bounded resource consequences. |

Implementations can combine physical storage work when it preserves these semantics. Do not expose a generic CRUD/query API, require callers to sequence unchecked reads and writes, or expose raw credentials in generic return values. The complete example determines whether any of these operations can remain internal to the supplied provider.

### 4.3 Complete compiling examples before API freeze

Create proposed examples `examples/browser-auth-jdbc` and `examples/browser-auth-memory`, registered explicitly in sbt and CI. The JDBC example is the main integration/performance reference; the memory example proves atomic non-JDBC composition, not production durability.

Each must include all of the following, without placeholder helpers. Restart behavior differs explicitly: the durable JDBC integration recovers committed delivery after restart; the memory integration loses its process-local state and fails closed into fresh login. It must not simulate durability by silently preserving an old process's authority.

- Pure configuration and presentation policy, runtime resource acquisition, and official service setup.
- Account lookup, off-transaction proof computation, transactional current-policy checks, proof consumption, session issuance, completion preparation, and audit/outbox writes.
- Password-only and challenge/resume handlers using ordinary functional composition.
- An existing host transaction runner integrated without nesting another transaction.
- Complete denial, expiry, rollback, unknown-commit, recovery, and storage-failure handling.
- Logout, reconnect, missing-view recovery, a protected HTTP page, a protected action, and explicit sensitive-disclosure policy.
- Host rate-limit enforcement before expensive proof computation, using synthetic policy and data.

Compare the reference host's domain code before/after separately from its integration code. Count all production helpers wherever they are moved. The example is accepted only when a reader can see the complete integration and the production-code delta is non-positive.

### 4.4 Prevent conflicting security installation

Replace independent optional security overrides in the guarded path with a validated, opaque security installation. After validation, a caller cannot copy a public configuration record to replace only the authority, completion handler, or presentation store. Final service construction revalidates the bundle identity and any dependent settings before serving requests.

Ordinary public applications remain possible. Advanced guarded applications may use a documented lower-level bundle constructor, subject to the same consistency checks. Sensitive disclosure remains separately opt-in and default-deny.

### 4.5 Scala first, with a minimal browser adapter

Prefer server-side Scala for every decision that can be made there without adding a browser round trip or weakening browser-local safety. Pure decisions belong in `spoonbill-core`; effectful orchestration belongs in the Scala runtime/providers. The browser interprets a small typed protocol and reports observations. Applications implement the complete reference authentication flow in Scala, with no custom JavaScript, `evalJs`, or per-application browser authentication plugin.

The current client already delegates credential authorization and cookie construction to Scala. Its `connection.js` performs the HTTP completion handoff and reconnect, while `bridge.js` and `sensitive.js` enforce browser-local ordering and disclosure cleanup. Treat those responsibilities individually; their presence does not mean authentication policy is implemented in JavaScript.

| Responsibility | Planned home |
| --- | --- |
| Ceremony state, account/challenge association, proof admission, retry eligibility, transaction/recovery decisions, authorization and revocation | Pure Scala decisions plus effectful Scala runtime/provider interpretation. |
| Completion eligibility, origin/binding validation, credential custody, cookie attributes, and activation rules | Scala runtime/provider and HTTP adapter. HttpOnly credentials never become JavaScript values. |
| Presentation state, navigation intent, typed actions, rendering decisions, and user-facing authentication outcomes | Scala application/runtime; send compact existing protocol commands. |
| Wire command identifiers and stable payload definitions | One Scala-owned definition where practical, generating only the small client constants/shape data needed by the interpreter. Generation runs in the build, outside the pure core. |
| DOM/event operations, browser location/history effects, physical WebSocket/fetch calls, local cancellation/timeouts and bounded delivery retry mechanics | Minimal browser adapter; no account policy or independent ceremony state machine. |
| Stale-socket/frame rejection, callback suppression during partial DOM patches, immediate sensitive clearing on departure/disconnect/expiry | Browser-local checks remain mandatory; Scala supplies and validates the corresponding authority/protocol state. |

In Phase 0, inventory the production sources under `modules/spoonbill/src/main/es6` and any reference-application scripts. Classify each authentication/protocol responsibility as Scala-owned, necessarily browser-local, duplicated, or removable. Identify concrete deletions before claiming the coordinator reduces JavaScript. Do not invent substantial migration opportunities when the existing client already delegates a decision correctly.

Remove duplicated policy, per-application glue, and independently maintained protocol definitions. Prefer direct Scala APIs and existing wire commands over shipping more script text or adding a generic JavaScript evaluation mechanism. Generate tiny protocol tables only when that removes duplication without increasing delivered size or runtime costs. Generated JavaScript still counts toward the delivered client surface; embedding it in Scala strings does not count as removing it.

Keep browser defense checks, connection-local fences, input/payload bounds, and local cleanup even when the server also validates them. Browser-local event ordering cannot be replaced by a server acknowledgment without evaluating the extra delay and disconnected behavior. Preserve the bounded same-origin completion exchange and fresh physical handshake; do not add HTTP actions or extra round trips to move a few lines into Scala.

Scala.js is not required for this work. Moving handwritten code to Scala.js would change the source language while still delivering browser JavaScript; it does not by itself reduce browser responsibility, bundle size, latency, or memory. Any later proposal to use it needs a separate concrete comparison under the same gates. The default implementation uses server-side Scala and the existing small browser runtime.

Acceptance evidence must report handwritten production client lines/modules, authentication/protocol branches and duplicated definitions removed, application-specific authentication JavaScript, and emitted/minified/compressed bundle bytes. Do not count tests as production, omit generated output, or use formatting changes to manufacture a reduction. Require zero custom authentication JavaScript in the reference applications, no new domain/authentication policy in the client, and no net growth in handwritten authentication glue. Remove all audited duplication that can be eliminated while preserving the other gates. A claimed reduction must identify actual removed responsibilities/source; indispensable browser-local code is documented rather than deleted to meet a quota. If the inventory finds no safe reduction in a component, report that result explicitly as preservation of its minimum surface, not as a reduction.

Measure browser download/startup, parse/compile/execution, event-to-render and login latency, allocation/retained memory, and HTTP/WebSocket exchanges against the same baseline. Shifting client work to the server must also pass server latency/memory gates. The emitted authentication-related client and reference-application bundle must not grow; generation or a new abstraction cannot hide a runtime regression behind fewer handwritten lines.

## 5. Ceremony and authority model

### 5.1 Data versus live capabilities

| Type/category | Persistable? | Authority |
| --- | --- | --- |
| `CeremonyId`, completion ID, recovery reference | Opaque lookup metadata may be persisted or presented where permitted | Never sufficient to authenticate, activate, execute, or recover a credential. |
| `CeremonyRecord` | Necessary non-secret facts may be durable, bounded, and expiring | Input to decisions; fresh authorization is still required. |
| `BrowserCeremony` | Live capability; no UI serialization | Runtime/provider-issued, scope checked; aliases cannot bypass consumption. |
| Transaction-local pending completion | No escaping serialization or promotion by application code | Valid only inside its owning provider transaction scope. |
| Committed delivery receipt | Non-secret metadata can have a durable counterpart; live wrapper is runtime-issued | Enables a bound delivery workflow only after confirmed commit and current checks. It cannot execute proof-consuming work. |
| `Principal` plus authority stamp | Principal may be ordinary immutable host data | An observation at one boundary, never a durable authorization permit. |

Opaque IDs, private constructors, and provider-dependent types prevent accidental misuse. Trusted third-party providers get a narrow explicit SPI. The library cannot prove a malicious provider committed; conformance and the documented trust boundary are mandatory.

### 5.2 Ceremony invariants

Capture binding, realm, namespace, lineage, generation, purpose, absolute expiry, and original ceremony identity before any credential verification. Every use requires current connection/view authorization. Reconnection may replace the live connection capability but cannot refresh captured facts.

When the host selects a subject and creates a challenge, bind that subject, proof/challenge reference, relevant credential/policy version, and purpose to the ceremony. Later submissions cannot switch accounts, transplant a challenge, or reuse proof evidence from another ceremony. The host defines these values; the framework/provider enforces that the registered association is immutable and current when consumed.

Expensive password/factor computation occurs outside a long-lived transaction. Its result does not exempt the host from checking account status, credential version, factor state, and policy in the final authoritative operation. A password reset or policy change during computation can invalidate the result.

Define explicit states and transitions for begun, challenged, preparation admitted, committed, expired, rejected/consumed, and unresolved settlement. State labels are descriptive; exact commit disposition belongs to the transaction protocol. Keep challenge business models outside the core. Publish transition tables with permitted replay behavior before implementation.

Multiple ceremonies may capture one browser generation. Preparation alone does not silently install a session or refresh the generation. Preserve current slot semantics: the first valid committed activation advances the generation; competing preparations from the older generation cannot replace it. Repeated activation of that same still-current session is idempotent. Logout advances/fences the slot and invalidates all pending work based on the old generation.

### 5.3 Admission and abuse bounds

Bound ceremonies globally and per browser/host policy scope, challenge records, pending preparations, recovery observations, and delivery material. Rate limits remain host policy, invoked through explicit boundaries. Capacity errors are typed and fail closed before unbounded allocation or expensive proof work wherever possible.

Retiring presentation or expired ceremony metadata must not revive old proof evidence, IDs, generations, or replay fences. Define durable retention separately from disposable cache limits. Counter exhaustion fails closed. Never reset counters when deleting rows or recreating a browser lineage.

## 6. Transaction preparation, settlement, and recovery

### 6.1 One atomic host operation

The successful preparation transaction contains current host-policy validation, one-use proof consumption, host-session creation/preparation, browser preparation, completion material registration, and required transactional audit/outbox writes. Network calls, browser interaction, and slow proof computation stay outside it.

Recheck captured generation, binding, purpose, expiry, selected subject, and current owner where required under authoritative locks or equivalent conditional validation. Decisions protected by a wait use fresh observations/time after the wait. Any rejection rolls back the entire unit.

Reuse the existing `TransactionExecutor` and `TransactionProgram` settlement semantics. Apply the scoped registration/promotion pattern of `PendingPreparation` to a browser-specific pending completion. Do not route login through the existing durable prepare-then-execute protocol if that would introduce another transaction, ledger, or proof-consuming operation. Browser preparation is itself the atomic host mutation being committed.

### 6.2 Existing host transaction runners

Provide two implementations of the same contract:

1. A supplied outer runner for applications that want provider-managed transaction resources.
2. An enlistment adapter for an existing host runner that joins its native transaction program and receives actual outermost settlement.

The enlistment adapter must register pending results while the transaction is live, join all work, and promote them only after acknowledged outer commit. It must observe rollback and unknown disposition and revoke transaction-local access on finalization. A `markCommitted(Boolean)` callback supplied by an ordinary use case is not an acceptable contract.

The coordinator never independently commits, rolls back, closes, or retries an enlisted transaction. A nested savepoint release is not outer commit. If the host runner cannot expose reliable settlement and resource ownership, the provider declares that integration unsupported and guarded configuration fails explicitly.

### 6.3 Settlement table

| Observation | Result and permitted next action |
| --- | --- |
| Acknowledged outer commit | Produce a committed delivery receipt; delivery still checks expiry and current authority. |
| Acknowledged rollback/rejection | Report the actual typed disposition; no pending receipt is promoted and no same-attempt proof replay is allowed. |
| Commit failure with uncertain outcome, failed rollback, or observer disappearance | Record/report unresolved disposition where observable; retain the original recovery identity and reconcile. Do not infer rollback. |
| Commit acknowledged, then response lost or process dies | Reconcile the same durable identity, then recover only the delivery workflow if still authorized. |
| No outcome row found | Remain unresolved until the provider excludes earlier writers and commits a safe negative fence, or observes their committed outcome. |
| Delivery or activation later denied | Do not rewrite the earlier transaction as rolled back. Report current expiry/revocation and follow the fresh-login path. |

Assign the recovery identity once in the ceremony/challenge lifecycle, before dispatching the proof-consuming transaction. For durable providers, reuse the bound durable ceremony/challenge record and its stable server-assigned completion-attempt identity so reconnect or restart can locate the same attempt without submitting credentials again. Concurrent submissions cannot replace that identity or mint a second admitted completion attempt. Do not introduce a separate preparation ledger or mandatory extra transaction for this registration. Any required write/transaction must be explicit and counted in the equivalent Phase 0 baseline. An opaque browser lookup handle alone does not authorize this lookup's sensitive consequences.

Recovery never reconstructs an execution authority or repeats the original host callback. It may reconstruct a restricted delivery receipt only after authoritative committed-status lookup, current browser binding, purpose, host policy, and expiry checks. Keep this API distinct from one-use operation reconciliation, which returns execution status only.

### 6.4 Cancellation and resource safety

Preserve the existing difference between native transaction cancellation and cancellation of an outer observer. A blocking worker may still own a live transaction and may commit after its observer disappears. Never force early pool return, report rollback from observation cancellation, or replay a lazy transaction program.

Guard callback construction and actual evaluation against repeated execution. Use native finalization for asynchronous `G`. JDBC runs entirely on the configured blocking executor, returns connections only after safe settlement/abort, and preserves unresolved-disposal diagnostics. Tests must include rollback/abort/close failures and commit acknowledgment arriving after expiry.

### 6.5 Provider atomicity capability

Describe the actual atomic domain in provider configuration: which proof, host session, browser, completion, audit/outbox, replay, and recovery records share the operation. An interface implementation or matching database product name is not evidence of shared atomicity.

PostgreSQL uses one connection and a documented isolation/locking protocol. The memory example stages host and browser changes in one isolated transaction state and publishes them atomically; rejection discards all staged changes. It must not invoke external effects inside a retryable compare-and-set loop. A remote service qualifies only if it implements the complete atomic boundary. Unrelated stores are unsupported without a separately specified protocol; distributed transaction work is excluded.

## 7. Credential custody and completion transport

### 7.1 Responsibilities

| Responsibility | Owner |
| --- | --- |
| Generate cryptographically strong session/binding credentials | Trusted runtime/provider issuer using injected infrastructure. |
| Bind credential digest, host session, ceremony, browser lineage, purpose, and expiry atomically | Persistence provider joined to the host transaction. |
| Retain recoverable delivery material | Host-native completion store, or the supplied protected JDBC completion store. |
| Manage encryption keys and deployment rotation policy | Application infrastructure; raw keys never enter the pure core or UI configuration. |
| Authorize retrieval and emit cookies | Runtime completion service through provider operations and the official HTTP adapter. |
| Expire, erase, bound, and audit delivery material | Provider, with explicit retention and cleanup behavior. |

Preserve the 43-character base64url transport credential contract for this work. Use an issuer with sufficient cryptographic entropy; format validation does not establish entropy. Supporting arbitrary existing encodings remains a separate design decision.

### 7.2 Storage choices

Support a host-native completion implementation when the host already stores protected recoverable material. The supplied JDBC implementation must also offer a documented protected delivery-material path so a normal application does not invent an encrypted-secret translation table. Both implement one semantic contract; an application selects one path.

Use established authenticated-encryption primitives through an explicit infrastructure adapter if encryption is required. Specify key identity/rotation, associated binding metadata, expiry, access control, redaction, and bounded plaintext lifetime. Choose any additional crypto dependency through the managed build/Nix workflow and review it before API freeze; do not implement a custom cryptographic scheme.

Persist only credential digests in authority/session records. Protected recoverable material exists solely for bounded delivery and may not appear in ceremony presentation, snapshots, ordinary result caches, exceptions, or logs. Do not promise erasure of immutable JVM strings; minimize plaintext copies and lifetime, use erasable buffers where practical, and document unavoidable transport representations.

Recovery after commit-before-crash must retrieve the same prepared credential while delivery remains valid. Missing/unavailable keys or material cause a typed failure and fresh-login/recovery policy, never fabrication of a replacement session or repetition of proof consumption.

### 7.3 Delivery, activation, and acknowledgment

Retain the small same-origin HTTP exchange for HttpOnly cookie delivery. All proof submissions and ordinary interactions remain on WebSocket. Apply exact origin, cookie binding, realm, namespace, purpose, expiry, and current authority checks. A completion handle alone is insufficient.

Specify bounded idempotent redelivery, repeated activation, and acknowledgment separately. After acknowledged activation, retire delivery material according to an explicit retry/cleanup rule; a delayed retry cannot replace the active session. Existing sockets retain captured identity. Only a fresh physical handshake may activate or capture a new session.

An HTTP response can race with logout and install a stale cookie after its authorization check. Activation must reject that cookie; do not claim an HTTP response can be atomically rolled back with logout. The browser takes the explicit fresh-login/reload path after denial.

## 8. Coherent authorization and presentation ownership

### 8.1 One semantic authority check per boundary

Provide an operation that atomically/coherently validates captured connection identity, browser session or anonymous lineage, current host policy, slot generation, and view owner/epoch. Return the current principal when authenticated plus a non-secret authority stamp and view fence.

The stamp must distinguish session, subject, scope, security generation, and relevant host policy version. The host supplies the semantic comparison where its model requires it. Comparing user IDs or generic object equality alone is insufficient. An action running with a previously resolved principal must be denied or have its policy reevaluated against fresh facts when relevant authority changes; a cached role must not authorize later output.

Share resolution only within one uninterrupted authoritative boundary. Admission, post-effect output, sensitive delivery, snapshot access/publication, and business mutations remain distinct boundaries. Do not add a TTL principal cache or reuse a successful pre-async check after an asynchronous gap.

For JDBC, use one short transaction/connection for each coherent check. Document the complete lock order, including one-use identity/recovery fences before host locks when those are present, then host → browser slot → view. Every writer, revoker, and reconciler obeys compatible ordering. Preserve fresh reads after waits. Count statements, frontend executions, wire exchanges, pool acquisitions, and transactions separately.

Domain writes still authorize within their own authoritative transaction. Suppressed output cannot undo a committed effect. Denial must have a defined safe public presentation/reload outcome without restoring revoked state.

### 8.2 Ownership-fenced presentation store

Use the existing view identifiers/epochs and one authoritative ownership rule for acquisition, publication, mutation, invalidation, and release.

| Event | Store rule |
| --- | --- |
| Initial bootstrap | Allocate bounded transient state under a server-issued identity; cleanup covers abandoned HTTP bootstrap. |
| Ordinary disconnect | Retain only non-sensitive presentation within explicit reconnect TTL and capacity. |
| Valid takeover | Atomically attach the new owner/epoch; old-owner writes, publication, and close cannot affect the successor. |
| Authorization loss | Permanently detach the matching revoked manager; no stale reference can restore it. |
| Invalidation racing acquisition | A single serialized/conditional decision determines the winner; any new authorized presentation is built fresh. |
| Delayed setup result | Publish only under a still-current claim; otherwise release the result exactly once. |
| Expiry/capacity eviction | Remove disposable state and metadata together; follow a defined fresh-view/reload path. |
| Storage outage | Fail closed and release local acquisition resources; never interpret an error as a valid absent record or anonymous authority. |

Fence reads and writes, not only cleanup. An authoritative read followed by an unprotected local publication is insufficient. Specify how the provider ownership decision and local manager registry are coordinated at each publication boundary, and test an interleaving that revokes between them. Do not imply cross-node atomicity from an in-process lock.

Bound active views, disconnected views, bootstrap entries, pending acquisitions, callbacks/waiters, and auxiliary metadata, globally and per relevant scope. Active-capacity exhaustion rejects new acquisition by default; eviction of an active view, if supported, requires an explicit revocation/release policy. Disconnected eviction may discard disposable presentation, but durable fencing history is retired only under a proved safe rule. Otherwise fail closed at a documented limit.

Local TTL is not distributed owner reclamation. The initial provider must document single-node assumptions or its authoritative cross-node lease/fence policy, including crash and partition behavior. Recovery must not resurrect old managers. Unknown resume IDs cannot allocate durable ownership rows.

### 8.3 State manager performance

Change both object-backed and serialized node reads to direct lookup. Cost must depend on the requested value, not on the number of unrelated nodes. Serialized reads defensively copy only that node's bytes. Explicit snapshots may copy all nodes and retain their isolation contract.

Keep immutable-state expectations for object-backed values; never expose mutable storage internals as an optimization. Test node-local copying and mutation isolation. Use deterministic traversal/copy counters plus allocation measurements across node counts; timing alone does not prove lookup complexity.

Sensitive payloads never enter reconnect storage. Snapshot persistence remains separately selected, with its own current identity/owner checks and schema. Every eviction, release, failure, and shutdown path must clear sensitive runtime ownership and release resources.

## 9. Official adapter and dependency graph

Build and test a native Scala 3 Pekko adapter dependency graph. Start from the declared repository versions and inspect availability/compatibility during implementation; this plan does not claim a tested minimum pair. Pin the supported minimum and upgrade matrix before release.

Keep production and test dependencies on the same artifact variant. Verify generated POMs and fresh downstream resolution, including an application already using native Scala 3 Pekko actor/persistence modules. Do not substitute `_2.13` and `_3` binaries through exclusions.

Use the major release to change the adapter's graph or artifact organization cleanly. Preserve transport setup ownership, backpressure, finite terminal responses, cancellation, configured reporters, and exactly-once release. Existing typed/classic actor-system interoperability should remain ordinary documented setup.

Trusted network metadata belongs in route/configuration wrappers. Strip browser-supplied copies before adding server-derived values consistently for HTTP and WebSocket. Add a transport hook only if the existing extension points cannot express a demonstrated need. Network metadata is policy input, not authentication proof.

## 10. Correctness and conformance verification

Create a reusable provider conformance suite with deterministic barriers, explicit clocks, controlled entropy, and injectable settlement/storage failures. Run the common authority/atomicity suite against the non-JDBC provider and real PostgreSQL, not only mocks. Add a durability capability and separate restart-recovery cases: durable providers must preserve/reconcile committed records and delivery material; the memory example must discard process-local authority and require fresh login after restart. A provider cannot advertise durable recovery while skipping those tests.

| Area | Required cases |
| --- | --- |
| Pure core | Determinism, total typed rejection, immutable input preservation, generation/epoch monotonicity, transition laws, dependency purity. |
| Types and installation | Mixed IDs, raw UUID completion, foreign provider transactions, closed scope, uncommitted receipt use, conflicting guarded configuration, absent atomicity capability. |
| Ceremonies | Password-only, multistep resume, fixed original generation/expiry, subject substitution, cross-ceremony proof transplantation, duplicate factor submission, concurrent ceremonies. |
| Policy freshness | Logout/password reset/account disable during hashing, lock waits, queued/running actions, output, sensitive delivery, and snapshot publication. |
| Transactions | Atomic proof/session/browser/material/audit commit; rollback at every stage; commit-before-observer-loss; commit uncertainty; failed rollback/abort; cancellation; native lazy program reevaluation; nested runner misuse. |
| Recovery | Restart before/after dispatch and commit; absent record with live writer; negative fence versus late writer; same recovery identity; no reconstructed execution authority or second proof consumption. |
| Credential delivery | Wrong binding/origin/realm/purpose, redelivery bounds, stale/expired handles, key/material outage, restart retrieval, erasure, stale cookie after logout, unchanged old-socket identity. |
| Ownership | Takeover then old close/write/read, late setup publication, invalidation/reacquisition race, missing view, anonymous fencing, HTTP bootstrap abandonment, no state resurrection. |
| Resources | Every capacity/TTL, cleanup retry, cancellation during acquisition, shutdown, churn plateau, bounded tombstones/fences or explicit fail-closed retirement behavior. |
| Transport | Existing v3 lifecycle suites, setup timeout, terminal reload, backpressure, release on all exits, native browser cookie/reconnect flows. |
| Minimal browser adapter | Complete Scala-only application integration; protocol definition parity; malformed/stale commands; immediate clearing during lost connectivity; synchronous DOM callbacks; unchanged completion/reconnect semantics and round-trip counts. |
| Portability | One complete non-JDBC atomic host/browser operation, rejection discarding all staged writes, native asynchronous `G`, synchronous JDBC `Direct`, existing eager Future semantics. |

Compile-negative tests complement runtime checks; Scala types do not establish linearity, freshness, or actual commit. Pure-model property tests complement physical database concurrency tests; neither substitutes for the other.

Test refusal and recovery outcomes as well as successful UX. Browser fixtures use synthetic identities and real cookie/origin behavior. Keep the existing client/sensitive-disclosure tests and add an end-to-end reference application fixture; current mocked browser peers do not establish host authorization.

## 11. Performance contract: no latency or memory regression

### 11.1 Baselines and provenance

Retain two separate comparisons:

| Comparison | Baseline |
| --- | --- |
| Historical transport/lifecycle | 2.0.0 commit `0c6df154a185815d187b4f5d04aad3aab0570f8b`, with the existing identical-work fixture/adapters. Never move this historical baseline forward. |
| Feature and application integration | 3.0.0 commit `ab95a4fe65399814931c6ddd7f29dd68f33bbc5c` plus the frozen complete reference host integration implementing equivalent security behavior. |

Freeze the reference integration source and its production-code inventory before optimizing the candidate. Where the baseline requires custom fencing/storage/transport glue for equivalence, include and measure that glue. Never compare the candidate's secure path to a baseline omitting required checks, or improve only the candidate's host hashing/database setup.

Existing [performance results](websocket-performance-results.md) report inconclusive timing under the strict gate. They are prior evidence, not an inherited pass. Existing transport/core runners do not cover browser authentication, peak heap, steady retained memory, or all connection populations; add those fixtures.

Record commit/artifact hashes, dirty-diff identity if any, fixtures, reference data, flake locks, dependencies, JVM/GC flags, database configuration, browser versions, hardware, OS, concurrency, and completed-work assertions. A code or configuration change after measurement invalidates the affected result until rerun.

### 11.2 Required workload matrix

| Workload | Dimensions and observations |
| --- | --- |
| Public application with feature unused | Startup, steady events, idle footprint, and teardown; no hidden coordinator allocation on unused paths. |
| Cold guarded HTTP/bootstrap/WebSocket setup | Anonymous/authenticated, fresh/existing/missing view, process cold start separated from warmed operation. |
| Password-only and challenged login | Begin, proof computation, transactional preparation, completion exchange, fresh handshake/activation; phase costs and full user-visible latency. |
| Protected events and domain writes | Small/large presentation, cheap/expensive host policy, fixed concurrency and controlled contention; fresh authority and transaction checks preserved. |
| Reconnect and ownership takeover | Warm retained state, expired state, old-owner completion, repeated disconnect/reconnect, browser runtime work. |
| Logout, revocation, and failures | Active/pending session revocation, denied output, rollback, delivery failure, commit reconciliation, cleanup. |
| Node reads and explicit snapshots | Node counts 1, 100, and 10,000 with fixed requested-node size; separate value-size sweeps and object/serialized managers. |
| Lifetime and capacity | Idle populations, acquisition cancellation, session churn, all configured capacity boundaries, expiry cleanup, shutdown, restart recovery. |
| Transport compatibility | Preserve historical echo/lifecycle cells; measure the official Pekko adapter and reference browser flow as additional cells. |

Freeze concrete application concurrency, payload sizes, proof parameters, capacity/TTL values, data cardinalities, churn duration, and contention schedules in the Phase 0 manifest. Baseline and candidate perform identical useful work and use identical security policy. Human factor-entry time is excluded from automated latency while the server/browser challenge round trips are measured.

### 11.3 Measurements

Measure p50/p95/p99 end-to-end latency, throughput at fixed concurrency, allocation bytes per completed operation, peak heap, steady retained heap after repeatable churn/quiescence, idle memory per connection/view, and total bounded metadata/resources. Include browser memory, bundle bytes, startup/parse/compile/execution time, and event-to-render latency where the feature changes client behavior, and process/native memory where an optimization could move costs outside the heap. Track HTTP/WebSocket exchanges so moving JavaScript into Scala cannot hide added round trips.

For storage, separately count pool acquisitions, transactions, SQL frontend executions/statements, actual protocol round trips, lock acquisitions/wait time, and cleanup/recovery operations. Existing JDBC probes count API executions; do not rename those counts as wire round trips. Start/end heap samples are not a measurement of peak or retained heap. Run invasive profiling and heap diagnostics separately from latency acceptance runs unless identical instrumentation and its costs are explicitly part of both variants' frozen protocol.

Measure short proof workloads as well as representative expensive hashing so hashing cannot mask coordinator overhead. Keep proof computation constant across builds. Inspect allocation/retention profiles to ensure closures do not retain principals, inputs, credentials, transaction handles, or entire presentations unnecessarily.

### 11.4 Exact decision rule

The allowed product-regression budget is **zero** for latency and memory. Measurement uncertainty is not an allowed slowdown.

For every required cell and metric, orient the candidate-minus-baseline change so a positive value means worse. For lower-is-better metrics with a nonzero baseline, use ratio change `candidate / baseline - 1`; reverse the orientation for higher-is-better metrics such as throughput. Use absolute changes for zero-baseline counts/bytes. Predeclare the estimator and simultaneous confidence procedure for the full metric family.

- **Pass:** the upper simultaneous confidence bound is at or below zero, calibration/provenance/completed-work checks pass, and deterministic resource/operation invariants pass.
- **Regression:** the lower bound is above zero, or a deterministic memory/resource/operation requirement fails.
- **Inconclusive:** the interval includes positive and non-positive changes, measurements are missing/unsupported, calibration is unstable, or workloads/provenance are not comparable. This blocks release under this plan.

Use the existing paired balanced-process method as the starting implementation: at least 30 independent candidate/baseline pairs and 30 baseline/baseline calibration pairs, 95% family confidence, and at least 20,000 bootstrap replicates. The added workload family requires an explicitly updated multiplicity calculation and enough replicates to resolve its adjusted tail probabilities; 20,000 is a starting minimum, not guaranteed adequate resolution for every matrix. Do not pool tail samples across processes in a way that hides per-run variance.

Phase 0 freezes sample counts, warmup/measured work, numerical calibration criteria, measurement-resolution limits, maximum run duration, and any valid staged-sampling rule before candidate results are inspected. If greater precision is needed, revise the protocol with a stated reason and retain previous results; do not repeatedly sample until a favorable interval appears. Identical noisy performance may remain inconclusive. Do not relabel that result a pass or silently loosen the user's requirement.

Keep all raw runs, including failures and JIT/GC diagnostics. Warm the same code path that is measured. No workload averaging, regression offsets, dropped tail metrics, or newer-baseline substitution. A feature cannot ship merely because total application code shrank or security tests passed.

### 11.5 Deterministic CI performance protections

Add assertions for direct node access/copy counts, statement/transaction budgets, no extra scheduling per JDBC statement, no full-map work on node reads, zero active resources after release, bounded registries under churn, and disabled-feature footprint. Freeze exact operation ceilings from the reference baseline before implementation; an unexplained extra database operation fails the gate even if timing is noisy.

Do not claim these checks prove wall-clock performance. Run controlled benchmark jobs separately from contention-heavy correctness CI. Memory and latency regressions require implementation changes and affected-workload reruns. Purity must be preserved while optimizing the runtime implementation.

## 12. Implementation phases and exit gates

### Phase 0 — Freeze the reference and measurement contract

Deliver the complete 3.0 reference host integration, synthetic fixtures, code inventory, JavaScript responsibility/removal inventory from Section 4.5, provider atomic-domain description, supported-effect targets, and performance manifest. Link immutable baseline artifacts and fill every measurement parameter identified in Section 11. Include logout, factor challenges, revoked-state invalidation, and commit recovery in baseline equivalence. Freeze handwritten/generated client accounting, built/compressed bundle sizes, and browser/server exchange counts.

Exit: the baseline compiles/runs through Nix, required behavior has deterministic tests, counters and raw-data capture work, and there are no unspecified acceptance thresholds. Do not begin broad API implementation with an unreviewed or behaviorally weaker baseline.

### Phase 1 — Prove the boundary and caller API

Implement a narrow end-to-end spike using existing transaction abstractions: ceremony capture, one atomic host preparation, acknowledged receipt promotion, delivery, and activation. Include challenge association, existing-runner enlistment, and commit-before-response-loss recovery. Build the non-JDBC atomic example at this stage to challenge SQL assumptions early.

Extract the minimal pure core and establish dependency checks. Compile a normal action handler and complete host/provider integration without custom application JavaScript. Record predicted and actual production-code removal, client responsibilities eliminated, operation counts, and allocations for the narrow path. Decide which shared wire definitions can be Scala-owned without introducing a general protocol generator.

Exit: ordinary caller code composes naturally in `F`/`G`; no raw authority plumbing leaks into handlers; core is pure; no nested transaction or second ledger is required. Reject or revise abstractions that merely rename existing application glue. API names remain provisional until this gate passes.

### Phase 2 — Deliver independent reuse improvements

Implement direct node reads and snapshot isolation tests. Validate native Scala 3 Pekko packaging and downstream resolution. These can proceed in parallel after Phase 0 without sharing auth-protocol edits.

Implement the ownership-fenced presentation store after its transition model and provider/publication boundary are reviewed. It is a concurrency feature with its own correctness and memory gate, not a mechanical cache change.

Exit: focused lifecycle/concurrency tests pass, published graph checks pass, direct reads are node-local, and relevant latency/memory cells pass. Keep changes independently reviewable; they do not certify the complete authentication feature.

### Phase 3 — Implement coordinator and provider contracts

Finish pure transitions, live capabilities, validated service installation, typed coherent authorization, JDBC host enlistment, protected completion delivery, restart reconciliation, and capacity/retirement policies. Factor shared settlement machinery where it reduces duplication without merging distinct execution/delivery authorities.

Complete common provider conformance tests against real PostgreSQL and the non-JDBC provider, durable restart recovery for JDBC, and fail-closed restart behavior for the memory example. Exercise the native asynchronous transaction implementation as well as JDBC/Future paths. Integrate the existing sensitive, snapshot, and transport boundaries without cached authorization shortcuts.

Exit: the entire correctness matrix passes; core dependency checks pass; error/settlement behavior is documented; supported provider/effect combinations have concrete evidence. Unsupported combinations fail configuration explicitly.

### Phase 4 — Migrate the reference integration and measure

Replace the old integration with one coordinator/provider path. Remove obsolete ceremony translation, copied transport, duplicate validators, custom revocable storage, and the client duplication identified in Phase 0. Keep historical comparison fixtures isolated from production dispatch. Retain the browser-local guards and lifecycle effects required by Section 4.5.

Recount production integration code, files, helpers, adapters, and public configuration points, including handwritten/generated JavaScript and built bundle sizes. Publish the before/after browser-responsibility table and removed client code. Measure every required cell against both applicable baselines. Investigate regressions or inconclusive results without reducing workload/security equivalence.

Exit: non-positive consumer production-code delta, complete functional and browser evidence, all latency/memory gates pass, and no unexplained additional database operations or retained resources.

### Phase 5 — Major-release readiness

Finalize versioning, artifact coordinates, package/configuration changes, SQL migrations, retention policies, minimum dependency matrix, and deployment guide. Generate and inspect POMs and resolve the published graph from a clean downstream fixture.

Exit: the release checklist in Section 15 is satisfied. Release publication and application deployment remain explicit actions after review. This planning document does not authorize production writes.

## 13. Files and work allocation

The following are intended implementation targets, not files already added by this plan:

| Workstream | Targets |
| --- | --- |
| Core extraction and decisions | New `modules/core`; relevant model definitions currently under `modules/spoonbill/src/main/scala/korolev/security`; corresponding pure tests. |
| Coordinator/runtime | New browser-auth runtime area under `modules/spoonbill`; existing `action`, `server`, `security/transaction`, and session lifecycle integration points. |
| Presentation | Existing `state/StateStorage.scala`, `state/StateManager.scala`, and session acquisition/publication paths, with focused tests. |
| JDBC/provider conformance | `modules/security-jdbc`, provider-neutral conformance fixtures, and native-effect transaction tests. |
| Adapter | `interop/pekko`, `build.sbt`, downstream resolution fixtures, and supported dependency documentation. |
| Minimal client | `modules/spoonbill/src/main/es6`, Scala frontend protocol definitions, and `project/JsUtils.scala` only where the inventory establishes a necessary change; browser lifecycle/parity and bundle checks. |
| Reference apps | New JDBC and memory examples, complete synthetic host schemas/policies, and explicit sbt/CI registration. |
| Performance | Existing `misc/websocket-performance` and performance support; new auth/browser/churn/heap fixtures and frozen manifests. |
| Release documentation | New major-version migration guide, provider SPI/retention documentation, benchmark report, and release notes. |

Core/API and transaction work share a contract owner. Adapter packaging and node-read work can be delegated independently. Presentation-store and coordinator teams agree on publication/ownership linearization before editing shared session paths. Review each workstream against the same baseline and avoid unrelated cleanup.

## 14. Reproducible validation commands

All environment-dependent commands run from the repository through its `flake.nix`. Do not install host tools or bypass Nix. If a required profiler, dependency-lock generator, or validation tool is missing, add it through an approved managed configuration change; do not improvise a host installation.

The following commands exist at the baseline and are implementation validation inputs, not checks performed when writing this plan.

### JVM compilation and correctness

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

The wrapper starts a disposable loopback PostgreSQL instance and supplies `SPOONBILL_JDBC_TEST_URL` and `SPOONBILL_JDBC_TEST_USER`. No production database or credentials are required. Preserve actual database tests; a silently skipped suite is not a pass.

The root aggregate currently excludes `pekkoHttpExample`, `integration-tests`, and `performance-benchmark`. Compile them explicitly when their dependency/API surfaces are affected:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'pekkoHttpExample/compile' 'integration-tests/Test/compile' \
  'performance-benchmark/Test/compile'
```

Compilation does not run their external integration or benchmark programs. Add new `core`, conformance, and reference-example projects to the aggregate or explicit CI commands when created. Add named focused suite commands to each implementation change so reviewers can reproduce its behavior tests.

### Browser and deterministic performance checks

```sh
nix develop --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs \
  misc/performance-regression-tests.mjs
```

```sh
nix develop .#browser --no-write-lock-file --command \
  bash scripts/test-native-browsers.sh
```

The native browser gate runs on Linux with the flake-provided Chromium/WebKit environment. Add the auth reference-server fixture through the same managed mechanism; do not download browsers independently.

### Formatting and build-definition checks

The repository declares sbt-scalafmt. For implementation changes, run the applicable checks without reformatting unrelated code:

```sh
nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false scalafmtCheckAll scalafmtSbtCheck
```

Record any pre-existing failure separately. This is formatting validation, not a substitute for compilation/type checks. Add the core dependency/purity check and downstream POM-resolution test as explicit CI tasks during implementation; those tasks do not exist yet.

### Performance reproduction

Compile classpaths as documented in the [transport guide](../misc/websocket-performance/README.md) and [core-session guide](../misc/websocket-performance/CoreSessionBenchmark.md), using each baseline's pinned Nix environment. Then invoke the existing comparison/calibration/analyzer through Nix:

```sh
nix develop --no-write-lock-file --command node \
  misc/websocket-performance/run.mjs BASE_CP CANDIDATE_CP comparison.jsonl \
  30 1:128,1:4096,8:128,8:4096 comparison
nix develop --no-write-lock-file --command node \
  misc/websocket-performance/run.mjs BASE_CP CANDIDATE_CP calibration.jsonl \
  30 1:128,1:4096,8:128,8:4096 calibration
nix develop --no-write-lock-file --command node \
  misc/websocket-performance/analyze.mjs comparison.jsonl calibration.jsonl report.json
```

`BASE_CP` and `CANDIDATE_CP` are placeholders for generated classpath files. Use separate files for each baseline and suite; append `core` to collection commands for the existing core suite. The analyzer exits nonzero for an inconclusive strict gate. New auth/heap/browser runner commands must be checked in and documented during Phase 0/1 before the corresponding implementation is accepted.

For every meaningful check report the exact command, source identity, pass/fail/inconclusive status, short result, and next action. Do not repeat a passing broad suite unless new edits or unresolved concerns justify it. Documentation-only changes require link/structure validation, not the full application build.

## 15. Major-version migration and release checklist

Use the major release to remove invalid combinations and raw authority assembly from the ordinary path. Document all changed packages, constructors, settings, provider hooks, transaction integration, state-store behavior, and adapter dependencies. Keep lower-level extension points where they express a supported coherent contract.

Ship explicit SQL migration and rollback/deployment guidance. No request-time schema changes. Determine whether old/new writers share fence and schema semantics; if not, require draining old processes before cutover. Coordinate outstanding ceremonies/completions, cookie namespace/version policy, and active sockets. Conservatively expire/re-authenticate old sessions when safe coexistence cannot be established. Do not allow an old process to resurrect authority after rollback.

Document recovery from failed migration separately from application rollback, including key/material compatibility and retained negative fences. Consumer Nix users regenerate dependency locks with their supported generator before CI validation. Publication metadata must resolve without undocumented dependency exclusions.

- [ ] Pure core contains no side-effect implementation or runtime dependency.
- [ ] Complete functional examples compile; provider/host code is visible and counted.
- [ ] Existing-runner enlistment and commit-before-response-loss recovery work.
- [ ] Subject/challenge association, proof replay, and competing-ceremony rules are tested.
- [ ] Delivery custody, restart retrieval, redaction, expiry, and key failure are specified and tested.
- [ ] JDBC and non-JDBC providers pass shared conformance plus their physical atomicity tests.
- [ ] Ownership, invalidation, late publication, bounds, and retirement behavior pass concurrency/churn tests.
- [ ] Sensitive disclosures, snapshots, and v3 transport guarantees are preserved.
- [ ] Native Scala 3 adapter artifacts resolve and run in the supported downstream matrix.
- [ ] Consumer production integration code has no net growth.
- [ ] Reference authentication flows require zero custom application JavaScript; Scala owns policy/orchestration and identified removable client duplication is eliminated, with actual reductions and retained browser responsibilities reported separately.
- [ ] Browser-local safety/lifecycle checks remain intact; client bundle sizes and exchange counts do not grow, and browser/server latency and memory gates pass.
- [ ] Every required latency/memory cell passes the zero-regression gate; no missing or inconclusive metric is counted as passed.
- [ ] Raw results, provenance, operation counts, and resource/retention evidence are published with synthetic data.
- [ ] Migration, mixed-version deployment constraints, schema changes, and recovery procedures are reviewed.

## 16. Stop and redesign conditions

Stop the affected workstream and revise the design if the pure core needs I/O or mutable lifecycle machinery; the provider cannot share the required atomic domain; settlement depends on an application assertion; normal caller code still assembles raw authority; recovery recreates executable proof authority; or limits require unsafe deletion of replay fences.

A latency/memory regression blocks the feature. Inconclusive evidence blocks release until resolved under a valid measurement protocol. Do not trade away purity or security to make a benchmark pass, and do not relax the zero-regression requirement without a new explicit user decision.

For execution failures, follow the repository recovery policy: read the full failure, re-plan after two failed attempts on the same issue, and escalate with evidence if the first attempt after re-planning also fails. Keep unaffected work moving. Report unsupported infrastructure or unresolved design choices concretely, with the smallest decision needed to continue.

This document plans future implementation. Its completion means the plan has been written and reviewed; none of the feature or release checkboxes above are implicitly complete.
