# Explicit state-update implementation validation

Date: 2026-10-18. Records implementation and validation of explicit state-update outcomes.
Migration guidance: [Spoonbill 7 migration](migration-v7.md).

## Implementation

- `UiOutcome` is a sealed hierarchy. `StateUpdate.NoUpdate()` and `StateUpdate.Modify(reduce)` directly implement it; the old `Updated` wrapper and identity-based helpers are removed.
- Composition and zoom describe pure updates without evaluating reducers or lenses. Composition with `NoUpdate` reuses the nonempty operand. `Modify(identity)` still requests a transition.
- `ActionForms` interprets successful updates, sensitive completion, invalid outcomes, and dispatch rejection through the same internal interpreter. Both rejection overloads accept `ActionRejection => StateUpdate[S]`.
- Application callbacks execute through `Effect.delay` in the existing continuation. Ordinary thrown callback exceptions fail the effect without requesting a transition; ZIO receives typed failures instead of defects. Reducer execution and failure reporting retain their existing component-queue semantics.
- All compiled framework, example, baseline application, JDBC, and interop consumers are migrated. No dependencies, lockfiles, global effect/queue behavior, admission rules, or production browser protocol code were changed.
- The standalone browser fixture uses the actual Scala service and assembled client, port zero, an ordered bounded output gate, bounded control requests, and synthetic state. CI includes its runner in the existing Linux native-browser job.

