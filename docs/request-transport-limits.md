# Inbound WebSocket budgets

SB-04 is partly remediated. The application aggregation and standalone decoder
are bounded. ZIO HTTP 3.11.6 still has an unbounded dependency-owned ingress queue;
this work does **not** establish a bounded total memory footprint for that adapter.
A public deployment using ZIO HTTP needs an upstream queue/backpressure fix or a
separately verified exposure restriction before this finding can be closed.

## Limits by adapter

| Adapter | Message/frame limit | Pending application payload | Overflow behavior |
| --- | --- | --- | --- |
| Pekko | `PekkoHttpServerConfig.wsMaxMessageBytes`, default 16 KiB | At most `wsStreamedParallelism` aggregation slots (default 2); finite Reactive Streams buffers, with the pre-materialized application sink input buffer pinned to 1 | Fails input, cancels output, releases connection ownership |
| Akka | `AkkaHttpServerConfig.wsMaxMessageBytes`, default 16 KiB | Same bounded aggregation/backpressure scheme as Pekko | Same as Pekko |
| ZIO HTTP | `service` argument `wsMaxMessageBytes`, default 16 KiB, installed in the Netty frame decoder and checked on application admission | `wsMaxQueuedMessages` (default 16) times the message limit: default 256 KiB payload, plus one frame being processed | A failed offer aborts the socket; finalization closes and empties the application queue |
| Standalone | `buildServer` argument `wsMaxMessageBytes`, default 16 KiB for both advertised frame length and aggregate message | Pull-based decoder retains one partial frame and one partial message, each bounded by the limit, plus socket read chunks (default 8096 bytes) | Decoder failure follows existing connection teardown |

The limits count UTF-8 bytes for text, not UTF-16 character count. Pekko/Akka count
before each append and preserve surrogate pairs crossing chunk boundaries. Invalid
or incomplete surrogate sequences fail admission. Binary aggregation checks the
sum in `Long` before concatenation. A message exactly at the limit is accepted.
Pekko/Akka stream collection retains the existing completion deadline (default
30 seconds), independently of byte limits, including discarded terminal input.

Pekko 1.4's `FrameEventParser` emits partial frame data as it arrives; it does not
allocate the advertised frame length. Its high-level API creates a strict message
only when that message is already complete in the current input chunk. The
adapter rejects strict messages immediately and stops streamed, unfragmented
payloads as soon as the cumulative cap is crossed. A raw-socket regression sends
a 65535-byte length but only five payload bytes against a four-byte cap, checking
that ownership is released without receiving the remaining payload. There is no
public Pekko setting for a frame-length rejection based solely on its header.
The same application aggregation policy is applied to the Akka sibling.

The standalone decoder rejects excessive or negative advertised lengths before
waiting for payload chunks; fragment aggregation checks the cumulative size
before appending. `HttpClient.create(maxWebSocketMessageBytes = ...)` uses the same
bounded decoder with a separate 16 MiB default because server responses, including
DOM updates, can exceed the client-command budget. This replaces its previous
unbounded behavior; callers receiving larger responses must choose an explicit
positive limit. Direct protocol users can choose a different positive limit.
Outgoing messages are not limited by these ingress settings.

ZIO HTTP previously delivered the initial nonfinal text/binary fragment as a
complete application command and then failed on continuation. It now rejects the
initial fragment before delivering a partial command. Fragmented messages remain
unsupported on this adapter; this is not a fragmentation reassembly feature.
Netty's existing masking and UTF-8 validation stay enabled.

## Remaining deployment requirements

The application queue budget is not a process heap cap. Byte arrays, UTF-16 text,
accumulator copies, protocol objects, TLS/TCP buffers, stream-stage buffers, and
outgoing application data add overhead. Pekko/Akka retain a finite number of
messages under backpressure; materializer/transport buffer overrides must be
included in deployment memory calculations. Aggregate retained payload scales
with concurrent connections. Set and verify connection quotas, transport read
chunk sizes, idle/read deadlines, and deployment rate controls. The standalone
message byte cap does not add a fragmented-message completion deadline.

ZIO HTTP 3.11.6 `ServerInboundHandler.upgradeToWebSocket` unconditionally constructs
`Queue.unbounded[WebSocketChannelEvent]`. `WebSocketAppHandler` copies decoded frame
payloads into this queue before Spoonbill can apply admission checks.
`WebSocketConfig` and `SocketDecoder` have no supported queue-capacity setting.
The new bounded application queue and decoder reduce exposure but cannot bound
that earlier queue under a producer outrunning the handler. No total per-connection
or aggregate ZIO transport budget is claimed. An untested proxy rate limit is not
closure evidence. Keep this limitation in deployment release decisions.

Guarded frontend message validation, protocol negotiation, uncompressed guarded
transport requirements, authority checks, and existing release-once ownership
contracts remain in place.

## Repeatable validation

Run inside the repository Nix environment:

```sh
nix develop --no-write-lock-file -c sbt \
  'http/testOnly spoonbill.http.protocol.WebSocketLimitsSpec' \
  'pekko/testOnly spoonbill.pekko.PekkoWebSocketLimitsSpec' \
  'akka/testOnly spoonbill.akka.AkkaWebSocketLimitsSpec' \
  'standalone/testOnly spoonbill.server.StandaloneWebSocketLimitsSpec' \
  'zioHttp/testOnly spoonbill.zio.http.ZioHttpIngressLimitsSpec'
```

The tests cover declared lengths before payload arrival, cumulative fragmentation,
UTF-8 boundaries and split surrogates, slow incomplete input, real Pekko and standalone sockets,
Netty's configured decoder boundary, bounded queue overflow, and cleanup. Run the
existing adapter lifecycle/ownership suites as well. These local regressions do
not establish production heap limits or proxy behavior; a disposable deployment
load test and connection quota verification remain required for release closure.
