# Explicit state-update outcomes: implementation plan

Date: 2026-10-18.

Status: implemented and validated after [plan review](sensitive-acknowledgement-type-design-plan-review.md); see the [implementation validation report](sensitive-acknowledgement-implementation-validation.md) for the completed checks, including the real-service Chromium/WebKit regression. Publication remains a separate task.

Target: the next major release after the reported 6.0.0 behavior. Source and binary compatibility breaks are intentional. Prefer one clear API over compatibility aliases for the old identity-as-unchanged behavior.

## 1. Outcome and acceptance criteria

A typed action that acknowledges a sensitive disclosure and requests no state update must finish without creating a state transition, render, handler replacement, or guarded patch. Another action submitted from the same delivered view must remain admissible when authority, ownership, and the published view are otherwise unchanged.

Use the same explicit update model for ordinary successful actions, sensitive-disclosure completion, and rejection callbacks. Preserve the current `spoonbill.effect.Effect[F]` abstraction and execution queues. Do not add a Cats/ZIO requirement to the framework module.

Completion requires:

1. A public, closed distinction between `NoUpdate()` and `Modify(reduce)`.
2. No unconditional transition after a typed no-update result, including rejection handling.
3. Pure, intent-preserving composition and zoom, with no reducer evaluation during construction.
4. Correct eager-Future and lazy-effect behavior, including callback failure handling.
5. A deterministic typed-form regression; a real-browser regression using the actual Scala service and client is required before release and may land in a separate change.
6. Existing authority, ownership, sensitive-lifecycle, and published-view protection tests still passing.
7. All in-repository callers migrated; source/binary breaks documented; structural performance checks passed and any measured regression concern resolved. A new benchmark framework is not a completion requirement.

This fixes the unnecessary publication caused by no-update completion. A genuine later publication can still make an earlier browser revision stale. The new type is not permission to accept stale revisions or replay an action.

## 2. Evidence and relevant architecture

Read the [bug report](sensitive-acknowledgement-view-revision-race.md) and [current reproduction](../modules/spoonbill/src/test/scala/korolev/server/internal/services/NoOpSensitiveAcknowledgementSpec.scala) first. The reported source chain was confirmed and its Nix/sbt reproduction was rerun during planning: compilation passed, the new regression failed with `SessionAccessDenied`, and both `PublishedViewAdmissionSpec` tests passed.

| Source | Existing responsibility and consequence |
| --- | --- |
| `modules/spoonbill/src/main/scala/korolev/action/Action.scala` | `StateUpdate` is currently an `S => S` wrapper. Both `StateUpdate.unchanged` and `UiOutcome.unchanged` represent identity transformations, not absence of a transition. |
| `modules/spoonbill/src/main/scala/korolev/ActionForms.scala` | Ordinary updates, sensitive completion, invalid input, and dispatch rejection all call `access.transition`. |
| `modules/spoonbill/src/main/scala/korolev/internal/ComponentInstance.scala` | A transition queues a thunk. On execution it reads current state, evaluates the reducer, writes state, and queues rendering. Equality is not examined. |
| `modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala` | Rendering rebuilds handlers; publication installs the corresponding handler snapshot. DOM-event completion also publishes an event counter. |
| `modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala` | Guarded patch publication advances the revision before transport delivery. Admission checks the published revision and closes on a mismatch. |
| `modules/spoonbill/src/test/scala/korolev/internal/PublishedViewAdmissionSpec.scala` | Even an empty DOM patch can accompany different handlers. Empty markup differences cannot be used to suppress publication generally. |

The consumer-browser diagnostic is supporting evidence, not proof that its entire failure has this cause. Keep that distinction in subsequent release notes.

## 3. Public type design

### 3.1 One closed hierarchy

Create `modules/spoonbill/src/main/scala/korolev/action/UiOutcome.scala` containing the root and its direct children. Scala requires these sealed-hierarchy declarations to share a file, not a particular filename. Keep action definitions, contexts, and dispatch in `Action.scala`. Use the existing names `UiOutcome` and `StateUpdate`; replace their representations:

```scala
sealed trait UiOutcome[S]

enum StateUpdate[S] extends UiOutcome[S]:
  case NoUpdate()
  case Modify(reduce: S => S)

  def andThen(next: StateUpdate[S]): StateUpdate[S] = this match
    case NoUpdate() => next
    case Modify(first) => next match
      case NoUpdate() => this
      case Modify(second) => Modify(state => second(first(state)))

  def zoom[Parent](read: Parent => S)(
      write: (Parent, S) => Parent
  ): StateUpdate[Parent] = this match
    case NoUpdate() => NoUpdate[Parent]()
    case Modify(reduce) =>
      Modify(parent => write(parent, reduce(read(parent))))

object StateUpdate:
  def noUpdate[S]: StateUpdate[S] = NoUpdate[S]()

object UiOutcome:
  def modify[S](reduce: S => S): UiOutcome[S] = StateUpdate.Modify(reduce)
  def noUpdate[S]: UiOutcome[S] = StateUpdate.noUpdate[S]

  final case class Invalid[S](error: InputError) extends UiOutcome[S]
  final case class CommitAuthentication[S](completionId: java.util.UUID)
      extends UiOutcome[S]
  final case class CommitReplacement[S](handoff: spoonbill.auth.CommittedReplacement)
      extends UiOutcome[S]
  final case class RecoverReplacement[S](ceremonyId: spoonbill.auth.CeremonyId)
      extends UiOutcome[S]
  final case class PresentSensitive[S](
      region: spoonbill.sensitive.RegionId,
      purpose: spoonbill.sensitive.Purpose,
      disclosure: spoonbill.sensitive.SensitiveDisclosure,
      lifetime: scala.concurrent.duration.FiniteDuration,
      onResult: spoonbill.sensitive.DisclosureOutcome => StateUpdate[S]
  ) extends UiOutcome[S]
```

