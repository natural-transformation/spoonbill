// Long polling against the actual Scala service, in Chromium and WebKit.
// The page loads without WebSocket, so the client falls back to long polling.
// One poll then fails inside the browser. The page reconnects by itself, with
// a new bridge. The click after that must reach the service: the server has to
// give the new bridge a new view, which announces its events again.
// Runs through scripts/test-renderer-acceptance.sh, on that script's fixture.
'use strict';
const assert = require('node:assert/strict');
const {setTimeout: pause} = require('node:timers/promises');
assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_F2_ORIGIN, 'Start through scripts/test-renderer-acceptance.sh');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_F2_ORIGIN).origin;
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
const engines = (process.env.SPOONBILL_F2_ENGINES || 'chromium,webkit').split(',');
const clip = text => String(text).split('\n')[0].slice(0, 300);

// The two helpers below follow renderer-acceptance-native-tests.cjs.
async function control(context, command, timeout = 10000) {
  const response = await context.request.post(`${origin}/fixture/${command}`, {timeout});
  assert.equal(response.status(), 200, `Control ${command} must finish within its bound`);
  return JSON.parse(await response.text());
}

// Polls the fixture until `condition` holds, within 5 seconds.
async function waitStatus(context, condition, message) {
  const deadline = Date.now() + 5000;
  for (;;) {
    const remaining = deadline - Date.now();
    assert.ok(remaining > 0, message);
    const status = await control(context, 'status', remaining).catch(error => assert.fail(`${message} (${clip(error.message)})`));
    if (condition(status)) return status;
    await pause(Math.min(25, Math.max(0, deadline - Date.now())));
  }
}

const subscribePattern = /\/bridge\/long-polling\/[^/]+\/subscribe(\?|$)/;
const listensToClick = frame => frame.startsWith('[2,"click"');
const countsClick = frame => frame.startsWith('[0,');

// Waits until `condition` holds, within 5 seconds.
async function waitFor(condition, message) {
  for (const deadline = Date.now() + 5000; !condition(); await pause(10)) assert.ok(Date.now() < deadline, message);
}

async function exercise(browser, engine) {
  const context = await browser.newContext();
  const errors = [];
  // Every poll of the page, in the order in which the page sent them: the
  // opening that it names and the frame that answered it.
  const polls = [];
  let sockets = 0, failNext = false, finished = false, phase = 'start';
  const openings = () => polls.map(poll => poll.opening).join(',');
  try {
    await control(context, 'start/first-patch/plain/none');
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    page.setDefaultNavigationTimeout(10000);
    page.on('pageerror', error => errors.push(`pageerror ${clip(error.message)}`));
    page.on('websocket', () => { sockets++; });
    // The case passes every poll on to the service itself. So it sees each
    // frame, and it can fail exactly the poll that it wants to fail.
    const answer = async route => {
      const poll = {opening: new URL(route.request().url()).searchParams.get('open'), frame: null, failed: false};
      polls.push(poll);
      const response = await route.fetch({timeout: 0});
      poll.frame = response.status() === 200 ? await response.text() : `status ${response.status()}`;
      if (!failNext) return route.fulfill({response});
      failNext = false;
      poll.failed = true;
      return route.abort('connectionreset');
    };
    // The last poll is still outstanding when the case ends, and it fails when
    // the page closes. That is no error of the page; any earlier one is.
    await page.route(subscribePattern, route => answer(route).catch(error => {
      if (!finished) errors.push(`poll ${clip(error.message)}`);
    }));
    // The page has handled a frame when it has sent the poll after it: the
    // client renews its poll in the same task in which it handles the frame.
    const handled = (opening, matches) => polls.some((poll, index) =>
      poll.opening === opening && !poll.failed && poll.frame !== null && matches(poll.frame) && index < polls.length - 1);
    await page.addInitScript(() => {
      window.__ready = 0;
      window.addEventListener('SpoonbillReady', () => { window.__ready++; });
      // Without WebSocket the client selects long polling.
      Object.defineProperty(window, 'WebSocket', {value: undefined, writable: true, configurable: true});
    });

    phase = 'first-opening';
    assert.equal((await page.goto(origin)).status(), 200);
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    assert.equal(sockets, 0, 'The page must run on long polling');
    await waitFor(() => handled('1', listensToClick), `The first view must tell the page to listen to clicks: ${openings()}`);
    await page.click('#current');
    await waitStatus(context, status => status.previous === 1, 'A click of the first opening must reach the service');
    await waitFor(() => handled('1', countsClick), 'The page must get the answer to its first click');

    phase = 'failed-poll';
    // The service answers the next click with one frame. The poll that
    // carries it fails in the browser; the service sees nothing of that.
    failNext = true;
    await page.click('#current');
    await waitStatus(context, status => status.previous === 2, 'The click before the failed poll must reach the service');
    await waitFor(() => polls.some(poll => poll.failed), 'One poll must fail');
    await page.waitForFunction(() => window.__ready >= 2 && window.Spoonbill.ready === true, null, {timeout: 5000})
      .catch(() => assert.fail(`The page must become ready again after the failed poll: ${openings()}`));
    assert.equal(polls.at(-1).opening, '2', `The reconnect is the second opening: ${openings()}`);
    assert.equal(polls.filter(poll => poll.failed).length, 1, 'Exactly one poll failed');

    phase = 'second-opening';
    await waitFor(() => handled('2', listensToClick), `The new view must tell the page again to listen to clicks: ${openings()}`);
    await page.click('#current');
    await waitStatus(context, status => status.previous === 3, 'The click after the reconnect must reach the service');
    assert.equal(sockets, 0);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({engine, polls: polls.length, openings: openings(), result: 'pass'}));
  } catch (error) {
    // Synthetic data only; messages are clipped.
    console.error(JSON.stringify({engine, phase, failure: clip(error.message), openings: openings(), errors}));
    throw error;
  } finally {
    finished = true;
    await context.close();
  }
}

(async () => {
  const types = {chromium, webkit};
  for (const engine of engines) {
    assert.ok(types[engine], `Unknown engine ${engine}`);
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    let browser;
    try {
      browser = await types[engine].launch({headless: true, env, timeout: 15000});
      await exercise(browser, engine);
    } finally { if (browser) await browser.close(); }
  }
  console.log(`PASS: long polling reconnects to a working page (${engines.join(', ')})`);
})().catch(error => { console.error(clip(error.message)); process.exitCode = 1; });
