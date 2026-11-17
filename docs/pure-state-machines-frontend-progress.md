# Frontend state-machine work

## Close sequence: confirmed defects fixed before extraction

Two regressions failed against the original implementation. A pending user-action
completion callback throwing during close prevented every later cleanup step
(input cancellation count was zero). A second close while the first awaited input
cancellation invoked that cancellation twice. The tests use the actual frontend
and an effect adapter whose completion callback throws after settling its promise.

Close now claims teardown before invoking callbacks, runs every ordered cleanup
step even after a nonfatal failure, and returns the first failure to its owning
caller. Pending callbacks and download-cancellation launches are isolated from one
another. Download finalizers remain nonjoining, as they can await a consumer.
The owning close runs uncancelably; a repeated or recursive close returns without
joining its own teardown. Authority is revoked before any callbacks run. Request,
sensitive-resource, outgoing-queue, user-queue, browser-rack, and input cleanup
retain their existing order.

Validation via the repository Nix environment:

- `spoonbill/testOnly *FrontendCloseSpec` before the fix: two expected failures,
  demonstrating skipped cleanup and duplicate cancellation.
- `spoonbill/testOnly *FrontendCloseSpec *BrowserRpcLifecycleSpec
  *SensitiveRuntimeSpec *SensitiveRuntimeConcurrencySpec` after the fix: 35 tests
  passed, including recursive close and all queued action completions. The legacy
  `Stream.sort` cancellation logged its existing null-sibling-callback defect;
  that independent Hub fix is owned by the effect-module work and not changed
  here. It did not fail a frontend assertion.

This commit fixes ownership and error handling only. The connection/publication,
outgoing variants, registries and close-command model remain the next refactor.

### Close-publication race found in review

Review identified a gap between claiming cleanup and publishing the output fence.
If a render callback held the publication monitor, the first close could claim
cleanup and wait for the monitor, while a second close returned before either
caller fenced output. A public-behavior regression holds that monitor through
`acceptInitialRender` and starts two closes. It failed on the initial fix because
the second close returned with the interaction still live. Every close now
publishes the fence before testing the cleanup claim. Recursive cleanup remains
nonjoining because the monitor is released before callbacks begin.

The focused competing-close regression failed before the order correction.
The four-suite teardown command above then passed all 36 tests after correction.

## Sensitive runtime ownership

`SensitiveRuntimeMachine` holds the presentation phase and outcome-waiter status,
plus registry admission/retirement metadata. The shared pending-registry model
provides closed admission and the four-entry limit; region uniqueness remains a
separate domain condition in the sensitive model. A single acknowledgement-window
constant governs timer delay, emission and acknowledgement checks.

Each interpreter entry now owns one atomic record containing that metadata and
its opaque plaintext, callback and cancellation handles. No payload or callback
enters the pure model. Emission atomically marks delivery and detaches plaintext;
retirement detaches the entry, clears plaintext and cancellation handles, then
cancels timers. Browser clear is still queued before the result callback runs.
Authority checks, clock observation, encoding under the entry monitor, notification
and callback effect boundaries retain their existing order. CAS retries never
invoke cancellation or completion callbacks.

The baseline `SensitiveRuntimeSpec` and `SensitiveRuntimeConcurrencySpec` passed
14 cases before extraction, including blocked inline acknowledgement, reentrant
retirement, failed clear enqueue and delayed absolute deadlines. After extraction,
the frontend/sensitive focused matrix passed 70 cases, including three direct
model laws for strict acknowledgement boundaries, independent emission/retirement/
completion and bounded region ownership after close. New model/tests were formatted
with explicit-file `scalafmtOnly`; existing runtime edits remain surgical.
## Sensitive metadata boundary repair

`SensitivePresentation` and its existing metadata types now reside in
`spoonbill-core`, together with the unchanged `SensitiveError` enum extracted
from the runtime payload file. Public `spoonbill.sensitive` names remain the
same. The runtime artifact already depends transitively on core; consumers using
the published dependency graph retain these types without a new dependency.
The defining JAR changes, so manually assembled classpaths must include the
matching core artifact and must not mix old runtime and new core versions.
Plaintext payloads, scheduling durations, callbacks and cancellation handles
remain in the runtime module. The model accepts a supplied clock and expresses
the acknowledgement window as five seconds; scheduling still uses the minimum
of that window and the original lifetime.

The source guard accepts only explicit named sensitive metadata imports after
checking the two core definition files for expected declarations and forbidden
APIs. Missing, symlinked or impure definitions revoke that allowance. Wildcard,
unknown, renamed and runtime imports remain forbidden, and ordinary core source
checks retain their existing restrictions. Core compilation checks prove the
metadata is available without the plaintext payload runtime.

Validation through `nix develop --no-write-lock-file --command`:

- `node --test misc/core-purity-tests.mjs`: all 10 tests pass, including the
  existing JavaScript guard tests and three metadata boundary regressions.
- `node scripts/check-core-purity.mjs`: 12 core files and 35 machines, no violations.
- `sbt --batch --supershell=false -Dsbt.server.autostart=false 'core/test'
  'spoonbill/testOnly *Sensitive*Spec'`: 40 core and 64 sensitive tests pass.
