# Authenticated session replacement: implementation plan

Status: implemented and ready for review. The latest
user-authorized performance repeat met all frozen budgets; the earlier memory
p95 breach remains recorded and its precise cause is unproven.
Updated: 2026-10-17 (plan created 2026-10-16).

For final code review, use the [review guide](authenticated-session-replacement-review-guide.md),
including the subsequent API/Scala 3 fixes and their separate validation boundary.

This plan expands the [proposal and reproduction](authenticated-session-replacement-proposal.md).
It describes a portable protocol extension, both provider implementations,
browser delivery and recovery, and successor-bound sensitive disclosure. Work
and preserves existing unrelated changes and the supplied regression.

## 1. Outcome and scope

An authenticated application can change a factor or recovery credential,
consume its required proof, revoke the predecessor host session, create a
successor session, and record its audit in the browser provider's atomic host
transaction. After acknowledged commit, the initiating browser receives the
successor credential through a restricted framework handoff. If settlement or
delivery is uncertain, recovery finds the original outcome without repeating
the mutation or proof consumption.

The predecessor loses ordinary authority at replacement commit. It never
regains authority while delivery, activation, or recovery is pending. The
successor gains ordinary authority only after its fresh transport activation.

In scope:

- Pure replacement decisions and provider-issued capabilities.
- Atomic preparation, delivery, activation, recovery, logout, and retention.
- The in-memory provider and PostgreSQL provider, including shared ownership.
- Action completion, coordinator, HTTP recovery, and browser bridge integration.
- A verified successor-to-host-completion lookup and reference one-use sensitive
  result integration, including recovery-code disclosure.
- Explicit schema upgrades, examples, documentation, correctness tests, and
  bounded performance measurements.

Outside scope: implementing production factor algorithms, choosing account
policy, adding account switching, changing unrelated reconnect behavior,
introducing a new transaction engine, or redesigning all authentication APIs.
Factor verification and account/session policy remain host responsibilities.
External notifications belong in the host's transactional outbox, not in the
preparation callback.

## 2. Verified starting point

The existing JDBC regression was run during proposal review using the
repository's Nix environment and disposable PostgreSQL. Exactly one test ran
and failed with `Left(Rejected(HostDenied))`; the predecessor remained authorized
and one host session remained after rollback. This is a confirmed unsupported
transition with atomic rollback, not evidence of unauthorized protected output.

Relevant implementation facts:

- `JdbcBrowserPersistence.prepare` invokes the host callback and then calls
  `current(connection, originalView)`. Revoking the predecessor makes that check
  fail, so the transaction rolls back.
- JDBC `recover` also calls `current`; `BrowserAuthentication.recover` checks
  ordinary view authority before entering the provider.
- `ActionDispatcher.authenticated` revalidates ordinary authority after an
  action returns. `Frontend` also authorizes ordinary output. A successful
  replacement receipt alone cannot pass those paths.
- `/auth/complete` delivers a cookie using a completion lookup ID and HttpOnly
  browser binding. The browser then reconnects to activate it. Delivery and
  activation are already separate operations.
- The memory provider publishes immutable host and browser state together, but
  intentionally provides no recovery across process restart.
- Browser provider completion IDs and host completion IDs are already separate
  values. The new path must preserve that distinction.

Keep the original failing scenario as evidence. When adding the explicit
replacement API, adapt the positive regression to that API and retain a
separate negative test demonstrating that ordinary preparation does not gain
replacement authority.

## 3. Required invariants

1. **Explicit transition.** A replacement starts from current authenticated
   authority. A purpose string, ceremony ID, caller Boolean, anonymous view, or
   fabricated receipt cannot convert ordinary preparation into replacement.
2. **Exact predecessor.** Bind the attempt to provider scope, browser binding,
   captured generation, predecessor host/session authority stamp, original view,
   physical owner/epoch, purpose, challenge, and deadline. Do not refresh those
   values after proof verification to evade a concurrent change.
3. **Same subject.** A successor has a new session ID and credential and the same
   immutable subject. Account switching requires a separate future protocol.
   Security and policy versions cannot move backward. The host validates the
   proof against pre-transition versions and derives legitimate successor
   versions inside the transaction; a factor change may intentionally advance
   them, so old challenge equality is not a successor authorization rule.
4. **Atomic host effects.** Host proof consumption, factor/recovery mutation,
   predecessor revocation, successor session, host completion/audit, browser
   preparation, pending replacement reservation, and recovery outcome commit
   together. Admission fencing can remain a preceding transaction, as today;
   it is not evidence that the host mutation committed.
5. **Fresh checks in the mutation transaction.** Earlier admission does not
   authorize later host work. Recheck the predecessor while it is still valid,
   serialize with host revocation, and validate browser fences again before
   settlement. After the callback, check the structural browser fences and the
   successor; do not demand renewed ordinary authority from the predecessor.
6. **Predecessor stays revoked.** Verify that the host no longer authorizes the
   predecessor after staging the mutation and revoke its framework credential
   record in the same transaction. Expiry, failed delivery, cleanup, or provider
   restart must never restore it.
7. **Restricted handoff.** A handoff authorizes one fixed framework completion
   operation. It cannot authorize rendering, transitions, application callbacks,
   protected actions, snapshots, subscriptions, downloads, or sensitive output.
8. **Known settlement.** Return a handoff only after acknowledged outermost
   commit. `CommitUnknown`, cancellation, or an interrupted observer grants no
   credential or ordinary authority. Recover the durable outcome instead.
9. **Recovery is not execution.** Recovering an attempt neither invokes its host
   preparation callback nor consumes another proof. Lookup IDs are metadata;
   authoritative binding, generation, outcome, and successor checks remain
   mandatory.
10. **Bounded resources.** Deadlines and capacity apply to attempts, reservations,
    encrypted delivery material, retries, and retained outcomes. Cleanup removes
    delivery authority without erasing replay fences or changing a committed
    outcome into `NotCommitted`.
11. **Successor-only disclosure.** Sensitive results require current activated
    successor authority. Plaintext cannot enter presentation, snapshots, logs,
    retained completion outcomes, or reusable callbacks.

## 4. Protocol and API design

Use existing `BrowserCapabilityScope`, transaction ownership, and effect
abstractions. The boundaries below describe the implemented responsibilities;
the concrete SPI and compatibility decisions are recorded in section 4.1.

| Boundary | Responsibility |
| --- | --- |
| Begin replacement | Resolve a current authenticated view and issue a distinct replacement ceremony bound to its predecessor, purpose, owner, generation, and deadline. |
| Bind/resume replacement proof | Preserve the original predecessor and deadline; a valid new owner can resume an uncommitted ceremony only through the provider's ownership checks. |
| Prepare replacement | Accept only a provider-issued replacement ceremony and the provider's instance-dependent `Tx => G[...]` host callback. Return a distinct restricted handoff after known commit. |
| Complete replacement | Turn a valid handoff into one framework control operation for the originating invocation/connection. No application-defined output is attached. |
| Recover replacement | Resolve an existing committed outcome through a transport-only request boundary, or fence an uncommitted attempt negative after excluding its writer. Never require a revoked predecessor to pass ordinary authorization. |
| Resolve successor completion | Under fresh activated successor authority, provide the verified host completion reference inside a provider-owned transaction callback. The reference is not an execution permit. |