These local checks preceded publication. [Spoonbill 7.0.0](https://github.com/natural-transformation/spoonbill/releases/tag/v7.0.0) was subsequently published on 2026-10-18 at commit `ab16dfa75b0497c1140502c5acb7a7c4c4894def`. The [signed publication workflow](https://github.com/natural-transformation/spoonbill/actions/runs/39123047855) and merged-main CI passed, and the POMs and JARs for all 24 Scala 3 modules were verified publicly available on Maven Central. [PR #15](https://github.com/natural-transformation/spoonbill/pull/15) also corrected a test-fixture cleanup deadlock discovered in post-merge CI by starting transport shutdown before response release. The validation observations below retain their original scope and date.

## Reproduction and protection coverage

`ActionFormsExecutionSpec` renders and captures the actual typed form event, dispatches through the real action dispatcher, and records transition requests without evaluating reducers. It covers ordinary success, invalid input, input/access rejection, all three sensitive completion outcomes, callback and presentation failure, expiry, supersession, and suppressed output. Its real `SensitiveDisclosureScope`, using a deterministic fake deadline scheduler, releases pending holders and deadlines on completion and cleanup. A state-read counter checks that interpretation does not read state early.

`NoOpSensitiveAcknowledgementSpec` uses real `SessionsService`, `ApplicationInstance`, and `Frontend` instances with ownership epochs. It submits distinct reveal/confirm forms through the incoming guarded wire stream, sends the matching real disclosure acknowledgement, and awaits the actual event-counter completion. Inspected output is kept separate from browser delivery. Six cases cover:

1. `NoUpdate` preserves state and admits confirm once at the delivered revision, with the connection alive.
2. `Modify(identity)` still publishes and rejects the old revision.
3. A state-changing `Modify` still publishes and rejects the old revision.
4. Authority revocation still denies confirm.
5. A replaced ownership epoch still denies confirm.
6. A typed reducer queued behind an unfinished transition runs once against the state produced by that earlier transition.

The workflow is bounded to ten seconds and cleanup independently to three seconds. It uses no sleeps, forced transitions, `afterRender`, or action replay. Existing published-handler, admission, sensitive lifecycle, replacement, and ownership suites are retained.

## Validation commands and results

Commands run from the repository root through its flake, Scala 3.3.8, and managed JDK 21. No lockfile was written. For the commands below, this helper records the environment used:

```sh
run_sbt() {
  env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
    nix develop . --no-write-lock-file --command env \
    JAVA_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2' \
    SBT_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dsbt.supershell=false' \
    sbt --batch --supershell=false -Dsbt.server.autostart=false "$@"
}
```

| Check | Result | Observation / next action |
| --- | --- | --- |
| Isolated type/interpreter prototype | Pass | Existing diagnostic prototype compiled in the `effect` test scope and all assertions passed; permanent suites below supersede it. |
| Focused core suites | Pass | 71 tests, including six real guarded-runtime cases and actual public callback API compile-time boundaries. |
| IO adapter suite | Pass | Four tests: laziness, acknowledgement ordering, IOLocal context, deferred reducer, failure, widening. |
| ZIO adapter and typed-action suites | Pass | Eleven tests, including environment/FiberRef preservation and typed callback failures without defects. |
| Sensitive lifecycle and ownership selection | Pass | 52 tests. |
| Testkit | Pass | 18 tests; legacy transitions preserved. |
| Example, compiled baseline, ZIO HTTP, standalone test compilation | Pass | Includes the final browser fixture observer correction. |
| Client protocol suites | Pass | 60 tests. |
| Existing native browser suites | Pass | Durable view, sensitive DOM, and sensitive departure checks in Chromium and WebKit. |
| Existing public example browser suites, Future and IO | Pass | Both providers passed password, factor, replacement, second-tab, and lost-delivery cases in Chromium and WebKit, using exported compiled classpaths. |
| Existing ZIO HTTP browser suite | Pass | Both navigation orders, disclosure, reconnect, replacement, and recovery passed in Chromium and WebKit. |
| Full aggregate compile / test compile / test with disposable PostgreSQL | Pass | 1,106 tests (1,065 ScalaTest and 41 ZIO) across aggregate projects; no failures, cancelled, ignored, or pending tests reported. |
| New real-service acknowledgement browser matrix | Pass | User-authorized retry passed all eight cases: no update, identity modification, state modification, and authority revocation in Chromium and WebKit. |
| Migration and whitespace audit | Pass | Multiline audit finds only deliberately negative removed-API snippets; `git diff --check` passes. |

Focused and cross-project commands (the failed initial compilation stages were resumed after correction, without repeating passed focused suites):

```sh
run_sbt \
  'set spoonbill / Test / parallelExecution := false' \
  'set spoonbill / Test / fork := false' \
  'spoonbill / Test / testOnly spoonbill.action.StateUpdateSpec spoonbill.action.StateUpdateInterpreterSpec spoonbill.action.ActionSpec spoonbill.action.InputSchemaSpec spoonbill.ActionFormsSpec spoonbill.ActionFormsExecutionSpec spoonbill.server.internal.services.NoOpSensitiveAcknowledgementSpec spoonbill.internal.PublishedViewAdmissionSpec example.sensitive.SensitivePayloadSpec' \
  'spoonbill / Test / testOnly spoonbill.internal.Sensitive* spoonbill.sensitive.* spoonbill.action.SensitiveInvocationOwnershipSpec spoonbill.action.ReplacementDispatchSpec spoonbill.server.internal.services.Sensitive*' \
  'testkit / Test / test'

run_sbt \
  'ce3 / Test / testOnly spoonbill.action.StateUpdateIOSpec' \
  'zio2 / Test / testOnly spoonbill.zio.StateUpdateSpec spoonbill.zio.TypedActionSpec'

run_sbt \
  'browserAuthExample / Test / compile' \
  'browserAuthBaseline / Compile / compile' \
  'zioHttp / Test / compile' \
  'standalone / Test / compile' \
  'export browserAuthExample / Runtime / fullClasspath' \
  'export zioHttp / Test / fullClasspath'
```

The isolated pre-integration diagnostic ran with:

```sh
run_sbt \
  'set effect / Test / unmanagedSources := Seq(file("/private/tmp/spoonbill-update-design.47x8E9/StateUpdateDesignCheck.scala"))' \
  'set effect / Test / fork := false' \
  'effect / Test / runMain spoonbill.plancheck.StateUpdateDesignCheck'
```

Those source settings were process-local. The scratch source is diagnostic material outside the repository; permanent suites provide the durable regression coverage.

Client/browser commands:

```sh
nix develop . --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs
nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command bash scripts/test-sensitive-acknowledgement.sh
```

The existing authentication browser scripts accept previously exported classpath files to avoid redundant compilation:

```sh
nix develop . --no-write-lock-file --command bash scripts/test-browser-auth.sh \
  /private/tmp/spoonbill-explicit-updates-example.classpath
nix develop . --no-write-lock-file --command env SPOONBILL_AUTH_PROVIDER=io \
  bash scripts/test-browser-auth.sh /private/tmp/spoonbill-explicit-updates-example.classpath
nix develop . --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh \
  /private/tmp/spoonbill-explicit-updates-zio-http.classpath
```

Full gate:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop . --no-write-lock-file --command env \
  JAVA_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2' \
  SBT_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dsbt.supershell=false' \
  bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

The installed sbt reports that the `Global / concurrentRestrictions` setting is unused. This report does not claim that setting serialized the aggregate gate. Focused core suites explicitly disabled parallel execution and forking.

Audits and syntax checks:

```sh
rg -n -U 'UiOutcome\s*\.\s*(Updated|update|unchanged)|StateUpdate\s*\.\s*unchanged|StateUpdate\s*\(' \
  modules interop examples misc -g '*.scala'
rg -n 'onRejected|ActionForms\.onSubmit' modules interop examples misc -g '*.scala'
git diff --check
nix develop . --no-write-lock-file --command bash -c \
  'bash -n scripts/test-sensitive-acknowledgement.sh && node --check misc/sensitive-acknowledgement-native-tests.cjs'
```

The removed-API audit intentionally reports four negative `typeCheckErrors` source snippets in `StateUpdateSpec`; there are no active removed-API calls. Syntax and whitespace checks passed. The first formatter invocation used unsupported globs and failed without changes; the corrected exact-absolute-file invocation of `spoonbill / Compile / scalafmtOnly` succeeded. Unrelated formatting churn in six minimally migrated existing lifecycle fixtures was removed; new code and substantive edits were formatted using the existing configuration.

## Failures encountered and corrections

- Initial Nix execution could not reach the daemon inside the sandbox. The same Nix commands succeeded with the approved daemon access; no host-tool fallback was used.
- Initial focused run: 60 of 62 passed. The two new revocation/ownership tests read protected state after invalidating the guard, so they failed before the intended confirm event. Moved that state assertion before invalidation; all six final runtime cases pass.
- An attempted warning-free event extractor in the private form fixture lost its generic event type because `Context.Binding` is covariant. Restored the typed test-only pattern. The final fixture compiles with one expected unchecked generic pattern warning; the production hierarchy matching compiles without casts or unchecked variance.
- Example compilation found a multiline `UiOutcome.update` missed by the initial single-line migration audit. Migrated it and expanded the audit to multiline matching; all four compiled consumer gates pass.
- New browser attempt 1: WebSocket origin authorization rejected the synthetic fixture. Added a synthetic authentication completion configuration with the actual loopback origin chosen by port-zero binding.
- New browser attempt 2: actual disclosure/acknowledgement traffic arrived, but the fixture's event-counter regex omitted the serializer's trailing whitespace. Corrected the parser after checking the real serializers.
- New browser attempt 3: Chromium's no-update scenario completed both actions, called confirm once, emitted zero post-ack patches, and retained its connection. Its final observer assertion failed because Playwright's sent-frame events recorded no submissions. Replaced that observation with a bounded, read-only observer of actual server input, retaining only two connection/revision stamps and preserving every original frame. Paused further browser execution at the recovery limit and requested human direction. These partial observations alone did not establish a passing matrix.
- The user explicitly authorized another run. The corrected runner compiled the fixture and passed all eight cases in Chromium and WebKit, exited with status zero, and completed its owned-resource cleanup. Both actual form submissions were observed using the browser's delivered connection/revision; no frames were manually replayed.

The successful run emitted these bounded observations (each case also requires the normal browser acknowledgement):

| Engine | Mode | Confirm admitted | Publication withheld | Connection closed |
| --- | --- | --- | --- | --- |
| Chromium | No update | Yes, once | No | No |
| Chromium | Identity modification | No | Yes | Yes |
| Chromium | State modification | No | Yes | Yes |
| Chromium | Authority revocation | No | No | Yes |
| WebKit | No update | Yes, once | No | No |
| WebKit | Identity modification | No | Yes | Yes |
| WebKit | State modification | No | Yes | Yes |
| WebKit | Authority revocation | No | No | Yes |

Both no-update cases asserted two completed events, zero post-acknowledgement patches, one confirm invocation, and the original live connection. Modification cases awaited observed publication before the stale submit while holding patch delivery in order. Revocation cases denied the next action without requiring a publication. Final runner output: `PASS: real-service sensitive acknowledgement in Chromium and WebKit`.

## Structural performance findings and limits

Code review, interpreter spies, binding tests, and the runtime regression establish:

- `NoUpdate` requests zero transitions; there is no acknowledgement-triggered render/patch work through this path. Completion/event-counter and sensitive lifecycle work still runs.
- `Modify` directly implements `UiOutcome`, without an `Updated` allocation, and passes one unevaluated reducer to the existing transition queue.
- No-update composition reuses the nonempty operand; reducers and lenses do not run during construction.
- No new state equality check, DOM traversal, render pass, executor hop, production buffer, or scheduler was added. The bounded transport gate/observer exists only in the browser test fixture.
- Callback interpretation adds `Effect.delay` and its continuation. This is not evidence of a numerical allocation or latency improvement.

Independent review identified no concrete performance regression concern requiring a targeted benchmark. No throughput, latency, allocation, or universal non-regression measurement is claimed. No benchmark framework was added. The verified baseline identity remains `5362feabd2ae1c5d58104ef255f5e52b2d8e7cfd`; this work did not run a baseline/candidate benchmark comparison.

All implementation validation gates for this change passed, including the complete real-service Chromium/WebKit acknowledgement run. Artifact publication subsequently completed as recorded above; application deployment remains separate. A genuine later publication still invalidates an older revision; revision/ownership validation and one-shot sensitive ownership were not relaxed.
