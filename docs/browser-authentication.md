# Browser authentication

Spoonbill coordinates browser authentication in Scala. Applications supply
account policy and host transactions; `BrowserAuthentication[F, S, P]` connects
that authority to HTTP rendering, physical WebSocket ownership, typed actions,
cookie delivery and presentation storage. The application does not need an
authentication JavaScript plugin.

The executable [example](../examples/browser-auth/src/main/scala/example/browserauth/)
shares one effect-polymorphic UI between native cats-effect IO memory, Future
memory, and Future PostgreSQL implementations. Its accounts,
password parameters, factors and encryption key are public synthetic fixtures,
not deployment defaults.

For multiple live web nodes and rolling handoff, use the explicit
[shared PostgreSQL mode and presentation projection](clustered-browser-authentication.md).
Explicit exclusive mode retains its single-owner deployment contract.
Use matching **8.0.0** versions for all Spoonbill
modules and recompile application integrations. Start with this guide and the
[8.0 migration guide](migration-v8.md);
the earlier snapshot guides remain historical testing records.

## Install the coordinator

`F` is the application effect, `S` is ordinary immutable presentation, and `P` is
the application's principal. This helper uses only public APIs:

```scala
import spoonbill.auth.*
import spoonbill.effect.Effect
import spoonbill.server.SpoonbillServiceConfig

def install[F[_]: Effect, S, P, M](
  provider: BrowserPersistence[F, P],
  presentation: BrowserPresentation[S, P],
  base: SpoonbillServiceConfig[F, S, M],
  httpsOrigin: String
): (BrowserAuthentication[F, S, P], SpoonbillServiceConfig[F, S, M]) = {
  val browser = new BrowserAuthentication[F, S, P](
    provider,
    presentation,
    BrowserConfiguration(provider.scope, Set(httpsOrigin))
  )
  browser -> browser.configure(base)
}
```

Supply the usual document, router and state loader in `base`. Leave its
`stateStorage`, `sessionAccessControl`, `authenticationCompletion` and
`browserSecurityInstallation` unset. `configure` installs those fields together;
replacing part of the installed bundle is rejected when the service is built.
Guarded service configuration requires WebSockets and disables WebSocket
compression. The official adapters retain the explicit v3 transport lifecycle.

