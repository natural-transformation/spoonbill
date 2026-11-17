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
