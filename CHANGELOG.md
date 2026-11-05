# Changelog

## Unreleased

- Add inert render-owned `timer.after` and `timer.every` bindings for
  `StateUpdate`, with typed `TimerId`, structural `RestartKey`, validated
  `DelayDuration` and `Period`, and `SpoonbillServiceConfig.timerSettings`.
  Registration follows accepted live rendering. Duplicate, invalid, and excess
  timers are omitted with diagnostics; the default budget is 1024 retained
  identities, including completed one-shots whose bindings remain rendered.
- Fence component state access across removal and replacement, and drain
  admitted writes before reusing an address. Failed address cleanup remains
  fenced until the view ends; unrelated renders do not repeatedly initialize it.
- Restore support for the `delay` helper by removing its deprecation. Legacy
  callbacks keep render-driven repetition but construct and execute off the
  scheduler thread, with unexpected failures reported once.
- Continue legacy session cleanup after frontend/component teardown errors,
  including extension destruction and removal from the application registry.
  Guarded teardown retires component admission before draining storage writes.
  New timers wait for guarded readiness and extension state-stream registration.
- Legacy teardown now closes the frontend explicitly. In-flight handlers that
  try to send output during teardown can fail with an access denial, rather than
  having output silently dropped, and may produce event/transition error reports.
- Migrate dismissal and streaming examples to the timer API; keep timer bindings
  inert in static testkit rendering. Internal `ComponentInstance` construction
  and `ComponentEntry.createInstance` gained parameters: defaults preserve source
  compatibility, but consumers calling these directly must recompile. Binary
  compatibility for those signatures is not promised by this change.

## 8.1.0 — 2026-11-03

This release reduces guarded event latency and timer memory, and adds an
opt-in for concurrent authority checks with the JDBC provider. See the
[event latency report](docs/event-latency-performance.md) and the
[concurrent authorization design](docs/concurrent-authorization-design.md).

- A guarded click that changes state now makes 5 authority observations instead
  of 14: event admission, the transition's read, its write, the render's
  snapshot and the patch's release. Each boundary still observes current
  authority. A duplicate check that immediately followed another one with no
  application code in between is removed, and checks of two presentations at one
  boundary share one observation. With the JDBC provider this cuts PostgreSQL
  round trips per click from 243 to 47. A custom `SessionGuard` sees 7
  `authorize` calls, one per presentation checked.
- Single-owner (exclusive) output is checked once, immediately before transport
  handoff, for both the current and the produced presentation. This matches the
  shared-ownership output admission. A rendered frame's presentation is still
  authorized by the guarded snapshot it was rendered from.
- Guarded sessions without extensions no longer authorize every state change
  and published message only to invoke no callback.
- An exclusive `JdbcBrowserClock` commits at most one checkpoint per second
  instead of one per observation. Concurrent observations share one renewal; a
  caller that waits for it samples time again, and fails closed after five
  seconds. Returned times never exceed an acknowledged durable checkpoint; after
  a quick restart, time can run up to one second ahead. Shared clock writers
  still consult storage on every observation.
- The scheduler removes canceled timers at once. Previously every canceled
  bounded wait, idle timeout or RPC deadline kept its callback until its original
  due time, retaining thousands of timer objects per busy guarded session.
- `BrowserAuthentication.maintenance()` takes one short coordinator turn per
  owned view instead of one turn for the whole sweep. Clicks and other
  operations now interleave with a sweep instead of waiting for it. A sweep is
  no longer atomic with respect to other coordinator operations: a view closed
  during it is skipped, and a sweep that overlaps `close()` ends quietly.