Preserve each existing control outcome's field names, payload, authorization rules, and construction boundaries. Do not make a new public constructor for `SensitiveDisclosure` or move plaintext into ordinary state/outcomes.

`NoUpdate()` and `Modify(reduce)` are the two data cases. `UiOutcome.modify` and `UiOutcome.noUpdate` return those same values with the wider static result type; they allocate no extra outcome wrapper. Use them for ordinary actions returning `F[UiOutcome[S]]`. Use `StateUpdate.Modify` and `StateUpdate.noUpdate` for the narrower callbacks and composition. Direct case construction remains available; all current action signatures continue to return `F[UiOutcome[S]]`.

Remove these old APIs in the major release:

- `UiOutcome.Updated`.
- `StateUpdate.apply(reduce)` and the instance `StateUpdate.apply(state)`.
- `StateUpdate.unchanged`, `UiOutcome.unchanged`, and `UiOutcome.update`.

The new helper names express the new semantics; they are not compatibility aliases for identity-as-unchanged. Keep conversion explicit. A private test evaluator may pattern-match the ADT; do not reintroduce the erased public `apply(state)` API for test convenience.

`StateUpdate[S]` and `UiOutcome[S]` remain invariant. `S => S` consumes and produces `S`; covariance is not sound here. `NoUpdate()` is a generic case and can allocate. Do not use `@uncheckedVariance`, a cast from `StateUpdate[Nothing]`, reflection, or a null sentinel to manufacture a universal singleton.

No new `Refresh`, conditional-update, effectful-reducer, or general command-sequencing case is required for this fix. Existing explicit transitions and `Modify(identity)` retain their rendering behavior.

### 3.2 Narrow callback results

Preserve this sensitive-completion signature in the public context and stored outcome:

```scala
onResult: DisclosureOutcome => StateUpdate[S]
```

Change both `ActionForms.onSubmit` overloads and their private binding helper to:

```scala
onRejected: ActionRejection => StateUpdate[S]
```

Neither callback may return arbitrary `UiOutcome[S]`. Their static result type permits only no update or a pure state transformation, excluding authentication commits, replacement recovery, validation outcomes, and nested disclosure outcomes. Runtime disclosure ownership checks remain essential; Scala does not enforce linear consumption or callback purity here.

Example usage:

```scala
context.presentSensitive[Page](region, purpose, payload, 30.seconds) {
  case DisclosureOutcome.BrowserProcessed => StateUpdate.noUpdate[Page]
  case _ => StateUpdate.Modify[Page](
    _.copy(message = "Delivery could not be confirmed.")
  )
}

val onRejected: ActionRejection => StateUpdate[Page] =
  _ => StateUpdate.Modify(_.copy(message = "The action was rejected."))

val ignoreRejection: ActionRejection => StateUpdate[Page] =
  _ => StateUpdate.noUpdate[Page]

// Ordinary action result; the helper supplies the intended wider type.
F.pure(UiOutcome.modify[Page](_.copy(message = "Saved.")))
```

An expected `F[UiOutcome[S]]` can guide `F.pure` inference, including typed action callbacks and nested branches; an unannotated intermediate `F[StateUpdate[S]]` stays narrow when `F` is invariant. The helpers make both styles convenient without requiring an annotation at every call site. Keep compile-time tests for both contextual inference and helper-based widening.

### 3.3 Observable composition contract

| Operation | Required result |
| --- | --- |
| `NoUpdate().andThen(x)` | Return `x` without evaluating its reducer. |
| `x.andThen(NoUpdate())` | Return `x`; do not allocate a composed reducer. |
| `Modify(f).andThen(Modify(g))` | One `Modify` that evaluates `f` then `g`; one requested transition when interpreted. |
| `NoUpdate().zoom(read)(write)` | A parent-typed `NoUpdate`; neither lens function is called. |
| `Modify(f).zoom(read)(write)` | Transform the selected child using the parent state supplied when the queued transition executes. |
| `Modify(identity)` | Remain a modification, including when composed or zoomed. |
| Two reducers whose results cancel out | Remain a modification; do not inspect/evaluate functions to discover equivalence. |

Left/right identity and associativity are observational laws for pure reducers. Zoom laws assume lawful getter/setter pairs. Compare results and requested transitions, not function-object equality or type-parameter-sensitive equality of `NoUpdate` values: generic arguments are erased. Arbitrarily deep reducer chains are not a new stack-safety guarantee.

