# WebSocket notification calibration

This measurement-only probe compares browser notification counts with actual
client-to-server WebSocket frames on a synthetic loopback connection. It does not
observe the reference application, real credentials or arbitrary browser pages,
and does not fill performance budgets.

Run the deterministic parser/ownership tests and the explicit native probe:

```sh
nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/websocket-events.test.mjs
nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/websocket-events.native-tests.mjs
```

The native file requires the exact pinned Playwright 1.56.1 driver and both
installed browsers. Missing capabilities fail; they are not silently skipped.
Every case owns a new context and a new loopback server, sends three messages,
waits for three fixed server ACKs, and completes the close handshake. The server
declines WebSocket extension negotiation. Browser-side `CompressionStream` cases
compress synthetic data before `send(Blob)`, including the reference client's
`deflate-raw` form; this is separate from WebSocket extension compression.

The physical counter retains one 14-byte header buffer. It checks masking,
opcodes, FIN/continuation order, length encodings, control-frame shape and bounded
frame/byte counts, then skips payload bytes without copying or unmasking them.
It distinguishes data frames (including continuations), completed logical
messages and control frames. A fixed ACK is sent only after the final payload
byte of a complete message. This is a framing counter, not a general WebSocket
server: it does not inspect UTF-8, close reasons or ping payloads. Unit tests cover
split headers, 16/64-bit lengths, fragmentation and interleaved control frames.

Observers read only event counts and Chromium request-id/opcode metadata. They
never access public event payloads, CDP `payloadData`, URLs, cookies or message
contents. Bounded socket/event sets, server/work deadlines and owned context,
session, socket and browser cleanup prevent indefinite captures. Tests install
throwing payload/URL getters to enforce this observer boundary.

The [official CDP Network schema](https://github.com/ChromeDevTools/devtools-protocol/blob/master/pdl/domains/Network.pdl)
defines `WebSocketFrame` as a complete WebSocket message, despite its name, and
`webSocketFrameSent` as a message-sent notification. Its count is therefore not a
count of physical frames when messages are fragmented. Matching counts below
apply to the observed unfragmented cases; only the wire parser measures their
physical framing.

On the tested Darwin build (Chromium 141.0.7390.37, WebKit 26.0), each case
produced exactly three physical data frames and three completed messages:

| Send kind | Bytes before optional compression | Chromium CDP opcode 1/2 events | Chromium public `framesent` | WebKit public `framesent` |
|---|---:|---:|---:|---:|
| Blob | 128 | 3 | 0 | 3 |
| Text | 128 | 3 | 3 | 3 |
| ArrayBuffer | 128 | 3 | 3 | 3 |
| Blob | 0 | 3 | 0 | 0 |
| Text | 0 | 3 | 0 | 0 |
| ArrayBuffer | 0 | 3 | 0 | 0 |
| Gzip-compressed Blob | 128 | 3 | 0 | 3 |
| Deflate-raw-compressed Blob | 128 | 3 | 0 | 3 |

The public event stream therefore cannot stand in for all physical sends on
either browser. The observed Chromium CDP stream matched these wire cases;
**the reference pilot's 6/7 notifications versus 3/4 expected application sends
was not reproduced as CDP duplication here**. Extra real client protocol sends,
observation scope and reconnect boundaries still require investigation against
that workload. Do not divide or subtract events to force agreement.

Results retain separate physical and notification quantities and leave
`physicalFramesFromBrowserEvents` null. Synthetic unfragmented matches do not
override CDP's message-level contract or establish behavior for extension
compression, failure, reconnect or the actual reference workload. Wire frames, logical messages and
request/response exchanges also remain different quantities. The observer does
not turn a frame count into an exchange count.
