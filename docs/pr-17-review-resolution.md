# PR #17 consolidated review resolution

This consolidates the 22 October 2026 security and Scala reviews of commit
`2dfb7a879374c87958c439965cf012c58b964ee2`. The reviews contain ten distinct
findings. All ten are accepted, including the existing TLS, HTTP-header, and
release risks. No finding was rejected. The source reviews are user-owned local
documents and are not added to the PR by this change.

## Dispositions

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

There are no rejected findings. Two qualifications matter when interpreting the
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

## Verification

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

## Measured JDBC work

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
