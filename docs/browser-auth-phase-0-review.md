# Browser authentication Phase 0: staged-change review

Date: 2026-10-10.

Current disposition: **all six original findings are closed**. The final
`prependListener` change resolves the shutdown ordering edge case, and its
72-test Node suite passed on the last review. On 2026-10-11 the maintainer
authorized making PR #7 merge-ready as an unpublished reference foundation,
without further performance testing. Correctness CI remains required; the
original measurement programme is deferred and no performance pass is claimed.
The review rounds below are preserved as historical evidence.

## Final shutdown review and takeover scope

The final shutdown review inspected staged-diff SHA-256
`4eb2570f25d3dae011e381463ba5ae2196e8361ecffe898187ab76b91f8c85b7`.
Cleanup is now prepended before existing handlers and synchronously terminates
owned collector groups. Four actual-process cases cover SIGINT/SIGTERM through
both observation and collection, verify collector/descendant termination before
test teardown, preserve application listeners, and reject incomplete evidence.
No new actionable finding remained; `git diff --cached --check` and all 72 Node
tests passed. Scala/native suites were not rerun for that harness-only change.

The subsequent takeover retains the user's local evidence and moves optional
performance tooling/profiling to explicit manual CI dispatch. It does not resume
the JSC experiment or run further performance tests. Current merge scope and
historical validation are recorded in the [validation report](../misc/browser-auth-baseline/validation.md).

## First follow-up review of the fixes — historical

Reviewed the [response and evidence](../misc/browser-auth-baseline/review-response.md), the current fixes, and their tests on 2026-10-10. The follow-up covered the 61-file staged version, whose `git diff --cached --binary | shasum -a 256` identity was:

```text
b838146afe750fe12ffe559b40408043b55a0b4b6dbda166c254acb79298e1c9
```

This identity was unchanged when updating this report. No production or test sources were modified during either review.

### Finding status at the first follow-up

| Original finding | Status | Follow-up assessment |
| --- | --- | --- |
| 1. Expiry revived after JDBC restart | **Closed within the documented single-writer contract** | `JdbcReferenceClock` exposes only acknowledged durable maxima, new instances consult storage, and the host/guard share the clock. Rejected domain work cannot roll back the independent checkpoint. Restart, rollback, acknowledgment-loss, precision, and resource-disposal tests passed. |
| 2. Memory lost-response recovery unavailable | **Closed** | The original retained ceremony always offers recovery. `Used` preserves its ID and hides proof inputs. Native tests drop the real completion command and recover the same attempt after reconnect without another proof submission. |
| 3. Zero bootstrap seed false pass | **Closed** | Manifest validation and the exported interval function require canonical nonzero uint32 seeds, including before the equality shortcut. The original false-pass case now becomes inconclusive. |
| 4. Zero timing/throughput false pass | **Closed** | Throughput must be positive for completed work; elapsed timing must meet observed resolution. Legitimate zero counts/bytes remain supported. The original false-pass case now becomes inconclusive. |
| 5. Interrupted harness orphaned collectors | **Partially resolved; P2 remains** | Normal CLI signals, concurrent owners, listener removal, and interruption evidence are covered. An earlier application listener that exits synchronously can still prevent collector cleanup. See the remaining finding below. |
| 6. Origin test used an invalid completion | **Closed** | The browser test now rejects a foreign-origin request using a real deliverable completion, then verifies successful same-origin delivery of that completion. Both providers and browser engines passed. |

The clock fix does not establish support for simultaneously active independently cached writers or restoring an older database snapshot. Those cases remain outside the documented deployment contract. Its extra connection acquisitions, checkpoint statements, and commits are explicitly acknowledged and must be included in the eventual performance baseline; correctness tests do not establish unchanged latency or memory use.

### Then-remaining finding 5 — [P2] Run cleanup before existing shutdown listeners

Current location: [run.mjs](../misc/browser-auth-baseline/performance/run.mjs), lines 27–30.

`ownInterruption` registers cleanup with `process.on`, so it runs after existing application listeners. An embedding application that previously registered the following handler exits before the collector-ownership handler runs:

```js
process.on('SIGTERM', () => process.exit(0));
```

Reproduction: register that handler before calling `observe`, wait for an owned synthetic detached collector to start, signal the harness with `SIGTERM`, and check whether the collector remains alive. The Nix-run reproduction returned:

```json
{
  "case": "earlier-synchronous-shutdown-listener",
  "harness": { "code": 0, "signal": null },
  "collectorSurvived": true,
  "expectedCollectorSurvived": false
}
```

