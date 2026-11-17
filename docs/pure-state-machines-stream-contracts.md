# Decision 6: separate contracts for the three stream operators

These are source-derived choices for owner review. No choice below is silently
adopted by the representation work. The three operators do not share the same
buffering or ownership contract.

## Current behavior and actual callers

| Operator | Existing storage and driving rule | Existing terminal/cancel behavior | Actual callers |
| --- | --- | --- | --- |
| `Hub(stream, bufferSize)` | A subscriber wins one shared upstream read. Its item is offered to every currently registered sibling queue. A finite full queue drops the **new incoming** item. Default capacity is `Int.MaxValue`, effectively unbounded. | EOF closes/discards sibling buffers; failure now settles all subscribers with the same error. A subscriber's explicit cancel detaches only its queue, never the shared source. | Legacy Sessions: Frontend plus activity/idle-timeout watcher. Akka/Pekko `asPublisher(fanout=true)`: independently demanded subscribers. |
| `Stream.sort(numRacks)(route)` | **One value slot and one callback slot per rack**, not an unbounded queue. No capacity argument. Reading an item for an unwaited rack replaces its previous slot. A read routed elsewhere can leave its driving request pending without restarting the pump. | EOF replaces slots with `None` (discarding queued values); the null-callback EOF defect is fixed. Failure can still strand siblings. Canceling any rack delegates to the shared source's cancel. | Legacy Frontend partitions DOM events, browser history and control/RPC messages into three racks. Guarded Frontend bypasses this operator. |
| `Stream.buffer(duration, maxSize=16)` | Already starts an **eager producer** into `Queue.enqueue`; a full queue backpressures that producer. Each output pull takes one element, waits its own duration, then drains the queue. Nonpositive duration produces singleton batches. | The independent fix now drains its accepted prefix before EOF or producer failure. Cancel closes the output queue, but still discards a lazy upstream-cancel effect pending the cleanup-contract decision. | ApplicationInstance's delayed-render loop, in both initialization paths. |

Sources: [Hub](../modules/effect/src/main/scala/korolev/effect/Hub.scala),
[Stream](../modules/effect/src/main/scala/korolev/effect/Stream.scala),
[legacy Sessions](../modules/spoonbill/src/main/scala/korolev/server/internal/services/SessionsService.scala),
[Frontend](../modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala),
[ApplicationInstance](../modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala).

Queue capacity measures queued items, not every value retained by an operator.
An eager producer can additionally hold the item whose enqueue is waiting; a
buffer consumer also holds its first batch element during the delay. Thus the
default buffer can produce a batch of 17 elements. These bounds must be stated
accurately if a new contract uses the word “bounded.”

## Hub: lossless fanout or explicit best effort

Hub's public Scaladoc says subscribers contain the parent's same elements. The
[Pekko adapter](../interop/pekko/src/main/scala/korolev/pekko/instances.scala) and
[Akka adapter](../interop/akka/src/main/scala/korolev/akka/instances.scala)
`asPublisher(fanout=true)` documentation also promises input-buffer backpressure
between the fastest and slowest subscriber. The implementations construct
`Hub(stream)` with its effectively unbounded default and do not read a configured
input-buffer capacity. This is a contract/documentation mismatch, not evidence
that dropping is an intended public guarantee.

| Choice | Data and capacity rule | Consequence |
| --- | --- | --- |
| H1: lossless at the configured bound | Every item reaches each live subscriber in order; a full subscriber queue stops further shared reading until space or subscriber cancellation. | Matches the “same elements” promise. A slow subscriber delays everyone. The default remains effectively unbounded unless the owner separately chooses a finite default or caller configuration. |
| H2: explicit best effort | Preserve finite-buffer drop-new behavior and independent progress for fast subscribers. | Requires an explicit documented lossy contract, including correcting the adapter backpressure claim. EOF/failure and subscriber removal still need reliable completion. |

Recommendation: H1 is the better match for the existing documentation, but a
**finite default is a separate decision**, not implied by the current API.
The implementation must define positive-capacity requirements or rendezvous
behavior for zero capacity; current Queue accepts a waiting direct handoff even
at zero but does not provide a complete lossless rendezvous protocol.

Keep source ownership separate from that choice. Detaching one subscriber should
not cancel others. Today even the last subscriber does not cancel the source and
later subscription is possible until source termination. Automatically canceling
the source at the last unsubscribe would be an additional lifecycle change.
Subscribers currently join the recipients visible when an upstream item is
published, rather than a frozen set taken before the read starts.

The legacy activity watcher consumes the same Hub as Frontend. Lossless finite
fanout would couple observed activity to Frontend progress; a slow consumer can
therefore affect idle-timeout behavior. A deterministic slow-consumer/session
test is required before adopting that policy. Fanout Publisher tests must also
cover one subscriber with no demand and another requesting many elements.

## Sort: partitioning needs its own overflow choice

