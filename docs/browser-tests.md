# Browser client regression checks

Run from the Spoonbill checkout. Its root `flake.nix` and committed `flake.lock`
provide the environments and pinned browsers. No host application checkout,
JavaScript package installation or separate browser download is needed. The
source-only client fixtures use synthetic transport; the real-service sections
below start their own Scala server with synthetic in-memory state.

The fast Node suites run in the default development shell:

```bash
nix develop --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs misc/browser-protocol-tests.mjs
```

The separate `browser` shell provides Node, GNU timeout, the Playwright driver,
and its matching Chromium/WebKit bundle from the same locked nixpkgs revision:

```bash
nix develop .#browser --no-write-lock-file --command \
  bash scripts/test-native-browsers.sh
```

The native CI job runs that command on Linux. Each fixture runs both engines:

| Fixture | Coverage |
| --- | --- |
| `durable-view-native-tests.cjs` | Native custom-element reactions, durable-view callbacks and mounted URL handling. |
| `sensitive-native-tests.cjs` | Closed-root and ordinary DOM/RPC/event isolation, local QR drawing, lifetime/clear ordering, disconnect and navigation fences. |
| `sensitive-departure-native-tests.cjs` | Genuine canceled departure dialogs, same-socket recovery, stale barriers, replay suppression and fresh disclosure. |

The shell sets `PLAYWRIGHT_DRIVER_PATH`, `PLAYWRIGHT_BROWSERS_PATH` and
`PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`. On Linux it also sets
`SPOONBILL_PLAYWRIGHT_EGL_VENDOR` to the pinned Mesa vendor file. Only the owned
WebKit process receives that EGL setting and software rendering; host graphics
configuration is unaffected. The runner requires this configured vendor path
on Linux and fails clearly when it is missing.

On Linux the shell sets `PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS=1` because
Playwright's `/sbin/ldconfig` probe cannot discover libraries in the Nix closure.
The locked browser packages have patched runtime paths. Browser launch and all
native assertions still run; missing runtime libraries fail the job. Do not
install host packages to satisfy that distribution-specific probe.

The runner resolves sources relative to its own checkout, checks driver/browser
and Linux EGL availability before launch, and fails with a setup message when a
dependency is missing. Each fixture has a 120-second limit and a 10-second
termination grace period. Fixtures close owned browsers in `finally`; the
departure fixture also destroys its loopback sockets and closes its server.
Interrupting the runner signals only its active test process group. It does not
stop unrelated browsers or services.

## Sensitive acknowledgement against the Scala service

Spoonbill 7.0.0's explicit `NoUpdate` behavior is checked through the actual
standalone Scala service and assembled browser client:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop . --no-write-lock-file --command \
  bash scripts/test-sensitive-acknowledgement.sh
```

The runner compiles `SensitiveAcknowledgementBrowserFixture`, starts an owned JVM
on a loopback port selected by the server, then enters `.#browser` itself. It
requires no database, host account, or authentication proof. Chromium and WebKit
each run four cases:

| Completion or authority change | Expected next action |
| --- | --- |
| `NoUpdate` | Confirm runs once at the delivered revision, with zero new patches and the same live connection. |
| `Modify(identity)` | A patch is published; confirm from the old delivered revision is denied. |
| State-changing `Modify` | A patch is published; confirm from the old delivered revision is denied. |
| Authority revocation after `NoUpdate` | Confirm is denied even without a publication. |

The browser receives a real disclosure and sends its normal acknowledgement.
An ordered, bounded output gate can withhold patch delivery while separate
loopback control requests observe publication and completed event counters.
The test submits the still-visible confirm form through native UI interaction;
it does not replay a captured action frame. The browser process has a 250-second
bound and individual control requests have a 10-second bound. Cleanup starts
adapter-owned transport shutdown before releasing response resources, closes
the guard, and releases held output and owned processes, including on failure.

## ZIO HTTP transport

`scripts/test-zio-http-browser-auth.sh` serves a synthetic in-memory
browser-authentication page through `spoonbill-zio-http` and runs
`misc/zio-http-browser-auth-tests.cjs` in Chromium and WebKit. It covers cookie
delivery through `/auth/complete`, the authenticated reconnect, a sensitive
disclosure and then native and programmatic navigation in the same context. Run
it from the default shell; it starts the `.#browser` shell itself:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/test-zio-http-browser-auth.sh
```

The fixture submits an invalid password before the valid one, inserting an error
banner before the setup controls. It checks that the authentication reconnect
receives a full view reset before patches/readiness, that all old anonymous
controls are gone when `SpoonbillReady` fires, and that sensitive disclosure works.
A further same-identity reconnect must preserve authorized server presentation
and support another disclosure before both navigation modes are exercised.
Coordinator tests separately cover the same reset requirement with the real
JDBC exclusive provider, including restart and retained presentation.

Browsers may open new connections, so the strict same-connection proof is the
raw-socket `spoonbill.zio.http.ZioHttpKeepAliveSpec` suite.

All fixtures described here use synthetic data. In the source-only native
client suites, transport replies are supplied by the fixture; the departure
fixture also opens an ephemeral loopback HTTP/WebSocket listener. Those peers
model revalidation and do not establish host application authorization. The
acknowledgement and ZIO HTTP suites instead execute actual Scala service paths.
Framework Scala tests and host application integration checks remain separate
gates. Host applications remain responsible for validating their own
authorization and authentication behavior.
