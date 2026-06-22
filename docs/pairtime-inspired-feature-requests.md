# PairTime-Inspired Feature Requests

PairTime is a large server-side rendered application built on Spoonbill with
Pekko HTTP, Pekko Typed actors, streamed file uploads, custom session state
storage, and renderer-level UI tests. The requests below come from that usage,
but they are framed as general framework improvements for any non-trivial
Spoonbill application.

Each request distinguishes between capabilities Spoonbill already has and the
work needed to make those capabilities stable, documented, and ergonomic for
production applications.

## 1. Make Pekko HTTP Interop a Stable Supported Public Surface

### User Value

Teams using Pekko HTTP should be able to add one Spoonbill dependency, import one
package, and mount a route without copying bridge code into their application.
This is especially valuable for production apps that already use Pekko Streams,
Pekko Typed, and custom route composition.

### Current Friction

Spoonbill already has a `spoonbill-pekko` module, a `pekkoHttpService` entry
point, and stream adapters. The remaining friction is confidence and discovery:
published coordinates, examples, package names, public adapter names, and test
coverage need to clearly match the current code.

Before this work, the user guide still contained old `org.fomkin` coordinates in
several sections, and the Pekko HTTP example showed `akkaHttpService` where it
should show `pekkoHttpService`.

### Proposed Shape

- Treat `com.natural-transformation %% "spoonbill-pekko"` as the supported
  public artifact.
- Keep the public route API centered on `spoonbill.pekko.pekkoHttpService`.
- Document the expected imports for route setup, including `spoonbill.pekko.*`
  and any adapter imports required from `spoonbill.pekko.instances`.
- Provide examples for mounting the Spoonbill route inside a larger Pekko HTTP
  route tree.
- Document the stream converters already exposed by the Pekko module:
  - Spoonbill stream to Pekko `Source`
  - Pekko `Source`/`Sink` to Spoonbill stream
  - Reactive Streams `Publisher`/`Subscriber` adapters
  - Pekko `ByteString` `BytesLike` support
- Treat low-level `util` classes as implementation details unless they are
  intentionally promoted to supported API.
- Support Pekko Typed applications ergonomically, either with a typed
  `ActorSystem[_]` overload or explicit documentation for obtaining the required
  classic actor system.

### Acceptance Criteria

- A Pekko HTTP app can remove local copies of Spoonbill route, WebSocket, byte
  string, and stream adapter code.
- The user guide uses current coordinates, current package names, and
  `pekkoHttpService` in the Pekko section.
- The documented imports compile in a minimal Pekko HTTP example.
- The module has an integration test that exercises:
  - ordinary HTTP page rendering
  - streamed request bodies
  - streamed response bodies
  - WebSocket protocol negotiation for supported and unsupported protocols
- The public/private boundary for stream adapter APIs is documented.

## 2. Add Managed Session Extensions for Long-Lived Resources

### User Value

Many applications need one background resource per browser session: a message
queue subscription, a search worker, a timer, a live actor, or a domain-event
listener. Spoonbill already has lifecycle hooks, but a small abstraction could
make the common pattern reliable and concise.

### Current Friction

Applications repeat the same lifecycle wiring:

- read the Spoonbill session id
- allocate a resource for that session
- forward selected UI messages to it
- update state from resource callbacks
- stop the resource on `onDestroy`

For Pekko users, this often means spawning one supervised actor per session and
manually sending a shutdown message when the tab is closed.

### Proposed Shape

Add a Spoonbill-native managed-extension helper in core, plus Pekko-specific
helpers in `spoonbill-pekko`. Core should not force users to adopt Cats Effect,
ZIO, or Pekko.

Possible core API:

```scala
Extension.managed(
  acquire = access => acquire(access),
  release = resource => release(resource)
) { (resource, access) =>
  Extension.Handlers(
    onMessage = handleMessage(resource, access),
    onState = handleState(resource, access)
  )
}
```

