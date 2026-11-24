// Long polling that resumes, against the actual Scala service, in Chromium
// and WebKit. A page that loses a poll keeps its bridge and its view, gets the
// frame that it missed, and a click made on an older display runs no handler.
// Runs through scripts/test-renderer-acceptance.sh, on that script's fixture
// (the `resume` scenario of RendererAcceptanceBrowserFixture).
'use strict';
const assert = require('node:assert/strict');
const {setTimeout: pause} = require('node:timers/promises');
assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_F2_ORIGIN, 'Start through scripts/test-renderer-acceptance.sh');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_F2_ORIGIN).origin;
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
const engines = (process.env.SPOONBILL_LP_ENGINES || 'chromium,webkit').split(',');
const rounds = Number(process.env.SPOONBILL_LP_ROUNDS || '1');
const clip = text => String(text).split('\n')[0].slice(0, 300);

async function control(context, command, timeout = 10000) {
  const response = await context.request.post(`${origin}/fixture/${command}`, {timeout});
  assert.equal(response.status(), 200, `Control ${command} must finish within its bound`);
  const text = await response.text();
  return text.startsWith('{') ? JSON.parse(text) : text;
}

// Waits until `condition` holds, within `limit` milliseconds.
async function waitFor(condition, message, limit = 10000) {
  for (const deadline = Date.now() + limit; !(await condition()); await pause(20))
    assert.ok(Date.now() < deadline, message);
}

// Whether the page is ready. A page that is being reloaded is not.
const ready = page => page.evaluate(() => window.Spoonbill && window.Spoonbill.ready === true).catch(() => false);

/**
 * Whether the page listens for clicks. On long polling `Spoonbill.ready` turns
 * true with the first answer, and the frame that makes the client listen for
 * clicks comes in a later one; a click before that frame is applied is never
 * sent. On long polling the frame counts as applied once a later poll of the
 * same page names it; on a WebSocket once it reached the newest socket.
 */
function listening(state, polling) {
  const listen = body => body.startsWith('[2,"click"');
  if (!polling) return (state.sockets.at(-1) || []).some(listen);
  const last = state.polled.at(-1);
  return last !== undefined && state.answers.some(answer => answer.page === last.page && listen(answer.body) &&
    state.polled.some(poll => poll.page === last.page && poll.seen >= Number(answer.frame)));
}

/** Waits until the page is ready and listens for clicks. */
async function usable(page, state, polling, message) {
  await waitFor(async () => (await ready(page)) && listening(state, polling), message);
}

/**
 * Whether the page has applied the completion of a navigation (procedure 24),
 * of navigation `number` if given, which reopens its input: on long polling
 * once a later poll of the same page names its frame, on a WebSocket once it
 * reached the newest socket.
 */
function completed(state, polling, number) {
  const done = body => number === undefined ? body.startsWith('[24,') : new RegExp(`^\\[24,${number}\\s*\\]$`).test(body);
  if (!polling) return (state.sockets.at(-1) || []).some(done);
  const last = state.polled.at(-1);
  return last !== undefined && state.answers.some(answer => answer.page === last.page && done(answer.body) &&
    state.polled.some(poll => poll.page === last.page && poll.seen >= Number(answer.frame)));
}

