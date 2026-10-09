# Migrating to Spoonbill 3.0

Upgrade all Spoonbill modules together to `3.0.0` and recompile applications,
custom transport adapters, and custom effect instances. This release changes
the public WebSocket lifecycle API; 2.0 binaries are not compatible.

```scala
libraryDependencies += "com.natural-transformation" %% "spoonbill" % "3.0.0"
```

## Explicit WebSocket responses

`SpoonbillService.ws` still returns `F[WebSocketResponse[F]]`. Replace the former
HTTP-response envelope with one of two dispositions:

```scala
WebSocketResponse.Duplex(output, selectedProtocol, release)
WebSocketResponse.SendThenClose(output, selectedProtocol, release)
```

`output` is an ordinary `Stream[F, Bytes]`. `release` is a thunk returning
`F[Unit]`; it disposes the acquired response/session. Use
`WebSocketResponse.releaseOnce` for cleanup shared by concurrent termination paths.
Callers must execute the returned effect, not merely construct it.

- `Duplex` keeps application input and output coupled. Input cancellation, peer
  termination, or an output failure ends the connection.
- `SendThenClose` delivers finite output before normal closure. The application
  no longer needs input; releasing it must not discard the response. This is the
  result used when an unavailable view requires a browser reload.

Access denial and setup failure remain failures in `F`. They must not be converted
into successful terminal responses. Stream contents never determine disposition.

Transform output through `mapOutput` or replace it through `withOutput`. Both
preserve disposition and include the resulting stream in response disposal:

```scala
response.mapOutput(_.mapAsync(encode))
```

## Custom transport adapters

The adapter owns the physical connection. Before the service result is known,
application input cancellation must release application reads without closing a
transport that may still carry `SendThenClose` output.

On a terminal result, detach unused application input and drain incoming data
without buffering entire messages. Send output under backpressure, then complete
the WebSocket closing handshake. Peer Close, disconnect, and errors abort pending
application work. On a duplex result, input termination also ends output.

Execute response release when an attached connection ends and when an acquired
response is abandoned before attachment. A late result after request timeout must
be disposed without pulling output. Ensure error paths release resources too.

Pekko and Akka provide `wsSetupTimeout` on their HTTP server configuration, with a
positive default of 30 seconds. Setup resource ownership is bounded by the shorter
of that limit and a finite HTTP request timeout. Custom HTTP timeout responses are
preserved. The setup timer stops at WebSocket materialization. Their routing APIs
do not expose a pre-upgrade peer-disconnect callback, so unmaterialized resources
are released within this bound; a late service result is disposed when it arrives.

ZIO HTTP accepts the same positive bound as an optional service parameter:

```scala
new ZioHttpSpoonbill[Any].service(config, wsSetupTimeout = 30.seconds)
```

Its setup deadline stops when the socket handler attaches. The deadline also
disposes of results that arrive after setup has been abandoned.

`standalone.buildServer` also accepts a final optional `wsSetupTimeout` parameter
with the same default. Its deadline ends on the first output pull, after the
upgrade headers have been written. Expiry closes the connection and disposes of
late results; it does not limit the lifetime of an attached session.

## Custom effect implementations

Implement the new `Effect.uncancelable` operation using the effect runtime's
cancellation mask. It must preserve the executing context and suppress cancellation
while its argument runs. Eager effects without cancellation can evaluate the
argument directly. Spoonbill uses this operation for the single cleanup attempt
and publishing its completion to concurrent waiters, not for ordinary message
processing or service evaluation.

## Wire compatibility and previous releases

Subprotocols and frame formats are unchanged. Guarded missing-view recovery still
sends `[1]`; the corrected transport lifetime ensures delivery before closure.

Version 2.0.0 remains available. Maven Central does not permit replacing published
components; see [Sonatype's policy](https://central.sonatype.org/faq/can-i-change-a-component/).
The public API break is therefore released as 3.0.0.
