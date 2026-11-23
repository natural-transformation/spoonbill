# Long polling that resumes: design

Status: **revision 5**, 2026-11-22: revision 4, which was built, with the
seven decisions that the user made after the review of pull request 42; see
[Changes after the review of PR 42](#changes-after-the-review-of-pr-42). The
[plan](long-polling-resume-plan.md#what-was-built) says where the work
differs. Before that, three throwaway trials, one inventory run and a few
probes ran; every change was undone. Their results are in
[Evidence](#evidence). Everything else is read from the code or reasoned, and
marked so.

The [implementation plan](long-polling-resume-plan.md) says in which order it
is built, and [ADR 0006](adr/0006-legacy-attach-needs-stored-state.md)
records its one decision with a known cost. The reviews of this design and
the reports of the defects that it names are working papers. They are not in
the repository; this text says in short what it takes from them.

Revision 4 follows the review of the implementation plan, and the user
narrowed two rules after that review:

- A stored state is removed only from a storage that is local to this
  process. Nothing new is deleted from a repository.
- An unguarded attach never builds a state. A missing state means reload, for
  a WebSocket too.

See [Changes from revision 3](#changes-from-revision-3). A run made
afterwards showed that the second rule reaches further with a repository
than was first said. **With that in view the user confirmed it on 2026-11-22
and asked for an ADR, so that the decision can be revisited and redesigned
later.** It is decision 25.

## Summary

A page on long polling loses a poll now and then. On `main` the page then
builds a new bridge, the server keeps the old view, and the page answers no
click any more. Pull request 40 gives the page a new view instead. That makes
the page live without making it right: after a lost DOM patch it shows one
state and runs the handlers of another.

The proposal: **the page keeps its bridge and its view, and the two agree on
what the page has applied.**

1. **The page counts.** Every request says which page it is and which frame it
   has applied: `subscribe?page=<id>&seen=7`.
2. **The server sends exactly the next frame,** and says which one it is and
   what the page shows after it. A poll that says `seen=7` gets frame 8: again
   if the page did not get it, or for the first time.
3. **A frame is made when a poll asks for it.** One frame, the one that was
   handed out last, is kept until the page confirms it.
4. **A failed poll is not a closed connection.** The page polls again and
   keeps its bridge. No new view, no new listeners, no rebuilt DOM.
5. **A poll builds a view once for a page:** for a page that was just rendered
   and has applied nothing.
6. **A click made on an older display does nothing.** The view checks it where
   it selects the handler, as a guarded view does.
7. **Everything uncertain ends in a reload:** the view has ended, the server
   was restarted, the page is out of step, or a patch did not apply.
8. **An attach never builds a state.** When the stored state of an unguarded
   session is missing, its page reloads. On `main` the server builds a state
   from the bridge request, and the page can get the view of another page.
9. **A page that nothing has claimed does not keep its state for ever,** where
   the storage is local to this process. From a storage that other servers
   may share, this change deletes nothing.

No view is replaced while its page lives. So the handoff of pull request 40
goes, and with it the handoff of the stored state.

## What the user of a page sees

| Situation | `main` | Pull request 40 today | This design |
| --- | --- | --- | --- |
| One poll fails, nothing was lost | The page answers no click (run) | New view, the page works (run) | Nothing |
| A poll fails and its DOM patch is lost | Old display, no click answered (run) | Old display, clicks run the handlers of the new state (run) | The update appears with the next poll (trial run) |
| A response that hangs | The page reloads after two lost heartbeats, by default (read) | The same | The page polls again and goes on |
| A break shorter than the idle timeout of the session (60 s by default) | The page answers no click | New view; updates made during the break are lost (read) | The connection-lost notice, then the missed updates in order |
| A longer break on long polling, or a restart of the server | A new view that trusts the page (read) | The same | One reload. What only the session held is lost |
| A click on a display that is behind | Not answered | Runs the newer handler (run) | Does nothing (trial run). It works after the page has caught up |
| Back is pressed while a patch is on its way | The route changes (read) | The same | The route changes; URL and page agree after the catch-up |
| A quiet page with the heartbeat switched off | A new view after every idle period (read) | The same | Nothing: its poll keeps the view alive |
| A page whose first poll comes later than the idle timeout after it was rendered | It connects (read) | The same | One reload |
| A WebSocket page reconnects after its socket closed, default storage | It goes on from its state; the page is trusted (run) | The same (read) | The same. Not changed |
| The same with a storage over a repository | A view of the default route; the page is trusted (run) | The same (read) | **One reload, at every such reconnect** (decision 25) |
| A WebSocket page after a restart of the server, default storage | A view built from the bridge request; the page is trusted (read) | The same (read) | One reload |

The last three rows are new. They come from rule 8, and they are for a
WebSocket, which the earlier revisions did not touch.

## Goal and limits

- **Goal.** For the life of one view, a long-polling page applies exactly the
  frames that the view handed out, in order, each once.
- **Goal.** A handler never runs for a click that was made on an older
  display than the one it was published with.
- **Goal.** No view of an unguarded session starts from a state that was
  built for a bridge request.
- **Goal.** In a storage that is local to this process, the state of an
  unguarded page lives only while a view owns it, or while its page is new
  enough to connect.
- **Limit: the delivery rules are for long polling only.** A WebSocket
  reconnect that finds its state still trusts the page. That is a known
  defect on `main`: a page that missed a frame stays behind, and its clicks
  run the handlers of the newer state.
- **Limit: unguarded sessions only.** Guarded sessions do not use long
  polling, and their unclaimed pages are not covered (decision 23).
- **Limit: a reload loses the state of an unguarded session.**
- **Limit: this change deletes nothing from a storage that is not local to
  this process.** What such a storage keeps today, it keeps.
- **No new frame type.** New are two query parameters on a poll and on a
  message, two response headers, and the answer `204`. A user event of a
  long-polling page travels in the envelope that a guarded page uses.
- **Every decision is a pure function** next to its interpreter, in files that
  the purity guard scans. Who waits for a frame is state, not a handle.

## The protocol

**Terms.**

- **Page:** one document in the browser. The client makes a random **page id**
  when it starts, a UUID built from random bytes, and keeps it as long as the
  document lives. The view that is built for the page takes it as its
  connection id.
- **Frame:** one message from a view to its page, the body of one poll answer.
  The frames that a view hands out are numbered from 1.
- **Cursor:** `seen`, the number of the last frame that the page has applied.
  It starts at 0.
- **DOM revision:** how many DOM frames (`ModifyDom`, procedure 4) the page
  has applied. The server render of the page is revision 0. The view counts
  the DOM frames that it publishes, the delivery counts the ones that it
  hands out, and the page counts the ones that it applies. Frames arrive in
  the order in which they were made, so the three counts name the same
  display.
- A request whose page id, cursor or revision is not well formed counts as a
  request without a cursor.

**Requests.** The `open` parameter of pull request 40 goes.

```text
GET  …/bridge/long-polling/<session>/subscribe?page=<page id>&seen=<n>
POST …/bridge/long-polling/<session>/publish?page=<page id>&seen=<n>
```

A DOM event, a custom callback and a history event are sent in the envelope
that a guarded page uses (`ViewEvent`, callback type 7): the connection is the
page id, the revision is the DOM revision of the page. The server has the
parser for it.

**Answers to a poll.**

| Answer | Meaning |
| --- | --- |
| `200` with a frame, `spoonbill-frame: <n>` and `spoonbill-dom: <r>` | Frame `n` of the view of this page. `r` is the number of DOM frames among frames 1 to `n`: what the page shows after this frame |
| `200` with the reload frame `[1]` | This page cannot go on. It reloads |
| `204` | Nothing yet, at the end of the poll wait. The page polls again with the same cursor |
| `410` | A request that nobody waits for: its cursor is older than what the page has confirmed |

Every answer carries `cache-control: no-cache`, as the poll answers of `main`
do (read: `MessagingService.scala`, line 244). The `204` too.

- **`spoonbill-dom` belongs to its frame,** not to what the view has
  published by now. A frame that is not a DOM frame keeps the number of the
  frame before it. A DOM frame adds one. Example: a heartbeat answer is made
  at revision 0, then patch 1 is published, then the page polls. The
  heartbeat is answered with `spoonbill-dom: 0`, and the patch after it with
  1. With "the latest revision" the page would label a click on display 0 as
  revision 1, and the check of the view would let it through.
- **The page checks both headers before it applies a frame.**
  `spoonbill-frame` must be `seen + 1`. `spoonbill-dom` must be the page's own
  count, plus one when the frame is a DOM frame. A `200` without these
  headers, or with other numbers, makes the page reload. That is also how a
  new client notices an old server, which would send the next frame whatever
  the cursor says.

**Answers to a message.**

| Answer | Meaning |
| --- | --- |
| `200` | The view's queue took it. Whether its handler runs is decided in the view |
| `410` | No view can take it: no view of this page, its queue is closed, or the message claims more than the view has handed out |

## The server

### Which poll builds a view

A session has one topic entry while it has a long-polling view. The entry
names the page it belongs to and holds the delivery state.

| The poll says | Topic entry of the session | Result |
| --- | --- | --- |
| no cursor | any | Reload |
| page `p`, any `seen` | entry of page `p` | Deliver by the cursor, below |
| page `p`, any `seen` | entry of another page | Reload |
| page `p`, `seen = 0` | none | Try a first attach |
| page `p`, `seen > 0` | none | Reload |

**A first attach** is one step that a cancelled request cannot stop half way:

1. The entry goes into the map with `putIfAbsent`. A poll that loses this
   decides again and finds the entry.
2. The poll claims the **fresh-page record** of the session. The answer must
   be `Fresh`: the page was rendered by this server, no attach has claimed it,
   and it is not older than its lifetime at this moment. Anything else:
   Reload.
3. The session service builds the view for this entry from the stored state.
   Without a stored state: Reload. If the session already has a view, or one
   is being built, the poll did not build it: Reload.
4. The entry owns the view. Its output goes to this entry and to nobody else.

Every Reload of these steps removes the entry again. An attach that fails
does the same: the poll that built gets the failure, as on `main`, and the
polls that wait are told to reload.

- **The fresh-page record** is reserved while the state of a rendered page
  is stored, and made when it is stored; its lifetime starts then. No expiry
  takes a reservation, so a write that runs long keeps its record, and a
  write that fails or is cancelled ends it and leaves nothing behind (after
  the review at `fe313e1a`). Every
  unguarded attach claims it, whichever transport it comes through. It is
  positive evidence that the stored state is the one the page shows. Where it
  lives and how it ends is in
  [A page without a view](#a-page-without-a-view-its-record-and-its-state).
  Every page after a restart of the server is told to reload at its first
  poll.
- **The age is checked at the claim.** A page that is rendered, waits longer
  than its lifetime and then polls is told to reload, also when nothing else
  was rendered in between. Revision 3 let the next render decide, so the
  answer depended on other traffic.
- **Why no mark in the stored state** (revision 1): to read it the server
  would call `StateStorage.get`, and the default storage then moves a state
  that was retired back among the live ones (read: `StateStorage.scala`,
  lines 82 to 94). A refused poll would leave it there for good.
- **Who owns a view.** The attach result of the session service says so: this
  transport built the view and owns it, or the page must reload, or another
  transport owns the view. On `main` a second attach gets the same output
  stream as the first, and closing it closes the stream (read; it is the root
  of a known defect, a `connect()` that opens a second socket). Here a
  WebSocket that arrives late for a session whose view belongs to a
  long-polling entry gets the reload frame and is closed, and a poll never
  joins the view of a WebSocket. A second document with the same session id
  so reloads once, into a session of its own, through the reload guard; the
  page that moved on to long polling ignores the frame, because its socket is
  not its transport any more. (Decided by the user on 2026-11-22, after the review of PR 42.) Before, such a socket was
  closed with nothing, and a document whose WebSocket works reconnected
  every 200 ms.

### An attach needs its stored state

On `main` an unguarded attach that finds no stored state builds one from its
own request (read: `SessionsService.scala`, lines 883 to 903). That request
is the bridge request. No route of the application matches a bridge path, so
the state is what the state loader gives, and the first render of an
unguarded view sends nothing, because it assumes that the page matches (run:
a page rendered at `/orders/42` got a view of the home page; see
[Evidence](#evidence)).

**The rule, decided by the user on 2026-11-22: an unguarded attach never
builds a state. When the stored state is missing, the page is told to
reload.** That holds for a poll and for a WebSocket. The page loads again, is
rendered from its own address, and stores a new state under a new session id.

- **The change is small.** The branch of `createLegacy` that rebuilds is
  replaced by the answer Reload. A WebSocket page then gets the reload frame
  `[1]` and its socket closes; the handler for this answer is on `main`
  (read: `MessagingService.scala`, line 217). A page render is then the only
  caller of `initAppState`.
- **What it closes.** Two defects that are on `main`. A page on a route gets
  the view of another page. And a slow rebuild resets the state of a session
  that already has a view; its root is two attaches that rebuild at the same
  time.
- **What it leaves.** A reconnect that finds its state gets a view that trusts
  the page, as on `main`.
- **Guarded sessions are not touched.** Their attach has its own rule for a
  missing view (`MissingLocalView`, read).

**When a state is missing, and what changes.**

| Storage | What happened | `main` | This design |
| --- | --- | --- | --- |
| Default | The socket closed, the page reconnects | The state is still there (run) | The same |
| Default | The server was restarted | A state from the bridge request; the page is trusted (read) | One reload |
| Default | 5,000 other states were removed after this one | The same rebuild (read) | One reload |
| Repository | The socket closed, the page reconnects | The view retires and deletes the row. The reconnect gets a view of the default route (run) | **One reload at every such reconnect** |
| Repository | The server was restarted and did not retire its views | The row is found (read) | The same |
| Any | Several servers, and an attach reaches one that has neither the state nor a shared repository | A state is built there (read) | A reload, and again until the render and the attach meet on one server. The reload guard slows this down |

The fourth row was not in view when the rule was first decided. It is
decision 25, and the user confirmed it with this row in view.

**In which order an attach looks.** First the claim of the fresh-page record,
then the storage, then the view. The claim is a step of a pure machine, and
the expiry can only take a record that nobody has claimed. So the storage is
never read for a session whose state the expiry is removing. The three steps
are one effect, which a cancelled request cannot stop half way: a stop
between the claim and the view would leave a state without record or view.
A storage that throws instead of failing its effect fails that effect, and
the entry of a poll leaves the table. (Added after the review of PR 42,
finding 13.) The other
removal of this change is made by a failed attach itself, while it holds the
place of the session in the table of views; see
[A page without a view](#a-page-without-a-view-its-record-and-its-state).

### Delivery: `PollDeliveryMachine`

No poll pulls from the view by itself, and nothing pulls ahead. The machine
asks for one frame when a poll needs one that is not there.

State: `confirmed` (frames that the page has confirmed), `dom` (the DOM
frames among them), `kept` (the frame `confirmed + 1` when it was handed out
and is not confirmed, with its kind: a DOM frame, a command, or another
frame), `pulling`, `waiting` (the polls that wait, by id), `ended`, and
`owned` (the entry owns its view, so frames can be asked for). With
`handed = confirmed + 1` if a frame is kept and `handed = confirmed` if not,
the first row that fits decides:

| Event | Condition | Change | Result |
| --- | --- | --- | --- |
| `Poll(id, seen)` | `ended` | none | Reload |
| | `seen < confirmed` | none | Stale: `410` |
| | `seen > handed` | none | Reload |
| | `seen = confirmed + 1` | confirm the kept frame: `confirmed + 1`, and `dom + 1` if it was a DOM frame. Then go on with the rows below | progress |
| | `seen = confirmed`, a frame is kept | none | Answer with the kept frame |
| | nothing kept, `pulling` or not `owned` | add `id` to `waiting` | Wait; progress |
| | nothing kept, not `pulling`, `owned` | add `id`, `pulling` | Pull one frame; wait; progress |
| `Owned` | not `owned`, not `ended`, polls wait | `owned`, `pulling` | Pull one frame |
| | not `owned`, not `ended`, no poll waits | `owned` | none |
| | otherwise | none | none |
| `Pulled(frame, kind)` | `pulling`, not `ended` | `kept = frame`, not `pulling`, `waiting` empty | Answer every poll that waited |
| | otherwise | none | Nothing: the frame was not asked for, and is dropped |
| `Ended` | | `ended`, `waiting` empty | Reload for every poll that waited |
| `TimedOut(id)`, `RequestEnded(id)` | `id` waits | remove `id` | `204` for a timeout, nothing for the end of a request |
| | `id` does not wait | none | Nothing: it was settled already |
| `Message(seen)` | `ended` | none | Gone: `410` |
| | `seen > handed` | none | Gone: `410` |
| | otherwise | none | Deliver to the view |

- **Every answer with a frame names two numbers:** the frame, `confirmed + 1`,
  and the DOM revision after it, `dom`, plus one if the kept frame is a DOM
  frame.
- **A frame comes out of the view when it is asked for.** So the view still
  drops a browser command that expired while it waited in its queue
  (`Frontend.outgoingMessages`, pinned by `BrowserRpcLifecycleSpec`). A pump
  that pulls ahead would keep such a command and deliver it later; see trial
  B.
- **The kept frame** knows whether its command is still awaited: the view
  hands that along with the frame (trial B). A page that asks again for a
  kept command that has expired gets a keep-alive frame under the same number
  (decision 3). It is not a DOM frame, so the revision stays. A DOM frame
  never expires.
- **Each waiter is settled once:** by the frame, by its timeout, or by the
  end of its request, whichever the machine sees first. The end of a request
  (`RequestEnded`) comes for every poll, answered or cancelled; for an
  answered one it changes nothing. The interpreter keeps the completion
  handle and the timer of each id in the same cell as this state, and
  completes the handle when the machine says so. A timer that the scheduler
  gives back is kept only while its poll waits (`keepsTimer`); otherwise it
  is cancelled in the same step. (Added after the audit for hidden state.)
- **Before the entry owns its view** a poll only waits. The frames of the
  view come with `Owned`, and a poll that joined the first attach is then
  answered.
- **The pull belongs to the entry, not to a request.** A poll that is
  cancelled does not stop it. A second poll that joined the first is answered
  when the frame comes, also when the first one was cancelled.
- **The end of a view** is the end of its frame stream: after an idle timeout
  the retirement closes the frontend (read). The entry leaves the map when
  the view cancels its input, as on `main`.
- **A view whose frames end retires at once.** The frontend can close by
  itself: a routing overflow, a revision that the view never published, an
  output failure. Then the delivery runs the release of its view, as a
  WebSocket adapter does when the output ends, and the view does not wait
  for its idle timeout. The release cancels the input through the stream
  that the close watcher sees. (Added after the review of PR 42, finding 12.)
- **A view whose input fails closes.** A message that the view cannot read,
  or a reply that it cannot take, fails its input. A view that resumes then
  closes its frontend, as a guarded view does, so its page is told to
  reload; else its polls keep it alive while it handles nothing. An
  unguarded WebSocket view keeps the failure in its input, as on `main`
  (`FrontendConnectionMachine.closesOnFailedInput`). Admission takes a page
  id in lower case only, as the view compares it. (Added after the review of
  PR 42, finding 14.)
- **A message never creates an entry.** So no entry can be an orphan, and the
  orphan timer of `main` has no job left; see decision 9.

### The view: which handler may run

Today an unguarded view selects the handler of an event from its live
handlers, whatever the page showed when the user clicked (read:
`ApplicationInstance`, lines 278 to 287; run: trial A). A guarded view selects
under its published revision and discards an event of an older one
(`Frontend.selectFromPublishedBrowserView`). A view that a long-polling entry
owns does the same:

- **Revision and handlers are published together.** The revision is the
  `View` of `FrontendConnectionMachine`, the pure machine of guarded views:
  the accepted first render is `Published(0)`, and every DOM frame adds one,
  in the step that offers the frame and stores the handler snapshot. All three
  ways to render do this: the first render, a later render, and the start
  from a saved render context in development mode. The trial missed the
  third, and one spec showed it.
- **A DOM event and a custom callback carry the revision of the page** in
  their envelope. When the handler is selected, a revision below the
  published one means the event is discarded and counted
  (`obsolete-browser-events`); a revision above it cannot come from an honest
  page and is refused.
- **An event without the envelope is refused.** The selection skips its
  comparison when an event names no revision (read: `Frontend.scala`, lines
  105 to 118). So a DOM event or a custom callback that comes without the
  envelope runs no handler on such a view and is reported. A guarded view
  refuses it in the same way ("View callback requires a revision", read:
  line 1010).
- **A history event is always handled.** The browser has changed the URL
  already. The router works on the current state, so the page reaches the
  right route when it has caught up. A guarded view does not drop an old
  history event silently either: it closes and recovers (read:
  `Frontend.enqueueUserAction`).
- **Nothing is checked when the message arrives.** A render can win between
  arrival and selection, so a check at arrival proves nothing; see trial A.
- **What "a guarded view" means here.** Where this document says what a
  guarded view does with revisions, the envelope or a whole document, it
  means a guarded view that recovers its view (`Frontend.recoversView`: its
  guard has an ownership epoch or view snapshots). A guarded view without
  that selects from its live handlers and sends nothing at its start, as a
  legacy view does (read; corrected on 2026-11-22, the earlier revisions said
  it of every guarded view).

### A sign of life

On `main` every incoming message resets the idle timer of a view, and nothing
else does (read: `handleCommunicationTimeout`, `SessionsService.scala`, lines
744 to 765). With the heartbeat switched off, a quiet page loses its view
while its poll waits. And if messages went on resetting the timer here, a
page that never moves its cursor would keep its view alive by its heartbeats.

- **For a view that a long-polling entry owns, only progress resets the idle
  timer.** Progress is a poll that confirms a frame, or a poll that waits
  because the page has everything. A message does not reset it, and neither
  does a poll that asks again for the kept frame. So a page that sends
  heartbeats and clicks but never moves its cursor loses its view after the
  idle timeout, and what waits for it is bounded by that time, as on `main`.
  Revision 3 added progress to the messages; the review showed that this
  keeps the old behaviour.
- **A WebSocket view is not changed:** its messages reset the timer.
- **A poll has a wait of its own:** a setting, 15 seconds by default, and
  never more than half the idle timeout. Then it is answered with `204`. A
  quiet page so makes progress at least twice in every idle period. Akka HTTP
  and Pekko HTTP end a request after 20 seconds with `503` by default, so
  their poll route raises its request timeout above that wait. Only that
  route: `/bridge/long-polling/<session>/subscribe`, parsed and matched as
  the service routes it, so an application route with a like name keeps its
  own timeout, and a spelling that the service routes there, such as a
  trailing slash, gets the timeout of a poll (after the reviews at
  `fe313e1a` and `b6194262`). A proxy with
  a shorter read timeout than the wait still gives failed polls; the setting
  is for such a deployment. (Decided by the user on 2026-11-22, after the review of PR 42.) Before, a poll waited half the idle
  timeout, 30 seconds by default, and on Akka and Pekko every quiet poll
  ended in a `503`.
- These rules are a matter of the transport. They decide nothing about the
  state of a view.

### A page without a view: its record and its state

On `main` a page render stores a state, and only the retirement of a view
removes it. A page that never connects keeps its state for good (run: 1,000
renders, 1,000 states, no cleanup scheduled; see [Evidence](#evidence)).

**The record.** For a guarded page the session service already keeps a record
that is made at the render and taken once at the attach: the `bootstrap` of
`SessionRegistryMachine`, a pure machine (read). The fresh-page record is the
same thing for an unguarded page and lives in that machine, as a field of its
own: when the page was rendered, in the order of the renders.

- **Only a page render makes a record.** It reserves the record **before**
  it stores the state (`Storing`), so no stored state is without a record,
  and makes it when the state is stored: the record waits from then on, in
  the order of the renders. A reservation is not in that order, so no expiry
  takes it while the write runs. A write that fails, throws, or is cancelled
  ends the reservation in a finalizer that also covers the reservation step
  itself, and a state that it left in a local storage is dropped; nothing is
  left behind (after the reviews at `fe313e1a` and `b6194262`).
- **A record ends in one of these steps.** All are steps of the one machine
  state, so only one can win.

| Step | When | What follows |
| --- | --- | --- |
| `Claim` | An unguarded attach of the session. The machine answers `Fresh`, `Expired`, `Absent` or `Releasing`; a reservation is `Absent`, since no browser has the page yet | A poll goes on only with `Fresh`, and takes the record. A WebSocket takes the record at any age and goes on with `Fresh`, `Expired` and `Absent`: it needs only the stored state. `Releasing` means Reload for both. An expired record that a poll finds stays where it is |
| `Expire` | A later render finds the record older than its lifetime | Local storage: the record becomes `Releasing` and its state is removed. Another storage: the record ends and nothing is removed |
| `Released` | The removal has returned | The record is gone. A removal that fails puts the record back, and a later render tries again |

- **A render cleans up in bulk,** at most 100 expired records at a time, and
  sets no timer. While nothing is rendered, nothing is cleaned up: an expired
  state can stay as long as the server is quiet. That costs memory for a
  while and decides nothing, because the claim checks the age itself.
- **The clock chooses between two safe answers,** a view or a reload. It never
  decides that a removal is safe. The machine does: a claimed record cannot
  expire, and a record that is `Releasing` refuses every attach until the
  removal has returned.

**A storage that is local to this process.** This change removes a state only
from such a storage. It means two things:

1. No other process can hold a view over a state of this storage.
2. `remove` has done its work when it returns.

The built-in default storage is local: it keeps its states in the memory of
the process, and `remove` moves a state into the for-deletion cache before it
returns (read: `StateStorage.scala`, lines 123 to 130).
`StateStorage.fromRepository` fails both: its `remove` returns when the
deletion has only started (read: lines 271 to 278), and a second server with
the same repository can hold the view. A storage that an application wrote is
not local either. How the session service knows is decision 26.

**Who removes.** Two paths, and only for a local storage:

1. **The expiry** of a record that nothing claimed. Every record that it
   takes leaves its releasing step, released or back for a later expiry,
   also when a removal fails and its report throws.
2. **An attach that fails before it has a view** (decision 24). On `main`
   nothing removes the state in this case (read: `abandon`, line 724). The
   removal runs while the failed attach still holds the place of the session
   in the table of views. A removal that fails is reported, and the attach
   keeps its own error.

(The two last sentences were added after the review of PR 42, finding 18.)

**What became of decision 20.** The user decided on 2026-11-22 that a session
whose page is told to reload, and which has no view, gives up its stored
state. Its case was a repository after a restart of the server, and "Narrow
it" takes that case out: this server cannot know that no other server has a
view. For a local storage no case is left. Every state without a view there
is either waiting for its expiry, or was left by an attach that failed before
it had a view, or was moved to the for-deletion cache when its view retired.
After a restart a local storage holds nothing in memory. So a reload removes
nothing by itself, and no code is needed for it.

**What the default storage keeps after a removal** (run). `remove` moves the
state into its for-deletion cache, which holds 5,000, and a WebSocket page
whose socket closed takes its state back from there. Unclaimed pages shared
that cache until the review of PR 42: 5,000 expired pages pushed out the
state of a page that was away. So **the expiry drops the state for good,**
past the for-deletion cache, with an operation of the default storage that
only the framework sees. (Decided by the user on 2026-11-22, after the review of PR 42.) The cache then holds the states of
views that retired, as on `main`, and of an attach that failed before it had
a view (decision 24 keeps `remove` for it). In development mode the files of
a state stay, as before.

**A WebSocket that first attaches late.** With the default storage it finds
its state while the record lives; the page has applied nothing, so page and
state agree. After the cleanup of its record the state is gone for good, and
the page reloads once. (Decided by the user on 2026-11-22, after the review of PR 42.) With another storage the row is still
there, and the page connects.

**Who ends a stored state.**

| The stored state of an unguarded session | Default storage | Another storage |
| --- | --- | --- |
| Its page was rendered and nothing has claimed it | The expiry, at a render after its lifetime; the state is dropped for good, not moved to the for-deletion cache | Nobody, as on `main`. The record ends, the row stays |
| A view owns it | The retirement of that view, as on `main` | The same. Its deletion can outlive the retirement, as on `main`: a reconnect that is faster than the deletion loses its row |
| An attach failed after it had built a view | Its rollback, as on `main` (read: `LegacySessionMachine`) | The same |
| An attach failed before it had a view | That attach (decision 24) | Nobody, as on `main` |
| The server was restarted | Nothing is stored any more | Nobody: the server has no list of what a repository holds. As on `main` |

## The client

The delivery is in the long-polling transport. The reload guard is in the
bridge and holds for both transports of an unguarded page.

| Event | State | Command |
| --- | --- | --- |
| A poll is answered with frame `seen + 1` and the fitting DOM revision | `failures = 0`, link up | Give the frame to the bridge |
| A `200` names another frame or another revision, or none | none | Reload |
| The bridge has applied the frame | `seen + 1`; `dom + 1` after a DOM frame | Poll again |
| The bridge could not apply a DOM frame | none | Reload |
| Another frame throws in the bridge: a command, a property read, a history call | `seen + 1` | Logged; poll again, as on a WebSocket |
| A poll is answered with `204` | `failures = 0`, link up | Poll again |
| A poll fails: status 0, 5xx, `408`, `429`, or no answer by the deadline | `failures + 1`, link interrupted, `replaced` ends | Poll again after the back-off; show the notice when `failures` is 2 |
| A poll is answered with any other status | none | Reload |
| The reload frame arrives | none | Reload |
| A heartbeat message cannot be sent: its request fails, is answered with a `5xx`, or has no answer when the next heartbeat is due | `failures + 1`, link interrupted; `replaced` stays, since the poll has not failed | As a failed poll; the heartbeat is not counted as unanswered, and the re-poll is given back. A request that is written off at the next heartbeat is aborted, so that requests do not pile up on a link that hangs (the last two added after the second review of PR 42) |
| No transport takes a heartbeat, so no request goes out (added after the second review of PR 42) | none | Not counted as unanswered, and no failed poll |
| A message that the server took while the link is interrupted, the first since the last failed poll; a failed heartbeat is no failed poll here (added after the second review of PR 42, once-only after the review at `82baf17a`, the heartbeat after the review at `fe313e1a`) | `replaced` | The link is back: give up the current poll, which may hang on the broken link, or its back-off, and poll again. A later taken message leaves the new poll alone until it is answered or fails, so a quiet poll still reaches its `204` |
| The heartbeat limit is reached, for the first time since a reply or a failed heartbeat | none | Give up the current poll, or its back-off, and poll again |
| The heartbeat limit is reached again, and the heartbeats that the server took are still unanswered | none | Reload, through the reload guard |

- **Applied means applied.** The bridge reports the result of a frame to the
  transport. A DOM frame counts only when every command found its node: today
  the bridge catches a failed patch and only logs it, and `create`,
  `createText` and `remove` skip a missing parent without any error (read:
  `bridge.js`, `spoonbill.js`).
- **A missing child is allowed in `remove`.** The page renderer writes an
  empty text as nothing, so the parsed page has no node for it, and the view
  may remove that text later. Such a patch names a child that the page never
  had, and the page is still in step. The diff of the renderer removes each
  node once, and never a node inside one that it removes (read: Avocet
  2.0.1, `DiffRenderContext.deleteLoop`). So `remove` fails only for a
  missing parent. (Checked after the review of PR 42, finding 17.)
- **A failed poll keeps the bridge.** No `close` event. Two new events,
  `interrupt` and `resume`, show and hide the connection-lost notice. It
  appears when the first retry fails too. Every answered poll hides it again,
  a `204` too. The heartbeat goes on through an interruption (see below). `Spoonbill.ready` stays true and
  `SpoonbillReady` is not sent again.
- **A poll has a deadline in the client:** the poll wait, which the page
  configuration names, plus a margin, and always below the idle timeout. A
  response that hangs is then a failed poll, and not a silent stop, and the
  page notices it while its view still lives. (Decided by the user on 2026-11-22, after the review of PR 42.)
  The retry goes out after the deadline and the first back-off of 200 ms.
  The deadline is at most three quarters of the idle timeout, so the retry
  comes in time for an idle timeout of about one second or more; below that,
  a response that hangs ends the view, and the page reloads once (after the
  review at `fe313e1a`).
- **The heartbeat on long polling tells a dead link from a dead input.**
  (Decided by the user on 2026-11-22, after the review of PR 42.) `heartbeatLimit` is 2 by default, and on `main` the page
  reloads when it is reached (read).
  1. The heartbeat goes on while the link is interrupted. Its reply answers
     the poll that the server holds, so the notice hides within one
     heartbeat interval after the link is back.
  2. The page looks at the answer to each heartbeat message. One that cannot
     be sent counts as a failed poll, not as an unanswered heartbeat. So a
     link that hangs shows the notice at the second failure, and the page
     goes on with its state when the link is back.
  3. A heartbeat that the server took and did not answer is counted. At the
     limit the page gives up its poll and polls again, once. If the
     heartbeats that the server took are still unanswered at the next limit,
     the input of the view is dead, and the page reloads through the reload
     guard: about 20 seconds with the defaults. A heartbeat reply in between
     starts the count again.
  4. The count does not go below zero: a reply to a heartbeat that was
     written off changes nothing.

  Before, the limit only polled again: a link that hangs gave no sign, and a
  page whose messages did not arrive stayed deaf.
- **`503` is a failure,** with the back-off. On `main` the client polls again
  at once (read).
- **An answer to a request that the transport gave up is ignored.**
- **A reload that the framework starts for an unguarded page has a guard,**
  on both transports: the reload frame, and the reloads that the long-polling
  transport decides. The client notes the time of such a reload in
  `sessionStorage`. A page that is told to reload again within the window
  shows the notice and reloads when the window is over. The guard limits a
  rate. It decides nothing about a view, so it may use a clock. It matters
  more than in revision 3: with rule 8 a WebSocket page is told to reload in
  cases where `main` builds a state, and two such reloads can come close
  together. A guarded page is not changed: its reloads belong to flows of
  its own (read: `bridge.js` reloads in four places).
- **The transport is remembered when it has to be.** A proxy can pass a
  WebSocket request on to the server and then break the socket. The server
  has then built a view that the page never had, and that view has taken the
  fresh-page record. So a document that tried a WebSocket first, fell back
  to long polling, and is told to reload before it applied a frame notes
  "start with long polling" in `sessionStorage` before it reloads. Only such
  a document writes the note. (Decided by the user on 2026-11-22, after the review of PR 42.)
  - **The note ends** when a document that started with long polling because
    of it is told to reload before it applied a frame. The next document
    then tries a WebSocket again, which ends the loop on a network where
    long polling is what fails. Otherwise it stays for the life of the tab.
  - The keys of the note and of the reload guard are named after the root
    path of the application, so that two applications on one origin do not
    share them, and the time of the last reload is read in a `try` of its
    own.
- **Without `sessionStorage`** the page can note nothing. It then reloads as
  `main` does on a reload frame, without a guard and without the remembered
  transport.

## Why it is right

**Claim.** While a page lives, its DOM is what its view believes it to be
after frame `seen`, and a handler runs only for the display it was published
with.

- **Start.** A poll builds a view only for a page with `seen = 0` whose
  fresh-page record is `Fresh`. The page shows the server render of the
  stored state, and no view has changed that state.
- **Step.** The page applies a DOM frame `seen + 1` completely, or it
  reloads; every other frame is delivered once. (Decided by the user on 2026-11-22, after the review of PR 42.) It
  knows the number and the revision from the answer and compares both with
  its own counts. The server answers `seen = k` only with frame `k + 1` of
  the view that this page id owns.
- **Events.** The view runs a handler only when the event names the revision
  that the handlers were published with. An event that names none runs
  nothing.
- **No state from a bridge request.** A view starts from a state that a page
  render stored, or it does not start.
- **Removal.** This change removes a state only from a local storage, only
  for a record that is `Releasing` or inside a failed attach that holds the
  place of the session. No attach reads the storage in the first case, and no
  other attach builds in the second.

**What it assumes.**

1. Only a view changes the stored state of a session.
2. Only the framework changes the DOM nodes that it manages. `main` assumes
   the same.
3. All requests of a session reach a server that has its view, and its first
   attach reaches a server that has its state. If not, the page reloads, and
   the guard slows a repeat. On `main` the second half is not needed: a
   server without the state builds one.

## What goes and what stays in pull request 40

- **Goes:** `LegacyAttachMachine`, `LegacyViewMachine`, `LegacyViews`,
  `TopicOpeningMachine`, `SessionStart.Superseded`, the `Release` event and
  the `Storage` field of `LegacySessionMachine`, and their specs and check
  runners.
- **Stays, smaller:** the notion that a view has an owner (`AttachOwner`
  today). Revision 1 listed it as going.
- **Stays:** the fix for a failed attach that fails every later attach (the
  third commit; the restart that the fifth commit added for a waiting poll
  goes), and `410` for a message that no view can take (a seventh commit,
  local and not pushed).
- **Findings of the third review.** 1 is what this design is for. 2 is gone
  at its root: no attach rebuilds a state. 3 is gone with the handoff. 4 is
  fixed.

## Acceptance tests

Each case is written first and must fail without its part of the change.

| Rule | Case | Where |
| --- | --- | --- |
| Which handler runs | The page is behind and clicks: no handler runs, the event is counted | service spec; trial A |
| | A held click: it arrives while nothing newer is published, and the render is published before its handler is selected: no handler runs | service spec with the `awaitReady` seam, and the checks with a lazy effect |
| | After the catch-up the click runs the handler of the new stage | service spec |
| | A DOM event and a custom callback without the envelope: no handler runs | service spec |
| | Back is pressed while a patch is on its way: the route changes, URL and page agree | browser |
| | In the browser: the patch is dropped, the user clicks before the retry; and the same with the response held | browser, Chromium and WebKit |
| | An element that was not touched keeps its focus and its unsent input | browser |
| Frames | A browser command that expires while the page is away is not delivered | service spec; trial B |
| | The kept frame is an `evalJs` that has expired, and the page asks again: a keep-alive frame under that number and with the same DOM revision; the command does not run | service spec, Node client tests |
| | A heartbeat answer is made at revision 0, patch 1 is published, then the page polls: the heartbeat says `spoonbill-dom: 0`, the patch says 1 | table, service spec |
| | Two polls with one cursor, one cancelled after the pull: the frame is neither skipped nor handed out as two | checks with a lazy effect |
| | A frame is made between "wait" and the registration of the waiter: the waiter gets it | table, service spec |
| | A waiter is settled once by frame, timeout or cancellation | table |
| | A `200` without `spoonbill-frame` or `spoonbill-dom`, or with a number that does not fit: the page reloads and applies nothing | Node client tests |
| | A patch that throws, and a patch whose node is missing: no count, the page reloads | Node client tests |
| Which poll builds a view | The first response is lost and the view lives: frame 1 again | service spec |
| | The first response is lost and the view has ended: Reload | service spec |
| | The winning poll is cancelled right after `putIfAbsent`: no entry stays | checks with a lazy effect |
| | A second poll joins the first, and the first is cancelled, before the record is claimed and after the entry owns the view: the second gets the frame or Reload. It does not hang, and no second view is built | checks with a lazy effect |
| | A WebSocket that arrives after the poll built the view: it gets no frame, and closing it does not close the view. And the other order | service spec, both orders |
| | No fresh-page record, as after a restart of the server: Reload | service spec |
| | One page is rendered, its lifetime passes, **nothing else is rendered**, then it polls: Reload | service spec with a clock |
| | A message with `seen` ahead: `410`, not delivered | table, service spec |
| An attach needs its state | A router that depends on the address. A page is rendered at `/orders/42`, its row is deleted, and it attaches for the first time, by WebSocket and by poll: the reload frame. The state loader ran once, for the render | service spec |
| | The same in a browser: after one reload the page shows the order again, and a click runs the handler of the order page | browser |
| | A repository; a WebSocket page whose socket closed reconnects: the reload frame. With the default storage it goes on from its state | service spec; the probe of 2026-11-22 |
| | Two attaches of a session without a state at the same time: both are told to reload, and nothing is stored | service spec |
| | The missing-view specs of the three adapters: the reload frame, and no state is built | adapter specs |
| | Each of these reloads adds one to its counter (decision 28) | service spec |
| Sign of life | One frame is withheld, the page asks for it again and again and keeps sending heartbeats and clicks: the view ends after the idle timeout | service spec with the timing seam |
| | With the heartbeat off, a page whose poll waits keeps its view over two idle periods | service spec with the timing seam |
| | A response hangs: the client polls again at its deadline, and a lost heartbeat does not reload | Node client tests |
| | A `204` after an interruption hides the notice, and the heartbeat goes on | Node client tests |
| | Repeated `503`: retries with the back-off, the notice shows | Node client tests |
| Reload | A view ends while a poll waits: the reload frame | service spec |
| | An unguarded page is told to reload twice within the window, on long polling and on a WebSocket: one reload at once, the second when the window is over, the notice in between. A guarded page reloads as before | Node client tests |
| | A page that fell back and is told to reload starts with long polling after the reload | Node client tests |
| A page without a view | A claim and the expiry of one record: exactly one wins, in both orders. A claimed record cannot expire. A claim while the record is `Releasing`: Reload, and the storage is not read | table of the machine; service spec with a storage that holds its `remove` |
| | **Held deletion.** A repository whose `removeRepository` is held. Pages expire, a poll is told to reload, an attach fails before it has a view: `removeRepository` is not called | service spec |
| | **Two servers, one repository.** A poll reaches the server that has no view of the session: Reload. The row is intact in the repository itself, and the view on the other server goes on | service spec with two services |
| | A default storage with a for-deletion cache of 2. Five pages expire, and one more has a view: the three oldest states are gone, the view keeps its state | service spec with a clock |
| | 250 pages expire: three renders clean them up, 100 at a time | service spec with a clock |
| | A render is cancelled after its record is reserved and before the state is stored; and right after it has selected expired records: afterwards no state is live without a record or a view, and no reservation is left | checks with a lazy effect |
| | An attach makes no record | service spec |
| | An attach fails before it has a view, and its page never comes back: the state leaves the live states of the default storage | service spec |

The browser cases need a page with an input field and a route. The fixture
gets a scenario of its own, so that the ids that the renderer matrix has
hard-coded stay as they are.

A note for the held-click case: with the built-in `Future` effect the whole
path from the arrival of a message to the selection of its handler is one
step (run: trial A needed a seam). The case uses the `awaitReady` parameter
that `ApplicationInstance` has, or a lazy effect.

## Cost

- **Long polling:** one machine step for each poll and each message, and one
  kept frame for each view.
- **A view that a long-polling entry owns:** one more state step and one
  handler snapshot for each DOM frame, as in a guarded view.
- **Every unguarded attach:** one step of `SessionRegistryMachine` under its
  monitor, the claim.
- **Every unguarded page render:** one such step for the record, and the
  removal of up to 100 states when records have expired. No timer.
- **Every WebSocket frame or event of an unguarded view:** nothing, if only
  long-polling views publish revisions (decision 2).
- **Existing tests.** 11 cases in 7 suites rely on a state that an attach
  builds; see [Evidence](#evidence). They change with rule 8.
- ADR 0004 asks for a measured budget for a new state model. The event path
  of a guarded view must not change. The event-latency harness and the four
  workloads of the gate are run as controls. Both use a WebSocket or no
  HTTP at all (read), so they say nothing about long polling: one matched
  measurement of a click and its patch on healthy long polling, `main`
  against the change, comes with them.

## Not covered

- A WebSocket reconnect that finds its state: the page is still trusted. The
  draft of ADR 0006 names the redesign that would close this and take the
  reload of decision 25 away for a short break: a closed WebSocket is not the
  end of a view.
- Two WebSockets for one session, which is the matter of pull request 39.
- A page script that changes managed DOM nodes.
- `Spoonbill.disconnect()` and `connect()` on long polling. Pull request 39
  defines them. Proposal for it: `disconnect` stops the polls and keeps the
  bridge; `connect` goes on with the same view, and the page reloads when
  that view has ended.
- A cap in bytes on what waits for a page (decision 11).
- **Guarded pages that never connect.** Their render stores a state in the
  same way (read). Their attach is not traced yet (decision 23).
- **Everything that a storage other than the local one keeps:** the row of a
  page that never connects, the row of an attach that failed before it had a
  view, the rows that a restart leaves. As on `main`. To remove them safely a
  storage must be able to say who owns a row, or to delete only the row of a
  given owner. That is a change to the storage contract and a design of its
  own.
- **The deletion at a retirement with a repository.** It still returns before
  it has ended. That is a known defect on `main`.
  This change adds no way into it.
- **A browser without `sessionStorage`:** no reload guard and no remembered
  transport.

## Decisions

Each is the user's. The recommendation is first. Changed against revision 3:
5, 14, 18, 19, 20, 21, 22 and 24. New: 25 to 28.

**About what a page does.**

1. **The handler check is made in the view, at selection.** *Alternative:* a
   check when the message arrives. Trial A shows what it lets through.
2. **Only a view that a long-polling entry owns publishes revisions.**
   *Alternative:* every unguarded view does. A WebSocket event has no
   revision and would still not be checked, but its handler would come from
   the published snapshot. With the check on for every unguarded view, one of
   the 987 cases of the module failed because of the trial; see
   [Evidence](#evidence).
3. **A kept command that has expired is answered with a keep-alive frame.**
   *Alternative, which the first review prefers:* reload. Both keep the
   numbers in step and neither runs the command.
4. **A custom callback is checked like a DOM event,** as in a guarded view.
   *Alternative:* always deliver it, like a history event.
5. **Reload when the view is gone**, also after a restart of the server.
   Since decision 25 the same holds for a WebSocket page whose state is
   missing. *Alternative, later:* send the whole document, for both
   transports.
6. **A request without a cursor is told to reload.** *Alternative:* serve it
   as `main` does, on a second code path that trusts the page.
7. **A patch that does not apply reloads the page** on long polling. Only a
   DOM patch: a command that throws is logged and delivered, as on a
   WebSocket. (Decided by the user on 2026-11-22, after the review of PR 42.)
8. **A message before the first poll gets `410`.** *Alternative:* keep the
   topic that `main` makes for it, with its orphan timer.
9. **Remove the orphan timer and `OrphanTopicMachine`.** *Alternative:* leave
   them for a later cleanup; they are then dead code.
10. **In development mode a long-polling page reloads after a restart.**
    *Alternative:* keep the attach of `main` for a saved render context. A
    WebSocket page is not changed here: the default storage finds its files.

**About limits and numbers.**

11. **No cap in bytes now.** What waits for a page that does not move on is
    bounded by the idle timeout, as on `main`. *Alternative:* end a view whose
    queue passes a size. That is a new policy for unguarded views.
12. **A poll has a wait of its own, 15 seconds by default and at most half
    the idle timeout; the client's deadline is that plus a margin, below the
    idle timeout.** (Decided by the user on 2026-11-22, after the review of PR 42.) *Not taken:* only raising the request
    timeout of the two adapters (a proxy with a shorter read timeout runs
    into the same loop, and a long idle timeout gives a wait of hours); a
    `503` that means "poll again", as on `main` (a real outage then shows no
    notice). The cost: a quiet page with the heartbeat off asks every 15 s.
    Revision 4 had half the idle timeout and a deadline five seconds above.
13. **On long polling the heartbeat shows the notice for a dead link, and
    reloads for a dead input.** A heartbeat that cannot be sent is a failed
    poll; heartbeats that the server took and did not answer give one
    re-poll at the limit, and a reload through the guard at the next one.
    (Decided by the user on 2026-11-22, after the review of PR 42.) *Not taken:* reload at the limit as on `main` (a 10 s
    stall of the network then costs the page its state); poll again and
    nothing more, as revision 4 said (a dead link gives no sign, and a deaf
    page never recovers).
14. **The reload guard allows one framework reload in 30 seconds for a tab,
    on both transports of an unguarded page. A second one waits, with the
    notice, until the window is over.** *Alternative:* the second one does
    not happen and the page stops, as in revision 3. Then two honest reloads
    close together leave a dead page, and with decision 25 that can happen:
    two closed sockets within the window, with a repository. *Alternative:*
    another window.
15. **The fresh-page records are bounded by age, not by a count.** Their
    number is what is rendered within one lifetime, plus what waits for the
    next render to clean up. *Alternative:* also a bound by count, as the
    bootstrap record of a guarded page has (5,000). Then many renders that
    never connect push out the record of a slow page, and that page is told
    to reload.

**About the work.**

16. **The page id is made by the page.**
17. **A new branch and a new pull request from `main`**, with the parts that
    stay. Pull request 40 would then be closed. *Alternative:* more commits on
    its branch.
18. **An ADR entry for the delivery rule** when it is built. Not written
    before the user says so. **ADR 0006 is another matter:** the user asked
    for it on 2026-11-22, for decision 25 only. It came into the
    repository with the implementation: [ADR 0006](adr/0006-legacy-attach-needs-stored-state.md).

**About stored state.**

19. **Decided by the user on 2026-11-22: a page that nothing has claimed
    expires. Narrowed by the user the same day:** its state is removed only
    from a storage that is local to this process. With another storage the
    record ends and the row stays. **After the review of PR 42** the state
    is dropped for good, past the for-deletion cache, so that unclaimed
    pages do not push out the states of pages that are away. (Decided by the user on 2026-11-22, after the review of PR 42.)
    *Not taken:* keep sharing the cache; a cache of their own.
20. **Decided by the user on 2026-11-22: a session whose page is told to
    reload, and which has no view, gives up its stored state. Narrowed the
    same day:** nothing is deleted from a repository because this server has
    no view. For a local storage no case is left; see "What became of
    decision 20".
21. **A record lives as long as the idle timeout of a session** (60 seconds
    by default), and its age is checked when it is claimed. A page has that
    long to make its first poll; a first WebSocket attach has no such limit.
    *Alternative:* five minutes, as the bootstrap record of a guarded page is
    kept; or a setting of its own, which a deployment with a short idle
    timeout and slow clients would want.
22. **The cleanup rides on renders,** at most 100 expired records at a
    render. *Alternative:* a timer for each render. It removes to the second
    also when nothing is rendered any more, and it costs one timer for each
    page.
23. **Guarded pages are not covered in this change.** *Alternative:* the same
    expiry for them at once. Their attach has more parties, and it has to be
    traced first.
24. **An attach that fails before it has built a view removes the stored
    state, in a local storage.** A WebSocket that comes again takes the state
    back from the for-deletion cache; a poll that comes again is told to
    reload. *Alternative:* leave the state, as `main` does. If the page never
    comes back, it stays. With another storage it stays in both variants.
25. **Decided by the user on 2026-11-22, and confirmed the same day: an
    unguarded attach whose stored state is missing is told to reload,** on a
    WebSocket too, with every storage.
    The first decision was made with only the restart of the server in view.
    A run made afterwards showed that with a storage over a repository every
    reconnect after a closed socket finds no state, because the retirement of
    the view deletes the row. On `main` such a page silently gets a view of
    the default route. With this rule it reloads, at every such reconnect. A
    deployment with several servers that share no repository and have no
    sticky routing is hit too: on `main` each server builds a state. With
    that in view the user kept the rule, and asked for an ADR so that the
    decision can be revisited and redesigned later. The
    [ADR 0006](adr/0006-legacy-attach-needs-stored-state.md) has the
    decision, its cost, when to revisit it, and what a redesign has to keep.
    *Not taken:* the rule only where this change removes states, that is for
    a poll, and for a WebSocket with a local storage. It would leave the
    wrong view of `main` for a WebSocket over a repository.
26. **The storage itself says that it is local:** a member of `StateStorage`
    that only the framework can see, false by default and true for the
    built-in default storage. *Alternative:* the session service tests for
    the class of the default storage; no new member, but the knowledge sits
    in the wrong place. *Alternative:* a public setting, so that an
    application can mark its own storage. A wrong mark then deletes the state
    of a working view on another server.

**About the protocol.**

27. **The number of a frame and its DOM revision travel in response
    headers.** *Alternative:* in the body, in front of the frame. The reason
    is what can stand between a page and the server: an intermediary that
    drops response headers it does not know would make every long-polling
    page reload at its first answer, again and again, slowed by the guard.
    That is safe, and the page is of no use. No such deployment is known
    (not checked), and the bridge is on the origin of the
    page, so the browser shows the headers to the client (read:
    `connection.js`). If such an intermediary exists, the body is the place:
    it cannot lose the numbers without losing the frame.

**About the ADR.**

28. **The server counts the reloads of decision 25,** in `Metrics`, next to
    `obsolete-browser-events`. The user wants to revisit decision 25 later;
    a number from a deployment says whether it is time. One counter, and one
    line where the reload is answered. It is proposed with the ADR and is not
    decided. *Alternative:* no counter. Then the debug line of the reporter is
    the only trace, and the ADR says so.
29. **"Start with long polling" is noted only after a fallback, and ends at a
    reload.** Only a document that tried a WebSocket first, fell back and is
    told to reload before it applied a frame writes the note; a document that
    started with long polling because of the note and is told to reload
    before its first frame takes it away. Decided by the user on 2026-11-22,
    after the review of PR 42. *Not taken:* an expiry after a time (on a
    network that breaks WebSockets it costs one more reload at each expiry);
    leaving it as revision 4 had it, written at every reload at cursor 0 and
    never removed.
30. **A WebSocket for a session whose view a poll owns gets the reload
    frame.** Decided by the user on 2026-11-22, after the review of PR 42.
    *Not taken:* closing it with nothing, as revision 4 said; a second
    document whose WebSocket works then reconnects every 200 ms.
31. **Pull request 41, the plan and this design, is merged before pull
    request 42.** Decided by the user on 2026-11-22. The branch of PR 42 then
    takes `main` in; its versions of the three documents are the newer ones.

## Steps

Each step is one commit with its tests first. The
[implementation plan](long-polling-resume-plan.md) has the files and the
cases.

1. A failed attach is not final for its session (from pull request 40). It
   removes nothing new.
2. The pure machines and their tables.
3. The view checks a user event where it selects the handler.
4. An unguarded attach needs its stored state (decision 25).
5. The server delivers by the cursor, and a first poll needs a fresh-page
   record. The case of the DOM revision on a frame that is not a DOM frame
   comes first here.
6. The cleanup in a local storage. The held-deletion case comes first here.
7. The client.
8. Both sides in a browser, and the long-polling measurement.
9. Words and checks. ADR 0006 comes into the repository here.

## Evidence

All trials were throwaway changes. Each was undone from a saved copy, and
the export of `main` that the probes used was the same as `main` again
afterwards (compared). One run each unless it says otherwise.

**Resend (before revision 1).** Ten lines in `connection.js`, eight in
`MessagingService.scala`. The reviewer's lost-patch case in Chromium and
WebKit: the page kept its bridge, got the patch again, showed the new stage
and ran the matching handler.

**Trial A, the held click.** 36 lines in `Frontend.scala`, 14 in
`ApplicationInstance.scala`: a counter of published DOM frames, the handler
snapshot stored at publication, the check at selection, and a seam before the
selection for the probe.

```text
[A/today] the page is behind (published=1, the page has applied 0): ran=List(stage 1 handler), discarded=0
[A/today] a held click: it arrived while published=0 and its handler was selected while published=1: ran=List(stage 1 handler), discarded=0
[A/today] the page has caught up (applied 1 DOM frame): ran=List(stage 1 handler), errors=0
[A/check at selection] the page is behind (published=1, the page has applied 0): ran=List(), discarded=1
[A/check at selection] a held click: it arrived while published=0 and its handler was selected while published=1: ran=List(), discarded=1
[A/check at selection] the page has caught up (applied 1 DOM frame): ran=List(stage 1 handler), errors=0
```

The second line is the case that a check at arrival cannot catch. With the
check switched on for every unguarded view, the tests of the module ran once
in a forked JVM: 970 of 987 passed. 16 of the 17 failures are
`ClientEventListenerSpec`, which reads a file by a relative path and fails
the same way in a forked run without the trial (control run). The one real
failure is `DevModeSavedRenderContextSpec`: the trial did not publish the
handlers on the start from a saved render context.

**Trial B, the expired command.** A second output stream in `Frontend.scala`
that gives a frame together with "is its command still awaited".

```text
[B] on demand: the command expired in the queue (failed with ClientSideException); the next frame that a poll gets: Some([5,"1" ])
[B] eager pump: the command expired afterwards (failed with ClientSideException); the log still holds: Vector([10,"0","must not execute" ])
[B] kept frame: handed out [10,"0","must not execute" ], its command is awaited: true
[B] kept frame: after the deadline (failed with ClientSideException), its command is awaited: false
[B] a DOM frame [4 ] is awaited: true, published DOM frames: 1
```

A browser command lives 5 seconds by default, a file command 30
(`RpcSettings`, read).

**Probes for the page without a view** (scratch files in the test scope, no
change of a source file). The second and third line show what `remove` leaves
in each storage. Since revision 4 nothing is removed from a repository.

```text
[unattached] rendered 1000 pages that never connect: states stored=1000, cleanup jobs scheduled=0, tasks waiting=0
[release] default storage: 10000 states of pages that never connected: stored=10000; after remove for each: stored=5000
[release] repository: 1000 states of pages that never connected: entries=1000; after remove for each: entries=0
```

**Probes for a missing state, on an export of `main`** (`82aa5dbd`, no other
change; the first also on the branch of pull request 40). A router maps
`/orders/<id>` to the state "order <id>"; the state loader gives "the home
page".

```text
[late] the page was rendered at /orders/42 from the state: 'order 42'; stored: Some(order 42)
[late] after the removal the repository holds the session: false
[late] the WebSocket attach: Some(Success(Attached)); the view starts from the state: Some(the home page)

[repository] rendered at /orders/42 from the state 'order 42'; rows in the repository: 1
[repository] first attach: Some(Success(Attached)); a view is registered: true
[repository] after the socket closed: a view is registered: false; the storage says the state exists: false; rows in the repository: 0
[repository] the reconnect: Some(Success(Attached)); the view starts from the state: Some(the home page)
[default storage] first attach: Some(Success(Attached)); a view is registered: true
[default storage] after the socket closed: a view is registered: false; the storage says the state exists: true; rows in the repository: -1
[default storage] the reconnect: Some(Success(Attached)); the view starts from the state: Some(order 42)
```

The first block is a page whose row was removed before its first attach. The
second is an ordinary reconnect: with a repository the view of the reconnect
starts from the wrong state on `main`, with the default storage from the
right one. `-1` means that this storage has no repository to count.

**Inventory for decision 25.** The rule as a throwaway change on the export
of `main`: the branch that rebuilds answers Reload. The test command of CI,
once: 2,287 ScalaTest cases ran, 11 failed, in 7 suites. All 11 rely on a
state that an attach builds.

| Suite | Cases | What they rely on |
| --- | --- | --- |
| `StandaloneMissingViewReloadSpec`, `AkkaMissingLocalViewReloadSpec`, `PekkoMissingLocalViewReloadSpec` | 1 each | They count one rebuild before the reload frame. The reload frame stays, the count becomes 0 |
| `SessionsServiceSpec` | 3 | One pins the rebuild ("rebuild missing state and create app"). Two attach a session that was never rendered |
| `LegacyStreamFailureSpec` | 2 | They attach a session that was never rendered |
| `ExtensionContractSpec` | 2 | "attach a view whose state was rebuilt", and a failed setup after a rebuild |
| `InvalidDevRenderContextSpec` | 1 | It waits for the read of a state that was rebuilt |

Not run with the rule: the Node client tests, the acceptance script, the
browser tests.

**Read, not run.** Everything else that this design states about the code.
**Not run.** Every rule beyond these trials and probes.

The trial code and the probes were scratch files. They are not kept in the
repository. Step 4 of the plan turns the two probes for a missing state into
test cases.

## Changes after the review of PR 42

The review of pull request 42 (2026-11-22) found six gaps of this design, and
the user decided each on the same day. They change:

| Decision | Before (revision 4) | Now |
| --- | --- | --- |
| 12 | A poll waits half the idle timeout; on Akka and Pekko a quiet poll ends in `503` after 20 s, a failed poll | A wait of its own, 15 s by default, at most half the idle timeout; the two adapters raise their request timeout for the poll route; `408` and `429` are failed polls |
| 13 | At the heartbeat limit the page polls again, nothing more; the heartbeat pauses while the link is interrupted | A heartbeat that cannot be sent is a failed poll; unanswered heartbeats give one re-poll, then a reload through the guard; the heartbeat goes on during an interruption |
| 19 | The expiry moves an unclaimed state into the for-deletion cache | The expiry drops it for good |
| 7 | Every frame that throws reloads the page | Only a DOM patch must apply |
| 29 (new) | The transport note is written at every reload at cursor 0 and never removed | Only after a fallback, and it ends at a reload |
| 30 (new) | A refused WebSocket is closed with nothing | It gets the reload frame |
| 31 (new) | | PR 41 is merged first |

The same review found plain fixes, which needed no decision. Those that add
a rule to this design are written where the rule belongs, each marked
"after the review of PR 42": a view whose frames end retires at once (finding
12); the attach is one effect from the claim on (13); a view that resumes
closes when its input fails, and admission takes a page id in lower case
only (14); `remove` allows a missing child (17, checked, no change of code);
the cleanup steps every record that it takes (18). With cats-effect 3.5 or
newer a waiting poll could not be cancelled (21); `promise` of that instance
can be cancelled now, as on the version that the build pins.

The reviews after it (at `92d73daf`, `82baf17a` and `fe313e1a`) found client
rules of the heartbeat and of the recovery after an outage, which the client
table above now has, and three more points that the text above has: the
reservation of the fresh-page record while its state is stored, the request
timeout of the poll route only, and when the retry after a hanging response
comes in time.

## Changes from revision 3

After the review of the implementation plan, and after the user narrowed the
storage cleanup. Every point of the review was checked in the code, and all
of them hold. The last two rows go beyond the review.

| Revision 3 | Why it changes | Revision 4 |
| --- | --- | --- |
| A release removes the state of a session without a view, from every storage | `fromRepository.remove` returns when the deletion has only started, so a release that has "finished" can still delete the state of a working view (review, point 1). And one server cannot know that a shared row has no owner (point 2) | A state is removed only from a storage that is local to this process |
| A session whose page is told to reload gives up its state | Its case was a repository after a restart, which point 2 rules out | No case is left, and no code |
| A late first WebSocket attach "goes on as `main` does when a state is missing" | `main` builds the state from the bridge request: a view of another page (point 3; run) | A missing state means reload, on both transports. The user's decision |
| `spoonbill-dom` is "the revision that the page shows", taken from what the view has published | A frame that is not a DOM frame can be answered after a newer patch was published (point 4) | The delivery counts the DOM frames that it hands out. The page counts too and compares |
| A user event travels in the envelope | An event without it would skip the check (point 4) | A view of a long-polling entry refuses it |
| The next render decides whether a first poll comes too late | The answer depended on other traffic | The age is checked at the claim. A render only cleans up |
| The record is made in the step that stores the state | A cancelled render could leave a state without a record | The record is made first |
| Progress resets the idle timer, next to the messages | The watcher resets on every message, so a page that never moves on stayed alive by its heartbeats | For a view of a long-polling entry only progress resets it |
| A release takes the place of the session in the table of views | With removals that have ended when `remove` returns, the machine can hold the fence itself: a record that is `Releasing` refuses every attach | No release step. `Expire`, the removal and `Released`, with the claim in the same machine |
| A second framework reload within the window does not happen, and the page stops | With decision 25 two honest reloads can come close together | It waits for the end of the window, and the guard holds for both transports |

## Changes from revision 2

On 2026-11-22 the user asked whether the design covers a defect that is on
`main`: the stored state of a page that never connects is never removed. It
did not, and the user decided to cover it here.

| Revision 2 | Why it changes | Revision 3 |
| --- | --- | --- |
| The stored state of a page that never connects stays for good, as on `main`; each such page also keeps a fresh-page record | The defect is taken into this design | A record that nothing claims expires at a later render, and its state is released |
| A page that is told to reload leaves its stored state when no view owns it | With a repository that is every page that comes back after a restart | The session is released |
| The fresh-page record is "in memory, as long as the state is unattached" | It had no home and no end | A field of `SessionRegistryMachine`, which keeps such a record for guarded pages already; `Claim` and `Expire` are its two ends |
| A removal could meet an attach | A state must not be removed under a view | One release step that takes the session's place in the table of views |

**Refined on 2026-11-22 while the implementation plan was written:** the
revision of the page is not a query parameter. A user event travels in the
envelope of guarded pages, and the page id is a UUID that the view takes as
its connection id. Both reuse what the server and the client already have.

## Changes from revision 1

After the review of revision 1. Every point of it was checked in the code,
and all of them hold.

| Revision 1 | Why it was wrong | Revision 2 |
| --- | --- | --- |
| A user event is checked when its message arrives, against the latest DOM frame that a pump has recorded | A render can win after the check, and the pump can lag behind the view (trial A) | The view checks at selection, with the revision and the handlers that it published together |
| A pump takes every frame out of the view at once and keeps a log | The view drops an expired browser command only when it is pulled (trial B) | A frame is made when a poll asks; one frame is kept |
| The rule "a view is registered" is checked inside `getFill` | `getFill` does not run the factory when an entry exists or is being filled; and a late WebSocket could take the output of the long-polling view | The attach result names the owner; a transport that does not own the view gets no output |
| A mark "has had a view" in the stored state | Reading it moves a retired state back among the live ones | A fresh-page record made at render time, taken by the first attach |
| An old server cannot be told from a new one | It ignores the cursor and sends the next frame | Every answer names its frame; the page checks it |
| The polls that wait are handles in the interpreter | A frame made between "wait" and the registration is missed | The waiters are in the machine; each is settled once |
| Every poll is a sign of life | A page that polls and never moves on keeps its view for ever | Only progress is |
| No deadline in the client; `503` means "poll again" | A hanging response stops the page until the heartbeat limit reloads it; a `503` loop spins | A deadline, a re-poll at the heartbeat limit, `503` as a failure |
| The reload guard: no second reload before a frame is applied | Every page applies frame 1 before it is sent away | One framework reload in a time window |
| History events are discarded like clicks | The browser has changed the URL already | Always handled |
| A message with `seen` ahead is delivered | It claims frames that the view never handed out | `410` |

## Record

- Pull request 40 was reviewed three times from outside. The first finding
  of the third review ended the handoff approach.
- The user asked on 2026-11-21 for the option that the user of a page does
  not notice. Resume is that option.
- A further review backed it and named three things to settle.
- Revision 1 was reviewed. The user chose to run trials first; the two
  trials that the review proposed are trials A and B.
- On 2026-11-22 the user took the stored state of a page that never connects
  into this design (revision 3).
- The implementation plan and revision 3 were reviewed. The user narrowed
  the storage cleanup after that review (revision 4).
- On 2026-11-22 the user confirmed decision 25 with its larger consequence in
  view, and asked for an ADR for it:
  [ADR 0006](adr/0006-legacy-attach-needs-stored-state.md).