Keep the ordinary sign-in API and authorization rules intact. Add explicit
replacement entry points and a distinct transition kind in authoritative
records. Reuse shared settlement interpretation only where it preserves the
different receipt types and checks. Avoid an `allowRevoked` or equivalent flag
on ordinary authorization.

Replacement ceremonies and handoffs must be opaque, redacted, issuer-bound
classes with restricted constructors. They must not contain application state,
principal callbacks, plaintext recovery codes, or externally reusable authority.
The handoff contains only the minimum identity needed for the provider to
validate its fixed completion operation. Durable records reconstruct a fresh
locally issued handoff after restart; process-local issuer identity is not a
durable authentication mechanism.

An action may return a dedicated replacement-completion outcome. Its dispatcher
path validates the handoff and invocation association instead of performing the
now-invalid predecessor post-action check. Every other outcome, including an
ordinary sign-in completion, keeps the existing checks. A copied, foreign,
expired, stale-owner, or wrong-invocation handoff must be rejected.

Do not implement this by accepting arbitrary completion UUIDs from application
actions. The transport eventually carries a non-bearer lookup handle, but the
server-side control path must retain the capability and its provenance until
the fixed completion frame has been admitted.

### 4.1 Implemented local contract

The optional `BrowserReplacementSupport[F, P]` boundary provides transport
recovery and fixed-control admission. `ReplacementBrowserPersistence[F, G, P]`
adds transactional preparation and verified successor completion lookup while
preserving the provider's instance-dependent `Tx`. Both built-in providers
implement it. Providers without the extension remain unsupported; ordinary
preparation never delegates to replacement.

`BrowserReplacementCeremony` and `CommittedReplacement` are distinct from the
ordinary ceremony and receipt types. `BrowserReplacementInvocation` copies a
framework invocation identity and non-secret authority/fence metadata, without
retaining its action binding, application callbacks, or credential-bearing view.
Admission records the exact preparation invocation, and receipt promotion must
use that same identity. Beginning and preparing a ceremony may occur in different
invocations; copied public invocation IDs do not recreate this association.

`BrowserReplacementRequest` is created only by the framework HTTP boundary.
Recovery returns `ActivateExistingCookie`, a request-bound `Deliver` capability,
`NotCommitted`, or `CommittedUnavailable`. The fixed completion control and
request-bound delivery cannot carry application-defined output. Their local
issuer/one-use checks supplement the provider's authoritative transaction checks.
Provider-facing fields are read-only metadata or transport-only cookie inputs;
none is an execution permit. Verified successor lookup exposes the host
completion and successor session IDs only inside the provider-owned callback.

The pure `BrowserReplacementAttempt` and committed reservation are decision
inputs, not capabilities or proof of settlement. A reservation retains its
committed disposition when delivery becomes unavailable. Both activation and
redelivery check the original bounded pending-completion deadline; accepting an
already-held successor removes only the material/budget dependency, not expiry.
Both providers implement reservation writes, ownership resume, activation,
logout, bounded recovery, and retained committed-unavailable outcomes. First
replacement activation retires predecessor view owners while retaining their
epochs; repeated activation preserves owners already held by the successor.

`UiOutcome.CommitReplacement` admits only the fixed completion frame. An
uncertain observer whose transaction actually rolled back can instead return
`UiOutcome.RecoverReplacement`; that outcome retains ordinary authority checks
and starts checked HTTP reconciliation. Procedure 26, `RecoverAuthentication`,
is generated through the existing protocol generator. Updated Scala consumers
must be recompiled and browser pages refreshed onto the new bundle before the
feature is enabled; binary compatibility and wire negotiation are not promised.

## 5. State, concurrency, and transaction rules

### 5.1 Replacement lifecycle

Keep settlement disposition separate from credential delivery availability.

| State | Permitted next operation | Ordinary predecessor authority |
| --- | --- | --- |
| Begun / challenged | Bind proof, resume under a current owner, admit, or expire | Valid only while independent ordinary checks pass |
| Admitted, mutation unsettled | One guarded preparation; recovery must exclude the writer before concluding failure | Determined by authoritative host/browser state |
| Committed, successor pending | Restricted completion delivery or recovery of the same outcome | Denied |
| Successor activated | Normal successor operations and verified successor completion lookup | Denied |
| Not committed | Start a new authorized ceremony if needed; never replay this attempt | Unchanged by the failed atomic mutation |
| Committed but delivery unavailable | Fresh authentication/reset flow; never repeat the committed mutation automatically | Denied |

An unavailable, expired, exhausted, or revoked delivery is not proof that the
host transaction rolled back. Represent or document that distinction in recovery
results without exposing another browser's attempt existence.

### 5.2 Slot reservation and competing attempts

Use one explicit pending replacement reservation per binding/generation. Bind
it to the attempt, predecessor, successor, purpose, and expiry. Acquire it in
the same transaction as the host mutation, checking availability before host
effects where lock ordering permits. A second replacement cannot commit for the
same predecessor. A stale or losing callback must have all effects rolled back.

For the initial design, preparation retains the captured slot generation and
predecessor pointer while adding the pending reservation. The predecessor host
and browser credentials are revoked at commit. The successor's activation
advances the generation once using the existing activation rule, replaces the
slot's current session, and retires the reservation and credential material.

All ordinary capture/claim/authorization paths must treat the pending
reservation as a restricted transition, never as an anonymous slot. Pending
successor credentials confer no principal before activation. Ordinary
preparations and competing prepared completions cannot bypass the reservation;
activation and delivery must check its exact attempt/successor association.
Pre-existing competing completions must be fenced while replacement is pending
and remain unusable after successor activation.

Logout advances the authoritative generation and invalidates pending delivery
and recovery. An administrative revocation of the successor also denies them.
An expired reservation may lose delivery material, but does not reinstall the
predecessor or allow an older competing completion to activate. Define a safe
binding reset through the existing logout/fresh-login boundary for this state.
If the slot model requires a durable terminal fence to enforce these rules,
retain it within existing bounded history rather than silently clearing it.

### 5.3 JDBC ordering and host revocation races

Preserve the documented order: realm admission, ceremony, host rows, slot,
view. Extend it consistently to reservation and delivery records; audit every
path that acquires those locks, including activation, logout, recovery, and
administrative retirement.

The preparation transaction must:

1. Read and lock the exact admitted ceremony and validate its transition kind,
   immutable predecessor binding, purpose, challenge, and deadline.
2. Resolve and serialize the predecessor host authority before mutation. Host
   row locks or a host version comparison must also serialize revocations made
   outside the browser provider. The provider realm lock alone does not do so.
3. Invoke the host callback at most once on the supplied transaction handle.
   The host consumes proof, applies its mutation, revokes the predecessor,
   creates the successor, and records completion/audit/outbox effects.
4. Resolve the staged successor, verify same subject, new session/credential,
   permitted versions and expiry, and verify predecessor host denial.
5. Lock and recheck slot generation, current predecessor association, exact
   view owner/epoch, deadline, and reservation eligibility. Use an internal
   structural check that cannot escape as ordinary authority.
