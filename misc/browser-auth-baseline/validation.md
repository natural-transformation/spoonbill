# Phase 0 validation and remaining gates

This is a working v3 reference/review package, not completion of the 4.0 plan.
Published runtime sources, public API and release version remain unchanged.
Phase 0 is open; broad API/core implementation has not begun because the plan's
Phase 0 exit explicitly requires reviewed equivalent behavior and working
measurement/counter contracts.

The complete Scala applications cover password/factor ceremonies, committed
completion, fresh-handshake activation, protected HTTP/actions, logout and
reconnect through the official Pekko adapter. JDBC adds real process-loss
recovery of the same committed attempt without proof replay. Resource fixes
include per-binding ceremony/view quotas, conditional ownership release,
reserved coalesced maintenance, expiry material retirement and settlement-safe
worker shutdown. The [Phase 0 review response](review-response.md) records the
six review fixes, including durable clock checkpoints and lost-command recovery.
Source helpers and infrastructure all count as consumer code.

## Reproducible checks

Run from the repository's `flake.nix`. Commands below use the same focused tasks
as the final checks; classpath reuse during native testing used the complete
compiled `browserAuthBaseline` application, not hand-selected classes.

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly spoonbill.browserauthbaseline.*' \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcReferenceHostSpec spoonbill.security.jdbc.JdbcBrowserSecuritySpec' \
  'browserAuthBaseline/test'

nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/*.test.mjs \
  misc/browser-auth-baseline/performance/*.test.mjs

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/test-browser-auth-baseline.sh

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_BASELINE_PROVIDER=jdbc bash scripts/test-browser-auth-baseline.sh

nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  node --test misc/browser-auth-baseline/performance/postgres-wire-proxy.pg-tests.mjs
```

| Check | Result and scope |
| --- | --- |
| `spoonbill/testOnly spoonbill.browserauthbaseline.*` | Pass, 40 tests: memory authority, settlement, challenge, expiry erasure, quotas and ownership |
| `securityJdbc/testOnly ...JdbcReferenceHostSpec` | Pass, 31 tests against physical disposable PostgreSQL, including durable clock restart, failure, rollback and pool-disposal laws |
| `securityJdbc/testOnly ...JdbcBrowserSecuritySpec` | Pass, 36 tests: guarded publication, reserved release/maintenance, durable snapshot/session expiry and blocking-executor placement |
| `browserAuthBaseline/test` | Pass, complete application compiles and two worker-lifecycle tests pass, including settlement after actor dispatcher termination |
| Baseline `node --test` globs | Pass, 72 tests, including invalid bootstrap seeds, unusable timing/throughput and SIGINT/SIGTERM cleanup before earlier immediate-exit handlers |
| Memory/JDBC native browser wrappers | Pass, eight ordinary password/factor flows, four same-process memory completion-loss recoveries, two JDBC crash/restart recoveries and a stalled startup-peer check; live deliverable completions isolate the foreign-origin check |
| Physical PostgreSQL wire probe | Pass; proves sequential protocol sensor, not end-to-end JDBC login budgets |
| Linux native profiling commands | Pass, five native sensor tests and nine experimental Chromium trace tests; see [UTM evidence](performance/utm-validation.md) |
| `git diff --check` | Pass for tracked changes; new Scala sources were formatted explicitly and new text files checked separately |
| `all scalafmtCheckAll scalafmtSbtCheck` | Fails on existing repository formatting. A clean `git archive HEAD` checkout produced the same failing project/file counts. Unrelated files were preserved; new baseline compile/test sources were formatted with scoped tasks |

Earlier broad regression runs passed `spoonbill/test` (306 tests) and
`securityJdbc/test` (128 tests), before the later baseline-only test additions.
Focused final checks cover those additions. Existing native WebSocket lifecycle
tests also passed. No benchmark pass is inferred from any correctness test.

Review verification exposed test-infrastructure failures that were corrected
before the final passing runs: the process-timeout fixture's one-second budget
included two Node startups; browser waits needed actual client readiness; and
Playwright's WebKit proxy could forward close before a pending binary reload
message. Failed raw logs were retained. The corrected correctness fixtures and
the production changes passed; no performance threshold or manifest was relaxed.

## Captured inventories

[Current consumer inventory](reports/v3-source-inventory-review-fixes.json)
records 11 production integration files, 3,500 nonblank lines including comments,
with source-set hash
`d934be05f005fb6c40790b3ee08b4a14552590ccf357fa0a54ebeecacfaea49e`.
The [originally reviewed inventory](reports/v3-source-inventory.json) remains
available as historical evidence. [Current client inventory](reports/v3-client-inventory-review-fixes.json) records six handwritten
framework modules/1,651 nonblank lines, emitted bundle 27,365 B, gzip 10,055 B,
and Brotli 8,845 B. The applications have no script files or embedded-script scan
matches; source review confirms the Scala typed authentication forms. Existing
framework JavaScript was preserved; no removal or size reduction is claimed.
These source identities do not freeze an accepted baseline artifact by themselves.

## Required work before Phase 0 closes

1. Durable-clock correctness now has independent acknowledged checkpoints and
   restart/rollback/failure tests. Their additional connection, SQL, commit and
   synchronous snapshot costs must be included in the future measured workload
   ceilings and latency/memory calibration; correctness tests do not freeze them.
2. Reference/measurement configurations must be made equivalent and frozen.
   Current examples seed two accounts and use bounded small fixtures, while the
   draft manifest includes 1,000 accounts/views, larger node sweeps and work
   counts exceeding retained quotas. Memory/JDBC TTL/rate settings also differ.
   Full cached component-graph and pre-admission callback bounds/accounting are
   not certified by the adapters' stored-node/queued-job counters.
3. Actual completed-work collectors, whole-workload HTTP/WebSocket/JDBC wire
   counts, operation/resource ceilings, artifact identities and baseline-only
   calibration remain incomplete. The 94-cell manifest stays
   `awaiting-reference-review`; missing values stay null/inconclusive.
4. Comparable cumulative browser allocation and simultaneous process-tree peak
   RSS remain unsupported. Stock retained heap/native allocator/RSS and V8 trace
   probes measure different scopes. [Profiling notes](performance/profiling.md)
   describe the lighter Mac JSCOnly investigation and the pinned-source HTTP 422
   download blocker. No engine build or additional VM clone was started.

Phases 1–6, including pure-core extraction, the coordinator/provider API,
client-duplication removal, publication verification and migration, are pending.
The shared CI guest remained running after the capability probes.
