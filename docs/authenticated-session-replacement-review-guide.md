# Authenticated session replacement: review guide

This change lets an authenticated host operation revoke its predecessor and
prepare one successor atomically, then deliver or recover that successor without
restoring the predecessor's ordinary authority. Ordinary sign-in keeps its
existing contract. Both memory and JDBC providers implement the extension.

The review diff contains 71 feature and review files: sources, tests, generated
migration artifacts, fixtures, documentation and portable evidence. Unrelated
design/bug documents, `AGENTS.md` and `HANDOFF.md` are excluded. Start with
the PR's file list and use the reading order below to inspect the changes.

## Reading order

1. Read the [outcome and invariants](authenticated-session-replacement-plan.md#3-required-invariants)
   and [application-facing contract](browser-authentication.md#authenticated-session-replacement).
   The [proposal](authenticated-session-replacement-proposal.md) explains why
   weakening ordinary preparation's final predecessor check is insufficient.
2. Review the pure transition rules in
   [BrowserReplacementModels.scala](../modules/core/src/main/scala/spoonbill/auth/BrowserReplacementModels.scala):
   `BrowserReplacementAttempt.prepare`, `BrowserReplacementSlot.reserve` and
   `activate`, and `BrowserReplacementReservation.recover`.
   The [model spec](../modules/core/src/test/scala/spoonbill/auth/BrowserReplacementModelsSpec.scala)
   exercises exact captured facts, successor validity and terminal fences.
3. Review [BrowserReplacement.scala](../modules/spoonbill/src/main/scala/korolev/auth/BrowserReplacement.scala),
   [BrowserPersistence.scala](../modules/spoonbill/src/main/scala/korolev/auth/BrowserPersistence.scala),
   and [BrowserReplacementPreparation.scala](../modules/spoonbill/src/main/scala/korolev/auth/BrowserReplacementPreparation.scala).
   Follow `BrowserReplacementSupport`, `ReplacementBrowserPersistence`,
   `BrowserCapabilityScope` and the settlement interpreter before provider SQL.
4. Compare the providers:
   [InMemoryBrowserPersistence.scala](../modules/spoonbill/src/main/scala/korolev/auth/InMemoryBrowserPersistence.scala)
   and [JdbcBrowserPersistence.scala](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserPersistence.scala).
   Trace `prepareReplacement`, `admitReplacementCompletion`,
   `recoverReplacement`, `deliverReplacement` and `withSuccessorCompletion`.
   Then inspect activation in
   [JdbcBrowserSessions.scala](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserSessions.scala).
5. Follow the runtime boundary through
   [BrowserAuthentication.scala](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala),
   [Action.scala](../modules/spoonbill/src/main/scala/korolev/action/Action.scala),
   [ActionForms.scala](../modules/spoonbill/src/main/scala/korolev/ActionForms.scala),
   and [Frontend.scala](../modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala).
   Distinguish `CommitReplacement`'s fixed-frame admission from ordinary output
   and `RecoverReplacement`'s uncertain-outcome handling.
6. Trace HTTP recovery and held-cookie activation in
   [AuthenticationCompletionService.scala](../modules/spoonbill/src/main/scala/korolev/server/internal/services/AuthenticationCompletionService.scala),
   then asynchronous close/navigation handling in
   [connection.js](../modules/spoonbill/src/main/es6/connection.js) and
   [bridge.js](../modules/spoonbill/src/main/es6/bridge.js).
7. Review the host-owned result example in
   [RecoveryResults.scala](../examples/browser-auth/src/main/scala/example/browserauth/RecoveryResults.scala),
   [MemoryAccounts.scala](../examples/browser-auth/src/main/scala/example/browserauth/MemoryAccounts.scala),
   [JdbcAccounts.scala](../examples/browser-auth/src/main/scala/example/browserauth/JdbcAccounts.scala),
   and [Model.scala](../examples/browser-auth/src/main/scala/example/browserauth/Model.scala).
   Finally read the migration and evidence sections below.

## API and functional-programming decisions

- Pure immutable case classes describe observed facts and transition decisions;
  they are not authorization capabilities. `Either` expresses refusals and enums
  distinguish protocol outcomes. A committed reservation's recovery method
  returns availability directly; transport recovery still needs `NotCommitted`.
  Preparation checks captured facts and the successor. Fresh predecessor
  authority and current ownership are verified by the providers in their actual
  transactions, not by comparing the captured value with itself in the model.
- Capabilities are final classes with restricted constructors and redacted
  rendering. They deliberately lack structural `copy` and serialization.
  Public UUIDs/stamps are lookup metadata; issuer identity and exact private
  invocation/request identity establish local provenance.
- `BrowserReplacementSupport[F, P]` exposes transport/provider operations.
  `ReplacementBrowserPersistence[F, G, P]` preserves the provider-dependent `Tx`
  and inner transaction program `G`; coordinator code does not guess or erase it.
- Authenticated-action calls to the ordinary coordinator ceremony methods are
  deprecated with replacement-specific guidance. The ordinary provider methods
  remain the supported anonymous sign-in path and are documented accordingly;
  migration changes the ceremony, host transaction callback and completion path
  together.
  The warnings apply when the caller's context is statically
  `AuthenticatedActionContext[P]`: both `begin` and `resume` forms, and
  `recover`. Calls from `PublicActionContext` keep their ordinary sign-in
  semantics without a warning. Existing method signatures continue forwarding
  to the same implementation.
- The existing `Effect` and `TransactionProgram` abstractions remain in use.
  Admission is suspended, callbacks are evaluated once, and successful values
  escape only after acknowledged settlement. A callback returning an ordinary
  `Left` value is not itself a transaction rollback instruction.
- Atomics, monitors and JDBC mutation remain at capability, ownership and
  transaction boundaries. They enforce one-use/lifetime rules across threads;
  the pure core contains no executor, database or mutable protocol state.
- No new effect stack or broad Scala syntax migration was introduced. Existing
  Scala 3 enums, contextual parameters and instance-dependent types are retained.
  Formatter changes affect originally touched Scala files; review semantic
  changes separately from formatting, without restoring selected formatter hunks.

## Review-follow-up changes

These focused changes address the final API/FP review. Regression results and
their exact boundaries are recorded below.

| Change | Reason and expected coverage |
| --- | --- |
| Remove the unused pure recovery wrapper | A committed reservation cannot yield `NotCommitted`; core tests retain expired/revoked committed-unavailable behavior. Provider-level negative recovery remains distinct. |
| Reject exhausted slot generation before memory host work | `Long.MaxValue` cannot activate a successor. Memory preparation and pure `BrowserReplacementSlot.reserve` reject it; a focused core test checks the boundary. |
| Suspend `Frontend.retireReplacement` through `delayAsync` | Lazy effects must inspect the replacement marker when executed, including markers set after effect construction; native IO regression covers this timing. |
| Make example `Accounts.presentRecovery` explicitly effectful | One-use holder consumption must occur inside `F`, not while constructing a lazy program; IO suspension/replay coverage checks the boundary. |
| Acquire the JDBC test fixture before awaiting initialization | A timed-out observer does not cancel a Future. Cleanup now follows initialization settlement and attempts both account closure and schema deletion; the existing seed workload and 15-second bounds remain unchanged. |

These changes postdate the performance collections. Those collections remain
evidence for their recorded source fingerprints, not measurements of these
follow-ups. The final validation record must state the resulting source boundary.

### Subsequent code-review response

| Finding | Resolution |
| --- | --- |
| 1: V4 checksum hazard | Restore historical V4 exactly, pin its checksum in the exporter, and generate V5 only. Fresh V4→V5 schema parity and populated-upgrade ciphertext preservation are tested. |
| 2: Redundant predecessor observation | Remove `observedPredecessor` and its tautological comparison. Test invalid captured data in the pure core; real memory/JDBC stamp-change tests prove rejection before host callbacks. Existing revocation, race and resumed-owner tests remain. |
| 3: Capture also marks retirement intent | Add unmarked `currentInvocation` for result disclosure. `replacementInvocation` explicitly marks preparation intent. Suppressed-output tests distinguish the two and retain private identity checks. |
| 4: Two delivery channels | Document live `/auth/complete` and request-bound `/auth/recover`, including their shared budget and authoritative checks. |
| 5: Serialization and reconnect cost | Document realm-and-namespace locking and one awaited probe per eligible close, capped across reconnect cycles. No unproven parallel-reconnect or lock-free-authority change. |
| 6: Successor lookup/retention | Add a partial successor lookup index in V5 and runtime initialization. Clarify existing finite ceremony/history limits; do not prune still-needed linkage based only on generation or ceremony expiry. |
| 7–8: File size and minor consistency | File extraction remains follow-up work. JDBC `resolve` already requires fresh expiry/revocation checks; no error-taxonomy or host-contract change was needed. |

The schema-export regressions also prove that both `--check` and `--write`
reject edited V4 bytes or unreviewed runtime DDL without rewriting migration
files. Once V5 is applied permanently it must also remain frozen; later schema
changes require a new migration version.

## Invariants and risk questions

- **Atomic replacement:** Does host mutation, proof use, audit, predecessor
  revocation, successor creation and browser reservation settle together?
  Ordinary preparation must continue rejecting this transition.
- **Fresh authority:** Are original subject, versions, browser generation and
  physical owner checked at the mutation boundary? Can independent host
  revocation or a competing completion win without creating two successors?
- **Restricted output:** Can any handoff authorize arbitrary state updates,
  snapshots, subscriptions, downloads or sensitive disclosure? It must admit
  only one fixed completion frame for its originating invocation.
- **Unknown settlement:** Does a lost acknowledgment return no handoff and
  recover the same stored outcome without replaying host work? Negative recovery
  must exclude a still-running writer, not infer rollback from absence.
- **Delivery versus activation:** Can an already-held valid successor activate
  after the final delivery consumed the material/budget? Expiry still uses the
  original deadline; neither recovery nor maintenance extends it.
- **Terminal fences:** Do expiry, cleanup and delayed old-owner release preserve
  committed disposition and successor ownership? Logout advances the lineage;
  it does not reset counters or resurrect competing ordinary completions.
- **One-use results:** Is consumption bound to the activated successor and
  exact disclosure invocation? Unknown consumption commit yields no holder;
  response loss does not authorize redisclosure. At-most-once consumption does
  not guarantee that a human received the codes.
- **Lazy effects:** Are marker reads, host callbacks and holder consumption
  evaluated inside the intended effect? Re-running an outer effect or inner
  transaction program must not repeat committed work or disclose twice.

Useful focused specs are
[capability provenance](../modules/spoonbill/src/test/scala/korolev/auth/BrowserReplacementCapabilitiesSpec.scala),
[settlement/replay](../modules/spoonbill/src/test/scala/korolev/auth/BrowserReplacementPreparationSpec.scala),
[external provider compilation](../modules/spoonbill/src/test/scala/replacementconsumer/ReplacementProviderBoundarySpec.scala),
[dispatch](../modules/spoonbill/src/test/scala/korolev/action/ReplacementDispatchSpec.scala),
[fixed output](../modules/spoonbill/src/test/scala/korolev/internal/ReplacementOutputSpec.scala),
[shared provider laws](../misc/browser-auth-conformance/BrowserReplacementConformance.scala),
[deferred retirement in native IO](../examples/browser-auth/src/test/scala/spoonbill/internal/ReplacementRetirementIOSpec.scala),
[native IO coordinator coverage](../interop/ce3/src/test/scala/spoonbill/auth/BrowserCoordinatorGateIOSpec.scala),
and [native IO example coverage](../examples/browser-auth/src/test/scala/example/browserauth/MemoryAccountsIOSpec.scala).

## Migration and browser compatibility

Use the [explicit deployment procedure](browser-auth-v4-deployment.md#upgrade-an-existing-browser-authentication-deployment).
[V5](../modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql/V5__authenticated_session_replacement.sql)
is the forward upgrade. V4 remains byte-for-byte identical to the historical
artifact; its checksum is pinned and neither exporter mode rewrites it.
Fresh installations apply V4 then V5. Populated-schema tests preserve
outstanding ordinary ciphertext and fences, and a catalog-level check compares
fresh migrated schema with runtime initialization. The combined schema has
14 statements, nine tables and four explicit indexes.

Drain old binaries before enabling replacement, apply V5 explicitly, and refresh
pages onto the new client bundle. Old binaries ignore replacement reservations;
old tabs do not understand the new recovery procedure. There is no mixed-version
guarantee, wire negotiation or automatic downgrade procedure. Preserve required
vault keys and retained replay history. No migration runs on an HTTP request.

## Validation and evidence boundary

The [plan's validation record](authenticated-session-replacement-plan.md#15-local-implementation-progress)
contains exact commands and chronology. The table distinguishes the original
gates from subsequent review-follow-up results:

| Gate | Recorded result |
| --- | --- |
| Full compile, test compilation and Scala/ZIO tests | 1,029 passed; no failures, cancellations or ignored tests |
| Client/fixture checks | 98 passed; core purity and generated-schema drift passed |
| Native Chromium examples | 21/21 scenarios across memory Future, memory IO and JDBC |
| Native WebKit examples | Memory and IO 7/7 each; JDBC 7/7 cumulative coverage |
| Clustered browser acceptance | Chromium and WebKit passed, including node-loss recovery |
| ZIO HTTP browser acceptance | Four native/programmatic-order runs passed |
| Review-follow-up compile/test compilation | Passed after formatting 48 feature Scala files |
| Review-follow-up aggregate | 1,031 executed: 1,029 passed and 2 JDBC example initialization timeouts; zero canceled/ignored |
| Isolated example suite after fixture-lifetime repair | 19/19 passed, including both earlier failures and the IO regressions |
| Review-follow-up client/purity/schema | 98/98 passed; 8 pure-core files checked; 13 generated statements / 9 tables matched |
| Focused browser smoke after review fixes | 9/9 passed: Chromium password, factor and lost-command flows for memory, IO and JDBC |
| Subsequent code-review response: compile/test compilation | Passed, including consumers of the revised pure API |
| Subsequent code-review response: affected Scala suites | 731/731 passed: core 33, runtime 435, JDBC 244, examples 19; zero canceled/ignored |
| Subsequent code-review response: client/schema tests | 101/101 passed, including three migration-export regressions; purity and V4/V5 drift checks passed |
| Subsequent code-review response: browser smoke | 9/9 passed: password, factor and lost-command flows for memory, IO and JDBC in Chromium |

The earlier post-review aggregate completed naturally with two failures at
`JdbcAccountsSpec.Fixture` initialization, before those test assertions or the
new presentation method ran. Initialization seeds 1,000 synthetic accounts with
roughly 2,000 inserts; the failures' precise timing cause is unproven. Review
found the constructor-timeout cleanup gap and repaired fixture ownership. The
isolated suite then passed with unchanged 15-second bounds and seed counts.
Across the aggregate and focused rerun, all 1,031 tests have passing coverage;
this is not a claim that the failed aggregate invocation passed.

For the subsequent code-review response, the first core run stopped because
a new invalid-capture test also constructed an invalid ceremony, whose existing
constructor rejected it before the tested decision. The fixture now keeps the
ceremony valid and supplies invalid captured data. Production checks were not
changed to accommodate the test; the affected suites then passed. The full
aggregate and performance collections were not repeated for this response.

The first formatter invocation used unsupported glob paths and stopped before
compilation/tests. Explicit project-relative file paths corrected that setup
error. The aggregate also logged a nonfatal `Stream.scala:305` shutdown
diagnostic already present in the earlier passing aggregate; the affected
existing suite passed, and unrelated stream implementation was not changed.

The full JDBC/WebKit invocation **failed** its unchanged 180-second aggregate
deadline after three passing scenarios. Four remaining scenarios passed in two
labelled batches with unchanged operation bounds and assertions. This is 7/7
cumulative coverage, not a successful full invocation. Historical ordinary-login
timeouts remain unexplained; the evidence does not establish a JDBC deadlock.

Representative commands from the plan, using the repository's Nix environment:

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
nix develop --no-write-lock-file --command node --test \
  misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs \
  misc/replacement-bootstrap-tests.mjs misc/core-purity-tests.mjs \
  misc/browser-auth-baseline/*.test.mjs
nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs
nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check
git diff --cached --check
```

The aggregate command above ran after scoped formatting. The isolated rerun
and refreshed classpath exports used:

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'browserAuthExample/Test/scalafmtOnly src/test/scala/example/browserauth/JdbcAccountsSpec.scala' \
  'browserAuthExample/test' \
  'export browserAuthExample/Runtime/fullClasspath' \
  'export zioHttp/Test/fullClasspath'
```

The current 101-check command includes the three new schema-export regressions;
the earlier 98-check result predates them.
Detailed local logs are `/private/tmp/spoonbill-replacement-review-validation-20261017-attempt2.log`,
`/private/tmp/spoonbill-replacement-review-example-retry-20261017.log` and
`/private/tmp/spoonbill-replacement-review-client-checks-20261017.log`.
Repeat checks only for new changes, failures or unresolved concerns.

After adding these scoped deprecations, formatting, full compile and test
compilation passed. The coordinator/dispatch suites passed 20/20 tests, and the
example suite passed 19/19, including JDBC and native IO. A temporary Scala 3
compile probe of all five authenticated-context overloads emitted exactly five
targeted deprecation warnings and compiled successfully; the probe was not
added to the repository. Exact regression command:

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/scalafmtOnly src/main/scala/korolev/auth/BrowserAuthentication.scala src/main/scala/korolev/auth/BrowserPersistence.scala' \
  compile 'Test / compile' \
  'spoonbill/testOnly spoonbill.auth.BrowserAuthenticationSpec spoonbill.action.ReplacementDispatchSpec' \
  'browserAuthExample/test'
```

The local log is `/private/tmp/spoonbill-deprecation-validation-20261017.log`.

The subsequent code-review response used scoped `scalafmtOnly` on its ten
changed Scala files, then the following compile/test command (repeated after
correcting the new core test fixture):

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' 'core/test' 'spoonbill/test' \
  'securityJdbc/test' 'browserAuthExample/test' \
  'export browserAuthExample/Runtime/fullClasspath'
```

Logs: `/private/tmp/spoonbill-replacement-review-response-20261017-attempt2.log`
and `/private/tmp/spoonbill-replacement-review-response-node-20261017.log`.

The browser smoke uses the freshly exported classpath and the existing fixture's
validated flow selector, keeping all assertions and time limits:

```sh
for provider in memory io jdbc; do
  if [ "$provider" = jdbc ]; then
    nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
      env SPOONBILL_AUTH_ENGINES=chromium SPOONBILL_AUTH_PROVIDER=jdbc \
      SPOONBILL_AUTH_FLOWS=password,factor,lost-command \
      bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-review-example.classpath
  else
    nix develop --no-write-lock-file --command env SPOONBILL_AUTH_ENGINES=chromium \
      SPOONBILL_AUTH_PROVIDER="$provider" SPOONBILL_AUTH_FLOWS=password,factor,lost-command \
      bash scripts/test-browser-auth.sh /private/tmp/spoonbill-replacement-review-example.classpath
  fi
done
```

The optional classpath may be omitted to build/export afresh. The local smoke
log is `/private/tmp/spoonbill-replacement-review-browser-smoke-20261017.log`.
The subsequent code-review response repeated the same selection with
`/private/tmp/spoonbill-replacement-review-response-example.classpath`; its log
is `/private/tmp/spoonbill-replacement-review-response-browser-20261017.log`.

## Performance interpretation

Read the [performance report](authenticated-session-replacement-performance.md)
alongside its [portable raw evidence](authenticated-session-replacement-performance-evidence.json).
Runs 4 and 5 completed all six lanes, faults and cleanup. The explicitly
user-authorized run 5 met both providers' frozen ordinary-path budgets.
Its memory candidate median/p95 were 379.09/397.39 ms; JDBC was
1,068.59/1,093.13 ms. Replacement has no successful historical baseline and is
reported as absolute cost, separately from result consumption.

Run 4's memory ordinary p95 of **4,336.59 ms** exceeded its **1,978.67 ms**
limit and remains valid evidence. Run 5 did not reproduce that tail with the
same source/settings; it does not prove measurement error or identify the cause.
All samples, incomplete attempts and failure records remain preserved. Whole-JVM
RSS and post-GC heap observations do not measure per-feature retained objects or
prove secret erasure. No failed sample, assertion or budget was relaxed to pass.
