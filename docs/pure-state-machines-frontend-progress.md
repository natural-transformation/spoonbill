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
