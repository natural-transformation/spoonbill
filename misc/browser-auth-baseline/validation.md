# Phase 0 validation and remaining gates

This is a working v3 reference/review package, not completion of the 4.0 plan.
Published runtime sources, public API and release version remain unchanged.
The original Phase 0 measurement plan is open; broad API/core implementation has
not begun. Its original exit required reviewed equivalent behavior and working
measurement/counter contracts.

## Merge scope decision — 2026-10-11

The maintainer requested that PR #7 be made ready to merge without any further
performance testing. This PR is therefore reviewed as an unpublished reference
foundation. Correctness, authorization, transaction settlement, resource cleanup,
and ordinary browser behavior remain merge requirements. Completion of the
measurement programme below is deferred and is not a blocker for this PR.

No latency or memory pass is inferred from this decision. The acceptance manifest
and missing measurements remain unchanged/null/inconclusive; the proposed v4
coordinator is not delivered here. Automatic CI runs Scala, browser-client,
native lifecycle, and reference-authentication correctness checks. Performance
tooling tests, physical instrumentation probes, native profiling, and runtime
artifact capture/upload are opt-in through the default-false manual
`performance_tooling` workflow input. No optional run was requested for this
takeover, and no further engine build or oracle execution is part of it.

The checks and experiment records below are historical unless explicitly dated
otherwise. Pending local artifact verification and source-audit evidence is
preserved; it is not new performance testing or a performance acceptance result.

### Takeover validation — 2026-10-11

