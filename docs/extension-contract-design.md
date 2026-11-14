# Extension contract: design

Status: revision 1, 2026-11-14. This is a proposal. Nothing in it is
implemented. It changes behaviour and signatures, so it targets the next major
release.

## Summary

An extension is server code that lives as long as one attached view. It is set
up once per view. It is told about state changes and published messages.
Applications use it for work that is not tied to one DOM event: subscriptions,
background loading, per-view actors.

The contract is thin today, and legacy and guarded sessions do not agree on it.
There are six problems. Throwaway specs against `SessionsService` confirmed the
first five; see [verified assumptions](#verified-assumptions).

1. **One failing handler silences every extension.** After one failed
   `onState`, no extension of that view gets another state change. `onMessage`
   behaves the same. Nothing is reported, and the session stays up.
2. **The first state is not delivered reliably.** Legacy sessions never deliver
   the state a view starts with. Guarded sessions deliver it only in one
   configuration, as a side effect of another write.
3. **`onDestroy` cannot read state.** The view is closed before `onDestroy`
   runs, so `access.state` fails.
4. **`setup` fails differently in each mode.** A legacy session logs a failed
   `setup` and runs without that extension. A `setup` that never completes is
   worse: no extension is ever notified, and the session is never cleaned up. A
   guarded session fails the attach in both cases.
5. **Callbacks overlap, and their order depends on the effect type.** `onState`
   and `onMessage` of one extension can run at the same time. With `Future`, a
   later `setup` runs even when an earlier one has failed.
6. **Extensions cannot be composed.** An application that wraps a group of
   extensions rebuilds setup with rollback, fan-out and ordered teardown by
   hand.

The proposal:

1. **One runtime, one contract.** A single internal class serves legacy and
   guarded sessions.
2. **`onState` starts with the state at attach.** After that it reports every
   write, in order.
3. **One ordered lane per view.** Callbacks never overlap. A failed callback is
   reported with the extension's name, and delivery continues.
4. **Strict setup.** `setup` runs in order, is bounded, and fails the attach
   when it fails.
5. **Strict destroy.** `onDestroy` runs once, last, in reverse order. It
   receives the last state, because `access` is closed by then.
6. **API cleanup.** Constructors take a name. The handler builder takes partial
   functions and has no `null` defaults.
7. **Two combinators:** `imapState` and `combine`.

Two things are deliberately not proposed: DOM events for extensions, and
messages from the router. [Events and extensions](#events-and-extensions) gives
the reasons.

## Goal and limits

- Give extension authors one contract that holds in every session mode and with
  every effect type.
- Make "the view arrived in this state" observable, so applications stop
  replaying the first state by hand.
- Keep one misbehaving extension from breaking the others.
- Keep every guarded-session property. Each delivery is still checked against
  current authority first.
- Not covered: durable jobs (an extension still ends with its view), delivery
  across reconnects (a new attach is a new extension instance), DOM events in
  extensions, messages from the router or the state loader, and back pressure
  per extension.

## Current model

```scala
trait Extension[F[_], S, M] {
  def name: String
  def setup(access: Context.BaseAccess[F, S, M]): F[Extension.Handlers[F, S, M]]
}

trait Handlers[F[_], S, M] {
  def onState(state: S): F[Unit]
  def onMessage(message: M): F[Unit]
  def onDestroy(): F[Unit]
}
```

See [`Extension`](../modules/spoonbill/src/main/scala/korolev/Extension.scala)
and
[`SessionsService`](../modules/spoonbill/src/main/scala/korolev/server/internal/services/SessionsService.scala).

- `SpoonbillServiceConfig.extensions` lists the extensions of an application.
- `setup` runs when a connection attaches to a view (WebSocket or long
  polling). It does not run for the HTTP page render.
- `access` is the top-level component's access, without event data. It can read
  and change state, publish messages, run JavaScript, read and set element
  properties, and register browser callbacks.
- `onState` is fed by writes of the top-level state. Writes of nested component
  state are filtered out.
- `onMessage` is fed by `access.publish` on the top level. A nested component's
  `publish` goes to its parent's handler, not to extensions.
- `onDestroy` runs when the view ends.
- Each view runs two independent loops: one for states, one for messages. Each
  loop calls every extension and waits for all of them before it takes the next
  item.

### Observed behaviour

All rows were run with `Future`, unless marked "from reading".

| Behaviour | Legacy session | Guarded session |
| --- | --- | --- |
| The state at attach reaches `onState` | No | Yes without snapshots, because the "connected" state is written again. No with snapshots or reconstruction (from reading) |
| A state written by another extension during `setup` | Delivered | Delivered |
| A handler fails once | No extension gets another notification of that kind. Nothing is reported. The session stays up | Same |
| `access.state` inside `onDestroy` | Fails with `SessionAccessDenied` | Same |
| A `setup` fails | Logged. The session runs without that extension | The attach fails. The other extensions are destroyed |
| A `setup` never completes | No extension is ever notified. After the connection closes, the view stays registered and no `onDestroy` runs | The attach fails after 10 seconds |
| Setup order | Every `setup` starts up front (from reading) | A later `setup` runs even when an earlier one failed |
| Destroy order | Setup order | Reverse setup order |
| `onState` and `onMessage` of one extension | Can overlap | Can overlap (from reading: same two loops) |
| One `onState` that takes long | Later states wait, for every extension. Messages still flow | Same (from reading) |

With a lazy effect such as cats-effect `IO` or ZIO, some rows differ (from
reading). `setup` calls and handlers run one after another. The state loop
subscribes only after every `setup` has completed, so a state written during
`setup` is missed.

### How a large application uses extensions

PairTime is the largest known consumer. It serves as a design reference here, as
in earlier design documents. Its usage shows the gaps:

- It registers one extension that wraps about ten child extensions. The wrapper
  narrows the application state for its children, applies its own checks before
  it passes a notification on, and tears the children down in order.
- Each child starts one supervised actor per view in `setup`, forwards one
  family of messages from `onMessage`, and stops the actor in `onDestroy`.
- Its router cannot start work. So the router returns a "loading" state that
  names the work to do. Extensions pick that request out of the state in
  `onState`.
- The first state does not arrive. So six children read `access.state` in
  `setup` and handle the first state by hand. One of them needs a guard against
  handling the same request twice.
- Almost every `onState` and `onMessage` ends with a "do nothing" default case.

## Events and extensions

A recurring question is why extensions "do not support events". The answer
depends on the kind of event.

| Kind | Reaches an extension today? | After this proposal |
| --- | --- | --- |
| A published message (`access.publish`) | Yes, in `onMessage` | Same |
| A top-level state write, including one caused by browser navigation | Yes, in `onState` | Same |
| The state at attach: first page, deep link, reconnect | Not reliably | Yes, in `onState` |
| A browser callback (`Spoonbill.invokeCallback`) | Yes, if the extension registered it | Same |
| A nested component's event | Only if the parent's handler publishes it | Same |
| A DOM event | Only if a view handler publishes a message for it | Same, by design |

### DOM events stay in the view

A DOM handler belongs to a rendered node. `event("click")` is a binding that
the render collects for that node. When the browser sends an event, the runtime
looks up the handlers of the render the browser is showing, drops stale events,
and gives the handler an access that carries that event
([`ApplicationInstance.onEvent`](../modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala)).

An extension is not part of the render. It has no node to bind to and no event
to carry. A listener for the whole view would also let a browser run server
code that its current view never offered. In a guarded session that would break
a useful property: a browser can only invoke what its current, authorized view
declares.

So the bridge stays as it is. The view handles the DOM event and publishes a
message: `event("click")(_.publish(Refresh))`. A signal that starts in
page JavaScript can use `access.registerCallback`; see
[`EvalJsExample`](../examples/evalJs/src/main/scala/EvalJsExample.scala).

### The router does not publish messages

The router and the state loader return a state and nothing else. They run for
the HTTP page render, before any extension exists, so there is nobody to
deliver a message to. A message is also held in memory only. It would be lost
on a reconnect or a restart. State survives both.

So a request that must outlive a reconnect belongs in the state. What is
missing today is a reliable way for an extension to see that state. Proposal 2
adds it.

## Proposal

### 1. One runtime for both session modes

A new internal class, working name `ExtensionRuntime`, exists once per attached
view. It owns setup, delivery and teardown. Legacy and guarded session creation
both call it, so the contract is implemented once.

A guarded session passes two hooks: a check to run before a state is delivered,
and a check to run before a message is delivered. They are the checks that run
today. A legacy session passes none.

A view without extensions gets no runtime. Nothing is subscribed, buffered or
checked for it.

### 2. `onState` starts with the state at attach

The rule: after every `setup` has completed, `onState` is called with the state
the view has at attach. After that it is called once for every write of the
top-level state, in write order.

The steps at attach:

1. The application instance is initialized. A guarded session writes its
   "connected" state where it does so today.
2. The runtime subscribes to top-level state writes and to published messages.
3. The runtime reads the current top-level state. This is the attach state.
4. `setup` runs for each extension.
5. Delivery starts with the attach state. Everything captured since step 2
   follows.

What follows from this:

- A write made during `setup` is delivered after the attach state. Nothing is
  lost, with any effect type.
- A write that lands between steps 2 and 3 is delivered twice: once as the
  attach state, once as a write. So the guarantee is "at least once, in order".
  A handler must treat a repeated state as harmless.
- The runtime does not compare states. A write that stores an equal value is
  still delivered, as today.

### 3. One ordered lane per view

All notifications of a view pass through one queue, the lane. One loop reads
it.

- For each notification the loop calls the extensions in registration order,
  one after another. The next call starts when the previous one has completed.
- So no two callbacks of a view run at the same time. An extension can keep
  plain local state.
- States arrive in write order. Messages arrive in publish order. No order is
  promised between a state and a message. A handler that needs the current
  state reads `access.state`.
- The behaviour is the same for `Future` and for lazy effects.

A handler should return quickly. Long work belongs in the extension's own
fiber or actor. A handler must not wait for a later notification of its own
view, because that notification cannot be delivered before the handler returns.

A failed callback does not stop the lane:

- Each call is wrapped. A failed effect and a thrown exception are treated the
  same.
- The failure is reported once:
  `reporter.error("Extension <name>: onState failed", error)`.
- Delivery continues with the next extension and the next notification.
- A handler failure never closes the session.
- A `SessionAccessDenied` from a handler is not reported while the view is
  closing. It is the expected result of using `access` then.

### 4. Setup

- `setup` runs for one extension at a time, in registration order.
- Each `setup` is bounded by 10 seconds, the bound that guarded sessions use
  today.
- `setup` fails closed. If one fails or runs out of time, the extensions that
  are already set up are destroyed in reverse order, and the attach fails. This
  holds for both session modes.
- If a `setup` completes after its bound, its handlers are destroyed at once.
- If the connection closes during setup, the remaining `setup` calls are
  skipped, and the extensions that are already set up are destroyed.

An extension that should never fail an attach recovers inside its own `setup`
and returns empty handlers.

In a legacy session, user events are not held back during setup. Their messages
and state writes are captured from step 2 on and are delivered once setup is
done.

### 5. Destroy

The steps when a view ends, for any reason:

1. The runtime stops the lane. No new delivery starts.
2. The view is closed as today.
3. The runtime waits for the delivery in flight, for at most 10 seconds.
4. `onDestroy(lastState)` runs for each extension in reverse setup order, one
   after another. Each call is bounded by 10 seconds. A failure is reported and
   never stops the rest of the cleanup.

The guarantees:

- `onDestroy` runs exactly once for every extension whose `setup` completed.
- It is the last callback. One exception: if the delivery in flight outlasts
  the wait in step 3, `onDestroy` can overlap with it. That is reported.
- `access` is closed during `onDestroy`. Calls on it fail with
  `SessionAccessDenied`.
- `lastState` is the last state the runtime delivered to this view's
  extensions. Before any delivery it is the attach state.

A view ends when the browser closes or leaves the page, when the idle timeout
fires, when access is denied or revoked, when the view is replaced, or when the
attach itself fails. The current documentation names only the first case.

### 6. API changes

```scala
trait Extension[F[_], S, M] {
  def name: String
  def setup(access: Context.BaseAccess[F, S, M]): F[Extension.Handlers[F, S, M]]

  /** Runs this extension inside an application with a larger state. */
  def imapState[S0](read: S0 => S, write: (S0, S) => S0)(using Effect[F]): Extension[F, S0, M]
}

object Extension {

  trait Handlers[F[_], S, M] {
    def onState(state: S): F[Unit]
    def onMessage(message: M): F[Unit]
    def onDestroy(lastState: S): F[Unit]
  }

  object Handlers {
    def apply[F[_], S, M](using F: Effect[F])(
      onState: PartialFunction[S, F[Unit]] = PartialFunction.empty,
      onMessage: PartialFunction[M, F[Unit]] = PartialFunction.empty,
      onDestroy: S => F[Unit] = (_: S) => F.unit
    ): Handlers[F, S, M]
  }

  def apply[F[_], S, M](name: String)(
    setup: Context.BaseAccess[F, S, M] => F[Handlers[F, S, M]]
  ): Extension[F, S, M]

  def pure[F[_]: Effect, S, M](name: String)(
    setup: Context.BaseAccess[F, S, M] => Handlers[F, S, M]
  ): Extension[F, S, M]

  def managed[F[_]: Effect, S, M, R](name: String)(
    acquire: Context.BaseAccess[F, S, M] => F[R],
    release: R => F[Unit]
  )(handlers: (R, Context.BaseAccess[F, S, M]) => Handlers[F, S, M]): Extension[F, S, M]

  /** One extension made of several. */
  def combine[F[_]: Effect, S, M](name: String)(
    children: List[Extension[F, S, M]]
  ): Extension[F, S, M]
}
```

`managedF` changes the same way as `managed`.

- **`onDestroy` takes the last state.** See [destroy](#5-destroy).
- **Constructors take a name.** Failure reports name the extension. Today every
  extension built with `Extension.apply`, `pure` or `managed` is reported as
  "unnamed".
- **The builder takes partial functions.** A state or message that a handler
  does not match is ignored. The "do nothing" default case goes away. A total
  function, a method reference and a `{ case ... }` block are all accepted.
  `onDestroy` stays a total function, so cleanup is never skipped by accident.
- **No `null` defaults.** The effect is taken in a leading `using` clause, so
  the defaults can use it.

The `Handlers` trait keeps total methods. A class that implements it directly
only changes the `onDestroy` signature.

### 7. Combinators

**`imapState`** runs an extension that is written for a part of the state
inside an application with a larger state. `read` and `write` are total
functions. The child's `access` is the application's access through the same
pair. The child's `onState` and `onDestroy` receive `read(state)`. Messages
pass through unchanged.

**`combine`** builds one extension from several.

- Setup runs in order. If a child fails, the children that are already set up
  are destroyed in reverse order, and the combined `setup` fails.
- Each callback goes to every child, in order. A failing child does not stop
  the others. When all have run, the combined callback fails with the first
  failure. The other failures are attached as suppressed. Each failure names
  its child.
- Destroy runs in reverse order. Every child is destroyed, even if one fails.

The runtime is the same ordered loop over `config.extensions`. The only
difference is that it reports each failure instead of passing it on. One
implementation serves both.

A wrapper that guards a group of extensions then becomes small. It builds
`Extension.combine("children")(children).imapState(read, write)`, calls its
`setup`, and wraps the handlers it gets back.

## Breaking changes and migration

| Change | Who is affected | What to do |
| --- | --- | --- |
| `onState` is called with the attach state | An extension that reads `access.state` in `setup` to handle the first state | Delete that code. Otherwise the first state is handled twice |
| A failed handler is reported, and delivery continues | Everyone | Nothing. Expect error reports that were silent before |
| Callbacks of a view run one at a time | A handler that waits for a long effect | Move the long work to a fiber or actor |
| A failed `setup` fails the attach in a legacy session | Legacy sessions with an extension that may fail | Recover inside that `setup` if the extension is optional |
| `setup` is bounded by 10 seconds in a legacy session | Legacy sessions with a slow `setup` | Start slow work in `setup` without waiting for it |
| `setup` runs in order | `Future` users who relied on a concurrent start | Start work in parallel inside one extension |
| Legacy sessions destroy in reverse order | Extensions that depend on the destroy order | Check the order |
| `onDestroy()` becomes `onDestroy(lastState)` | Everyone | `() => stop()` becomes `_ => stop()`. Replace `access.state` in `onDestroy` with the parameter |
| Constructors take a name | Users of `Extension.apply`, `pure`, `managed`, `managedF` | Add a name |
| The builder takes partial functions | Nobody has to change | Default cases can be removed |

Guarded sessions that use neither snapshots nor reconstruction already receive
the attach state through `onState`. For them the first row changes nothing.

Before:

```scala
def setup(access: Access): Future[Extension.Handlers[Future, State, Message]] =
  for {
    worker <- startWorker(access)
    // The first state does not arrive, so replay it by hand.
    _ = access.state.foreach {
          case State.Loading(request) => worker ! Run(request)
          case _                      => ()
        }
  } yield Extension.Handlers(
    onState = {
      case State.Loading(request) => Future.successful(worker ! Run(request))
      case _                      => Future.unit
    },
    onMessage = {
      case message: SearchMessage => Future.successful(worker ! Handle(message))
      case _                      => Future.unit
    },
    onDestroy = () => Future.successful(worker ! Stop)
  )
```

After:

```scala
def setup(access: Access): Future[Extension.Handlers[Future, State, Message]] =
  startWorker(access).map { worker =>
    Extension.Handlers(
      onState = { case State.Loading(request) => Future.successful(worker ! Run(request)) },
      onMessage = { case message: SearchMessage => Future.successful(worker ! Handle(message)) },
      onDestroy = lastState => Future.successful(worker ! Stop(lastState))
    )
  }
```

The release needs a migration guide in the style of
[the 10.0 guide](migration-v10.md).

## Why security is unchanged

- **Checks before delivery.** A guarded session still checks each state
  notification together with the current state, with one fresh authority
  observation. It still checks each message against the current state. A
  failed check still denies the view and closes the connection.
- **The attach state.** It passes the same check as any other state before it
  is delivered.
- **Isolation.** Only failures of the extension's own code are isolated. A
  failed check is not a handler failure.
- **`lastState`.** It is a state that already passed its check, because it is
  the last one that was delivered. A state that was written but never delivered
  is not handed to `onDestroy`. `access` is closed during `onDestroy`, so
  nothing can be written or sent to the browser from there.
- **DOM events.** They stay in the view. The server code that a browser can
  reach is unchanged.
- **Setup.** Failing the attach is stricter than what legacy sessions do today.
- **Waits.** Every new wait is bounded, so extension code cannot hold up
  teardown forever.

## Verified assumptions

Three throwaway specs ran at commit `d39c52c1`. The first two ran against
`SessionsService`, with `Future`, in both session modes. All three were deleted
after the run. Step 1 of the [implementation plan](#implementation-plan) turns
the first two into permanent specs.

1. **Notification probe.** Three extensions: one that fails on one state and
   one message, one that records what it receives and reads `access.state` in
   `setup` and in `onDestroy`, and one that writes state during `setup`. It
   gave the rows on the attach state, writes during `setup`, handler failure
   and `access.state` in `onDestroy`.
2. **Lifecycle probe.** A `setup` that fails, a `setup` that never completes,
   the call order of setup and destroy, and an `onState` that stays pending
   while another state and a message arrive. It gave the remaining rows. The
   overlap case ran in a legacy session only.
3. **Builder shape.** The proposed `Handlers.apply` compiles on Scala 3.3.8,
   with a leading `using` clause and defaults that use the effect. These call
   shapes compile: a total function, a method reference, a `{ case ... }`
   block, type arguments inferred from the expected type, and the same inside
   a `for` comprehension that yields the handlers. An unmatched state and an
   unmatched message are ignored.

Not run: lazy effects, and guarded sessions with snapshots. Those rows come
from reading the code.

## Costs and risks

- **The first `onState` call is new for most applications.** An extension that
  still replays the first state handles it twice until that code is removed.
- **One lane.** A slow `onState` now delays `onMessage` for the same view, and
  the reverse. Today the two loops are independent. Rendering is not affected:
  it does not wait for extensions.
- **One call at a time.** With `Future`, the handlers of one notification start
  together today. They will run one after another, so a slow extension delays
  the ones after it.
- **A handler that never completes stalls the lane of its view.** Today it
  stalls one of the two loops. The session's idle timeout still ends the view.
- **Fail-closed setup in legacy sessions.** A bug in an extension now keeps the
  view from attaching. Today it only disables that extension.
- **Teardown can take longer when handlers hang.** The wait for the delivery in
  flight and each `onDestroy` have their own 10-second bound, and they run in
  sequence. A guarded view cannot reattach until its cleanup is done. Without
  hanging handlers nothing changes.
- **The lane is unbounded**, like the queues it replaces.
- **Review cost.** Session creation is security-sensitive code. Moving the
  extension loops out of it must not change the order of the guarded checks.

## Alternatives considered

- **Deliver DOM events to extensions.** Rejected; see
  [DOM events stay in the view](#dom-events-stay-in-the-view).
- **Let the router or the state loader publish messages.** Rejected; see
  [the router does not publish messages](#the-router-does-not-publish-messages).
- **A separate `onStart(state)` handler.** It would not change `onState`. But it
  gives two callbacks for one idea, and most extensions would pass the same
  function to both. One guarded configuration already delivers the attach state
  through `onState`.
- **Run `onDestroy` before the view is closed, so `access` still works.**
  Rejected. Guarded teardown closes admission first on purpose. Extension code
  could otherwise write state or send output while the view is being retired.
- **Read the final state from storage for `onDestroy`.** Rejected. It can hand
  over a state that never passed its check, and it adds a storage read to
  teardown.
- **One lane per extension.** Extensions would not delay each other. It needs
  one loop per extension, and it gives up a simple order across extensions. It
  can be added later without changing the contract of a single extension.
- **Skip intermediate states when the lane falls behind.** Rejected.
  Applications use `onState` to react to a state that names work to do. A
  skipped state would be a lost request.
- **Keep the extension loops in `SessionsService`.** Rejected. The file is
  already large, and the two session modes would keep two copies of the
  contract.

## Implementation plan

1. **Specs first.** Turn the probes into permanent specs that state the new
   contract. They fail on the current code.
2. **The ordered loop.** Implement setup with rollback, delivery to every child
   and reverse destroy once. Expose it as `Extension.combine`.
3. **`ExtensionRuntime`.** Add the lane, the attach state, the bounds, failure
   reports and `lastState` on top of the loop.
4. **Wire both session modes.** Replace the extension code in guarded and
   legacy session creation. Make the state subscription explicit; today it is
   created eagerly with `Future` and lazily with other effects. Keep the order
   of the guarded checks.
5. **API changes.** `onDestroy(lastState)`, named constructors, the builder,
   `imapState`.
6. **Documentation.** The scaladoc of `Extension`, the extension section of the
   user guide, the two examples that use extensions, a migration guide and the
   changelog.

Steps 2 to 4 change behaviour without changing a signature. Step 5 is the
source-breaking part. Applications migrate after the release, each on its own
plan.

## Test plan

Each case runs in a legacy and in a guarded session. The cases that depend on
evaluation order also run with a lazy effect.

- The attach state is the first `onState` call, in every configuration:
  legacy, guarded without snapshots, guarded with snapshots, guarded with
  reconstruction.
- A state written during `setup` arrives after the attach state.
- States arrive in write order. Messages arrive in publish order.
- A handler that fails, and one that throws, are reported with the extension's
  name. Later notifications reach every extension.
- While one callback is pending, no other callback of that view starts.
- `setup` calls run in order. A failed `setup` destroys the earlier extensions
  in reverse order and fails the attach. A later `setup` does not run.
- A `setup` that never completes fails the attach after the bound. A late
  result is destroyed.
- `onDestroy` runs once per extension, in reverse order, after the last
  delivery, with the last delivered state. A failing `onDestroy` does not stop
  the others or the rest of the cleanup.
- `access` fails with `SessionAccessDenied` inside `onDestroy`.
- A guarded check that fails before a delivery still denies the view. The
  handlers are not called for that notification.
- A view without extensions creates no lane and runs no check per change.
- `imapState`: the child sees the focused state, and its transitions change
  only that part.
- `combine`: setup rollback, a failing child, reverse destroy.
- The builder ignores an unmatched state and an unmatched message.

## Open questions

1. **Should one handler call have a time bound?** A bound would keep one
   hanging handler from stalling the lane. But the lane would then move on
   while that call still runs, which breaks "one at a time" for that
   extension.
2. **Should the lane have a size bound?** If so, what happens when it is full?
   Messages cannot be dropped silently.
3. **Is 10 seconds right for `setup` in a legacy session?** It could become a
   setting.
4. **Should legacy sessions hold user events back until setup is done?**
   Guarded sessions already do. Nothing is lost without it, but the first user
   event can run before an extension is ready.
5. **Should `onDestroy` also get the reason the view ended?** No use is known
   yet.
6. **Should a state and a message have a defined order?** One useful rule: a
   message published after `transitionForce` returns is delivered after that
   state. It needs both to enter the lane at their source, not through two
   loops.
7. **Are required names and a partial-function builder the right taste?** Both
   are source-breaking for small gains. They can be dropped without touching
   the contract.
