# WebSocket lifecycle release note

This is the historical 3.0.0 release note. For the current 7.0.0 API and upgrade
steps, see the [user guide](user-guide.adoc) and [v7 migration guide](migration-v7.md).

This change breaks the WebSocket result type shipped in 2.0.0. Protocols and on-wire messages are unchanged. Custom adapters and service wrappers must be rebuilt. Old binaries are not expected to link.

## Historical version decision

At the original assessment on 2026-10-09, GitHub release `v2.0.0` was the latest release. Its Maven coordinates used organization `com.natural-transformation` and the Scala 3 artifact suffix, including `spoonbill`, `spoonbill-pekko`, `spoonbill-akka`, `spoonbill-zio-http`, and `spoonbill-standalone`, at version `2.0.0`.

Maven Central [does not allow replacing or removing published components](https://central.sonatype.org/faq/can-i-change-a-component/). The change was therefore released as **3.0.0** rather than replacing 2.0.0, because `WebSocketResponse` and `Effect` had breaking API changes. That development build used `3.0.0-SNAPSHOT`, with `RELEASE_VERSION=3.0.0` supplied for publication. See the [3.0 migration guide](migration-3.0.md) for that upgrade.

## API migration

`SpoonbillService.ws` still returns `F[WebSocketResponse[F]]`. The result is no longer an HTTP response plus a protocol. Match one of:

- `WebSocketResponse.Duplex(output, selectedProtocol, release)` — live input and output. Canceling application input still ends the connection.
- `WebSocketResponse.SendThenClose(output, selectedProtocol, release)` — finite output, then a normal close. Releasing application input must not cancel `output`.

Service failure, including access denial and a rejected origin, stays in the effect error channel.

Transform output with `mapOutput` or `withOutput`. Both keep the variant. Call and execute `release()` when the upgrade is abandoned or the transport has ended, and do not pull `output` after that. Do not discard the effect: a lazy `F` runs only when it is sequenced.

Adapters choose the transport path once from the variant. For `SendThenClose`, release the unused application input, drain peer bytes with the existing completion limit and bounded parallelism, and let finite output completion close the connection. Do not inspect frame bytes or cancellation timing to pick the mode.

## Behavior

A guarded reconnect whose local view is missing and whose `resume` returns `None` now delivers one reload frame, `[1]`, and then the server closes the socket. The guard is not opened for that view. If a guard was acquired before the missing baseline was discovered, it is released before the reload result is returned.