6. Revoke the predecessor browser credential, install the pending replacement
   reservation and successor preparation, encrypt credential material, and
   persist the committed outcome/linkage together. Any refusal aborts all host
   and browser effects.
7. Promote the fixed handoff only after the outer runner acknowledges commit.

Predecessor host locks may need to precede slot/view checks to avoid inversion.
If a final structural check fails after the callback, host effects must roll
back and no callback may be retried automatically. Tests must distinguish
callback invocation count from committed effect count.

For the memory provider, run the equivalent decisions under its transaction
owner against both original and staged immutable state. Validate the original
authority before mutation and the successor/revocation against staged host
state. Publish the entire replacement atomically; do not reauthorize only
against the original host state after the callback.

## 6. Delivery and recovery without old application authority

### 6.1 Live completion

Add a dedicated admission boundary for the single replacement completion
control frame. Validate provider issuance, attempt disposition, connection and
invocation association, exact physical owner/epoch, generation, reservation,
deadline, and current successor host authority at that boundary.

The admitted value is the immutable framework instruction to deliver the
replacement credential. It cannot include DOM patches or an application
callback. Keep ordinary output admission in force for all other frames and
resources. Clear sensitive presentation and pending application sends, fence
the old application instance, and use the existing cookie-delivery/reconnect
mechanism where possible. A frame already admitted before a takeover may
arrive later under the existing output contract; the HTTP delivery still checks
the authoritative pending attempt.

### 6.2 Recovery after a lost command, reconnect, or restart

Provide a framework-owned, bounded HTTP replacement recovery operation before
ordinary application session loading. Do not route recovery through a public
action on a fabricated anonymous view or through `browser.recover(context, id)`
with the revoked view.

The recovery request uses the HttpOnly browser binding and a ceremony lookup
handle when available. Preserve only non-secret lookup metadata in bridge
recovery state; never store credentials or proof. If the command and lookup
metadata were lost, allow bounded discovery of the unique pending replacement
for the authoritative binding/slot. Do not enumerate arbitrary attempts.

The endpoint is intentionally not an application view or ownership grant.
Reconstruct only the restricted delivery operation after checking the durable
binding, generation, predecessor/successor linkage, pending reservation,
deadline, delivery budget, and successor host authority. Old cookies may be
recognized as transition metadata but never accepted as an ordinary session.
Existing application owners remain subject to their normal fences. A restarted
provider validates storage before issuing any new process-local capability.

Use the existing completion service's same-origin POST checks, strict bounded
input, unambiguous cookies, no redirects to foreign origins, and `no-store`
responses. Return no principal, application state, host completion ID, factor
metadata, or plaintext token in the response body. Credential delivery uses
HttpOnly `Set-Cookie` only. Denials must not reveal foreign attempt existence.

Handle reloads before activation with a minimal framework recovery bootstrap
when a valid pending replacement exists. It must not invoke the application
loader, restore protected snapshots, establish subscriptions, or render old
protected content. A page navigation cannot directly perform recovery as a GET;
the bootstrap performs a checked POST. If the request already carries the exact
valid pending successor cookie, validate its binding, reservation, generation,
deadline, and host authority, then initiate the fresh activation handshake
without redelivery or delivery-budget consumption. This path must remain
available after the final permitted delivery has removed credential material.
If the cookie is absent or still belongs to the predecessor, require the normal
bounded delivery checks before reconnecting. An unrelated or malformed supplied
credential is denied, not treated as a missing cookie. Neither bootstrap branch
grants application authority before activation.
For no eligible attempt, preserve the normal access-denied/fresh-login path.

Recovery of an admitted attempt must acquire the same durable ceremony fence
as preparation before returning `NotCommitted`. A read miss, request timeout,
database outage, or lost acknowledgment is insufficient. Repeated committed
recovery returns the same successor delivery identity within its budget and
does not refresh any deadline.

### 6.3 Activation and response-loss cases

On a new transport handshake, activation verifies the pending reservation,
successor host authority, binding and generation, acknowledges the host
completion, advances the slot, and removes credential delivery material
atomically. Repeated activation of the same already-current successor remains
idempotent. A supplied invalid predecessor credential never falls back to
anonymous authority.

Cover lost HTTP delivery responses both when the browser accepted the cookie
and when it did not. If the successor is already activated, ordinary successor
reconnect should succeed without redisclosing the credential or replaying the
mutation. If activation has not occurred, only the existing bounded delivery
may be retried when the browser still needs the cookie; a valid already-held
successor cookie can proceed directly to activation even with exhausted delivery
budget or removed material. Recovery must not mint a different successor.

## 7. Provider persistence and schema compatibility

Keep pure transition values and decisions in `spoonbill-core`; clocks, entropy,
transactions, encryption, and transport remain outside it. Keep JDBC types out
of the public coordinator and action APIs.

Persist or immutably stage the minimum replacement linkage: transition kind,
predecessor session and authority stamp, initiating view/fence, successor session,
provider attempt/completion ID, host completion ID, reservation identity,
deadlines, and committed disposition. Store no raw predecessor or successor
credential in ordinary records; reuse hashes and the credential vault.

JDBC delivery ciphertext must authenticate provider scope, binding, attempt,
transition kind, and successor linkage as associated data. New associated-data
encoding needs a version discriminator so existing sign-in ciphertext remains
readable. Key rotation and retention remain deployment-owned.

Add an explicit forward upgrade for existing PostgreSQL deployments. Choose its
number from the actual migration sequence at implementation time. Existing
ordinary ceremony rows must acquire ordinary semantics, never inferred
replacement permission from their purpose. Preserve counters, ownership epochs,
negative/replay history, and outstanding ordinary completions.

Update authoritative initialization DDL and the schema exporter/drift checks
together. Preserve historical V4 byte-for-byte and generate only the unreleased
V5 delta. Fresh installations apply V4 then V5; existing V4 deployments apply
V5 explicitly. Test both fresh-schema parity with runtime initialization and
preservation of populated pending sign-in rows through the upgrade.

Old binaries do not understand pending replacement reservations. Do not claim
mixed-version serving is safe. The initial deployment contract is to drain old
serving processes, apply the upgrade, start compatible processes, and only then
enable replacement. A rolling mixed-version rollout requires an additional
enforced compatibility mechanism and its own tests. Downgrade is unsupported
while replacement records/fences require new semantics; document the supported
drain/recovery procedure rather than dropping history.

Third-party provider SPIs must either implement the new contract or explicitly
report unsupported replacement. Existing providers must not advertise support
through a default method that delegates to ordinary preparation. Review Scala
source/binary compatibility, action outcome exhaustiveness, and protocol version
handling before choosing the release version.

| Provider | Atomic replacement | Same-process recovery | Restart / cross-node recovery |
| --- | --- | --- | --- |
| In-memory | Required | Required while records survive | Unsupported; fresh instance requires authentication |
| JDBC exclusive scope | Required | Required | Restart required with retained storage/keys; overlapping writers remain unsupported |
| JDBC shared views | Required | Required | Required across compatible nodes with authoritative ownership fencing |

## 8. Verified successor metadata and one-use sensitive results

After activation, the provider must verify that the current browser/view is
the exact recorded successor before exposing the host completion reference to
a host transaction callback. Check current authority in that same transaction;
possession of either completion ID is insufficient. Never ask applications to
compare provider and host completion IDs for equality.