## 4. Integration with the existing effect system

### 4.1 Use the current abstraction and existing queues

Relevant contracts are in:

- [Effect.scala](../modules/effect/src/main/scala/korolev/effect/Effect.scala) and [syntax.scala](../modules/effect/src/main/scala/korolev/effect/syntax.scala).
- [IO3Effect.scala](../interop/ce3/src/main/scala/korolev/cats/IO3Effect.scala).
- [Zio2Effect.scala](../interop/zio2/src/main/scala/korolev/zio/Zio2Effect.scala).
- [InvocationAdmission.scala](../modules/spoonbill/src/main/scala/korolev/action/InvocationAdmission.scala).
- [ComponentInstance.scala](../modules/spoonbill/src/main/scala/korolev/internal/ComponentInstance.scala).

`StateUpdate` is pure data and has no `F` parameter or `Effect` requirement. The action performs business effects through its existing `F[UiOutcome[S]]`; the framework later interprets the result through `Effect[F]` and `access.transition`.

| Existing effect behavior | Implementation rule |
| --- | --- |
| `Effect.pure(value)` has a strict argument. | Do not use `pure(onResult(...))` or `pure(onRejected(...))` to capture callback exceptions. |
| `FutureEffect.delay` executes immediately and catches exceptions. | It is not a scheduling barrier. Establish ordering with the existing surrounding `flatMap`, admission queue, and transition thunks. |
| Cats IO and ZIO `delay` defer evaluation to execution. | Evaluate application callbacks inside `Effect.delay`, so ordinary thrown callback errors enter the effect failure channel. |
| `Effect.delayAsync` suspends construction and flattens an `F`. | Keep its existing admission/freshness usage. Do not move action construction out of the final admitted thunk. |
| Component transitions store `() => F[Unit]` for eager effects. | Pass the reducer to `access.transition`; do not read state, invoke it, or construct a transition before selecting `Modify`. |
| Normal `transition` completes enqueueing, not rendering. | Retain that contract. Do not substitute `transitionForce` for the implementation or for convenient test waiting. |
| `afterRender` internally calls `transitionForce(identity)`. | Never use it to await a no-update completion; it introduces a render itself. |

The state read and reducer evaluation remain inside `ComponentInstance`'s existing serialized pending-effect thunk. That ensures modifications use the current queued state rather than a state captured when a disclosure was first created. No changes to `Effect`, its adapters, the queue implementation, or legacy `BaseAccess` transition methods are needed.

### 4.2 One small internal interpreter

Add `modules/spoonbill/src/main/scala/korolev/action/StateUpdateInterpreter.scala`, with `private[spoonbill]` visibility. Taking the existing transition function keeps the helper independently testable without adding public `Context.Access` methods or another execution runtime:

```scala
private[spoonbill] object StateUpdateInterpreter:
  def apply[F[_]: Effect, S](update: StateUpdate[S])(
      transition: (S => S) => F[Unit]
  ): F[Unit] = update match
    case StateUpdate.NoUpdate() => Effect[F].unit
    case StateUpdate.Modify(reduce) => transition(reduce)

  def callback[F[_]: Effect, S](update: => StateUpdate[S])(
      transition: (S => S) => F[Unit]
  ): F[Unit] =
    val F = Effect[F]
    F.flatMap(F.delay(update))(value => apply(value)(transition))
```

The direct interpreter accepts an already-produced value. The callback entry point evaluates its by-name expression once per execution of that callback effect. For Future, `delay` runs inside the same post-acknowledgement or rejection continuation that invokes the callback today; this adds no scheduling boundary and does not construct the callback before admission. Lazy effects can be run again explicitly; existing invocation ownership and sensitive consumption remain responsible for replay protection.

In `ActionForms.bind`, use these branches:

| Result | Handling |
| --- | --- |
| `Completed(update: StateUpdate[S])` | `StateUpdateInterpreter(update)(access.transition)` |
| `Completed(PresentSensitive(..., onResult))` | Preserve `disclosure.consume(binding.sensitiveOwner)(access.presentSensitive(...))`; inside its completion `flatMap`, invoke `StateUpdateInterpreter.callback(onResult(outcome))(access.transition)`. |
| `Completed(Invalid(error))` | `StateUpdateInterpreter.callback(onRejected(ActionRejection.Input(error)))(access.transition)` |
| `Rejected(reason)` | `StateUpdateInterpreter.callback(onRejected(reason))(access.transition)` |
| Authentication/replacement controls | Preserve existing dedicated operations and checks. |
| `Superseded()` / `OutputSuppressed(_)` | Preserve current skip/retirement behavior; no rejection callback or action replay. |

Keep `Effect`/syntax imports consistent with the surrounding repository. Do not introduce `.unsafeRun*`, `toFuture`, `runAsyncForget`, blocking, detached fibers, or new cancellation masking in this path. The interpreter must run in the caller's existing effect context.

### 4.3 Failures and lifecycle

