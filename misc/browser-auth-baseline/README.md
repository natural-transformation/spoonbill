# Browser authentication v3 reference baseline

This unpublished project implements the reference work required before the
browser-authentication plan's broad API changes. Its starting library revision
is `ab95a4fe65399814931c6ddd7f29dd68f33bbc5c`. It does not implement or certify the
proposed 4.0 coordinator, pure-core extraction, new providers or release gates.
See [validation and remaining gates](validation.md) for the current checks,
captured inventories and why Phase 0 remains open.

The application, host glue, SQL, ownership storage, completion material and
transport wrappers are visible here and count toward consumer integration code.
None is added to the published runtime's compilation sources. The root build
aggregates `browserAuthBaseline`, while backend correctness fixtures compile in
the existing `spoonbill` and `securityJdbc` test configurations.

| Reference component | Contract |
| --- | --- |
| [Memory host](memory/README.md) | One serialized staged atomic domain; Future/Direct settlement, challenge association, one-use protected mutation and fail-closed process restart. |
| [Shared applications](app/README.md) | Complete Scala typed forms, ordinary protected actions, guarded HTTP, real cookie delivery, reconnect and sign-out through the official Pekko adapter for both providers. No application authentication JavaScript. |
| [JDBC host](jdbc/README.md) | Real PostgreSQL host/browser/material/audit preparation; existing outer runner integration; status reconciliation and protected credential recovery. |
| [Client responsibility inventory](client-responsibilities.md) | Existing Scala/browser responsibilities and concrete duplicated wire definitions. No removed client code or size improvement is claimed. |
| [Measurement contract](performance/README.md) | Enumerated workloads, strict evidence validation and bounded process collection. Missing measurements or budgets cannot pass. |

The browser correctness evidence covers password-only and challenged login for
memory and JDBC in both Chromium and WebKit, original challenge identity, HttpOnly cookies,
fresh physical handshakes, protected increments and HTTP, logout, stale-cookie
denial and exact-origin rejection. These are correctness observations, not
latency or memory measurements. Memory identity changes intentionally use v3's
terminal reload path; that extra HTTP/bootstrap work belongs in the baseline's
exchange counts.
The JDBC suite additionally kills a real JVM after committed preparation and
before delivery, then recovers the same attempt from a new JVM without proof replay.

All commands run from the repository using its `flake.nix`:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly spoonbill.browserauthbaseline.*' \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcReferenceHostSpec' \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserSecuritySpec' \
  'browserAuthBaseline/compile'

nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/*.test.mjs \
  misc/browser-auth-baseline/performance/*.test.mjs

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/test-browser-auth-baseline.sh

env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
  nix develop --no-write-lock-file --command bash scripts/with-test-postgres.sh \
  env SPOONBILL_AUTH_BASELINE_PROVIDER=jdbc bash scripts/test-browser-auth-baseline.sh
```

The browser wrapper owns a loopback JVM and closes it on every exit. It compiles
through the default Nix shell, then uses the pinned browser shell without
downloading browsers. An explicitly compiled one-line classpath file may be
passed as its sole argument for isolated verification. Report which sources
were compiled when using that mode. CI uses the complete project's classpath.

Capture inventories only after compiling the final reference revision:

```sh
nix develop --no-write-lock-file --command node \
  misc/browser-auth-baseline/inventory.mjs /tmp/spoonbill-v3-inventory.json
nix develop --no-write-lock-file --command node \
  misc/browser-auth-baseline/client-inventory.mjs \
  modules/spoonbill/target/scala-3.3.7/resource_managed/main/static/spoonbill-client.min.js \
  /tmp/spoonbill-v3-client-inventory.json
```

Reports are created exclusively, never overwritten. Source accounting includes
all nonblank physical lines, comments included; production helpers are not
excluded by filename. Emitted and compressed bytes count generated output.
The script/Scala embedding scan is an aid to source review and does not prove
application completeness or runtime behavior.

Phase 0 remains open until full reviewed workload equivalence, immutable reference
artifacts, measured operation/resource budgets
and all required collectors are complete. The checked-in performance manifest
deliberately remains `awaiting-reference-review`; its analyzer reports
`inconclusive`. No synthetic test result can fill a real measurement, and no
inconclusive cell permits the zero-regression release gate to pass.