Use a synthetic recovery-code regeneration example to demonstrate the full
boundary. The application creates a bounded encrypted one-use result record in
the original host mutation transaction, keyed by its host completion and
successor. The provider does not become a recovery-code database. The memory
example stages equivalent host-owned immutable result data and declares its
non-durable behavior. Plaintext working buffers have short, explicit ownership;
clear mutable buffers where possible without claiming complete JVM zeroization.

Consumption must authorize the activated successor, claim/delete or mark the
host result consumed atomically, and expose a one-shot payload only after known
commit. Use the existing invocation-owned `SensitiveDisclosure` and sensitive
presentation runtime for final output; keep their fresh authority checks.
Suppressing output must release/discard its payload, and retained outcomes may
hold only an emptied disclosure holder or non-secret metadata.

The delivery guarantee is **at-most-once consumption**, not guaranteed human
receipt. If consumption commits but its response is lost, or settlement becomes
unknown, do not redisclose or replay consumption automatically. The user may
perform a newly authorized regeneration ceremony. Document this tradeoff in
the example and API contract. A host could provide a different acknowledged
delivery policy later, but this feature must not promise exactly-once viewing.

Test that the predecessor, another successor, a foreign browser, a stale owner,
an anonymous view, and an unactivated successor cannot consume the result.
Do not attach the plaintext result to the replacement handoff itself.

## 9. Implementation phases and exit gates

Implement in dependency order. Each phase should leave coherent local changes
and focused evidence; no phase may enable the feature before the whole required
path works. Deployment requires a separate release decision after review.

| Phase | Work | Exit gate |
| --- | --- | --- |
| 1. Contract and pure decisions | Finalize opaque types, SPI support behavior, state transitions, slot reservation, failure/disposition semantics, same-subject/version rules, and recovery request boundary. Add pure transition tests. | Every invariant has an owning boundary and a positive/negative test; no I/O in core; compatibility decisions recorded. |
| 2. Shared conformance and memory | Extend synthetic fixtures and provider-independent laws. Implement memory replacement against staged host state, reservation, delivery, activation, recovery, logout, and limits. | Shared laws pass for memory; old ordinary flow remains covered; restart explicitly rejects old authority. |
| 3. JDBC atomic replacement | Add linkage/reservation schema, explicit upgrade, lock checks, host race contract, vault envelope versioning, preparation and recovery. Preserve outer-runner settlement handling and transaction handle lifetime. | Both providers pass shared laws; PostgreSQL rollback, unknown settlement, restart, takeover, and migration tests pass without canceled tests. |
| 4. Runtime and browser | Add replacement outcome, capability-aware dispatcher and fixed-frame admission, HTTP recovery/bootstrap, bridge recovery, and activation integration. | Real browser can complete and recover after old-session revocation; all ordinary old-view outputs remain fenced. |
| 5. Successor results and examples | Add verified completion lookup and host-owned one-use result integration in Future/IO memory and JDBC examples. | Synthetic factor/recovery mutation and single-use disclosure pass, including lost response and stale/foreign successor denial. |
| 6. Regression, performance, docs | Complete clustered/browser matrix, performance evidence, migration/support guidance, CI coverage and public examples. | Full validation passes or genuine environment blockers are explicit; no weakened security checks, unexplained regression, or unsupported guarantee. |

## 10. Implementation map

Paths below are repository-relative. Existing patterns take precedence over
introducing parallel abstractions; new files should have focused purposes.

| Area | Main files / directories |
| --- | --- |
| Pure model and tests | `modules/core/src/main/scala/spoonbill/auth/BrowserReplacementModels.scala` and corresponding core spec |
| Portable SPI and settlement | `modules/spoonbill/src/main/scala/korolev/auth/BrowserReplacement.scala`, `BrowserReplacementPreparation.scala`, `BrowserPersistence.scala`, `BrowserPreparation.scala` |
| Memory provider | `modules/spoonbill/src/main/scala/korolev/auth/InMemoryBrowserPersistence.scala`, `modules/spoonbill/src/test/scala/korolev/auth/InMemoryBrowserPersistenceSpec.scala` |
| Provider laws | `misc/browser-auth-conformance/BrowserReplacementConformance.scala`, both provider fixtures and existing ordinary laws |
| JDBC provider / host contract | `modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserPersistence.scala`, `JdbcBrowserSessions.scala`, `JdbcHostSessions.scala`, JDBC provider/session specs |
| Schema | `modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/`, provider initialization DDL, `scripts/export-browser-auth-schema.mjs` |
| Coordinator / actions | `modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala`, `action/Action.scala`, `ActionForms.scala`, related action and coordinator specs |
| Output / transport | `modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala`, `ComponentInstance.scala`, `Context.scala`, server completion/service routing and session guards as needed |
| Recovery endpoint | `modules/spoonbill/src/main/scala/korolev/server/AuthenticationCompletionConfig.scala`, `server/internal/services/AuthenticationCompletionService.scala`, `server/internal/KorolevServiceImpl.scala`, corresponding service specs |
| Browser bridge | `modules/spoonbill/src/main/es6/connection.js`, `bridge.js`, protocol generation only if a new wire procedure is necessary, `misc/browser-protocol-tests.mjs`, `misc/redesign-client-tests.mjs` |
| Sensitive results | Existing sensitive disclosure/runtime boundaries and tests; `examples/browser-auth/src/main/scala/example/browserauth/RecoveryResults.scala`, host integrations and focused result-consumption specs |
| End-to-end / adapters | `misc/browser-auth-native-tests.cjs`, `misc/clustered-browser-auth-tests.cjs`, `misc/zio-http-browser-auth-tests.cjs`, existing browser scripts and adapter fixtures |
| Documentation | `docs/browser-authentication.md`, `docs/clustered-browser-authentication.md`, applicable migration guide, new feature validation report |

Generated protocol/DDL outputs must come from their existing generators. Do not
edit historical performance records, change unrelated formatting, or repair the
separately documented reconnect/transport bugs as part of this feature.

## 11. Acceptance and fault-injection matrix

Use controlled clocks, deterministic IDs, callback counters, transaction
barriers, and simulated lost acknowledgments. Use separate real PostgreSQL
connections/processes where concurrency is the behavior under test.

