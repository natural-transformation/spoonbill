# Snapshot reference model rollout

## Confirmed read-pin ownership defect

Before the model extraction, a public-behavior regression reproduced a successor
view removing its predecessor's live read pin. `finishRead` rejected the foreign
owner but had already removed the pin, allowing garbage collection before the
original reader released it. The original memory implementation failed with zero
open pins where one was required (`/tmp/spoonbill-reference-pin-before.log`).

The memory implementation now removes only a pin whose owner and epoch match
the bound reader. This matches the existing PostgreSQL DELETE predicate. The
new test runs both implementations through takeover, attempted foreign finish,
deferred garbage collection and the original reader's eventual release. No
reference-state refactor is included in this defect fix.

Validation through the repository Nix flake and its fresh disposable PostgreSQL
wrapper passed 51 memory/testkit tests and 39 PostgreSQL tests. Four memory and
two PostgreSQL cases retain their existing cancellations for cleanup guarantees
an unproven adapter intentionally cannot offer. Exact selections:

```
recoveryTestkit/test
securityJdbc/testOnly *JdbcSnapshotReferencesSpec *JdbcFencedRecoveryLawsSpec *JdbcUnprovenRecoveryLawsSpec
```

The command used `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop
--no-write-lock-file --command bash scripts/with-test-postgres.sh sbt --batch
--supershell=false -Dsbt.server.autostart=false`; the log is
`/tmp/spoonbill-reference-pin-after.log`. Model extraction remains the next unit.

## Pure reference state and cleanup ownership

`SnapshotReferenceMachine` now holds records, views and pins in one immutable
state. A retired reference owns an explicit cleanup state: pending (with attempts
and retry eligibility), claimed (with claim identity/generation/lease), or deleted
(with the completed claim retained for replay). Staged and published references
cannot carry garbage claims or deletion flags. Pure transitions cover reservation,
publication plus predecessor retirement, read-pin ownership, reclamation,
reservation expiry, claim selection, retry and completion.

The interpreter retains the provider's monitor/fencing entry points, authority
checks, schema-policy callbacks and UUID generation. Every time sample and claim
identity is an explicit model input; no payload bytes or effect closures enter
the model. Its generic identity parameter carries the same immutable metadata
that was stored previously. Schema-policy callbacks retain their documented pure,
bounded contract and still run before admission or pin construction.

Two formerly redundant fields are deliberately unrepresentable: a claimed
reference no longer carries its elapsed retry deadline (claim expiry is strictly
later), and a deleted reference does not retain attempt counts that no public
observation uses. Retry still clears the prior claim, so the next generation
starts at one with a fresh UUID, exactly as before. Pin metadata retention after
expiry and permanent publication history are unchanged; this unit adds no new
eviction policy. Repeated retirement is inert in the pure model; interpreter
retirement paths already select only staged/published records.

Independent review found no changed observable decision or effect ordering.
Five model tests cover atomic supersession, pin protection, expiry equality,
stale claim rejection, completed-claim replay and retry backoff. All 51 memory
and 39 PostgreSQL tests above also passed unchanged, with the same six existing
cleanup-basis cancellations. The log is `/tmp/spoonbill-reference-model-after.log`.
The Nix machine-purity guard passed; the two new files were formatted with exact
module-relative `scalafmtOnly` paths after this plugin rejected a filename glob.
The logs are `/tmp/spoonbill-reference-model-purity.log` and
`/tmp/spoonbill-reference-model-format-exact.log`.