- A throwing `onResult` or `onRejected` fails its callback effect through `Effect.delay`; no transition is requested. Preserve the existing surrounding event error handling and cleanup.
- With ZIO, ordinary callback exceptions currently thrown inside `flatMap` are defects; `Effect.delay` uses `ZIO.attempt(...).mapError(liftError)`, making them typed failures. Document and test this deliberate change. Existing `Effect.recover`/`recoverF` handle both channels, so not every outer observer will see different behavior.
- A throwing `Modify` reducer follows the existing queued-transition error/reporting path. Since normal `transition` completes enqueueing, do not promise that its caller observes a later reducer/render failure.
- Admission, state-dependent authorization, output authorization, ownership epochs, sensitive acknowledgement validation, expiry, cancellation, and one-shot payload consumption remain in their current owners.
- `NoUpdate` skips only state-update work. It does not skip action admission, policy checks, sensitive acknowledgement processing, event-counter completion, or resource retirement.
- Preserve current caller-local behavior. Do not assert that existing background component workers inherit all caller fiber-local values; test that this change adds no new context switch at the callback/interpreter boundary.

## 5. Implementation sequence and file scope

### Step 1 — Implement the algebra and its tests

Move the outcome declarations from `action/Action.scala` to the new `action/UiOutcome.scala`. Implement the algebra and widening helpers above; convert the control cases to explicitly generic final case classes with the same names/fields. Preserve `PublicActionContext.presentSensitive` and dispatcher checks. Add `action/StateUpdateSpec.scala` for algebra and compile-time API tests.

Use an isolated prototype to compile the new declarations before changing execution code. The production module will temporarily fail to compile while `ActionForms` and callers still refer to removed APIs; the first integrated compile is after Steps 1–3. Verify subtype matching is exhaustive without casts or unchecked variance, and that representative Future/IO/ZIO action return expressions type-check with explicit widening where needed. Do not add temporary production compatibility aliases just to make intermediate edits compile.

### Step 2 — Integrate interpretation

Add the internal interpreter and replace the four unconditional update paths in `ActionForms.scala`. Change both rejection callback signatures. Add `StateUpdateInterpreterSpec.scala`, `ActionFormsExecutionSpec.scala`, and a private `modules/spoonbill/src/test/scala/korolev/ActionFormsRuntimeFixture.scala` in package `spoonbill`. Keep the existing HTML-attribute checks in `ActionFormsSpec`.

The private fixture must execute the actual `ActionForms` binding, not duplicate its interpreter:

1. Render `form(ActionForms.onSubmit(...))` with an anonymous `avocet.impl.Html5RenderContext[Binding]` subclass overriding `addMisc`. Capture the actual `Context.Event`; assert one bubbling `submit` event, then invoke its `effect(access)`. Production `server/internal/Html5RenderContext.scala` already demonstrates this subclass pattern. No testkit dependency or new rendering abstraction is needed.
2. Implement a test-only `Context.BaseAccessDefault[Future, S, Unit]`. Supply immutable `submittedFields`, a fresh `actionBinding` per invocation, controlled `state`, and a `transition` spy that records the reducer without evaluating it. For authenticated cases, override `authenticatedActionBinding` with the test binding and provide an explicit test authority. Count `retireReplacement` calls where suppression is tested.
3. Implement the remaining abstract property, focus, publish, form/file, session, JavaScript, callback, event-data, transition-variant, and `afterRender` methods as explicit unexpected-operation failures unless a test needs them. This is a private harness; avoid permissive defaults that hide unwanted work.
4. Give the binding a real `SensitiveDisclosureScope` with a deterministic fake `Frontend.RpcDeadlineScheduler`, following `SensitiveDisclosureScopeSpec`. Let the registered action create its disclosure through the real `PublicActionContext.presentSensitive` and dispatcher. Stub access-level `presentSensitive` to signal entry and await a separate `Promise[DisclosureOutcome]`.
5. Start the captured event effect, await presentation entry, assert no completion callback/transition, resolve the acknowledgement-result promise, then await the event effect. Inspect zero/one recorded transitions and evaluate a recorded reducer explicitly when required. Cover callback throws and all completion outcomes. Bound cleanup, settle pending promises, close the disclosure scope, and assert no pending holders remain.

This fixture proves binding/dispatch/interpretation behavior. Actual wire acknowledgement checks, queued-state timing, and view admission belong to the runtime/browser tests. The current testkit browser lacks typed fields/bindings and cannot substitute for this fixture; public typed-submission support can be a separate feature.

Do not change production `Frontend`, `ApplicationInstance`, `ComponentInstance`, `SensitiveRuntime`, or browser protocol code merely to make the regression pass. If a required invariant cannot be met at the outcome boundary, report the concrete reason and revise the plan before broadening the fix.

### Step 3 — Migrate all compiled consumers

Search `modules`, `interop`, `examples`, and `misc`, not only the framework package. Known required sites include:

