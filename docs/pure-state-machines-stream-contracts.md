# Selected stream operator contracts

The owner approved these contracts. This is a semantic redesign where the old
operators dropped data or stranded consumers, not a claim of behavior-preserving
representation changes. The core source/native checkpoint is validated; adapter,
legacy Frontend and common-semantics cost checks are separate follow-up units.

## Public data and capacity rules

| Operator | Selected contract | Public capacity and failure API |
| --- | --- | --- |
| `Hub(upstream, bufferSize)` | Lossless FIFO fanout. A full live subscriber backpressures the one shared upstream reader. A new subscriber joins the recipients present when a pending upstream value is published. | Capacity must be positive. The generic default remains `Int.MaxValue`, so the default is still effectively unbounded. Akka/Pekko fanout publishers explicitly use 128 per subscriber. |
| `stream.sort(numRacks)(route)` | FIFO partitioning with 128 queued values per rack. Reading continues for waiting racks; a full destination fails the entire partition instead of overwriting or waiting behind that rack. | The old call form remains. `sort(numRacks, capacityPerRack)(route)` accepts a positive configured bound. `StreamSortOverflowException(rack, capacity)` is public, carries zero-based rack/capacity metadata and retains no payload. All racks observe the same failure. Zero racks returns an empty list; negative rack counts are rejected. |
| `stream.buffer(duration, maxSize)` | An eager bounded producer and FIFO delayed batches. One active batch owns its first element; after its delay it atomically reserves the queued suffix before a later batch starts. | The default remains 16; capacity must be positive. The delay starts with the first element, not a global tumbling window. A duration whose milliseconds are nonpositive yields singleton batches. |

Queue capacity is a queued-data bound, not a bound on every reference held by an
operator and its callers. There is at most one actual upstream read per operator.
An in-flight value must be accounted for, as must the buffer's reserved first
batch element and an already-selected result awaiting publication. A buffer can
therefore emit a batch of `maxSize + 1` elements. Completed values already assigned
to outstanding consumer pulls belong to those consumers; concurrent pull handles
and the number of Hub subscribers are not capped by the data-buffer parameter.

Hub's documentation promises the same parent elements, and adapter fanout
previously promised backpressure without selecting a finite size. The selected
lossless policy and explicit publisher 128 capacity make that promise concrete.
This does not silently replace the generic Hub default with a new finite default.

Sort was not previously an unbounded queue: it held one overwriteable value and
one callback per rack. The new FIFO/overflow contract deliberately replaces that
loss policy and its missing-read-restart behavior. Failure is an ordinary typed
`F` failure, not a sentinel data item. Route-function failures and invalid rack
indices also terminate the partition; no value is included in library diagnostics.

## Terminal and ownership rules

| Boundary | Hub | Sort | Buffer |
| --- | --- | --- | --- |
| Natural EOF | Drain every accepted subscriber tail, then EOF; reject new subscribers. | Drain accepted per-rack tails, then EOF. | Drain the accepted prefix, respecting an already-started batch delay, then EOF. |
| Upstream failure | Fail all live subscribers promptly with the original error. | Fail all racks promptly; discard queued partition data and start cleanup. | Drain the accepted prefix, then fail with the original error. |
| Explicit cancel | Detach only this subscriber and settle its waiting pulls. Other subscribers and the shared source remain live. | Stop the whole partition and settle waiting racks. An already-published partition failure remains sticky. | Stop the whole operator, discard queued data, cancel owned timer handles and settle pending batches. |
| Native pull cancellation | Detach only that subscriber; the independent in-flight read remains owned and can still serve siblings. | Stop the whole partition. | Stop the whole buffered operator. |
| Intrinsic source cancellation | The owned reader publishes a cached `CancellationException("Shared upstream reader canceled")` at its actual exit. | The same typed terminal failure reaches every rack. | Accepted buffered data drains before the same typed terminal failure. |

The generic Hub retains source ownership with its caller: even detaching its last
subscriber does not cancel the source or forbid a later subscription before the
source terminates. A canceled subscriber does not receive subsequent publication.
A value or batch already claimed for completion can win a concurrent cancellation;
there is no promise of revoking a result already handed off by the pull task.

The native-cancellation guarantee covers the protected pull's own success/error
boundary, not irrevocable delivery through arbitrary outer caller continuations.
The [native effect handoff probes](../misc/effect-handoff-probes/README.md) explain
why backend finalizer outcomes cannot promise that stronger boundary universally.
Source-internal finalizers can delay the source's actual exit just like any other
pending upstream effect; explicit consumer cancellation still settles its operator
scope without joining those finalizers.

