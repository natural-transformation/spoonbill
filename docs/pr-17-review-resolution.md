# PR #17 consolidated review resolution

This records three rounds of the 22 October 2026 security and Scala reviews,
against `2dfb7a87`, `56cff8c8`, and `3d2d757e`. All nineteen findings across the
three rounds are accepted; none is rejected. The source reviews remain user-owned local
documents and are not added to the PR by these changes. GitHub release setup
remains explicitly deferred to the user's final review.

## Third-round dispositions

| ID | Updated review finding | Resolution |
| --- | --- | --- |
| SEC3-1 | P2 client response headers have no aggregate bound/deadline and failure can strand a borrow | Bound the response prefix before concatenating and enforce an absolute deadline through header completion. Strict status/field/framing validation prevents malformed-response parser loops. Parse, EOF, timeout and request-write failures retire the connection. Canceled/truncated/close-delimited responses and `Connection: close` do not recycle. Failure publication does not await user request-body cleanup, which has bounded reserved notification capacity. Plain/TLS hostile-peer tests cover max-one pool recovery, exact caps with body tails, writer failures and blocked cleanup isolation. |
| SEC3-2 | P3 malformed or timed-out request bodies amplify error traces | Record expected framing/transport/deadline failures actually observed by the request body and propagate them without `ec.reportFailure` or a futile 500 response on the closed socket. They are silent by default, matching header rejection. Application failures still receive full reporting, even when they use the same exception classes. A raw-socket burst verifies zero reports for expected input failures and preserves three deliberately raised application errors. |
| SCALA3-1 | P2 lazy server starts share admission/stop state | Allocate dispatcher, parser and accept/stop/failure closures inside each effect execution. Cats Effect regressions reuse one start effect sequentially, run independent concurrent servers at quota one, and retry after a bind failure. |
| SCALA3-2 | P2 the public HTTP facade loses WebSocket upgrade input | Preserve public `HttpServer.apply` plus `WebSocketProtocol.upgrade` composition through a private one-use input capability. Ordinary body reads remain strictly framed; unframed HTTP stays empty. Explicit upgrade checks the represented handshake fields and absence of an HTTP payload before claiming input; handler completion seals the capability. External-package tests cover echo, zero length, ordinary reads/pipelining, malformed input, late/repeated claims and non-101 cleanup. |

The isolation-test instability was also investigated. EOF did not prove that a
connection still occupied admission: failed-read cleanup could finish the second
connection before the excess socket arrived, leaving a legitimate free slot. The
fixture now holds two independent asynchronous cleanup promises and positively
observes both admissions and cleanup entries before testing excess admission. It
also proves the first synchronous finalizer remains blocked when the second
deadline closes its socket. The 1,500 ms socket timeout is unchanged. The whole
standalone batch and three additional bounded repeats passed. No production
defect was established by this fixture failure.

### Client settings and scope

`HttpClient.create` adds trailing settings:

| Setting | Default | Contract |
| --- | --- | --- |
| `maxResponseHeaderBytes` | 16 KiB | Response status/header prefix including CRLF-CRLF; body bytes in the same read are excluded. |
| `responseHeaderTimeout` | 30 seconds | Absolute time after borrowing through complete headers; trickled progress cannot extend it. |
| `maxTotalConnections` | 1,024 | Per-client physical connections and outstanding cleanup across destinations; saturation refuses new physical connections. |
| `closeNotificationWorkers` | 2 | Bounded reserved cleanup workers; no user finalizer runs on the deadline scheduler. |

The per-address default remains eight connections. Pool wait and connection/TLS
establishment are outside the header deadline. It stops at complete headers and
does not time response-body consumption. Applications must consume or cancel
returned bodies and make their finalizers terminate to recover cleanup capacity.
HTTP, HTTPS, WS and WSS response headers share these bounds. TLS certificate and
hostname validation remain enabled.

