# ADR 0002 — Stream routing and effect instances

Status: accepted.

## Context

Fanout, partitioning and batching need explicit capacity, terminal and ownership
contracts. Silent Hub drops and rack overwrites can lose application events;
releasing read ownership when a consumer is canceled can start a second upstream
read while the first callback is still alive. A representation refactor alone
cannot resolve these semantic choices.

The implementation must preserve native effect context and cancellation while
avoiding per-element effect machinery that Scala Future does not need. That
specialization must respect the supplied `Effect` instance: a custom Future
instance can give `promise` and `runAsync` meaningful behavior.

## Decision

### Public stream contracts

All capacities are positive queued-data bounds; invalid capacities fail at
construction with `IllegalArgumentException`. They do not bound subscriber count,
outstanding pulls or values already handed to consumers. Each operator owns at
most one actual upstream read, including a read whose consumer has been canceled.

| Operator | Data and capacity policy | Terminal policy |
| --- | --- | --- |
| `Hub(upstream, bufferSize)` | Lossless FIFO fanout. A full live subscriber backpressures the shared reader. The generic default remains `Int.MaxValue`; Akka/Pekko fanout publishers explicitly use 128 per subscriber. Recipients are the subscribers present at publication, including those added during a pending read. | EOF drains accepted tails, then returns EOF. Upstream failure discards queued tails and fails live subscribers with the same error. Terminal admission rejects new subscribers with `IllegalStateException("Hub is closed")`. |
| `stream.sort(numRacks)(route)` | FIFO partitioning, default 128 queued values per rack; the overload `sort(numRacks, capacityPerRack)(route)` sets the bound. Demand in any rack can drive routing. A full destination fails the whole partition instead of overwriting data or blocking access to later control messages. | EOF drains accepted tails. Failure discards queued data and fails every rack. A published failure remains sticky across cancellation. |
| `stream.buffer(duration, maxSize)` | Eager bounded producer, default 16. One batch owns its first element and starts its delay then; after the delay it atomically takes the queued suffix. A duration whose `toMillis` is nonpositive produces singleton batches. | EOF and upstream failure drain the accepted prefix, including an already-delayed batch, before EOF or the original error. Cancellation discards queued data and settles pending batches. |

A delayed batch can contain `maxSize + 1` values: its reserved first value is
outside the queue. An in-flight value and a selected result awaiting notification
also remain live outside the queued-data bound.

Sort overflow is the public effect failure
`StreamSortOverflowException(rack, capacity)`, with message
`Stream partition rack <rack> exceeded capacity <capacity>`. The zero-based rack
and capacity identify the failure without retaining a payload. Every rack sees
the same exception object. Route-function failures also terminate the partition;
an invalid returned index produces `IllegalArgumentException("Invalid stream rack index: <index>")`.
Negative rack counts are rejected; zero racks returns an empty list after capacity
validation. Failures travel through `F`, not sentinel data elements.

### Cancellation and cleanup ownership

Explicit or native pull cancellation detaches only the affected Hub subscriber,
settling its pending pulls with EOF. Even detaching the last subscriber does not
cancel upstream; source ownership stays with the Hub's caller, and later
subscriptions remain possible before termination. The independent pending read
can still deliver to siblings. Sort and buffer cancellation stop the whole
operator, settle pending consumers and start upstream cleanup.

Native backends use one persistent reader program, retaining its own fiber-local
context across reads. Subscribers do not lend their contexts to that program.
If the source intrinsically cancels, the reader's actual exit publishes a cached
`CancellationException("Shared upstream reader canceled")`. Hub and sort fail
their consumers; buffer drains accepted data before that failure. Source
finalizers may delay the actual exit.

Cleanup starts as independently owned child work; cancellation and overflow do
not join user finalizers. Explicit/native cancellation supplies the canceling
effect's context; overflow cleanup retains the reader's context. Cleanup failures
are reported. Rejected startup releases the claim for a later retry only when
the child has not entered; a late abandoned child cannot perform cleanup twice.

A terminal late read does not enter new user routing work. Already-admitted
routing and a value or batch already claimed for completion may win a concurrent
cancellation. The guarantee covers the protected pull task's settlement, not
irrevocable delivery through arbitrary outer caller continuations; native
finalizer outcomes do not establish that stronger guarantee on every backend.

### State and dispatch

Pure routing decisions govern read credit, recipients, terminal transitions and
cleanup admission. One published immutable `Control` holds phase, read ownership
and cleanup claim. Ordinary data queues and opaque callback handles stay in the
interpreter under its monitor; derived counts change with those queues. The
immutable routing model is also the differential oracle.

Dispatch ownership is one atomic value of the pure `Dispatch` machine. One owner
runs notifications and read commands outside the monitor. A command arriving
during the owner's release changes the same cell, so it is neither stranded nor
executed twice. FIFO dispatch preserves already-reserved work; cross-lane callback
traversal order is not a public guarantee. Reentrant ready Futures use an iterative
pump rather than recursively starting new reads.

Cleanup admission pauses terminal notification dispatch until child dispatch has
been attempted. Otherwise a synchronous notification waiting for cleanup entry
could prevent that cleanup from starting. Batch selection likewise reserves the
whole result before admitting a successor, and releases producer capacity before
calling its consumer. Timer handles installed after cancellation are canceled
immediately. No user callback, route function or upstream effect runs under the
ownership monitor.