There is no existing buffer-size or overflow-policy parameter to preserve. The
one-slot overwrite is drop-**oldest** behavior for a rack, distinct from Hub's
drop-new behavior. It is also distinct from the separate liveness defect where
one read satisfies another rack but does not resume the driving rack's demand.

| Choice | Proposed rule | Consequence |
| --- | --- | --- |
| S1: lossless partitioning with finite per-rack buffers | Retain each routed item in FIFO order; stop shared reads when the routed rack cannot accept the next item. | Requires a capacity/default decision and potentially an overload. Creates head-of-line blocking across racks. One pending routed item must be included in the memory bound. |
| S2: bounded partitioning with explicit overflow failure | Retain FIFO items up to a chosen per-rack bound, then fail the whole partition rather than silently overwrite. | Keeps memory bounded without silently losing accepted application input. A slow rack can terminate the connection; this resembles guarded Frontend's existing offer-or-close behavior. |
| S3: explicit latest-value compatibility | Preserve one slot per rack and overwrite its previous buffered value, while fixing read restart and terminal completion. | No capacity API addition, but the lossy contract must be intentional and documented. It cannot be described as ordinary lossless partitioning. |

Do not recommend S1 automatically just because it is attractive for Hub. Legacy
Frontend multiplexes user/history traffic and control/RPC replies on one input.
A full data rack can prevent reading a later control reply. Legacy DOM handling
forks its user computation, so it is not accurate to claim that every DOM action
directly blocks its rack; history/startup/slow consumers still create coupling.
This is a source-derived risk requiring a deterministic caller test, not a
claimed reproduced application deadlock.

Guarded Frontend already uses three queues of 128 elements and an eager reader
that **offers or closes**, not waits for rack capacity. Its code explicitly avoids
awaiting user actions in the control-message reader because subsequent transport
replies may be needed by the running action. S2 deserves consideration for this
multiplexed caller; generic `Stream.sort` can have a separately chosen contract.
Keeping its old signature while adding an explicit policy/capacity overload, or
adapting only the legacy Frontend, needs owner authorization rather than inference.

Explicit cancellation currently owns the entire partition: canceling any rack
cancels the upstream. Changing it to detach just that rack is a separate choice,
because incoming values for an abandoned rack then need a discard/fail/stop rule.

## Buffer: retain the existing eager bounded pump

There is no basis for replacing buffer with a demand-driven operator. It already
has an eager producer, configured enqueue backpressure and delayed batching.
The remaining decisions concern terminal state and ownership:

| Boundary | Recommended contract to confirm | Compatibility detail |
| --- | --- | --- |
| Natural EOF | Drain accepted queued elements, then return `None`; independently implemented. | Preserves the existing first-element delay; immediate EOF-triggered flushing would be a timing change. |
| Upstream failure | Drain the already accepted prefix, then fail with the original error; independently implemented with the terminal fix. | Preserves the previously readable prefix and completes its eventual terminal pull instead of hanging. This avoids the inconsistent partial-buffer behavior of simply calling `queue.fail`. |
| Explicit cancel | Abort pending batches, stop admitting producer work, and execute upstream cancellation. | Specify whether cancel joins upstream cleanup. The current strict-Future implementation starts cancellation without awaiting its result; the lazy-effect call is discarded entirely. |
| Canceled output pull | Define whether this cancels the whole buffered stream or only that waiting pull. | Do not silently assume a native effect cancellation removes an already registered queue callback. |

The sole production use is delayed rendering. Application teardown closes the
source state queue and settles unrendered state waiters; `onState` also settles
callbacks on failure. The buffered stream is built inline, so EOF propagation is
needed to end its consumer loop. Validation must cover a finite final batch,
failure during a delayed batch, a producer blocked on a full queue, and teardown
with already-buffered render callbacks. The prior over-17-write stall was a
separate Queue.dequeueAll capacity-notification defect and is already fixed.

## Shared ownership limit that a capacity choice does not settle

The [controlled native Hub regression](../interop/ce3/src/test/scala/spoonbill/action/HubCancellationIOSpec.scala)
proves that canceling a driver effect can leave the actual upstream callback
alive. Releasing the pull lock at that cancellation permits a second concurrent
read. Success/error-only cleanup now preserves the original boundary. A new
operator must prove that its old read has settled or that the whole operator is
terminal before granting another read; a cancellation finalizer alone is not
proof.

Owner choices must therefore include the meaning of native pull cancellation:
abort the whole operator, detach one consumer under an independently owned read,
or a clearly stated cancellation restriction. An independently owned pump may
change caller-fiber context; an uncancelable shared read may delay cancellation.
Neither should be slipped into a storage refactor. `Effect.Fiber` currently
exposes join, not a portable cancel-and-join operation.

Suggested next decision: choose Hub fanout policy/default, choose Sort overflow
policy separately with its Frontend implication, and keep Buffer's eager bounded
behavior and terminal drain while selecting its cancellation-completion rules.
Until then the confirmed independent terminal bugs are fixed separately and the
three policy-dependent replacements remain open.