| Area | Files / work |
| --- | --- |
| Public browser-auth example | `examples/browser-auth/src/main/scala/example/browserauth/Application.scala`, `RecoveryResults.scala`: constructors, callbacks, and pure reducer composition. |
| Compiled reference application | `misc/browser-auth-baseline/app/ReferenceApplication.scala`: this compiles against current projects despite its name; migrate source, preserve historical results/manifests. |
| ZIO HTTP fixture | `interop/zio-http/src/test/scala/korolev/zio/http/ZioHttpBrowserAuthFixture.scala`: outcomes, disclosure completion, rejection callback. |
| Action and lifecycle tests | `ActionSpec`, `ActionFormsSpec`, `BrowserAuthenticationSpec`, sensitive ownership/scope/runtime specs, and departure/admission specs under `modules/spoonbill/src/test`. |
| Compile-negative tests | `modules/spoonbill/src/test/scala/korolev/sensitive/SensitivePayloadSpec.scala` and `action/InputSchemaSpec.scala`: update embedded source and check the intended diagnostic, with positive controls. |
| Additional sensitive callers | `SensitiveDisclosureSpec`, `SensitiveDepartureAdmissionSpec`, and `SensitiveDomAdmissionSpec`: migrate the explicit outcome constructors. |
| Other project tests | `interop/zio2/.../TypedActionSpec.scala`, `modules/security-jdbc/.../JdbcBrowserPersistenceSpec.scala`, `examples/browser-auth/.../RecoveryResultsSpec.scala`, `MemoryAccountsIOSpec.scala`, and `MemoryReplacementTestAccess.scala`. |
| Testkit | Run existing tests; keep legacy transitions unchanged. This fix uses the private fixture above, not a new public typed-submission API. |

Migration rules:

| Old use | New use |
| --- | --- |
| `StateUpdate(f)` | `StateUpdate.Modify(f)` |
| `UiOutcome.update(f)` | `UiOutcome.modify(f)` |
| `UiOutcome.Updated(update)` construction | Return `update` directly |
| `StateUpdate.unchanged` / `UiOutcome.unchanged` meaning no work | `StateUpdate.noUpdate[S]` / `UiOutcome.noUpdate[S]`, respectively |
| Old unchanged call intentionally forcing a render | `StateUpdate.Modify[S](identity)` |
| Rejection `_ => identity` meaning ignore | `_ => StateUpdate.noUpdate[S]` |
| Rejection callback returning a reducer | Wrap that reducer in `StateUpdate.Modify` |
| `Updated` pattern/type assertions | Match the precise `Modify` or `NoUpdate` case expected by that test |
| `update(state)` in tests | Match `Modify(reduce)` and run `reduce(state)`; assert `NoUpdate` separately |

Review identity-reducer intent at each call site. In `SensitivePayloadSpec`'s `UiOutcome.presentSensitive` bypass test, replace the removed `StateUpdate.unchanged` and assert the unavailable bypass diagnostic, not merely nonempty errors. In `InputSchemaSpec`, replace `UiOutcome.update` while retaining the missing-`policy` diagnostic check. Add positive controls to distinguish valid new constructors from deliberately prohibited calls.

### Step 4 — Add runtime and cross-effect regression coverage

Replace the current reproduction's manual post-ack `transitionForce(identity)` with the actual typed form path. Preserve an explicit identity-modification companion case proving the guard still rejects stale revisions after a requested publication.

Add lazy adapter coverage in `interop/ce3/src/test/scala/spoonbill/action/StateUpdateIOSpec.scala` and `interop/zio2/src/test/scala/korolev/zio/StateUpdateSpec.scala`. Existing project dependencies already expose Spoonbill to these test scopes; no library dependency is required.

### Step 5 — Add browser integration and complete release validation

The browser fixture may land separately after the core API/fix changes; it must pass before the major release. Implement section 7, connect its runner to the existing Linux native-browser CI job, and complete the structural performance checks in section 8. Add targeted measurements only where they resolve a concrete concern; a new benchmark framework does not block this step.

### Step 6 — Document migration and prepare release review

Create `docs/migration-v7.md` if 7.0.0 is still the next major at implementation time; otherwise use the selected next-major filename. Document the new constructors/helpers and patterns, rejection callback type, no-update versus identity modification, and unchanged low-level transition semantics. Explicitly describe two behavioral changes:

- Rejection callbacks returning `NoUpdate` no longer request a render/revision bump. Callers that intentionally relied on that render must return `Modify(identity)`.
- In the ZIO adapter, ordinary exceptions thrown by completion/rejection callbacks move from defects to the typed failure channel through `Effect.delay`; defect-specific handling may need adjustment. Reducer failures retain existing queued semantics.

Link the migration note from current API/browser-auth documentation. Preserve historical design/evidence documents.

Record implementation results in this plan or a linked validation report. The build currently has a historical `BaseVersion` and release CI supplies `RELEASE_VERSION`; resolve next-major metadata through that existing release workflow. Do not assume changing `BaseVersion` alone releases a major. Publication and deployment are separate tasks.

## 6. Test matrix and deterministic synchronization