### Effect instances and backend selection

`Effect[F]` is the public customization boundary. `Effect.futureEffect` supplies
the default Future instance; `Effect.future(FutureInstrumentation(...))` supplies
an explicitly instrumented instance. Both belong to a closed implementation
family with fixed promise, observation and failure-normalization semantics.
Runtime selection recognizes that family once and uses the typed callback pump
(`Promise`/`Try`). All custom instances, including composed `Effect[Future]`
wrappers, use the persistent reader and their own `promise`/`runAsync`
(`Either` callbacks). There is no public capability to forward or inherit.

The default instance's `start` has no hook calls or child-completion observer.
The instrumentation factory chooses continuation checks once at construction;
the ordinary per-item methods do not test for them. Startup hooks run
synchronously and can block or throw. In particular, `afterStart` can fail after
the child has already been dispatched; observing its completion does not join it.
The [public API and migration guide](../effect-future-instances.md) defines the
hooks and composition pattern.

## Alternatives and consequences

- Dropping Hub values and overwriting rack slots were rejected because callers
  require complete event delivery. Universal partition backpressure was also
  rejected: a full data rack must not indefinitely conceal a later control reply.
  Named whole-partition failure makes exhaustion observable and recoverable by
  the caller's connection lifecycle.
- Consumer-owned reads and joining cleanup were rejected: the former can lose
  ownership of a still-live callback; the latter can make cancellation wait on
  arbitrary user finalizers. A persistent reader and explicit nonjoining cleanup
  keep those ownership boundaries distinct.
- Open Future implementation inheritance, inherited opt-in markers and reflective
  override detection were rejected. They either allow custom semantics to be
  silently bypassed or make selection depend on implementation details. The closed
  family imposes a source/binary migration for former `FutureEffect` subclasses;
  composition preserves custom behavior at the generic interpreter's cost.
- A fully immutable per-item runtime was too expensive in the common-semantics
  comparison. Compact pure decisions plus interpreter-owned data retain a checked
  model without copying the complete routing store for every value.

Lossless Hub fanout couples slow subscribers to fast ones; the generic default is
still effectively unbounded. Legacy Frontend handles overflow on every rack by
closing output and preserving the original overflow error, including when cleanup
fails. Output EOF drives transport release and session retirement. Closing only
Frontend's Hub lane would leave the separate activity watcher and raw input live.
Unrelated legacy handler errors and the guarded Frontend path keep their existing
behavior.

## Implementation and verification

- [Routing decisions](../../modules/effect/src/main/scala/korolev/effect/StreamRoutingMachine.scala),
  [shared core and typed interpreters](../../modules/effect/src/main/scala/korolev/effect/StreamRoutingRuntime.scala),
  [batch ownership](../../modules/effect/src/main/scala/korolev/effect/StreamBatchMachine.scala)
  and [batch runtime](../../modules/effect/src/main/scala/korolev/effect/StreamBufferRuntime.scala).
- [Public contract cases](../../modules/effect/src/test/scala/korolev/effect/StreamOperatorContractSpec.scala),
  [differential histories](../../modules/effect/src/test/scala/korolev/effect/StreamRoutingDifferentialSpec.scala),
  [dispatch races](../../modules/effect/src/test/scala/korolev/effect/StreamRoutingDispatchSpec.scala)
  and [cleanup/batch ownership](../../modules/effect/src/test/scala/korolev/effect/StreamOperatorOwnershipSpec.scala).
- [IO cancellation/context](../../interop/ce3/src/test/scala/spoonbill/action/StreamOperatorCancellationIOSpec.scala)
  and [ZIO cancellation/context](../../interop/zio2/src/test/scala/korolev/zio/StreamOperatorCancellationZioSpec.scala)
  cover subscriber detachment, persistent reader context, nonjoining cleanup and
  intrinsic source cancellation. [Handoff probes](../../misc/effect-handoff-probes/README.md)
  retain the backend limitations on outer result delivery.
- [Instance API tests](../../modules/effect/src/test/scala/korolev/effect/FutureInstanceSpec.scala)
  and [selection tests](../../modules/effect/src/test/scala/korolev/effect/StreamRoutingSelectionSpec.scala)
  pin hook failures, default startup, normalization and custom-operation dispatch.
  [Akka](../../interop/akka/src/test/scala/spoonbill/akka/util/StreamPublisherFanoutCapacitySpec.scala),
  [Pekko](../../interop/pekko/src/test/scala/spoonbill/pekko/util/StreamPublisherFanoutCapacitySpec.scala)
  and [legacy Frontend](../../modules/spoonbill/src/test/scala/korolev/internal/FrontendRoutingOverflowSpec.scala)
  exercise the caller boundaries.
- The stream and default-instance fixtures measure different paths. Use the
  stream fixture to check routing changes and the default-instance fixture to
  check runtime effects outside the routing loop.
  [Stream measurements](../../misc/stream-operator-cost/v3/README.md) ·
  [Default-instance measurements](../../misc/effect-instance-runtime-cost/README.md) ·
  [Performance validation](0004-runtime-performance-validation.md)
