# Migrating to Spoonbill 11: the extension contract

Spoonbill **11.0** gives extensions one contract for legacy and guarded
sessions. It changes two signatures and several behaviours. Only applications
that register extensions are affected. An application with an empty
`SpoonbillServiceConfig.extensions` needs no change.

The contract and its reasons are in the
[extension contract design](extension-contract-design.md). This guide lists
what to change.

Update every Spoonbill module together and recompile all consumers. Upgrades
from before 10 must follow the [10.0 migration guide](migration-v10.md) first.
There is no database migration in this release.

## What changes

| Change | Who is affected | What to do |
| --- | --- | --- |
| `onDestroy()` becomes `onDestroy(lastState)` | Everyone | `() => stop()` becomes `_ => stop()`. Replace `access.state` in `onDestroy` with the parameter |
| Constructors take a name | Users of `Extension.apply`, `pure`, `managed`, `managedF` | Add a name |
| `Handlers.apply` takes its `Effect` in a leading `using` clause and has no `null` defaults | Callers that pass `null` or the `Effect` instance by hand | Leave the `null` argument out: it still compiles, and it is rejected when the handlers are built. Pass the instance as `Handlers(using effect)(...)` |
| `onState` is called with the attach state | An extension that reads `access.state` in `setup` to handle the first state | Delete that code. Otherwise the first state is handled twice |
| States written before delivery starts are folded into the attach state | An application whose `setup` or timer writes state during setup, if it relies on one `onState` call per write | Publish a message for a request that must be handled every time. Keep a request that must survive a reconnect in the state until it is handled |
| A failed handler is reported, and delivery continues | Everyone | Nothing. Expect error reports that were silent before |
| Callbacks of a view run one at a time | A handler that waits for a long effect | Move the long work to a fiber or actor |
| A failed `setup` fails the attach in a legacy session | Legacy sessions with an extension that may fail | Recover inside that `setup` if the extension is optional |
| `setup` is bounded by 10 seconds in a legacy session | Legacy sessions with a slow `setup` | Start slow work in `setup` without waiting for it |
| The connection is completed after setup in a legacy session | Legacy sessions whose `setup` waits for the browser: an `evalJs` result, a property read, a callback from the page. Every legacy session with extensions: the WebSocket opens after the last `setup` | Start the call without waiting for it, or make it from the first `onState` |
| With ZIO, each callback runs in a fiber of its own | ZIO extensions whose handler starts work with `.fork` | Use `.forkDaemon`, or a scope that the extension closes in `onDestroy` |
| `setup` runs in order | `Future` users who relied on a concurrent start | Start work in parallel inside one extension |
| Legacy sessions destroy in reverse order | Extensions that depend on the destroy order | Check the order |

## Source changes

These fail to compile until they are changed. One exception is named under
`Handlers.apply`.

### `onDestroy` takes the last state

The view is closed before `onDestroy` runs. A state read on `access` fails
there with `SessionAccessDenied`. This was already so in 10; the state is now
passed in.

Nothing can be written, published or sent to the browser from `onDestroy`.
`transitionForce` fails with `SessionAccessDenied` too. Other calls fail the
same way or return without an effect: a `transition` returns, and the change
is dropped.

```scala
// Before
Extension.Handlers[Future, State, Message](
  onDestroy = () => access.state.flatMap(state => unsubscribe(state.userId))
)

// After
Extension.Handlers[Future, State, Message](
  onDestroy = lastState => unsubscribe(lastState.userId)
)
```

`lastState` is the last state that was passed to the `onState` of this
extension. If none was, it is the state the view was accepted with. So an
`onDestroy` can undo what its extension did for the state it last saw.

A class that implements `Extension.Handlers` directly changes
`def onDestroy(): F[Unit]` to `def onDestroy(lastState: S): F[Unit]`.

### Constructors take a name

Failure reports name the extension. A class that extends `Extension` already
has a name and needs no change.

```scala
// Before
Extension.pure[Future, State, Message] { access => handlers }
Extension.managed[Future, State, Message, Resource](acquire, release) { (resource, access) => handlers }

// After
Extension.pure[Future, State, Message]("search") { access => handlers }
Extension.managed[Future, State, Message, Resource]("search")(acquire, release) { (resource, access) => handlers }
```

### `Handlers.apply`

Function values, method references and `{ case ... }` blocks compile as before.
Two rare call shapes change:

- An `Effect` instance that is passed by hand moves to the front:
  `Extension.Handlers(using effect)(onState = ...)`.
- An explicit `null` argument no longer means "no callback". It still
  compiles. `Handlers.apply` and `Handlers.partial` reject it with a
  `NullPointerException` that names the callback, so the `setup` that builds
  the handlers fails. Leave the argument out instead.

## Behaviour changes

These compile without a change. Check each one.

### The first `onState` call

`onState` is now called first with the state the view has when delivery
starts. Delivery starts when every `setup` has completed. In 10 this call was
missing in legacy sessions and in guarded sessions with snapshots or
reconstruction.

Delete the code that replays the first state by hand:

```scala
// Before
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
    onDestroy = () => Future.successful(worker ! Stop)
  )

// After
def setup(access: Access): Future[Extension.Handlers[Future, State, Message]] =
  startWorker(access).map { worker =>
    Extension.Handlers.partial(
      onState = { case State.Loading(request) => Future.successful(worker ! Run(request)) },
      onDestroy = _ => Future.successful(worker ! Stop)
    )
  }
```

### States written before delivery starts are folded

An extension starts with the newest state. It is not called for the states
that were written before delivery started. With `Future`, 10 delivered each of
those writes.