`BrowserPresentation` supplies `fresh`, `allowed`, and `connected`. `allowed`
decides which ordinary state may be rendered for the current optional principal.
`connected` receives optional continuation metadata for an interrupted challenge
or preparation. A display field such as `page.account` is never authentication
evidence. Sensitive regions require the separate `SensitiveAccess` policy;
authentication does not enable them automatically. Since 6.0.0, custom
`SessionGuard` implementations must also select their
[application-state initialization contract](clustered-browser-authentication.md#custom-session-guard-initialization).
The coordinator supplies that contract for its own guards.

Use exact HTTPS origins and secure cookies for deployments. The configuration
permits insecure HTTP only for explicit loopback origins. Scope must match the
provider's realm and cookie namespace. Cookie names, origins, WebSocket settings
and provider capabilities are checked before serving requests.

Ordinary HTTP forms opt into native browser submission through
`ActionForms.nativeSubmit`. For example, the shared `Application[F]` signs out
with an ordinary POST:

```scala
form(
  spoonbill.ActionForms.nativeSubmit[F, Page, Any],
  method := "post",
  action := "/auth/logout",
  button("Sign out")
)
```

The HTTP endpoint must still validate origin/CSRF, cookies and current policy.
The marker supplies browser submission behavior without application JavaScript.
Typed `ActionForms.onSubmit` takes precedence if both markers are present.
Existing departure and sensitive-region cleanup still applies.

## Account actions and preparation

### Typed outcomes and rejection

Since 7.0.0, ordinary actions return `F[UiOutcome[S]]` using `UiOutcome.modify`
for a pure state transformation or `UiOutcome.noUpdate` when no state work is
needed. Both `ActionForms.onSubmit` overloads require a rejection callback of type
`ActionRejection => StateUpdate[S]`:

```scala
import spoonbill.ActionForms
import spoonbill.action.*
import spoonbill.effect.Effect

case class ActionPage(message: String)

def saved[F[_]: Effect]: F[UiOutcome[ActionPage]] =
  Effect[F].pure(UiOutcome.modify[ActionPage](_.copy(message = "Saved.")))

def noChange[F[_]: Effect]: F[UiOutcome[ActionPage]] =
  Effect[F].pure(UiOutcome.noUpdate[ActionPage])

val onRejected: ActionRejection => StateUpdate[ActionPage] =
  _ => StateUpdate.Modify(_.copy(message = "The request was rejected."))

def submit[F[_]: Effect](action: PublicAction[F, ActionPage, Unit]) =
  ActionForms.onSubmit[F, ActionPage, Unit, Unit](action, onRejected)
```

To ignore rejection without a render, return `StateUpdate.noUpdate[ActionPage]`.
`StateUpdate.Modify[ActionPage](identity)` intentionally requests a transition and
render even when state is equal. The reducer is applied later on the existing
transition queue; composing or zooming updates does not evaluate it. The
[7.0 migration guide](migration-v7.md) lists removed constructors and helpers.

Completion and rejection callbacks execute through `Effect.delay`; a thrown
callback exception fails the effect without requesting a transition. In ZIO,
ordinary callback exceptions are typed failures rather than defects. Reducer
failures retain their existing queued reporting behavior.

### Sign-in ceremony

The shared example's [Application](../examples/browser-auth/src/main/scala/example/browserauth/Application.scala)
shows the complete typed forms and outcomes:

1. `browser.begin(context, "sign-in")` captures a live ceremony before proof
   verification. Store only its `CeremonyId` in ordinary presentation.
2. `browser.resume(context, id, "sign-in")` resolves that lookup under the current
   physical connection. A decoded UUID cannot construct a `BrowserCeremony`.
3. The host rate-limits and computes expensive password proofs outside the
   transaction. `ceremony.browserKey` is a non-bearer rate-policy lookup key.
4. For a second factor, `provider.bindChallenge` registers immutable subject,
   credential/policy version and challenge-reference metadata. Final proof
   validation must check that association against current host records.
5. `provider.prepare(ceremony)` consumes proof, creates the host session and
   writes the host audit together with browser preparation. It returns a
   `CommittedAuthentication` only after acknowledged outer commit.
6. Return `browser.complete(receipt)` from the typed action. The HTTP completion
   endpoint delivers an HttpOnly credential, and a fresh handshake activates it.
   A receipt from another or closed provider is rejected.

`HostSessionPrepared` carries the host session ID, host acknowledgment ID,
session expiry and transport-only `BrowserSessionToken`. The provider assigns
the browser completion identity separately. Generate a fresh token through
`BrowserCredentials.generate[F]` before entering the transaction, or retain an
existing host issuer with the required entropy and encoding. In 8.0.0, built-in
providers derive the delivered browser cookie from this preparation material;
`HostSessionPrepared.credential` is not itself the delivered cookie. Integrations
must use the actual provider-delivered cookie and respect the configured maximum
session lifetime. See the [issuance and retention contract](request-admission-lifecycle.md).
Keep credentials,
transaction handles, live ceremonies and view capabilities out of UI state,
snapshots and logs. `Secret.withValue` is an explicit trusted-code boundary;
JVM strings cannot promise secure erasure.

The provider-specific examples are executable references for the transaction
callback: [JdbcAccounts](../examples/browser-auth/src/main/scala/example/browserauth/JdbcAccounts.scala)
uses `tx.connection` and `tx.now`; [MemoryAccounts](../examples/browser-auth/src/main/scala/example/browserauth/MemoryAccounts.scala)
uses `tx.hostState`, `tx.modify` and `tx.now`. Each provider's `Tx` is an
instance-dependent type. Handles also reject access after their callback ends
or from another thread.

Throw `OperationProtocolException` to reject transaction work. Returning an
ordinary `Left` from a host callback is a successful callback value, not an
instruction to roll back. Host callbacks must not commit, roll back, close,
detach work, wait on asynchronous work or call external services.

For protected domain writes, obtain the captured view through
`browser.currentView(context)`, then use the concrete provider's
`transact(view)` operation. It reauthorizes that view in the transaction that
executes the host write. The application still checks operation-specific
permissions. This convenience does not replace the one-use operation protocol
when a business operation requires an independently retained idempotency key.

## Authenticated session replacement

Use the explicit replacement API for factor enrollment, factor replacement,
disabling, or recovery-code regeneration that revokes the current host session.
Ordinary `prepare` does not acquire that permission from its purpose string.
Calls to `browser.begin`, `browser.resume`, or `browser.recover` from an
`AuthenticatedActionContext` have been deprecated since 5.1.0 to direct those
flows to `beginReplacement`, `resumeReplacement`, and the checked replacement recovery
outcome. The ordinary methods and `provider.prepare` remain supported for
anonymous sign-in; deprecating them globally would warn valid sign-in callers.
An existing authenticated flow must migrate its ceremony and transaction
callback together. Changing only the purpose string or completion call does not
grant replacement authority.
Both built-in providers implement `ReplacementBrowserPersistence`; a third-party
provider must implement this extension explicitly. The coordinator discovers its
transport operations through `BrowserReplacementSupport` and does not fall back
to ordinary preparation.

The [shared application](../examples/browser-auth/src/main/scala/example/browserauth/Application.scala)
demonstrates the public sequence inside an authenticated action:

1. Call `browser.beginReplacement(context, purpose)` before verifying the proof.
2. Capture `browser.replacementInvocation(context)` for the action that will
   prepare the replacement. It is distinct from the ceremony's earlier begin or
   challenge invocation. Public IDs cannot reconstruct this association. This
   method also marks replacement intent so a suppressed or failed observer can
   retire its transport and discover the stored outcome.
3. Bind the original proof metadata with `provider.bindReplacementChallenge`.
   Check the proof and its captured account versions again in the transaction.
4. Call `provider.prepareReplacement(ceremony, invocation)`. On its supplied
   transaction, consume proof, change the host credentials, revoke the exact
   predecessor, create a fresh same-subject session, and store audit/outbox work.
   Return `HostSessionPrepared` for that successor. Security/policy versions may
   advance but cannot decrease. External notifications belong in an outbox.
5. Return `browser.completeReplacement(handoff)` only for the acknowledged
   committed result. Its fixed completion command carries no application update,
   sensitive result, callback or reusable ordinary authority.

For an unresolved preparation result, return
`browser.recoverReplacementOutcome(ceremony.id)`. It requests the checked HTTP
recovery operation without replaying the action. This matters when an unknown
commit actually rolled back and the predecessor is still current: recovery must
still fence that attempt negative. If the predecessor was revoked, ordinary
output stays suppressed and the marked replacement invocation retires its
transport so binding-based recovery can discover the committed successor.

The provider atomically reserves the browser slot for the successor while
retaining the captured generation. The predecessor is revoked immediately.
Pending successor cookies cannot render an anonymous or authenticated app;
activation advances the generation once. An expired reservation remains a fence
against competing older completions. Logout is the explicit reset boundary.
First replacement activation also retires obsolete logical view owners on that
binding, retaining their epochs and history, so a fresh page can attach even
when a crashed predecessor occupied the configured view capacity.

There are two checked HTTP delivery paths. The live fixed command uses
`POST /auth/complete` with the provider completion UUID and HttpOnly binding
cookie. The provider recognizes replacement material and rechecks the stored
reservation, successor, generation, deadline and delivery budget. Recovery uses
`POST /auth/recover`; its `BrowserReplacementDelivery` is bound to that exact
validated HTTP request before delivery. Both paths consume the same stored
delivery budget and enforce origin/input/cookie checks. The live path does not
consume the recovery request's capability wrapper. Restricted output refers to
the predecessor socket's fixed-frame exception, not to a single HTTP channel.

The framework's checked `POST /auth/recover` uses the HttpOnly binding and an
optional ceremony lookup. It can discover the unique pending replacement after
a lost command, without loading the application or rerunning proof. A fresh
page receives only a minimal recovery bootstrap while replacement is pending.
That page uses a fresh `/auth/activate` WebSocket handshake before ordinary
application loading. If the browser already holds the valid successor cookie,
activation does not require another delivery or remaining delivery budget.
The original completion deadline still applies. With exhausted delivery and no
successor cookie, fresh authentication/reset is required; the committed security
change is never undone or automatically repeated.

For a one-use result, capture `browser.currentInvocation(context)` in the
disclosure action. Unlike `replacementInvocation`, this captures identity
without marking replacement intent. It proves neither activated-successor
status nor permission to disclose. Call the concrete provider's
`withSuccessorCompletion(view)` under the freshly activated successor. It
provides the verified **host** completion ID inside the same transaction as the
host callback; do not equate it with the provider completion ID. The examples
atomically delete an encrypted, expiring host result and expose only a one-shot
ciphertext holder after known commit. The holder checks the exact successor and
actual disclosure invocation, rejects copied public invocation IDs, and checks
expiry again before decryption. Decryption transfers directly into
`context.presentSensitive` and the existing invocation-owned disclosure runtime.
Snapshots and ordinary presentation never contain the recovery codes.

The completion callback returns `StateUpdate[S]`, so it can update ordinary
presentation or explicitly request no update. For an action using `ActionPage`
from the typed-outcome example above:

```scala
import scala.concurrent.duration.*
import spoonbill.sensitive.*

def disclose(
  context: PublicActionContext,
  region: RegionId,
  purpose: Purpose,
  payload: SensitivePayload
): UiOutcome[ActionPage] =
  context.presentSensitive[ActionPage](region, purpose, payload, 30.seconds) {
    case DisclosureOutcome.BrowserProcessed => StateUpdate.noUpdate[ActionPage]
    case _ => StateUpdate.Modify(_.copy(message = "Delivery could not be confirmed."))
  }
```

Call this from the admitted typed handler; an authenticated context also supports
this method. Render the matching `SensitiveRegion` and configure the separate
sensitive-access policy. `BrowserProcessed` is a browser acknowledgement, not
proof of human receipt. Returning `NoUpdate` avoids an acknowledgement-triggered
transition and view publication while preserving acknowledgement and resource
cleanup. A genuine later publication still makes an earlier browser revision
stale; no-update outcomes do not bypass authority or ownership checks. The
[recovery-result example](../examples/browser-auth/src/main/scala/example/browserauth/RecoveryResults.scala)
chooses `Modify` instead because it displays a completion message.

This guarantees at-most-once **consumption**, not human receipt. Lost output or
unknown consumption commit can leave the codes unavailable. Regenerate through
a new authorized ceremony instead of replaying disclosure. The memory example's
encryption key and results are process-local. The JDBC example keeps encrypted
results in host tables and uses deployment-owned vault keys. Applications must
bound and expire their own results; the reference `Accounts.maintenance` does so.

For current deployments, use **8.0.0** for every Spoonbill module and rebuild
consumers; public configuration and transport signatures have changed. Follow the
[8.0 migration guide](migration-v8.md), including the required V6 schema cutover
and exclusion of older writers before retention-aware providers start.
Deploy compatible serving binaries and refresh browser pages onto their client
bundle. When upgrading an installation that predates authenticated replacement,
follow the [V5 schema and deployment upgrade](browser-auth-v4-deployment.md#upgrade-an-existing-browser-authentication-deployment)
before enabling that feature. The fixed recovery procedure has been part of the
client bundle since 5.1.0; older live tabs need not understand it, and there is no
wire-version negotiation. Existing ordinary procedure numbers remain unchanged.
The [historical replacement implementation and validation record](authenticated-session-replacement-plan.md)
and [performance protocol](authenticated-session-replacement-performance.md)
retain their original evidence and limits.

## Providers and transaction ownership

| Provider | Atomic domain | Restart behavior |
| --- | --- | --- |
| `InMemoryBrowserPersistence[F, H, P]` | Immutable host `H` and browser records publish under one process monitor; callbacks use `Direct`. | Authority is lost; old cookies and capabilities require fresh login. |
| `JdbcBrowserPersistence[F, P]` | Host SQL, browser session, ceremony outcome and encrypted delivery share one PostgreSQL transaction. | Durable records allow the original attempt to be recovered after process loss. |

The memory host implements pure `Host.resolve` and `Host.acknowledge` over `H`.
Its optional pure `Host.maintenance` expires host-owned material in the same
immutable publication as browser maintenance.
It must not hide mutable references or external effects inside that state.
`JdbcHostSessions[P]` resolves a fresh immutable `HostPrincipal[P]` and
acknowledges activation using the supplied connection. Resolve host account
locks before session locks; preserve host → slot → view ordering. Subject and
session identity must remain immutable, and policy changes must advance their
versions. The application owns password/factor algorithms, proof replay rules,
rate limiting, account revocation and domain audit policy.

JDBC defaults to `JdbcTransactionExecutor[F]` on the supplied blocking executor.
An `existingRunner` must implement the same actual outermost settlement contract;
it is not permission to nest an independently committing transaction. Both
concrete providers use `Direct` transaction callbacks. The portable
`TransactionalBrowserPersistence[F, G, P]` interface separates application effect
from transaction program without claiming that these providers execute arbitrary
asynchronous `G` programs.

The JDBC constructor requires an explicit `BrowserOwnership` mode. Selecting
`BrowserOwnership.ExclusiveScope` is an
explicit deployment promise that authority changes and presentation ownership
are coordinated through the owning integration; it is not automatic distributed
cache invalidation. Realm-level transaction advisory locking serializes provider
admission and retained-capacity checks. Raw authority writes outside that
contract are unsupported.

Select `BrowserOwnership.SharedViews` for overlapping web nodes using the
supported shared PostgreSQL integration. It revalidates shared authority and
fences ownership per view; the [clustered guide](clustered-browser-authentication.md)
describes publication, recovery and explicit presentation projections.

## JDBC deployment and credential custody

The [deployment guide](browser-auth-v4-deployment.md) includes the explicit
PostgreSQL migration, writer ownership, pool sizing and upgrade/restore contract.

Call `provider.initialize(connection)` in an explicit deployment transaction,
alongside the application's schema initialization. Requests never create tables.
It creates the framework browser tables plus `spoonbill_auth_ceremony`,
`spoonbill_auth_material` and `spoonbill_auth_clock`. The example adds separate
`example_*` host tables. Preserve durable ceremony outcomes and generation/view
fences; expired authority is not permission to delete replay history.

Supply a `JdbcCredentialVault`. `JdbcCredentialVault.aesGcm(key)` provides an
explicit single-key adapter; there is no default key. A deployment can supply a
key-ID envelope and rotation policy through the interface. Keep the necessary
keys across restart for the delivery retention period. Ciphertext authenticates
the provider scope, browser binding and completion identity. Delivery checks
current host authority and expiry before decrypting, and activation, logout or
maintenance retires unusable material.

`JdbcBrowserClock` durably checkpoints observed time independently of domain
rollback. A new instance reads the checkpoint before using its clock. Under the
provider's exclusive writer contract, equal/backward samples reuse only a locally
acknowledged checkpoint; newer samples must commit another checkpoint. Shared
clock writers without that opt-in consult storage on every observation. An earlier
clock after restart therefore cannot revive already observed expiry. A domain transaction may
hold its connection while that checkpoint runs: provide a reserved `clockSource`
or enough pool capacity for domain transactions plus a clock connection. These
are real additional transactions, not a free or implicit clock guarantee.

## Recovery and release

Keep the original ceremony ID when preparation reports `CommitUnknown` or an
uncertain storage failure. Call `browser.recover(context, id)` without submitting
the password or factor again. Recovery excludes an earlier writer using the
durable ceremony fence. It returns either `Committed(receipt)` for authorized
delivery or a safely established `NotCommitted`; absence alone is not rollback.
Recovery never recreates executable business-operation authority.

Offer recovery while the original ceremony remains in presentation, including
after a successful commit whose WebSocket completion command was lost. New
connections receive matching `BrowserContinuation` metadata from the provider.
Expiry, revocation and logout can deny delivery without changing the earlier
transaction's committed disposition.

`BrowserPersistenceLimits`, coordinator admission and
`BoundedPresentationStore.Limits` bound their respective resources. Exhaustion
fails closed; security history is not silently evicted. Schedule
`browser.maintenance()` with one owned, nonoverlapping timer on every coordinator;
construction starts no scheduler, and provider-only maintenance is insufficient.
With default retention windows, start with a 30-second interval and monitor failed
or delayed ticks. Review the [lease, reclamation, and capacity contract](request-admission-lifecycle.md#required-application-maintenance-and-observability)
when changing these windows. Stop new work and
the timer, drain application proof work, then await `browser.close()` before
shutting down its worker pool. JDBC close waits for admitted work and
conditionally releases its own view owners, preserving successor owners and
durable tombstones.

## Run the synthetic application

Start the native lazy IO example from the repository's Nix environment:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'browserAuthExample/runMain example.browserauth.LazyMain 8080'
```

`LazyMain` uses `Application[IO]` and `MemoryAccounts[IO]`, including the same
password/factor, recovery, protected-action and logout forms. Proof admission and
the host workflow stay in IO. `Resource` owns the actor system, worker executor,
server binding and browser release. The maintenance loop is sequential IO work;
there is no detached authentication fiber or Future-based account implementation
hidden behind IO. Only Pekko's transport lifecycle APIs cross a Future boundary.
`Effect.blocking` uses the effect's blocking execution policy: the Future path
uses its supplied worker executor, while IO uses its runtime's blocking pool.

The Future memory variant uses the same implementation:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false SPOONBILL_AUTH_PROVIDER=memory \
  nix develop --no-write-lock-file --command sbt --batch --supershell=false \
  'browserAuthExample/runMain example.browserauth.Main 8080'
```

For the Future PostgreSQL variant, use an owned disposable database and
initialize a new synthetic schema explicitly:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false SPOONBILL_AUTH_PROVIDER=jdbc \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false \
  'browserAuthExample/runMain example.browserauth.Main 8080 --initialize'
```

For a restart experiment, keep that database running, retain
`SPOONBILL_AUTH_SCHEMA`, and omit `--initialize`. The example uses independent
connections and a public test vault key; it binds to loopback and is unsuitable
for real account data. The source's synthetic workload and correctness tests do
not establish a performance improvement or a completed release benchmark gate.
