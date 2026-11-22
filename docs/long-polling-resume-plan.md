# Long polling that resumes: implementation plan

Status: **second version**, 2026-11-22. For review. **Nothing is built.** This
plan implements the design "Long polling that resumes", revision 4. The
design says what and why; this file says in which order, in which files, and
with which tests.

**What is not in the repository.** The design, the reviews of the design and
of this plan, and the reports of the defects that this plan names are working
papers. This plan names them where it leans on them, and the record of its
own review is at the end.

**The idea in short.** A page on long polling loses a poll now and then. On
`main` the page then builds a new bridge, the server keeps the old view, and
the page answers no click any more. With this plan the page keeps its bridge
and its view. Every request says which frame the page has applied, and the
server sends exactly the next one. Everything uncertain ends in a reload.

The first version of this plan was reviewed, and the user narrowed the
storage cleanup after that review. What changed against the first version:

- **Nothing new is deleted from a repository.** States are removed only from
  a storage that is local to this process (step 6).
- **A missing state means reload**, on both transports. It is a step of its
  own (step 4), and it changes 11 existing test cases.
- **The removal is out of step 1.** Step 1 only makes a failed attach not
  final.
- **The DOM revision of a frame** is counted by the delivery, and a view of a
  long-polling entry refuses an event without the envelope (steps 3 and 5).
