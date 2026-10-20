# Sensitive acknowledgment can invalidate an unchanged browser action

Date: 2026-10-18

Affected version: Spoonbill 6.0.0, tag commit `5362feabd2ae1c5d58104ef255f5e52b2d8e7cfd`.

## Status

Resolved in [Spoonbill 7.0.0](https://github.com/natural-transformation/spoonbill/releases/tag/v7.0.0) through [PR #14](https://github.com/natural-transformation/spoonbill/pull/14). Sensitive completion callbacks can now return an explicit `NoUpdate`, skipping the unnecessary transition and publication. The regression now exercises real typed forms and guarded incoming messages, with a separate real-service Chromium/WebKit suite. See the [migration guide](migration-v7.md) and [implementation validation](sensitive-acknowledgement-implementation-validation.md).

The investigation below records the original 6.0.0 behavior and reproduction. Its manual identity transition was replaced by the typed form path in the current test. The original deterministic test reproduced the connection-closing interaction while the two existing publication/admission protection tests still passed.

A separate consumer browser diagnostic fails during enrollment confirmation with compatible symptoms. Its sanitized log establishes that the browser sent revision-bearing view events during the confirmation interval and that the serving process reported `SessionAccessDenied`. It does not identify the inner DOM event, prove that the typed confirmation handler ran, or identify the exact server rejection point. The isolated framework result is confirmed; attributing that entire browser failure to this mechanism remains an inference.

## User-visible effect

A browser displays sensitive setup details and acknowledges their delivery. The application returns an unchanged state after the acknowledgment. A subsequent interaction from the still-visible page can close the connection before its application handler runs, even though authority, ownership, route, and application state have not changed. This can interrupt a security setup flow and force recovery.

The observed behavior fails closed. These checks do not demonstrate unauthorized access or sensitive-data exposure.

## Mechanism in the 6.0.0 sources

1. `PublicActionContext.presentSensitive` requires a completion callback returning `StateUpdate[S]`.
2. `ActionForms` consumes the disclosure and unconditionally calls `access.transition(onResult(result).apply)` after acknowledgment. `StateUpdate.unchanged` is an identity function. `UiOutcome.unchanged` likewise represents `Updated(identity)`, not an instruction to skip a transition.
3. `ComponentInstance.applyTransitionEffect` writes the resulting state; the transition queues a render even when that state is unchanged.
4. `ApplicationInstance.onState` rebuilds the render and handler snapshot. `Frontend.performDomChangesWithProducer` can produce an empty DOM diff, `[4 ]`.
5. `Frontend.enqueueOutput` publishes that diff as a new guarded patch and advances `renderRevision` before transport delivery.
6. `Frontend.selectFromPublishedView` requires the submitted revision to equal the published revision. A subsequent action stamped with the browser's last delivered revision is rejected and closes the connection.

Strict rejection after a newer publication is intentional and is covered by existing tests. The integration problem is that completing a sensitive disclosure can create that publication without changing the visible state, while another visible control remains actionable. This report does not propose accepting arbitrary stale revisions.

Relevant sources at the affected 6.0.0 commit, relative to the repository:

- `modules/spoonbill/src/main/scala/korolev/ActionForms.scala`
- `modules/spoonbill/src/main/scala/korolev/internal/ComponentInstance.scala`
- `modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala`
- `modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala`
- `modules/spoonbill/src/test/scala/korolev/internal/PublishedViewAdmissionSpec.scala`

## Isolated reproduction recorded against 6.0.0

Historical diagnostic name: `modules/spoonbill/src/test/scala/korolev/server/internal/services/NoOpSensitiveAcknowledgementSpec.scala`.

This section describes the original diagnostic source and its recorded results, not the current 7.0.0 test implementation. That diagnostic was supplied separately to the 6.0.0 build; checking out the release tag alone does not supply it. The current specification has six passing scenarios covering no update, identity modification, state modification, revoked authority, replaced ownership, and queued-state timing. Use the [implementation validation report](sensitive-acknowledgement-implementation-validation.md) for current commands and results.

The test uses real `SessionsService`, `ApplicationInstance`, `Frontend`, and sensitive-runtime behavior, with a synthetic payload and an always-authorizing guard. It requires no application database, browser, production configuration, or external service.

It starts a guarded view, consumes initialization output, presents a disclosure, and sends the real acknowledgment derived from the emitted presentation binding. The acknowledgment result must be `BrowserProcessed`. It then performs one identity transition, waits for that render, and asserts that state is still unchanged. Post-acknowledgment output is deliberately left undelivered. The next action uses the last browser-delivered revision and is expected to execute; on 6.0.0 it fails with `SessionAccessDenied`.

`transitionForce` replaces the ordinary acknowledgment transition solely to await the same render deterministically. It does not add a second render. The test calls the guarded action-admission method directly rather than reproducing the complete typed DOM wire path. It therefore records the higher-level desired behavior and the failing interleaving, rather than asserting that the existing low-level revision guard violates its current contract. The 7.0.0 regression subsequently replaced this diagnostic path with real typed submissions and explicit update outcomes.

The workflow has a 10-second timeout and cleanup has a separate 3-second bound. It uses no sleeps to arrange the race. The next-action effect is passed by name and is evaluated only after admission checks.

Historical command for the 6.0.0 tree with the diagnostic source added:

```sh
nix develop . --no-write-lock-file --command env \
  JAVA_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2' \
  SBT_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dsbt.supershell=false' \
  sbt --server --batch --no-server \
  'set spoonbill / Test / parallelExecution := false' \
  'set spoonbill / Test / fork := false' \
  'spoonbill / Test / testOnly spoonbill.server.internal.services.NoOpSensitiveAcknowledgementSpec spoonbill.internal.PublishedViewAdmissionSpec'
```

The recorded execution used the same diagnostic source via a temporary `Test / unmanagedSources` addition before saving it locally at the path above. These are historical 6.0.0 results, not the expected results of running the current specification. Environment: Scala 3.3.8, sbt 1.10.7, Nix-provided JDK 21.0.8. Compilation succeeded. Three tests completed in 2.395 seconds:

| Check | Result |
| --- | --- |
| New no-op sensitive acknowledgment regression | Expected failure: `SessionAccessDenied` |
| Retain old handler until replacement frame is published | Pass |
| Reject old revision after asynchronous authorization yields to newer publication | Pass |

The initial diagnostic draft had an ambiguous untyped `identity` call and did not compile. An explicit `String => String` lambda corrected the test; that first attempt provides no runtime evidence. An incidental Nix removal of unused lock entries was restored, and the recorded runtime test used `--no-write-lock-file`.

## Browser corroboration and limits

One approved diagnostic used production consumer actions with synthetic data and the published 6.0.0 artifacts. It recorded 13 outgoing JSON frames, all parsed successfully, including a successful sensitive acknowledgment and subsequent view events carrying revision 3. The original connection closed; a replacement connection initialized at revision 0 and advanced to 1. The automatic HTTP recovery request returned 403. No host session, framework session, or completion was created. The serving process reported `SessionAccessDenied`.

No new patch was observed on the original connection in that run. Server publication can precede transport delivery, so this observation is compatible with the isolated race but does not establish the server's published revision or precise denial branch. Client-send and transport-observer timestamps use separate relative clocks and must not be treated as exact synchronized timestamps.

Earlier instrumentation received no Playwright `framesent` events and incorrectly suggested missing proof dispatch or compression. Those interpretations are invalid: guarded browser authentication disables WebSocket compression, and the client sends a Blob even for JSON. The corrected observer calls native `WebSocket.send` first and inspects bounded owned slices afterward. Only opcodes, numeric revisions, acknowledgment status, and allowlisted rejection metadata were retained; no proofs, cookies, identifiers, or raw frames are included here.

## Fix requirements recorded for 6.0.0

- Address disclosure-completion publication or interaction sequencing at the framework boundary. The 6.0.0 typed API offers no supported skip-update acknowledgment outcome.
- Preserve authority checks, ownership fencing, published-handler consistency, and denial of genuinely stale views.
- Do not hide the race with application sleeps, weakened assertions, or retries that replay authentication proofs.
- If adding an explicit no-update outcome, ensure it actually skips rendering/publication; another alias for `Updated(identity)` is insufficient.
- Retain both existing admission tests and add coverage through the typed sensitive form and a real browser. Verify that an ordinary confirmation can proceed after setup acknowledgment, including delayed transport delivery.

No library implementation, release artifact, ACC environment, or production deployment was changed for this investigation.