| Scenario | Required observation |
| --- | --- |
| Authorized factor replacement / enrollment / disabling / recovery regeneration | One host mutation, predecessor denied after commit, one successor, audit and browser preparation committed together |
| Ordinary preparation supplied replacement work | Remains rejected with no committed host effects; no replacement capability is inferred |
| Wrong kind, purpose, browser, scope, issuer, challenge, or expired proof | Denied; no committed host/proof/audit effects |
| Subject switch, reused session/token, backward versions, invalid successor expiry, or predecessor still host-authorized | Entire preparation rolls back |
| Owner takeover, generation change, logout, or host revocation between admission and mutation | Losing preparation cannot commit; final fences cover pauses at each boundary |
| Independent host administrative revocation races preparation | Locks/version checks establish a deterministic winner; an unrelated revocation cannot be mistaken for authorized replacement |
| Two replacements or replacement versus existing ordinary completion | One authorized successor can activate; losers do not duplicate host effects or evade the reservation |
| Callback throw, vault failure, capacity exhaustion, expiry during callback, or browser write failure | All mutation-transaction effects roll back; admitted attempt cannot replay proof |
| Callback/program evaluated twice or retained transaction handle used later | Second evaluation denied; closed/thread-foreign handles rejected |
| Commit acknowledgment lost before receipt | No handoff from the failed observer; original outcome recovered without new host work |
| Completion command lost after commit | Old UI remains fenced; checked HTTP recovery discovers/delivers the same successor |
| HTTP response lost before/after cookie acceptance | Bounded retry or successor reconnect succeeds as appropriate; no second preparation |
| Final permitted delivery accepts the cookie, then reload occurs before activation (`maxDeliveries = 1`) | Minimal bootstrap validates the pending successor and reaches activation without redelivery, retained credential material, or application loading |
| Restart / compatible other-node recovery | JDBC issues a fresh local restricted capability from verified durable records; memory explicitly requires new authentication |
| Foreign attempt probe / malformed body / bad origin / duplicate cookies | Uniform denial, bounded request handling, no credential or principal leakage |
| Repeated recovery, delivery budget exhausted, expired material, clock moving backward | Same outcome and original deadline; no revived delivery or mutation replay |
| Logout, successor revocation, or successor activation races delivery | Transactional winner determines eligibility; no stale credential gains ordinary authority |
| Old actions, UI frames, snapshots, sensitive regions, downloads, subscriptions | Denied after replacement commit; no general output bypass from the handoff |
| Paused old owner resumes after takeover or shutdown cleanup | Cannot send a new replacement command, write a snapshot, release a successor owner, or mutate protected state |
| Current successor completion lookup | Correct host completion reference, checked in the host transaction; unequal provider/host IDs work |
| Sensitive result consumed twice / lost consumption response / unknown settlement | At most one consumption; no automatic redisclosure; predecessor/foreign/stale/unactivated views denied |
| Expiry, maintenance, close, and restart cleanup | Material released within bounds, replay fences retained, committed disposition preserved, successor ownership respected |
| Fresh schema / upgrade of populated old schema | Identical new contract; outstanding ordinary sign-in behavior preserved; no counter reset or implicit request-time DDL |

Browser acceptance must cover Chromium and WebKit, memory Future/IO, JDBC,
same-view reconnect, fresh-page recovery, multiple tabs, and compatible shared
JDBC nodes. Preserve existing native Pekko and ZIO HTTP behavior; extend their
fixtures only where the new transport boundary needs coverage.

## 12. Validation commands and reporting

All environment-dependent work uses repository-local `flake.nix` and
`--no-write-lock-file`. Do not install host tools or bypass Nix. PostgreSQL tests
use disposable owned storage through `scripts/with-test-postgres.sh`; no
production connection, credentials, or services are required.

The following commands define the validation scope. Section 15 records the
exact completed invocations, outcomes, failed attempts and environment limits.

Focused Scala validation:

```sh
nix develop --no-write-lock-file --command sbt \
  'core/test' \
  'spoonbill/testOnly spoonbill.auth.* spoonbill.action.* spoonbill.ActionFormsSpec spoonbill.internal.* spoonbill.snapshot.* spoonbill.sensitive.* spoonbill.server.internal.services.*' \
  'browserAuthExample/test'

nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt 'securityJdbc/test'
```

Core, generated-schema, and client checks:

```sh
nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs
nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check
nix develop --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs \
  misc/sensitive-client-tests.mjs misc/replacement-bootstrap-tests.mjs
git diff --check
```

Real browser and shared-node checks:

```sh
nix develop --no-write-lock-file --command bash scripts/test-browser-auth.sh
nix develop --no-write-lock-file --command env SPOONBILL_AUTH_PROVIDER=io \
  bash scripts/test-browser-auth.sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_PROVIDER=jdbc bash scripts/test-browser-auth.sh
nix develop --no-write-lock-file --command bash scripts/test-clustered-browser-auth.sh
nix develop --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh
```

The scripts above now assert the replacement scenarios. The JDBC suite also
tests an explicit upgrade of populated prior-schema state, including pending
ordinary sign-in. Changed Scala files were formatted with the declared
formatter; unrelated files were not included in formatting cleanup.

After targeted gates pass, run the aggregate once for the completed feature:

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

For every meaningful check record the exact command, pass/fail, executed and
canceled test counts, short outcome, and next action on failure. Do not treat
database tests skipped for missing configuration as a pass. Repeat or broaden
checks only for new changes, failures, or unresolved concerns. Publication,
`publishLocal` and deployment are not part of these local validation commands.

## 13. Performance and retained-resource evidence

Reuse existing transaction observation and performance support. Measure the
complete replacement path separately from ordinary sign-in: begin/proof
binding, admission, atomic preparation, live completion-frame admission, HTTP
delivery, activation, recovery, and successor result consumption. Count durable
clock transactions, connections and host lookups rather than hiding them.

Capture deterministic transaction/callback counts in tests. Then run a bounded
synthetic comparison with fixed workload, runtime, database and ownership mode,
recording sample count, median and tail latency, retained attempts/materials,
active/retained views, and peak/steady retained memory after maintenance. Compare
ordinary sign-in before/after for unintended regressions. Replacement has no
successful historical baseline, so report its absolute cost instead of claiming
an improvement against the currently rejected path.

Cover successful completion, repeated bounded recovery, denied attempts, and
cleanup under capacity pressure. Freeze the measurement protocol and acceptable
ordinary-path regression budget before collection; do not choose a threshold
after seeing results. Timing observations supplement correctness gates and
must not become flaky per-run unit assertions. Historical inconclusive browser
performance evidence remains inconclusive. If tooling is unavailable through
the flake, report the gap and request Nix configuration direction instead of
installing a host fallback or weakening authorization.

## 14. Risks, stop conditions, and completion bar

Primary risks are accidental general authority from a handoff, a recovery path
that depends on the revoked session, stale host revocation checks, slot races,
mixed-version nodes ignoring replacement state, and sensitive-result replay or
retention. The invariants and fault matrix above are release gates for those
risks, not optional hardening after the feature ships.

Stop and revise the design if the current SPI cannot express same-transaction
predecessor locking, verified successor lookup, or restricted output admission
without leaking JDBC or reusable authority. Do not merge a provider-only
success path while transport recovery remains unreachable. Escalate a material
change in scope or required external action before proceeding. For repeated
failures, follow repository policy: after two attempts re-plan; if the first
attempt after re-planning fails, present the evidence and request direction.

The feature is complete only when both providers meet their declared guarantees,
the browser can recover the committed replacement without old ordinary
authority, the predecessor stays fenced across every output/mutation path, the
successor can consume a one-use host result under the documented loss semantics,
upgrades and compatibility constraints are tested, and correctness/performance
evidence and limitations are documented. Until then, record phase progress and
remaining work explicitly; do not mark the proposal implemented merely because
the initial JDBC regression passes.

Record completed slices and validation below; the feature completion bar above
still applies. Review of this change does not authorize deployment.

## 15. Local implementation progress

- Plan review correction: activation bootstrap must work with an already-held
  successor cookie after delivery material or budget is exhausted.
