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
