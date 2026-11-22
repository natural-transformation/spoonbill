# ADR 0006 — A legacy attach needs its stored state

Status: accepted on 2026-11-22 as an interim rule with a known cost. To be
revisited; see the last section. The
[design of long polling that resumes](../long-polling-resume-design.md), which
came with this decision, has the full reasoning.

## Context

A legacy session, one without session access control, stores its state when
its page is rendered. A view is attached later, over a WebSocket or long
polling, and starts from that stored state. The first render of a legacy view
sends nothing to the browser: it assumes that the page shows the stored state.

Until this decision an attach that found no stored state built one from its
own request. That request is the bridge request,
`/bridge/web-socket/<session>` or `/bridge/long-polling/<session>/subscribe`.
No route of an application matches a bridge path, so the new state was what
the state loader gives, whatever address the page was rendered at. The attach
succeeded and nothing was sent. A page rendered at `/orders/42` got the view
of the default route, and a click ran a handler of a page that the browser did
not show. Two attaches could also build at the same time, and the slower one
replaced the stored state under the view of the faster one.

How often the state is missing depends on the storage:

| Storage | The state is missing |
| --- | --- |
| The built-in default storage | After a restart of the server. And for a state that was removed, once 5,000 later removals have pushed it out of the for-deletion cache |
| `StateStorage.fromRepository` | At every attach after a view has retired. Retirement removes the row, and when the connection of a legacy view closes, its watcher retires the view. So every reconnect after a closed WebSocket found no state |
| Any | When an attach reaches a server that has neither the state nor a shared repository |

Run on `main` at `82aa5dbd` on 2026-11-22, one run each. A router maps
`/orders/<id>` to its own state. A page is rendered at `/orders/42`, connects
with a WebSocket, loses its socket and connects again. With a repository the
row was gone after the socket closed, the reconnect succeeded, and its view
started from the default state. With the default storage the view started
from the state of the order.

One more thing is known about a repository and is not changed by this
decision. Its removal returns when the deletion has only started. A reconnect
that is faster than the deletion finds the row, and loses it under its view.

A guarded attach never takes a bridge path for the address of a page. When it
has to build a state, it uses the address that the page reports, or the one
that the render recorded. When it has neither, it answers with a reload. A
guarded view that recovers its view also starts by sending the whole
document.

## Decision

A legacy attach never builds a state. When the stored state is missing, the
server answers with the reload frame, on both transports and for every
storage. The page loads again, is rendered from its own address and gets a new
session. For a legacy session a page render is the only place that stores its
first state.

The server counts these reloads in
[`Metrics.MissingStateReloads`](../../modules/spoonbill/src/main/scala/korolev/Metrics.scala)
(`missing-state-reloads`), so that the cost of this rule can be seen in a
deployment.

Guarded sessions are not changed. The rule gives a legacy attach the answer
that a guarded attach already gives when it cannot know what the page shows.

See the [attach of a legacy session](../../modules/spoonbill/src/main/scala/korolev/server/internal/services/SessionsService.scala)
(`createLegacy`), the [storage](../../modules/spoonbill/src/main/scala/korolev/state/StateStorage.scala)
and the tests that pin the rule:

- [`MissingStateReloadSpec`](../../modules/spoonbill/src/test/scala/korolev/server/internal/services/MissingStateReloadSpec.scala):
  a page rendered at a route whose stored state is gone, the reconnect of a
  WebSocket page with a repository and with the default storage, two attaches
  without a state, and an attach that finds its state.
- The missing-view specs of the three adapters:
  [Akka](../../interop/akka/src/test/scala/AkkaMissingLocalViewReloadSpec.scala),
  [Pekko](../../interop/pekko/src/test/scala/PekkoMissingLocalViewReloadSpec.scala)
  and [standalone](../../modules/standalone/src/test/scala/korolev/server/StandaloneMissingViewReloadSpec.scala).
