# Browser client regression checks

Run from the Spoonbill checkout. Its root `flake.nix` and committed `flake.lock`
provide the environments; no host application checkout, application server, database,
JavaScript package installation or separate browser download is needed.

The fast Node suites run in the default development shell:

```bash
nix develop --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs
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

These fixtures use synthetic data. Most requests are fulfilled in-process; the
departure fixture opens an ephemeral loopback HTTP/WebSocket listener. Its peer
models revalidation and does not establish host application authorization.
Framework Scala tests and host application integration checks remain separate
gates. Browser CI verifies the library fixtures; host applications remain
responsible for validating their authorization and authentication behavior.