Possible Pekko API:

```scala
PekkoSessionExtension.spawn(
  name = qsid => s"search-${qsid.sessionId}",
  behavior = (qsid, access) => SearchSessionActor(qsid, access),
  stop = SearchSessionActor.Stop,
  stopTimeout = 5.seconds
)(
  onMessage = {
    case event: SearchEvent => SearchSessionActor.FromUi(event)
  }
)
```

Lifecycle rules should be part of the API contract, not left to examples:

- If setup fails after acquisition, release is still attempted.
- Release is idempotent and runs at most once per acquired resource.
- Release failures are reported and do not prevent remaining session cleanup.
- Callbacks arriving after release are ignored or reported in a documented way.
- Pekko actors are supervised, stopped on session destroy, and watched or timed
  out so shutdown cannot hang forever.

### Acceptance Criteria

- A session resource is released exactly once when the session ends.
- A session resource is released if extension setup partially succeeds and then
  fails.
- One failing extension cleanup does not prevent other extension cleanup, state
  removal, or app removal.
- A Pekko actor spawned through the helper is supervised and stopped on session
  destroy.
- The helper does not require users to adopt Pekko unless they import the Pekko
  interop module.
- Examples cover both a generic subscription and a Pekko actor.
- Tests cover normal shutdown, duplicate close signals, setup failure, release
  failure, and actor stop timeout.

## 3. Provide Public Builders for Custom State Managers

### User Value

Production applications often need session state backed by Redis, Cassandra,
PostgreSQL, a distributed cache, or a cluster data structure. Spoonbill should
make custom storage feel like plugging in persistence hooks, not reimplementing
internal state-manager behavior.

### Current Friction

`StateStorage` is public, but a custom implementation must provide its own
`StateManager`, cache behavior, snapshot behavior, delete handling, and restore
logic. Applications may also need to define serializers only to satisfy
Spoonbill typeclass constraints, even when the custom storage keeps typed values
or delegates serialization elsewhere.

Spoonbill's default storage already contains useful mechanics, but they are not
available as stable public building blocks.

### Proposed Shape

Expose reusable state-manager builders for the common cases:

```scala
StateManager.cached[F](
  initial = Map.empty,
  onWrite = (nodeId, value) => persist(nodeId, value),
  onDelete = nodeId => delete(nodeId)
)
```

And storage helpers that let applications supply repository behavior without
copying Spoonbill's session and snapshot mechanics:

```scala
StateStorage.fromRepository[F, S](
  key = (deviceId, sessionId) => s"$deviceId-$sessionId",
  exists = key => repo.exists(key),
  loadSnapshot = key => repo.loadSnapshot(key),
  createSnapshot = (key, state) => repo.create(key, state),
  remove = key => repo.remove(key)
)
```

The design should explicitly choose how to model:

- typed in-memory values
- serialized byte snapshots
- per-node writes and deletes
- whole-session create, restore, and remove
- concurrent writes for one session
- restore after a recent remove
- custom storage behavior when developer mode is enabled

If the existing synchronous `StateStorage.remove` signature remains, the helper
should document how asynchronous repository cleanup is reported. If the API
becomes effectful, the migration path should be explicit.

### Acceptance Criteria

- A custom persistent or distributed storage implementation can reuse
  Spoonbill-provided snapshot/read/write/delete mechanics.
- The API supports typed in-memory values and serialized bytes.
- Delete, restore, and remove semantics are documented for both in-memory and
  persistent repositories.
- Concurrent writes for one session are tested or explicitly serialized by the
  builder.
- The default dev-mode behavior remains unchanged.
- Documentation includes examples for an in-memory repository and a persistent
  repository.

## 4. Add Typed Client Event and Form Decoding Helpers

### User Value

Interactive apps commonly read keyboard events, pointer coordinates, form
fields, checkboxes, selects, and validation errors. Spoonbill already exposes the
raw primitives; typed helpers would reduce repeated JSON parsing and make
renderer code safer.

