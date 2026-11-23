# ADR 0005 — Transport deadlines and framing

Status: adopted in the HTTP and WebSocket transports.

## Context

Transport limits must hold before application delivery, including when an
executor is busy, bytes arrive in small fragments, or an application stops
pulling. Parser extraction must preserve consumed-prefix, error and callback
ordering without allocating a transition object for every byte.

## Decision

### Share deadline decisions only where completion means the same thing

HTTP header/body assembly, standalone WebSocket message assembly and HTTP-client
TCP/TLS setup use [DeadlineMachine](../../modules/effect/src/main/scala/korolev/effect/DeadlineMachine.scala).
An armed interval is absolute: progress does not extend it, and completion at or
after the bound fails even if the timer task has not run. Timer tickets fence
stale callbacks. Scheduling failure consumes its ticket without installing a
job; scheduling, handle publication and cancellation remain serialized by the
interpreter. Timer expiry closes the physical transport without waiting for the
application executor. The wrappers retain their own result/error contracts.
See [request deadline](../../modules/http/src/main/scala/korolev/http/RequestDeadline.scala),
[message deadline](../../modules/standalone/src/main/scala/korolev/server/MessageDeadline.scala)
and [deadline laws](../../modules/effect/src/test/scala/korolev/effect/DeadlineMachineSpec.scala).

Other timers are deliberately different:

| Protocol | Rule that prevents substitution |
| --- | --- |
| Required-operation wait | A first-winner permit arbitrates completion and timeout; it is not a completion-time elapsed-clock check. Resource-owning callers handle late acquisition. |
| Guarded/legacy session idle | Rearming preserves each interpreter's cancellation order. A canceled callback already dispatched can still win terminal publication; replacing it with assembly-ticket rules changes behavior. |
| Long-poll wait | The delivery machine settles each poll once, by the first event it sees: a frame, the wait limit or the end of the request. A frame that arrives after the bound still answers the poll. Expiry answers `204` and closes nothing; a callback for a settled poll is inert. |
| Standalone upgrade setup | Attachment retains response cleanup until physical termination. Repeated attachment succeeds; rejection after attachment is inert. Timer expiry closes transport, while its reserved close notification performs application cleanup. |

Keep [standalone SetupDeadline](../../modules/standalone/src/main/scala/korolev/server/SetupDeadline.scala)
as its existing Pending/Attached/Expired/Finished ownership protocol rather than
forcing it into the [shared adapter setup machine](../../modules/spoonbill/src/main/scala/korolev/internal/SetupOwnershipMachine.scala).
Akka, Pekko and the zio-http adapter share Waiting/Prepared/Attached/Abandoned
transfer decisions; their timers and failure policies stay adapter-owned.
Attachment transfers ownership once in that machine, whereas standalone retains
cleanup. The standalone
[ownership tests](../../modules/standalone/src/test/scala/korolev/server/StandaloneSetupDeadlineSpec.scala),
the [session idle tests](../../modules/spoonbill/src/test/scala/korolev/server/internal/services/SessionIdleTimingSpec.scala)
and the long-poll wait tests
([machine](../../modules/spoonbill/src/test/scala/korolev/server/internal/services/PollDeliveryMachineSpec.scala),
[delivery](../../modules/spoonbill/src/test/scala/korolev/server/internal/services/LongPollingDeliverySpec.scala))
pin these differences.

### Keep the zio-http wire boundary inside its derived runtime

The zio-http adapter uses a separately published derived runtime, not classpath
shadowing of an upstream jar. [The build](../../project/ZioHttpRuntime.scala)
verifies pinned upstream inputs and packages the replacement handler; the
[runtime check](../../interop/zio-http-runtime/src/main/scala/zio/http/netty/SpoonbillWebSocketRuntime.scala)
rejects an unpatched handler or duplicate runtime. Applications must not add the
unpatched `dev.zio:zio-http` jar alongside it.

The server [upgrade handler](../../interop/zio-http-runtime/src/main/scala/zio/http/netty/WebSocketAppHandler.scala)
installs the raw frame observer after TLS and before WebSocket decoding. A
temporary upgrade boundary rejects unread pre-upgrade bytes rather than guessing
where a frame starts. Installing the observer after decoding began fails closed.
The handler bounds ingress before frame conversion and leaves a terminal signal
when discarding queued payloads, so an idle consumer can finish.

