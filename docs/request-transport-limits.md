# Inbound WebSocket budgets

All four server adapters enforce finite inbound message budgets. Limits apply
before retaining complete messages, independently of guarded frontend protocol
validation. The ZIO adapter includes a derived, pinned ZIO HTTP runtime because
upstream 3.11.6 queues decoded WebSocket events without a capacity limit.

## Limits by adapter

| Adapter | Message/frame limit | Queued application payload | Time and overflow behavior |
| --- | --- | --- | --- |
| Pekko | `PekkoHttpServerConfig.wsMaxMessageBytes`, default 16 KiB | At most `wsStreamedParallelism` aggregation slots (default 2), finite Reactive Streams buffers; application sink input buffer pinned to 1 | Existing absolute collection deadline, default 30 seconds; failure cancels output and releases ownership |
| Akka | `AkkaHttpServerConfig.wsMaxMessageBytes`, default 16 KiB | Same aggregation/backpressure policy as Pekko | Same as Pekko |
| ZIO HTTP | `service` argument `wsMaxMessageBytes`, default 16 KiB, installed in Netty's decoder | Before conversion: at most 16 admitted transport events, therefore at most 256 KiB data at the default frame cap, plus one reserved payload-free terminal on ordinary closure. Application queue: `wsMaxQueuedMessages` (default 16) times message cap, another 256 KiB | Queue overflow closes connection and replaces buffered input with one terminal signal; absolute raw frame deadline 30 seconds |
| Standalone | `buildServer` argument `wsMaxMessageBytes`, default 16 KiB, bounds advertised frames and aggregate fragments | Pull-based decoder: one partial frame and one partial message, each bounded, plus socket read chunks (default 8096 bytes) | `wsMessageTimeout`, default 30 seconds, starts at first input and persists through fragments/control frames; failure closes socket and releases ownership |

Budgets count UTF-8 bytes, not UTF-16 characters. Pekko/Akka count before every
append and preserve surrogate pairs crossing chunks. Invalid or incomplete
surrogate sequences fail admission. Binary aggregation checks the sum in `Long`
before concatenation. Messages exactly at the cap succeed.

Pekko's frame parser emits partial data without allocating the advertised length.
Strict messages occur only when complete in the current input chunk. The adapter
checks strict messages immediately and stops larger streamed/unfragmented input
as soon as its cumulative cap is crossed. A raw-socket regression advertises
65535 bytes but sends only five against a four-byte cap; the connection releases
without waiting for the rest. The Akka sibling uses the same aggregation policy.

The standalone decoder rejects oversized/negative lengths from their headers,
and checks aggregate fragments before appending. Its independent timer closes a
stalled partial input even when no further bytes arrive. Progress and interleaved
control frames do not reset a fragmented message's deadline. Valid completion
cancels the timer. Cancellation and errors use the existing once-gated teardown.

Standalone HTTP servers reserve a cleanup slot before invoking a handler or
installing a deadline. `maxConnections` defaults to 1024 and includes connections
whose transport has closed but whose serving work or cleanup has not finished.
`closeNotificationWorkers` defaults to 2; the per-server notification queue holds
at most `maxConnections` tasks, with no caller-runs fallback. Saturation closes
new connections before application handlers run. Ordinary RawDataSocket clients
do not inherit this server admission limit.

Header, body, setup, and message timers close the native socket directly, then
submit its one reserved close notification. Application finalizers never run on
those shared timers. A blocked synchronous finalizer can occupy a notification
worker, and asynchronous cleanup retains its admission slot until completion.
Both are bounded by the server's configured connection budget. Graceful shutdown
waits for serving work and finalization; stopping admission does not discard
already reserved cleanup. Applications must still make their finalizers terminate
to recover capacity after application failures.

`HttpClient.create(maxWebSocketMessageBytes = ...)` uses a separate 16 MiB response
cap: server DOM updates can exceed the command budget. This replaces previously
unbounded response decoding; users expecting larger responses must explicitly
choose a positive finite cap. Outgoing messages are not limited by ingress caps.

## Derived ZIO HTTP runtime