- `JdbcBrowserPersistence` takes an optional
  `JdbcConcurrentChecks(clockSource, maxConcurrentChecks)` setting. With it,
  authority checks run concurrently: the coordinator runs them in read turns
  keyed by the browser binding, at most the cap at once plus one for
  maintenance, and the provider takes the realm advisory lock in shared mode
  for checks, so checks exclude only writers. Every writer keeps the exclusive
  lock, and every other coordinator operation stays exclusive. A check denied
  in a read turn is final at once, a check aborted by a database deadlock is
  retried once, checks have their own admission budget, and lease renewals
  never move a lease backward. The setting requires a reserved clock data
  source, refuses `SharedViews`, and runs the host's session resolution
  concurrently, so hosts must lock their rows in one global order. Without the
  setting nothing changes; the previous constructor is kept for binary
  compatibility.
- With that setting, a provider-owned daemon thread renews the exclusive clock
  lease ahead of its expiry while observations happen, so checks perform no
  clock I/O inside their transactions in the normal case. An idle provider
  commits nothing in the background. A lease that has expired anyway, because
  the renewal fell behind, is still renewed by the caller inside its own
  transaction as before, so bound the clock source's pool, statement, lock and
  socket timeouts. `close()` stops the thread.
- The browser client observes document mutations only while a sensitive region
  is shown, instead of recording every DOM patch.

## 8.0.1 — 2026-11-02

This patch release changes how guarded connections treat obsolete browser
events. Its only public API addition is one metric.

- Keep a guarded connection open when a valid browser sends a DOM event or
  custom callback for a view that a newer render has already replaced. This
  happens in ordinary use: an input or window resize can cross a server-pushed
  patch, or follow an earlier event whose handler rendered. The obsolete event
  is discarded without running an old handler or selecting a newer one; the
  next current event and heartbeats continue on the same connection.
  Previously the server closed the connection and the browser reconnected.
- Future or invalid revisions, revoked authority and replaced view ownership
  still deny and close. Browser history and programmatic transitions keep their
  strict check: an obsolete history change still reconnects, and the new
  connection reads the current URL.
- A discarded event is not replayed. A one-shot event, such as a `change` or a
  form submit, sent before the newer view arrived has no effect and must be
  repeated.
- Count discarded events in `Metrics.ObsoleteBrowserEvents` and log each one at
  debug level without event data.

## 8.0.0 — 2026-10-23

This is a major release with public Scala signature/binary compatibility changes
and a required PostgreSQL V6 cutover. Update every Spoonbill module together,
recompile, and follow the [v8 migration guide](docs/migration-v8.md).

- Bound static-resource cache entries, payload bytes and concurrent reads;
  validate directory/JAR containment and canonical asset names.
- Add guarded browser retention, anonymous-binding quotas, renewable owner
  leases and capacity warnings. Permanent issuance and generation fences keep
  retired security history from reopening old authority. Shared JDBC cadence
  bounds maintenance scans; local ownership reconciliation remains independent.
- Bound inbound WebSocket messages across standalone, Akka, Pekko and ZIO HTTP.
  The published `spoonbill-zio-http-runtime` derives from ZIO HTTP 3.11.6 with
  bounded pre-conversion ingress and uses ZIO 2.1.27. Consumers must exclude a
  second original `zio-http` runtime.
- Bound standalone HTTP headers, chunk metadata and body assembly time; reject
  ambiguous framing and stream chunk payload without whole-chunk accumulation.
  Preserve ordinary empty request bodies and the public WebSocket upgrade API.
- Bound client TCP/TLS setup and response headers; validate HTTPS/WSS hostnames
  and use TLS for WSS. Recover pool capacity after failed setup, parsing, writes
  or cancellation. Correct bodyless and informational responses, and deliver
  final response EOF independently of unfinished request uploads.
- Keep deadline-triggered physical socket closure independent of application
  finalizers, reserve finite cleanup capacity, isolate repeated lazy server
  starts, and avoid application-error traces for expected malformed input.
- Harden release source selection and require the configured GitHub release
  protection boundary before accessing signing/publishing credentials.