- In Chromium and WebKit, a page at a route whose state is gone before it
  connects, by WebSocket and by long polling: it reloads once and shows its
  route ([`misc/long-polling-native-tests.cjs`](../../misc/long-polling-native-tests.cjs)).

## Alternatives and consequences

**What is gained.** After an attach the page and its view agree, or the page
reloads. No view starts from a state that was built for a bridge request, and
no attach writes a first state under a running view.

**What it costs.** A reload that the user of a page sees, where the server
used to go on in silence with a view that might not match:

- With the default storage: after a restart of the server, and for a state
  that has left the for-deletion cache.
- With a repository: at every reconnect after a closed connection, and after
  an idle retirement.
- With several servers that share no repository and have no sticky routing:
  until the render of a page and its attach meet on one server. Such a
  deployment worked before only because every server built a state of its
  own.

A reload loses what only the session held, and what the user had typed. The
client limits how often a legacy page reloads on the word of the server: once
in 30 seconds for a tab. A second reload waits for the end of that time, with
the connection-lost notice (`reloadPage` in
[`connection.js`](../../modules/spoonbill/src/main/es6/connection.js), and
`reloadWait` in [`connection-machine.js`](../../modules/spoonbill/src/main/es6/connection-machine.js)).

The user accepted this cost on 2026-11-22. The wrong view gave no sign, and
with what the code can do today the reload is the only variant after which
the page and its view agree.

**Alternatives that were not taken.**

- **Keep building a state at the attach.** It is the defect.
- **Build the state from the address that the page reports,** as a guarded
  attach does. It gives the right route. What the session held is still lost,
  now without any sign, and the page is still trusted to match.
- **Apply the rule only to long polling, and to a WebSocket only with the
  default storage.** It leaves the wrong view where it is most common: on a
  repository, at every reconnect.
- **Keep the row when a view retires,** so that a reconnect finds it.
  `fromRepository.remove` returns when the deletion has only started, and one
  server cannot know whether another one holds a view of that row. A deletion
  at some later time would be a timer that races a reconnect. And a state
  that is found does not make the page right; see point 2 below.

## When to revisit, and what a redesign has to keep

Revisit this rule when one of these holds:

- A deployment with a repository, or one that restarts often, finds the
  reloads too costly. The counter `missing-state-reloads` says how often a
  deployment pays for this rule where the storage is asked: at a WebSocket
  attach, and at the first poll of a page that this server rendered. A
  long-polling page after a restart, or on another server, is told to reload
  before that, for its missing fresh-page record, and is not counted.
- The WebSocket transport gets the delivery rules that long polling has since
  this change.
- A legacy view can send a whole document.
- The storage contract can say who owns a state.

A redesign may remove the reload. It has to keep three things:

1. No view starts from a state that was built for a bridge request.
2. When a view starts, the page shows the state that the view starts from, or
   the view sends the whole document. A state that is merely found does not
   prove it, because a page can have missed frames. A legacy WebSocket
   reconnect that finds its state still has this gap.
3. A state is deleted from a storage that other servers may share only under
   a fence at that storage: an owner or a generation that the deletion
   checks. Not by a timer, and not because one server knows of no view.

Directions, the one that the user of a page notices least first:

1. **A closed WebSocket is not the end of a view.** Long polling works like
   this since this change: the view lives until its idle timeout, the page
   says which frame it has applied, and the view sends what is missing. With
   the same rules behind a WebSocket, a reconnect within the idle timeout
   finds its view, with every storage, and nothing reloads. The storage is
   not involved, because the view has not retired. This also closes the gap
   of point 2 for a short break. Today a closed connection retires a legacy
   view at once.
2. **A whole document for a legacy view.** For a break longer than the idle
   timeout, and after a restart: the page reports its address, the server
   builds a state for it and sends the whole document, as a guarded view
   that recovers does. The browser does not reload. What only the session
   held is still lost.
3. **A storage that knows its owner.** Only this lets a state outlive its
   view safely, for a long break or across servers. It changes the contract
   of `StateStorage`, and every storage that an application wrote.
