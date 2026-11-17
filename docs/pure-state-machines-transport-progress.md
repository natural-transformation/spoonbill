# Transport state-machine work

This records phase-7 decisions and validation. Tests use the repository-local
`flake.nix`; production effects and security ordering remain in their existing
interpreters. Setup-ownership sharing and the shared deadline are tracked by
their own work units.

## Upgrade input

`HttpUpgradeInput` now names its three atomic states `Available`, `Claimed` and
`Sealed`. The same compare-and-set claim and get-and-set seal preserve its public
boundary. This is the plan's explicit enum-only change, not a new machine.

Three new characterization tests passed before the change: a claim succeeds only
once, only the first seal reports that claim, an unused seal prevents a later
claim, and ordinary stream operations do not consume transport ownership.

Validation:

- `sbt 'http/testOnly *HttpUpgradeInputSpec'` on the old code: pass, 3 tests.
- `sbt 'http/testOnly *HttpUpgradeInputSpec *HttpServerPublicUpgradeSpec'` after
  the enum change: pass, 10 tests, including real public HTTP/WebSocket upgrades.

## Publisher: confirmed defect fixed separately

The Pekko and Akka publisher bodies are equivalent apart from their namespaces
and unused imports. Both admitted a second single-mode subscriber reentrantly
from the first subscriber's `onSubscribe`, because their claim happened after
application code returned. Their five-case characterization suites each passed
four existing-behavior cases and failed the new reentrant-claim case before the
fix. A single atomic claim now precedes the callback, also serializing concurrent
subscription attempts. Fanout and the demand/terminal loop are unchanged.

Validation after this isolated fix: `sbt 'pekko/test' 'akka/test'` passed 46 and
38 tests respectively, including the subscriber laws below. Publisher lifecycle extraction remains a separate audit
decision; this commit does not treat that work as complete.

