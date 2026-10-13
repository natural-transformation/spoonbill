# Changelog

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
