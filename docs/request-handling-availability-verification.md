# Request-handling remediation verification

Local verification on 22 October 2026 against the report baseline
`7d2b739cc8b1181dea72c2c380749198852cb55f`. Dependencies, builds and tests use
the repository-local `flake.nix`; no host packages were installed.

## Finding disposition

| Finding | Result in this PR | Remaining work |
| --- | --- | --- |
| SB-01 static cache | Finite entry/byte/resource/concurrent-read limits; no negative cache; failure/cancellation recovery tested | Deployment connection quotas, response lifetimes and actual heap sizing |
| SB-02 retained authentication history | Safe retirement with permanent generation/epoch floors, nonrepeating IDs, protected cookie issuance, quotas, finite sessions, owner leases, bounded local history and transition warnings | Repeated cycles beyond tiny limits, active keeper, stale capabilities/cookies, restart/backward clock, overlapping nodes, held/delayed transactions, rollback and V6 migration tests pass |
| SB-03 static containment | Strict public namespace plus directory real-path and exact JAR-entry checks; traversal and symlink corpus tested | Verify deployed proxy normalization and immutable classpath packaging |
| SB-04 WebSocket buffering | Bounded standalone decoding, Pekko/Akka aggregation and ZIO pre-conversion/application queues; independent assembly deadlines; socket/pool cleanup | Paused-consumer real-socket stress, close/cleanup witnesses, UTF-8/fragment/timeout cases, subsequent healthy connections and binary/source/POM/classpath audit pass |
| SB-05 response headers | Bounded device IDs, deterministic cookie parsing, output header validation and failed-response cleanup; raw TCP tests | Verify any other deployed adapter/proxy configuration |

All five findings are implemented for the built-in paths in this matrix. This
does not certify an uninspected deployment. See the [implementation plan](request-handling-availability-plan.md),
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

**Pass:** compilation, test compilation and all **1,159 Scala tests** passed;
zero failures, canceled, ignored or pending tests. This includes existing authority,
one-use, late-writer, replacement, restart, ownership and transport lifecycle
regressions, plus the new bounded-resource tests. PostgreSQL runs on loopback in a
temporary owned directory and is stopped/removed by the existing wrapper.

Completion commits: `54cb48c5` (retention/migration/monitoring), `718c1e26`
(bounded ZIO runtime and deadlines), and `c9fb184f` (response/socket cleanup and
deterministic TLS), extending the earlier static/header fixes.
These are unreleased source changes; this PR publishes no package or deployment.

Additional checks:

| Command | Result | Evidence |
| --- | --- | --- |
| `nix develop --no-write-lock-file --command node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs misc/replacement-bootstrap-tests.mjs misc/core-purity-tests.mjs misc/browser-auth-baseline/*.test.mjs` | Pass | 103 tests, no failures or skips |
| `nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs` | Pass | Nine checked files; no boundary violations |
| `nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check` | Pass | 17 runtime statements/10 tables; frozen V4/V5 unchanged and forward V6 matches runtime DDL |
| `nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh` | Pass | Durable view, sensitive disclosure and departure fixtures in Chromium and WebKit |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-browser-auth.sh` | Pass | Real memory-provider browser login, cookie delivery, protected reload, replacement, logout and lost-response recovery in both engines |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | Pass | Same real-browser workflow with disposable PostgreSQL; reused the freshly compiled example classpath |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh` | Pass | Actual ZIO HTTP authentication, event ordering, reconnect, disclosure and replacement in Chromium/WebKit |
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

The final full run also replaces a public-DNS-dependent HTTPS test with a bounded
local TLS fixture, validating its test certificate through a scoped trust store.
No certificate validation is disabled globally. Native memory, PostgreSQL and
ZIO browser authentication runs all passed on the final implementation.

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

The lifecycle tests repeatedly exceed tiny capacities while retaining an active
authenticated keeper. They prove safe framework history reclamation and preserve
application-owned host proof/audit records. Capacity alerts are emitted through
the configured reporter and callback; deployment-specific alert routing remains
an application responsibility.

No production system, production proxy, or production secret was accessed. These
tests establish framework payload/state bounds, not a measured whole-process
heap or production traffic threshold.

## Current ZIO documentation and artifact verification

Official releases checked on 22 October identify
[ZIO 2.1.27](https://github.com/zio/zio/releases/tag/v2.1.27) and
[ZIO HTTP 3.11.6](https://github.com/zio/zio-http/releases/tag/v3.11.6).
Core/streams/test dependencies now use 2.1.27. HTTP was already current; its
[tagged ingress implementation](https://github.com/zio/zio-http/blob/v3.11.6/zio-http/jvm/src/main/scala/zio/http/netty/server/ServerInboundHandler.scala#L316-L343)
still creates the internal unbounded queue, so an ordinary version bump does not
remove the need for the derived runtime.

The integration follows the documented
[WebSocket configuration](https://ziohttp.com/reference/server/#websocket-configuration)
and [Close frame API](https://ziohttp.com/reference/socket/websocketframe/).
One explicit failure Close goes through the normal outbound path before terminal
publication, with a finite forced-close timeout. Decoder/UTF-8 validation remain
enabled. The socket test requires the server's `awaitShutdown` witness before
opening the paused consumer gate or closing the client socket. No application
output or duplicate Close is allowed; EOF/reset is terminal transport evidence
only alongside that positive witness. Cleanup runs once and a later connection
remains usable.

The derived runtime packaging audit passed:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'zioHttpRuntime/packageBin' 'zioHttpRuntime/packageSrc' 'zioHttpRuntime/makePom' \
  'export zioHttp/Runtime/fullClasspath'
nix develop --no-write-lock-file --command node misc/check-zio-http-runtime.mjs \
  --jar interop/zio-http-runtime/target/scala-3.3.8/spoonbill-zio-http-runtime_3-5.0.0-SNAPSHOT.jar \
  --pom interop/zio-http-runtime/target/scala-3.3.8/spoonbill-zio-http-runtime_3-5.0.0-SNAPSHOT.pom \
  --sources interop/zio-http-runtime/target/scala-3.3.8/spoonbill-zio-http-runtime_3-5.0.0-SNAPSHOT-sources.jar \
  --classpath-file interop/zio-http/target/streams/runtime/fullClasspath/_global/streams/export
```

It checks pinned upstream hashes, tracked/published patch sources, replaced
classes, licenses/attribution, POM dependency scopes/classifiers, the reviewed ZIO
override and exact resolved versions, and one derived handler on the actual
classpath. CI runs the same audit.

## Rollout requirements

Apply V6 after draining older writers. Preserve its encrypted issuance key,
counter, generation/epoch floors and durable clock. Older binaries cannot safely
resume a reclaimed scope. Schedule coordinator maintenance and configure alert
routing as documented. ZIO consumers must retain the derived runtime and exclude
duplicate original jars. These are explicit deployment contracts, not remaining
implementation gaps. Fleet connection quotas, application-owned retention and
production proxy/memory settings remain deployment responsibilities.
