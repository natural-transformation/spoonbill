# Browser authentication Phase 0 review response

Date: 2026-10-10. Addresses the six findings in the locally supplied
`docs/browser-auth-phase-0-review.md`, which is preserved outside this change.
The original review and its staged-diff identity are preserved. These fixes
remain on `codex/browser-auth-integration` and are included in PR #7.
Phase 0 stays open for the acknowledged workload/measurement gaps.

| Finding | Change | Verification |
| --- | --- | --- |
| 1. Expiry revived after restart | `JdbcReferenceClock` independently commits the greatest observed instant before exposing it. New instances access storage; exact seconds/nanoseconds avoid timestamp rounding. Guard and host share one clock. Rejected domain work cannot roll back the checkpoint. Existing transaction disposal handles abort, quarantine and acknowledged close failure. Async paths use the blocking executor; snapshots retain their explicit synchronous v3 boundary. | Real PostgreSQL tests reconstruct hosts/adapters after challenge/session/snapshot expiry with an earlier clock. Tests cover rollback, clock write/acknowledgment failure, exact nanoseconds, abort success/failure, quarantine and costs. |
| 2. Memory lost-response recovery unavailable | The retained original ceremony always offers a typed recovery form. `Used` preserves the original ID and hides proof inputs. Recovery still performs fresh bound authorization and never executes proof again; a definitely uncommitted attempt receives its existing negative fence. | Chromium and WebKit each drop the real acknowledged completion command, disconnect, reconnect without restarting the host and recover password-only and factor attempts. They verify the same completion ID and unchanged proof-submission count. |
| 3. Zero bootstrap seed false pass | Manifest and exported interval require canonical nonzero uint32 seeds, including the exact-equality shortcut. | Invalid zero/truncated/signed/fractional seeds fail closed; accepted boundary seeds correctly classify mixed improving/regressing pairs. |
| 4. Zero timing/throughput false pass | Positive completed work requires positive throughput. Elapsed latency must meet actual sensor/clock resolution. Zero bytes/counts and absent lock wait remain legitimate. | Zero/below-resolution timing and zero throughput become inconclusive; exact-resolution boundaries and legitimate absolute zero comparisons remain covered. |
| 5. Interrupted harness orphaned collectors | Shared SIGINT/SIGTERM cleanup is prepended before existing application listeners and synchronously terminates owned groups. It removes only owned listeners. Cooperative CLI interruption preserves failure evidence and exits 130/143; an embedding application's immediate exit can override status and prevent final evidence writes. SIGKILL remains outside the contract. | Actual harness/collector/grandchild tests cover both signals through library and CLI, concurrent observers, preserved application listeners, and earlier handlers that immediately exit through both observation and collection APIs. Incomplete forced-exit evidence is rejected. |
| 6. Origin test used invalid completion | The test holds the browser's actual prepared completion before activation, rejects a foreign-origin request using that ID and cookie context, then allows the original same-origin request to return 204 and activate. | Both providers and engines pass the live negative/positive pair in their ordinary password/factor flows. |

## Explicit baseline cost and schema change

The startup fixture schema now includes one `baseline_clock` row per configured
realm/namespace. Existing synthetic schemas need explicit migration/recreation;
request paths do not create tables. Each newly advanced observation adds one
connection acquisition, one UPSERT/RETURNING execution and one commit, even if
the domain transaction later rejects or rolls back. Real wall-clock observations
can advance several times within one request. No once-per-request amortization
or unchanged latency claim is made.

The expiry-rejection cost test observes two connections, two frontend JDBC
executions, one acknowledged clock commit, one domain rollback and zero active
connections afterward. Fixed-clock preparation still has eleven domain JDBC
executions after an earlier acknowledged clock; this is not a whole-login or
wire-round-trip count. All clock costs participate in the same probe data source.

The serial guarded adapter needs at least two pool connections. A raw host with
`N` simultaneous domain transactions needs `N + 1` or a separately reserved
clock pool. The executable fixture opens independent connections without pooling.
Unknown checkpoint settlement uses the existing safe abort/quarantine rules.

## Checks

All checks use the repository `flake.nix`:

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
```

Results: 40 memory + 67 JDBC + two application-lifecycle tests passed; all
72 Node tests passed, including the shutdown-ordering follow-up below. Native runs passed eight ordinary flows, four memory
completion-loss recoveries, two JDBC process-restart recoveries and the stalled
database startup/release check. Scoped formatting, syntax and whitespace checks
cover the changed files. Full-repository formatting retains its previously
verified pre-existing failures; unrelated files were preserved.

The first full Node run exposed the old timeout fixture's startup allowance;
the corrected fixture then passed focused and full runs. Browser debugging
preserved its failed logs and corrected readiness/close-ordering in the test
harness. Performance acceptance parameters were unchanged. Native WebSocket
fault injection connects to the actual server and never fabricates authentication
or reload commands, changes cookies, or supplies application JavaScript.

[Updated source inventory](reports/v3-source-inventory-review-fixes.json) and
[client inventory](reports/v3-client-inventory-review-fixes.json) preserve the
original reports alongside the review-fix identities. Framework client source
and delivered bundle remain unchanged. Workload equivalence, frozen budgets,
baseline calibration and unsupported memory metrics remain open as listed in
[validation](validation.md).

## Follow-up: earlier immediate-exit shutdown listeners

The updated review identified an existing application signal listener that calls
`process.exit()` before ownership cleanup can run. Ownership now uses
`process.prependListener`, so synchronous group termination runs first while the
application listener remains installed and retains its chosen exit status.
Four actual-process cases cover SIGINT/SIGTERM through both `observe` and
`collect`. They verify the application's exit marker and code 0, terminated
collector/descendant PIDs and a stopped heartbeat before teardown. Test cleanup
also handles groups whose leader has already exited.

Forced exit may prevent raw-output draining and the normal failure record.
The collection tests retain metadata/prior samples, require no completion marker,
and verify that the analyzer rejects the incomplete evidence. Normal CLI
interruptions still finalize failure evidence and exit 130/143. No guarantee is
made for SIGKILL or a later listener deliberately prepended ahead of cleanup.

Verification for this follow-up:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/run.test.mjs

nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/*.test.mjs \
  misc/browser-auth-baseline/performance/*.test.mjs
```

Both passed: 18 focused harness tests and 72 complete Node tests. Scala/native
browser suites were not rerun for this harness-only change; their earlier passing
results above are retained as history. No acceptance manifest or performance
threshold changed.

## Phase 0 continuation

The subsequent [validation report](validation.md) records the current shared
executable policy, separate host/view history bounds, pre-dispatch callback
admission, actor-dispatcher-independent worker settlement, real driver protocol
cycle probes, browser notification scope, and relocatable runtime artifacts.
Its final inventories preserve the earlier review-fix reports as history. All
six review findings are addressed; full workload runners, measured ceilings,
baseline-only calibration and unsupported memory quantities still prevent Phase 0
closure. Correctness and sensor checks do not establish a performance pass.
