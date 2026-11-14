# Extension contract: design

Status: revision 2, 2026-11-14. This is a proposal. Nothing in it is
implemented. It changes behaviour and signatures, so it targets the next major
release. Revision 2 folds in one review; see [review record](#review-record).

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
6. **Extensions are hard to reuse.** An extension that is written for a part of
   the application state needs a hand-written wrapper. An application that
   wraps a group of extensions rebuilds setup with rollback, fan-out and
   ordered teardown by hand.

The proposal:

1. **One runtime, one contract.** A single internal class serves legacy and
   guarded sessions. It is the only owner of extension lifecycles.
2. **`onState` starts with the state at attach.** After that it reports every
   write once, in order. It never reports an older state after a newer one.
3. **One ordered lane per view.** Callbacks never overlap. A failed callback is
   reported with the extension's name, and delivery continues.
4. **Strict setup.** `setup` runs in order and is bounded. When one fails, the
   runtime closes the view, destroys the extensions that are set up, and fails
   the attach.
5. **Strict destroy.** `onDestroy` runs once, last, in reverse order. It
   receives the last state that passed its check, because `access` is closed by
   then.
6. **API cleanup.** Constructors take a name. `Handlers.apply` loses its `null`
   defaults. A new `Handlers.partial` takes partial functions.
7. **Reuse through `imapState`.** A group of extensions stays a plain list, so
   the runtime owns every lifecycle.

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
- Not covered: an extension that sets up other extensions itself. The runtime's
  guarantees end at that extension.
- Not covered: a `setup` that acquires something and then never completes. The
  runtime cannot release what it was never given.

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

The runtime is the only owner of extension lifecycles. It holds the handlers of
every extension, bounds every call, and decides when an extension is destroyed.

A guarded session passes two hooks: a check to run before a state is delivered,
and a check to run before a message is delivered. They are the checks that run
today. A legacy session passes none.

A view without extensions gets no runtime. Nothing is recorded, buffered or
checked for it.

### 2. `onState` starts with the state at attach

Two terms:

- The **admitted state** is the state a view is accepted with. In a legacy
  session it is the state the application instance is created with. In a
  guarded session it is the state that passes the guarded check when the
  attachment is accepted. That is the "connected" state where a guarded
  session writes one.
- The **attach state** is the first state an extension receives.

The steps at attach:

1. The application instance is created. From this moment every write of the
   top-level state is recorded for the runtime, in the order the application
   instance publishes them. No write can be published before recording starts.
2. The instance is initialized. A guarded session writes and checks its
   "connected" state where it does so today. This settles the admitted state.
3. `setup` runs for each extension.
4. Delivery starts. The attach state is the newest recorded write. If nothing
   was written, it is the admitted state. Older recorded writes are dropped.
5. Every later write is delivered once, in order.

The guarantees:

- The first `onState` call carries the attach state. It happens once.
- Every write that is published after the attach state is delivered once, in
  publish order.
- An extension never receives a state that is older than one it has already
  received.

There is no separate read of "the current state" next to the recording.
Revision 1 had one, and two writes between subscribing and reading could be
delivered out of order. Recording from the moment the instance exists removes
that gap: the admitted state and the recorded writes form one ordered history.

The states that are dropped in step 4 were written before any extension could
observe the view. The newest one stands for them, as the state after a
reconnect stands for everything before it. A write made during `setup` is
therefore not lost. It is either the attach state, or a newer state replaced
it.

Publish order is the order in which the application instance announces writes
for rendering. The render loop sees the same order. The runtime does not
compare states. A write that stores an equal value is still delivered, as
today.

Messages are recorded from step 1 too. They are delivered after the attach
state, in publish order.

### 3. One ordered lane per view

All notifications of a view pass through one queue, the lane. One loop reads
it.

- For each notification the loop calls the extensions in registration order,
  one after another. The next call starts when the previous one has completed.
- So no two callbacks of a view run at the same time. An extension can keep
  plain local state.
- States arrive in publish order. Messages arrive in publish order. No order is
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
- The runtime keeps the handlers of an extension from the moment its `setup`
  completes.
- `setup` fails closed, in both session modes. This applies when a `setup`
  fails, when it runs out of time, and when the connection closes during
  setup.

The steps when setup does not finish:

1. The runtime skips the `setup` calls that have not started.
2. It closes the view.
3. It destroys the extensions that are set up, in reverse order, as in
   [destroy](#5-destroy). `access` is closed in these `onDestroy` calls too.
   They receive the admitted state.
4. The attach fails.

So rollback is owned by the runtime, not by any extension. The view is always
closed before an `onDestroy` runs.

A `setup` that is still running when the runtime gives up is not cancelled. An
effect such as `Future` cannot be. If it completes later, its handlers are
destroyed at once. If it never completes, whatever it acquired stays acquired.

An extension that should never fail an attach recovers inside its own `setup`
and returns empty handlers.

In a legacy session, user events are not held back during setup. Their messages
and state writes are recorded and are delivered once setup is done.

### 5. Destroy

The steps when a view ends, for any reason:

1. The runtime stops the lane. No new delivery starts.
2. The view is closed as today.
3. The runtime waits for the delivery in flight, for at most 10 seconds.
4. `onDestroy(lastState)` runs for each extension in reverse setup order, one
   after another. Each call is bounded by 10 seconds. When a call fails or runs
   out of time, that is reported, and the runtime moves on to the next
   extension and to the rest of the cleanup.

The guarantees:

- `onDestroy` runs exactly once for every extension whose `setup` completed.
- It is the last callback. One exception: if the delivery in flight outlasts
  the wait in step 3, `onDestroy` can overlap with it. That is reported.
- `access` is closed during `onDestroy`. Calls on it fail with
  `SessionAccessDenied`.
- `lastState` is the last state that was delivered to this view's extensions.
  If no state was delivered, it is the admitted state.

Both sources passed the guarded check: a state is checked before it is
delivered, and the admitted state is checked when the attachment is accepted.
`onDestroy` never receives a state that failed its check, and never one that
was written but not checked.

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
      onState: S => F[Unit] = (_: S) => F.unit,
      onMessage: M => F[Unit] = (_: M) => F.unit,
      onDestroy: S => F[Unit] = (_: S) => F.unit
    ): Handlers[F, S, M]

    /** Like `apply`, but a state or message that is not matched is ignored. */
    def partial[F[_], S, M](using F: Effect[F])(
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
}
```

`managedF` changes the same way as `managed`.

- **`onDestroy` takes the last state.** See [destroy](#5-destroy).
- **Constructors take a name.** Failure reports name the extension. Today every
  extension built with `Extension.apply`, `pure` or `managed` is reported as
  "unnamed".
- **`Handlers.apply` keeps total functions.** A stored function value, a method
  reference, a function literal and a `{ case ... }` block with a default case
  all keep compiling. Its defaults are no longer `null`. The effect is taken in
  a leading `using` clause, so the defaults can use it.
- **`Handlers.partial` is new.** A state or message that a handler does not
  match is ignored, so the "do nothing" default case goes away. `onDestroy`
  stays a total function, so cleanup is never skipped by accident.

`partial` matters more once failures are isolated. A `{ case ... }` handler
without a default case stops all delivery today. Under this proposal it would
report a `MatchError` for every notification it does not match. `partial` says
"only these" without either effect.

The `Handlers` trait keeps total methods. A class that implements it directly
only changes the `onDestroy` signature.

`managed` keeps one cleanup of its own. When building the handlers fails after
`acquire`, it releases the resource inside `setup`, before the runtime learns
of the failure. The view is still open then. `release` receives only the
resource, not `access`.

### 7. Reuse through `imapState`

`imapState` runs an extension that is written for a part of the state inside an
application with a larger state. `read` and `write` are total functions. The
child's `access` is the application's access through the same pair. The child's
`onState` and `onDestroy` receive `read(state)`. Messages pass through
unchanged.

`imapState` wraps exactly one extension. `setup` and `onDestroy` pass straight
through, so the runtime still owns the lifecycle and every guarantee holds.

A group of extensions stays a plain list:

```scala
val extensions: List[Extension[F, Page, Message]] =
  children.map(_.imapState[Page](_.ui, (page, ui) => page.copy(ui = ui)))
```

The runtime sets up, notifies and destroys each element itself. That is the
ordered loop with rollback that a wrapper has to write by hand today.

There is no combinator that sets up other extensions inside its own `setup`.
Revision 1 proposed one. Such a combinator hides its children from the runtime,
and it cannot keep the guarantees of this document; see
[alternatives](#alternatives-considered).

An application can still write an extension that owns other extensions. The
guarantees then end at that extension. It must bound, roll back and destroy its
children itself.

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
| `Handlers.apply` takes its `Effect` in a leading `using` clause and has no `null` defaults | Callers that pass `null` or the `Effect` instance by hand | Leave the `null` argument out. Pass the instance as `Handlers(using effect)(...)` |
| `Handlers.partial` is new | Nobody has to change | Handlers with a "do nothing" default case can move to it |

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
    Extension.Handlers.partial(
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
- **`lastState`.** It is the last delivered state, or the admitted state. Both
  passed their check. A state whose check failed is never handed to
  `onDestroy`, and neither is a state that was written but not checked. This
  holds when a `setup` fails before any delivery, and when the first delivery
  is rejected.
- **Closed access in `onDestroy`.** The runtime closes the view before every
  `onDestroy`, also when it rolls back a failed setup. Nothing can be written
  or sent to the browser from there.
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
3. **Builder shapes.** Both builders compile on Scala 3.3.8 with a leading
   `using` clause and defaults that use the effect.
   - `apply` accepts a stored function value, a function literal, a
     `{ case ... }` block with a default case, and no arguments. Type arguments
     can be left out, also inside a `for` comprehension that yields the
     handlers.
   - `partial` accepts `{ case ... }` blocks, also inside a `for`
     comprehension. An unmatched state and an unmatched message are ignored.
   - A stored function value does not compile against a `PartialFunction`
     parameter. The review found this. It is why `apply` keeps total
     functions.

Not run: lazy effects, and guarded sessions with snapshots. Those rows come
from reading the code. The proposal itself is not implemented, so its
sequences were checked by reasoning against the current code, not by running
them.

## Costs and risks

- **The first `onState` call is new for most applications.** An extension that
  still replays the first state handles it twice until that code is removed.
- **States before delivery are folded into the attach state.** An extension
  does not see the intermediate states that were written before delivery
  started.
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
- **A `setup` that acquires something and then never completes keeps it.** The
  runtime destroys only what it was given. `managedF` has this property when
  its handler effect hangs. Today is the same.
- **No owning combinator.** An application that wraps a group of extensions in
  one extension keeps its hand-written lifecycle code, and the guarantees stop
  at that wrapper.
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
- **Subscribe to writes, then read the current state (revision 1).** Rejected.
  Two writes between the two steps are delivered as newer, older, newer.
- **Deliver every recorded write, starting with the admitted state.** It keeps
  every write. But an extension would first receive states from before it
  existed, and in one guarded configuration it would receive the admitted
  state twice. The newest state says the same in one call.
- **Run `onDestroy` before the view is closed, so `access` still works.**
  Rejected. Guarded teardown closes admission first on purpose. Extension code
  could otherwise write state or send output while the view is being retired.
- **Read the final state from storage for `onDestroy`.** Rejected. It can hand
  over a state that never passed its check, and it adds a storage read to
  teardown.
- **Give `onDestroy` an optional state.** Rejected. Every handler would have to
  deal with the empty case. The admitted state is always there.
- **A combinator that owns its children, `combine` (revision 1).** Rejected.
  The runtime sees one extension, so it cannot bound or destroy the children.
  If a child `setup` never completes, the children before it are never
  destroyed. If a child `onDestroy` never completes, the remaining children are
  never destroyed. And the combinator has to roll back a failed child `setup`
  while the view is still open. A list keeps ownership in the runtime. A safe
  owning combinator needs what open question 8 asks for.
- **Partial-function parameters on `Handlers.apply` (revision 1).** Rejected.
  A stored function value stops compiling.
- **One lane per extension.** Extensions would not delay each other. It needs
  one loop per extension, and it gives up a simple order across extensions. It
  can be added later without changing the contract of a single extension.
- **Skip intermediate states when the lane falls behind.** Rejected.
  Applications use `onState` to react to a state that names work to do. A
  skipped state would be a lost request. The attach state is the one exception:
  it stands for what was written before any extension could observe the view.
- **Keep the extension loops in `SessionsService`.** Rejected. The file is
  already large, and the two session modes would keep two copies of the
  contract.

## Implementation plan

1. **Specs first.** Turn the probes into permanent specs that state the new
   contract. They fail on the current code.
2. **Recording.** The application instance records top-level writes and
   published messages from its construction, when the application has
   extensions. Today the state subscription is created eagerly with `Future`
   and lazily with other effects.
3. **`ExtensionRuntime`.** Ordered and bounded setup, rollback after closing
   the view, the lane, the attach state, failure reports, and destroy with
   `lastState`.
4. **Wire both session modes.** Replace the extension code in guarded and
   legacy session creation. Keep the order of the guarded checks.
5. **API changes.** `onDestroy(lastState)`, named constructors,
   `Handlers.partial`, no `null` defaults, `imapState`.
6. **Documentation.** The scaladoc of `Extension`, the extension section of the
   user guide, the two examples that use extensions, a migration guide and the
   changelog.

Steps 2 to 4 change behaviour without changing a signature. Step 5 is the
source-breaking part. Applications migrate after the release, each on its own
plan.

## Test plan

Each case runs in a legacy and in a guarded session. The cases that depend on
evaluation order also run with a lazy effect.

Attach and order:

- The attach state is the first `onState` call, in every configuration:
  legacy, guarded without snapshots, guarded with snapshots, guarded with
  reconstruction.
- Two writes before delivery starts: the newer one is the attach state, and the
  older one never follows it. The test controls the order of both writes.
- A write that is published while the attach state is being delivered arrives
  after it.
- States arrive in publish order. Messages arrive in publish order.
- While one callback is pending, no other callback of that view starts.

Failures:

- A handler that fails, and one that throws, are reported with the extension's
  name. Later notifications reach every extension.
- A guarded check that fails before a delivery still denies the view. The
  handlers are not called for that notification.

Setup:

- `setup` calls run in order. A later `setup` does not run after a failed one.
- A failed `setup` fails the attach. The extensions before it are destroyed in
  reverse order. `access` fails inside their `onDestroy`, and they receive the
  admitted state.
- A `setup` that never completes fails the attach after the bound. The
  extensions before it are destroyed, so what they acquired is released. A late
  result is destroyed.
- The connection closes during setup: the remaining `setup` calls do not run.

Destroy:

- `onDestroy` runs once per extension, in reverse order, after the last
  delivery, with the last delivered state.
- An `onDestroy` that fails, and one that never completes, do not keep the
  other extensions from being destroyed, and do not stop the rest of the
  cleanup.
- `access` fails with `SessionAccessDenied` inside `onDestroy`.
- The first delivery is rejected by the guarded check: `onDestroy` receives the
  admitted state, not the rejected one.

API:

- A view without extensions creates no lane and runs no check per change.
- `imapState`: the child sees the focused state, and its transitions change
  only that part. Applied twice, it composes.
- `Handlers.apply` compiles with a stored function value. `Handlers.partial`
  ignores an unmatched state and an unmatched message.

## Review record

One review checked revision 1 against the runtime. Every finding was checked
before it was accepted.

### First review (revision 1)

A review of commit `ca493150`, 2026-11-14.

| Finding | Severity | Resolution |
| --- | --- | --- |
| The attach sequence subscribed, then read the current state, and delivered that read before the captured writes. Two writes between subscribing and reading were delivered as newer, older, newer | P1 | No separate read. Recording starts with the application instance. The attach state is the newest recorded write, or the admitted state. [Proposal 2](#2-onstate-starts-with-the-state-at-attach) states the order guarantee, and the test plan has the two-write case |
| A combined extension hid its children from the runtime. A child `setup` that never completed left the earlier children undestroyed. A child `onDestroy` that never completed blocked the others | P1 | The owning combinator is removed. A group is a list, so the runtime holds, bounds and destroys every extension. [Setup](#4-setup) and [destroy](#5-destroy) state this, and the test plan has both pending cases |
| The combination rolled back a failed child `setup` while the view was still open, so `access` still worked in the earlier child's `onDestroy` | P2 | Rollback is owned by the runtime, which closes the view first. `managed` documents the one release it performs itself |
| `lastState` fell back to the attach state before any delivery. That contradicted the claim that `onDestroy` only receives a delivered state | P2 | The admitted state is defined. `lastState` is the last delivered state, or the admitted state. The security section says the same, and the test plan covers a failed `setup` and a rejected first delivery |
| A stored `S => F[Unit]` value does not compile against a `PartialFunction` parameter, but the migration table said that nobody has to change | P2 | `Handlers.apply` keeps total functions. `Handlers.partial` is a separate builder. The migration table and the builder checks are updated |

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
7. **Should constructors require a name?** It is source-breaking for a small
   gain. It can be dropped without touching the contract.
8. **Should `setup` be able to register cleanup while it acquires?** Then a
   `setup` that never completes could not keep what it acquired, and a
   combinator could own children safely. It is a larger API change.
9. **Should one extension be able to hold a notification back from the
   extensions after it?** An application-level check for a group of extensions
   needs this. Today it is done by a wrapper that owns the group.
