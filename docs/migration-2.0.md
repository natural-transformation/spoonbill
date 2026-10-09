# Migrating to Spoonbill 2.0

## Dependencies and recompilation

Upgrade all Spoonbill modules together to `2.0.0` and recompile the application
and its integrations. This release does not preserve binary compatibility with
1.x configuration constructors or access interfaces.

```scala
libraryDependencies += "com.natural-transformation" %% "spoonbill" % "2.0.0"
```

The optional PostgreSQL adapters are a separate dependency:

```scala
libraryDependencies += "com.natural-transformation" %% "spoonbill-security-jdbc" % "2.0.0"
```

Core transaction interfaces do not require JDBC. Supply an adapter for your
database that meets the atomicity and lifetime contract. JDBC users supply their
own `DataSource` and PostgreSQL driver; the module's test driver is not a runtime
dependency. Apply schema initialization explicitly through the adapter APIs.

## Custom runtime integrations

`SpoonbillServiceConfig` has new configuration parameters. Prefer named arguments
and recompile code that calls its constructor or `copy` method.

Custom implementations of `Context.BaseAccess`, `Context.EventAccess` and
`Context.Access` must implement the new access methods. The bundled runtime and
testkit implementations are updated. A custom browser runtime must implement
guarded operations explicitly rather than treating them as ordinary DOM output.

`StateStorage.remove` must be safe to retry. Guarded session recovery may repeat
a removal after failure. Use `StateStorage.ephemeral` when state should stay in
memory even in development mode. Durable presentation uses the separate typed
snapshot APIs rather than serializing arbitrary component or DOM state.

## WebSockets and guarded sessions

For unguarded sessions, `compressionSupport` alone no longer enables
`json-deflate`. Set `webSocketCompressionEnabled = true` explicitly if compression
is needed and its security implications are appropriate for the application.

For guarded sessions, configure `sessionAccessControl` and
`authenticationCompletion` together, keep `webSocketEnabled = true`, and leave
`webSocketCompressionEnabled = false`. Authentication completion provides the
allowed-origin policy used for guarded WebSocket connections. Browser cookies
are installed through its bounded HTTP completion endpoint.

Guarded sessions disable legacy HTTP bridge routes, including long polling and
attachments. Applications needing file transfer should provide an independently
authorized endpoint. Do not rely on those bridge routes as a fallback transport.

## Transaction adapters

`TransactionExecutor[F, G, Tx]` separates the application's result effect `F`
from the joined transaction program `G` and native capability `Tx`. Direct JDBC
callbacks can use `G = Direct`; asynchronous database clients can supply their
native transaction effect. The ZIO2 module provides `Zio2TransactionProgram`.

Join all reads, authorization checks, mutations and outcome writes in that
transaction. Do not commit or close the supplied capability, detach work, or
automatically replay an admitted transaction body. Commit uncertainty is not
proof of rollback; reconcile status through the protected store decision.

The [one-use authority contract](one-use-authority-contract.md) describes
issuance, replay protection, cancellation, capacity and reconciliation. Follow
the adapter contract for row retention and fences; deleting expired-looking
records without that analysis can remove replay protection.

## Deployment scope

Applications provide domain authorization, database migrations and deployment
configuration. The bundled adapters do not provide a distributed invalidation
delivery service or general node-failure owner reclamation. Configure and verify
those behaviors when the deployment requires them. Browser acknowledgment of a
sensitive disclosure does not establish human reading or secure memory erasure.
