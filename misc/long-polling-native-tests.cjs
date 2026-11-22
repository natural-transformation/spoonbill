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

const subscribe = /\/bridge\/long-polling\/[^/]+\/subscribe/;
const publish = /\/bridge\/long-polling\/[^/]+\/publish/;

/**
 * A page of the `resume` scenario. Every poll passes through the test, so a
 * case can fail all of them for a while, drop a DOM frame after the server
 * has answered it, or hold its answer. `polling` removes WebSocket, so the
 * client uses long polling.
 */
async function open(browser, path, {polling = true, beforeLoad} = {}) {
  const context = await browser.newContext();
  await control(context, 'start/resume/plain/none');
  const page = await context.newPage();
  page.setDefaultTimeout(15000);
  // `polled` holds the cursor of every poll: the last frame that the page had applied.
  const state = {errors: [], answers: [], published: [], polled: [], loads: 0, dropNextDom: false,
    dropped: false, hold: null, held: false, heldFrame: null, block: false, finished: false};
  page.on('pageerror', error => state.errors.push(`pageerror ${clip(error.message)}`));
  page.on('load', () => { state.loads++; });
  page.on('request', request => { if (publish.test(request.url())) state.published.push(request.postData()); });
  await page.addInitScript(longPolling => {
    window.__ready = 0;
    window.addEventListener('SpoonbillReady', () => { window.__ready++; });
    if (longPolling) Object.defineProperty(window, 'WebSocket', {value: undefined, writable: true, configurable: true});
  }, polling);
  await page.route(subscribe, async route => {
    // A route outlives its case: what comes after the end is ignored.
    const ignore = () => {};
    if (state.finished) return route.abort().catch(ignore);
    state.polled.push(Number(new URL(route.request().url()).searchParams.get('seen')));
    if (state.block) return route.abort('connectionreset').catch(ignore);
    let response, body;
    try { response = await route.fetch(); body = await response.text(); } catch (_) { return route.abort().catch(ignore); }
    state.answers.push({status: response.status(), frame: response.headers()['spoonbill-frame'], body});
    if (state.dropNextDom && body.startsWith('[4')) {
      state.dropNextDom = false; state.dropped = true;
      return route.abort('connectionreset').catch(ignore);
    }
    if (state.hold && body.startsWith('[4')) {
      const release = state.hold; state.hold = null; state.held = true;
      state.heldFrame = Number(response.headers()['spoonbill-frame']);
      await release;
    }
    return route.fulfill({response, body}).catch(ignore);
  });
  if (beforeLoad) await beforeLoad(page, context, state);
  await page.goto(origin + path);
  await waitFor(() => ready(page), 'The page never became ready');
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
    answer.body.startsWith('[0,') && state.polled.some(seen => seen >= Number(answer.frame))),
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
    await waitFor(() => state.polled.some(seen => seen >= state.heldFrame), 'The held patch was never applied');
    await waitFor(async () => new URL(page.url()).pathname === backTo && (await stage(page)) === `stage ${backTo.split('/').pop()}`,
      'The page never showed the route that Back went to');
    assert.equal(state.loads, 1, 'The page reloaded');
    assert.equal(await page.evaluate(() => window.__ready), 1, 'The bridge was built again');
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
    await waitFor(() => ready(page), 'The reloaded page never became ready');
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
    await waitFor(() => ready(page), 'The reloaded page never became ready');
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
  ['lost-state-lp', 'a page at a route whose state is gone reloads once to it, on long polling', browser => lostState(browser, true)],
  ['lost-state-ws', 'a page at a route whose state is gone reloads once to it, on a WebSocket', browser => lostState(browser, false)]
];
// SPOONBILL_LP_CASES picks cases by key, for example `lost-frame,back`.
const picked = (process.env.SPOONBILL_LP_CASES || all.map(([key]) => key).join(',')).split(',');
for (const key of picked) assert.ok(all.some(([known]) => known === key), `Unknown case ${key}`);
const cases = all.filter(([key]) => picked.includes(key));

(async () => {
  for (const engine of engines) {
    const browser = await {chromium, webkit}[engine].launch({headless: true});
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