Public upgrade callers need no new server entry point. A WebSocket key alone
does not expose transport bytes through an ordinary HTTP body. Explicit upgrade
checks the represented fields described in
[RFC 6455 section 4.2.1](https://www.rfc-editor.org/rfc/rfc6455.html#section-4.2.1).
An old positive fixture was corrected to include `Upgrade: websocket`; a separate
negative test covers its absence.

## Verification

Third-round aggregate compilation and all **1,304 tests** passed (1,263
ScalaTest and 41 ZIO Test), with no failures, canceled, ignored or pending cases.

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test \
  'zioHttpRuntime/packageBin' 'zioHttpRuntime/packageSrc' 'zioHttpRuntime/makePom' \
  'export zioHttp/Runtime/fullClasspath' 'export zioHttp/Test/fullClasspath' \
  'export browserAuthExample/Runtime/fullClasspath'
```

| Check | Result | Evidence |
| --- | --- | --- |
| Full command above | PASS | Includes 15 new client safety cases, exact response-cap/parser regressions, seven external public-upgrade cases, three Cats Effect server lifecycle cases, request diagnostics and the corrected isolation fixture. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command sbt --batch --supershell=false -Dsbt.server.autostart=false 'standalone/test' 'standalone/testOnly *StandaloneDeadlineIsolationSpec' 'standalone/testOnly *StandaloneDeadlineIsolationSpec' 'standalone/testOnly *StandaloneDeadlineIsolationSpec'` | PASS | All 39 standalone tests and three additional bounded isolation repeats, with unchanged socket timeouts. |
| `nix develop --no-write-lock-file --command node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs misc/replacement-bootstrap-tests.mjs misc/browser-auth-completion-loss-tests.mjs misc/core-purity-tests.mjs misc/release-ref-tests.mjs misc/browser-auth-baseline/*.test.mjs` | PASS | 113 cases, no failures/skips, including six deterministic completion-loss fixture regressions. |
| Runtime artifact audit (exact command in the first-round table) | PASS | Final patched-source manifest, binary, POM, attribution and single runtime provider match. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-sensitive-acknowledgement.sh` | PASS | Actual standalone service, Chromium/WebKit acknowledgements and identity/modify/revocation invalidation. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | Memory-provider authentication, replacement and forced recovery in both engines. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | PostgreSQL browser authentication, replacement, logout, reload and loss recovery in both engines. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command env SPOONBILL_AUTH_PROVIDER=io bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | Lazy IO provider, both engines and all recovery scenarios. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh interop/zio-http/target/streams/test/fullClasspath/_global/streams/export` | PASS | ZIO authentication, reconnect, disclosure and replacement in both engines/event orders. |
| `git diff --check` | PASS | No whitespace errors. |

Early checks caught fixture-only compilation mistakes, which were corrected.
The first aggregate run then rejected the incomplete positive WebSocket fixture
described above; the corrected fixture and explicit negative case pass in the
final aggregate. Validation did not weaken handshake or authorization checks.

The [first third-round CI run](https://github.com/natural-transformation/spoonbill/actions/runs/39838021482)
passed the build but exposed another native-browser fixture assumption in
JDBC/Chromium: the forced `lost-command` checkpoint required observing a completion
frame, although replacement may commit and retire its transport before sending
that frame. The browser had already received recovery 204. The fixture now arms
one exact predecessor transport and recognizes either its intercepted completion
or its witnessed upstream close. It drops any late completion after close,
requires exactly one fault and zero forwarded completions, and permits zero
observed completions only with that close witness. Setup and successor closures
cannot satisfy the checkpoint. All changed-cookie, predecessor-denial, bounded
delivery and one-time-disclosure checks remain. The six deterministic tests cover
both event orders, late frames, unrelated closures and duplicate controls. No
timeout or production authentication behavior changed. The complete memory, IO
and PostgreSQL browser flows passed again in Chromium and WebKit with this
fixture. The PostgreSQL run directly observed both an intercepted command and a
close with zero commands; both retained the full security assertions.

Local logs are `/tmp/pr17-round3-final-all.log`,
`/tmp/pr17-round3-isolation.log`, `/tmp/pr17-round3-focused-rerun.log`,
`/tmp/pr17-round3-js-final.log`, `/tmp/pr17-round3-artifact.log`, and
`/tmp/pr17-round3-browser-*.log`. They are temporary; committed tests and commands
are sufficient to reproduce the checks.

## Second-round dispositions

| ID | Updated review finding | Resolution |
| --- | --- | --- |
| SEC2-1 | P1 chunked request bodies can accumulate unbounded framing/payload | Stream chunk payload immediately instead of waiting for its advertised length. Bound size/extension lines and the complete trailer section before appending metadata. Apply an absolute body deadline; reject truncated/malformed framing and prevent an unread body from becoming the next request. Actual `/auth/complete` socket regressions verify early rejection at 37 bytes, incomplete-body timeout, oversized size-line rejection, and successful chunked delivery. |
| SEC2-2 | P2 ambiguous HTTP framing can disagree with a proxy | Reject every repeated/list-valued Content-Length, invalid/negative/overflowing lengths, mixed length/transfer encoding, repeated transfer encoding, and unsupported encodings before dispatch with 400 and connection closure. Requests without either framing field have an empty body; only the trusted upgrade adapter receives raw upgrade bytes. Test pipelined canaries and valid chunked trailers on real sockets. |
| SCALA2-1 | P2 shared sweep cadence skips process-local owner reconciliation | Reconcile local ownership independently during that provider's maintenance and when local claim bookkeeping reaches capacity. Only committed authoritative loss removes uncertain tuples. Preserve shared cadence for full sweeps and the absence of routine public-GET ownership scans. Lost acknowledgements, serial takeovers, peer-winning maintenance, rollback, and shutdown regressions pass. |
| SCALA2-2 | P2 failed pool creation recursively drains waiting borrowers | Launch every creation handoff through the existing parasitic-executor trampoline. No pool-owned executor or second waiter queue is introduced. Regressions cover 3,000 failures, direct factory throws, concurrent asynchronous failures, callback defects, zero remaining reservations, and a subsequent healthy borrow. |
| SCALA2-3 | P2 application finalizers block the shared deadline scheduler | Timers perform only strict physical socket closure. Server admission reserves bounded notification capacity before user handlers; cleanup runs separately and retains its reservation until both serving and cleanup settle. Two-socket tests hold synchronous/asynchronous finalization while another deadline closes its socket; saturation rejects additional connections and graceful shutdown waits for cleanup. |

Related inspection found that `ServerSocket.accept` retained every completed
connection's join effect indefinitely. Active-connection tracking replaces that
history so rapid completed or admission-rejected connections cannot bypass the
new cleanup bound. These are directly related lifecycle corrections, not
disagreements with either review.

### New standalone transport settings

Both `HttpServer` and `standalone.buildServer` expose these trailing settings:

| Setting | Default | Contract |
| --- | --- | --- |
| `requestBodyTimeout` | 30 seconds | Absolute time from parsed headers to complete fixed-length or chunked body framing; progress does not reset it. |
| `maxChunkLineBytes` | 1,024 bytes | Complete chunk-size/extension line, including CRLF. |
| `maxTrailerBytes` | 16 KiB | Total trailer section, including final CRLF. |
| `maxConnections` | 1,024 | Per-server admitted connections, including outstanding serving/cleanup work after physical closure. |
| `closeNotificationWorkers` | 2 | Workers for bounded close notifications; timers never run application finalizers. |

Payload is streamed with transport read-buffer bounds; there is no whole-chunk
aggregation or arbitrary maximum advertised chunk length. Applications must
still enforce their own total body limits. Slow/incomplete bodies are closed at
the configured deadline, including when an application delays reading. Complete
bodies do not time application work. Unread or canceled bodies allow the current
response to finish, then force closure rather than connection reuse.

The framing policy follows the message-boundary rules in
[RFC 9112 sections 6.3 and 7.1](https://www.rfc-editor.org/rfc/rfc9112#section-6.3).
Rejecting all repeated lengths is intentionally stricter than repairing equal
duplicates. Bounded valid extensions and trailers are parsed; routing/framing
trailers are rejected and accepted trailers are discarded, never merged into
the request headers. No deployed proxy configuration was tested.

## Second-round verification (`3d2d757e`)

Second-round production/test compilation and the full suite passed: **1,275
tests** (1,234 ScalaTest and 41 ZIO Test), with no failures, canceled, ignored,
or pending tests. All commands use the repository-local flake.

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test \
  'zioHttpRuntime/packageBin' 'zioHttpRuntime/packageSrc' 'zioHttpRuntime/makePom' \
  'export zioHttp/Runtime/fullClasspath' 'export zioHttp/Test/fullClasspath' \
  'export browserAuthExample/Runtime/fullClasspath'
```

| Check | Result | Evidence |
| --- | --- | --- |
| Full command above | PASS | Includes 88 JDBC cases, pool failure backlogs, raw framing/pipeline cases, actual chunked authentication, independent socket deadlines, bounded cleanup, late observer registration, and graceful startup/teardown. |
| `nix develop --no-write-lock-file --command node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs misc/replacement-bootstrap-tests.mjs misc/core-purity-tests.mjs misc/release-ref-tests.mjs misc/browser-auth-baseline/*.test.mjs` | PASS | 107 cases, no failures/skips. |
| Runtime artifact audit (exact Nix command in the first-round table below) | PASS | Final binary/source manifests, POM, attribution and single runtime provider match. |
| `nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs` | PASS | Nine checked files, no violations. |
| `nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check` | PASS | 18 statements/11 tables; no migration changes this round. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-sensitive-acknowledgement.sh` | PASS | Real standalone service, Chromium/WebKit acknowledgements, identity/modify/revocation invalidation and physical closure. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | Memory provider in both browsers, including replacement and forced loss/recovery scenarios. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | PostgreSQL provider in both browsers, including the previously failing replacement scenario. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command env SPOONBILL_AUTH_PROVIDER=io bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | Lazy IO provider in both browsers, including replacement and forced recovery. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh interop/zio-http/target/streams/test/fullClasspath/_global/streams/export` | PASS | ZIO authentication, reconnect, disclosure, replacement and recovery in both browsers/event orders. |
| `git diff --check` | PASS | No whitespace errors. |

The aggregate run's strengthened JDBC fixture includes a live local owner.
Across 100 sequential public GETs over 8,192 seeded rows per table, it still
executes 4,200 SQL statements, **zero local ownership reconciliation scans**,
zero repeated full sweeps, and zero repeated aggregate monitoring scans. Advancing
one shared interval triggers one sweep/sample across both peers. Median/p95/max
service times were 46.64/52.79/77.71 ms; the initial sampled request took 107.09 ms.
The same local measurement limits described below apply; this is not a production
throughput rating or comparative benchmark.

An intermediate aggregate attempt failed on the new fixture's `Future.never`
type annotation; correcting that test allowed the complete run above to pass.
The previous head's [CI browser job](https://github.com/natural-transformation/spoonbill/actions/runs/39812578345/job/119942487478)
failed on a different fixture assertion: JDBC/Chromium replacement succeeded via
`/auth/recover` with 204 and a ready successor socket, but the test waited only
for `/auth/complete`. The waiter now accepts either successful delivery endpoint
and still requires the new HttpOnly cookie, same logical view, fresh physical
socket, predecessor denial, one-time disclosure, and logout behavior. It logs
only the public delivery pathname. No timeout was increased or production
authorization weakened.

Current local logs: `/tmp/pr17-round2-final-all.log`,
`/tmp/pr17-round2-js.log`, `/tmp/pr17-round2-artifact.log`, and
`/tmp/pr17-round2-browser-*.log`. Original CI diagnostics are in
`/tmp/pr17-native-browser-failed.log`. These temporary logs are not required to
rerun the committed tests.

## First-round dispositions

| ID | Source finding | Resolution and regression evidence |
| --- | --- | --- |
| SEC-1 | P2 public GETs amplify JDBC work | Shared durable cadence reservations bound full sweeps and aggregate monitoring across nodes, restarts, failed admission, and backward clocks. Unsampled capacity checks do not take the realm lock. Authorization and exact admission remain fresh. Disposable PostgreSQL tests cover pressure recovery and 100 public GETs over 8,192 rows in each retained table. |
| SEC-2 | P2 separate Cookie fields bypass ambiguity checks | Akka, Pekko, and ZIO preserve every raw Cookie field, matching standalone. Raw TCP tests cover valid disjoint cookies, conflicting credentials, malformed fields, and absence of host callback on rejection. |
| SEC-3 | P1 WSS uses plaintext | WSS uses the TLS connection pool. Local certificate fixtures verify a working TLS WebSocket and no HTTP request or credentials reaching a plaintext trap. |
| SEC-4 | P1 HTTPS omits hostname verification | Client engines enable HTTPS endpoint identification before handshake using the original hostname. Secure pools distinguish peer names even at the same IP. Failed handshakes close the transport and release pool reservations. Tests trust both certificates, reject the wrong hostname for HTTPS/WSS, verify pool recovery, and cover hostname isolation on reuse. |
| SEC-5 | P1 standalone header accumulation is unbounded | Header accumulation is capped before concatenation, with a separate absolute header deadline. Defaults are 16 KiB and 30 seconds, configurable through both server entry points. Malformed request lines/fields fail before appending, preventing a missing-colon parser loop. Parser boundary/body-preservation and raw-socket malformed, oversize, idle, slow-progress, keepalive, and body/handler tests cover the change. |
| SEC-6 | P2 branch-controlled release build receives publishing secrets | Workflow resolves an immutable commit from main history, rejects manual branch dispatch and moved/unreviewed tags, gates publishing on a release environment, and audits runtime artifacts before importing the key. Repository/environment setup is deliberately deferred to the user's final review; see the required steps below. |
| SCALA-1 | P2 normal ZIO close loses admitted messages | Normal closure preserves FIFO data and appends one separately bounded terminal event. Overflow/protocol failure retains destructive cleanup. Tests include a delayed consumer with a full admitted queue and normal peer closure. |
| SCALA-2 | P2 frame observer misses buffered upgrade prefix | Reject an upgrade with an already buffered prefix, rather than installing an observer at an unknown parser position. Exercise the real HTTP-to-WebSocket decoder handoff and clean immediate post-101 traffic. Monotonic completion checks also prevent a delayed timer from admitting elapsed HTTP headers or WebSocket frames. See the scope qualification below. |
| SCALA-3 | P2 replacement operations omit owner-lease expiry | Validate the owner lease before invoking host work and recheck/renew under the established lock order after host work and during completion. Regressions cover expired completion and admission delayed beyond the lease, with zero host callbacks. |
| SCALA-4 | P2 memory usage reports zero anonymous bindings | Populate anonymous usage with the same predicate used for admission. Verify independent warning and exhaustion while global binding capacity remains available. |

## Scope qualifications and disagreements

There are no rejected findings in any round. Two qualifications matter when interpreting the
fixes:

- The Scala review reproduced observer misalignment in an embedded Netty channel,
  not a complete network handshake. Netty's default strict HTTP parser can reject
  eager WebSocket bytes before upgrade. The regression also exercises a real
  socket upgrade with a supported lenient parser configuration so a buffered
  prefix can reach the handoff. Normal clients that wait for HTTP 101 remain
  supported. This accepts the parser-alignment risk without claiming every
  default ZIO upgrade exposed it.
- The JDBC fixture measures local work and latency, not production throughput or
  a maximum safe traffic rate. The realm lock and exact admission checks remain;
  this change removes repeated maintenance/monitoring scans, not every database
  operation from public requests.

Implementation inspection also found the malformed-header loop and delayed-timer
completion races described above. They directly defeated the requested bounds
and are fixed with focused regressions. TLS handshake failure required releasing
the connection-pool reservation; hostname verification required separating pools
for different peer names even when their resolved IP addresses match.

## Release setup deferred to final review

The user explicitly requested that GitHub environment and secret setup be left
documented for final review. No environment, protection rule, or secret was
created, changed, retrieved, or deleted. Metadata inspection found no existing
environments and a repository-scoped secret named `GPG_PRIVATE_KEY`.

Before enabling releases, a repository administrator must:

1. Create the `release` environment with trusted required reviewers and prevent
   self-review. Review both the workflow revision and resolved source SHA before
   approval. Maintain reviewed, protected `main` history, disallow force pushes,
   and restrict release-tag creation, update, and deletion to release owners.
2. Configure environment deployment rules for branch `main` and the intended
   release tags (for example, `v*`). The rule applies to the workflow event ref,
   not the commit checked out later. Manual dispatch must run on `main`; release
   tags must resolve to a commit in reviewed main history.
3. Re-enter signing and publishing credentials directly as environment secrets:
   `GPG_PRIVATE_KEY`, `PGP_PASSPHRASE`, and the used `SONATYPE_CENTRAL_*` or
   `SONATYPE_STAGING_*` pairs. Existing secret values are write-only; they cannot
   be copied out by the API. Confirm the replacements, then remove repository
   or organization-level copies exposed to this repository.
4. Set the **environment** variable `RELEASE_PROTECTION_READY=true` only after
   verifying those controls. Until then, the proposed workflow exits before
   signing or publishing. This variable is an enablement latch, not a substitute
   for server-side protection.

A repository-scoped signing key remains accessible to another branch workflow
that omits the environment. YAML changes alone cannot close that exposure.
GitHub also creates an unprotected environment when a workflow first references
an absent name, so the administrator must configure it explicitly. See GitHub's
[environment management documentation](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments)
and [deployment protection reference](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments).
The resolver uses GitHub's documented [event SHA/ref semantics](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release).

## First-round verification (`56cff8c8`)

All commands run from the repository root with its local Nix flake. The final
aggregate run passed **1,237 tests**: 1,196 ScalaTest cases and 41 ZIO Test cases,
with no failures, canceled, ignored, or pending tests. Production and test
compilation both passed. The command also rebuilt the exact runtime artifacts
and exported classpaths reused by the browser checks:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'set Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)' \
  compile 'Test / compile' test \
  'zioHttpRuntime/packageBin' 'zioHttpRuntime/packageSrc' 'zioHttpRuntime/makePom' \
  'export zioHttp/Runtime/fullClasspath' 'export zioHttp/Test/fullClasspath' \
  'export browserAuthExample/Runtime/fullClasspath'
```

Additional checks:

| Command | Result | Evidence |
| --- | --- | --- |
| `nix develop --no-write-lock-file --command node --test misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs misc/browser-auth-schema-tests.mjs misc/replacement-bootstrap-tests.mjs misc/core-purity-tests.mjs misc/release-ref-tests.mjs misc/browser-auth-baseline/*.test.mjs` | PASS | 107 tests; no failures/skips. Release resolver tests use temporary Git repositories and reject branch dispatch, unreviewed commits, moved tags, malformed versions, and ref mismatches. |
| `nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs` | PASS | Nine files; no violations. |
| `nix develop --no-write-lock-file --command node scripts/export-browser-auth-schema.mjs --check` | PASS | 18 statements/11 tables; V4 and V5 frozen, unpublished V6 matches runtime including the cadence table. |
| `nix develop --no-write-lock-file --command node misc/check-zio-http-runtime.mjs --jar interop/zio-http-runtime/target/scala-3.3.8/spoonbill-zio-http-runtime_3-5.0.0-SNAPSHOT.jar --pom interop/zio-http-runtime/target/scala-3.3.8/spoonbill-zio-http-runtime_3-5.0.0-SNAPSHOT.pom --sources interop/zio-http-runtime/target/scala-3.3.8/spoonbill-zio-http-runtime_3-5.0.0-SNAPSHOT-sources.jar --classpath-file interop/zio-http/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | Patched classes, four-source manifest, full source archive, license/attribution, dependency POM, exact versions and single runtime provider. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | Memory-provider password/factor authentication, replacement, logout, protected reload and response-loss recovery in Chromium/WebKit. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc bash scripts/test-browser-auth.sh examples/browser-auth/target/streams/runtime/fullClasspath/_global/streams/export` | PASS | The same public authentication/replacement/loss-recovery flows against disposable PostgreSQL in Chromium/WebKit. |
| `env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-zio-http-browser-auth.sh interop/zio-http/target/streams/test/fullClasspath/_global/streams/export` | PASS | Actual ZIO HTTP authentication, reconnect, disclosure, replacement and recovery in Chromium/WebKit, in both native/programmatic event orders. |
| `git diff --check` | PASS | No whitespace errors. |

Intermediate runs found and corrected fixture argument errors and the generated
request round-trip test's insufficient header budget. The first aggregate run
also exposed an old logout success fixture that sent duplicate binding cookies.
It now sends one field; the original duplicate request is retained as a separate
403/no-cookie/healthy-keepalive regression. No production ambiguity check was
relaxed. The final aggregate command above passes after those corrections.

The signing/publishing workflow is not executed during testing. No artifact was
published and no production credentials were read. GitHub environment/secret
configuration remains deferred as explicitly requested.

## First-round measured JDBC work

The final `JdbcBrowserPersistenceSpec` cadence fixture seeded 8,192 rows in each
of the slot, view, and ceremony tables in disposable PostgreSQL. After two warmup
requests, it issued 100 sequential HTTP-service GETs, alternating between two
coordinators. These are in-process service calls against real PostgreSQL; timings
exclude network transit and full response-body consumption.

| Measurement | Result |
| --- | --- |
| Executed SQL statements for 100 measured GETs | 4,200 |
| Repeated full retention sweeps during those GETs | 0 |
| Repeated aggregate monitoring scans during those GETs | 0 |
| Median / p95 / maximum service latency | 47.62 / 53.60 / 56.08 ms |
| First sampled request | 145.94 ms |
| Two requests after advancing one shared interval | Exactly one sweep and one capacity sample |

Exact admission/authority reads still execute. The assertions test bounded
maintenance work rather than machine-specific latency thresholds. Separate tests
cover an already-held realm lock, restart/backward clock behavior, rolled-back
admissions, and recovery after the one-second pressure backoff. This is local
performance evidence, not a before/after throughput benchmark, production load
rating, or whole-process memory measurement.

Local evidence logs are `/tmp/pr17-review-final-all.log`,
`/tmp/pr17-review-js.log`, `/tmp/pr17-review-artifact.log`, and
`/tmp/pr17-review-browser-{memory,jdbc,zio}.log`; they are temporary and are not
required to rerun the committed regressions. For a fresh checkout, run the
aggregate command first to produce the classpath files used above.