The collector group was explicitly terminated in the reproduction's cleanup. The current inherited-listener test only increments a counter; it does not exercise a listener that exits immediately.

**Required change:** prepend the ownership handler so its synchronous group termination runs before existing shutdown handlers, preserve the application's listeners, and add the immediate-exit case to the tests. Raw-evidence finalization must remain explicit about the limits of an embedding application's forced exit. No cleanup guarantee is claimed for `SIGKILL`.

### Follow-up verification

Reran all four verification commands in the response. The exact Node and focused Scala commands are retained in the original-verification section below; the native commands were:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/test-browser-auth-baseline.sh

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_BASELINE_PROVIDER=jdbc bash scripts/test-browser-auth-baseline.sh
```

| Check | Follow-up result | Remaining action |
| --- | --- | --- |
| Focused Node suites | **Pass: 68 tests** | Add the earlier immediate-exit listener regression case. |
| Focused Scala suites with disposable PostgreSQL | **Pass: 109 tests** — 40 memory, 67 JDBC, two application lifecycle | No failure in these runs; baseline performance remains unmeasured. |
| Memory/JDBC native browser wrappers | **Pass** — eight ordinary flows, four memory completion-loss recoveries, two JDBC process-restart recoveries, and the stalled-startup release check | Keep these cases in the validation gate. |
| Original analyzer false-pass reproductions | **Closed:** invalid seed/measurement evidence becomes inconclusive | Retain the new regression tests. |
| Earlier application shutdown listener reproduction | **Defect confirmed:** detached collector survived | Fix listener ordering and verify collector termination. |
| Updated source and client inventories | **Match** the current sources and Nix-built bundle | Freeze reviewed artifacts/configuration before acceptance measurements. |
| `git diff --cached --check` | **Pass** | None. |

The updated source inventory records 11 files, 162,166 bytes, and 3,500 nonblank lines. Framework client source and emitted/compressed bundle identities match the updated report. The staged-diff identity remained unchanged during the follow-up.

Full-repository regression suites and actual latency/memory performance workloads were not rerun in the follow-up. Native browser suites were rerun, unlike in the initial review. Phase 0's workload equivalence, frozen budgets, calibration, and unsupported memory metrics remain open as recorded in the [validation report](../misc/browser-auth-baseline/validation.md). No performance pass is claimed.

## Original review — archived scope and state

Reviewed the 57 staged files for the Phase 0 implementation against the [implementation plan](browser-auth-integration-plan.md), including the Scala reference applications, memory/JDBC integrations, browser tests, source/client inventories, performance runner/analyzer, profiling helpers, and build/CI changes.

The original staged diff was unchanged between the initial code review and creation of this report. Its SHA-256, produced by `git diff --cached --binary | shasum -a 256`, was:

```text
1c42255d908257e87894480d56e1d040b95a437b94383b7efe3af3c6521b7def
```

The following findings and verification record describe that original staged version, not the current fix status. Their line references and reproduced outputs are historical. Use the follow-up section above for current disposition.

## Original findings — historical

### 1. [P1] Expired JDBC authority can revive after restart

Location: [JdbcReferenceHost.scala](../misc/browser-auth-baseline/jdbc/JdbcReferenceHost.scala), lines 64–68. The corresponding clock in `JdbcBrowserSecurity.scala`, lines 206–209, also resets per instance; `JdbcReferenceServer.scala`, line 109, supplies `Instant.now()`.

The high-water mark starts at `Instant.MIN` for each host instance. It prevents a backward clock from reviving authority within the same process, but it does not preserve observed expiry across restart. The executable fixture does not supply the cross-restart nondecreasing clock assumed by the comment.

A concrete failure sequence is:

1. Create a factor challenge at `t0`.
2. Advance the injected clock to `t0 + 61 seconds`; factor submission correctly returns `Expired`.
3. Construct a new host against the same database and key with its clock at `t0 + 1 second`.
4. Submit the original challenge and factor. The durable row remains challenged, and the new instance's freshness check can accept it.

Active sessions have the same problem: a session rejected after its 600-second expiry can validate again after restart with an earlier clock. This does not depend on beating delivery-material cleanup, which deletes ciphertext but does not durably invalidate active sessions or challenged ceremonies.

**Required change:** implement and test a cross-restart clock/expiry contract. Use either an actually guaranteed trusted clock or durable monotonic/terminal expiry handling that survives rejection, rollback, and crashes. A watermark written only inside the rejected transaction is insufficient because it rolls back. Include the resulting storage and latency costs in the reference baseline.

**Evidence:** static control-flow review. The existing clock test checks backward movement within one host instance, not across a newly constructed host. The staged validation document already acknowledges this unresolved limitation.

### 2. [P2] Lost completion response leaves the memory application unable to recover

Location: [ReferenceApplication.scala](../misc/browser-auth-baseline/app/ReferenceApplication.scala), lines 87–88; recovery is exposed at lines 97–103 and rendered at lines 194–202.

Successful preparation returns `UiOutcome.CommitAuthentication` without updating the retained `Page`. If the socket drops after the host commits but before the completion command reaches the browser, memory reconnect preserves the old password/factor form. The application offers recovery only when the backend explicitly returns `CommitUnknown`.

Resubmission instead returns `Used` and clears the ceremony. The host still has a valid committed attempt, but the ordinary application flow cannot recover its delivery without beginning another login. This occurs while the memory host remains alive; it is separate from the documented fail-closed behavior on memory-provider process restart.

**Required change:** retain recovery state or rediscover committed attempts when reconnecting. Add a browser/application test that drops the completion response after acknowledged commit, reconnects without restarting the host, and recovers the same attempt without resubmitting proof.

**Evidence:** static review of completion handling, recovery rendering, and memory reconnect state preservation. The loss scenario was not exercised by the browser suites in this review.

### 3. [P2] An accepted zero seed can certify a performance regression

Location: [analyze.mjs](../misc/browser-auth-baseline/performance/analyze.mjs), lines 154–158; manifest seed validation is at lines 56–58.

Manifest validation accepts any integer seed. With seed `0`, or a value whose uint32 representation is zero, the xorshift generator remains zero forever. Every bootstrap draw selects the first pair, so the resulting interval is unrelated to the full sample distribution.

The reproduction used one latency pair with a 10% improvement followed by 29 pairs with a 10% regression. The analyzer returned:

```text
manifest issues: []
overall status: pass
mean latency change: +9.33%
confidence bounds: approximately [-10%, -10%]
```

The checked-in default seed is nonzero, but the validator currently accepts the configuration that produces this false pass.

**Required change:** reject seeds whose uint32 representation is zero, or use a generator that handles zero correctly. Add a regression test using mixed improving/regressing pairs and verify that the accepted seed domain cannot collapse resampling to one observation.

**Evidence:** reproduced through the repository Nix environment using the existing synthetic fixture.

### 4. [P2] Zero timing and throughput are accepted as valid evidence

Location: [analyze.mjs](../misc/browser-auth-baseline/performance/analyze.mjs), lines 255–261.

The shared nonnegative-number validation accepts zero latency and zero throughput for positive completed work. When the baseline is zero, the analyzer switches to absolute differences and treats identical zero observations as exact equality.

Replacing every comparison and calibration sample's latency and throughput with zero produced an overall `pass`. The exception for legitimate zero counts or bytes must not certify unusable timing/throughput measurements.

**Required change:** validate each metric's meaningful domain. Zero throughput for positive completed work and timing observations below usable measurement resolution should be inconclusive. Preserve absolute comparisons for metrics where zero is legitimate, such as counts and allocation bytes.

**Evidence:** reproduced through Nix. The reproduction command for findings 3 and 4 is included below.

### 5. [P2] Interrupting the harness leaves detached collectors running

Location: [run.mjs](../misc/browser-auth-baseline/performance/run.mjs), lines 25–26.

The runner creates detached collector process groups, but neither `observe` nor the CLI installs cleanup for harness `SIGINT`/`SIGTERM`. Terminating a wrapper awaiting `observe` leaves the collector alive. Its timeout also disappears with the wrapper, so a JVM/browser/database workload can continue and contaminate subsequent measurements.

The existing tests cover collector timeout and a collector parent leaving descendants. They do not cover termination of the measurement harness itself.

**Required change:** handle interrupt/termination by stopping the currently owned process group, preserving incomplete-run evidence, and exiting with an appropriate status. Add a test that signals the harness and verifies that its collector group terminates. This concerns catchable termination signals; it does not claim cleanup can run after `SIGKILL`.

**Evidence:** reproduced through Nix with an owned synthetic collector that wrote its PID and stayed alive. After terminating the wrapper, the collector was still running. The review explicitly terminated that owned process group afterward.

### 6. [P2] The foreign-origin browser test also fails for an unrelated reason

Location: [browser.test.cjs](../misc/browser-auth-baseline/browser.test.cjs), lines 83–87.

The test sends a random completion UUID after successful activation. Unknown-completion lookup returns 403 even if origin validation is removed, so the assertion does not establish that the foreign-origin gate works.

**Required change:** use an actual prepared, deliverable completion in the same cookie context before activation. Assert that the foreign-origin request is rejected, then demonstrate that delivery succeeds with the permitted origin. This isolates the origin check from missing, consumed, or expired completion rejection.

**Evidence:** static test-path review. This is a validation gap, not evidence that the current server accepts foreign-origin completion.

## Original Phase 0 completion assessment

The staged [validation report](../misc/browser-auth-baseline/validation.md) correctly describes Phase 0 as open. The [performance manifest](../misc/browser-auth-baseline/performance/manifest.json) remains `awaiting-reference-review`, with missing artifact identities, completed-work contracts, and measured operation/resource ceilings. Those gaps must not be filled with synthetic test results.

The executable workloads also need alignment with the declared measurement contract. For example, the default latency pass requests 10,000 warmup operations followed by 5,000 measured operations, while the JDBC integration defaults to 1,024 retained bindings and audit records. Successful login/action workloads consume retained audit capacity, so the declared run cannot complete against those defaults without an explicit compatible configuration and reset/setup protocol.

Other acknowledged differences include session/ceremony/reconnect TTLs, seeded account counts, proof algorithms, and required collectors. Freeze one equivalent workload configuration and verify completed useful work before accepting performance evidence. Unsupported browser allocation/process-tree memory measurements remain blockers for their required gates.

The Scala-first direction is preserved: the reference applications contain no custom authentication JavaScript, and the client inventory identifies concrete protocol-duplication candidates without claiming an unmeasured reduction.

## Original verification — historical

### Focused Node suites — pass, 59 tests

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/*.test.mjs \
  misc/browser-auth-baseline/performance/*.test.mjs
```