`spoonbill-zio-http-runtime` derives from `dev.zio:zio-http_3:3.11.6`. The managed
build verifies SHA-256 hashes for the upstream binary, sources, POM, and original
handler source. It compiles the patched handler and packages the original classes
with the replaced handler's entire class/TASTy family removed. Runtime consumers
receive one derived jar, not two competing implementations. Source publication
includes unchanged upstream sources plus the patch. Apache-2.0 license and
attribution, upstream version, POM, dependency scopes, and native classifiers are
preserved. The two direct `dev.zio:zio_3` and `dev.zio:zio-streams_3` dependencies
are explicitly upgraded to 2.1.27; the manifest and packaging checker record this
reviewed override without changing the original POM hash. Updating upstream
requires reviewing the source patch and hashes.

The patched handler admits events on the channel's serialized Netty event loop.
It checks queue size before `frameFromNetty` copies or converts the payload.
Consumers only decrease the size, so concurrent consumption cannot over-admit.
All dispatch paths use the same cap. Overflow closes the channel, clears retained
payloads, and leaves one terminal failure to wake the receiver; queued protocol
messages are never silently dropped while keeping the connection alive. Ordinary
peer closure preserves admitted events in FIFO order, followed by one reserved
payload-free terminal signal. The termination gate prevents any later producer
from adding events, so the total is bounded by 16 admitted events plus one terminal.
This preserves a final client response even when its consumer runs after closure.

The adapter keeps decoder/UTF-8 validation enabled but disables automatic decoder
Close emission. The bounded runtime sends one explicit failure Close through the
normal outbound pipeline before publishing the terminal event, then requests
protocol-aware shutdown. This lets Netty record the Close and suppress competing
Close writes. The public `forceCloseTimeout` is set to one second, bounding a
stalled close write. Decoder violations retain their 1007/1009 codes, queue
exhaustion uses 1013, policy/time violations use 1008, and other failures use 1011.

The socket regression permits at most one bounded Close control frame and no
application output. EOF or a TCP reset counts only together with the server's
`channel.awaitShutdown` witness, obtained while the client socket and consumer
gate are both still open. Client cleanup cannot create this witness. The public
shutdown API initiates channel closure; it does not promise FIN rather than RST
when a peer floods unread input. Cleanup still runs once, and a later independent
connection must succeed.

A server-only wire observer is installed after TLS and before the HTTP decoder
that is replaced during upgrade. The accepted-upgrade handoff first replaces that
HTTP decoder with a temporary rejection decoder: any unread prefix forwarded on
removal, or bytes received before the real WebSocket decoder is installed, close
the connection. Pre-101 WebSocket input is intentionally rejected rather than
guessing the boundary of an already consumed frame. Netty replaces the temporary
decoder before writing HTTP 101, so a compliant client can send its first frame
immediately after reading that response. The observer also refuses installation
after a WebSocket decoder already exists.

The observer tracks at most 14 header bytes and a remaining length counter,
retains no payload, and never changes buffer read indexes. It enforces an absolute
30-second frame assembly deadline, including the initial post-upgrade wait.
Subsequent idle periods after complete frames do not retain a timer. Masking,
UTF-8 validation, and the configured maximum frame size remain Netty's job.

ZIO server fragmentation is explicitly unsupported. Nonfinal/continuation frames
are rejected from the first header byte, before a split UTF-8 sequence is converted
or a partial application command delivered. The adapter also rejects nonfinal
frames defensively at its application boundary. Other adapters support bounded
fragmentation.

The ZIO service checks the runtime at startup and rejects an unpatched handler or
duplicate handler resources. If another dependency brings `dev.zio:zio-http`,
exclude that original artifact and retain the derived runtime; do not depend on
classpath ordering. The derived runtime's bounded event admission also applies to
ZIO HTTP client sockets; server-only wire fragmentation/deadline policy does not.

## Official integration evidence (22 October 2026)