- Phase 1 foundation implemented: pure predecessor/successor and committed
  recovery decisions, opaque ceremonies and handoffs, invocation-bound admission,
  request-bound one-use delivery, the optional portable SPI, and the replacement
  settlement interpreter. Ordinary and replacement preparation share only the
  single-evaluation guard; they retain distinct admission and receipt types.
- Added 29 tests covering those decisions, capability provenance and lifetime,
  copied invocation IDs, lazy-program/callback replay, delayed or unknown commit,
  activation after final delivery, and external-provider type boundaries.
- Phases 1–3 implemented: pure slot decisions, shared replacement conformance,
  both providers, exact host-stamp validation, retained fences, populated-schema
  upgrade, restart/shared-node recovery, and callback/transaction lifetime
  guards. Saved JDBC authority stamps preserve nanoseconds across restart.
- Phase 4 implemented: invocation-bound fixed output, explicit uncertain-rollback
  reconciliation, bounded HTTP discovery, minimal reload bootstrap, held-cookie
  activation after the final delivery, and browser departure/BFCache handling.
  Discovery is bounded to three requests per disconnected period so one lost
  request during node failure does not permanently strand recovery.
- Phase 5 implemented: four synthetic factor/recovery changes, encrypted
  host-owned results in memory Future/IO and JDBC, and committed one-use
  consumption bound to the exact successor and disclosure invocation. Result
  expiry is checked after lock acquisition; an uncertain consumption commit
  never yields a disclosure holder.
- Phase 6 implementation and evidence collection are complete. The aggregate Scala suite,
  client checks, migrations, native Chromium matrix, clustered matrix, and ZIO
  browser matrix have passed. All native WebKit scenarios also have passing
  coverage; exclusive JDBC used labelled batches after an aggregate timeout.
  The complete frozen performance comparison finished with all fault and cleanup
  assertions passing. Run4 exceeded the memory ordinary p95 budget. The user's
  subsequent time-bounded repeat used identical source and settings and met
  both providers' ordinary-path budgets. Both observations and every sample
  remain recorded; the earlier spike's precise cause is unproven.
- The supplied JDBC regression is now an explicit-replacement success test;
  ordinary preparation retains a negative regression proving that revocation
  rolls all host effects back. Unrelated local changes remain preserved.

### Foundation validation (historical first slice, 2026-10-16)

The following commands ran through the repository flake:

```sh
nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'core/scalafmtOnly src/main/scala/spoonbill/auth/BrowserReplacementModels.scala' \
  'core/Test/scalafmtOnly src/test/scala/spoonbill/auth/BrowserReplacementModelsSpec.scala' \
  'core/test'

nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'spoonbill/testOnly spoonbill.auth.* spoonbill.action.* replacementconsumer.ReplacementProviderBoundarySpec' \
  'securityJdbc/Test/compile' 'browserAuthExample/Test/compile'

nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec -- -z "recover unknown outer commit" -z "withhold receipts and host visibility" -z "close host transaction handles"'

nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs
git diff --check
```

| Check | Result | Evidence / next action |
| --- | --- | --- |
| Core tests | Pass | 31 executed, 0 failed, 0 canceled |
| Authentication, action and external-provider boundary tests | Pass | 86 executed, 0 failed, 0 canceled |
| JDBC and browser example test compilation | Pass | Existing consumers compile; this does not run database tests |
| Existing JDBC settlement and transaction-handle tests | Pass | 3 executed, 0 failed, 0 canceled against disposable PostgreSQL; unknown commit, delayed outer commit, and closed handles remain covered |
| Core purity | Pass | 8 source files checked, no violations |
| Tracked-change whitespace | Pass | No diagnostics; untracked new files also checked separately |

The first formatting invocation used `*BrowserReplacementModels.scala` and
`*BrowserReplacementModelsSpec.scala` with `scalafmtOnly`; that plugin treats its
arguments as literal paths, so it failed before running tests. The explicit-path
command above corrected it and passed. New runtime/test files and changed
runtime additions were also formatted through the declared formatter; unrelated
pre-existing formatting was preserved.

These results describe only the first foundation slice; they are superseded by
the later integration evidence below where applicable. No Git commit was
created.

### Integration validation and recorded limitations

Validation resumed on 2026-10-17 from the saved handoff. The results below are
the latest relevant checks; overlapping earlier runs are not added together
as a final test total.

The combined focused run after formatting repair passed **555 tests** with no
failures or cancellations: 32 core, 267 runtime, 238 JDBC, and 18 host-example
tests. Subsequent additions provide one memory race and four JDBC fault/race
tests; the final aggregate below is responsible for those additions.

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'core/test' \
  'spoonbill/testOnly spoonbill.auth.* spoonbill.action.* spoonbill.ActionFormsSpec spoonbill.internal.* spoonbill.snapshot.* spoonbill.sensitive.* spoonbill.server.internal.services.* replacementconsumer.ReplacementProviderBoundarySpec' \
  'securityJdbc/test' 'browserAuthExample/test'
```

Client/fixture checks passed **98 tests**, none failed, canceled, or skipped.
Core purity passed for 8 files; schema drift passed for 13 statements and 9
tables. The client tests include back/forward-cache restoration with a live
socket: the terminal old bridge retires before fresh callbacks are admitted.
They also cover successful handoff followed by delayed socket close or delayed
message processing, and verify that a later unexpected socket close still
performs bounded recovery.

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/redesign-client-tests.mjs \
  misc/sensitive-client-tests.mjs misc/replacement-bootstrap-tests.mjs \
  misc/core-purity-tests.mjs misc/browser-auth-baseline/*.test.mjs
nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs
nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check
```

The full-project `compile`, `Test / compile`, and `test` run passed
**1,029 tests**: 992 ScalaTest and 37 native ZIO tests, with zero failures,
cancellations, or ignored tests. This includes all five additional race/fault
tests. No Scala source changed afterward. The later client-only intentional
handoff correction was checked by the 98 Node tests and rebuilt before the
latest browser runs; both example and ZIO classpaths were exported again.

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/Test/scalafmtOnly src/test/scala/korolev/auth/InMemoryBrowserPersistenceSpec.scala' \
  'securityJdbc/Test/scalafmtOnly src/test/scala/spoonbill/security/jdbc/JdbcBrowserPersistenceSpec.scala' \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test \
  'export browserAuthExample/Runtime/fullClasspath' \
  'export zioHttp/Test/fullClasspath'
```

Cross-node acceptance passed in **Chromium and WebKit**, using two independent
JVMs and disposable shared PostgreSQL. The replacement fault commits the host
change, drops the command/response path, kills the serving node, and recovers
the same successor on the other node. It verifies predecessor denial and one-use
disclosure while retaining the existing ownership, snapshot, logout, and
non-replay scenarios.

ZIO HTTP acceptance passed **four browser runs** on 2026-10-17 after the final
client correction (native/programmatic form orders in both engines). The cases cover replacement, interrupted committed
observers, an uncertain observer whose mutation rolled back, and lost delivery
followed by the minimal activation bootstrap.

```sh
nix develop --no-write-lock-file --command \
  bash scripts/test-clustered-browser-auth.sh \
  /private/tmp/spoonbill-replacement-example.classpath