- **Every test that the review asked for is in a step**, and two of them come
  before the code that they guard. See
  [Review of this plan](#review-of-this-plan).

**Decision 25 is confirmed.** It reaches further than was first said: with a
storage over a repository a WebSocket page reloads at every reconnect after a
closed socket. With that in view the user confirmed it on 2026-11-22 and
asked for an ADR, so that the decision can be revisited and redesigned later.
The ADR is drafted. It comes into the repository in step 9, as the earlier
ADRs came with their changes.

## What is true when the plan is done

- A long-polling page that loses a poll keeps its bridge and its view, gets
  the frame it missed, and shows nothing but a short delay.
- A click that was made on an older display runs no handler.
- A page whose view is gone, or that is out of step, reloads once.
- A poll builds a view once for a page, and the output of a view has one
  consumer.
- No view of an unguarded session starts from a state that was built for a
  bridge request. A page whose stored state is missing reloads.
- With the default storage, a page that nothing has claimed does not keep its
  state. From any other storage this change deletes nothing.
- A failed attach is not final for the session, and a message that no view
  can take is answered with `410`. These two come from pull request
  [#40](https://github.com/natural-transformation/spoonbill/pull/40).
- ADR 0006 is in the repository: a missing state means reload, what that
  costs, when to revisit it, and what a redesign has to keep.
- Every decision is in a pure machine with a table spec. The purity guard
  passes with the new files.
- The test command of CI, the Node client tests, the acceptance script and
  the browser cases pass. The two controls of ADR 0004 and one long-polling
  measurement are run and reported.

## What the plan assumes

The design has 28 decisions. For each one that is open, the plan takes the
recommendation of the design. A changed row moves the steps in the last
column.

| # | Decision | The plan assumes | Steps |
| --- | --- | --- | --- |
| 1 | Where a user event is checked | In the view, where the handler is selected | 3 |
| 2 | Which views publish revisions | Only a view that a long-polling entry owns | 3 |
| 3 | A kept command that has expired | A keep-alive frame under the same number | 5 |
| 4 | Custom callbacks | Checked like DOM events | 3 |
| 5 | The view is gone | Reload | 5, 7 |
| 6 | A request without a cursor | Reload | 5 |
| 7 | A patch that does not apply | Reload, on long polling | 7 |
| 8 | A message before the first poll | `410` | 5 |
| 9 | The orphan timer and `OrphanTopicMachine` | Removed | 5 |
| 10 | Development mode | A long-polling page reloads after a restart | 5 |
| 11 | A cap in bytes | None | none |
| 12 | Waiting | A poll waits half the idle timeout; the client's deadline is that plus 5 seconds | 5, 7 |
| 13 | The heartbeat limit on long polling | Poll again, no reload | 7 |
| 14 | The reload guard | One framework reload in 30 seconds for a tab, on both transports of an unguarded page; a second one waits for the end of the window | 7 |
| 15 | The bound of the fresh-page records | By age only | 2, 5 |
| 16 | Who makes the page id | The page | 2, 7 |
| 17 | Branch and pull request | A new branch and a new pull request from `main` | all |
| 18 | An ADR entry | **ADR 0006 for decision 25, at the user's word.** For the delivery rule: not written; to be asked when the work is built | 9 |
| 19 | A page that nothing has claimed expires | **Decided by the user: yes; its state is removed only from a local storage** | 5, 6 |
| 20 | A session told to reload gives up its state | **Decided by the user, then narrowed: no case is left, no code** | none |
| 21 | How long a record lives | The idle timeout of a session; the age is checked at the claim | 2, 5 |
| 22 | What starts the cleanup | The next render, at most 100 records, no timer | 5, 6 |
| 23 | Guarded pages | Not covered | none |
| 24 | An attach that fails before it has a view | Removes the stored state, in a local storage | 6 |
| 25 | A missing state at an unguarded attach | **Decided and confirmed by the user: reload, on both transports, with every storage** | 4 |
| 26 | How a local storage is known | A member of `StateStorage` that only the framework sees | 6 |
| 27 | Where the numbers of a frame travel | In two response headers | 5, 7 |
| 28 | A counter for the reloads of decision 25 | One counter in `Metrics`. Proposed with the ADR, not decided | 4 |

## Where the work happens

- **A new branch from `origin/main`:** `claude/long-poll-resume`. The base is
  fetched and checked first.
- **Pull request 40 stays open** until the implementation has its own pull
  request: its branch is the source for the two parts that stay. Closing it
  is the user's call.
- **Commits stay local.** Nothing is pushed, and no pull request is opened,
  before the user says so after step 9.
- If decision 17 changes to "stay on the branch of pull request 40", a step 0
  comes first that removes the handoff.

## The order with pull requests 39 and 40

This plan comes before both, in two different ways. Both are open.

- **Pull request [#40](https://github.com/natural-transformation/spoonbill/pull/40)
  is not merged at all. This plan takes its place.** Its handoff gives a
  reconnecting page a new view. The third review of it showed the price:
  after a lost patch the page shows one state and runs the handlers of
  another. This plan removes the handoff. The two parts of pull request 40
  that stay are in steps 1 and 5. It stays open as the source for them until
  the implementation has its own pull request, and is closed then (decision
  17; closing is the user's call). A seventh commit of it, which answers
  `410` for a message that no view can take, is local and was not pushed. It
  needs no push: step 5 carries its cases.
- **Pull request [#39](https://github.com/natural-transformation/spoonbill/pull/39)
  comes after this plan, and is reworked on top of it.**
  - Its review found that it must not merge before the server knows how a
    long-polling page goes on after a pause. With its client,
    `Spoonbill.disconnect(true)` leaves a long-polling page deaf to clicks;
    on `main` that call does nothing and the page works (run, in the review
    of pull request 39). The user decided on 2026-11-21: the server fix
    first, pull request 39 on top. The server fix was pull request 40 then.
    It is this plan now.
  - Its long-polling half is written for the fix of pull request 40:
    `disconnect()` runs the close lifecycle, the page builds a new bridge,
    and the server starts a new view. This plan does the opposite: the view
    and the bridge stay. On top of this plan that half would destroy the
    bridge of a view that lives on, and the page would be deaf again or
    reload at its next frame (read, not run). It has to be written again:
    `disconnect` stops the polls and keeps the bridge, `connect` goes on
    with the same view, and the page reloads when that view has ended.
  - Both change the same function of `connection.js`, the long-polling
    transport. Step 7 rewrites it. Pull request 39 first would mean writing
    its long-polling half twice.
  - Its WebSocket half does not depend on this plan. It needs one new look
    afterwards: a socket that replaces an open one is a reconnect for the
    server, and with decision 25 a reconnect with a repository reloads the
    page.
- **Alternative for pull request 39: split it now.** A small pull request
  with only the WebSocket half (one connection attempt at a time; a new
  socket closes the one it replaces) can merge before this plan. It repairs
  a page that is dead on `main` after `Spoonbill.connect()` on an open
  socket. Only application code that calls that global reaches the defect.
  The split is needed only if this repair cannot wait for the plan.
- **Both pull requests say that they wait.** Pull request 40 is a draft
  since 2026-11-22, and the descriptions of both name this plan.

## Steps

Each step is one commit. Its tests are written first and must fail without
the change. A case that guards against a change, and so passes before it, is
proved by a mutation: the forbidden behaviour is put in, the case fails, and
the mutation is taken out again by its inverse edit. After each step: the
specs of the step ten times, `spoonbill/test` once, the purity guard, and a
list of every cell, flag and `if` that the diff adds outside a machine.

**Steps 5 and 7 change the two sides of one protocol.** Between them the
server of the branch asks for a cursor and its client does not send one, so a
long-polling page of that state reloads. The pull request is merged as one
change. Step 5 is checked through the service specs and its fixture; the
browser checks of long polling run from step 7 on.

### Step 1: a failed attach is not final

Carried over from pull request 40 (`7dde64b9`, in the machine form of
`7dcda721`), without owners, without the restart for a waiting poll, and
**without any new removal**.

- **Tests first.** `LegacyFailedAttachSpec` with its four cases. Rows for
  `NothingBuilt`, `Published` and `Forget` in `LegacySessionMachineSpec`.
  `ExtensionSessionChecks.cancelledLegacyRollback` and its two runners.
- **Changes.** `LegacySessionMachine` (the rollback has a last step, "forget
  the failure"). `SessionsService.createLegacy` (`abandon`, the failure
  travels with what the rollback knew).

### Step 2: the pure machines and their tables

No interpreter uses them yet, so behaviour does not change.

- **`PollDeliveryMachine`** with `PollDeliveryMachineSpec`: every row of the
  table in the design, with `dom` and the kind of the kept frame. Three
  properties over generated event sequences: a waiter is settled at most
  once; at most one frame is handed out and not confirmed; the DOM revision
  of an answer is the number of DOM frames among the frames up to it.
- **`PollAdmissionMachine`** with its spec: the parse of `page` (a UUID) and
  `seen`; the table "Which poll builds a view"; what each transport does with
  each answer of a claim; who may have the output of a view.
- **`SessionRegistryMachine`:** a field for the fresh-page record, with
  `rendered`, `claimFresh`, `expire`, `released` and `removalFailed`. Rows:
  - a claim and an expiry of one record, in both orders: one wins;
  - `Fresh` up to the lifetime and `Expired` after it, by the time that the
    claim gets;
  - a poll leaves an expired record where it is, a WebSocket takes it;
  - a claimed record cannot expire;
  - a record that is `Releasing` answers `Releasing` and is not given out a
    second time;
  - `removalFailed` puts the record back, and a later `expire` gives it out
    again;
  - `expire` gives the oldest first and at most the limit;
  - without removal (`removes = false`) an expired record just ends;
  - no count pushes a record out.
- **`FrontendConnectionMachine`:** no change expected. Rows for the sequence
  of an unguarded view: the accepted first render is `Published(0)`, each DOM
  frame adds one.
- **`connection-machine.js`:** the page id, `seen`, `dom`, `failures`, the
  link, the check of the two headers, and the pure reload guard (reload now,
  or wait so many milliseconds). Cases in `misc/connection-machine-tests.mjs`.
- **`bridge-machine.js`:** a long-polling bridge starts with a view
  (connection = page id, revision 0); `frame-applied` adds one after a DOM
  frame. Cases in `misc/bridge-machine-tests.mjs`.

### Step 3: the view checks a user event where it selects the handler

The step that touches the event path that guarded views share. It comes
early, so that its measurements can stop the plan before the rest is built.

- **Tests first**, in a new `ResumingViewSpec` that builds an
  `ApplicationInstance` as `RenderLifecycleSpec` does:
  - The page is behind and clicks: no handler, the event is counted.
  - A held click: it arrives while nothing newer is published, a render is
    published, then its handler is selected: no handler. The seam is the
    `awaitReady` parameter that `ApplicationInstance` has; no seam is added
    to the sources.
  - After the catch-up the click runs the handler of the new stage.
  - A custom callback of an older revision is discarded; a history event of
    an older revision is handled.
  - **A DOM event and a custom callback without the envelope run no handler**
    and are reported.
  - All three ways to render publish their handlers: the first render, a
    later render, the start from a saved render context
    (`DevModeSavedRenderContextSpec` gets a resuming case).
  - `outgoingFrames`: a command that expires in the queue is not handed out;
    a handed-out command knows when it has expired; each frame says whether
    it is a DOM frame. Next to the cases of `BrowserRpcLifecycleSpec`.
- **Changes.** `Frontend`: a parameter that says "this view resumes"; for
  such a view the unguarded publication step advances `View.Published` and
  stores the handler snapshot in one critical section, the first render sets
  `Published(0)`, the envelope is accepted, an event without it is refused,
  and a custom callback is checked before it runs; `outgoingFrames` gives
  each frame with its kind and with "is its command still awaited".
  `ApplicationInstance`: the handler snapshot in the three render paths, and
  the selection from it.
- **Control.** The guarded suites pass unchanged. Then the event-latency
  harness (`misc/event-latency`) and the four workloads of the gate
  (`misc/extension-contention`), base against this step. If a number leaves
  the budget of ADR 0004, the work stops with a report before step 4.

### Step 4: an unguarded attach needs its stored state

Decision 25, confirmed by the user. A small change in the sources and a
wider one in the tests.

- **Tests first.**
  - A router that depends on the address. A page is rendered at
    `/orders/42`, its row is deleted from a repository, and it attaches for
    the first time by WebSocket: the reload frame, no view, and the state
    loader ran once, for the render. The scratch probe that showed the
    defect on `main` becomes this case.
  - A repository; a WebSocket page whose socket closed reconnects: the
    reload frame. With the default storage it goes on from its state. The
    scratch probe of the reconnect becomes this case.
  - Two attaches of a session without a state at the same time: both are
    told to reload, and nothing is stored.
  - A failed attach is still not answered with a reload: the cases of
    step 1 pass unchanged.
  - Each reload for a missing state adds one to its counter, and no other
    reload does (decision 28).
- **The 11 cases that rely on the rebuild.** An inventory run found them:
  the rule as a throwaway change on `main`, and the test command of CI once.
  11 of 2,287 cases failed, in 7 suites.
  - `StandaloneMissingViewReloadSpec`, `AkkaMissingLocalViewReloadSpec`,
    `PekkoMissingLocalViewReloadSpec`, one case each: the reload frame stays;
    the count of rebuilds becomes 0.
  - `SessionsServiceSpec`: "rebuild missing state and create app" becomes
    "tell a session without a stored state to reload". Two reconnect cases
    render their page first.
  - `LegacyStreamFailureSpec`, two cases: they render their page first.
  - `ExtensionContractSpec`: "attach a view whose state was rebuilt" becomes
    "tell a session without a stored state to reload, and set up no
    extension". The case of a failed setup after a rebuild goes: its reason
    was that a reload would rebuild and fail again without an end, and
    nothing rebuilds any more.
  - `InvalidDevRenderContextSpec`, the variant without a prior state: every
    connection is told to reload, no state is read, and the saved file is
    not touched.
- **Changes.** `SessionsService.createLegacy`: the branch that rebuilds
  answers Reload; its comments are rewritten, because they describe the
  rebuild. `initAppState` says in its comment that a page render is its only
  caller. `Metrics`: one counter for these reloads (decision 28).
- **Checks of this step, beyond the usual:** the Node client tests, the
  acceptance script and the browser tests, because the inventory did not run
  them.

### Step 5: the server delivers by the cursor

- **Tests first.** A new `LongPollingFixture` (a page render, a poll with
  page and cursor, a message, the response headers, a clock) and
  `LongPollingDeliverySpec`. **The first case written is the DOM revision:**
  - A heartbeat answer is made at revision 0, patch 1 is published, then the
    page polls: the heartbeat is answered with `spoonbill-dom: 0`, the patch
    with 1.
  - The first response is lost and the view lives: frame 1 again.
  - Two polls with one cursor get the same frame, from one pull.
  - A poll waits, and is answered with `204` at half the idle timeout.
  - **Only progress resets the idle timer.** One frame is withheld, the page
    asks for it again and again and keeps sending heartbeats and clicks: the
    view ends after the idle timeout. With the heartbeat off, a page whose
    poll waits keeps its view over two idle periods.
  - A view ends while a poll waits: the reload frame.
  - `seen` ahead, another page id, no cursor: Reload. `seen` behind: `410`.
  - A first poll for a session without a fresh-page record, as after a
    restart of the server: Reload. A first poll after a WebSocket has taken
    the record: Reload.
  - **One page is rendered, its lifetime passes, nothing else is rendered,
    then it polls: Reload.**
  - An attach makes no record. A render that fails after its record leaves a
    record and no state.
  - 250 pages are rendered and never connect; after their lifetime three
    renders end their records, 100 at a time. Nothing is removed from the
    storage in this step.
  - The development-mode specs pass unchanged (`InvalidDevRenderContextSpec`
    after step 4, `DevModeSavedRenderContextSpec`).
  - A message for an ended view, a closed queue or with `seen` ahead: `410`.
    These carry the two cases of the unpushed commit of pull request 40.
  - Every frame answer names its frame and its DOM revision.
  - The kept frame is an `evalJs` that has expired: a keep-alive frame under
    that number, with the same DOM revision.
  - A WebSocket that arrives after the poll built the view gets no frame, and
    closing it does not close the view. And the other order.
  - With a lazy effect (`LongPollingSessionChecks`, run by cats-effect and
    ZIO): the winning poll is cancelled right after `putIfAbsent` and no
    entry stays; a poll is cancelled after the pull and the frame is neither
    skipped nor handed out as two; a frame is made between "wait" and the
    registration of the waiter and the waiter gets it; **a second poll joins
    the first and the first is cancelled, before the record is claimed and
    after the entry owns the view: the second gets the frame or Reload, does
    not hang, and no second view is built**; a render is cancelled after its
    record is made and before the state is stored.
- **Changes.**
  - `MessagingService`: an entry names its page and holds the delivery; a
    message never makes an entry. Every answer of the poll route keeps the
    common headers with `cache-control: no-cache`, the `204` too.
  - A new `LongPollingDelivery`, the interpreter of `PollDeliveryMachine`:
    the state in a `StateCell`, one completion handle for each waiter, the
    one pull, the wait limit.
  - `SessionStart`: the attach result says who owns the view.
  - `SessionsService`: for an unguarded session `initAppState` makes the
    fresh-page record before it stores the state, and ends up to 100 expired
    records; every unguarded attach claims the record before it looks into
    the storage; `createAppIfNeeded` gets the transport and the owner of a
    view; for a long-polling entry the stream that resets the idle timer is
    the progress of the delivery, and no longer the incoming messages. The
    time comes through a constructor parameter with `System.nanoTime` as its
    default.
  - `KorolevServiceImpl`: the publish route passes its request on.
  - `PageService`: the page configuration names the wait limit.
  - Removed: `OrphanTopicMachine`, its spec and its timer.

### Step 6: the cleanup in a local storage

- **Tests first**, in a new `UnclaimedPageSpec`, with a clock that the spec
  moves. **The first two cases written are the guards.** They pass before the
  step, so each is proved by a mutation.
  - **Held deletion.** A repository whose `removeRepository` is held. Pages
    expire, a poll is told to reload, an attach fails before it has a view:
    `removeRepository` is not called. Mutation: the removal without the
    question "is the storage local".
  - **Two servers, one repository.** A poll reaches the service that has no
    view of the session: Reload. The row is intact in the repository itself,
    not only in a cache, and the view on the other service goes on.
    Mutation: a removal for a page that is told to reload.
  - A default storage with a for-deletion cache of 2. Five pages expire, and
    one more has a view: the three oldest states are gone, the view keeps
    its state.
  - A claim while the record is `Releasing`: Reload, and the storage is not
    read. The storage of this case is a local one that holds its `remove`.
  - A removal that throws: it is reported, the record is back, and the next
    render removes the state.
  - An attach that fails before it has a view, and whose page never comes
    back: the state leaves the live states of the default storage.
  - With a lazy effect: a render is cancelled right after it has selected
    expired records; afterwards a later render leaves no state live without
    a record or a view.
- **Changes.** `StateStorage`: the member that says "local to this process",
  false by default, true in `DefaultStateStorage`. `SessionsService`: for a
  local storage the cleanup of a render removes the state of each record
  that `expire` gave out, and then steps `released`; `abandon` removes the
  state when the attach had no view and the storage is local.

### Step 7: the client

- **Tests first**, in the Node files that CI runs
  (`misc/connection-lifecycle-tests.mjs`, `misc/redesign-client-tests.mjs`):
  - A failed poll: no `close`, the bridge stays, the next poll carries the
    same cursor.
  - A `200` without `spoonbill-frame` or `spoonbill-dom`, or with a number
    that does not fit: reload, and nothing is applied.
  - A patch that throws, and a patch whose node is missing: no count, reload.
  - A poll that is not answered by its deadline is a failed poll.
  - The heartbeat limit is reached: the poll is given up and sent again; no
    reload. No heartbeat is sent while the link is interrupted.
  - `503` again and again: retries with the back-off.
  - The notice shows at the second failure in a row and hides at the next
    answer, **also when that answer is a `204`**, and the heartbeat goes on;
    a patch that is applied while it shows is not disturbed.
  - An answer to a request that was given up changes nothing.
  - The reload guard, on long polling and on a WebSocket of an unguarded
    page: the first framework reload happens at once; a second one within 30
    seconds waits for the end of the window, with the notice. Without
    `sessionStorage` the page reloads at once. A guarded page reloads as
    before.
  - A page that fell back and is told to reload starts with long polling
    after the reload; a page whose fallback works notes nothing.
  - A user event is sent in the envelope, with the page id and the DOM
    revision of the page.
- **Changes.** `connection.js`: the transport loop. `bridge.js`: the result
  of a frame goes to the transport; the check of the DOM revision before a
  frame runs; the reload guard for the reload frame of an unguarded page, on
  both transports; the heartbeat. `spoonbill.js`: `modifyDom` says whether
  every command found its node. `launcher.js`: the events `interrupt` and
  `resume`.

### Step 8: both sides in a browser

- **The fixture** gets a scenario of its own with an input field, two
  buttons and a route, one control that ends the view of a session, and one
  that removes the stored state of a session.
- **`misc/long-polling-native-tests.cjs`**, in Chromium and WebKit:
  - A poll fails: the page stays ready, keeps its bridge, and gets the lost
    frame and the frames after it.
  - The DOM patch is dropped and the user clicks before the retry: the
    handler of the new state does not run. The same with the response held.
  - After the catch-up the click works, and an element that was not touched
    keeps its focus and its unsent input.
  - Back is pressed while a patch is on its way: URL and page agree.
  - The view ends: the page reloads once and works.
  - **A page at a route loses its stored state before it connects, by
    WebSocket and by long polling: after one reload it shows the route
    again, and a click runs the handler of that page.**
- **The long-polling measurement.** The event-latency harness uses a
  WebSocket (read: `misc/event-latency/README.md`). One more run of it with
  the WebSocket blocked, so that the page uses long polling: the time from a
  click to its patch, `main` against the change, alternated.

### Step 9: words and checks

- **ADR 0006.** The draft becomes
  `docs/adr/0006-legacy-attach-needs-stored-state.md`. Its places marked
  "at commit" get their links: the attach of step 4 and its cases, the
  counter, the reload guard of step 7. `docs/adr/README.md` gets its row:
  "A legacy attach, a missing session state, or when a page is told to
  reload". Every link of the file is checked, and that the text still fits
  what was built.
- `CHANGELOG.md`, with a note for deployments: a page whose stored state is
  missing reloads; what that means with a repository and with several
  servers; where the ADR is. `docs/browser-tests.md`. The text of the pull
  request.
- The status of this plan: what was built, and where it differs.
- The whole verification below, and the report for the user.

## Verification at the end

| Check | How |
| --- | --- |
| The specs of the change | Ten runs; with cats-effect and ZIO three runs each |
| The guard cases of step 6 | Each fails under its mutation, once; the mutation is undone by its inverse edit |
| The test command of CI | `scripts/with-test-postgres.sh` with `compile`, `Test / compile`, `test`, one test at a time |
| The Node client tests | The `node --test` command of `.github/workflows/scala.yml` |
| The acceptance script | `scripts/test-renderer-acceptance.sh` |
| The browser cases | Twenty rounds in each engine |
| The purity guard | `scripts/check-core-purity.mjs` |
| The controls of ADR 0004 | After step 3 and again at the end; base and candidate alternated |
| The long-polling measurement | At the end; base and candidate alternated |
| No hidden state | The list of what the diff adds outside machines, in the report |

Every pass is reported with the number of runs behind it. A failure in a
module that the change does not touch is rerun alone and reported, not waved
through.

## The files

- **New, main:** `PollDeliveryMachine.scala`, `PollAdmissionMachine.scala`,
  `LongPollingDelivery.scala`, in `server/internal/services`.
- **Changed, main:** `MessagingService.scala`, `SessionsService.scala`,
  `SessionStart.scala`, `SessionRegistryMachine.scala`,
  `LegacySessionMachine.scala`, `PageService.scala`, in
  `server/internal/services`; `server/internal/KorolevServiceImpl.scala`;
  `server/package.scala` (it builds the messaging service);
  `state/StateStorage.scala`; `internal/Frontend.scala`,
  `internal/ApplicationInstance.scala`; `es6/connection-machine.js`,
  `connection.js`, `bridge-machine.js`, `bridge.js`, `spoonbill.js`,
  `launcher.js`.
- **Changed, main, for decision 28:** `Metrics.scala`.
- **New and changed, documents:**
  `docs/adr/0006-legacy-attach-needs-stored-state.md`, `docs/adr/README.md`,
  `CHANGELOG.md`, `docs/browser-tests.md`, and this plan.
- **Removed, main:** `OrphanTopicMachine.scala`.
- **New, tests:** `PollDeliveryMachineSpec`, `PollAdmissionMachineSpec`,
  `LongPollingFixture`, `LongPollingDeliverySpec`, `UnclaimedPageSpec`,
  `ResumingViewSpec`, `LegacyFailedAttachSpec`, `LongPollingSessionChecks`
  with its two runners, `misc/long-polling-native-tests.cjs`.
- **Changed, tests:** `SessionRegistryMachineSpec`,
  `LegacySessionMachineSpec`, `FrontendConnectionMachineSpec`,
  `MessagingServiceSpec`, `DevModeSavedRenderContextSpec`,
  `ExtensionSessionChecks`; the seven suites of step 4; the fixture of the
  acceptance tests, the Node test files, `scripts/test-renderer-acceptance.sh`.
- **Removed, tests:** `OrphanTopicMachineSpec`.
- `SessionsService.scala` and `Frontend.scala` are far over 400 lines on
  `main`. New code goes into new files where it can. The old files are not
  split in this change.
- The build has no check of binary compatibility (read: no such plug-in in
  `build.sbt` or `project/`). The new member of `StateStorage` has a body, so
  a storage that an application wrote compiles and links as before.

## When the work stops for a decision

- A control of ADR 0004 leaves its budget after step 3, or the long-polling
  measurement shows a loss.
- The three render paths cannot publish their handlers in one step with the
  frame.
- A rule of the design turns out wrong or incomplete while it is built. The
  design is changed first, and the change is reported.
- A test cannot be made to fail before its change, or a guard case does not
  fail under its mutation.
- Step 4 breaks a test outside the 11 cases of the inventory.
- Two failed attempts at the same problem.
- Anything that needs a push, a pull request, a change to pull request 40,
  or an ADR other than 0006.

## Not in this plan

- A WebSocket reconnect that finds its state, and two WebSockets for one
  session.
- Guarded pages that never connect.
- Everything that a storage other than the local one keeps: the rows of
  pages that never connect, of a failed attach, and after a restart.
- The deletion at a retirement with a repository, which returns before it
  has ended. It is a known defect on `main`.
- `Spoonbill.disconnect()` and `connect()` on long polling: pull request 39.
  It needs a new look after this change.
- The other known defects on `main` that this plan does not name.

## Review of this plan

The first version was reviewed on 2026-11-22. Each point of the review was
checked in the code, and all of them hold. Where each one is now:

| Point of the review | Checked | Where it is in this version |
| --- | --- | --- |
| 1. A release that has "finished" can still delete the state of a working view: `fromRepository.remove` returns when the deletion has only started | Read: `StateStorage.scala`, lines 271 to 278 | No removal from such a storage. A removal needs a storage whose `remove` has ended when it returns (step 6). The removal is out of step 1 |
| 2. "No view here" is not "no view anywhere" | Read. It holds for the expiry too | The same condition: the storage is local to this process. The guard case "two servers, one repository" (step 6) |
| 3. A late first WebSocket attach after a deletion builds its state from the bridge request | Run, also on `main`. After the review the ordinary reconnect was run too: with a repository it ends the same way on `main` | Decision 25 and step 4: a missing state means reload |
| 4. The revision header must say what the page shows after that frame; an event without the envelope must not run | Read: `Frontend.scala`, lines 105 to 118 and 1010 | The delivery counts the DOM frames that it hands out; the page counts too (steps 2, 5, 7). A view of a long-polling entry refuses the event (step 3) |

| Test of the review | Where it is |
| --- | --- |
| A page older than its lifetime, and no later render: Reload at its first poll | Step 5. The age is checked at the claim (step 2) |
| Cancel between the storage write and the record; cancel between selecting expired records and removing; records only from real renders | The record is made first (step 5). The second cancel is a case of step 6. "An attach makes no record" is a case of step 5, and since step 4 no attach calls `initAppState` |
| The cursor does not move while heartbeats keep coming: the view must still expire. A `204` after an interruption clears the notice | Step 5, and step 7 for the notice |
| A for-deletion cache of 2 and more than 2 expired pages, with a claimed view as a control | Step 6 |
| A duplicate poll joins the winner, and the winner is cancelled | Step 5, before and after the claim |
| A matched long-poll measurement | Step 8 |
| The held-deletion test and the test of the revision on a frame that is not a DOM frame come before the cleanup and the client loop | They are the first cases of step 6 and of step 5; the client is step 7 |

**Added in this version beyond the review:** the inventory of the tests that
rely on the rebuild (step 4); the run of the reconnect with a repository,
which is why decision 25 went back to the user for confirmation; the mutation
proof for a guard case; the reload guard that waits and leaves guarded pages
alone (decision 14); the note that steps 5 and 7 are the two sides of one
protocol; and decision 27, found when the design was walked through what
stands between a page and the server.

**Added on 2026-11-22 after the confirmation:** decision 25 is confirmed; ADR
0006 is drafted and is part of step 9; and a counter for the reloads of
decision 25 is proposed (decision 28), so that the ADR can be revisited with
a number.