## Reader, cleanup and notification ordering

One persistent effect program owns upstream reads and its fiber-local context.
Subscribers do not lend their contexts to successive reads. Native cancellation
of a subscriber therefore cannot abandon an old callback and grant another read.
Late outcomes after whole-operator cancellation are discarded before entering
new user routing work; a routing invocation already admitted may overlap cancel.

Upstream cleanup starts as owned child work and is **not joined** by cancellation
or overflow. Explicit/native cancellation captures the canceling effect's context;
overflow cleanup inherits the persistent reader's context. Cleanup failures are
reported. A startup rejection before the child entered releases the cleanup claim
for a later retry, and an abandoned late child cannot execute cleanup twice.

Terminal publication pauses notification draining until cleanup has been
dispatched. This prevents synchronous Future continuations waiting for cleanup
entry from starving that very startup. Notifications execute outside ownership
locks. One thread at a time owns their dispatch; a notification or read that
arrives meanwhile follows the running one in FIFO order, also from another
thread. Buffer batch selection reserves its entire FIFO suffix before admitting a
successor, then returns eager-producer credit before notifying the completed batch.
Timer installation after cancellation immediately cancels the late handle.

## Caller implications

Legacy Sessions uses Hub for a Frontend lane and a separate activity watcher.
Lossless fanout can couple a slow frontend to observed activity; it no longer
silently drops frontend input to keep the activity watcher ahead. Subscriber
cancellation remains local, so callers must close their connection lifecycle when
partition overflow makes input unusable.

Legacy Frontend partitions DOM, history and control/RPC traffic. Bounded overflow
failure avoids silently overwriting events and avoids waiting indefinitely behind
a full data rack before reading a later control reply. Guarded Frontend already
has its independent 128-slot offer-or-close queues and is not redirected through
this operator. The legacy overflow-to-output-close caller regression/fix is a
separate unit, preserving unrelated legacy handler-error behavior.

Delayed rendering continues to use eager buffering. Its existing terminal-drain
fix is retained, including prefix delivery before failure. The scheduling test
seam changed from intercepting `Scheduler.sleep` promises to an internal timer
factory because batches now own cancelable JobHandler handles; the original
batch contents/delay/failure assertions remain. Actual Scheduler behavior is also
covered by native delayed-batch cancellation, including pending consumers and late
first-value publication.

## Validation evidence and remaining acceptance work

- Four compatible-call-form cases fail the exact pre-redesign Hub/Stream source:
  bounded fanout credit, EOF tail retention, continued rack routing and default
  overflow. Log: `/tmp/spoonbill-operators-contract-original2.log`.
- The core checkpoint passes 125 effect tests and 10 selected IO tests, including
  generated pure model laws, cleanup-start rejection/retry, reentrant cancellation,
  capacity wake before callback, native detach/sibling progress, persistent IOLocal
  context, nonjoining cleanup and intrinsic source cancellation. Log:
  `/tmp/spoonbill-operators-core-native-verified.log`.
- Root's independent ZIO2 suite (`dca626a8`,
  `zio2/testOnly *StreamOperatorCancellationZioSpec`) passes all four cases. The
  original source stranded siblings/cleanup, borrowed subscriber FiberRef values,
  or propagated native interruption instead of cached failure. Logs:
  `/tmp/spoonbill-stream-zio-contract-before.log`,
  `/tmp/spoonbill-stream-zio-source-interruption-before.log`, and
  `/tmp/spoonbill-stream-root-native-after.log`.
- Source purity checks inspect 43 machines with no violations. Models hold data
  and opaque identities; callbacks, upstream effects and timer handles stay in
  their interpreters.

Final acceptance also includes the separate Akka/Pekko publisher and legacy
Frontend caller cases, broad integration, and the common-semantics cost screen.
No throughput/allocation parity with the old lossy or overwriting operators is
claimed. The new routing state/results allocate on item handling, so the cost
screen must inform the final disposition rather than being replaced by a claim
that stronger semantics alone make cost irrelevant.

## Compact experiment (not yet accepted)

The first compact candidate retains the pure read-credit, recipient, lifecycle
and dispatch decisions, while ordinary FIFO data and callback handles use
interpreter-owned stores. Its immutable routing model remains a differential
oracle. Native backends keep the persistent reader and protected pull boundaries;
Scala Future uses an iterative callback pump and does not allocate native
cancellation scopes. Future construction failures are still normalized through
`Effect.fail`.

