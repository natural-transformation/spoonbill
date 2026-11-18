# Future effect instances

`Effect[F]` is the public customization boundary. Use the standard Future
instance with `Effect.futureEffect`, create an instrumented built-in instance
with `Effect.future`, or supply a custom `Effect[Future]` through composition.
There is no public implementation class to subclass.

## Standard instance

```scala
import scala.concurrent.Future
import spoonbill.effect.Effect

given Effect[Future] = Effect.futureEffect
```

The built-in implementation family is closed. Its promise creation, Future
observation and failure normalization have fixed semantics. The stream factory
selects the typed Future callback interpreter for that same instance. It selects
the generic reader interpreter for custom instances, including wrappers around
the standard instance; an underlying Future type alone does not grant permission
to bypass a custom instance's methods.

The uninstrumented default does not call instrumentation hooks or allocate a
child-completion observation Future when starting a child.

## Explicit instrumentation

```scala
val observed: Effect[Future] = Effect.future(
  Effect.FutureInstrumentation(
    afterStart = completion => recordChildCompletion(completion)
  )
)
```

Here `recordChildCompletion` is an application-supplied function accepting a
`Future[Unit]`. It may retain that Future to observe child completion; `start`
does not join it. The hooks have these execution contracts:

- `beforeStart` runs synchronously before child dispatch. Throwing prevents
  dispatch; blocking delays it.
- `afterStart` runs synchronously after dispatch and receives a Future that
  completes when the child completes. It does not mean the child has finished.
  Throwing can fail `start` after the child has already been dispatched.
- `continuation`, when supplied, runs before delayed bodies and `map`/`flatMap`
  callbacks on their executing thread. Its exceptions follow those operations'
  failure handling. It is not an interception point for every internal stream
  notification.

These are synchronous instrumentation hooks, not isolated passive logging.
The factory chooses the implementation with continuation checks once during
construction. Instances without those checks retain the ordinary per-item
`delay`, `map` and `flatMap` bodies.

## Custom semantics through composition

A custom instance supplies its own operations and delegates the rest. For
example, a custom promise implementation can use an initialized standard
instance as its delegate:

```scala
val base = Effect.futureEffect
given custom: Effect[Future] = new Effect[Future] {
  export base.{
    pure, delay, uncancelable, fail, unit, never, fromTry, promiseF,
    flatMap, map, recover, recoverF, start, blocking, fork, sequence,
    runAsync, run, toFuture
  }

  def promise[A](register: Effect.Promise[A] => Unit): Future[A] = {
    recordRegistration()
    base.promise(register)
  }
}
```

`recordRegistration` is an application-supplied function. The derived operations
(`none`, `delayAsync` and `guarantee`) remain those of the custom instance. Its
stream interpreter calls the custom operations; it does not inherit the built-in
fast path. The delegate must be initialized before constructing the wrapper,
because `Effect` initializes its cached empty result through `pure`.

## Migration

- Replace ordinary `new Effect.FutureEffect` construction with
  `Effect.futureEffect`.
- Replace startup/continuation instrumentation subclasses with
  `Effect.future(Effect.FutureInstrumentation(...))`.
- Replace subclasses that change effect semantics with composed
  `Effect[Future]` instances. There is no public fast-path marker to forward.

Removing the public `FutureEffect` implementation class is a source and binary
API change. Clients that referenced it must migrate and recompile. Existing
uses of `Effect.futureEffect` and native effect instances retain their API.

See [ADR 0002](adr/0002-stream-routing-and-effect-instances.md) for the stream
ownership contracts, backend-selection rationale, alternatives and retained
validation/performance evidence.
