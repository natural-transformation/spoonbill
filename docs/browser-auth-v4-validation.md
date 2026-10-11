# Browser authentication v4 validation

This record distinguishes implementation/correctness checks from performance
acceptance. No latency, allocation, retained-memory, or end-to-end performance
pass is claimed. The original measurement manifests and historical results are
preserved. New profiling or engine experiments are not part of these checks.

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

The earlier protocol-only one-byte gzip increase was explicitly accepted by the
user on 2026-10-11. The subsequent native-submit correctness fix changes the full
comparison to **+149 raw bytes, +35 gzip bytes and +37 Brotli bytes**. That larger
delta is reported and awaits an explicit acceptance decision; it is not covered
by the earlier one-byte approval. The fix enables the required native logout POST
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
| Deterministic client/protocol, core-purity and inventory Node checks | 78 passed through Nix. No profiling or timing acceptance run is included. |
| Pure core forbidden-API source guard | Passed for six core main-source files; no runtime, clock, entropy, mutable-lifecycle or I/O API violations found. Compiler classpath checks complement this source guard. |
| Explicit PostgreSQL schema drift check | Passed: the exported migration matches 11 provider DDL statements and eight tables. |
| Public example native fixture and shell runner syntax | `node --check` and `bash -n` passed through Nix. |
| Spoonbill runtime tests | Latest complete project run passed all 363 tests, including coordinator/presentation/memory behavior and the two native-submit rendering tests. The earlier takeover-retention failure is fixed and its seven-test failure/release suite passed. |
| JDBC provider and ownership correctness | Latest complete `securityJdbc` project run passed all 198 tests against disposable PostgreSQL. The earlier focused 54-test run and driver-discovery correction are included in this evidence. |
| Native Future/ZIO transaction preparation | Four tests passed. |
| Cats Effect coordinator ownership and context | Three tests passed, including immediate/queued `IOLocal` preservation and cancellation ownership. |
| Native Scala 3 Pekko adapter fixture | One HTTP fixture test passed. Independent published-consumer validation remains separate. |
| Public example compilation and host-policy tests | Compilation and all seven tests passed: four Future cases and three IO cases. |
| Root aggregate compilation and test orchestration | Both `compile` and `Test / compile` passed. A later aggregate attempt exhausted Metaspace in the long-lived validation JVM after repeated reload/documentation/publication work. Only the owned JVM and disposable PostgreSQL were stopped; a fresh-process aggregate run remains pending. Per-project passes are recorded separately. |
| Explicit optional example/benchmark compilation | `pekkoHttpExample/compile` and `performance-benchmark/Test/compile` passed. No benchmark was executed. |
| Legacy Selenium integration-test compilation | `integration-tests/Test/compile` fails on 18 existing Selenium 4 API errors, including `DesiredCapabilities.chrome`. Both its sources and Selenium 4.12.1 dependency are unchanged from the branch base; this project is outside the root aggregate. No unrelated migration was attempted. |
| Repository formatting checks | `scalafmtCheckAll` / `scalafmtSbtCheck` report pre-existing widespread differences as well as new-file formatting. Formatting of newly authored files is pending; unrelated sources are preserved. |
| Public Future-memory, IO-memory and JDBC browser smoke | Implemented and wired into automatic correctness CI; complete execution pending. |
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

The full repository suite and real-browser execution have not yet been recorded
as passed. Existing optional performance-tooling CI
steps require manual `workflow_dispatch` with `performance_tooling` enabled
(default false); automatic pull-request correctness CI does not invoke them.

An initial native macOS memory-example attempt reached authentication, protected
actions and HTTP reload, then exposed a logout-form submission issue. That run
is not counted as a native pass; the focused form fix and complete browser rerun
remain pending. The stale numeric-client assertion and PostgreSQL
driver-discovery fixture failures were corrected and their complete project
suites now pass; they are not outstanding code failures.

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