| Layer | Required checks |
| --- | --- |
| Pure algebra | Identity laws, associativity on representative pure reducers, ordered exactly-once reducer calls when evaluated, no eager reducer/lens evaluation, correct parent/child isolation, identity modification remains `Modify`. |
| Compile-time API | Direct update is a valid `UiOutcome`; sensitive and rejection callbacks accept `StateUpdate`; those callbacks reject authentication/replacement/nested-disclosure outcomes; removed function-like API is unavailable. Include positive controls for negative tests. |
| Interpreter with Future | `NoUpdate` makes zero transition calls; `Modify` passes one unevaluated reducer; callback called once after its preceding completion; callback exceptions become failed effects and request zero transitions. |
| Real `ActionForms` binding | Ordinary success, `Invalid`, input/access rejection, and every sensitive completion outcome honor returned `NoUpdate`/`Modify`; `Superseded` and `OutputSuppressed` retain their behavior. |
| Real guarded runtime | Actual typed disclosure submit, matching browser acknowledgement, then another typed submit using last delivered revision; unchanged state and next handler invoked once with no connection closure. |
| Negative publication controls | `Modify(identity)` and a state-changing `Modify` still publish; old revision is rejected after publication. Preserve old handler until its replacement is published. |
| Authority/ownership | Revocation or ownership change before the next action still denies it; no-update results do not bypass action policy/revalidation or sensitive owner checks. |
| Lazy effects | IO and ZIO callbacks are not evaluated when the effect is built; they run when evaluated, after the acknowledgement dependency; thrown callback errors enter the failure channel. Check helper behavior in the caller's existing local/environment context. |
| Failure/cleanup | Callback failure, disclosure failure/expiry, peer close, and held-output cancellation terminate within bounds and release owned resources. Reducer failure retains queued reporting semantics. |

For the runtime regression:

1. Use real `SessionsService`, `ApplicationInstance`, `Frontend`, and a guarded configuration with synthetic payloads and `ViewOwnershipEpoch` enabled.
2. Render two separate typed forms: reveal setup details and confirm. Distinct forms avoid conflating this race with same-control event-counter deduplication.
3. Deliver initialization and record the connection ID from `[19,epoch,connection,"0",patch]` and the last delivered revision after any subsequent `[20,...]` patches. Find each form target in `allEventHandlers` filtered to bubbling `submit`, identifying the distinct forms by their rendered IDs/markers. Send the reveal submission using the frame construction below.
4. Receive the actual presentation and send its matching acknowledgement. The callback returns `NoUpdate`.
5. Await the completed reveal event's event-counter output or a narrow test-owned completion probe. Receiving the acknowledgement or entering the callback alone is not completion.
6. Keep subsequent render patches buffered outside the simulated/browser delivery boundary. Reading a server output frame for inspection is not browser delivery; track these separately.
7. Submit the confirm form at the last delivered revision; await its handler and completion signal. Assert exactly one invocation and connection survival.

Send both submissions through the incoming stream using the real guarded frame shape:

```text
[7,"connection:revision:0:eventCounter:target:submit:encodedFields"]
```

Here `7` is `ViewEvent`, the first `0` is the inner DOM-event opcode, and `eventCounter` is that form's counter (initially zero). Encode field names and values with `java.net.URLEncoder` using UTF-8, join each pair with `=` and pairs with `&`, then JSON-escape the enclosing string. Preserve the colon after `submit` for an empty schema: `submit:` supplies an empty submission, whereas `submit` supplies none. Validate this construction against `Frontend.decodeDomEvent` and `FormSubmission.decode`; do not use direct `runUserAction` as the acceptance assertion.

The completed event-counter frame `[0,"target","submit",n]` is emitted after all selected handlers finish and does not advance the guarded revision. It is a valid completion signal for a no-update event. Existing `SensitiveDomAdmissionSpec` has authorization guarding but no ownership epoch enabling revision-bearing view events; its unstamped click frames are not this test's wire format.

Prove zero transition requests with a spy on the real form-binding execution boundary in the focused tests. Do not claim the absence of a later render solely because a finite output sample contains none: ordinary transition enqueueing and rendering run on different queues. Combine that structural assertion with the full runtime/browser behavior test. For negative cases, explicitly await the actual patch publication before submitting the stale event.

Do not insert `transitionForce`, `afterRender`, arbitrary sleeps, retries, or proof replay to arrange the race. Keep the current 10-second workflow and independent 3-second cleanup bounds for the framework regression unless measured startup needs justify a named fixture-specific bound.

Retain at least `PublishedViewAdmissionSpec`, `SensitiveDepartureAdmissionSpec`, `SensitiveDepartureBarrierSpec`, `SensitiveHistoryBarrierSpec`, `SensitiveDomAdmissionSpec`, `SensitiveRuntimeConcurrencySpec`, `SensitiveInvocationOwnershipSpec`, `SensitiveDisclosureScopeSpec`, and `ReplacementDispatchSpec`.

## 7. Real-browser fixture

Existing `misc/sensitive-native-tests.cjs` exercises real DOM behavior using synthetic transport frames. Retain it, but it cannot establish that the Scala server admits the next action correctly.

Add:

- `modules/standalone/src/test/scala/korolev/server/SensitiveAcknowledgementBrowserFixture.scala`.
- `misc/sensitive-acknowledgement-native-tests.cjs`.
- `scripts/test-sensitive-acknowledgement.sh`.

