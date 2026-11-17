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

The uninstrumented default retains the original `start` implementation. It does
not call instrumentation hooks or allocate a child-completion observation Future.

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

The performance comparison uses one shared workload with version-specific
instrumentation adapters, fresh frozen classpaths and complete child cleanup
checks. Historical fixtures and results are retained separately under
[`misc/stream-operator-cost`](../misc/stream-operator-cost/README.md).

## Validation and measured limits

The repository's complete Nix CI command passed: 2,192 ScalaTest cases and 41
native ZIO cases, with six existing cleanup-basis cancellations. The command was:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

The log is `/tmp/spoonbill-effect-instance-api-full-ci.log`. The ZIO framework's
58-result summary includes its 17 ScalaTest cases; those are not counted twice.
Focused tests also preserve custom promise/runAsync behavior, startup refusal,
dispatch pauses, native cancellation/context and complete child observation.

The [27-row stream comparison](../misc/stream-operator-cost/v3/README.md) meets
the predeclared 0.95 throughput / 1.10 allocation guides against both compact7
and the original operator baseline. Relative to compact7, throughput ratios are
0.99594 (Hub), 0.96485 (sort) and 0.99555 (zero-duration buffer); allocation ratios
are effectively 1.0. Sort is therefore 3.52% lower on the observed median and
close to the guide, not proven zero-cost. All rows and ranges are retained.

The [canonical-instance runtime comparison](../misc/effect-instance-runtime-cost/README.md)
also passes all four current-implementation-relative gates: minimum throughput
98.35%, maximum delivery p99 104.38% and maximum allocation 100.051%. This uses
24 fresh trials and the unchanged live-producer fixture, without instrumentation
on either default instance. Its reference is compact7 `5d71d676`, not original
main; it does not close the separate final original-main aggregate gate.