Two additional publisher probes exposed demand continuing after cancellation and
cleanup being repeated after cancellation or terminal completion. Those probes
failed before the separate cancellation fix. Subscription cancellation now wins
once, releases a loop waiting for demand, and suppresses subsequent pulls and
late pull outcomes. Terminal success/failure also closes the subscription.
This follows the cancellation lifecycle in the
[Reactive Streams subscription rules](https://github.com/reactive-streams/reactive-streams-jvm#3-subscription-code).
It is not a claim of full Reactive Streams conformance: synchronous rejection of
a second subscriber remains an explicitly documented adapter API.

Validation: the complete adapter suites passed 48 Pekko and 40 Akka tests after
the cancellation fix. The publisher suites then passed nine cases each,
including added late-success and late-failure checks after cancellation.

A follow-up regression pinned null input before claiming the single-subscriber
slot. The first atomic-claim fix had allowed a rejected null attempt to consume
that slot. Both adapters now validate the subscriber first. The new case failed
before correction; the corrected publisher suites pass ten tests each. This
input-validation fix is separate from the lifecycle extraction.

A positive-demand overflow was then reproduced: requesting `Long.MaxValue` twice
and three more elements while the first pull waits wrapped the remaining demand
to zero, losing the second item. Both adapter countdowns now saturate positive
demand at the effectively unbounded maximum, which consumption does not reduce.
The regression failed before this arithmetic fix. Full adapter suites passed
53 Pekko and 45 Akka tests afterward, including a direct countdown boundary case.
Nonpositive requests were also confirmed as a separate Reactive Streams defect,
then corrected after the behavior-preserving extraction below. They are not
treated as an intended compatibility policy.

After those separate fixes, both publishers use the same `StreamPublisherMachine`
for Active/Canceled/Completed/Failed terminal ownership and positive-demand
arithmetic. The terminal model runs only at lifecycle edges; demand remains in
the existing immutable countdown value with its callback slot. No event or command
collection was added per element, and callback execution stays in the original
effect loop. The single-subscriber atomic flag remains an independent one-shot
claim. Cancellation still wakes a pending demand wait before starting cleanup.

The candidate tables match: positive demand adds credit, zero credit parks one
consumer, terminal outcomes claim their signal once, and cancellation suppresses
late pull outcomes. Synchronous duplicate-subscriber exceptions remain the
documented compatibility policy. `spoonbill/testOnly *StreamPublisherMachineSpec` passed both table/generated
tests, and the unchanged complete adapter suites passed 53 Pekko and 45 Akka tests
after extraction. The purity guard passed.

### Invalid demand: serialized terminal failure

Five new probes failed before correction: zero/negative demand, rejection from
inside `onNext`, rejection concurrent with a blocked `onNext`, and rejection while
a pull remains outstanding. The lifecycle now claims a pending demand rejection;
the lane publishes an entering marker before rechecking that lifecycle. A request
either observes the marker and defers its error, or fences the callback before it
can start. The marker clears after the callback returns, and the pure model grants
only one terminal-error report. No application callback runs under a lock.

Invalid-demand cleanup is scheduled independently so a request inside `onNext`
does not wait for cleanup which may need that callback to finish. A once-claim
also protects the refusal fallback. Valid demand and normal callback execution
remain in the original effect loop; the publisher does not allocate a transition
per element. This fixes invalid-demand semantics, not the separately documented
synchronous duplicate-subscriber exception API.

Validation: the pure publisher suite passed three cases, and the complete adapter
suites passed 58 Pekko and 50 Akka tests. The expanded seven-case invalid-demand
suite then passed in each adapter, adding canceled-versus-invalid races and
refused cleanup scheduling. The purity guard and staged whitespace check passed.

## Subscriber: retain the existing explicit state

The candidates are
`interop/pekko/src/main/scala/korolev/pekko/util/KorolevStreamSubscriber.scala`
and its Akka counterpart. Their production files differ only in package name.
Both already keep subscription, waiting callbacks, terminal result and cancellation
ownership in one immutable `State`, published under the same reentrant monitor.

| Input | Both candidates' behavior | Characterization |
| --- | --- | --- |
| Pull before subscribe | Queue the waiter; request its demand when subscription arrives | Deferred-demand case |
| Pull after subscribe | Publish waiter before requesting under the monitor | Synchronous-response case |
| Repeated identical subscription | No request or cancellation is repeated | New same-instance case |
| Different second subscription | Cancel the incoming subscription; preserve the active owner | Duplicate-subscription case |
| Next value | Give one queued waiter the value; ignore values after terminal state | Delivery and cancel-race cases |
| Complete or fail | Keep the first terminal result; drain waiters once | Completion and retained-error cases |
| Cancel before subscribe | Complete pending pulls; cancel late subscription without demand | Early-cancellation cases |
| Complete before subscribe | Cancel late subscription without replaying demand | New late-subscription case |
| Cancel after subscribe | Cancel once, preserving any terminal error | Attached-cancellation case |
| Throwing upstream cancel | Preserve the failure and still settle waiting pulls | New throwing-cancel case |

Disposition: retain under plan rule 7. The lifecycle is already one explicit
immutable value; the twelve tests in each adapter exercise wrong-move and race
boundaries; review found no confirmed defect in this subscriber protocol. A
shared extraction would split the existing subscription/callback ownership table
without resolving an unsettled rule. The monitor is intentional: upstream
`request` may synchronously call `onNext`, and demand must not escape after cancel.
No effects, monitors or subscriber production lines were changed.

Validation: `pekko/testOnly *SpoonbillStreamSubscriberSpec` and its Akka counterpart
each passed all 12 tests on unchanged subscriber code. Both complete adapter
suites then passed, 46 Pekko and 38 Akka tests, after the isolated publisher fix.

## Socket-close reservation

The reservation's claim flag and two completion bits are now six explicit states
in `SocketCloseReservationMachine`: reserved, reserved after connection completion,
dispatched, waiting for connection completion, waiting for notification completion,
and released. A pure transition emits at most one submission or permit release.
The interpreter commits it with `StateCell`; worker submission, user cleanup,
permit return and release-promise completion retain their original order outside
the transition. The separately extracted dispatcher admission gate is unchanged.

Three new characterizations passed before extraction: cancel before installation,
both completion orders including connection completion before dispatch, and
concurrent repeated completion. The existing four dispatcher cases also passed.
The machine's table and generated histories check once-only submission/release
and terminal release. `sbt 'http/test'` passed all 159 HTTP tests after extraction,
including real socket shutdown, server framing, client pooling and TLS cases.

## Raw socket: retain explicit atomic state

`modules/http/src/main/scala/korolev/effect/io/RawDataSocket.scala` already keeps
read admission, closure, close observers, asynchronous finalizers and the reserved
notification owner in one immutable `State` behind one atomic reference. Native
channel/buffer state belongs to NIO and stays outside that value. Review found no
confirmed defect in the socket ownership transitions.

| State/move | Pinned behavior |
| --- | --- |
| Open and idle → reading | A second concurrent read is rejected |
| Reading → open and idle | Completion permits a subsequent read |
| Reading → closed | EOF closes transport and notifies observers once |
| Closed with native read pending | Ordinary cancellation prevents new reads; the already admitted read may settle without reopening |
| Physical shutdown | Channel closure precedes close callbacks; pending native read fails; future reads return EOF |
| Observer removal/late registration | Detach drops retained state; closure notifies once; late asynchronous cleanup stays in the caller's effect |

Disposition: retain under rule 7. The state is already one value, direct laws and
the HTTP integration suites reject wrong transitions, and no confirmed defect was
found. Extracting callback/handle tables into an additional model is not needed
to name this lifecycle and would alter its hot-path representation. No
socket production code or native-effect order changed.

Four new read-ownership characterizations join the three existing close-observer
laws and four dispatcher cases. On unchanged socket code,
`sbt 'http/testOnly *RawDataSocket*Spec *SocketCloseDispatcherSpec'` passed all 11.
The preceding complete HTTP run passed 159 tests, including deadline, framing,
pooling and physical-cleanup integration.

## HTTP server body ownership

`HttpBodyOwnershipMachine` names Ready, Reading(owner) and Abandoned(owner?) in
place of the server's active-body/null and sticky-abandonment fields. Its owner
identities are supplied by the interpreter. Completion can release framing, but
never undo cancellation; cancellation of an older body cannot poison a subsequent
request. A generated comparison checks these decisions against the previous two
field rules, including all event orderings.

The existing monitor remains because deadline arming and completion checks must
stay ordered with ownership changes. Native shutdown, failure publication,
response construction and header/body deadline effects keep their original order.
The per-body cancellation and first-error flags remain distinct one-shot facts.
Header timing uses the separately shared deadline; upgrade ownership uses the
explicit upgrade-input enum. The connection is decomposed by these protocols,
not consolidated into a new hot-path CAS cell.

The seven existing `HttpServerBodyFramingSpec` cases already pinned sticky
cancellation, old-owner fencing, unread framing and deadlines before extraction.
After extraction, `sbt 'http/test'` passed all 165 HTTP tests, including the two
new model cases and four raw-read characterizations. The machine purity guard
also passed. No transport effect was moved across a security decision.

## HTTP client exchange ownership

`HttpClientConnectionMachine` makes the physical connection's Ready, Borrowed(owner)
and Closed states explicit. The exchange supplies an opaque owner independently
of its cleanup thunk. The original monitor still publishes state and cleanup
together; physical close takes cleanup before invoking it, and only the matching
live exchange may return the connection for reuse. A late completion cannot clear
a newer exchange or reopen a physically closed connection.

The surrounding exchange intentionally keeps distinct one-shot facts: publishing
the response once, retaining its first input failure, claiming borrowed-resource
retirement once, and finishing its response body once. Their lifetimes differ:
response headers can be published before upload settlement, while cleanup may
still hold pool capacity. The byte count is data owned by sequential body reads;
writer completion is already one promise. Setup/header deadlines use the shared
deadline model. These are not merged into one global state or a new per-chunk CAS.

The existing early-response, bodyless-response, response-safety, setup-deadline,
WebSocket-cleanup and TLS suites passed before this extraction. Two model cases
add stale-owner/terminal-close checks and generated comparisons with the previous
monitor rules. After extraction, `sbt 'http/test'` passed all 167 tests and the
purity guard passed. No request-write, response-publication, cleanup, or TLS effect
was reordered.

### HTTP acquisition cancellation: separate caller fix

Controlled real-IO/loopback probes exposed two lost-loan cases: canceling while a
request waits for the one-slot pool, and canceling after a loan is assigned but
before its native effect callback is published. Both prevented a later request
from acquiring capacity. They failed before the fix.

HTTP, HTTPS, WebSocket and secure-WebSocket entry points now use the coordinated
internal `borrowAndTransfer` scope. It owns acquisition through successful request
task completion, preserving caller effect context and interruptible waiting.
Before success, abort schedules `loan.discard` without joining user cleanup.
Discard's once-claim precedes physical mutation; a stale abort cannot close a
connection which another owner already reused. Successful streaming responses
keep their loan until body consumption/cancellation, as before.

The transfer boundary is task success, not irrevocable delivery to an outer
caller. CE2/Monix/ZIO2 can honor late cancellation after a successful finalizer;
callers still need an effect-appropriate bracket/adoption scope for returned
bodies. No universal sealed-success primitive or public API change is claimed.

After correction, both red probes passed. An additional real-IO case cancels an
active header wait, verifies physical closure and prompt cancellation while
request cleanup remains blocked, and verifies capacity returns only after cleanup
finishes. The combined client/pool IO suites passed 10 tests; existing HTTP client
tests passed 56, including streaming-body retention, TLS, errors and cleanup.

## WebSocket text limits

`WebSocketTextMachine` now owns pure per-chunk UTF-16 validation and UTF-8 byte
accounting. The StringBuilder stays in the interpreter. Five old-code
characterizations pin split surrogates, exact budgets, incomplete results,
oversized chunks, and the existing consumed-prefix state after rejected input.
Generated comparisons prove compact paths agree with the checked model.

The first four measured implementations were rejected, not silently retained:
stored snapshots added allocation; scalar storage still missed short-input
throughput; an ASCII fast path still missed Unicode throughput; direct
`codePointAt` iteration regressed Unicode sharply. The selected fifth version
keeps thread-confined scalar storage, pure compact ASCII/complete-UTF-16 counters,
and the immutable checked fallback for partial/error states. A low surrogate's
budget is already reserved and checked by its high half. No per-character event
or state object is allocated, and no `Long` range is narrowed.

The [fixture, all 120 rows, failed patches and source hashes](../misc/transport-state-machines/README.md)
record the full experiment. Final three-trial median ratios for short / ASCII /
Unicode / fragmented input are: throughput 101.0 / 201.8 / 101.0 / 181.3%, allocation
100.0 / 100.0 / 97.3 / 97.8%, and sampled p99 100.0 / 54.5 / 100.0 / 53.4% of the
paired baseline. All four meet the 95/110/110 guide. Tiny p99 samples are limited
by timer granularity; this is not an application-wide performance guarantee.

Before measurement, `web/test` passed 54 tests and both complete adapter suites
passed 53 Pekko and 45 Akka tests. The purity guard passed. The selected production
sources were unchanged between those checks and their benchmark collection.

Other phase-7 items remain under audit; no unreviewed item is marked retained or
complete here.

## Standalone input: confirmed drain defect

The drain's recovery surrounded both input reads and its terminal callback.
Consequently a failed terminal callback ran three times after an ordinary EOF
and twice after an input failure in the new one-item characterization. Both
probes failed before the fix. Recovery now classifies input/predicate failure
before calling the terminal callback, so that callback's own failure propagates
without retry. The input, handoff, and cleanup effect order otherwise stays intact.

`standalone/testOnly *SetupInputSpec *StandaloneLifecycleSpec *StandaloneSetupDeadlineSpec`
passed all 26 tests after correction, including a terminal-predicate failure case.
The phase extraction remains a separate unit following this fix.

That subsequent unit is `SetupInputMachine`: Setup/Coupled/Released are explicit
immutable phases with pure pull, coupling and end-notification decisions. The
original waiter monitor, direct coupled read, callback table, and once-only
end callback remain with the interpreter. No effect continuation or event object
was inserted into a coupled pull. The full standalone suite passed all 55 tests
and the purity guard passed before measurement.

The [coupled-input comparison](../misc/transport-state-machines/README.md#coupled-standalone-input)
retains twelve valid trials plus the earlier failed fixture attempt and diagnostic.
One/128-source median throughput is 96.01/96.16% of baseline, with zero allocation
per pull in both versions and identical quantized sampled p99. The small throughput
cost is within the guide; this is not an end-to-end transport performance claim.

## Standalone setup deadline: retain its distinct explicit protocol

`SetupDeadline` already publishes Pending(release?), Attached(release),
Expired(release?) and Finished as one immutable atomic value. Its timer is a
scheduler handle; the late observer detacher is an idempotent external handle.
The existing late-result, expiry, disconnect and observer tests found no
confirmed defect in this ownership protocol.

It is not another copy of the shared adapter setup machine: repeated attachment
succeeds, attachment retains response cleanup until physical termination,
rejection after attachment is inert, and timer expiry closes only the physical
transport until its reserved notification runs application cleanup. Three added
laws pin repeated attachment/rejection, disposal of a second post-attach result,
and release of an HTTP rejection without prematurely closing transport.

Disposition: retain under rule 7, with its single named state and pinned effect
boundaries. `standalone/testOnly *StandaloneSetupDeadlineSpec` passed all 12 cases
on unchanged production code. No timer, callback, or cleanup ordering changed.

## WebSocket assembly: retain the pure stages and one observer flag

Four direct public-upgrade cases now pin empty input, complete-before-tail-rearm
ordering, fragmented messages across control frames, and suppression of tail
restart when completion rejects a message. On unchanged protocol code,
`http/testOnly *WebSocketMessageSignalsSpec *WebSocketProtocolSpec` passed 21 tests;
`standalone/testOnly *StandaloneMessageDeadlineSpec` passed six real deadline and
socket cases. The two pure decoder stages already have explicit state. Their
remaining tail flag must still be read after the completion callback; precomputing
a combined command batch would move that observation across an effect boundary.
Disposition: retain under rule 7. `decodeFrames` and `mergeFrames` already expose
their complete immutable state and next result. The observer owns exactly one
boolean, `pendingFrameBytes`, in one place; there is no additional lifecycle
combination to reconcile. The four public-upgrade cases fail if the flag is not
updated, completion is moved after rearming, control frames finish a fragmented
message, or rejection still rearms. The callback remains between completion and
the flag read, preserving its effect boundary. No confirmed defect was found.
The extraction review reran the 21 HTTP cases unchanged and they passed. No hot
path changed, so no new performance comparison is required.

## Server connection tracking: a dedicated drain model

`ActiveConnectionsMachine` owns the accepting flag, active-handler count, and
first failure. `ServerSocket.ActiveConnections` retains its synchronized cell
and once-completed promise. This is deliberately not the shared drain protocol:
handler failure does not stop acceptance, stop can carry a failure, and completion
waits for both acceptance to stop and handlers to settle. Repeated stop may update
the diagnostic state but cannot replace an already published result.

Two added interpreter characterizations passed on the original implementation:
accept failure wins over a later handler failure, and completed success survives
a repeated failing stop. `http/testOnly *ServerSocketLifecycleSpec` passed 8 cases
before extraction. After extraction,
`http/testOnly *ServerSocketLifecycleSpec *ActiveConnectionsMachineSpec
*WebSocketMessageSignalsSpec *WebSocketProtocolSpec` passed 31 tests. The two direct
model cases cover both completion prerequisites and first-failure carry in either
event order. The model runs at connection ownership edges, never per read/frame.

## Standalone output and close ownership

`WebSocketOutputMachine` combines the output phase, close direction and cleanup
claim. Its interpreter publishes one atomic state; CAS retries compute state only.
Ordinary frames read direction and phase without creating events or next states.
Lifecycle transitions attach output, request graceful closure, emit Close, finish
the following pull, or abort. Abort remains dominant even after cleanup is claimed.
The cleanup promise remains a result handle outside the model.

The interpreter keeps the security/effect ordering: in-flight output checks the
current close direction; the Close frame is returned before the following pull
closes the channel; physical channel closure precedes response cancellation and
release. Cleanup is claimed before user callbacks. Inbound peer Close launches
output cancellation without joining finalizers that can await that same read.

Before extraction, `standalone/testOnly *StandaloneLifecycleSpec
*StandaloneWebSocketLimitsSpec *StandaloneDeadlineIsolationSpec
*StandaloneMessageDeadlineSpec` passed 19 real lifecycle/deadline tests. The same
command plus `*WebSocketOutputMachineSpec` passed 21 after extraction. Direct laws
pin Close emission versus cleanup and abort dominance. The zio transport baseline,
`zioHttp/testOnly *BoundedTransportSpec`, independently passed 9 tests; its scanner
and timer extraction is still pending.