PostgreSQL deployments must drain all older writers before applying V6 and
starting retention-aware providers. Schedule `browser.maintenance()` for every
live coordinator; preserve issuance keys/counters, fences and the durable clock.
Older binaries cannot safely resume a reclaimed scope.

See the [release notes](https://github.com/natural-transformation/spoonbill/releases/tag/v8.0.0)
and [consolidated review and validation record](docs/pr-17-review-resolution.md).

## 7.0.0 — 2026-10-18

This is a source and binary compatibility break. Update all Spoonbill modules
together and recompile using the [v7 migration guide](docs/migration-v7.md).

- Added explicit `StateUpdate.NoUpdate()` and `StateUpdate.Modify(reduce)`
  outcomes, with `UiOutcome.noUpdate` and `UiOutcome.modify` helpers for ordinary
  action results. Pure `andThen` composition and `zoom` preserve the distinction
  without evaluating reducers or lenses during construction.
- Sensitive completion and form rejection callbacks returning `NoUpdate` now
  finish without requesting a transition, render, handler replacement, or guarded
  patch. A subsequent action from the same delivered view remains admissible
  when authority and ownership are unchanged. Genuine later publications still
  invalidate older revisions; `Modify(identity)` still requests publication.
- Both `ActionForms.onSubmit` rejection callbacks now return `StateUpdate[S]`.
  Removed `UiOutcome.Updated`, `UiOutcome.update`, the old `unchanged` helpers,
  and the function-like `StateUpdate` construction and evaluation APIs.
- Completion and rejection callbacks execute through `Effect.delay`. Ordinary
  thrown ZIO callback exceptions now enter the typed failure channel instead of
  becoming defects; queued reducer execution and failure reporting are unchanged.
- Added typed-form, guarded-runtime, IO/ZIO, and real-service Chromium/WebKit
  coverage. Browser fixture cleanup starts adapter shutdown before releasing
  response resources, avoiding a reentrant cleanup wait.

See [release notes](https://github.com/natural-transformation/spoonbill/releases/tag/v7.0.0)
and [validation results](docs/sensitive-acknowledgement-implementation-validation.md).

## 6.0.0 — 2026-10-17

- Made guarded presentation initialization explicit. Custom `SessionGuard`
  implementations must supply `applicationStateInitialization`, selecting a
  retained baseline or a reconstructed baseline with a deferred acknowledgement.
  The ordinary `BrowserAuthentication` coordinator supplies this contract.
- Fixed anonymous peer attachment in clustered deployments: HTTP rendering on
  one node followed by the first WebSocket on another preserves the requested
  public route and continuation without requiring an authenticated snapshot.
  Failed initialization discards unfinished presentation; retained reconnects
  preserve existing state.
- Fixed chained JDBC replacement recovery by excluding revoked historical
  successors when finding the current committed replacement after response loss.
- Replaced independent route strings and flags in the browser-authentication
  example with `PageRoute` and an opaque `PublicContinuation`.

This is a breaking release for custom guard implementations. See the
[initialization contract](docs/clustered-browser-authentication.md#custom-session-guard-initialization)
and [release notes](https://github.com/natural-transformation/spoonbill/releases/tag/v6.0.0).
No additional database schema migration is required when upgrading from 5.1.0;
earlier installations still need their documented migrations.

## 5.1.0 — 2026-10-17

- Added explicit authenticated session replacement for factor enrollment,
  replacement, disabling, and recovery-code regeneration. Host proof use,
  predecessor revocation, successor creation, audit, and browser reservation
  settle together. The old session loses ordinary application authority.
- Added bounded recovery after lost completion or delivery. A valid successor
  cookie can activate on a fresh socket after the final delivery budget is used,
  without replaying the security change. The memory and JDBC providers support
  the new operation; JDBC supports recovery on another compatible node.
- Added verified successor metadata and one-use encrypted recovery results.
  Consumption is at most once; response loss does not permit redisclosure.
- Authenticated-action calls to ordinary `begin`, `resume`, and `recover` are
  deprecated in favor of the replacement API. Ordinary sign-in and its provider
  SPI remain supported.

Upgrade all Spoonbill modules together and refresh browser pages onto the new
client bundle. Drain older serving binaries, apply the explicit V5 PostgreSQL
migration, and enable replacement only after compatible nodes are running.
Previously applied V4 migration bytes remain unchanged. Mixed-version
replacement serving is unsupported. See the
[API and review guide](docs/authenticated-session-replacement-review-guide.md)
and [deployment procedure](docs/browser-auth-v4-deployment.md).

## 5.0.2 — 2026-10-13

- Fixed authentication reconnect rendering in memory and JDBC exclusive mode:
  when the server rebuilds a presentation after an authority change, the browser
  receives a complete authorized DOM baseline before incremental patches and
  user-event readiness. An invalid-code retry can no longer leave old setup
  controls mixed into the recovery-code screen.
- The reset requirement stays with the reconstructed presentation through failed
  setup and later reconnects. Same-identity reconnects retain authorized server
  state and component nodes; ordinary HTTP bootstraps keep their existing path.
- Regression coverage includes credential delivery, logout, presentation expiry,
  JDBC coordinator restart, failed claim admission, and invalid-then-valid browser
  authentication in Chromium and WebKit.

No public API migration is required. Consumers using a forced-page-reload
workaround for this rendering issue can remove it after validating this release.

## 5.0.1 — 2026-10-13

- `spoonbill-zio-http` now uses ZIO HTTP **3.11.6** (from 3.11.4). ZIO 2 core,
  streams and test libraries stay at **2.1.26**; the legacy `spoonbill-zio` and
  `spoonbill-zio-streams` adapters stay on ZIO **1.0.18**.
- Fixed: ordinary HTTP responses with a known length of zero (for example the
  204/403/303 authentication completion, rejection and logout responses) no
  longer stall the next request on a kept-alive ZIO HTTP connection. ZIO HTTP
  3.11.4 and 3.11.6 never complete a streamed body declared as zero bytes; the
  adapter now sends `Body.empty` and cancels the unused response stream, so
  resource-owning streams are released. Status, headers, chunked and nonempty
  responses, and WebSocket upgrades are unchanged.
- The ZIO HTTP example now depends on the ZIO 2 adapter instead of the legacy
  ZIO 1 adapter.

Consumers that added their own zero-length `Body.empty` middleware as a
workaround can remove it after validating against this release.

## 5.0.0 — 2026-10-11

This is a breaking release. Update all modules together and recompile consumers
using the [v5 migration guide](docs/migration-v5.md).

- Opt-in shared PostgreSQL browser authentication across overlapping web nodes,
  with durable view registration, fenced takeover and protected output admission.
- `JdbcBrowserPersistence.projectedSnapshots` restores explicitly selected
  non-sensitive presentation after reconnect or process replacement.
- Snapshot schema incompatibility preserves stored drafts; `SnapshotFormat.reading`
  supports explicit pure compatibility conversions.
- A two-process browser fixture exercises rolling handoff, process loss and
  recovery without replaying committed business actions.
- `BrowserOwnership.ExclusiveScope` / `SharedViews` replaces Boolean ownership
  configuration and capability flags.
- Ordinary output validates its producer/current presentation and owner in one
  database admission, preserving fresh checks while avoiding duplicate work.
- Bounded administrative usage, owner discovery and authorized conditional
  retirement release active capacity while preserving snapshots and fences.
- Failed snapshot initialization cannot commit; setup failures produce a
  redacted diagnostic without changing refusal or cleanup behavior.

See the [shared-store integration guide](docs/clustered-browser-authentication.md).
The initial move from 4.0.0's exclusive mode requires draining old writers.
Performance acceptance remains unmeasured for the new shared mode.

## 4.0.0 — 2026-10-11

Browser authentication is coordinated through a Scala API with generic memory
and PostgreSQL providers. Upgrade modules together and recompile using the
[v4 migration guide](docs/migration-v4.md).

### Added

- `spoonbill-core` for immutable security decisions and browser ceremony models.
- `BrowserAuthentication` for coherent HTTP/socket/action authorization,
  completion delivery, continuation recovery and bounded presentation ownership.
- Provider-owned live ceremonies, commit-gated receipts, and instance-dependent
  host transaction handles with scope and thread checks.
- Atomic in-memory host/browser publication and a generic JDBC provider with
  explicit host hooks, encrypted credential custody, durable recovery and
  restart-safe expiry checkpoints.
- Shared provider conformance tests and an effect-polymorphic Scala example:
  native IO/Future memory authentication and Future PostgreSQL authentication.

### Changed

- Browser security configuration is installed and validated as one bundle;
  partial replacement is rejected.
- The Pekko adapter uses native Scala 3 dependencies.
- Browser protocol tables are generated from Scala declarations, replacing
  independently maintained client constants and operand widths.
- Cached state managers support direct node reads and immutable snapshots.

### Fixed

- Wake another waiting producer after direct queue delivery so free capacity
  cannot leave a bounded queue stalled.

See the [validation report](docs/browser-auth-v4-validation.md) for correctness
and bundle-size results. Comprehensive performance profiling remains deferred.

## 3.0.0

This release makes WebSocket response lifetime explicit. Recompile all integrations
and follow the [migration guide](docs/migration-3.0.md).

### Changed

- Replace the HTTP-shaped WebSocket result with `Duplex` and `SendThenClose`.
  Adapters choose connection behavior from the public result and execute its
  idempotent resource finalizer on termination or abandoned setup.
- Preserve output disposition and resource ownership through `mapOutput` and
  `withOutput`.
- Add bounded WebSocket setup ownership in the HTTP integrations and cancellation
  masking for effect-safe cleanup.

### Fixed

- Deliver the reload frame for guarded missing local views before transport close.
- Release pending output and acquired resources on input termination, failed
  writes, peer Close, and abandoned upgrades.
- Preserve lazy-effect execution context and complete shared cleanup when its
  first caller is interrupted.

## 2.0.0 — 2026-10-09

Spoonbill 2.0 adds typed actions, guarded browser sessions, protected presentation
and database-independent operation transactions. This is a major release;
recompile integrations and follow the [migration guide](docs/migration-2.0.md).

### Added

- Typed action inputs captured in one WebSocket submission, with explicit
  authority and invocation binding.
- Guarded browser sessions, origin-checked authentication completion, logout
  handling and exclusive view ownership.
- Typed, non-sensitive presentation snapshots with schema and ownership checks.
- Transient sensitive regions with separate authorization, bounded lifetimes and
  clearing barriers; sensitive output is excluded from ordinary snapshots.
- Transaction executor and store interfaces separating the application effect,
  native transaction program and database capability. Both direct and native
  asynchronous transaction programs are supported.
- Durable operation grants and issuer-bound one-use authority, with commit-gated
  results, replay fences and status reconciliation.
- The optional `spoonbill-security-jdbc` module for PostgreSQL browser sessions,
  snapshots and operation stores, and ZIO2 transaction-program integration.

### Changed

- Public access interfaces and service configuration constructors changed.
- WebSocket compression now requires explicit opt-in. Guarded sessions require
  WebSockets without compression and do not use legacy HTTP bridge transports.
- Custom state-store removal must tolerate retries. Typed snapshot persistence
  is separate from legacy state serialization.

### Fixed

- Stream-subscriber cancellation and terminal-signal races in HTTP integrations.
- Stale-view, asynchronous admission and canceled-departure handling for guarded
  actions and transient sensitive output.
- Transaction cleanup after uncertain commits or rollbacks; unresolved JDBC
  connections are aborted. Failed aborts require host pool eviction or
  administrative recovery before reuse.
