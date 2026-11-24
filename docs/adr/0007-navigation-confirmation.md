# ADR 0007 — An unguarded view confirms a navigation once its route is published

Status: accepted on 2026-11-24 with the fix for a click that overtakes Back.

## Context

A click right after Back could run the handler of the route that the user
had left, in Chromium and WebKit, on long polling and on a WebSocket. Two
things let it happen:

- On long polling every message is a POST of its own. The click could reach
  the server before the history event of Back.
- The server handles history events and clicks in two fibers. Its history
  handling returned once the new state was written, before the new route
  was rendered and its handlers published. A click was checked against the
  handlers published at that moment: those of the route that the user left.

An answer of `200` to the history POST did not help: the click still ran
while the new route waited on the server. The page has to learn when the new
route is published. Guarded views already wait for a navigation signal of
their own; unguarded views had none.

## Decision

For an unguarded view that says that it confirms navigations (point 1), on
both transports. Guarded views are not changed: their pages already wait for
a navigation signal of their own, whose timing is investigated separately.

1. **Capability.** The first frame of the view, `ResetEventCounters`, carries
   `true` (`[17,true]`): the view confirms navigations. A page that gets `[17]`
   without it, from an older server, gates nothing. A navigation that the
   page makes before that first frame waits for it.
2. **Numbers.** The page and the view number the history events of the view
   in the order in which they are sent and taken: 1, 2, and on.
3. **Publication, then confirmation.** The render that publishes the route of
   navigation `n` sends the URL that its router defines for its state, if
   any, with the number: `[6,url,n]`, its only URL frame. It publishes its
   patch and its handlers, and then confirms with `[24,n]` (the procedure
   that completes a guarded navigation), before any later render sends a
   frame. A path that the router does not route changes nothing: the view
   confirms it at once, with no URL.
4. **The gate.** From a history event until its confirmation, the page drops
   its user events: DOM events and custom callbacks. History events,
   heartbeats and replies to the server go on. A later navigation takes the
   place of an earlier one: only the latest confirmation opens the gate.
5. **History entries.** While a navigation waits, the page skips the URLs
   that the server sends, except the one numbered with that navigation: it
   replaces the entry that the user chose. An entry without history state
   sends the path and the query that the location shows.
6. **Delivery on long polling.** A history POST waits for the answer to the
   one before it, so Back and Forward reach the view in order. Only a `200`
   counts as taken. A confirmation proves a navigation also when the answer
   to its POST is lost.
7. **Deadlines.** Two waits, each the deadline of a poll: the poll wait plus
   at most 5 s, 20 s by default. The first is for the answer to a history
   POST; the second for the confirmation, counted from that answer (on a
   WebSocket, from the history event). Only a pause, or a resume after one,
   gives more time.
8. **Recovery.** A page always reloads at the URL that it shows at that
   moment. That may already be the numbered URL of the navigation, which
   replaced the address that the user chose, such as an alias. A navigation
   that fails, gets no answer, or is not confirmed in time reloads the page.
   A paused page, and one that its user disconnected with
   `Spoonbill.disconnect()`, also while its socket still closes, reload at
   `connect()`. A page whose socket is lost while a navigation waits reloads
   instead of connecting again.
9. **A route that fails on the server,** or whose render fails, retires the
   view: it runs no more routes, handlers, callbacks, timers or delays. A
   denial counts as a failure unless the view is being torn down. A
   long-polling view then closes, and its next poll is told to reload; a
   WebSocket view sends the reload frame, and the reload ends the socket.
10. **Mixed versions.** The guarantee needs both sides. An older page
    ignores the flag, the confirmations and the number on the URL frame,
    gates nothing, and still gets at most one URL frame per render. A newer
    page that gets a legacy `[17]` from an older server opens its
    provisional gate and gates nothing; it still posts the history events of
    long polling one at a time, and still reloads after a refused history
    POST. So during a rolling deployment the old race of a click with Back
    can remain on a page and a view of two versions.

