# v3 in-process host baseline

`MemoryBrowserAuth.scala` is counted consumer integration code using the current
3.0 API. It is compiled with the `spoonbill` test configuration so it cannot be
mistaken for a new framework API. Its accompanying test suite is
`spoonbill.browserauthbaseline.MemoryBrowserAuthSpec`.

The fixture exposes capture (`begin`), password and challenge handlers, delivery,
activation, reconnect authorization, a protected page boundary, a protected
one-use mutation, logout and status/delivery recovery. `completionConfig` supplies
the existing v3 HTTP completion callbacks. `MemoryBrowserSecurity.scala` supplies
`SessionAccessControl`, `SessionAuthority`, connection-bound action ports and
bounded ownership-fenced `StateStorage` using the same host monitor. Both are
counted integration code. They introduce no JavaScript. The sibling application
owns rendered UI/official transport wiring and native browser evidence; backend
tests alone do not close the complete Phase 0 gate.

## Atomic domain and effects

One process monitor serializes authoritative operations. The existing host
`TransactionExecutor[F, Direct, Tx]` snapshots and atomically publishes the
accounts, sessions, browser slots, completion material, audit and operation
outcomes. Any thrown rejection discards the staged state. Acknowledgement loss
publishes exactly once and returns `CommitUnknown`. No retry loop, external
business system or second browser transaction participates. The public executor
exists to show/count the host runner and its transaction boundary; raw transaction
access is trusted integration code. Handles check their active scope and entering
thread. Nested transactions are rejected, and re-evaluating one lazy runner
result cannot replay its callback. Closing the host clears authority and delivery
records, closes its issuer and invalidates presentation. Subsequent authorization
and admission fail closed.

Login's original ceremony ID is also its completion/recovery identity. Its
one-attempt admission bit survives rollback. Host sessions, completion material,
the committed outcome and audit are staged together. Login never enters the
prepare/execute operation protocol or adds a second operation ledger. Protected
business mutations use the existing v3 `OneUseOperationProtocol`; their outcome
and audit share the same staged state. Reconciliation takes the same monitor,
which excludes every earlier writer before publishing a negative fence.

`F` is generic over the existing `Effect` interface, with synchronous `Direct`
transaction work. Tests exercise eager Future behavior. This fixture does not
claim evidence for native asynchronous transaction programs; existing native
transaction conformance and a full lazy-effect application remain separate
validation targets. Production integration never blocks on `Await`; the bounded
wait helper exists only in tests.

Password hashing uses a fixed SHA-256 synthetic workload outside the transaction.
It is intentionally unsuitable as a real password-storage algorithm. Account
version/enabled policy is rechecked inside preparation. The challenge captures
subject, version, challenge identity and original expiry; another subject or
ceremony cannot transplant it. Password and factor submissions share eight
attempts per binding per 60-second window, checked before proof computation.
Invalid factors/challenges do not claim the ceremony and can retry within that
bound. Successful factor verification admits one preparation attempt; its claim
survives final transaction rollback, and duplicate correct submissions are denied.
Server time retains the greatest observed
instant so clock reversal cannot revive observed expiry. The final factor check
is the constant synthetic factor workload, checked before admission and rechecked
inside the transaction against current account policy and the captured challenge.

All registries and retained negative fences fail closed at the supplied capacity;
retained ceremonies also have a configurable per-binding limit, default 16.
Challenge, completion and expiry do not reset this quota or remove replay fences.
there is no eviction of security history. Delivery is limited to three attempts,
expires with the original 120-second ceremony, and is erased upon activation,
logout, expiry, policy/generation revocation or exhausted redelivery. Host reads
and committed transitions retire unusable plaintext; the application also owns
one nonoverlapping periodic maintenance operation. Its bounded scan preserves
all security-history records. Sessions expire after 3,600 seconds.
Counts expose transactions, commits, rollbacks, proof computations, host
mutations, audits, sessions, completions and retained delivery materials; these are logical in-process counts,
not SQL statements or wire round trips.

The default token issuer uses 32 secure random bytes encoded as 43 base64url
characters. Authority records keep a digest; bounded delivery material stays in
process memory only while eligible for bounded delivery. Tests inject deterministic credentials
and IDs. Never use those injected test credentials in a deployment. No fixture
method logs credentials or places them in ordinary UI values. Sensitive
presentation/disclosure is not enabled by this backend fixture.

Restart constructs a new empty process-local store and requires fresh login.
Recovery on the old process can redeliver only an already committed completion
with current binding, policy, generation and expiry checks. Recovery on a new
process cannot recreate old authority. Delivery lookup IDs alone grant nothing.

## Presentation ownership

The presentation registry shares the exact host monitor. Acquisition, manager
publication, reads, writes, snapshot access and release validate current host
identity and the same opaque owner lease while holding it. Snapshot values stay
immutable and isolated, and retained snapshots still check their lease when read.
Authority changes synchronously detach old managers and replace retained state
with fresh public bootstrap. A new owner cannot expose old protected presentation.
These replacement entries require a full authorized HTTP reload. After the new
guard activates/captures its identity, `StateStorage.exists` reports the local
DOM baseline unavailable; the existing v3 session runtime releases the guard and
sends its terminal reload command. The subsequent HTTP bootstrap preserves its
exact rendered initial state. This extra HTTP exchange is baseline work that
must be counted; replacement state must never be used to reconstruct an old DOM.
Same-identity reconnect preserves its original DOM baseline and retained nodes.
An old guard's close and the ownerless v3 `StateStorage.remove` callback cannot
delete a successor. Unknown views return the existing missing-view reload path.

Defaults allow 64 active views, at most 16 per binding, 128 disconnected views, 128 bootstraps and 256
nodes per view. Bootstrap TTL is 30 seconds; reconnect TTL is 120 seconds. Access
and authority-change sweeps remove expired disposable entries. Active exhaustion
denies acquisition; disconnected exhaustion discards the view being released.
Acquisition performs no asynchronous resource allocation before its final atomic
publication, and allocates no pending lease/waiter registry. Transport setup
limits still apply to requests awaiting service acquisition.

State-loader/bootstrap values and node values must be immutable, non-sensitive
presentation. The generic v3 `StateStorage.create` signature contains no request
identity, so authority is captured independently at handshake, never inferred
from stored UI state. Sensitive disclosure and persistent snapshots remain
default-deny. Node reads use direct map lookup; explicit snapshots capture the
immutable node map. This is counted host baseline glue, not a framework change.

## Reproduce

From the repository root:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly spoonbill.browserauthbaseline.MemoryBrowserAuthSpec spoonbill.browserauthbaseline.MemoryBrowserSecuritySpec'
```

The fixture needs no database, production credentials or network service. The
parent Phase 0 manifest/inventory owns immutable source hashes, measurement
parameters and remaining browser/performance gates.