Fresh GitHub release API checks confirmed [ZIO HTTP 3.11.6](https://github.com/zio/zio-http/releases/tag/v3.11.6)
(14 September) is current and [ZIO 2.1.27](https://github.com/zio/zio/releases/tag/v2.1.27)
(11 October) is the current core release. The latter's published POM uses Scala
3.3.8, matching this repository. Upgrading core does not change the HTTP transport's
[unbounded queue allocation](https://github.com/zio/zio-http/blob/v3.11.6/zio-http/jvm/src/main/scala/zio/http/netty/server/ServerInboundHandler.scala#L316-L343);
the same allocation remains on upstream `main` at verification time.

The official [server configuration](https://ziohttp.com/reference/server/#websocket-configuration)
exposes decoder limits, close status, and force-close timing, but no ingress queue
capacity. The [WebSocket examples](https://ziohttp.com/examples/websocket/) use
`Handler.webSocket`, `receiveAll`, and `channel.shutdown`, while the
[frame reference](https://ziohttp.com/reference/socket/websocketframe/) documents
explicit Close frames. The tagged implementation makes
[`shutdown` initiate the Netty channel close](https://github.com/zio/zio-http/blob/v3.11.6/zio-http/jvm/src/main/scala/zio/http/netty/WebSocketChannel.scala#L82-L83)
and `awaitShutdown` observe its completion. We retain those lifecycle semantics;
the small derived runtime supplies the missing pre-conversion admission bound.

## Validation and deployment accounting

Run inside the repository's Nix environment:

```sh
nix develop --no-write-lock-file -c sbt \
  'http/testOnly spoonbill.http.protocol.WebSocketLimitsSpec spoonbill.effect.io.SocketCloseDispatcherSpec' \
  'pekko/testOnly spoonbill.pekko.PekkoWebSocketLimitsSpec' \
  'akka/testOnly spoonbill.akka.AkkaWebSocketLimitsSpec' \
  'standalone/testOnly spoonbill.server.StandaloneWebSocketLimitsSpec spoonbill.server.StandaloneMessageDeadlineSpec spoonbill.server.StandaloneDeadlineIsolationSpec spoonbill.server.StandaloneSetupDeadlineSpec' \
  'zioHttp/testOnly spoonbill.zio.http.ZioHttpIngressLimitsSpec zio.http.netty.BoundedTransportSpec zio.http.netty.WebSocketUpgradeSocketSpec spoonbill.zio.http.ZioHttpTransportSocketSpec'
```

Tests exercise declared lengths before payload arrival, UTF-8 boundaries and split
surrogates, fragmentation, stalled frames, completion/reset behavior, actual
Netty queue admission, runtime artifact identity, and raw server sockets. The ZIO
socket stress pauses the handler, sends 1000 masked frames, and requires closure
before allowing the handler to consume; a separate connection must still echo.
Run existing adapter lifecycle/ownership suites alongside these checks.

The upgrade regression uses a real loopback HTTP handshake and Netty's supported
lenient HTTP parsing configuration to retain an eager partial frame. Default
strict parsing can reject that input earlier; universal reachability under the
default parser is not claimed. Separate default-ZIO tests send immediately after
101 with both values of `avoidContextSwitching` and verify the payload is intact.

CI must also run `zioHttpRuntime/packageBin`, `zioHttpRuntime/packageSrc`, and
`zioHttpRuntime/makePom`, and export `zioHttp/Runtime/fullClasspath` to a file
containing only its platform-separated path list. Verify those exact artifacts:

```sh
nix develop --no-write-lock-file -c node misc/check-zio-http-runtime.mjs \
  --jar path/to/spoonbill-zio-http-runtime.jar \
  --pom path/to/spoonbill-zio-http-runtime.pom \
  --sources path/to/spoonbill-zio-http-runtime-sources.jar \
  --classpath-file path/to/runtime-classpath.txt
```

This checks the entire runtime classpath for duplicate original handlers, the
published dependency POM, Apache attribution, and the generated SHA-256 manifest
against all four tracked and published patch sources. It fails on an omitted
upstream source artifact or changed pinned input; it does not publish anything.

These are payload budgets, not exact JVM heap limits. UTF-16 text, arrays,
accumulator copies, protocol objects, TLS/TCP buffers, finite stream-stage
buffers, and outgoing data add overhead. Aggregate storage scales with concurrent
connections. Deployment owners still need finite connection quotas and memory
accounting for their materializer/transport settings; changing those settings
requires revalidating the totals. Such operational controls supplement the
implemented per-connection bounds rather than substitute for them.

Guarded frontend validation, uncompressed guarded transport requirements,
subprotocol negotiation, authority checks, and once-only release remain intact.
