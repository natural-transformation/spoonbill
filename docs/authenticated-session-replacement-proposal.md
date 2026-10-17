# Authenticated session replacement through the browser coordinator

Status: explicit replacement implemented and ready for review.
Correctness validation and the latest user-authorized performance
repeat passed. An earlier memory ordinary-sign-in p95 breach did not reproduce
with identical source and settings; its evidence and causal limits remain
recorded. See the
[implementation plan and validation record](authenticated-session-replacement-plan.md).

## Required behavior

A host must be able to change authentication factors or recovery credentials,
revoke the preceding host session, and create its replacement in one transaction
owned by the browser provider. The initiating browser must then receive the
replacement credential, including after an uncertain commit or process restart.
The old session must never regain ordinary application authority.

This is needed for authenticated enrollment, factor replacement, disabling a
factor, and recovery-code regeneration. It is different from anonymous sign-in.

## Original reproduction

The original regression in
`modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcBrowserPersistenceSpec.scala`
uses only synthetic host rows and the public provider API:

1. Prepare, deliver, and activate a normal login.
2. Claim the authenticated view and begin a replacement ceremony.
3. In `prepare`'s host callback, disable the preceding host session and insert
   its replacement on the supplied connection.
4. Return `HostSessionPrepared` for the replacement.

Run with the repository's Nix environment and disposable PostgreSQL:

```sh
nix develop --command bash scripts/with-test-postgres.sh sbt \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec -- -z "replace an authenticated host session atomically"'
```

Observed: one test ran and failed at the desired successful-preparation
assertion. The result was `Left(Rejected(HostDenied))`; the preceding session
remained authorized and the host table still contained one session. Compilation
and PostgreSQL setup succeeded. The fixture shut its database down normally.

The test ran against the local 5.0.2 source. Inspection of the exact `v5.0.0`
tag confirms the same relevant preparation and recovery sequence. The local
implementation now retains this behavior as a negative ordinary-preparation
test and adds an explicit-replacement success test; the reproduction above is
historical evidence, not the current result of the positive regression.

## Cause and scope

`JdbcBrowserPersistence.prepare` invokes the host callback, resolves its new
session, then calls `current(connection, originalView)`. A legitimate host
callback that revokes its predecessor makes that final authority check fail.
The provider correctly rolls the transaction back; this is an unsupported
transition, not evidence that revoked sessions can access protected output.

Changing that one check is insufficient. `recover` also requires the preceding
view to remain current, and ordinary coordinator/action output authorization
still revalidates the old authority. A patch that commits the replacement but
cannot deliver or recover its credential would create a different failure.

## Proposed boundary

Add an explicit authenticated-replacement operation to the portable browser
authentication protocol. Keep anonymous sign-in's existing contract intact.
The exact API should follow the existing provider-owned capabilities and
instance-dependent transaction types, rather than expose an unchecked flag.

The operation must:

- Authorize the predecessor, ceremony purpose, host proof, browser generation,
  and physical owner at the correct transaction boundaries.
- Commit the host mutation, old-session revocation, new host session, audit,
  browser preparation, and durable recovery outcome together.
- Recheck the browser generation and owner without requiring the deliberately
  revoked predecessor to become an ordinary current host session again.
- Return a provider-issued, bounded handoff that permits only replacement
  credential delivery. It must not authorize arbitrary UI updates, business
  actions, subscriptions, or sensitive output under the preceding identity.
- Recover that same outcome after uncertain settlement and process loss,
  without rerunning the host mutation or consuming another factor/recovery code.
- Distinguish the provider attempt/completion ID from the host completion ID.
  If applications need post-transition metadata, provide a verified host lookup
  boundary; never require applications to equate these separate identifiers.
- Define how one-shot sensitive results are disclosed to the freshly activated
  successor. Plaintext recovery codes must not enter ordinary state, snapshots,
  logs, reusable callbacks, or retained completed outcomes.

Implement the contract for both the portable in-memory provider and the optional
JDBC module. JDBC details must not leak into the coordinator's public API.

## Acceptance tests

- The new regression passes, and every host/browser write rolls back on failure.
- Anonymous login and authenticated replacement retain distinct authorization.
- Wrong browser, wrong purpose, stale owner, changed generation, expired proof,
  and unrelated concurrent revocation are rejected without host effects.
- Credential delivery succeeds after the intended revocation, while ordinary
  old-view actions, frames, snapshots, and subscriptions remain fenced.
- Lost commit acknowledgment, lost delivery, restart, cross-node recovery, and
  repeated recovery do not duplicate host effects or disclose another attempt.
- Recovery-code disclosure is single-use and bound to the successor's current
  authority; stale or foreign views cannot consume it.
- Existing output, ownership, logout, retention, and snapshot conformance tests
  continue to pass. Measure transaction count, latency, and retained memory for
  the changed path without weakening its checks to improve timings.

Do not work around the limitation by keeping revoked sessions valid, caching a
credential in application state, fabricating an anonymous view, bypassing the
coordinator, or committing the host mutation independently.