The focused static review of shared policy, callback admission, memory/JDBC
authority, expiry, and shutdown changes found no new correctness blocker. The
following correctness-only command passed **73 tests**:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs \
  misc/browser-auth-baseline/*.test.mjs
```

Read-only checks also validated the changed Markdown links/fences/whitespace,
JSON evidence syntax, all six manual-only workflow gates, and
`nix-instantiate --parse performance/jsc-prototype/installed-proof.nix` (from this
directory). The last command parses the optional helper; it does not run it.
No performance suite, profiler, engine build, allocation oracle, or calibration
was executed. Both automatic correctness jobs must pass on the final PR head
before merge; earlier CI results are historical evidence only.

The first takeover CI run, [38120536588](https://github.com/natural-transformation/spoonbill/actions/runs/38120536588),
passed native-browser correctness but stalled in the full JVM test job. Its
retained log shows a pre-existing `StackOverflowError` in the legacy
`FutureEffect`/`Stream.foreach` inline callback chain; the queue fixture could
then wait indefinitely because producer completion was discarded or awaited
after the consumer. The stalled job was cancelled to retrieve its log.

`QueueSpec` now exercises queue offer/pull/backpressure through native Future
composition on its test execution context, joins producer and consumer results,
propagates producer failure, and gives each asynchronous test a ten-second
deadline. It retains three producers, the same 300/3,000 item counts, and queue
sizes 1/5/10. This is a test-harness correction, not a published runtime change.
The underlying Future stream stack-safety limitation remains a separate runtime
issue; it has not been hidden by increasing stack size or reducing test work.
The focused `effect/test` run passed all **27 correctness tests**, including
the new producer-failure case, through the repository Nix environment.

The complete Scala applications cover password/factor ceremonies, committed
completion, fresh-handshake activation, protected HTTP/actions, logout and
reconnect through the official Pekko adapter. JDBC adds real process-loss
recovery of the same committed attempt without proof replay. Resource fixes
include per-binding ceremony/view quotas, conditional ownership release,
reserved coalesced maintenance, expiry material retirement and settlement-safe
worker shutdown. The [Phase 0 review response](review-response.md) records the
six review fixes, including durable clock checkpoints and lost-command recovery.
Source helpers and infrastructure all count as consumer code.

The executable providers now share one [policy](common/README.md): 1,000 accounts,
short/representative PBKDF2, TTLs, browser/account-tuple rate windows and separate
live/retained bounds. Callback admission is bounded before executor/SQL-gate
dispatch and drains actual settlement. Independent review caught and fixed an
outcome-cap mismatch, differing operation-authority TTLs, a late challenge
expiry check and insufficient durable view-history headroom for complete browser
workflows. The last has a physical PostgreSQL boundary test distinct from live
view admission. These are deterministic correctness improvements, not performance
measurements. The compiled policy report matches the manifest.

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
  misc/browser-auth-baseline/performance/*.test.mjs \
  misc/browser-auth-baseline/performance/jsc-prototype/*.test.mjs

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/test-browser-auth-baseline.sh

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_BASELINE_PROVIDER=jdbc bash scripts/test-browser-auth-baseline.sh

nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  node --test misc/browser-auth-baseline/performance/postgres-wire-proxy.pg-tests.mjs

nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/browser-heap.native-tests.mjs \
  misc/browser-auth-baseline/performance/chromium-allocation.native-tests.mjs \
  misc/browser-auth-baseline/performance/websocket-events.native-tests.mjs \
  misc/browser-auth-baseline/performance/webkit-network-probe.native-tests.mjs \
  misc/browser-auth-baseline/performance/webkit-network-observer.native-tests.mjs

nix develop .#profiling --no-write-lock-file --command node \
  misc/browser-auth-baseline/performance/clock-resolution.mjs NEW_ABSOLUTE_JSONL_PATH
```

| Check | Result and scope |
| --- | --- |
| `spoonbill/testOnly spoonbill.browserauthbaseline.*` | Pass, 47 tests: memory authority, settlement, challenge, expiry erasure, quotas, ownership and shared policy/admission |
| `securityJdbc/testOnly ...JdbcReferenceHostSpec` | Pass, 33 tests against physical disposable PostgreSQL, including durable clock restart, profile TTLs, failure, rollback and pool-disposal laws |
| `securityJdbc/testOnly ...JdbcBrowserSecuritySpec` | Pass, 40 tests: guarded publication, separate durable view-history capacity, reserved release/maintenance, pre-dispatch bounds, retained outcomes, durable expiry and blocking-executor placement |
| `browserAuthBaseline/test` | Pass, complete application compiles and four worker-lifecycle tests pass, including ordinary/maintenance settlement after actor dispatcher termination |
| Baseline and JSC prototype `node --test` globs | Pass, 175 tests, including analyzer/signal regressions, all-cell coverage audit, real Java artifact relocation/archive, policy-contract drift, per-phase HTTP algebra, clock-owned process cleanup and nine envelope/OOM guard tests |
| Memory/JDBC native browser wrappers | Pass, eight ordinary password/factor flows, four same-process memory completion-loss recoveries, two JDBC crash/restart recoveries and a stalled startup-peer check; live deliverable completions isolate the foreign-origin check |
| Native wrappers with `SPOONBILL_AUTH_BASELINE_PROOF=representative` | Pass for both providers/engines, including completion-loss and JDBC process-restart recovery, using the final captured runtime and unchanged startup/work deadlines; initial and restarted servers use the same selected proof profile |
| Physical PostgreSQL wire sensor | Pass, two engine tests: sequential exchanges and bounded overlapping cycles |
| `reference-wire-probe.mjs ... --pipelined-cycles` | Pass, 73 real JDBC conformance tests; 1,184 startup cycles and 11,455 completed Query/Sync cycles, all connections/headers/pending cycles released. Whole-fixture diagnostic, not per-login budgets or physical round trips |
| `capture-browser-auth-artifact.sh NEW_DIRECTORY COMPILED_CLASSPATH_FILE` | Pass; actual compiled reference captured, verified after relocation, and executable policy report rerun from the copied runtime |
| `reference-policy.mjs manifest.json EXECUTABLE_POLICY_REPORT.json` | Pass; every shared constructor field and both proof modes match the manifest |
| Darwin native profiling command above | Pass, eight tests: retained browser heap, experimental Chromium allocation trace, physical WebSocket calibration, pinned WebKit metadata probe and provisional/navigation session ownership |
| `clock-resolution.mjs NEW_ABSOLUTE_JSONL_PATH` through `.#profiling` | Pass, all four diagnostic targets with 20,000 consecutive deltas each; raw data retained, hardware resolution and acceptance flags remain unsupported/false |
| Linux native profiling commands | Pass, five native sensor tests and nine experimental Chromium trace tests; see [UTM evidence](performance/utm-validation.md) |
| `git diff --check` | Pass for tracked changes; new Scala sources were formatted explicitly and new text files checked separately |
| `all scalafmtCheckAll scalafmtSbtCheck` | Fails on existing repository formatting. A clean `git archive HEAD` checkout produced the same failing project/file counts. Unrelated files were preserved; new baseline compile/test sources were formatted with scoped tasks |

Broad regression runs on the shared-policy sources passed `spoonbill/test`
(327 tests) and `securityJdbc/test` (167 tests), through the disposable PostgreSQL
wrapper and the same Nix/SBT entry point shown above. The later separate view
history bound and its additional guard test passed the complete focused run
(47 memory/common, 73 JDBC and four application tests). Existing native WebSocket
lifecycle tests also passed earlier. No benchmark pass is inferred from any
correctness test. The first new expiry test expected a generic rollback rather
than the existing typed `HostDenied` rejection; its assertion was corrected and
the full focused/broad runs passed. Its no-session/material/audit assertions remain.

Review verification exposed test-infrastructure failures that were corrected
before the final passing runs: the process-timeout fixture's one-second budget
included two Node startups; browser waits needed actual client readiness; and
Playwright's WebKit proxy could forward close before a pending binary reload
message. Failed raw logs were retained. The corrected correctness fixtures and
the production changes passed; no performance threshold or manifest was relaxed.

## Captured inventories

[Current consumer inventory](reports/v3-source-inventory-final.json)
records 14 production integration files, 4,018 nonblank lines including comments,
with source-set hash
`47ac5bcaae1835fef46604241911a3c32cf35c690ea6f075ca920cc1be5f3374`.
The [originally reviewed inventory](reports/v3-source-inventory.json) remains
available as historical evidence. [Current client inventory](reports/v3-client-inventory-final.json) records six handwritten
framework modules/1,651 nonblank lines, emitted bundle 27,365 B, gzip 10,055 B,
and Brotli 8,845 B. The applications have no script files or embedded-script scan
matches; source review confirms the Scala typed authentication forms. Existing
framework JavaScript was preserved; no removal or size reduction is claimed.
These source identities do not freeze an accepted baseline artifact by themselves.

The actual local [runtime capture](performance/artifact.md), summarized in the
[final report](reports/runtime-capture-final.json), contains 25 ordered
classpath entries, 1,405 files and 35,894,662 bytes. Its runtime content hash is
`3fb9713201347617d7c3d43610903dbaa671ce24c29b8e7380bb05626be79a5d`;
the ordered resolved runtime graph hash is
`fadc28e178f1fbb0b375ded380abe1221ffe4062879f8f29da0668ac206aba8f`.
The verified 37,263,360-byte tar has SHA-256
`6ad5d6488cdd3ef2d33f68af06e971a177bfffa1f7d70db453db85bfecf977e9`.
The actual [compiled policy report](reports/reference-policy-final.json) is retained.
This capture was built on Darwin with the tracked shared-policy changes and
added sources present; it is not a clean accepted measurement baseline. CI's
producing job is configured to link its separately built immutable upload,
actual Git revision and archive digest, with ninety-day retention. The
[CI run for `ebce4da`](https://github.com/natural-transformation/spoonbill/actions/runs/38078863177)
passed both `build` and `native-browser` and published the
[reference runtime](https://github.com/natural-transformation/spoonbill/actions/runs/38078863177/artifacts/11680330126).
The [CI capture summary](reports/ci-runtime-capture.json) records its merge/build
revision, content/source/graph identities and distinct tar/upload-ZIP digests.
Its reference source hash matches the final local inventory; compiled runtime
and graph hashes identify the separately built Linux artifact. The producing job
verified the capture/tar. The original browser download attempt timed out; that
diagnostic remains recorded. A later independent GitHub connector download on
2026-10-11 succeeded on its first attempt: the 28,796,649-byte uploaded ZIP and
37,263,360-byte contained tar matched the retained digests. After extraction to
an independent directory, the captured standalone loader verified all members,
25 classpath entries and the recorded runtime/source/graph identities. The
captured policy executable ran through Nix and matched both its captured report
and the checked-in manifest. This closes the download-verification gap;
source review/freeze and performance acceptance remain open.
The later [CI run for `7224b35`](https://github.com/natural-transformation/spoonbill/actions/runs/38091860022)
also passed both jobs, including the added static envelope/OOM tests. This does
not run the experimental JSC source build or establish allocation acceptance.
The final tar was also extracted to an independent temporary directory; its
loader verified the copied contents and executed the exact compiled policy report
without referring to the original classpath. The default SBT main class was
checked explicitly as `spoonbill.browserauthbaseline.JdbcReferenceServer`.

The final captured runtime, rather than mutable build output, was used for the
native browser runs. [Memory](reports/browser-memory-exchanges-diagnostic.jsonl)
and [JDBC](reports/browser-jdbc-exchanges-diagnostic.jsonl) journals retain actual
HTTP completion and selected-source WebSocket notification counts, named phases,
counter bounds and zero retained observer handles. Chromium and WebKit notification
counts differ; they are not silently normalized or relabeled as physical frames.
The [physical calibration](performance/websocket-events.md) documents wrapper
omissions and CDP message semantics. Correctness control probes and disabled HTTP
caching prevent these journals from becoming normal-flow acceptance budgets.

The [final JDBC wire diagnostic](reports/jdbc-wire-cycles-final-diagnostic.jsonl)
separately identifies the actual compiled test classpath, suites and instrumentation.
The initial strict sequential probe rejected actual driver pipelining and remains
preserved with the [earlier cycle probe](reports/jdbc-wire-cycles-diagnostic.jsonl).
The bounded overlapping mode counts completed protocol cycles; overlapping cycles
and physical network round trips are never equated. Raw private process logs and
all failed runs remain separate from the published payload-free journals.

The [clock diagnostic](performance/clock-resolution.md) and its
[safe summary](reports/clock-resolution-macos-diagnostic.json) retain actual
Node/JVM/Chromium/WebKit observations and raw/source hashes. Minimum positive
steps were 41 ns, 41 ns, approximately 0.1 ms and 1 ms respectively on this Mac.
Call/loop overhead, privacy coarsening and the small number of positive browser
steps prevent these minima from establishing hardware resolution or application
sensor bounds. No nominal nanosecond field was promoted to verified evidence.
Synchronous owned-process cleanup precedes earlier immediate-exit listeners;
forced exit can still prevent evidence finalization and SIGKILL is unsupported.

## Deferred work before the original Phase 0 measurement plan closes

1. Durable-clock correctness now has independent acknowledged checkpoints and
   restart/rollback/failure tests. Their additional connection, SQL, commit and
   synchronous snapshot costs must be included in the future measured workload
   ceilings and latency/memory calibration; correctness tests do not freeze them.
2. Shared host configuration and callback admission are now executable and tested.
   Full workload equivalence still needs exact presentation/node populations,
   expensive-policy/controlled-contention schedules and all capacity scopes.
   Full cached component-graph accounting is not established by the adapters'
   stored-node counters; the fixed reference topology and whole-runtime memory
   collectors must account for it. Capacity arithmetic is not a completed-work
   or maximum-process-duration measurement.
3. Actual completed-work collectors, whole-workload HTTP/WebSocket/JDBC wire
   counts, operation/resource ceilings, accepted artifact identities and baseline-only
   calibration remain incomplete. The 94-cell manifest stays
   `awaiting-reference-review`; missing values stay null/inconclusive.
   The [coverage audit](performance/coverage.md) enumerates all 94 cells, reusable
   fixtures and missing runners/instrumentation. A real legacy echo pilot
   completed 1,000 warmup plus 1,000 measured exchanges, with raw process output
   preserved and unsupported metrics null. It is explicitly unfrozen evidence.
   Calibration alone has 16,920 observations, 42,840 cold server launches and
   at least 47 hours of mandatory quiescence before actual work/builds/analysis.
   Pilot feasibility and the declared 72-hour collection cap need review.
4. Comparable cumulative browser allocation and simultaneous process-tree peak
   RSS remain unsupported. Stock retained heap/native allocator/RSS and V8 trace
   probes measure different scopes. [Profiling notes](performance/profiling.md)
   preserve the lighter Mac JSCOnly investigation and earlier pinned-source HTTP
   422 download failures. Fifteen pinned source files and the sole Playwright patch
   were fetched through Nix; the patch does not explain the installed header
   mismatch or supply a complete counter. A bounded follow-up found an exact
   upstream match for the installed header at revision `3f6cca8` (2025-09-23),
   later than the configured July base. This narrows the header provenance but
   does not identify the full binary revision or establish ABI compatibility.
   Matching installed-browser source/build provenance remains unresolved.
   The user subsequently approved a separate standalone JSC experiment in the
   existing guest: 16,000,000,000-byte maximum, 14,000,000,000-byte high watermark,
   zero swap, one compiler job, at most two CPUs, two hours and 15 GiB additional
   disk. Linux supplies the validated resource limit; no VM clone/restart or
   browser replacement is involved. Actual tiny-builder containment and the
   settled 64 MiB OOM/descendant-cleanup proof passed, and the official WebKitGTK
   2.50.4 archive was acquired with its Nix hash verified. Engine attempt one
   failed on flag quoting and attempt two on CMake generation. Attempt three
   failed at the shared-library link with undefined `Subspace::attributes() const`
   from instrumented `IsoSubspace`; the native oracle never ran. The final
   engine unit peaked at 3,669,725,184 charged bytes and its process group was
   released; shared CI remained active. The highest observed unit peak was
   approximately 6.587 GB during dependency staging, with the actual 16 GB
   decimal hard cap unchanged. A minimal inline-header fix was subsequently
   committed, and a separately authorized fourth attempt was last observed
   running in the 2026-10-11 05:29:40–06:14:40 UTC window. Its final build,
   oracle, and cleanup disposition is not established by the retained evidence.
   Further experiments and queued proof steps are deferred under the 2026-10-11
   instruction to stop performance testing; no additional attempt is scheduled.
   See the [ledger design](performance/jsc-prototype/design.md),
   [envelope](performance/jsc-prototype/envelope.md),
   [OOM proof](performance/jsc-prototype/oom-proof.md) and
   [results](performance/jsc-prototype/results.md). Interpreter-only JSCell
   accounting excludes GC-managed Auxiliary cells and native backing stores;
   it cannot fill browser acceptance metrics or close the 94-cell calibration
   and workload gaps.

Later phases, including pure-core extraction, the coordinator/provider API,
client-duplication removal, publication verification and migration, are pending.
The shared CI guest remained running after the capability probes.
