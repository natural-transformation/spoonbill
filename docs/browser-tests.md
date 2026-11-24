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

For the broader client and reference correctness checks run by
[Scala CI](../.github/workflows/scala.yml), use:

```bash
nix develop --no-write-lock-file --command node --test \
  misc/redesign-client-tests.mjs misc/sensitive-client-tests.mjs \
  misc/browser-protocol-tests.mjs \
  misc/browser-auth-schema-tests.mjs \
  misc/replacement-bootstrap-tests.mjs \
  misc/browser-auth-completion-loss-tests.mjs misc/release-ref-tests.mjs \
  misc/core-purity-tests.mjs \
  misc/browser-auth-baseline/*.test.mjs
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

Spoonbill 7.0.0's explicit `NoUpdate` behavior and 8.0.1's handling of obsolete
browser events are checked through the actual standalone Scala service and
assembled browser client:

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
| `Modify(identity)` | A patch is published and held. Confirm from the old delivered revision is discarded without running, and the connection stays open. After delivery, a current confirm runs once on the same connection. |
| State-changing `Modify` | As for `Modify(identity)`; the delivered patch also shows the new state before the current confirm. |
| Authority revocation after `NoUpdate` | Confirm is denied even without a publication. |

The browser receives a real disclosure and sends its normal acknowledgement.
An ordered, bounded output gate can withhold patch delivery while separate
loopback control requests observe publication, completed event counters and
discarded obsolete events (`Metrics.ObsoleteBrowserEvents`). A later control
request delivers the held patches without closing the connection.
The test submits the still-visible confirm form through native UI interaction;
it does not replay a captured action frame. The 250-second limit covers the whole
Node runner across both browsers and all eight cases. Individual control requests,
page actions and navigation have 10-second limits; each browser launch has a
15-second limit. Before starting the Node runner, the script allows 45 seconds
for the Scala server to become ready. Cleanup starts adapter-owned transport
shutdown before releasing response resources, closes the guard, and releases
held output and owned processes, including on failure.

## Renderer acceptance against the Scala service

The Avocet 2.0.0 renderer repairs (F2) are checked through the same standalone
service, its HTML5 server-side rendering and the assembled client:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop . --no-write-lock-file --command \
  bash scripts/test-renderer-acceptance.sh
```

Chromium and WebKit each load the server-rendered page, check the parsed nodes
against the server's ids, click once and check the first patch, then prove that
the current handlers run exactly once. The cases cover long element and
attribute names, fixed hash-collision witnesses, quote- and delimiter-bearing
data, and identity across the first patch, with default and preset ids. The
script then runs an injected name-truncation fault that the runner must
detect, and ends with the cases of long polling that resumes (below).
The native CI job runs it after the sensitive acknowledgement step; its first
Linux run passed with the preset-id defect as a recorded expected failure, and
the run after the fix passed every case.
[Renderer acceptance](renderer-acceptance.md) describes the assertions, bounds,
the preset-id client defect it found (now fixed) and the recorded runs.

## Long polling that resumes against the Scala service

`scripts/test-renderer-acceptance.sh` ends with
[`misc/long-polling-native-tests.cjs`](../misc/long-polling-native-tests.cjs),
on the same fixture, in its `resume` scenario: a page at `/orders/<stage>`
with a field, a button that moves the stage on, and one whose handler records
the stage that it was rendered for. An address can also name the stage in its
query, `/orders?stage=<stage>`, and bare `/orders` is stage 0. The fixture's idle timeout is 6 seconds
in this scenario, so a poll waits 3 seconds. Every poll of the page passes
through the test, which can fail all of them for a while, drop a DOM frame
after the server has answered it, or hold that answer. The test can also hold
a history event before it reaches the server, and the fixture can hold the
route change of a history event before its render (`/fixture/hold-route`).
The poll wait of the fixture is 3 seconds, so the deadline of a poll, which
also bounds a navigation, is 4.5 seconds. The cases that hold a navigation
on purpose use the scenario `resume-long`, the same page with an idle timeout
of 30 seconds, so a navigation has 20 seconds, and a slow run does not reach
the bound while the case holds it. The scenario `resume-unicode` is the
`resume-long` page at `/café/<stage>`, a URL that the server writes as it is
and the browser encodes.
The page has no WebSocket, so it uses long polling, except in the cases that
test a WebSocket. In Chromium and WebKit:

| Case | What must hold |
| --- | --- |
| `lost-frame` | A DOM frame is lost: the page stays ready, keeps its bridge, gets the lost frame and the frame after it, and does not reload |
| `stale-dropped`, `stale-held` | The DOM patch is lost, or held, and the user clicks before the retry: no handler runs. After the catch-up the click runs the handler of the new stage |
| `kept-input` | After the catch-up a field that the patch did not touch keeps its focus and its unsent input |
| `back` | Back is pressed while a patch is held, and the history event leaves before the patch arrives: the page ends at the route that Back went to, without a reload |
| `view-ends` | Every poll fails for longer than the idle timeout: the page reloads once and works |
| `lost-state-lp`, `lost-state-ws` | The stored state of a page at `/orders/42` is gone before it connects, by long polling and by WebSocket: the page reloads once, shows its route, and a click runs its handler |
| `pause-heartbeat` | With the heartbeat on and its replies held, the page polls again at the limit, the next heartbeat is taken, and the page calls `Spoonbill.disconnect()`: it does not reload, keeps what the user typed, and works again after `Spoonbill.connect()` |
| `back-pause` | Back is pressed while long polling is paused: `Spoonbill.connect()` reloads the page at the URL that Back went to, and a click runs the handler of that route |
| `ws-connect` | `Spoonbill.connect()` on an open WebSocket: no second socket opens, the bridge is not built again, and a click runs one handler |
| `ws-connect-decoding` | `Spoonbill.connect()` while the close of a WebSocket waits for its last frame to decode: no socket opens until the close has run, the bridge is built again, and a click goes out once |
| `back-listener-pause` | Back is pressed and the application's own `popstate` listener, which runs first, calls `Spoonbill.disconnect()`: `Spoonbill.connect()` reloads the page at the URL that Back went to, and a click runs the handler of that route |
| `eval-pause` | A command whose code calls `Spoonbill.disconnect()` returns its result during the pause: after `Spoonbill.connect()` the server gets the result once, well before its RPC timeout, and the page keeps what the user typed |
| `history-click`, `history-click-pause` | Back is pressed, and its history event is held before it reaches the server. With `-pause`, the application's own `popstate` listener, which runs after Spoonbill's, calls `Spoonbill.disconnect()`, and the page calls `Spoonbill.connect()`: the history event is healthy, so the page resumes. A click on the old display runs no handler, also once the history event has arrived. The page reaches the route of Back, and once it has applied the completion of the navigation a click there runs its handler once. The page does not reload and keeps what the user typed |
| `history-route-held`, `history-route-held-ws` | The server has taken the history event of Back, and the fixture holds the route change before its render, on long polling and on a WebSocket: a click on the old display runs no handler. Once the route is shown and its completion applied, a click runs its handler once, and the page does not reload and keeps what the user typed |
| `history-reconnect-ws` | The fixture holds the route of Back on a WebSocket, and the socket closes: the page reloads at the URL of Back instead of connecting again. A click on the old display while the reload waits runs no handler, and after the reload a click runs the handler of the route of Back once |
| `history-canonical-url`, `history-canonical-url-ws` | The page opens at `/orders/0001`, an alias of stage 1, goes on to stage 2 and then Back, on long polling and on a WebSocket: the URL that the server sends with the navigation, `/orders/1`, takes the entry of Back without adding one. A click runs the handler of stage 1 once, and Forward and Back still reach their stages without a reload |
| `history-query`, `history-query-ws` | The page opens at `/orders?stage=1`, goes on to stage 2 and then Back to that first entry, which has no history state, on long polling and on a WebSocket: the page sends the path and the query of the entry, reaches stage 1, and the server's URL of stage 1, `/orders/1`, takes the entry without adding one. A click runs the handler of stage 1 once |
| `history-disconnect-ws` | In the scenario `resume`, whose bound of 4.5 seconds the case lets pass, the fixture holds the route of Back on a WebSocket, and the page calls `Spoonbill.disconnect()`; its socket closes more slowly than that bound: the page does not reload until `Spoonbill.connect()`, which reloads it at the URL of Back, and after the reload a click runs the handler of that route once |
| `history-unicode` | In the scenario `resume-unicode`, two stages on and then Back: the render of the route of Back sends one URL frame, so the number of the tab's entries stays, also with the client of PR 39 at `3abb1412`, which adds an entry for every URL frame whose raw form differs from the address bar. A click runs the handler of stage 1 once |
| `history-back-forward` | Back, then Forward while Back's history event is held: Forward's is posted only after the answer to Back's. A click while the page waits runs no handler, and only the completion of Forward reopens the input. Two later Backs reach the entries before Forward's, the tab's history has no entry that the server added, and the page does not reload |
| `history-same-screen` | Back to an entry whose route leaves the screen as it is, while its history event is held: a click on that screen runs no handler. After the completion a click runs the handler of the route once |
| `history-hung-post` | Back, and its history event never gets an answer: the page gives it up at the deadline of a poll and reloads at the URL that it shows. A click while it waited runs no handler |
| `history-unconfirmed` | Back, the server takes the history event, and the fixture never publishes the route: the page reloads at the URL that it shows when the deadline of a poll has passed. A click while it waited runs no handler |
| `history-lost-answer` | Back, then Forward while Back's history event waits; the server takes Back's event and confirms it, and then the answer to its POST is lost: Forward's event goes out, the page reaches Forward's route without a reload, and a click there runs its handler once |
| `history-forward-fails` | Back, then Forward; Back's route is published while Forward's history event waits, and then Forward's POST fails: the URL of Back's route does not take Forward's entry, and the page reloads at Forward's URL. In Chromium the reloaded page shows Forward's route, and Back and Forward reach their entries after it. Playwright's WebKit does not finish a reload after Back and Forward in the same document, also on a static page without Spoonbill, so in WebKit the case checks the URL of the reload only |
| `history-before-capability` | Back before the page has read the first frame of its view, which says that the view confirms navigations, while the fixture holds the route of Back: a click on the old display runs no handler, and after the completion a click runs the handler of the route of Back once |
| `history-refused` | The server answers Back's history event with `410`, as when no view can take it: the page reloads at once at the URL that it shows, rather than after the bound, and a click there runs its handler |

