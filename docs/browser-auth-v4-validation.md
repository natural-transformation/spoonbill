# Browser authentication v4 validation

This record distinguishes implementation/correctness checks from performance
acceptance. No latency, allocation, retained-memory, or end-to-end performance
pass is claimed. The original measurement manifests and historical results are
preserved. New profiling or engine experiments are not part of these checks.

Implementation revision `c226a74` passed the complete
[Linux CI run](https://github.com/natural-transformation/spoonbill/actions/runs/38128832417)
on 2026-10-11, including both browser engines, all three public example profiles,
PostgreSQL correctness, and the independently resolved published-artifact fixture.

## Consumer integration inventory

The [source comparison](../misc/browser-auth-v4/reports/source-comparison.json)
compares the complete final v3 reference inventory with every Scala production
source under `examples/browser-auth/src/main/scala`. This includes `Main` and
`LazyMain`, shared `Application`/`Model`/`DomainPolicy`, and both `MemoryAccounts` and `JdbcAccounts`.
No example helper or provider-integration file is excluded.

| Inventory | v3 reference | v4 public example | Change |
| --- | ---: | ---: | ---: |
| Production integration files | 14 | 7 | −7 |
| Bytes | 184,282 | 56,803 | −127,479 |
| Nonblank physical lines, including comments | 4,018 | 1,206 | −2,812 |

The v3 side includes all common, memory, JDBC, and application sources from
[the fixed source report](../misc/browser-auth-baseline/reports/v3-source-inventory-final.json).
The v4 side delegates browser/view/ceremony/presentation infrastructure to the
library while retaining complete host proof/session/audit policy in the example.
Library implementation, tests, fixtures, and documentation are separate from
consumer production integration counts. The preserved v3 comparison application
does not participate in the v4 request path. Counts alone do not establish
behavioral equivalence; the coordinator/provider and real-browser suites are
separate requirements.

Regenerate after changing example sources:

```sh
nix develop --no-write-lock-file --command node misc/browser-auth-v4/inventory.mjs
```

## Browser responsibilities and generated source

| Responsibility | v4 treatment |
| --- | --- |
| Procedure and callback wire identifiers | Existing Scala `Frontend` declarations generate `protocol.js`; handwritten browser values are removed. |
| Property tags and DOM operation codes/operand widths | Generated from the same Scala declarations; Closure compiles symbolic browser references to wire values. |
| Completion eligibility, binding/origin checks, credentials, cookies and activation | Scala runtime/provider responsibility; the browser receives only the existing restricted handle. |
| Password/factor policy and authentication UI | Scala example actions and rendering; no application JavaScript or `evalJs` authentication integration. |
| DOM patch order, stale-frame rejection and callback suppression | Browser-local safety checks preserved. |
| Immediate sensitive clearing and departure/disconnect fences | Browser-local safety checks preserved. |
| Physical fetch/WebSocket, bounded delivery retry and fresh reconnect | Existing browser adapter retained without another action HTTP route. |
| Explicit native HTTP form submission | Scala `ActionForms.nativeSubmit` opts a real form into its browser-native submit behavior; typed action bindings retain precedence. No example JavaScript is required. |

Generated output remains part of the delivered bundle and source inventory.
The inventory reports handwritten and generated modules separately; generation
does not count as deleting delivered JavaScript. The parity build rejects a
stale checked-in `protocol.js`, and the wire-contract tests preserve the existing
authentication/ownership/disclosure codes. DOM widths remain allocated only on
the existing active-sensitive patch path.

The [current client comparison](../misc/browser-auth-v4/reports/client-comparison.json)
records the generated sources and the compiled native-submit resource output:

| Client surface | v3 | Current v4 |
| --- | ---: | ---: |
| Handwritten modules | 6 | 6 |
| Handwritten nonblank lines | 1,651 | 1,637 |
| Handwritten bytes | 62,107 | 63,073 |
| Generated modules / nonblank lines / bytes | 0 / 0 / 0 | 1 / 63 / 1,347 |
| Emitted/minified bytes | 27,365 | 27,514 |
| Gzip bytes, level 9 | 10,055 | 10,090 |
| Brotli bytes, quality 11 | 8,845 | 8,882 |

The final native-submit comparison is **+149 raw bytes, +35 gzip bytes and
+37 Brotli bytes**. The maintainer explicitly accepted the final 35-byte gzip
increase on 2026-10-11, superseding the earlier protocol-only one-byte exception.
The fix enables the required native logout POST
without application JavaScript or an extra HTTP round trip. Symbolic names also
increase handwritten source bytes while removing independently maintained
definitions; generated output is counted in full. Recompute the report after
the final resource build:

```sh
nix develop --no-write-lock-file --command node misc/browser-auth-v4/inventory.mjs \
  modules/spoonbill/target/scala-3.3.8/resource_managed/main/static/spoonbill-client.min.js
```

This also writes `misc/browser-auth-v4/reports/client-comparison.json`, including
the full emitted output, fixed gzip/Brotli settings, source hashes and application
script scan results. A source scan is corroborated by review of the complete
example, not treated as proof of arbitrary embedded-code absence.

## Correctness checks

| Check | Current evidence |
| --- | --- |
| Deterministic client/protocol, core-purity and inventory Node checks | 81 passed through Nix, with no failures or skips. No profiling or timing acceptance run is included. |
| Pure core forbidden-API source guard | Passed for six core main-source files; no runtime, clock, entropy, mutable-lifecycle or I/O API violations found. Compiler classpath checks complement this source guard. |
| Explicit PostgreSQL schema drift check | Passed: the exported migration matches 11 provider DDL statements and eight tables. |
| Public example native fixture and shell runner syntax | `node --check` and `bash -n` passed through Nix. |
| Spoonbill runtime tests | All 365 passed, including coordinator/presentation/memory behavior, native-submit rendering, and memory-provider restart/logout recovery. |
| JDBC provider and ownership correctness | Latest complete `securityJdbc` project run passed all 198 tests against disposable PostgreSQL. The earlier focused 54-test run and driver-discovery correction are included in this evidence. |
| Native Future/ZIO transaction preparation | Four tests passed. |
| Cats Effect coordinator ownership and context | Three tests passed, including immediate/queued `IOLocal` preservation and cancellation ownership. |
| Native Scala 3 Pekko adapter fixture | One HTTP fixture test passed. Independent published-consumer validation remains separate. |
| Public example compilation and host-policy tests | Compilation and all seven tests passed: four Future cases and three IO cases. |
| Root aggregate compilation and tests | A fresh Nix JVM passed `compile`, `Test / compile` and `test`: 884 test executions (847 ScalaTest and 37 ZIO), with no failures or skips, against owned disposable PostgreSQL. Linux CI passed the same command. |
| Bounded queue liveness | All 28 effect-module tests passed. A deterministic regression verifies that direct delivery wakes another waiting producer while buffer space remains available. |
| Explicit optional example/benchmark compilation | `pekkoHttpExample/compile` and `performance-benchmark/Test/compile` passed. No benchmark was executed. |
| Legacy Selenium integration-test compilation | `integration-tests/Test/compile` fails on 18 existing Selenium 4 API errors, including `DesiredCapabilities.chrome`. Both its sources and Selenium 4.12.1 dependency are unchanged from the branch base; this project is outside the root aggregate. No unrelated migration was attempted. |
| Repository formatting checks | New core/example sources and newly authored runtime/provider/test files were formatted through the declared formatter. Repository-wide `scalafmtCheckAll` / `scalafmtSbtCheck` still report widespread existing differences; unrelated sources and the existing build-file layout were preserved. A repository-wide formatting migration is separate work. |
| Public Future-memory, IO-memory and JDBC browser smoke | All 12 flows passed sequentially on native macOS and in Linux CI: two login variants × two browser engines × three example profiles. |
| Published native Scala 3 Pekko graph | Passed: generated POM has seven direct dependencies, all `_3`; an independent consumer resolved nine Scala dependencies, all `_3`, compiled with Scala 3.3.8, exercised typed/classic/persistence integration and completed a live HTTP run with exit code 0. Pekko 1.7.0 / HTTP 1.4.0 is the validated pair. |

The coordinator tests cover installation integrity, HTTP binding propagation,
public/protected policy, foreign action authorities, default-deny disclosure,
fresh presentation after login/logout, retained components on ordinary reconnect,
takeover, asynchronous output revocation, foreign receipts, and shutdown.
The failure/release tests additionally cover saturated cleanup admission,
failed-release retry, authorization storage failure, failed policy construction,
bounded unacknowledged ownership, snapshot detachment before asynchronous release,
and preservation of active takeover nodes when disconnected retention is full.
The native smoke uses real Chromium/WebKit and the official server adapter for
Future-memory, IO-memory and JDBC: password and factor login, same-view activation, protected actions,
HTTP reload, logout, stale-cookie rejection, and foreign-origin completion.

Existing optional performance-tooling CI
steps require manual `workflow_dispatch` with `performance_tooling` enabled
(default false); automatic pull-request correctness CI does not invoke them.

Earlier failures are not counted as passes: native logout initially exposed the
need for the explicit form opt-in; the numeric-client assertion and JDBC fixture
driver discovery were corrected. A long-lived interactive build exhausted its
Metaspace after repeated reloads/documentation builds; a fresh aggregate run then
exposed and deterministically reproduced the queue lost wakeup now fixed in
`c226a74`. Earlier macOS WebKit runs also reported a closed socket before readiness;
their cause was not established, and no readiness or origin check was relaxed.
Both focused WebKit variants and the subsequent quiet, sequential 12-flow run
passed, as did the complete Linux browser job. Temporary server diagnostics were
removed; bounded, non-secret browser failure diagnostics remain in the fixture.

Reproduce the complete Scala check with:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

Reproduce the source and migration boundary checks with:

```sh
nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs
nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check
```

Run the public examples on the repository's managed browser environment:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/test-browser-auth.sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  env SPOONBILL_AUTH_PROVIDER=io bash scripts/test-browser-auth.sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc \
  bash scripts/test-browser-auth.sh
```

The script can accept an already compiled classpath file to avoid concurrent sbt
execution. Linux CI uses the pinned Chromium/WebKit shell and owned-process
timeouts. The existing UTM capability record establishes historical Linux guest
use; it is not a current browser run or permission to create new infrastructure.