These suites passed despite the analyzer and harness edge cases above. Add the missing regression cases before using the tooling for acceptance evidence.

### Focused Scala suites — pass, 97 tests

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly spoonbill.browserauthbaseline.*' \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcReferenceHostSpec spoonbill.security.jdbc.JdbcBrowserSecuritySpec' \
  'browserAuthBaseline/test'
```

Results: 40 memory tests, 55 JDBC tests against disposable PostgreSQL, and two application lifecycle tests passed. These results do not establish cross-restart clock safety or lost-response recovery in the browser.

### Analyzer reproductions — confirmed false passes

```sh
nix develop --no-write-lock-file --command node --input-type=module <<'NODE'
import {syntheticFixture} from './misc/browser-auth-baseline/performance/test-fixture.mjs';
import {evaluate, identity, manifestIssues} from './misc/browser-auth-baseline/performance/analyze.mjs';

const zeros = syntheticFixture();
for (const evidence of [zeros.comparison, zeros.calibration])
  for (const row of evidence.samples) {
    row.metrics.latencyP50Ns = 0;
    row.metrics.operationsPerSecond = 0;
  }
console.log('zero timing:', evaluate(zeros.manifest, zeros.comparison, zeros.calibration).status);

const seeded = syntheticFixture();
seeded.manifest.protocol.seed = 0;
for (const evidence of [seeded.comparison, seeded.calibration])
  evidence.metadata.manifestSha256 = identity(seeded.manifest);
for (const row of seeded.comparison.samples)
  if (row.pass === 'latency' && row.variant === 'candidate')
    row.metrics.latencyP50Ns = row.block === 0 ? 90 : 110;
console.log('manifest issues:', manifestIssues(seeded.manifest));
console.log(evaluate(seeded.manifest, seeded.comparison, seeded.calibration).cells[0].metrics[0]);
NODE
```

The script executes successfully, but its `pass` outputs demonstrate analyzer defects. They are not successful performance validations.

### Staged whitespace and source inventory — pass

`git diff --cached --check` passed. Recomputing the source inventory through its exported `inventory` function matched the checked-in report: 10 files, 157,967 bytes, and 3,415 nonblank lines. The staged-diff hash remained unchanged throughout the review.

### Checks not rerun during the original review

Native browser suites, full-repository regression suites, and actual performance workloads were not rerun during the original review. Existing recorded runs were read as supporting context, not presented as newly executed checks. Native suites were subsequently rerun in the follow-up above. No latency or memory release pass is claimed.