Each case was proved by a mutation that it must catch, put in and taken out
again: a click from an older display that runs a handler (`stale-*`), a
failed poll that reloads (`lost-frame`, `kept-input`, `stale-dropped`), a
missing state that is built from the bridge request (`lost-state-*`), a reload
answer taken as a failed poll (`view-ends`), and an old history event that is
discarded (`back`). The last three fail on the client of PR 39 before its
review at `f55be0f2`: the paused page reloads, the resumed page keeps the old
route, and `connect()` opens a second socket. The `history-click`,
`history-click-pause` and `history-route-held` cases, and
`history-route-held-ws` as a probe, fail on the client of PR 39 at
`3abb1412`: the click on the old display runs the handler of stage 1. Without
that click they pass on that client. On that client `history-back-forward`
and `history-same-screen` fail too, as a click while the page waits runs a
handler, and so do `history-hung-post` and `history-unconfirmed`, as the page
never reloads. The `history-lost-answer`,
`history-forward-fails`, `history-before-capability` and `history-refused`
cases fail in Chromium on the first version of the history-ordering fix, as
its code review of 2026-11-24 found: Forward is never confirmed, the URL
becomes `/orders/1`, the click runs the handler of stage 1, and the page does
not reload in time. The `history-reconnect-ws`, `history-canonical-url` and
`history-canonical-url-ws` cases fail in Chromium and WebKit on the second
version, as its re-review of the same day found: once the socket had closed,
the page connected again and a click on the old display ran the handler of
stage 1, and the address bar kept `/orders/0001`. The `history-query`,
`history-query-ws` and `history-disconnect-ws` cases fail in Chromium and
WebKit on the third version, as its third review of the same day found:
Back reached stage 0, and the page reloaded before `Spoonbill.connect()`. On
that version `history-unicode` fails with the client of PR 39 at `3abb1412`,
in Chromium and WebKit: Back added an entry. With the client of this fix it
passes on that version too, so the mixed run shows it.

All the cases take about three minutes in both engines; the script stops
them after 600 seconds.

To run some cases, or more rounds, start the fixture as the script does and
set `SPOONBILL_LP_CASES` (keys from the table, comma-separated),
`SPOONBILL_LP_ENGINES` (`chromium,webkit`) and `SPOONBILL_LP_ROUNDS`.

A document that the test fulfils with `route.fulfill` loses its cookie and
its `sessionStorage` in WebKit, so the test never fulfils a document: to
remove a state before the page connects, it holds the client script instead.
The server sends the event counter of a handled click in a frame after the
patch, and drops a click that carries an old counter, on every transport. A
case that clicks one button twice therefore waits until the page has applied
that frame; without the wait the second click raced it on a fast runner.
Likewise a case uses a page only once it listens for clicks: on long polling
`Spoonbill.ready` turns true with the first answer, and the frame that makes
the page listen comes in a later one. Both waits were checked by holding
those frames for 600 ms, under which every case passes.

## Public authentication example and delivery loss

`scripts/test-browser-auth.sh` runs `misc/browser-auth-native-tests.cjs` against
the actual public example in Chromium and WebKit. Set `SPOONBILL_AUTH_PROVIDER`
to `memory` (the default), `io`, or `jdbc`; JDBC runs inside the disposable
PostgreSQL wrapper:

```bash
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh env SPOONBILL_AUTH_PROVIDER=jdbc \
  bash scripts/test-browser-auth.sh
```

Replacement delivery can use `/auth/complete` or checked `/auth/recover` when
the predecessor closes before sending its completion control. Cookie-loss
injection therefore captures one real 204/new HttpOnly cookie on either path.
Request-loss injection intercepts exactly three attempts on one path, preserving
the same completion handle when present; mixed paths or excess attempts fail.

After the positive fault witness, the fixture takes only its owned browser
context offline, explicitly aborts the held request while its page still exists,
closes that page, and restores the network. This prevents automatic fallback
from racing the controlled loss. A fresh page then exercises actual bootstrap
and activation. Binding/session cookies must survive teardown unchanged. A
missing completion control is accepted only with a witnessed predecessor close
captured before the fixture's teardown. The assertions retain predecessor denial,
one-time disclosure, exact delivery counts, held-cookie recovery without another
Set-Cookie, and exhausted-delivery refusal. No authentication frames or successful
server responses are fabricated.

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
