# Changelog

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
