# Review: Explicit state-update outcomes implementation plan

Date: 2026-10-18.

Reviewed document:
[Explicit state-update outcomes: implementation plan](sensitive-acknowledgement-type-design-plan.md).
Context: [bug report](sensitive-acknowledgement-view-revision-race.md) and the
[current reproduction](../modules/spoonbill/src/test/scala/korolev/server/internal/services/NoOpSensitiveAcknowledgementSpec.scala).

This is a static review. Every runtime and API claim below was checked against
the cited sources at commit `5362feab` (HEAD, tag `v6.0.0`). No sbt, browser, or
benchmark command was run: the plan's baseline evidence is already recorded in
the bug report, and nothing in this review depends on re-running it. This review
changes no implementation, test, or build source.

**Recommendation: approve the design; revise the plan before implementation.**
The type design, the interpreter, the `ActionForms` integration, and the intent
of the test matrix are sound, and the plan's description of the runtime matches
the code. The revisions concern proportionality (Section 8), two test harnesses
the plan assumes but which do not exist, API ergonomics for `F`-generic callers,
and a few migration items that should be named rather than left to a search.

## Verified claims

| Plan claim | Evidence |
| --- | --- |
| Four unconditional update paths call `access.transition`. | [ActionForms.scala](../modules/spoonbill/src/main/scala/korolev/ActionForms.scala) lines 71, 84, 86, 87. |
| A transition always queues a render; equality is not examined. | [ComponentInstance.scala](../modules/spoonbill/src/main/scala/korolev/internal/ComponentInstance.scala) `applyTransition`, lines 369–376: `stateQueue.enqueue` follows every reducer evaluation. |
| Only DOM patches advance the guarded revision. | [Frontend.scala](../modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala) `enqueueOutput`, line 445: the revision branch requires a `[4…` frame. The `[0,…` event-counter frame emitted by `ApplicationInstance.onEvent` (lines 221–228) never advances it, so `NoUpdate` does not need to skip event-counter completion. |
| Stale revisions close the connection. | `Frontend.selectFromPublishedView`, lines 81–89. |
| Empty-markup suppression is not generally safe. | [PublishedViewAdmissionSpec.scala](../modules/spoonbill/src/test/scala/korolev/internal/PublishedViewAdmissionSpec.scala) lines 22–52 publishes empty patches with different handlers. `afterRender` is `transitionForce(identity)` (ComponentInstance lines 165–166), so equality-based suppression would be a global semantic change. |
| `Effect.delay` semantics per adapter. | [Effect.scala](../modules/effect/src/main/scala/korolev/effect/Effect.scala) lines 92–98 (eager, catches); [Zio2Effect.scala](../interop/zio2/src/main/scala/korolev/zio/Zio2Effect.scala) lines 32–33 (`ZIO.attempt(...).mapError(liftError)`); [IO3Effect.scala](../interop/ce3/src/main/scala/korolev/cats/IO3Effect.scala) lines 32–33 (`IO.delay`). |
| `access.transition` fits `(S => S) => F[Unit]`. | `Transition[S] = S => S` in [package.scala](../modules/spoonbill/src/main/scala/korolev/package.scala) line 30. |
| Baseline commit and stale `BaseVersion`. | `git rev-parse HEAD` is `5362feab…`, tagged `v6.0.0`; `build.sbt` line 30 has `BaseVersion = "5.0.0"`. |
| Referenced projects, shells, scripts, and APIs exist. | `browserAuthExample`, `browserAuthBaseline`, `zioHttp`, `standalone`, `testkit`, `ce3`, `zio2` in `build.sbt`; `devShells.browser` in `flake.nix`; `scripts/test-zio-http-browser-auth.sh`, `scripts/test-native-browsers.sh`, `scripts/with-test-postgres.sh`; `WebSocketResponse.mapOutput`; `standalone.buildServer`. |
| Control outcomes stay source-compatible. | [BrowserAuthentication.scala](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala) lines 461–518 construct `UiOutcome.CommitAuthentication`, `CommitReplacement`, and `RecoverReplacement`; the enum-case to `final case class` conversion keeps these call sites compiling. |

The chosen shape, a `sealed trait` root with `enum StateUpdate[S] extends
UiOutcome[S]`, is the right one. It yields the `onResult`/`onRejected`
narrowing by type, and the type test `Completed(update: StateUpdate[S])` is
checkable because `UiOutcome` is invariant. Keeping `NoUpdate()` as a generic
class case rather than a cast singleton is correct for an invariant parameter.

## 1. [P1] Section 8 is a sub-project larger than the fix

