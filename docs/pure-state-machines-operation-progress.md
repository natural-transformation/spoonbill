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

## Pure invocation decision table

`InvocationDecisionMachine` lives in the pure core next to
`OperationAuthorization`. It defines exact-intent and consume-on-attempt
requirements, inclusive expiry, prepared execution eligibility and terminal
versus unresolved settlement. Runtime adapters map their existing status/error
types to this internal table; public metadata and error types retain their
existing packages and shape.

Only decision expressions moved. Missing-record rejection still precedes exact
identity and status checks. Transaction entry, protected store reads, host work,
every post-work expiry observation, outcome writes and scope finalization retain
their order. Recovery keeps terminal positive or negative outcomes and never
turns a negative outcome into permission to execute. The existing absent-record
interpreter branch still records a negative fence directly.

Independent review found no changed effect ordering or decision. The repository
Nix/disposable-PostgreSQL wrapper ran:

```
core/test
spoonbill/testOnly *OneUse*Spec *OperationTransactionsSpec *AsyncOperationTransactionsSpec
securityJdbc/testOnly *JdbcOperationOutcomesSpec
zio2/testOnly *OperationTransactionsSpec
```

Results: 39 core, 38 runtime, 29 PostgreSQL and eight native ZIO 2 cases passed.
The native tests include interruption, scope cleanup and lazy-program replay
rejection. Logs are `/tmp/spoonbill-rollout-invocation-after.log` and
`/tmp/spoonbill-rollout-invocation-purity.log`; the pure-core/machine guard passes.
This completes the one-use lease and invocation-table inventory items; reference
records, view lifecycle and provider record-store work remain separate units.
