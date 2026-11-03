# Concurrent guarded authorization: local implementation review

Updated: 2026-11-03, after reviewing the fixes to the three original findings.

Scope: the uncommitted concurrent-authorization implementation against baseline `eb2a1c87`, including the coordinator, authentication integration, JDBC transactions, clock renewal, and relevant tests. The [design](concurrent-authorization-design.md) supplied the intended behavior.

## Assessment

All three original P2 findings are resolved in the current working tree. No new correctness finding was identified in the follow-up review of these changes.

The rerun passed **160 repository tests plus the original temporary clock regression**. The permanent IO integration regression now verifies cleanup under cancellation. One nonblocking test-coverage limitation remains for the exact former clock-renewal race, described below.

## Resolved findings

### Resolved: [P2] Cancellation could skip deferred detach

Location: [`BrowserAuthentication.scala`](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala), lines 198–206.

**Original problem:** The continuation that scheduled cleanup ran outside `gate.read`'s cancellation mask. Pending cancellation could skip detach after a denied check, leaving provider ownership allocated and the old presentation lease readable. The original reproduction observed `(ownedViews, oldPresentationReadFailed) == (1, false)` instead of `(0, true)`.

**Fix verified:** An outer `F.uncancelable` now covers the read result and its cleanup settlement. The read turn still releases before the cleanup write turn is acquired, preserving the required ordering without a self-deadlock.

**Evidence:** The new [`BrowserAuthenticationIOSpec`](../interop/ce3/src/test/scala/spoonbill/auth/BrowserAuthenticationIOSpec.scala) passed. It checks that ownership is released and presentation invalidated before explicit close or maintenance. Accepting either a denial or cancellation outcome is appropriate: the required invariant is completed cleanup and no successful authorization. This permanent test supersedes the earlier temporary test, which required the specific `Canceled` outcome.

### Resolved: [P2] Background renewal compounded future time across restarts

Location: [`JdbcBrowserClock.scala`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserClock.scala), lines 89–100.

**Original problem:** At fixed wall time `t`, successive instances could checkpoint `t+1`, adopt it and checkpoint `t+2`, then observe `t+2`. Background renewal extended the adopted future observation floor, so repeated restarts could exceed the documented one-second early-expiry bound.

**Fix verified:** `renewAhead` now bases its decision and target on raw wall time. An adopted future checkpoint is not extended until wall time approaches it.

**Evidence:** The original temporary clock regression now passes unchanged. The new permanent test in [`JdbcBrowserClockSpec`](../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcBrowserClockSpec.scala), lines 281–302, also passes and covers repeated restarts at fixed time followed by wall-clock catch-up.

### Resolved: [P2] Background renewal used a sample taken before locking

Location: [`JdbcBrowserClock.scala`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserClock.scala), lines 90–94.

**Original problem:** A foreground renewal could complete after the background caller sampled time but before it acquired the renewal lock. Renewing with the stale sample could then adopt the newer stored lease as observed time, advancing expiry without a restart.

**Fix verified:** The nonblocking renewal lock is now acquired before reading the acknowledged checkpoint and sampling wall time. The renewal decision and checkpoint write remain under that lock, preventing a foreground renewal from overtaking the decision.

**Evidence:** Source inspection confirms the problematic interleaving is eliminated. The clock suite passes, including the existing test that background renewal does not wait for a foreground renewal already in progress. The exact former between-sample-and-lock race is not forced by a deterministic regression test.

## Nonblocking test-coverage note

In [`JdbcBrowserClockSpec`](../modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/JdbcBrowserClockSpec.scala), the `renewAhead()` call at line 268 consumes the demand flag. The additional call at line 274 therefore returns before inspecting the checkpoint or acquiring the lock. That assertion does not itself establish the lease-recheck behavior described in its comment.

The implementation fix is sound. For stronger coverage, establish fresh demand before asserting that a completed foreground renewal is not repeated; reproducing the exact former race would additionally require controlled interleaving around the sampling boundary. This is a coverage improvement, not an outstanding correctness finding.

## Verification

All dependency-backed checks ran through the repository's Nix environment. JDBC checks used the repository's disposable, loopback-only PostgreSQL harness.

### Coordinator and authentication suites: pass

The following command passed 50 coordinator/authentication tests and 4 Cats Effect tests, including the new cancellation integration regression:

```sh
nix develop --no-write-lock-file --command sbt \
  'spoonbill/testOnly spoonbill.auth.BrowserCoordinatorGateSpec spoonbill.auth.BrowserAuthenticationFailureSpec spoonbill.auth.BrowserAuthenticationSpec' \
  'ce3/testOnly spoonbill.auth.BrowserCoordinatorGateIOSpec spoonbill.auth.BrowserAuthenticationIOSpec'
```

### JDBC suites and original clock reproduction: pass

The following command passed 106 repository JDBC clock/persistence tests and the original temporary clock regression. The latter previously failed with two seconds of future time at a fixed wall clock and now passes unchanged:

```sh
nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh sbt \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserClockSpec spoonbill.security.jdbc.JdbcBrowserPersistenceSpec' \
  'set securityJdbc / Test / unmanagedSourceDirectories += file("/private/tmp/spoonbill-review-clock")' \
  'securityJdbc/testOnly spoonbill.security.jdbc.ConcurrentClockReviewSpec'
```

The extra source is [ConcurrentClockReviewSpec.scala](/private/tmp/spoonbill-review-clock/ConcurrentClockReviewSpec.scala), outside the repository. The final two sbt arguments depend on that temporary file remaining available; permanent restart coverage is also present in `JdbcBrowserClockSpec`.

### Plain-file check: pass

```sh
git diff --check
```

No whitespace errors were reported.

## Limits

Only this review document was edited during the follow-up; implementation and test sources were left unchanged. Browser fixtures, full-repository checks, and performance benchmarks were not rerun. The exact former clock sampling race remains validated by source inspection rather than a deterministic reproduction. Line references describe the implementation inspected on 2026-11-03 and may move as it changes.