const subscribe = /\/bridge\/long-polling\/[^/]+\/subscribe/;
const publish = /\/bridge\/long-polling\/[^/]+\/publish/;
// The body of a history event: a view event of callback type 3.
const historyEvent = /^\[7,"[^:]+:\d+:3:/;

/**
 * A page of the `resume` scenario. Every poll passes through the test, so a
 * case can fail all of them for a while, drop a DOM frame after the server
 * has answered it, or hold its answer, the first one too. A case can also
 * hold a history event on its way to the server, or answer it as it likes
 * (`onHistory`). `polling` removes WebSocket, so the client uses long
 * polling. With `ready` false the case does not wait for the page to take
 * clicks.
 */
async function open(browser, path, {polling = true, beforeLoad, scenario = 'resume', ready = true} = {}) {
  const context = await browser.newContext();
  await control(context, `start/${scenario}/plain/none`);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  // `polled` holds the page and the cursor of every poll: the last frame that
  // the page had applied. `sockets` holds the frames of every WebSocket.
  // `log` has every poll and message in the order that they left the page.
  // `holdReplies` holds the answers that carry a heartbeat reply.
  // `holdHistory` holds a history event before it reaches the server, and
  // `publishAnswers` has the status of every message that was answered.
  // `onHistory`, if set, handles the request of every history event.
  // `holdFirst` holds the first answer of the server, which has the first
  // frame of the view.
  const state = {errors: [], answers: [], published: [], polled: [], sockets: [], loads: 0, dropNextDom: false,
    dropped: false, hold: null, held: false, heldFrame: null, block: false, hang: null, finished: false,
    log: [], holdReplies: null, holdHistory: null, historyHeld: false, publishAnswers: [], onHistory: null,
    holdFirst: null, firstHeld: false};
  page.on('pageerror', error => state.errors.push(`pageerror ${clip(error.message)}`));
  page.on('load', () => { state.loads++; });
  page.on('request', request => {
    if (!publish.test(request.url())) return;
    state.published.push(request.postData());
    state.log.push({kind: 'post', body: request.postData()});
  });
  page.on('response', response => {
    if (publish.test(response.url()))
      state.publishAnswers.push({body: response.request().postData(), status: response.status()});
  });
  page.on('websocket', socket => {
    const frames = [];
    state.sockets.push(frames);
    socket.on('framereceived', event => frames.push(String(event.payload)));
  });
  await page.addInitScript(longPolling => {
    window.__ready = 0;
    window.addEventListener('SpoonbillReady', () => { window.__ready++; });
    if (longPolling) Object.defineProperty(window, 'WebSocket', {value: undefined, writable: true, configurable: true});
  }, polling);
  await page.route(subscribe, async route => {
    // A route outlives its case: what comes after the end is ignored.
    const ignore = () => {};
    if (state.finished) return route.abort().catch(ignore);
    const query = new URL(route.request().url()).searchParams;
    const pageId = query.get('page');
    state.polled.push({page: pageId, seen: Number(query.get('seen'))});
    state.log.push({kind: 'poll', seen: Number(query.get('seen'))});
    if (state.block) return route.abort('connectionreset').catch(ignore);
    // A link that hangs: the request waits, unanswered, until the case lets it go.
    if (state.hang) await state.hang;
    let response, body;
    try { response = await route.fetch(); body = await response.text(); } catch (_) { return route.abort().catch(ignore); }
    state.answers.push({page: pageId, status: response.status(), frame: response.headers()['spoonbill-frame'], body});
    if (state.holdFirst && state.answers.length === 1) { state.firstHeld = true; await state.holdFirst; }
    if (state.dropNextDom && body.startsWith('[4')) {
      state.dropNextDom = false; state.dropped = true;
      return route.abort('connectionreset').catch(ignore);
    }
    // A reply to a heartbeat waits here, unanswered, until the case lets it go.
    if (state.holdReplies && body === '[16]') await state.holdReplies;
    if (state.hold && body.startsWith('[4')) {
      const release = state.hold; state.hold = null; state.held = true;
      state.heldFrame = Number(response.headers()['spoonbill-frame']);
      await release;
    }
    return route.fulfill({response, body}).catch(ignore);
  });
  await page.route(publish, async route => {
    if (state.hang) await state.hang;
    const history = historyEvent.test(route.request().postData() || '');
    if (history && state.onHistory) return state.onHistory(route).catch(() => {});
    // A history event waits here, before the server has it, until the case lets it go.
    const holdHistory = state.holdHistory;
    if (holdHistory && history) {
      state.historyHeld = true;
      await holdHistory;
    }
    return route.continue().catch(() => {});
  });
  if (beforeLoad) await beforeLoad(page, context, state);
  await page.goto(origin + path);
  if (ready) await usable(page, state, polling, 'The page never became ready to take a click');
  return {context, page, state};
}

const stage = page => page.locator('#resume-stage').textContent();
const status = context => control(context, 'status');

/**
 * The server sends the event counter of a handled click in a frame of its
 * own, after the patch. A second click of that button before the page has
 * applied it carries the old counter, and the server drops it as a repeat, as
 * on every transport. So a case that clicks a button twice waits until the
 * page has applied a counter frame answered after `since`.
 */
async function counterApplied(state, since) {
  await waitFor(() => state.answers.slice(since).some(answer =>
    answer.body.startsWith('[0,') && state.polled.some(poll => poll.page === answer.page && poll.seen >= Number(answer.frame))),
  'The event counter of the click never reached the page');
}

/** A poll fails: the page stays ready, keeps its bridge, and gets the lost frame and the frames after it. */
async function lostFrame(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    state.dropNextDom = true;
    const since = state.answers.length;
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 1', 'The lost frame never arrived');
    assert.equal(state.dropped, true, 'The DOM frame was not dropped');
    await counterApplied(state, since);
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 2', 'The frame after it never arrived');
    assert.equal(await page.evaluate(() => window.__ready), 1, 'The bridge was built again');
    assert.equal(await page.evaluate(() => window.Spoonbill.ready), true);
    assert.equal(state.loads, 1, 'The page reloaded');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** The DOM patch is lost and the user clicks before the retry; then the same with the answer held. */
async function staleClick(browser, held) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    let release;
    if (held) state.hold = new Promise(resolve => { release = resolve; });
    else state.dropNextDom = true;
    await page.click('#resume-next');
    await waitFor(() => held ? state.held : state.dropped, 'The DOM frame was not intercepted');
    // The page still shows stage 0. Until the click is sent, no poll gets through.
    state.block = true;
    const sent = state.published.length;
    await page.click('#resume-act');
    await waitFor(() => state.published.length > sent, 'The click was not sent');
    state.block = false;
    if (held) release();
    await waitFor(async () => (await stage(page)) === 'stage 1', 'The page never caught up');
    // No handler ran for that click: the handler of stage 0 is not published
    // any more, and the one of stage 1 was not shown to the user.
    assert.deepEqual((await status(context)).clicks, [], 'A handler ran for a click made on an older display');
    // After the catch-up the click runs the handler of the new stage.
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after the catch-up ran no handler');
    assert.deepEqual((await status(context)).clicks, [1]);
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** After the catch-up an element that was not touched keeps its focus and its unsent input. */
async function keptInput(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    await page.fill('#resume-input', 'draft');
    await page.focus('#resume-input');
    state.dropNextDom = true;
    // A click by script does not move the focus.
    await page.evaluate(() => document.querySelector('#resume-next').click());
    await waitFor(async () => (await stage(page)) === 'stage 1', 'The page never caught up');
    assert.equal(state.dropped, true);
    assert.equal(await page.evaluate(() => document.activeElement && document.activeElement.id), 'resume-input');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Back is pressed while a patch is on its way: the route changes, and URL and
 * page agree after the catch-up. A history event is always handled, also when
 * it was sent from an older display, so the page does not reload.
 */
async function backDuringPatch(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    const since = state.answers.length;
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 1' && page.url().endsWith('/orders/1'), 'Stage 1 never arrived');
    await counterApplied(state, since);
    let release;
    state.hold = new Promise(resolve => { release = resolve; });
    await page.click('#resume-next');
    // The new URL comes in a frame of its own, before the held DOM patch.
    await waitFor(() => state.held && page.url().endsWith('/orders/2'), 'The patch of stage 2 was not held');
    await page.goBack();
    const backTo = new URL(page.url()).pathname;
    // The history event leaves while the patch is still held: it was sent
    // from the display of stage 1.
    await waitFor(() => state.published.some(body => /^\[7,"[0-9a-f-]+:\d+:3:/.test(body)), 'The history event was not sent');
    release();
    // Until the page has applied the held patch, it still shows stage 1.
    await waitFor(() => state.polled.some(poll => poll.seen >= state.heldFrame), 'The held patch was never applied');
    await waitFor(async () => new URL(page.url()).pathname === backTo && (await stage(page)) === `stage ${backTo.split('/').pop()}`,
      'The page never showed the route that Back went to');
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.evaluate(() => window.__ready), 1, 'The bridge was built again');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * With the heartbeat of long polling on, the link hangs for a while: every
 * poll and every message waits unanswered. The page shows the notice and does
 * not reload; when the link is back the notice hides and a click works.
 */
async function hangingLink(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-heartbeat'});
  const notice = () => page.evaluate(() => document.body.innerText.includes('Connection lost')).catch(() => false);
  try {
    let release;
    state.hang = new Promise(resolve => { release = resolve; });
    await waitFor(notice, 'The notice never showed while the link hung', 5000);
    await pause(1500);
    assert.equal(state.loads, 1, 'The page reloaded while the link hung');
    state.hang = null;
    release();
    await waitFor(async () => !(await notice()), 'The notice stayed after the link was back', 5000);
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after the hang ran no handler');
    assert.equal(state.loads, 1, 'The page reloaded');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Paused with a heartbeat taken and its re-poll spent (the review of PR 39 at
 * f55be0f2, finding 1): the replies to heartbeats are held, the limit gives a
 * re-poll, the next heartbeat is taken, and the page calls
 * Spoonbill.disconnect(). One more tick used to reload it and lose what the
 * user typed. It does not reload, and works again after Spoonbill.connect().
 */
async function pauseWithHeartbeat(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-heartbeat'});
  try {
    await page.fill('#resume-input', 'draft');
    let release;
    state.holdReplies = new Promise(resolve => { release = resolve; });
    const from = state.log.length;
    // A re-poll after the hold began: a poll with the cursor of the poll
    // before it, which may have left before the hold. Then the next heartbeat.
    const ready = () => {
      const polls = state.log.map((entry, index) => [entry, index]).filter(([entry]) => entry.kind === 'poll');
      const repoll = polls.find(([entry, index], at) => index >= from && at > 0 && entry.seen === polls[at - 1][0].seen);
      return repoll !== undefined && state.log.slice(repoll[1]).some(entry => entry.kind === 'post' && entry.body === '[6]');
    };
    await waitFor(ready, 'The heartbeat never reached its re-poll and the heartbeat after it', 8000);
    await page.evaluate(() => window.Spoonbill.disconnect());
    // Four ticks of the 500 ms heartbeat while paused.
    await pause(2000);
    assert.equal(state.loads, 1, 'The paused page reloaded');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
    state.holdReplies = null;
    release();
    const resumedFrom = state.log.length;
    await page.evaluate(() => window.Spoonbill.connect());
    // A paused page sends nothing: the click waits for the poll of the resumed transport.
    await waitFor(() => state.log.slice(resumedFrom).some(entry => entry.kind === 'poll'), 'connect() never resumed the polls');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after the pause ran no handler');
    assert.deepEqual((await status(context)).clicks, [0]);
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Back while long polling is paused (the review of PR 39 at f55be0f2, finding
 * 2): the server never hears of it, so Spoonbill.connect() reloads the page at
 * its new URL instead of resuming the view of the old route.
 */
async function backDuringPause(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    const since = state.answers.length;
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 1' && page.url().endsWith('/orders/1'), 'Stage 1 never arrived');
    await counterApplied(state, since);
    await page.fill('#resume-input', 'draft');
    await page.evaluate(() => window.Spoonbill.disconnect());
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/0'), 'Back did not change the URL');
    await page.evaluate(() => window.Spoonbill.connect());
    await waitFor(() => state.loads === 2, 'The page did not reload at its new URL');
    await usable(page, state, true, 'The reloaded page never became ready');
    assert.equal(await stage(page), 'stage 0');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click ran no handler');
    assert.deepEqual((await status(context)).clicks, [0], 'The handler of another route ran');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Spoonbill.connect() on an open WebSocket (the review of PR 39 at f55be0f2,
 * finding 3) starts nothing: a second socket got a view of its own, whose
 * listeners the bridge took as well, and a click went out twice. The page
 * keeps its one socket, and a click runs one handler.
 */
async function webSocketConnect(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {polling: false});
  try {
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The first click ran no handler');
    await page.evaluate(() => window.Spoonbill.connect());
    // A second socket, if one opened, and the listeners of its view.
    await pause(1500);
    assert.equal(state.sockets.length, 1, 'connect() opened another socket');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 2, 'The second click ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [0, 0], 'The second click ran more than one handler');
    assert.equal(await page.evaluate(() => window.__ready), 1, 'The bridge was built again');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Spoonbill.connect() while the close of a WebSocket waits for its last frame
 * to decode (the review of PR 39 at 17fd8ee0, finding 1). The socket was
 * CLOSED, so a second one opened; the close handler then found it and left
 * the old bridge, which took the listeners of the new view as well, and a
 * click went out twice. connect() waits for the close, the bridge is built
 * again, and a click goes out once.
 */
async function webSocketConnectWhileDecoding(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {polling: false, beforeLoad: async page => {
    await page.addInitScript(() => {
      // The decoding of a frame waits while `__hold` is set, until the case lets it go.
      const text = Blob.prototype.text;
      window.__hold = false; window.__held = [];
      Blob.prototype.text = function () {
        if (!window.__hold) return text.call(this);
        return new Promise(resolve => window.__held.push(() => resolve(text.call(this))));
      };
      // The frames that the page sends, and the socket of the last one.
      const send = WebSocket.prototype.send;
      window.__sent = 0;
      WebSocket.prototype.send = function (data) { window.__sent++; window.__socket = this; return send.call(this, data); };
    });
  }});
  try {
    await page.evaluate(() => { window.__hold = true; });
    // The frames that answer this click are the last ones of the socket.
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The first click ran no handler');
    await waitFor(() => page.evaluate(() => window.__held.length > 0), 'No frame waited for its decoding');
    await page.evaluate(() => window.Spoonbill.disconnect());
    await waitFor(() => page.evaluate(() => window.__socket.readyState === WebSocket.CLOSED), 'The socket did not close');
    await page.evaluate(() => window.Spoonbill.connect());
    // The reconnect timer is 200 ms: a second socket, if one opened.
    await pause(1000);
    assert.equal(state.sockets.length, 1, 'connect() opened a socket while the close waited');
    await page.evaluate(() => { window.__hold = false; for (const go of window.__held.splice(0)) go(); });
    await waitFor(() => state.sockets.length === 2, 'The page did not connect after the close');
    await usable(page, state, false, 'The new socket never became ready');
    assert.equal(await page.evaluate(() => window.__ready), 2, 'The bridge was not built again');
    const sent = await page.evaluate(() => window.__sent);
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 2, 'The second click ran no handler');
    await pause(500);
    assert.equal(await page.evaluate(() => window.__sent) - sent, 1, 'The click went out more than once');
    assert.deepEqual((await status(context)).clicks, [0, 0]);
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Back that an application handles before Spoonbill does, with a pause (the
 * review of PR 39 at 17fd8ee0, finding 2). Its popstate listener runs when the
 * URL already shows the destination, so the URL of the pause fits; but
 * Spoonbill's own listener could not tell the server. connect() reloads the
 * page at the URL that it shows, and the click runs the handler of that route.
 */
async function backListenerPause(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {beforeLoad: async page => {
    // Registered before the client starts, so it runs before Spoonbill's listener.
    await page.addInitScript(() => {
      window.addEventListener('popstate', () => { if (window.__pauseOnBack) window.Spoonbill.disconnect(); });
    });
  }});
  try {
    const since = state.answers.length;
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 1' && page.url().endsWith('/orders/1'), 'Stage 1 never arrived');
    await counterApplied(state, since);
    await page.fill('#resume-input', 'draft');
    await page.evaluate(() => { window.__pauseOnBack = true; });
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/0'), 'Back did not change the URL');
    await page.evaluate(() => window.Spoonbill.connect());
    await waitFor(() => state.loads === 2, 'The page did not reload at the URL that it shows');
    await usable(page, state, true, 'The reloaded page never became ready');
    assert.equal(await stage(page), 'stage 0');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click ran no handler');
    assert.deepEqual((await status(context)).clicks, [0], 'The handler of another route ran');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * A command whose code pauses long polling (the review of PR 39 at 17fd8ee0,
 * finding 3): its result was refused, and the server waited for its RPC
 * timeout of 5 s. The page keeps the result while it is paused and sends it
 * when it resumes: the server gets it once, well before the timeout.
 */
async function evalDuringPause(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    await page.fill('#resume-input', 'draft');
    await page.click('#resume-eval');
    // The command arrived, ran, and paused the page.
    await waitFor(() => state.answers.some(answer => answer.body.startsWith('[10,')), 'The command never arrived');
    await pause(500);
    const polls = state.polled.length;
    assert.deepEqual((await status(context)).evals, [], 'The result went out during the pause');
    await page.evaluate(() => window.Spoonbill.connect());
    await waitFor(async () => (await status(context)).evals.length > 0, 'The server did not get the result', 4000);
    await pause(500);
    assert.deepEqual((await status(context)).evals, ['42'], 'The server did not get the result exactly once');
    assert.equal(state.published.filter(body => body.startsWith('[4,')).length, 1, 'The result was posted more than once');
    assert.ok(state.polled.length > polls, 'The page did not resume');
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Stage 1 at `/orders/1`, with its event counter applied and a draft in the
 * field, so that Back goes to `/orders/0`.
 */
async function atStageOne(page, state, polling = true) {
  const since = state.answers.length;
  const frames = polling ? 0 : state.sockets.at(-1).length;
  await page.click('#resume-next');
  await waitFor(async () => (await stage(page)) === 'stage 1' && page.url().endsWith('/orders/1'), 'Stage 1 never arrived');
  if (polling) await counterApplied(state, since);
  else await waitFor(() => state.sockets.at(-1).slice(frames).some(frame => frame.startsWith('[0,')),
    'The event counter of the click never reached the page');
  await page.fill('#resume-input', 'draft');
}

/** Stage 2 at `/orders/2`, after the steps of atStageOne: Back goes to `/orders/1`. */
async function atStageTwo(page, state) {
  await atStageOne(page, state);
  const since = state.answers.length;
  await page.click('#resume-next');
  await waitFor(async () => (await stage(page)) === 'stage 2' && page.url().endsWith('/orders/2'), 'Stage 2 never arrived');
  await counterApplied(state, since);
}

/**
 * Once the page shows the route of Back and has applied the completion of
 * that navigation, no handler has run for a click on the old display, also
 * not late, and a click now runs the handler of that route once.
 */
async function clickAtRouteOfBack(page, context, state, polling = true) {
  await waitFor(async () => (await stage(page)) === 'stage 0', 'The page never showed the route of Back');
  // The completion reopens the page's input: the fresh click waits for it.
  await waitFor(() => completed(state, polling), 'The page never applied the completion of the navigation');
  await usable(page, state, polling, 'The page never became ready to take a click');
  assert.deepEqual((await status(context)).clicks, [], 'The click on the old display ran a handler late');
  await page.click('#resume-act');
  await waitFor(async () => (await status(context)).clicks.length === 1, 'The click at the route of Back ran no handler');
  await pause(500);
  assert.deepEqual((await status(context)).clicks, [0], 'The click ran the handler of another route, or ran twice');
}

/**
 * A click right after Back, while its history event is on the way (found with
 * the review of PR 39 at 3abb1412). On long polling every message is a POST
 * of its own, so the click reached the server first, and the handler of the
 * route that the user had left ran. With `paused`, the application's own
 * popstate listener, which runs after Spoonbill's, calls
 * Spoonbill.disconnect() while the history event is on the way, and the page
 * then calls Spoonbill.connect(): the event is healthy, so the page resumes
 * the same document, and the user clicks the old display after it.
 *
 * The click on the old display runs no handler, the page reaches the route of
 * Back without a reload and keeps what the user typed, and a click there runs
 * its handler once.
 */
async function clickBeforeHistory(browser, paused) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-long'});
  let release = () => {};
  try {
    await atStageOne(page, state);
    // Added after the bridge's own listener, so it runs once that has sent the history event.
    if (paused) await page.evaluate(() => window.addEventListener('popstate', () => window.Spoonbill.disconnect()));
    state.holdHistory = new Promise(resolve => { release = resolve; });
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/0') && state.historyHeld, 'The history event was not held');
    if (paused) {
      // The polls of the same page go on: connect() resumes it.
      const pageId = state.polled.at(-1).page;
      const from = state.polled.length;
      await page.evaluate(() => window.Spoonbill.connect());
      await waitFor(() => state.polled.slice(from).some(poll => poll.page === pageId) || state.loads > 1,
        'connect() did not resume the page');
      assert.equal(state.loads, 1, 'connect() reloaded the page while its history event was on the way');
    }
    await pause(300);
    assert.equal(await stage(page), 'stage 1', 'The page did not show the old display');
    await page.click('#resume-act');
    // The click has had its time to reach the server; the history event has not.
    await pause(1000);
    assert.deepEqual((await status(context)).clicks, [], 'A click made after Back ran a handler before the history event arrived');
    state.holdHistory = null;
    release();
    await clickAtRouteOfBack(page, context, state);
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.evaluate(() => window.__ready), 1, 'The bridge was built again');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { release(); state.finished = true; await context.close(); }
  return state;
}

/**
 * The server has taken the history event of Back (on long polling its POST
 * is answered), and the fixture holds the route change before its render, so
 * the handlers of the old route are still the published ones. A click on the
 * old display runs no handler here either, on long polling and on a
 * WebSocket, where the messages arrive in order. So a page may not send its
 * user events once the server has taken the history event before them: it
 * waits until the new route is published.
 */
async function clickBeforeRoute(browser, polling = true) {
  const {context, page, state} = await open(browser, '/orders/0', {polling, scenario: 'resume-long'});
  try {
    await atStageOne(page, state, polling);
    await control(context, 'hold-route');
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/0'), 'Back did not change the URL');
    const taken = () => !polling ||
      state.publishAnswers.some(answer => historyEvent.test(answer.body || '') && answer.status === 200);
    await waitFor(async () => taken() && (await status(context)).routeWaits === 1,
      'The server did not take the history event, or its route did not wait');
    assert.equal(await stage(page), 'stage 1', 'The page did not show the old display');
    await page.click('#resume-act');
    // The click has had its time to reach the server; the route has not changed.
    await pause(1000);
    assert.deepEqual((await status(context)).clicks, [], 'A click made after Back ran a handler before the route changed');
    await control(context, 'release-route');
    await clickAtRouteOfBack(page, context, state, polling);
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally {
    state.finished = true;
    await control(context, 'release-route').catch(() => {});
    await context.close();
  }
  return state;
}

/**
 * Keeps every WebSocket of the page in `window.__sockets`, so that a case can
 * close one. `window.__closeDelay` makes the next close wait that many
 * milliseconds, as a slow close handshake does.
 */
const keepSockets = page => page.addInitScript(() => {
  const Native = window.WebSocket;
  window.__sockets = [];
  window.__closeDelay = 0;
  window.WebSocket = class extends Native {
    constructor(...args) { super(...args); window.__sockets.push(this); }
    close(...args) {
      const delay = window.__closeDelay;
      window.__closeDelay = 0;
      if (delay) setTimeout(() => super.close(...args), delay);
      else super.close(...args);
    }
  };
});

/**
 * The socket closes while the server holds the route of Back (the re-review
 * of 2026-11-24). The page connected again with a fresh bridge, which knew
 * nothing of the navigation: a click on the old display ran the handler of
 * the route that the user had left. Now the page reloads at the URL of Back
 * instead, and the click on the old display reaches no server; after the
 * reload a click runs the handler of Back's route once.
 */
async function reconnectBeforeRoute(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {polling: false, scenario: 'resume-long',
    beforeLoad: keepSockets});
  // The document requests of the tab: a reload is one.
  const documents = [];
  page.on('request', request => {
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documents.push(request.url());
  });
  try {
    await atStageOne(page, state, false);
    await control(context, 'hold-route');
    await page.goBack();
    await waitFor(async () => page.url().endsWith('/orders/0') && (await status(context)).routeWaits === 1,
      'Back did not change the URL, or its route did not wait');
    const act = await page.locator('#resume-act').boundingBox();
    await page.evaluate(() => window.__sockets.at(-1).close());
    // Either the page reloads, or it connects again and its new view listens
    // for clicks. Nothing here evaluates in the page: while the reload waits
    // for the held route, an evaluation would wait for it too.
    await waitFor(() => documents.length > 0 || (state.sockets.length > 1 && listening(state, false)),
      'The page neither reloaded nor connected again');
    // The reload waits for the held route, so the old display is still there.
    // A click of the locator would wait for the reload; the mouse does not.
    await page.mouse.click(act.x + act.width / 2, act.y + act.height / 2);
    await pause(1000);
    assert.deepEqual((await status(context)).clicks, [], 'A click on the old display ran a handler after the socket closed');
    assert.deepEqual(documents.map(url => new URL(url).pathname), ['/orders/0'], 'The page did not reload at the URL of Back');
    await control(context, 'release-route');
    await waitFor(() => state.loads === 2, 'The reload never finished', 15000);
    await usable(page, state, false, 'The reloaded page never became ready to take a click');
    assert.equal(await stage(page), 'stage 0');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after the reload ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [0], 'The click ran the handler of another route, or ran twice');
  } finally {
    state.finished = true;
    await control(context, 'release-route').catch(() => {});
    await context.close();
  }
  return state;
}

/**
 * The page opens at `/orders/0001`, an alias of stage 1 whose URL on the
 * server is `/orders/1` (the re-review of 2026-11-24, D5). After Next and
 * Back, the page skipped the server's URL of Back's route, and the address
 * bar kept the alias. Now the URL that the server sends for the navigation
 * takes the entry that Back chose, without adding one; Forward and Back still
 * reach their stages, and a click runs the handler of the route that shows.
 */
async function canonicalUrl(browser, polling) {
  const {context, page, state} = await open(browser, '/orders/0001', {polling, scenario: 'resume-long'});
  try {
    assert.ok(page.url().endsWith('/orders/0001'), `The page did not open at the alias: ${page.url()}`);
    assert.equal(await stage(page), 'stage 1');
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 2' && page.url().endsWith('/orders/2'), 'Stage 2 never arrived');
    const entries = await page.evaluate(() => history.length);
    await page.goBack();
    await waitFor(() => completed(state, polling, 1), 'The page never applied the completion of Back');
    await waitFor(() => page.url().endsWith('/orders/1'), `The address bar kept the alias: ${page.url()}`);
    assert.equal(await stage(page), 'stage 1');
    assert.equal(await page.evaluate(() => history.length), entries, 'The URL of the server added an entry');
    await usable(page, state, polling, 'The page never became ready to take a click');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click at the route of Back ran no handler');
    // The entry that the URL took is still Back's: Forward and Back reach their stages.
    await page.goForward();
    await waitFor(async () => page.url().endsWith('/orders/2') && (await stage(page)) === 'stage 2', 'Forward did not reach stage 2');
    await waitFor(() => completed(state, polling, 2), 'The page never applied the completion of Forward');
    await page.goBack();
    await waitFor(async () => page.url().endsWith('/orders/1') && (await stage(page)) === 'stage 1',
      `The second Back did not reach stage 1: ${page.url()}`);
    await waitFor(() => completed(state, polling, 3), 'The page never applied the completion of the second Back');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [1], 'The click ran the handler of another route, or ran twice');
    assert.equal(state.loads, 1, 'The page reloaded');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * The page opens at `/orders?stage=1`, whose query names the stage; bare
 * `/orders` is stage 0 (the third review of 2026-11-24). Back to that first
 * entry, which has no history state, sent the path that the page had at its
 * start, without the query: the server routed stage 0, and its URL took the
 * entry. Now the page sends the path and the query of the entry: Back reaches
 * stage 1, the server's URL of stage 1 takes the entry, and a click runs the
 * handler of stage 1.
 */
async function queryDeepLink(browser, polling) {
  const {context, page, state} = await open(browser, '/orders?stage=1', {polling, scenario: 'resume-long'});
  try {
    assert.ok(page.url().endsWith('/orders?stage=1'), `The page did not open at its address: ${page.url()}`);
    assert.equal(await stage(page), 'stage 1');
    await page.click('#resume-next');
    await waitFor(async () => (await stage(page)) === 'stage 2' && page.url().endsWith('/orders/2'), 'Stage 2 never arrived');
    const entries = await page.evaluate(() => history.length);
    await page.goBack();
    await waitFor(() => completed(state, polling, 1), 'The page never applied the completion of Back');
    await waitFor(async () => (await stage(page)) !== 'stage 2', 'Back did not leave stage 2');
    assert.equal(await stage(page), 'stage 1', 'Back did not reach stage 1');
    assert.ok(page.url().endsWith('/orders/1'), `The entry of Back has another URL: ${page.url()}`);
    assert.equal(await page.evaluate(() => history.length), entries, 'Back added an entry');
    await usable(page, state, polling, 'The page never became ready to take a click');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click at the route of Back ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [1], 'The click ran the handler of another route, or ran twice');
    assert.equal(state.loads, 1, 'The page reloaded');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * `Spoonbill.disconnect()` while the server holds the route of Back, on a
 * WebSocket whose close takes longer than the deadline of the navigation,
 * 4.5 s here (the third review of 2026-11-24). The deadline reloaded the page
 * although its user had disconnected it. Now the page waits, and
 * `Spoonbill.connect()` reloads it at the URL of Back; after the reload a click
 * runs the handler of that route once.
 */
async function disconnectBeforeRoute(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {polling: false, beforeLoad: keepSockets});
  // The document requests of the tab: a reload is one.
  const documents = [];
  page.on('request', request => {
    if (request.isNavigationRequest() && request.frame() === page.mainFrame()) documents.push(request.url());
  });
  try {
    await atStageOne(page, state, false);
    await control(context, 'hold-route');
    await page.goBack();
    await waitFor(async () => page.url().endsWith('/orders/0') && (await status(context)).routeWaits === 1,
      'Back did not change the URL, or its route did not wait');
    await page.evaluate(() => { window.__closeDelay = 8000; window.Spoonbill.disconnect(); });
    // The deadline of the navigation passes while the socket closes.
    await pause(6000);
    assert.deepEqual(documents, [], 'The page reloaded before connect()');
    await page.evaluate(() => window.Spoonbill.connect());
    // The reload waits for the held route.
    await waitFor(() => documents.length > 0, 'connect() did not reload the page');
    assert.deepEqual(documents.map(url => new URL(url).pathname), ['/orders/0'], 'The page did not reload at the URL of Back');
    await control(context, 'release-route');
    await waitFor(() => state.loads === 2, 'The reload never finished', 15000);
    await usable(page, state, false, 'The reloaded page never became ready to take a click');
    assert.equal(await stage(page), 'stage 0');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after the reload ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [0], 'The click ran the handler of another route, or ran twice');
  } finally {
    state.finished = true;
    await control(context, 'release-route').catch(() => {});
    await context.close();
  }
  return state;
}

/**
 * Back on a route whose URL on the server is not ASCII, `/café/<stage>` (the
 * third review of 2026-11-24). A client that compares that URL with the
 * address bar, which the browser encodes, adds an entry for every URL frame,
 * as the client of PR 39 at `3abb1412` does. The render of the route of Back
 * came with two URL frames, so such a client added an entry for Back. Now it
 * comes with one: the number of entries stays, also with that client, and a
 * click runs the handler of the route of Back.
 */
async function unicodeRoute(browser) {
  const {context, page, state} = await open(browser, '/caf%C3%A9/0', {scenario: 'resume-unicode'});
  try {
    assert.equal(await stage(page), 'stage 0');
    for (const next of [1, 2]) {
      const since = state.answers.length;
      await page.click('#resume-next');
      await waitFor(async () => (await stage(page)) === `stage ${next}`, `Stage ${next} never arrived`);
      await counterApplied(state, since);
    }
    const entries = await page.evaluate(() => history.length);
    await page.goBack();
    await waitFor(() => completed(state, true, 1), 'The page never applied the completion of Back');
    await waitFor(async () => (await stage(page)) === 'stage 1', 'Back did not reach stage 1');
    assert.equal(decodeURIComponent(new URL(page.url()).pathname), '/café/1');
    assert.equal(await page.evaluate(() => history.length), entries, 'Back added an entry');
    await usable(page, state, true, 'The page never became ready to take a click');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click at the route of Back ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [1], 'The click ran the handler of another route, or ran twice');
    assert.equal(state.loads, 1, 'The page reloaded');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** Wait for the real stage-1 rerender and its unnumbered server URL to reach the page. */
async function rerenderStageOne(page, state, path) {
  const since = state.answers.length;
  // The shared Advance button transitions to 1 even when the view already shows 1.
  await page.click('#advance');
  await waitFor(() => state.answers.slice(since).some(answer => {
    if (!answer.body.startsWith('[6,')) return false;
    const frame = JSON.parse(answer.body);
    return frame.length === 2 && frame[1] === path &&
      state.polled.some(poll => poll.page === answer.page && poll.seen >= Number(answer.frame));
  }), 'The page did not apply the ordinary render URL');
  await counterApplied(state, since);
}

/** Two ordinary renders of stage 1 must leave the Forward entry reachable. */
async function unchangedRouteHistory(browser, unicode) {
  const scenario = unicode ? 'resume-unicode' : 'resume-long';
  const prefix = unicode ? '/café/' : '/orders/';
  const {context, page, state} = await open(browser, encodeURI(prefix + '0'), {scenario});
  try {
    for (const next of [1, 2]) {
      const since = state.answers.length;
      await page.click('#resume-next');
      await waitFor(async () => await stage(page) === 'stage ' + next, 'Next did not reach stage ' + next);
      await counterApplied(state, since);
    }
    await page.goBack();
    await waitFor(async () => completed(state, true, 1) && await stage(page) === 'stage 1', 'Back did not settle on stage 1');
    await rerenderStageOne(page, state, prefix + '1');
    await rerenderStageOne(page, state, prefix + '1');
    await page.goForward();
    assert.equal(new URL(page.url()).pathname, encodeURI(prefix + '2'), 'Forward after two same-URL renders');
    await waitFor(async () => completed(state, true, 2) && await stage(page) === 'stage 2', 'Forward did not settle on stage 2');
    for (const [counter, expected] of [[3, 1], [4, 0]]) {
      await page.goBack();
      await waitFor(async () => completed(state, true, counter) && await stage(page) === 'stage ' + expected,
        'Back did not settle on stage ' + expected);
      assert.equal(new URL(page.url()).pathname, encodeURI(prefix + expected));
    }
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** An ordinary render with the same path and query should preserve the current fragment. */
async function unicodeRenderFragment(browser) {
  const {context, page, state} = await open(browser, '/caf%C3%A9?stage=1#details', {scenario: 'resume-unicode-query'});
  try {
    assert.equal(new URL(page.url()).hash, '#details');
    await rerenderStageOne(page, state, '/café?stage=1');
    assert.equal(new URL(page.url()).hash, '#details', 'An ordinary same-URL render should preserve the fragment');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** A real query change adds an entry. Back restores the previous entry including its fragment. */
async function unicodeQueryHistory(browser) {
  const {context, page, state} = await open(browser, '/caf%C3%A9?stage=1#details', {scenario: 'resume-unicode-query'});
  try {
    const original = page.url();
    assert.equal(new URL(original).hash, '#details');
    assert.equal(await stage(page), 'stage 1');
    const entries = await page.evaluate(() => history.length);
    const since = state.answers.length;
    await page.click('#resume-next');
    await waitFor(async () => new URL(page.url()).search === '?stage=2' && await stage(page) === 'stage 2',
      'The query change did not reach stage 2');
    await counterApplied(state, since);
    assert.equal(new URL(page.url()).pathname, new URL(original).pathname);
    assert.equal(await page.evaluate(() => history.length), entries + 1, 'A real query change should add one entry');
    await page.goBack();
    await waitFor(async () => completed(state, true, 1) && await stage(page) === 'stage 1', 'Back did not restore stage 1');
    assert.equal(page.url(), original, 'Back should preserve the query and fragment on the selected entry');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Back, then Forward before the history event of Back reached the server. The
 * page posts Forward's history event only after the answer to Back's, so the
 * server takes them in the order of the page. A click while the page waits
 * runs no handler, and only the completion of Forward reopens the input. A
 * later Back reaches the entry before Forward's, and its route runs.
 */
async function backThenForward(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-long'});
  let release = () => {};
  try {
    await atStageTwo(page, state);
    const entries = await page.evaluate(() => history.length);
    state.holdHistory = new Promise(resolve => { release = resolve; });
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/1') && state.historyHeld, 'The history event of Back was not held');
    await page.goForward();
    await waitFor(() => page.url().endsWith('/orders/2'), 'Forward did not change the URL');
    assert.equal(state.published.filter(body => historyEvent.test(body)).length, 1, 'Forward was posted before Back was answered');
    await page.click('#resume-act');
    await pause(1000);
    assert.deepEqual((await status(context)).clicks, [], 'A click while the page waited ran a handler');
    state.holdHistory = null;
    release();
    await waitFor(() => completed(state, true, 2), 'The page never applied the completion of Forward');
    assert.equal(await stage(page), 'stage 2');
    assert.deepEqual((await status(context)).clicks, [], 'The click on the old display ran a handler late');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after Forward ran no handler');
    // Later Backs reach the entries before Forward's, in order: the history
    // of the tab has no entries that the server's URLs added.
    for (const [number, at] of [[3, 1], [4, 0]]) {
      await page.goBack();
      await waitFor(async () => page.url().endsWith(`/orders/${at}`) && (await stage(page)) === `stage ${at}`,
        `A later Back did not reach stage ${at}: ${page.url()}`);
      await waitFor(() => completed(state, true, number), 'The page never applied the completion of a later Back');
    }
    assert.equal(await page.evaluate(() => history.length), entries, 'The history of the tab has entries that the server added');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 2, 'The click after the later Backs ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [2, 0]);
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { release(); state.finished = true; await context.close(); }
  return state;
}

/**
 * Back to an entry whose route leaves the screen as it is, while its history
 * event is held. The user clicks the screen, which looks the same before and
 * after: the click runs neither route's handler. Once the navigation is
 * confirmed, a click runs the handler of the route once.
 */
async function sameScreen(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-long'});
  let release = () => {};
  try {
    await atStageOne(page, state);
    // A second entry of the same route: Back from it routes to stage 1 again.
    await page.evaluate(() => history.pushState('/orders/1', '', '/orders/1#same'));
    state.holdHistory = new Promise(resolve => { release = resolve; });
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/1') && state.historyHeld, 'The history event was not held');
    await page.click('#resume-act');
    await pause(1000);
    assert.deepEqual((await status(context)).clicks, [], 'A click while the page waited ran a handler');
    state.holdHistory = null;
    release();
    await waitFor(() => completed(state, true, 1), 'The page never applied the completion of the navigation');
    assert.equal(await stage(page), 'stage 1');
    assert.deepEqual((await status(context)).clicks, [], 'The click while the page waited ran a handler late');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after the navigation ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [1]);
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { release(); state.finished = true; await context.close(); }
  return state;
}

/**
 * Back, and its history event never gets an answer: the page gives the POST
 * up at the deadline of a poll, and reloads at the URL that it shows. A click
 * while it waited runs no handler, and the reloaded page works.
 */
async function hungHistory(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    await atStageOne(page, state);
    // Held for good: the history event never reaches the server.
    state.holdHistory = new Promise(() => {});
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/0') && state.historyHeld, 'The history event was not held');
    await page.click('#resume-act');
    await waitFor(() => state.loads === 2, 'The page did not reload after its history event hung', 15000);
    state.holdHistory = null;
    await usable(page, state, true, 'The reloaded page never became ready');
    assert.equal(await stage(page), 'stage 0');
    assert.deepEqual((await status(context)).clicks, [], 'A click while the history event hung ran a handler');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The reloaded page ran no handler');
    assert.deepEqual((await status(context)).clicks, [0]);
  } finally { state.finished = true; await context.close(); }
  return state;
}

/**
 * Back: the server takes its history event, but the route is never published
 * (the fixture holds it). The page waits for the completion until the
 * deadline of a poll, and reloads at the URL that it shows. A click while it
 * waited runs no handler.
 */
async function unconfirmedRoute(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  let reloading = false;
  try {
    await atStageOne(page, state);
    page.on('request', request => { if (request.isNavigationRequest()) reloading = true; });
    await control(context, 'hold-route');
    await page.goBack();
    await waitFor(async () => (await status(context)).routeWaits === 1, 'The route did not wait');
    await page.click('#resume-act');
    // The reload renders the page at its URL, which needs the route: it goes on then.
    await waitFor(() => reloading, 'The page did not reload when its navigation was not confirmed', 15000);
    await control(context, 'release-route');
    await waitFor(() => state.loads === 2, 'The reload did not finish');
    await usable(page, state, true, 'The reloaded page never became ready');
    assert.equal(await stage(page), 'stage 0');
    assert.deepEqual((await status(context)).clicks, [], 'A click while the navigation waited ran a handler');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The reloaded page ran no handler');
    assert.deepEqual((await status(context)).clicks, [0]);
  } finally {
    state.finished = true;
    await control(context, 'release-route').catch(() => {});
    await context.close();
  }
  return state;
}

/**
 * Back, then Forward while Back's history event waits. The server takes
 * Back's event and confirms it, and then the answer to its POST is lost (the
 * code review of 2026-11-24, finding 1). Back was delivered, so Forward's
 * event goes out, and the page reaches Forward's route without a reload.
 */
async function lostAnswer(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-long'});
  let release = () => {};
  try {
    await atStageTwo(page, state);
    const gate = new Promise(resolve => { release = resolve; });
    let backTaken = false;
    state.onHistory = async route => {
      if (backTaken) return route.continue();
      backTaken = true;
      await gate;
      // The server takes Back's event, routes it and confirms it; then its answer is lost.
      await route.fetch();
      await waitFor(() => completed(state, true, 1), 'Back was never confirmed');
      return route.abort();
    };
    await page.goBack();
    await waitFor(() => page.url().endsWith('/orders/1') && backTaken, 'Back was not posted');
    await page.goForward();
    await waitFor(() => page.url().endsWith('/orders/2'), 'Forward did not change the URL');
    release();
    await waitFor(() => completed(state, true, 2), 'Forward was never confirmed', 15000);
    assert.equal(await stage(page), 'stage 2');
    assert.equal(state.loads, 1, 'The page reloaded');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The click after Forward ran no handler');
    await pause(500);
    assert.deepEqual((await status(context)).clicks, [2]);
    assert.equal(await page.inputValue('#resume-input'), 'draft');
  } finally { release(); state.finished = true; await context.close(); }
  return state;
}

/**
 * Back, then Forward. The route of Back is published while Forward's history
 * event waits, and then Forward's POST fails (the code review of 2026-11-24,
 * finding 2). The URL of Back's route must not take Forward's entry: the page
 * reloads at Forward's URL, and Back and Forward reach their entries after.
 */
async function forwardFails(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-long'});
  let release = () => {};
  try {
    await atStageTwo(page, state);
    await control(context, 'hold-route');
    const gate = new Promise(resolve => { release = resolve; });
    let posted = 0;
    state.onHistory = async route => {
      // Back's event reaches the server; Forward's waits here, and fails.
      if (++posted === 1) return route.continue();
      await gate;
      return route.abort();
    };
    await page.goBack();
    await waitFor(async () => page.url().endsWith('/orders/1') && (await status(context)).routeWaits === 1,
      'The route of Back did not wait');
    await page.goForward();
    await waitFor(() => page.url().endsWith('/orders/2') && posted === 2, 'Forward was not posted');
    await control(context, 'release-route');
    await waitFor(() => completed(state, true, 1), 'Back was never confirmed');
    assert.equal(new URL(page.url()).pathname, '/orders/2', "The URL of Back's route took Forward's entry");
    const reloads = [];
    page.on('request', request => { if (request.isNavigationRequest()) reloads.push(new URL(request.url()).pathname); });
    release();
    await waitFor(() => reloads.length > 0, 'The page did not reload after Forward failed', 15000);
    assert.equal(reloads[0], '/orders/2', "The page reloaded at another URL than Forward's");
    // Playwright's WebKit does not finish a reload after Back and Forward in
    // the same document, also on a static page without Spoonbill: the rest is
    // checked in Chromium.
    if (browser.browserType().name() === 'webkit') return state;
    await waitFor(() => state.loads === 2, 'The reload after Forward failed did not finish', 15000);
    state.onHistory = null;
    await usable(page, state, true, 'The reloaded page never became ready');
    assert.equal(new URL(page.url()).pathname, '/orders/2');
    assert.equal(await stage(page), 'stage 2');
    for (const [move, at] of [[() => page.goBack(), 1], [() => page.goForward(), 2]]) {
      await move();
      await waitFor(async () => page.url().endsWith(`/orders/${at}`) && (await stage(page)) === `stage ${at}`,
        `Back or Forward did not reach stage ${at}: ${page.url()}`);
    }
  } finally {
    release(); state.finished = true;
    await control(context, 'release-route').catch(() => {});
    await context.close();
  }
  return state;
}

/**
 * Back before the page has read the first frame of its view, which says that
 * the view confirms navigations (the code review of 2026-11-24, finding 3).
 * The page is at `/orders/1`, with an entry of its own document for
 * `/orders/0` before it; the first answer of the server is held, and the
 * fixture holds the route of Back. A click on the old display runs no handler.
 */
async function backBeforeCapability(browser) {
  let releaseFirst = () => {};
  const {context, page, state} = await open(browser, '/orders/1', {scenario: 'resume-long', ready: false,
    beforeLoad: async (page, context, state) => {
      state.holdFirst = new Promise(resolve => { releaseFirst = resolve; });
      await page.addInitScript(() => {
        history.replaceState('/orders/0', '', '/orders/0');
        history.pushState('/orders/1', '', '/orders/1');
      });
    }});
  try {
    await waitFor(() => state.firstHeld, 'The first answer was not held');
    await control(context, 'hold-route');
    await page.goBack();
    await waitFor(async () => page.url().endsWith('/orders/0') && (await status(context)).routeWaits === 1,
      'The route of Back did not wait');
    releaseFirst();
    await usable(page, state, true, 'The page never became ready to take a click');
    assert.equal(await stage(page), 'stage 1', 'The page did not show the old display');
    await page.click('#resume-act');
    // The click has had its time to reach the server; the route has not changed.
    await pause(1000);
    assert.deepEqual((await status(context)).clicks, [], 'A click before the first frame of the view ran a handler');
    await control(context, 'release-route');
    await clickAtRouteOfBack(page, context, state);
    assert.equal(state.loads, 1, 'The page reloaded');
  } finally {
    releaseFirst(); state.finished = true;
    await control(context, 'release-route').catch(() => {});
    await context.close();
  }
  return state;
}

/**
 * Back, and the server refuses its history event with `410` (the code review
 * of 2026-11-24, finding 4): no view can take it. The page reloads at once at
 * the URL that it shows, rather than waiting for a confirmation that cannot
 * come, and a click there runs its handler.
 */
async function refusedHistory(browser) {
  const {context, page, state} = await open(browser, '/orders/0', {scenario: 'resume-long'});
  try {
    await atStageOne(page, state);
    state.onHistory = route => route.fulfill({status: 410, body: ''});
    await page.goBack();
    // Within the 20 s that the page would wait for a confirmation.
    await waitFor(() => state.loads === 2, 'The page did not reload after its history event was refused', 10000);
    state.onHistory = null;
    await usable(page, state, true, 'The reloaded page never became ready');
    assert.equal(new URL(page.url()).pathname, '/orders/0');
    assert.equal(await stage(page), 'stage 0');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The reloaded page ran no handler');
    assert.deepEqual((await status(context)).clicks, [0]);
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** The view ends while the polls fail: the page reloads once and works. */
async function viewEnds(browser) {
  const {context, page, state} = await open(browser, '/orders/0');
  try {
    // The idle timeout of the fixture is 6 s. Every poll fails for longer.
    state.block = true;
    await pause(7500);
    state.block = false;
    await waitFor(() => state.loads === 2, 'The page did not reload once its view had ended', 15000);
    await usable(page, state, true, 'The reloaded page never became ready to take a click');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The reloaded page ran no handler');
    assert.equal(state.loads, 2, 'The page reloaded more than once');
  } finally { state.finished = true; await context.close(); }
  return state;
}

/** A page at a route loses its stored state before it connects: after one reload it shows the route again. */
async function lostState(browser, polling) {
  const {context, page, state} = await open(browser, '/orders/42', {polling, beforeLoad: async (page, context) => {
    // The first load of the document: its state is forgotten before the page
    // connects, so its client script waits for that. The document itself is
    // not fulfilled by the test: WebKit would drop its cookie and its
    // sessionStorage.
    let arrived;
    const forgotten = new Promise(resolve => { arrived = resolve; });
    let first = true;
    page.on('response', response => {
      if (!first || !/\/orders\/42$/.test(response.url())) return;
      first = false;
      arrived(response.text().then(body => control(context, `forget/${/sid:'([^']+)'/.exec(body)[1]}`)));
    });
    let held = false;
    await page.route(/\/static\/spoonbill-client/, async route => {
      if (!held) { held = true; await forgotten; }
      return route.continue().catch(() => {});
    });
  }});
  try {
    await waitFor(() => state.loads === 2, 'The page was not told to reload', 15000);
    await usable(page, state, polling, 'The reloaded page never became ready to take a click');
    assert.equal(await stage(page), 'stage 42');
    await page.click('#resume-act');
    await waitFor(async () => (await status(context)).clicks.length === 1, 'The reloaded page ran no handler');
    assert.deepEqual((await status(context)).clicks, [42]);
    assert.equal(state.loads, 2, 'The page reloaded more than once');
    // On long polling the server told the page to reload: its first answer.
    if (polling) assert.equal(state.answers[0].body, '[1]', 'The first poll was not told to reload');
  } finally { state.finished = true; await context.close(); }
  return state;
}

const all = [
  ['lost-frame', 'a lost frame arrives again, and the bridge stays', lostFrame],
  ['stale-dropped', 'a click on a display whose patch was lost runs no handler', browser => staleClick(browser, false)],
  ['stale-held', 'a click on a display whose patch is held runs no handler', browser => staleClick(browser, true)],
  ['kept-input', 'a field that was not touched keeps its focus and its input', keptInput],
  ['back', 'back during a patch: URL and page agree, and the page does not reload', backDuringPatch],
  ['view-ends', 'a page whose view ended reloads once and works', viewEnds],
  ['hanging-link', 'with the heartbeat on, a link that hangs shows the notice, and the page goes on', hangingLink],
  ['lost-state-lp', 'a page at a route whose state is gone reloads once to it, on long polling', browser => lostState(browser, true)],
  ['lost-state-ws', 'a page at a route whose state is gone reloads once to it, on a WebSocket', browser => lostState(browser, false)],
  ['pause-heartbeat', 'a page paused with a heartbeat taken and its re-poll spent does not reload, and resumes', pauseWithHeartbeat],
  ['back-pause', 'Back during a pause: connect() reloads at the new URL, and the click runs its handler', backDuringPause],
  ['ws-connect', 'connect() on an open WebSocket starts nothing, and a click goes out once', webSocketConnect],
  ['ws-connect-decoding', 'connect() while the close waits for the last frame: one socket after the close, and a click goes out once', webSocketConnectWhileDecoding],
  ['back-listener-pause', 'Back that the application handles first with a pause: connect() reloads, and the click runs its handler', backListenerPause],
  ['eval-pause', 'a command that pauses the page: its result reaches the server once after connect()', evalDuringPause],
  ['history-click', 'a click after Back while its history event is on the way runs no handler', browser => clickBeforeHistory(browser, false)],
  ['history-click-pause', 'the same with a pause and connect() while the history event is on the way: the page resumes and keeps its draft', browser => clickBeforeHistory(browser, true)],
  ['history-route-held', 'a click after Back runs no handler while the server holds the new route', browser => clickBeforeRoute(browser, true)],
  ['history-route-held-ws', 'the same on a WebSocket', browser => clickBeforeRoute(browser, false)],
  ['history-reconnect-ws', 'the socket closes while the server holds the route of Back: the page reloads at its URL, and no click runs an old handler', reconnectBeforeRoute],
  ['history-canonical-url', "Back to an alias: the server's URL of the route takes Back's entry, and Forward and Back still work", browser => canonicalUrl(browser, true)],
  ['history-canonical-url-ws', 'the same on a WebSocket', browser => canonicalUrl(browser, false)],
  ['history-query', 'Back to the first entry, opened at `/orders?stage=1`: its query is sent, the page reaches stage 1, and the URL of stage 1 takes the entry', browser => queryDeepLink(browser, true)],
  ['history-query-ws', 'the same on a WebSocket', browser => queryDeepLink(browser, false)],
  ['history-disconnect-ws', 'Spoonbill.disconnect() while the server holds the route of Back, and the socket closes slowly: the page reloads at the URL of Back only at connect()', disconnectBeforeRoute],
  ['history-unchanged-ascii', 'ordinary same-URL renders preserve Forward and Back on an ASCII route', browser => unchangedRouteHistory(browser, false)],
  ['history-unchanged-unicode', 'ordinary same-URL renders preserve Forward and Back on a raw Unicode route', browser => unchangedRouteHistory(browser, true)],
  ['history-unicode-fragment', 'an ordinary same-URL render preserves the current fragment', unicodeRenderFragment],
  ['history-unicode-query', 'changing only the query adds one entry and Back preserves its fragment', unicodeQueryHistory],
  ['history-unicode', 'Back on a route at `/café/<stage>`: one URL frame, so the number of entries stays, also with an older client', unicodeRoute],
  ['history-back-forward', 'Back then Forward while Back is on the way: the server takes them in order, and a later Back reaches its entry', backThenForward],
  ['history-same-screen', 'Back to a route that leaves the screen as it is: a click while the page waits runs no handler', sameScreen],
  ['history-hung-post', 'a history event that never gets an answer: the page reloads at its URL after the deadline', hungHistory],
  ['history-unconfirmed', 'a navigation whose route is never published: the page reloads at its URL after the deadline', unconfirmedRoute],
  ['history-lost-answer', 'Back confirmed, then its answer lost while Forward waits: Forward goes out, and the page reaches it', lostAnswer],
  ['history-forward-fails', "Forward fails after Back's route is published: the page reloads at Forward's URL", forwardFails],
  ['history-before-capability', 'Back before the first frame of the view: a click on the old display runs no handler', backBeforeCapability],
  ['history-refused', 'a history event that no view can take: the page reloads at once at its URL', refusedHistory]
];
// SPOONBILL_LP_CASES picks cases by key, for example `lost-frame,back`.
const picked = (process.env.SPOONBILL_LP_CASES || all.map(([key]) => key).join(',')).split(',');
for (const key of picked) assert.ok(all.some(([known]) => known === key), `Unknown case ${key}`);
const cases = all.filter(([key]) => picked.includes(key));

(async () => {
  for (const engine of engines) {
    // On Linux the owned WebKit process gets the flake's EGL vendor and
    // software rendering, as in the renderer acceptance runner; without them
    // its first page never opens (docs/browser-tests.md).
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR, 'Use the flake-provided Linux WebKit EGL vendor');
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    const browser = await {chromium, webkit}[engine].launch({headless: true, env, timeout: 15000});
    try {
      for (let round = 1; round <= rounds; round++) {
        for (const [key, name, run] of cases) {
          const state = await run(browser);
          assert.deepEqual(state.errors, [], `${engine}: ${name}: page errors`);
          console.log(JSON.stringify({engine, round, case: key, polls: state.answers.length}));
        }
      }
    } finally { await browser.close(); }
  }
  console.log(`PASS: long polling that resumes in ${engines.join(', ')} (${cases.length} cases, ${rounds} round(s))`);
})().catch(error => { console.error(error); process.exit(1); });