Use `standalone.buildServer` with the actual service and assembled client, bound to loopback port `0`; obtain the chosen port from the server handler instead of racing a separate port reservation. The fixture uses synthetic in-memory state and a test guard; it needs no database, account, or authentication proof.

Wrap the test service and transform WebSocket output with `WebSocketResponse.mapOutput`, preserving response disposition, cancellation, and release behavior. Add a bounded, test-owned output gate. Preserve frame order: once a held frame blocks delivery, do not send later frames around it. Inspection/completion control signals can use a separate loopback test endpoint or server promise; they must not depend on a blocked browser output frame being delivered.

The browser must receive the real disclosure and send its normal acknowledgement. The test then submits the still-visible confirm form while post-disclosure render delivery is withheld. Observe actual outgoing guarded revisions and connection identity with bounded, sanitized test metadata. No production JavaScript hook or manual replay of action frames is required.

Run Chromium and WebKit through `.#browser`. Cover successful no-update acknowledgement, explicit identity modification, real modification, and authority revocation. For modification cases, wait for observed publication at the gate before sending the stale action. Always release/cancel the gate, application, guard, server, browser, and owned executors, including on failure.

Model the runner on `scripts/test-zio-http-browser-auth.sh`: compile and export `standalone / Test / fullClasspath`, launch the owned JVM, wait for bounded readiness, run the pinned browser shell, and clean up on every exit. Startup readiness polling is permitted; it must not determine the race interleaving. Define a script-owned `SPOONBILL_UPDATE_TEST_ORIGIN` containing the selected loopback origin. Bound browser execution to 250 seconds and individual control requests to 10 seconds.

## 8. Proportionate performance validation

The intended saving is elimination of transition/render/publication work. Do not infer a numeric speedup from the type shape or from correctness tests.

Required structural checks, using code review and the focused tests:

- `NoUpdate` requests zero transitions and zero acknowledgement-triggered renders/patches.
- A `Modify` value directly implements `UiOutcome`; no `Updated` wrapper is allocated.
- Composition with `NoUpdate` reuses the nonempty operand; no reducer or lens function runs while constructing descriptions.
- No new state equality, tree traversal, additional render pass, executor hop, unbounded buffer, or per-action scheduler appears.

The callback path also adds `Effect.delay` and an effect continuation; fewer outcome wrappers alone do not prove total allocation or latency improvement. If review or profiling identifies a concrete regression concern, use a small temporary Nix-built fixture around that path, with matching baseline/candidate JVM settings, warmup, repeated samples, and an explicit time budget. The verified baseline is commit `5362feabd2ae1c5d58104ef255f5e52b2d8e7cfd`.

Measure equivalent completed work for ordinary `Modify`, including render completion when testing the full runtime. Report old forced-identity acknowledgement versus new `NoUpdate` as intentionally eliminated work, with transition/render/frame counts. Record exact commands, source identities, raw observations, and limitations; unsupported allocation counters are unavailable, not zero. A demonstrated regression must be resolved or reported explicitly.

The larger two-tree benchmark runner, adapters, provenance system, and mandatory block counts from the first draft are deferred to a separate optional performance effort. They are not needed to implement or release this fix. Existing [CoreSessionBenchmark.md](../misc/websocket-performance/CoreSessionBenchmark.md) offers measurement principles, but its workload does not exercise typed actions. Completion of this plan establishes structural performance properties, not a quantified speedup or a statistically proven universal non-regression result.

## 9. Validation commands

Run from the repository root using its `flake.nix`, Scala 3.3.8, and managed JDK. Use `--no-write-lock-file`; no lockfile update is part of this fix. Core focused checks need no service or credential. Full repository tests use the existing disposable PostgreSQL helper.

Define a shell helper for this validation session:

```sh
run_sbt() {
  env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
    nix develop . --no-write-lock-file --command env \
    JAVA_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2' \
    SBT_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dsbt.supershell=false' \
    sbt --batch --supershell=false -Dsbt.server.autostart=false "$@"
}
```

After the named new suites are implemented:

```sh
run_sbt \
  'set spoonbill / Test / parallelExecution := false' \
  'set spoonbill / Test / fork := false' \
  'spoonbill / Test / testOnly spoonbill.action.StateUpdateSpec spoonbill.action.StateUpdateInterpreterSpec spoonbill.action.ActionSpec spoonbill.action.InputSchemaSpec spoonbill.ActionFormsSpec spoonbill.ActionFormsExecutionSpec spoonbill.server.internal.services.NoOpSensitiveAcknowledgementSpec spoonbill.internal.PublishedViewAdmissionSpec' \
  'ce3 / Test / testOnly spoonbill.action.StateUpdateIOSpec' \
  'zio2 / Test / testOnly spoonbill.zio.StateUpdateSpec spoonbill.zio.TypedActionSpec'

run_sbt \
  'spoonbill / Test / testOnly spoonbill.internal.Sensitive* spoonbill.sensitive.* spoonbill.action.SensitiveInvocationOwnershipSpec spoonbill.action.ReplacementDispatchSpec spoonbill.server.internal.services.Sensitive*' \
  'testkit / Test / test' \
  'browserAuthExample / Test / compile' \
  'browserAuthBaseline / Compile / compile' \
  'zioHttp / Test / compile' \
  'standalone / Test / compile'
```

