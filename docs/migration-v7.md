# Migrating to Spoonbill 7: explicit state updates

Spoonbill [7.0.0](https://github.com/natural-transformation/spoonbill/releases/tag/v7.0.0), released on 2026-10-18, replaces identity-based “unchanged” outcomes with an explicit choice between no update and a state modification. This is a source and binary compatibility break; applications must migrate and recompile.

Update every Spoonbill module to `7.0.0` together. The Scala 3 artifacts are published on Maven Central:

```scala
libraryDependencies += "com.natural-transformation" %% "spoonbill" % "7.0.0"
```

The [user guide](user-guide.adoc) describes the current API and includes complete starter applications. When upgrading from before 6.0.0, also implement the [custom session-guard initialization contract](clustered-browser-authentication.md#custom-session-guard-initialization); the standard `BrowserAuthentication` coordinator already supplies it. Upgrading from 6.0.0 to 7.0.0 adds no database schema migration.

## Constructors and callback types

`StateUpdate[S]` now directly extends `UiOutcome[S]` and has two cases:

- `StateUpdate.NoUpdate()` requests no state transition, render, or guarded patch.
- `StateUpdate.Modify(reduce)` requests one transition using a pure `S => S` reducer.

For ordinary action results, `UiOutcome.modify` and `UiOutcome.noUpdate` return the same values widened to `UiOutcome[S]`. The helpers are useful with invariant effects and unannotated intermediate values:

```scala
// Ordinary action returning F[UiOutcome[Page]].
F.pure(UiOutcome.modify[Page](_.copy(message = "Saved.")))
F.pure(UiOutcome.noUpdate[Page])

// Sensitive completion permits only state updates.
context.presentSensitive[Page](region, purpose, payload, 30.seconds) {
  case DisclosureOutcome.BrowserProcessed => StateUpdate.noUpdate[Page]
  case _ => StateUpdate.Modify(_.copy(message = "Delivery could not be confirmed."))
}

// Both ActionForms.onSubmit overloads now use this rejection signature.
val onRejected: ActionRejection => StateUpdate[Page] =
  _ => StateUpdate.Modify(_.copy(message = "The action was rejected."))

val ignoreRejection: ActionRejection => StateUpdate[Page] =
  _ => StateUpdate.noUpdate[Page]
```

Sensitive completion remains `DisclosureOutcome => StateUpdate[S]`. Rejection callbacks change from `ActionRejection => S => S` to `ActionRejection => StateUpdate[S]`. Neither callback accepts authentication commits, replacement controls, validation outcomes, or nested disclosures.

| Removed API | Replacement |
| --- | --- |
| `UiOutcome.update(reduce)` | `UiOutcome.modify(reduce)` |
| `UiOutcome.unchanged[S]` | `UiOutcome.noUpdate[S]` for no work |
| `StateUpdate(reduce)` | `StateUpdate.Modify(reduce)` |
| `StateUpdate.unchanged[S]` | `StateUpdate.noUpdate[S]` for no work |
| `UiOutcome.Updated(update)` | Return `update` directly |
| `case UiOutcome.Updated(update)` | Match `StateUpdate.NoUpdate()` or `StateUpdate.Modify(reduce)` |
| `update(state)` | Match the cases explicitly; evaluate `reduce(state)` only for `Modify` |

There are no compatibility aliases for these removed APIs. `StateUpdate` and `UiOutcome` remain invariant. An expected `F[UiOutcome[S]]` can guide direct or nested `F.pure` returns; an already inferred `F[StateUpdate[S]]` stays narrow when `F` is invariant.

## No update versus an identity modification

A successful action, sensitive completion, or rejection returning `NoUpdate` now skips state-update work. In particular, rejection callbacks that ignore a rejection no longer force a render or revision bump. If a caller intentionally needs that work, return `StateUpdate.Modify[Page](identity)`.

`Modify(identity)` still requests a transition and render. Reducers that cancel one another also remain a modification. No state equality comparison suppresses rendering. Low-level `transition` and `transitionForce` retain their existing behavior; ordinary `transition` completes enqueueing, not rendering.

Composition and zoom preserve this intent without evaluating reducers or lens functions during construction:

```scala
val increment = StateUpdate.Modify[Int](_ + 1)
val update: StateUpdate[Page] =
  increment.zoom[Page](_.counter)((page, counter) => page.copy(counter = counter))
    .andThen(StateUpdate.Modify(_.copy(message = "Incremented.")))
```

`NoUpdate.andThen(update)` and `update.andThen(NoUpdate)` reuse the other operand. Zooming `NoUpdate` produces a parent-typed `NoUpdate` without accessing the parent or child.

## Callback failures and admission

Completion and rejection callbacks now execute through `Effect.delay`. Ordinary thrown callback errors fail the effect and request no transition. IO and ZIO defer callback evaluation until execution, after the preceding acknowledgement or rejection dependency. Future retains its eager behavior within the existing continuation.

For ZIO, ordinary callback exceptions move from defects to the typed failure channel through the adapter's `liftError`. Applications using defect-specific handling should review that handling. Reducer failures retain their existing queued transition/reporting behavior; they are not newly guaranteed to fail the action's enqueueing effect.

No-update outcomes still pass through action policy, authority, ownership, acknowledgement validation, event completion, and sensitive resource cleanup. They prevent an unnecessary acknowledgement-triggered publication. A genuine later publication still makes an earlier browser revision stale; this change does not permit stale submissions or replay.
