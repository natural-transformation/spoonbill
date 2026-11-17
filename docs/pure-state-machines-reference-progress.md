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
