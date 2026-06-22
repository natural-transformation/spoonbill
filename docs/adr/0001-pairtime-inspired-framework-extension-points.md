# ADR 0001: PairTime-Inspired Framework Extension Points

## Status

Accepted.

## Context

PairTime is a production Spoonbill application that uses Pekko HTTP, Pekko
Typed actors, streamed file uploads, custom session state storage, and
renderer-level UI tests. That usage exposed several places where Spoonbill had
the right primitives but required application code to copy internal patterns or
hand-roll lifecycle and decoding glue.

The goal is to make those production patterns first-class without forcing a
specific effect library, validation library, storage backend, or actor runtime
onto all Spoonbill users.

## Decision

### Pekko HTTP Interop

Treat `spoonbill-pekko` and `spoonbill.pekko.pekkoHttpService` as the supported
Pekko HTTP entry point. Keep the existing stream adapters public through the
Pekko module and verify HTTP, WebSocket protocol negotiation, streamed request
bodies, and streamed response bodies in integration tests.

The user guide should use current `com.natural-transformation` coordinates and
show `pekkoHttpService` in the Pekko HTTP section.

### Managed Session Resources

Add `Extension.managed` and `Extension.managedF` for resources whose lifecycle is
bound to a browser session. The helper guarantees that an acquired resource is
released at most once, including when handler setup fails after acquisition.

Session shutdown should report extension cleanup failures without preventing
other extension cleanup, state removal, or app removal.

### Custom State Storage

Expose reusable public builders for custom state storage:

- `StateManager.cached` for typed in-memory values.
- `StateManager.serialized` for serialized byte snapshots.
- `StateStorage.fromRepository` for repository-backed session storage.

The repository-backed helper keeps the existing `StateStorage` contract, including
the synchronous `remove` method, and bridges effectful repository removal by
running it asynchronously with an error callback.

### Render-Safe Client Effects

Add `afterRender` as a server-render-ordering primitive. It runs a client-side
effect after pending state transitions have rendered on the server. It does not
promise browser-side DOM patch acknowledgement; a future protocol-level
acknowledgement should use an explicit stronger contract.

Testkit should expose deterministic behavior for `afterRender` so renderer tests
can assert the expected action order.

### Typed Value And Event Decoding

Add small decoder typeclasses rather than committing Spoonbill core to a JSON or
validation library:

- `Context.ValueDecoder`
- `Context.EventDataDecoder`
- `access.valueAs[T]`
- `access.checked`
- `access.eventDataAs[T]`

Spoonbill provides primitive value decoders and leaves richer browser event
models or application-specific event decoding to user code.

## Consequences

Applications can remove repeated bridge, lifecycle, and storage glue while still
choosing their own infrastructure and effect stack.

The `afterRender` API intentionally has a narrower guarantee than browser-applied
ordering. That keeps the first implementation compatible with the current wire
protocol and leaves room for a future acknowledged client-effect queue.

The custom storage helper preserves source compatibility with the existing
`StateStorage` API, but asynchronous repository removal cannot be awaited through
the current synchronous `remove` method.
