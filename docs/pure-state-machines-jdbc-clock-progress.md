# JDBC clock and managed construction

## Characterization before extraction

`JdbcBrowserClockSpec` now pins the exact half-lease renewal boundary and the
consumption of demand even when renewal is not yet due. A second case proves
that failed background renewal neither acknowledges a new lease nor invalidates
the previous durable checkpoint. Both passed on the original implementation.
The existing suite covers restart floors, concurrent renewal, failed-commit
acknowledgment, resampling after a wait and shared-writer storage consultation.

The unchanged provider suite also pins managed versus low-level creation,
enrollment fencing, reclamation and authorization ordering. Validation used a
fresh disposable loopback PostgreSQL instance through the repository flake:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'securityJdbc/testOnly *JdbcBrowserClockSpec *JdbcBrowserPersistenceSpec'
```

Result: 110 tests passed (14 clock, 96 provider), with none canceled. The combined
run is recorded at `/tmp/spoonbill-rollout-integration-8.log`; its pool selections
also passed (25 effect, five IO and seven HTTP-client tests).

## Immutable managed construction

`JdbcBrowserSessions` receives its managed-lifecycle setting at construction.
The provider selects managed mode through a package-restricted constructor;
public low-level construction still performs enrollment checks. The public
six-argument constructor and its default active-view limit remain available,
including their JVM constructor/default-argument signatures. No mutable setter
or post-construction publication is needed.

Compile checks outside the JDBC package accept both prior constructor forms and
reject the internal managed form. The original low-level/enrollment cases still
pass against PostgreSQL, along with the full session and provider suites. The
combined post-change selection passed 146 tests with none canceled:

```
securityJdbc/testOnly *JdbcClockMachineSpec *JdbcBrowserClockSpec *JdbcBrowserSessionsConstructionSpec *JdbcBrowserPersistenceSpec *JdbcBrowserSessionsSpec
```

This ran with the same Nix/disposable-PostgreSQL wrapper above; the log is
`/tmp/spoonbill-rollout-jdbc-clock-after.log`. The same run's broader
`http/testOnly *HttpClient*Spec` selection passed 56 tests. `javap -public` through
the Nix JDK confirmed the existing six-argument constructor and default-argument
signature. Package access is enforced by Scala, as it was for the old setter.

## Pure clock decisions with preserved publication boundaries

`JdbcClockMachine` owns observation monotonicity, acknowledged-checkpoint
advancement and coverage, the inclusive renew-ahead boundary, external checkpoint
advancement and the failure fence for a caller that waited. Time values and the
renewal window are explicit inputs. Model tests cover a backward-clock trace,
unacknowledged expiry after failure, future restart floors, boundary equality and
signed monotonic-counter wrap.

The interpreter still owns two separate monotone frontiers: sampling can advance
the observation before persistence fails, while only acknowledged durable state
authorizes reuse. Combining them into a fresh aggregate on every observation
would change publication boundaries and add a hot-path allocation. The current
atomic references, renewal lock, demand signal and lock-confined failure stamp
therefore remain; only their pure decision rules moved. Every clock read, SQL
transaction, lock operation, failure update and atomic publication stays in its
original order. The existing failure/uncertain-commit and concurrency tests pass
unchanged.

The 146-test JDBC and 56-test HTTP selections above passed after extraction. The
Nix `node scripts/check-core-purity.mjs` check and all five
`node --test misc/core-purity-tests.mjs` cases passed, recorded in
`/tmp/spoonbill-rollout-jdbc-purity.log`. This closes the clock inventory item and
managed-construction cleanup; the other phase-6 items remain separate work.