An example: `setup` of extension A writes `Loading(jobA)` with
`transitionForce`, and `setup` of extension B replaces it with `Loading(jobB)`.
`onState` is called once, with `Loading(jobB)`. Nothing starts job A.

A write counts from the moment the application announces it. `transitionForce`
returns after that moment. `transition` returns before it, so a write that is
requested with `transition` during setup can also arrive after delivery has
started. It is then delivered on its own.

Two rules for requests:

- A request that must be handled every time is a message. Messages are never
  folded. A message that is published during `setup` is delivered after the
  first `onState` call.
- A request that must survive a reconnect stays in the state until it is
  handled. The next request is added to it and does not replace it.

### Setup

- `setup` runs for one extension at a time, in registration order. With
  `Future`, 10 started every `setup` up front.
- Each `setup` has 10 seconds. Guarded sessions had this bound before; legacy
  sessions are new to it. Start slow work without waiting for it.
- The connection is completed after the last `setup`, in every session mode.
  Until then nothing is sent to the browser. So a `setup` must not wait for
  the browser: an `evalJs` result, a property read or a callback from the page
  cannot arrive before it completes. Such a `setup` now fails, and the attach
  with it. Guarded sessions had this rule before. In 10 a legacy session ran
  its `setup` calls next to the connection, so they could wait for the
  browser. Start the call without waiting for it, or make it from the first
  `onState`:

```scala
// Before: worked in a legacy session of 10. It fails in 11.
Extension[Future, State, Message]("time zone") { access =>
  access.evalJs("Intl.DateTimeFormat().resolvedOptions().timeZone").map(zone => handlers(zone))
}

// After: start the call, and do not wait for it. The browser answers once
// the page is connected.
Extension.pure[Future, State, Message]("time zone") { access =>
  access.evalJs("Intl.DateTimeFormat().resolvedOptions().timeZone").foreach(useZone)
  handlers
}
```

- For the same reason the WebSocket of a legacy session now opens after the
  last `setup`. A slow `setup` delays the moment the page becomes live.
- A failed `setup` fails the attach in every session mode. The view is closed,
  and the extensions that are already set up are destroyed. In 10 a legacy
  session logged the failure and ran without that extension.
- A legacy attach that fails writes back the state the page was rendered
  with. A state that a `setup` wrote before the failure is not kept, because
  the browser never received it.
- An extension that should never fail an attach recovers inside its own
  `setup`:

```scala
Extension[Future, State, Message]("statistics") { access =>
  connect(access).recover { case error =>
    log.warn("Statistics are off for this view", error)
    Extension.Handlers[Future, State, Message]()
  }
}
```

### Delivery

- The callbacks of a view run one at a time, in registration order. In 10,
  `onState` and `onMessage` of one extension could overlap.
- A handler should return quickly. A handler that waits for a long effect
  delays every later notification of its view, also for other extensions. Start
  long work in a fiber or actor.
- A failed handler is reported as `Extension <name>: onState failed` and
  delivery continues. In 10 the first failure stopped delivery to every
  extension, without a report. A handler whose effect is interrupted (ZIO) or
  cancelled (cats-effect) is reported the same way.
- A callback starts in a task of the session's execution context. It does not
  run on the thread that writes the state, and not on the thread that
  completed the previous callback.
- With ZIO, each callback runs in a fiber of its own. ZIO ends the fibers that
  an effect forks when the effect ends, so work that a handler starts with
  `.fork` is interrupted when the handler returns. In 10 it lived as long as
  the view. Use `.forkDaemon`, or a scope that the extension closes in
  `onDestroy`:

```scala
// Before: the load is interrupted when the handler returns
onState = { case State.Loading(request) => load(request).fork.unit }

// After
onState = { case State.Loading(request) => load(request).forkDaemon.unit }
```

- A `{ case ... }` handler without a default case now reports a `MatchError`
  for every state or message that it does not match. Use
  `Extension.Handlers.partial` for such a handler.

### Teardown

- `onDestroy` runs in reverse setup order in every session mode. Legacy
  sessions used setup order in 10.
- `onDestroy` is the last callback. The runtime waits up to 10 seconds for the
  callback that is still running.
- Each `onDestroy` has 10 seconds. A failure or a timeout is reported, and the
  other extensions are still destroyed. A timeout is an
  `ExtensionRuntime.TimedOut` in every session mode.
- `transitionForce` fails with `SessionAccessDenied` when the view closes
  before the change was rendered. In 10 such a call never completed. A handler
  that waits for it now ends when the view closes.

## New helpers

`Extension.Handlers.partial` takes partial functions. A state or a message
that is not matched is ignored, so the "do nothing" default case goes away.

```scala
Extension.Handlers.partial[Future, State, Message](
  onState = { case State.Loading(request) => load(request) },
  onMessage = { case message: SearchMessage => search(message) }
)
```

`imapState` runs an extension that is written for a part of the state inside
an application with a larger state. A group of extensions is a plain list.

```scala
val extensions: List[Extension[Future, Page, Message]] =
  children.map(_.imapState[Page](_.ui, (page, ui) => page.copy(ui = ui)))
```

## Internal API

`ApplicationInstance.stateStream` and `ApplicationInstance.messagesStream` are
removed. They were internal and fed the old extension loops. There is no
replacement outside the `spoonbill` package: the observer that feeds the
extension runtime is private to it. Application code observes states and
messages through an extension.

## Cost

A view without extensions pays nothing for the extension runtime. With
extensions, a guarded session makes one authority observation per delivered
state and per message, whatever the number of extensions. That is the cost in
10. One case costs more: a guarded session with snapshots or reconstruction
now delivers the attach state, which is one more observation per attach.