See the render and the history handling in
[`ApplicationInstance`](../../modules/spoonbill/src/main/scala/korolev/internal/ApplicationInstance.scala)
(`onState`, `confirmedHistory`), the turn in
[`ComponentInstance`](../../modules/spoonbill/src/main/scala/korolev/internal/ComponentInstance.scala)
(`updateFromHistoryRendered`, `NavigationTurn`), and the client rules in
[`bridge-machine.js`](../../modules/spoonbill/src/main/es6/bridge-machine.js)
and [`connection-machine.js`](../../modules/spoonbill/src/main/es6/connection-machine.js).
The tests that pin the rule:

- [`NavigationConfirmationSpec`](../../modules/spoonbill/src/test/scala/korolev/server/internal/services/NavigationConfirmationSpec.scala):
  the first frame, publication before confirmation, the numbered URL as the
  render's only URL frame, a state written while the route renders, the
  confirmation out before a later render while the history fiber waits, and
  failed or denied routes and renders.
- [`TimerApplicationSpec`](../../modules/spoonbill/src/test/scala/korolev/internal/TimerApplicationSpec.scala):
  no timer and no delay of a retired view.
- The Node tests of the
  [bridge machine](../../misc/bridge-machine-tests.mjs), the
  [connection machine](../../misc/connection-machine-tests.mjs), the
  [connection lifecycle](../../misc/connection-lifecycle-tests.mjs) and the
  [bridge](../../misc/redesign-client-tests.mjs).
- The `history-*` cases of
  [`misc/long-polling-native-tests.cjs`](../../misc/long-polling-native-tests.cjs),
  in Chromium and WebKit.

## Alternatives and consequences

**What is gained.** On a page and a view of this version, a click never
runs a handler of a route that the page does not show. Back and Forward reach
their entries, and the address bar shows the server's form of the URL. A
navigation that goes wrong ends with a reload at the URL that the page
shows, never at another route in silence.

**What it costs.**

- After Back, clicks do nothing until the confirmation: normally one round
  trip and one render.
- When something fails, up to about 40 s at the default on long polling,
  and 20 s on a WebSocket, before the page reloads. A navigation that waits
  behind one without an answer can add 20 s.
- A reload loses what the user typed: after a failed navigation, and after a
  socket lost during one.
- An application's own URL change while a navigation waits is skipped.
- During a rolling deployment, a page and a view of two versions keep the
  old race (point 10).

**Alternatives that were not taken.**

- **The server puts the events in order:** a click after a history event is
  checked only once that route is published. It needs a new ordering rule
  for every unguarded view, and a click on a route that leaves the screen as
  it is would run.
- **Keep the click and send it after the confirmation:** a bounded queue of
  clicks, which must never get the new revision.
- **Open the gate and go on after a failure:** the screen may stay on the old
  route under the new URL, and a click after a lost answer can still meet the
  old handler. Sending the history POST again was not taken either: the
  server may navigate twice, and the numbers drift apart.
- **A procedure of its own for the capability:** older clients log
  "Procedure is undefined" once per view.
- **Skip every URL while a navigation waits:** the tab keeps an alias, such
  as `/orders/0001`, that the server would write differently.
- **A URL frame of its own before the confirmation:** it could carry the URL
  of a state written after the render, and an older client took it as one
  more URL and added an entry.
- **Confirm from the history fiber:** a later render could send its frames
  before the confirmation, and the page would skip that render's URL.
- **Connect again after a lost socket:** the new view knows nothing of the
  navigation, and a click on the old display reached a handler.

## When to revisit, and what a redesign has to keep

Revisit this rule when one of these holds:

- The wait after Back shows: routes that take long to render.
- Guarded views get the same treatment. A separate investigation found
  their confirmation sent before the route's patch, with a delayed render;
  a wrong handler there is not shown yet.
- The deadlines need a setting of their own, or one bound for a whole
  navigation.
- A closed WebSocket stops ending a view (ADR 0006, direction 1): a socket
  lost during a navigation could then go on instead of reloading.

A redesign may change the frames and the waits. It has to keep four things:

1. No user event made on a display reaches a handler of another route.
2. A confirmation and its URL belong to the render that the page shows when
   it reads them: no later render comes between them.
3. A navigation that cannot be confirmed ends with a reload at the URL that
   the page shows, or waits for `connect()` when the user disconnected the
   page.
4. An older client gets at most one URL frame per render.
