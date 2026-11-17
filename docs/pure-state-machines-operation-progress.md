# One-use authority and invocation decisions

## Original-code characterization

The operation suites already exercise commit acknowledgment, exact-intent
conflicts, replay, rollback, scope confinement, negative recovery fences and
expiry checks after joined asynchronous work. Two additional one-use cases pin
concurrent redemption of aliases and permanent observed expiry, including its
precedence over used-evidence/used-permit errors after a backward clock step.

Before production changes, all 36 cases in these three suites passed:

```
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *OneUseAuthoritySpec *OperationTransactionsSpec *AsyncOperationTransactionsSpec'
```

The log is `/tmp/spoonbill-rollout-operations-before.log`. This is a prerequisite
for the corresponding extraction, not completion of the remaining phase-6 work.

## Canonical one-use lease

The canonical receipt and its issued authority now share one atomic
`Fresh / Issued / Admitted / Expired` lease state. `OneUseLeaseMachine` defines
issuance, admission and terminal expiry. The capability objects remain live,
nonserializable aliases with the same restricted constructors and redacted
metadata. No identity registry, durable grant or recovery-based reissuance was
introduced.

Production construction was checked independently: only allocation creates a
lease, only the evidence factories create receipts, and only successful issuance
creates an authority. Thus no public history admits a fresh unissued lease.
The scope monitor and foreign-issuer, closed-scope, sticky-expiry, used-capability
check order remain unchanged. The expiry clock is read at the same points;
admission still consumes authority before transaction dispatch.

The same Nix command selecting `*OneUse*Spec`, `*OperationTransactionsSpec` and
`*AsyncOperationTransactionsSpec` passed 38 tests after extraction. Through the
disposable PostgreSQL wrapper, `securityJdbc/testOnly *JdbcOperationOutcomesSpec`
passed all 29 durable-outcome cases. Logs are
`/tmp/spoonbill-rollout-one-use-after.log` and
`/tmp/spoonbill-rollout-one-use-purity.log`; the machine-purity check passes.
