# Request-handling remediation verification

Local verification on 22 October 2026 against the report baseline
`7d2b739cc8b1181dea72c2c380749198852cb55f`. Dependencies, builds and tests use
the repository-local `flake.nix`; no host packages were installed.

## Finding disposition

| Finding | Result in this PR | Remaining work |
| --- | --- | --- |
| SB-01 static cache | Finite entry/byte/resource/concurrent-read limits; no negative cache; failure/cancellation recovery tested | Deployment connection quotas, response lifetimes and actual heap sizing |
| SB-02 retained authentication history | Nonallocating HTTP principal inspection, denied-bootstrap protection, usage and warning metrics | **Open:** sustainable retained-history lifecycle, shared bootstrap/ceremony admission controls, configured alerts and rehearsed host-aware cutover |
| SB-03 static containment | Strict public namespace plus directory real-path and exact JAR-entry checks; traversal and symlink corpus tested | Verify deployed proxy normalization and immutable classpath packaging |
| SB-04 WebSocket buffering | Standalone decoder, Pekko/Akka aggregation, ZIO decoder and application queue bounds; overflow cleanup tested | **Open for ZIO:** dependency-owned unbounded queue before adapter. Deployment connection quotas and slow-input controls also required |
| SB-05 response headers | Bounded device IDs, deterministic cookie parsing, output header validation and failed-response cleanup; raw TCP tests | Verify any other deployed adapter/proxy configuration |

This PR does not certify an enterprise deployment or claim that all five findings
are closed. See the [implementation plan](request-handling-availability-plan.md),
[static budgets](request-static-resources.md), [admission contract](request-admission-lifecycle.md),
[transport matrix](request-transport-limits.md), and [header contract](request-header-safety.md).

## Commands and results

The complete Scala build and test command, including real disposable PostgreSQL:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false \
  -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test
```

**Pass:** compilation, test compilation and all **1,116 Scala tests** passed;
zero failures, canceled, ignored or pending tests. This includes existing authority,
one-use, late-writer, replacement, restart, ownership and transport lifecycle
regressions, plus the new bounded-resource tests. PostgreSQL runs on loopback in a
temporary owned directory and is stopped/removed by the existing wrapper.

Implementation commits: `b535fc59` (HTTP admission/metrics), `54fd112e` (header
safety), `60b7b76d` (transport bounds), and `48412389` (static cache/containment).
These are unreleased source changes; this PR publishes no package or deployment.

Additional checks:

| Command | Result | Evidence |
| --- | --- | --- |
| `nix develop --no-write-lock-file --command node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs misc/replacement-bootstrap-tests.mjs misc/core-purity-tests.mjs misc/browser-auth-baseline/*.test.mjs` | Pass | 101 tests, no failures or skips |
| `nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs` | Pass | Eight checked files; no boundary violations |
| `nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check` | Pass | Runtime schema matches explicit baseline and upgrade migrations |
| `nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh` | Pass | Durable view, sensitive disclosure and departure fixtures in Chromium and WebKit |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-browser-auth.sh` | Pass | Real memory-provider browser login, cookie delivery, protected reload, replacement, logout and lost-response recovery in both engines |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | Pass | Same real-browser workflow with disposable PostgreSQL; reused the freshly compiled example classpath |
| `git diff --check` | Pass | No whitespace errors |

For a fresh checkout, omit the final classpath-file argument in the JDBC browser
command so its wrapper compiles the example itself.

Intermediate validation caught and corrected generic/indentation compilation
errors, old tests expecting HTTP to allocate bindings, a cached-status fixture
that did not actually construct its malicious status phrase, and a cancellation
test that incorrectly assumed a cancel request always wins a completed read.
The cancellation fixture now keeps the fiber alive at a cancelable boundary and
checks cleanup from the cancellation finalizer. No production cancellation logic
was weakened to make that test pass.

## Bounded local stress and scope

The static fixture executes 2,000 distinct misses through guarded and unguarded
routing and 1,000 aliases, asserting cache entries, payload bytes and active
reservations remain within deliberately tiny budgets. Saturation returns 503 and
capacity recovers after failure or cancellation. Directory and JAR canaries
exercise containment without reading private application configuration.

Transport tests cover exact UTF-8 byte boundaries, split surrogates, cumulative
fragments, advertised lengths before payload arrival, incomplete input deadlines,
Netty decoder rejection, queue saturation and socket/ownership cleanup. A real
Pekko socket advertises 65,535 payload bytes but sends only five against a four-byte
budget; rejection does not wait for the rest. Standalone rejection is also tested
over a real socket, including a finalizer that waits for the failed read.

No production system, production proxy, or production secret was accessed. There
is no measured whole-process heap/load threshold, deployment alert installation,
or proved security-history deletion policy. Those remain explicit release work.