Unlike the standalone protocol, this runtime rejects fragmented WebSocket input
at its first header byte. Its [frame scan](../../interop/zio-http-runtime/src/main/scala/zio/http/netty/WebSocketFrameScan.scala)
and [deadline observer](../../interop/zio-http-runtime/src/main/scala/zio/http/netty/WebSocketFrameDeadline.scala)
therefore remain local to the derived runtime; it has no dependency on Spoonbill's
effect module. The timer enforces the same strict absolute-completion predicate,
but event-loop ownership needs no cross-executor callback ticket. Scanning stops
at each frame boundary so completion/cancellation and the next timer arm precede
the coalesced tail. It skips payload without copying or changing `ByteBuf`
indexes; rejection releases the input rather than forwarding it. See
[wire-boundary tests](../../interop/zio-http/src/test/scala/zio/http/netty/BoundedTransportSpec.scala)
and [split-header tests](../../interop/zio-http/src/test/scala/zio/http/netty/WebSocketFrameScanCharacterizationSpec.scala).

### Keep checked framing authoritative and storage compact

[ChunkedBodyDecoder](../../modules/http/src/main/scala/korolev/http/protocol/ChunkedBodyDecoder.scala)
has a pure checked step for framing, extensions, length overflow, CRLF and trailer
budgets. Its single-owner adapter retains immutable framing state or a payload
cursor with a full-width remaining length. Bounded simple-header and payload
paths use the same decisions; incomplete or rejected probes fall back to checked
parsing. Payload slices, consumed prefixes, errors and take-back order must not
move across those boundaries.

Empty input is a state-preserving `TakeNext`: the generic decoder feeds back even
empty `Fork` remainders. It must not reconstruct framing snapshots for that common
case. A lone CR/LF may inspect or retire an immutable prefix without rebuilding
it; ordinary metadata uses a stable local builder. The zio scanner similarly
installs pure scan results into event-loop-owned scalar storage, without a second
parser or narrowed counters. [Chunked model tests](../../modules/http/src/test/scala/korolev/http/protocol/ChunkedBodyMachineSpec.scala)
compare compact and checked paths across splits; [framing tests](../../modules/http/src/test/scala/korolev/http/protocol/Http11BodyFramingSpec.scala)
exercise the public decoder.

Standalone WebSocket frame decoding and fragment merging are already pure.
Their observer reads `pendingFrameBytes` **after** `onMessageCompleted`: that
callback may reject the message, and no tail deadline may then be rearmed.
Control frames do not finish an outstanding fragmented message. Keep this effect
boundary instead of precomputing a command batch; see
[protocol code](../../modules/http/src/main/scala/korolev/http/protocol/WebSocketProtocol.scala)
and [message-signal tests](../../modules/http/src/test/scala/korolev/http/protocol/WebSocketMessageSignalsSpec.scala).

### Transport ownership outlives logical completion where required

An unread or canceled HTTP request body cannot become the next request's framing.
The [server](../../modules/http/src/main/scala/korolev/http/HttpServer.scala)
retains transport ownership long enough to send an early refusal, then closes
instead of reusing that connection. [Active connection tracking](../../modules/http/src/main/scala/korolev/effect/io/ActiveConnectionsMachine.scala)
is a distinct drain: it retains the first failure and finishes only after
acceptance stops and handlers settle; handler failure alone does not stop acceptance.

The client's [pool handoff](../../modules/effect/src/main/scala/korolev/effect/AsyncResourcePool.scala)
owns acquisition through the supplied task's success. The caller must still
bracket or adopt the returned stream/body; success does not promise irrevocable
delivery through a later native cancellation. Abort uses the loan's `discard`
claim before touching the socket, so a late abort cannot close a returned and
reused connection. See [client cancellation tests](../../interop/ce3/src/test/scala/external/HttpClientBorrowCancellationIOSpec.scala).

Standalone output preserves Close-frame-before-next-pull shutdown and physical
close-before-response-cleanup order. Abort suppresses late output, including a
successful attachment arriving after cancellation; it must not start another
application pull. [Attachment tests](../../modules/standalone/src/test/scala/korolev/server/StandaloneOutputAttachmentSpec.scala)
pin that boundary.

## Alternatives and consequences

A single timeout abstraction would conflate absolute assembly, first-winner
arbitration and setup-resource ownership. A downstream-only zio interceptor would
act after decoding/conversion and miss the bounded ingress boundary. Allocating
immutable parser snapshots on every byte or empty tail adds cost without stronger
ownership. These alternatives are not used.

Changes must retain public framing/error tests and compare compact storage with
the checked model. Hot-path measurements must use matched source/fixture pairs;
the [transport measurement guide](../../misc/transport-state-machines/README.md)
records reproduction commands, selected results and diagnostic limits.