Browser and client checks (the new acknowledgement runner must pass before release, even if its fixture lands separately):

```sh
nix develop . --no-write-lock-file --command bash scripts/test-sensitive-acknowledgement.sh
nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh
nix develop . --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs
nix develop . --no-write-lock-file --command bash scripts/test-browser-auth.sh
nix develop . --no-write-lock-file --command env SPOONBILL_AUTH_PROVIDER=io bash scripts/test-browser-auth.sh
nix develop . --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh
```

After focused checks pass, run the existing full build/test gate once for the cross-project API migration:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

There is no separate static type-check command: compilation supplies it. The repository has scalafmt configured but no current whole-tree formatting CI gate. Format only touched Scala files using the existing configuration; inspect `git diff --check` and avoid unrelated whole-tree formatting changes.

Audit source migration with:

```sh
rg -n 'UiOutcome\.(Updated|update|unchanged)|StateUpdate\.unchanged|StateUpdate\(' \
  modules interop examples misc -g '*.scala'
rg -n 'onRejected|ActionForms\.onSubmit' modules interop examples misc -g '*.scala'
git diff --check
```

The first search should have no active uses of removed APIs; review intentionally negative compile tests and historical sources separately. A zero-match `rg` exit code of 1 means the audit found no matches, not a failed build.

Record each meaningful check's exact command, pass/fail, outcome, and next action for failure. Do not rerun the whole matrix after it passes unless subsequent changes or new evidence justify it.

## 10. Planning validation and implementation handoff

Planning checks already completed:

- Read the bug report, reproduction, action/form code, component/action queues, and Future/IO/ZIO effect adapters.
- Reran the report's exact Nix/sbt command for `NoOpSensitiveAcknowledgementSpec` and `PublishedViewAdmissionSpec`: compilation passed; 1 expected regression failure and 2 protection tests passed. This is baseline evidence, not a fixed build.
- Independently reviewed the type design, effect execution semantics, caller migration scope, and available browser harnesses.
- Checked the review's inference claim with Scala 3.3.8: expected types guide direct returns, typed callbacks, and representative nested branches; an unannotated intermediate `F[StateUpdate[S]]` remains narrow. The widening helpers address ergonomics, not a mandatory annotation at every call site.

A temporary type/interpreter prototype compiled and ran successfully with Scala 3.3.8 and the repository's actual `Effect[Future]`. It checked direct subtype matching, composition identity without eager reducer evaluation, no-update zoom without lens evaluation, zero transition calls for no-update, deferred reducer handoff for modification, and callback exceptions represented as failed effects. The prototype is outside the repository at `/private/tmp/spoonbill-update-design.47x8E9/StateUpdateDesignCheck.scala`; it is diagnostic scratch material, not the production implementation or a durable regression suite.

Exact prototype command run (pass; all assertions passed):

```sh
nix develop . --no-write-lock-file --command env \
  JAVA_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2' \
  SBT_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dsbt.supershell=false' \
  sbt --server --batch --no-server \
  'set spoonbill / Test / unmanagedSources += file("/private/tmp/spoonbill-update-design.47x8E9/StateUpdateDesignCheck.scala")' \
  'set spoonbill / Test / fork := false' \
  'spoonbill / Test / runMain spoonbill.plancheck.StateUpdateDesignCheck'
```

The inference check also passed via the same Nix/sbt launcher, with `Test / unmanagedSources` including both that prototype and `/private/tmp/spoonbill-update-design.47x8E9/InferenceReviewCheck.scala`, and task `spoonbill / Test / runMain spoonbill.plancheck.InferenceReviewCheck`. These temporary settings lasted only for their processes; no build or production source file changed. Recreate the scratch harnesses or use the planned permanent suites if they have been removed. They do not validate the full outcome migration, lazy adapters, or browser path.

Document checks passed after revision: `git diff --no-index --check /dev/null docs/sensitive-acknowledgement-type-design-plan.md`, and `nix develop . --no-write-lock-file --command node /private/tmp/spoonbill-update-design.47x8E9/ValidatePlan.mjs`. Results: ten local links resolved, code fences balanced, whitespace clean, and all six shell blocks passed syntax checks. These checks do not execute future implementation/build/browser commands. Independent review of the revised plan found no remaining contradictions or omissions.

At planning time, implementation, lazy-adapter runtime tests, real-browser regression, full migration build, and performance measurements had not run for the proposed fix. Current implementation results are recorded separately in the [validation report](sensitive-acknowledgement-implementation-validation.md); this historical planning evidence does not establish those gates.

Stop and revise with evidence if the fix requires relaxing revision/ownership checks, replaying proofs, skipping sensitive cleanup, or changing global transition/effect semantics. Use the repository's existing recovery policy for failed attempts.

The implementation handoff should include the migrated API and examples, exact test results, structural performance findings plus any targeted measurements, outstanding blockers, and next-major migration notes. The review remains a historical record of the original draft.