Reference: [Performance validation](sensitive-acknowledgement-type-design-plan.md#8-proportionate-performance-validation),
lines 327–358; completion criterion 7 at line 23.

The code change replaces `Updated(new StateUpdate(f))`, two allocations, with
`Modify(f)`, one allocation, and adds one `match` in `ActionForms.bind`.
Section 8 proposes five new files, a two-tree build with injected adapters,
provenance hashing, at least 30 calibration and 30 comparison pairs per
workload, and a 2,700-second budget. The plan itself predicts the probable
result: a comparison whose uncertainty cannot distinguish a regression is
inconclusive. There is no cheap reuse either: `CoreGuardedSessionBenchmark`
measures session lifecycle and never exercises typed actions.

Keep the structural gates in Section 8; they are code-review facts plus unit
tests. Remove the benchmark from completion criterion 7 and move the rest of
Section 8 into its own optional document. That also brings the plan under the
400-line file limit; it is 475 lines today.

## 2. [P1] Step 2's "real form-binding tests" have no harness

Reference: [Step 2](sensitive-acknowledgement-type-design-plan.md#step-2--integrate-interpretation),
line 228; testkit row at line 243; spy requirement in Section 6, line 301.

Nothing in the repository drives `ActionForms.onSubmit` end to end inside the
JVM:

- [ActionFormsSpec.scala](../modules/spoonbill/src/test/scala/korolev/ActionFormsSpec.scala)
  only renders HTML and inspects attributes.
- The testkit stub in [Browser.scala](../modules/testkit/src/main/scala/korolev/testkit/Browser.scala)
  (line 234) extends `BaseAccessDefault`, whose `submittedFields` and
  `actionBinding` fail with `IllegalStateException`
  ([Context.scala](../modules/spoonbill/src/main/scala/korolev/Context.scala)
  lines 456–461). A simulated `submit` on a typed form fails before dispatch.
- `testkit` depends on `spoonbill` (`build.sbt` line 320), not the reverse, so
  tests under `modules/spoonbill/src/test` cannot use `PseudoHtml.render` to
  extract the `Context.Event` that `ActionForms.bind` registers via `addMisc`.

The implementer therefore needs a private `BaseAccessDefault` subclass (about
twenty abstract members), a `StatefulRenderContext` capture for `addMisc`, and
an `InvocationBinding` carrying a `SensitiveDisclosureScope` for the sensitive
branch. The plan should state this and its location, so the work is estimated
and the spy on `transition` lands at the real binding boundary.

Alternative: extend the testkit `Browser` so typed submissions work (supply
`submittedFields` from `Browser.form(...)` data and a binding from
`ActionTestkit.binding`). This is additive, lets `Action.Transition` records
serve as the zero-transition spy, and gives consumers the same ability to unit
test `NoUpdate` versus `Modify`. The plan currently rules this out with "no
public helper change is planned"; reconsider that sentence.

## 3. [P2] The runtime regression's DOM path also lacks precedent

Reference: [Section 6](sensitive-acknowledgement-type-design-plan.md#6-test-matrix-and-deterministic-synchronization),
steps 3 and 7 at lines 295 and 299.

No existing test sends a revision-bearing `[7,"conn:revision:0:…submit:…"]`
frame through a guarded `Frontend` into `ActionForms`. `FormSubmissionSpec`
covers decoding only, and `SensitiveDomAdmissionSpec` sends unguarded
`[0,"counter:target:click"]` frames. The path is feasible: take the connection
id from the `[19,…` reset frame, the form target from `allEventHandlers`
filtered on `submit`, and encode fields as `name=value&…` with URL encoding,
matching [FormSubmission.decode](../modules/spoonbill/src/main/scala/korolev/internal/FormSubmission.scala)
lines 20–44. Because the plan forbids falling back to `runUserAction`, it should
spell out this frame construction.

Step 5's completion signal is sound: `onEvent` emits `[0,"target","submit",n]`
only after `aux(allEffects)` completes, so awaiting that frame proves the typed
handler finished, including the skipped transition.

## 4. [P2] Removing every `UiOutcome`-typed constructor penalizes `F`-generic code

Reference: [3.1 One closed hierarchy](sensitive-acknowledgement-type-design-plan.md#31-one-closed-hierarchy),
removal list at lines 89–93 and inference guidance at line 134.

[Application.scala](../examples/browser-auth/src/main/scala/example/browserauth/Application.scala)
is generic over `F` (line 19) and has seven `F.pure(UiOutcome.…)` sites. After
the change, `F.pure(StateUpdate.Modify(...))` infers `F[StateUpdate[Page]]`,
not `F[UiOutcome[Page]]`: Scala 3 widens an enum constructor only to the enum
type, and an abstract `F` offers no covariance. The plan's remedy is an explicit
`Effect[F].pure[UiOutcome[S]](...)` at every such site, in the framework's own
reference example.

Add `UiOutcome.modify[S](reduce): UiOutcome[S]` and
`UiOutcome.noUpdate[S]: UiOutcome[S]`, and consider `StateUpdate.noUpdate[S]`.
These are widening helpers with new names, not aliases for the removed
identity-as-unchanged behavior, so they do not conflict with the plan's
"one clear API" stance. `StateUpdate.NoUpdate[Page]()` at every "ignore"
callback is otherwise the most frequent expression in the migration.

## 5. [P2] Name the compile-negative tests that will pass for the wrong reason

Reference: [Step 3](sensitive-acknowledgement-type-design-plan.md#step-3--migrate-all-compiled-consumers),
line 259.

[SensitivePayloadSpec.scala](../modules/spoonbill/src/test/scala/korolev/sensitive/SensitivePayloadSpec.scala)
lines 93–98 wrap `StateUpdate.unchanged` inside `typeCheckErrors` and assert
only that errors are non-empty. Once `unchanged` is removed, the test keeps
passing without checking the intended bypass. It needs the new constructor and
an assertion on the error message. [InputSchemaSpec.scala](../modules/spoonbill/src/test/scala/korolev/action/InputSchemaSpec.scala)
lines 97–106 use `UiOutcome.update` but already assert on the `policy` message,
so it is safe but should still be migrated. List both in the Step 3 table.
`SensitiveDisclosureSpec`, `SensitiveDepartureAdmissionSpec`, and
`SensitiveDomAdmissionSpec` are also unnamed; the `rg` audit catches those.

## 6. [P2] Two behavior changes belong in the migration note

Reference: [Step 6](sensitive-acknowledgement-type-design-plan.md#step-6--document-migration-and-prepare-release-review),
lines 273–275.

- ZIO failure channel. Today `onRejected(reason)` and `onResult(result)` are
  evaluated inside a `flatMap` continuation, so a thrown exception becomes a
  ZIO defect. Routing them through `Effect.delay` (`ZIO.attempt`) moves them to
  the typed failure channel. This is an improvement, and it is observable to
  any consumer with defect-specific handling.
- Rejection no longer renders. Every current `onRejected` callback forces a
  render and a revision bump, including the `_ => identity` default pattern in
  `ActionFormsSpec`. Callbacks migrated to `NoUpdate` stop doing that. A
  consumer that relied on the post-rejection render must choose
  `Modify(identity)`. The plan asks the migrator to review intent per call
  site; the migration document should state the difference for consumers.

## 7. [P3] File placement

Reference: [3.1 One closed hierarchy](sensitive-acknowledgement-type-design-plan.md#31-one-closed-hierarchy),
line 46.

"Keep the root and its direct children in `action/Action.scala`" over-reads
the sealed restriction, which only requires one file. `Action.scala` is 315
lines before this change. A new `action/UiOutcome.scala` holding `UiOutcome`
and `StateUpdate` keeps both files well under 400 lines and makes the public
outcome algebra easier to find.

## 8. [P3] Minor

- Section 7 is well grounded, but it is the second-largest item. Since Step 5
  already follows the core fix, ship the browser fixture as a separate change
  so the fix is not blocked by harness work. The bug report's requirement for
  real-browser coverage is still met before release.
- `NoUpdate[Int]() == NoUpdate[String]()` is `true` under erasure. Tests should
  not rely on type-parameter-sensitive equality; this is consistent with the
  plan's "compare requested transitions, not function objects".
- The interpreter's by-name `callback` adds no eager evaluation for `Future`:
  `delay` runs inside the same `flatMap` continuation that evaluates the
  callback today, and the whole handler effect is already constructed inside
  the admitted thunk. Worth one sentence in Section 4.2 so a reviewer does not
  look for a new scheduling point.
- Many "Do not …" sentences restate repository policy from `AGENTS.md`.
  Trimming them would make the implementation steps easier to scan.

## Requested revisions before implementation

1. Cut or defer Section 8; keep its structural gates.
2. Specify the Step 2 harness, or extend the testkit `Browser` for typed
   submissions, and spell out the Section 6 DOM-submit frame construction.
3. Add `UiOutcome`-typed widening constructors with new names.
4. Name `SensitivePayloadSpec` and `InputSchemaSpec` in the Step 3 table.
5. Add the ZIO failure-channel and rejection-render changes to the migration
   note.
6. Place the outcome algebra in `action/UiOutcome.scala`.

## Verification of this review

| Check | Result |
| --- | --- |
| Read all plan-cited sources and the two referenced specs | Done; findings above cite file and line. |
| `git rev-parse HEAD`, `git tag --points-at` | `5362feab…`, `v6.0.0`; matches the plan's baseline. |
| `rg` for `UiOutcome.(Updated|update|unchanged)`, `StateUpdate.unchanged`, `StateUpdate(` across `modules`, `interop`, `examples`, `misc` | 22 files; all appear in the plan's table or its generic categories except the two compile-negative specs in finding 5. |
| `rg` for `UiOutcome|StateUpdate` in main sources | Only `Action.scala`, `ActionForms.scala`, `BrowserAuthentication.scala`, and the browser-auth example and baseline; no other interpreter of `UiOutcome` exists. |
| sbt compile or test | Not run; not required for a plan review and no source changed. |