### Current Friction

Applications often decode `access.eventData` manually, read individual element
properties one by one, and hand-roll validation display logic for forms.

### Proposed Shape

Start with small Spoonbill-owned decoder typeclasses and helpers. Keep the core
lightweight and avoid forcing Cats, ZIO, or any particular validation library
into Spoonbill core.

```scala
access.eventDataAs[KeyboardEvent]
access.form(formId).decode[ProfileForm]
access.checked(checkboxId)
access.valueAs[Int](durationInput)
```

For validation, provide a small pattern that maps decoded field errors back to
DOM updates without assuming a specific CSS framework:

```scala
access.form(formId).validate(ProfileForm.decoder).renderErrors(errorRenderer)
```

The first version should favor well-defined, testable helpers over exhaustive
browser coverage:

- common keyboard, input, pointer, and submit event fields
- text, number, checkbox, radio group, select, and optional field decoding
- documented missing-field and invalid-field behavior
- testkit support for supplying event data and form data
- examples for direct field reads and full-form validation

### Acceptance Criteria

- Users can decode common browser event data without manually parsing JSON.
- Form decoding works with text inputs, numbers, checkboxes, radio groups,
  selects, and optional fields.
- Missing, empty, and invalid values produce documented errors.
- The API does not force Cats, ZIO, or any particular validation library into
  Spoonbill core.
- Examples demonstrate both simple field reads and full-form validation.
- Testkit examples show how to exercise decoded event and form handlers.

## 5. Add Render-Safe Client Effects

### User Value

Event handlers often need to update state and then run a client-side effect such
as setting an input value, clearing an error message, focusing an element, or
running JavaScript. A framework-level sequencing primitive would make this
reliable across browsers and render timing.

### Current Friction

If an event handler transitions state and also mutates a DOM property, the target
element may be replaced by the render diff. Applications can work around this by
carefully ordering effects, forcing transitions, or delaying work, but this is
easy to get wrong.

`transitionForce` already waits for Spoonbill's render path, but it does not give
users a named post-render client-effect queue, and it does not by itself specify
whether the browser has applied the patch before a follow-up effect runs.

### Proposed Shape

Add a way to enqueue client effects after the DOM patch produced by the current
transition has been applied:

```scala
for {
  _ <- access.transition(_.copy(saved = true))
  _ <- access.afterRender {
    _.focus(nameInput) *>
    _.property(errorMessage).set("textContent", "")
  }
} yield ()
```

The API must define what "after render" means. There are two reasonable levels:

- after the server has computed and enqueued the DOM diff
- after the browser has acknowledged applying the DOM diff

The safer public promise is browser-applied ordering, even if that requires a
small client acknowledgment in the protocol. If Spoonbill also wants the lighter
server-enqueued behavior, it should use a distinct name.

Component-level hooks may be useful, but they should follow the same ordering
contract and should define what happens when the component is removed before the
hook runs.

### Acceptance Criteria

- The API specifies whether effects run after server enqueue or after browser
  patch application.
- If the public API promises browser-applied ordering, the protocol includes a
  deterministic acknowledgment path.
- Client effects scheduled through the API run once, in FIFO order for the
  session.
- Effects can safely target elements created or replaced by the current render.
- Removed elements are handled predictably, either as no-ops or reported
  failures.
- The API works for top-level renderers and nested components, or nested
  component support is explicitly deferred.
- The behavior is deterministic enough to test in `spoonbill-testkit`.
- Existing `transition`, `transitionForce`, `property`, `focus`, and `evalJs`
  APIs remain compatible.

## Suggested Implementation Order

1. Fix Pekko documentation and public-surface tests.
2. Expose state-manager builders around existing storage mechanics.
3. Add managed session resources with explicit cleanup semantics.
4. Design render-safe effects with a precise client/server ordering contract.
5. Add typed event and form helpers as an incremental developer-experience
   layer.
