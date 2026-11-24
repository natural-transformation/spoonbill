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

const subscribe = /\/bridge\/long-polling\/[^/]+\/subscribe/;
const publish = /\/bridge\/long-polling\/[^/]+\/publish/;

/**
 * A page of the `resume` scenario. Every poll passes through the test, so a
 * case can fail all of them for a while, drop a DOM frame after the server
 * has answered it, or hold its answer. `polling` removes WebSocket, so the
 * client uses long polling.
 */
async function open(browser, path, {polling = true, beforeLoad, scenario = 'resume'} = {}) {
  const context = await browser.newContext();
  await control(context, `start/${scenario}/plain/none`);
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  // `polled` holds the page and the cursor of every poll: the last frame that
  // the page had applied. `sockets` holds the frames of every WebSocket.
  // `log` has every poll and message in the order that they left the page.
  // `holdReplies` holds the answers that carry a heartbeat reply.
  const state = {errors: [], answers: [], published: [], polled: [], sockets: [], loads: 0, dropNextDom: false,
    dropped: false, hold: null, held: false, heldFrame: null, block: false, hang: null, finished: false,
    log: [], holdReplies: null};
  page.on('pageerror', error => state.errors.push(`pageerror ${clip(error.message)}`));
  page.on('load', () => { state.loads++; });
  page.on('request', request => {
    if (!publish.test(request.url())) return;
    state.published.push(request.postData());
    state.log.push({kind: 'post', body: request.postData()});
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
    return route.continue().catch(() => {});
  });
  if (beforeLoad) await beforeLoad(page, context, state);
  await page.goto(origin + path);
  await usable(page, state, polling, 'The page never became ready to take a click');
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
  ['eval-pause', 'a command that pauses the page: its result reaches the server once after connect()', evalDuringPause]
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