nix develop --no-write-lock-file --command \
  bash scripts/test-zio-http-browser-auth.sh \
  /private/tmp/spoonbill-replacement-zio.classpath
```

Those classpath files contain the latest rebuilt example and ZIO exports.
Omit the optional argument to either script to compile/export afresh.

The final native Chromium matrix passed **21/21 flows**: seven each for memory
Future, memory IO, and exclusive JDBC. Each provider covers password/factor
login and replacement, lost command, second-tab recovery, pre-server request
loss, and final-response loss both before and after cookie acceptance. The
delivery limit is one, so the accepted-cookie case proves activation without
redelivery or retained credential material.

```sh
nix develop --no-write-lock-file --command env SPOONBILL_AUTH_ENGINES=chromium \
  bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-example.classpath
nix develop --no-write-lock-file --command env SPOONBILL_AUTH_ENGINES=chromium \
  SPOONBILL_AUTH_PROVIDER=io \
  bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-example.classpath
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_ENGINES=chromium SPOONBILL_AUTH_PROVIDER=jdbc \
  bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-example.classpath
```

On 2026-10-17, the native WebKit memory and IO matrices passed **14/14 flows**
(seven per provider), and exclusive JDBC achieved **7/7 cumulative scenario
passes** as detailed below. Together with Chromium, all **42 public-example
scenario checks** have passing coverage. The routed-fault
fixture keeps login native, installs routing, and loads a new protected document
before arming the fault. Inspection of pinned Playwright 1.56.1 established that
WebKit's route installation uses an initialization script and cannot intercept
a reconnect in the already-loaded document. Binary forwarding and strict
runtime-error assertions remain intact.

The three user-authorized rework attempts stopped at: (1) an ordinary reload's
first socket closing before readiness; (2) the old same-document route setup;
and (3) initial page navigation with no recorded response/socket/error, before
the revised fixture ran. Normal password/factor flows passed on the diagnostic
second run. No owned browser/server processes remained afterward. The first and
third failures' causes are not established; no production fix, timeout widening,
or relaxed readiness assertion was introduced for them. Failed logs remain in
`/private/tmp/spoonbill-native-final-memory-webkit.log`,
`/private/tmp/spoonbill-native-memory-webkit-ready-observation.log`, and
`/private/tmp/spoonbill-native-final-memory-webkit-replanned.log`. The user
explicitly resumed from the handoff on 2026-10-17, providing fresh direction for
the pending WebKit validation. Performance collection follows browser validation
without overlapping servers or builds.

The resumed JDBC WebKit runs exposed two fixture synchronization errors. A
closed first socket was treated as terminal even when a later physical socket
emitted frame 21 and the browser became ready; the waiter now observes the latest
live socket within one unchanged 25-second deadline. Operation checkpoints also
started during login/setup; their unchanged 25-second timers now start at
replacement submission. Neither correction retries the application action or
relaxes authority/error assertions.

The next JDBC run failed during ordinary sign-in, before replacement, with a
server WebSocket request timeout. After fresh user direction, an isolated
diagnostic first hit the existing 45-second server startup limit before browser
launch, then passed ordinary JDBC/WebKit login on its next run with unchanged
production code and bounds. HTTP 204 to authorized socket readiness took
15.503 seconds. PostgreSQL snapshots showed client-read/idle connections and
no observed lock waits; startup, initial HTTP and health requests were also
slow. Observed host load averages were 33.36/31.41/27.61 with 52% free memory.
These observations suggest broader latency but do not establish the earlier
timeout's cause. No production changes were made in response to these
diagnostics. The user explicitly chose to continue under the current load.

```sh
nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_ENGINES=webkit SPOONBILL_AUTH_PROVIDER=jdbc \
  bash /private/tmp/spoonbill-jdbc-timeout-diagnostic/run.sh \
  /private/tmp/spoonbill-replacement-example.classpath
```

Diagnostic logs are retained in
`/private/tmp/spoonbill-jdbc-timeout-diagnostic/attempt1-authorized.log` and
`/private/tmp/spoonbill-jdbc-timeout-diagnostic/attempt2.log`. Diagnostics capture
only phase timings, numeric counters, filtered stack categories and database
wait categories; they do not capture credentials, SQL text or host records.

The subsequent full JDBC/WebKit invocation passed password, factor, and
lost-command recovery, then reached its unchanged **180-second aggregate
deadline** 6.984 seconds into second-tab login, before that replacement began.
No individual operation timeout or browser error occurred. That invocation
remains failed in
`/private/tmp/spoonbill-native-jdbc-webkit-full-after-diagnostic-20261017.log`.

The four remaining scenarios passed in two explicitly selected batches, with
unchanged 25-second operation bounds, 180-second engine bounds, assertions,
original scenario accounts and runtime code. The optional validated
`SPOONBILL_AUTH_FLOWS` filter defaults to all seven flows; it rejects empty,
unknown and duplicate selections. This establishes cumulative scenario coverage,
not a successful full invocation under the observed machine load.

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_ENGINES=webkit SPOONBILL_AUTH_PROVIDER=jdbc \
  SPOONBILL_AUTH_FLOWS=second-tab,request-loss \
  bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-example.classpath
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_ENGINES=webkit SPOONBILL_AUTH_PROVIDER=jdbc \
  SPOONBILL_AUTH_FLOWS=unaccepted-cookie-loss,accepted-cookie-loss \
  bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-example.classpath
```

Both commands exited 0 (two scenarios each). Logs are
`/private/tmp/spoonbill-native-jdbc-webkit-remaining-batch1-20261017.log` and
`/private/tmp/spoonbill-native-jdbc-webkit-remaining-batch2-20261017.log`.

Performance attempt 1 completed the two memory ordinary-sign-in lanes, then
stopped during the first replacement warmup before collecting replacement
samples. A separate diagnostic identified an unintended recovery POST after
each successful authentication handoff. The temporary completion flag cleared
before the real asynchronous socket close, so discovery ran despite a known
successful delivery. The client correction ties suppression to that exact
successfully delivered socket; it preserves discovery for lost commands and
unexpected closure of the next socket. The normal-flow benchmark assertion
remains zero discovery requests, and frozen budgets/sample counts are unchanged.
The client correction passed the client and browser checks described above.
All attempt-1 artifacts and diagnostics are retained
under `/private/tmp/spoonbill-replacement-performance-20261016-run1`.

Performance attempt 2 was interrupted during candidate build after the baseline
built, before any lane or timing sample. Its orphaned disposable PostgreSQL and
temporary checkout were identified and cleaned up; output remains under
`/private/tmp/spoonbill-replacement-performance-20261016-run2`. The
[portable evidence](authenticated-session-replacement-performance-evidence.json)
preserves all 40 attempt-1 ordinary samples, failure diagnostics and attempt-2
interruption metadata. Neither attempt establishes a final budget result.

Performance attempt 3 retained all 120 timing samples across six lanes, then
timed out in the final untimed JDBC fault check after sensitive delivery. The
exact failing operation was not captured, and JDBC replacement post-workload
resource checks did not execute. The attempt remains invalid under the frozen
protocol, although its ordinary timing observations were within the numerical
budgets. All samples and the failure remain in the portable evidence.