Validation passes 132 effect tests, 10 IO cases and four ZIO cases; adapter
fanout and legacy overflow caller tests also pass. Generated histories compare
per-ticket/per-lane outcomes rather than unspecified cross-lane callback order.
A gated first publication followed by 100,000 ready values verifies reentrant pump
stack bounds. A separate fully synchronous `Stream.foreach` stack limitation was
reproduced in both v1 and pre-operator sources; this experiment does not change
that helper.

The unchanged 18-row short-screen fixture reports compact/baseline ratios:

| Cell | Throughput | Allocation per delivery |
| --- | ---: | ---: |
| Hub | 2.6255 | 0.3179 |
| Sort | 0.5683 | 0.8892 |
| Zero-duration buffer | 1.0608 | 0.6480 |

All delivery, thread-liveness and allocation controls passed. The candidate is
**not accepted yet**: sort allocation is improved, but its CPU/dispatch cost needs
an independently measured follow-up. V1 and this candidate's frozen sources,
raw rows and profiles are retained by the experiment workstream.

## Retained implementation after experiment stop

The owner requested no further rounds after compact4 completed. The production
choice retained at that stop was compact2: fixed lane references and
first-command reservation under the existing monitor, preserving the original
phase checks. Its three-cell screen reports throughput/allocation ratios of
2.71018/0.31786 (Hub), 0.64402/0.88917 (sort), and 0.99523/0.64787
(zero-duration buffer). Its exact sources passed 132 effect, ten IO, four ZIO
and seven caller cases.

Sort missed the throughput guide. Compact3 and compact4 did not close the gap
and also regressed buffer throughput; neither was retained as production. The
unmeasured single-callback-storage prototype was excluded.

## Accepted implementation

The owner later asked for the sort gap to be closed, and then for the result to
hold its ownership in single values, without reflection. Production commit
`e57aabea` (compact7) keeps every contract above:

- One notification or read is held by its thread as its own first command.
  Dispatch still has one owner at a time, runs outside the ownership monitor
  and stays paused until cleanup has been dispatched.
- The phase, read ownership and the cleanup claim are one published `Control`
  value. Dispatch ownership is one value of the pure five-state `Dispatch`
  machine in one atomic cell. Its owner releases without the monitor only from
  `Running`, by a compare-and-set; a thread that queues a command behind it
  moves the same cell to `Backlogged`. A command that arrives during a release
  is therefore taken by exactly one of the two threads. The immutable routing
  model remains the oracle.
- Two typed interpreters share that core. A callback pump serves every
  FutureEffect: a pull is its Promise, completed with the Try that its read was
  observed with on the completing thread. A reader fiber serves every other
  effect with the Either callbacks of its own `promise`.
- A FutureEffect subclass that overrides `promise` or `runAsync` is therefore
  not called for a pull or a read. Compact2 called both for every delivery.

Its three-cell screen reports throughput/allocation ratios of 3.47189/0.25530
(Hub), 0.97990/0.64463 (sort) and 1.34773/0.48895 (zero-duration buffer). All
three satisfy the 0.95 throughput and 1.10 allocation guides. Two earlier steps
are retained with their rows. Compact5 changed only the interpreter, reached
0.84798 for sort and was rejected. Compact6 (`74fed761`) reached 1.10111 for
sort, but it spread the same ownership over several interpreter fields and
found a FutureEffect's own callbacks by reflection, so it was superseded. All
144 measured rows and earlier controls remain in
[the cost evidence](../misc/stream-operator-cost/README.md).

The measured source passed the complete CI test command: 2,185 ScalaTest and 41
ZIO cases without a failure. Generated histories compare both interpreters with
the immutable model, the reader fiber through a Future effect that is not a
FutureEffect, and a generated-history law covers the dispatch machine alone.
The dispatch cases pin what the release must keep, also for both interpreters: a
queued notification and then the read reserved after it follow a running
notification, a read that a value leaves credit for starts after that value's
notifications, a cleanup pause holds back an owner that finishes during it, and
a command that arrives at a release is not left behind. Concurrent Future and
IO consumers of Hub, sort and buffer receive every value once and in order, and
the Future cases never observe two threads inside dispatch. All 26 dispatch
cases also pass the compact2 source
(`/tmp/spoonbill-stream-compact7-dispatch-on-compact2.log`), so they describe
retained behavior. Each of sixteen single-rule mutations of the ownership
protocol fails at least one case
(`/tmp/spoonbill-stream-compact7-mutations.txt`), and an independent read-only
review found no defect.

The original four-workload gate has not been rerun on this final aggregate.
A short screen's three-pair medians are observations, not a whole-application
latency guarantee.