Fine-grained fault checkpoints were added without changing runtime code,
assertions, deadlines or budgets. An isolated JDBC fault diagnostic and a second
diagnostic after 25 same-JVM replacement contexts both passed all fifteen fault
steps; the latter also observed zero delivery materials, active view owners and
encrypted recovery results after maintenance. The historical timeout's cause
remains unknown. Attempt 4 completed a fresh comparison as a correctness retry
after the invalid attempt, not a rerun selected to improve timing results.
The performance report preserves the full attempt history.

Attempt 4 exited 0 after all six lanes completed five warmups and twenty samples
each. Both providers passed the final fault checks; JDBC maintenance left zero
active view owners, delivery materials and encrypted recovery results. The
source fingerprint matched before and after collection. The run retained every
sample and made no runtime change. Its exact command was:

```sh
nix develop .#profiling --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  bash scripts/measure-browser-replacement.sh \
  /private/tmp/spoonbill-replacement-performance-20261017-run4
```

**Run4 performance exception retained:** memory ordinary-sign-in p95 was
4,336.59 ms against a frozen 1,978.67 ms limit (baseline 1,428.65 ms). Its median
was within budget: 1,183.44 ms against 1,428.92 ms. JDBC ordinary median/p95 were
1,056.87/1,075.46 ms, within 1,318.44/1,498.83 ms limits. Successful collector
exit confirms collection and correctness assertions, not that every budget
passed. No slow sample was removed or automatic rerun selected after this breach.
The retained-evidence investigation and its causal limits are recorded in the
[performance report](authenticated-session-replacement-performance.md).

Replacement plus one-use result consumption had median/p95 of 336.50/388.62 ms
for memory and 1,769.32/1,795.43 ms for JDBC. These are absolute observed costs
under the current host load, not capacity or improvement claims. The portable
artifact initially preserved all 280 primary samples across successful and
invalid attempts, alongside both diagnostic records and process-level retention
data. The explicitly authorized repeat below adds another 120 samples.

On 2026-10-17, the user reported lower laptop CPU use and explicitly requested
a repeat within fifteen minutes to assess whether the breach reflected
measurement conditions. Run5 completed in about five and a half minutes from
the request's execution start, with identical source fingerprint, sample
counts, lane order, runtime settings and budget formulas. Both providers passed
their ordinary budgets and all fault/resource/source checks passed; the outer
watchdog was not reached. Exact command:

```sh
nix develop .#profiling --no-write-lock-file --command \
  timeout --kill-after=20s 780s \
  bash scripts/with-test-postgres.sh \
  bash scripts/measure-browser-replacement.sh \
  /private/tmp/spoonbill-replacement-performance-20261017-run5
```

Memory baseline median/p95 were 377.96/392.53 ms; candidate values were
379.09/397.39 ms, within unchanged formula-derived limits of 497.45/579.91 ms.
JDBC candidate median/p95 were 1,068.59/1,093.13 ms against limits of
1,329.60/1,493.26 ms. Memory ordinary candidate p95 was only 1.24% above its
same-run baseline, and the earlier multi-second tail did not recur. Observed
host load was lower. This supports sensitivity to measurement conditions,
without proving a timing-calculation error or identifying the earlier spike's
cause. No source change was needed. All 400 primary samples are retained;
the latest pass does not erase run4's measured breach.

Two integration defects found by fault tests were corrected: discovery now
retries within a fixed bound after a dropped cross-node request, and WebKit
navigation starts no new recovery request while the document is departing.
The fresh-document routed fixture now has passing coverage for all three
providers in both engines, including the previously blocked lost-command cases.
The earlier ordinary JDBC timeout remains unexplained; the later full WebKit
invocation's aggregate timeout and passing batches are recorded above.
Assertions have not been weakened.

Changed Scala files were formatted using the declared formatter. An attempted
partial restoration of formatting hunks broke a moved import and braces;
compilation caught this before execution. Complete formatter output was then
restored for the task's changed Scala files only, and the combined check was
restarted. Untouched files were not formatted.

The validation record above and the performance report contain the exact
aggregate, browser, schema/client and performance commands, counts, outcomes
and limitations. Local implementation is ready for review. The latest repeat
met all performance budgets; the earlier memory p95 breach and failed full
JDBC/WebKit aggregate invocation remain historical failed results, with their
limitations documented. These results were recorded before PR review; they do
not claim deployment or a release.

## 16. API/Scala 3 review and staged handoff

The final API/FP review simplified committed recovery to return availability
directly, rejected exhausted memory generations before host work, suspended
retirement-marker observation for lazy effects, and made the example's one-use
recovery presentation explicitly effectful. Focused regression tests exercise
generation exhaustion and lazy construction/evaluation. JDBC test fixtures now
acquire cleanup ownership before waiting for initialization and sequence cleanup
after initialization settles. No timeout, seed count or security assertion was
weakened. Existing effect abstractions, transaction semantics and Scala 3
formatting conventions were retained.

Post-review compilation/test compilation and scoped formatting passed. The
aggregate executed 1,031 tests: 1,029 passed and two JDBC example tests timed out
during initialization. It completed naturally, including all 37 native ZIO
tests. The isolated example suite passed all 19 tests after fixture-lifetime
repair, giving passing coverage for all 1,031 tests across the two invocations;
the original aggregate remains failed. All 98 client checks, core-purity and
schema-drift checks, and nine focused Chromium scenarios also passed. Exact
commands and logs are in the [review guide](authenticated-session-replacement-review-guide.md).

The review set has 71 feature/review files. Unrelated files, `AGENTS.md` and
`HANDOFF.md` are excluded. The validation baseline was
`7108eda8cef0d6d8ab838fe8c963f58cda810887`.
The performance collections precede these focused review changes and retain
their recorded source fingerprints and outcomes without reinterpretation.

## 17. Subsequent code-review response

The agreed code-review changes preserve historical V4 byte-for-byte and move
the replacement table and both reservation/successor indexes entirely into V5.
The exporter pins V4's checksum, writes only the unreleased V5 delta, and checks
that the complete migration chain matches runtime DDL. Regression tests cover
both write/check rejection of edited V4 or uncovered runtime changes, populated
upgrade preservation and fresh-install catalog parity.

The redundant `observedPredecessor` parameter is removed. Provider transactions
retain responsibility for fresh predecessor and ownership checks; new memory
and JDBC tests reject still-resolving predecessors whose stamps changed before
preparation, with no host callback. Resumed-owner and competing-writer coverage
remains intact. `currentInvocation` captures an association without replacement
intent, while `replacementInvocation` explicitly marks it; disclosure uses the
former. Documentation describes both checked delivery channels, finite retained
history and the actual scope/probe ordering. No history pruning, parallel
reconnect or unrelated file extraction was introduced.

Current validation passed: full compile/test compilation, **731 affected Scala
tests** (core33/runtime435/JDBC244/examples19), **101 Node checks**, core purity,
the **14-statement / nine-table** migration-chain check, and **nine Chromium
scenarios**. A new core test initially built an invalid ceremony fixture; after
correcting that fixture, all affected suites passed without changing production
validation. Exact commands and logs are in the review guide. Older aggregate,
WebKit and performance results remain separately labelled historical evidence.
The review diff contains 71 files: unchanged V4 no longer appears as a
modification, and the migration-export test is included.
