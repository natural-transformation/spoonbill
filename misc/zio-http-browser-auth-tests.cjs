// ZIO HTTP transport check: real cookie delivery, authenticated reconnect,
// authenticated replacement, sensitive disclosure and then navigation in the
// same browser context, including lost-response bootstrap through ZIO HTTP.
// The raw-socket ZioHttpKeepAliveSpec is the strict same-connection proof;
// browsers may choose different connections.
const assert = require('node:assert/strict');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_AUTH_ORIGIN, 'Start the fixture through scripts/test-zio-http-browser-auth.sh');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_AUTH_ORIGIN).origin;
assert.match(origin, /^http:\/\/(localhost|127\.0\.0\.1):\d+$/);
const engines = (process.env.SPOONBILL_AUTH_ENGINES ?? 'chromium,webkit').split(',').map(value => value.trim());
assert.ok(engines.length > 0 && new Set(engines).size === engines.length &&
  engines.every(value => ['chromium', 'webkit'].includes(value)), 'Select chromium, webkit, or both');

function frameCode(event) {
  try { return JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload)[0]; }
  catch (_) { return undefined; }
}

const navigations = {
  // Native GET form submission rendered by the fixture.
  native: page => page.getByRole('button', {name: 'Open dashboard', exact: true}).click(),
  programmatic: page => page.evaluate(() => { window.location.assign('/dashboard'); }),
};

async function exercise(browser, engine, first) {
  const second = first === 'native' ? 'programmatic' : 'native';
  const context = await browser.newContext();
  let phase = 'create-page', page;
  const errors = [], sockets = [], responses = [];
  const known = new Set(['/', '/dashboard', '/auth/complete', '/auth/recover', '/auth/activate']);
  try {
    const initialReplacements = Number(await (await context.request.get(origin + '/fixture/replacements')).text());
    assert.ok(Number.isSafeInteger(initialReplacements) && initialReplacements >= 0);
    await context.addInitScript(() => {
      window.fixtureReadyViews = [];
      window.addEventListener('SpoonbillReady', () => {
        // Observe the DOM synchronously at event admission, without retaining
        // credentials, ceremony values, sensitive payloads or document HTML.
        window.fixtureReadyViews.push({
          authenticated: document.body.textContent.includes('Authenticated account: alice'),
          anonymousControls: document.querySelectorAll('#anonymous-controls, #anonymous-error, #anonymous-setup, #anonymous-form, input[name="password"], input[name="ceremony"]').length,
          recoveryButton: [...document.querySelectorAll('button')].some(button => button.textContent === 'Show recovery codes'),
          disclosureCompleted: document.querySelector('#message')?.textContent === 'Recovery codes disclosed.',
          disclosureCount: document.querySelector('#disclosure-count')?.textContent ?? null,
        });
      });
    });
    page = await context.newPage();
    page.setDefaultTimeout(15000);
    page.setDefaultNavigationTimeout(15000);
    page.on('pageerror', error => errors.push({phase, name: /^[A-Za-z]+$/.test(error.name) ? error.name : 'Error'}));
    page.on('response', response => {
      const path = new URL(response.url()).pathname;
      responses.push({phase, path: known.has(path) ? path : 'resource', status: response.status()});
      if (responses.length > 32) responses.shift();
    });
    page.on('websocket', socket => {
      const observation = {url: socket.url(), phase, codes: [], closed: false, startup: [], readySeen: false,
        freshReset: false, earlyUserEvents: 0};
      socket.on('framereceived', event => {
        const code = frameCode(event);
        if (Number.isInteger(code)) { observation.codes.push(code); if (observation.codes.length > 48) observation.codes.shift(); }
        if (!observation.readySeen && Number.isInteger(code)) observation.startup.push(code);
        if (code === 19) {
          const frame = JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload);
          const dom = JSON.stringify(frame[4]);
          // The reset may render presentation.fresh(), followed by the
          // connected projection before readiness. Validate that baseline's
          // envelope and absence of the old error, then inspect the ready DOM.
          observation.freshReset = typeof frame[1] === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(frame[1]) &&
            typeof frame[2] === 'string' && /^[0-9a-f-]{36}$/.test(frame[2]) && frame[3] === '0' &&
            Array.isArray(frame[4]) && frame[4][0] === 4 && !dom.includes('anonymous-error');
        }
        if (code === 21) observation.readySeen = true;
      });
      socket.on('framesent', event => {
        if ([0, 1, 3, 7].includes(frameCode(event)) && !observation.readySeen) observation.earlyUserEvents++;
      });
      socket.on('close', () => { observation.closed = true; });
      observation.ready = new URL(socket.url()).pathname === '/auth/activate' ? Promise.resolve(null) :
        socket.waitForEvent('framereceived', {predicate: event => frameCode(event) === 21, timeout: 15000})
          .then(() => null, error => error);
      sockets.push(observation);
    });
    const nextSocket = () => {
      const pending = page.waitForEvent('websocket', {predicate: socket => new URL(socket.url()).pathname.startsWith('/bridge/web-socket/')});
      pending.catch(() => {}); return pending;
    };
    function authorizedBaseline(socket) {
      const changes = socket.startup.filter(code => [4, 19, 20, 21].includes(code));
      assert.equal(changes[0], 19, 'Authentication reconnect must reset the DOM before patches or event readiness');
      assert.equal(changes.at(-1), 21, 'The authorized baseline must precede event readiness');
      assert.ok(socket.freshReset, 'Reset must establish revision zero without retaining the old error banner');
      assert.equal(socket.earlyUserEvents, 0, 'User events must not leave the browser before its baseline is ready');
    }
    async function authorizedView(disclosureCompleted) {
      const view = await page.evaluate(() => window.fixtureReadyViews.at(-1));
      assert.deepEqual(view, {authenticated: true, anonymousControls: 0, recoveryButton: true, disclosureCompleted,
        disclosureCount: `Successful disclosures: ${disclosureCompleted ? 1 : 0}`},
        'The authorized view must be complete when SpoonbillReady admits user events');
      assert.equal(await page.locator('#anonymous-controls, #anonymous-error, #anonymous-setup, #anonymous-form, input[name="password"], input[name="ceremony"]').count(), 0,
        'Anonymous controls and the invalid-submission banner must be removed');
    }
    async function ready() {
      assert.ok(sockets.length, `${engine}: a physical WebSocket is required`);
      const error = await sockets.at(-1).ready;
      if (error) throw error;
      await page.waitForFunction(() => window.Spoonbill?.ready === true);
    }
    async function navigate(kind) {
      const socket = nextSocket();
      const response = page.waitForResponse(value => value.request().resourceType() === 'document' &&
        new URL(value.url()).pathname === '/dashboard');
      response.catch(() => {});
      phase = `${kind}-navigation`;
      await navigations[kind](page);
      assert.equal((await response).status(), 200, `${kind} navigation must receive its response`);
      await page.getByRole('heading', {name: 'Dashboard', exact: true}).waitFor({state: 'visible'});
      await page.getByText('Authenticated account: alice', {exact: true}).waitFor({state: 'visible'});
      await socket;
      phase = `${kind}-ready`; await ready();
    }

    const firstSocket = nextSocket();
    phase = 'initial-http';
    assert.equal((await page.goto(origin + '/')).status(), 200);
    await firstSocket;
    phase = 'initial-ready'; await ready();
    const firstView = new URL(sockets[0].url).pathname;

    phase = 'begin-sign-in';
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').waitFor({state: 'visible'});
    phase = 'invalid-password-submit';
    await page.locator('input[name="username"]').fill('alice');
    await page.locator('input[name="password"]').fill('invalid');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await page.locator('#anonymous-error').getByText('Invalid password. Try again.', {exact: true}).waitFor({state: 'visible'});
    assert.ok(await page.evaluate(() => document.querySelector('#anonymous-error').nextElementSibling?.id === 'anonymous-setup'),
      'Invalid submission must structurally insert a banner before the setup region');
    const completion = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/complete');
    completion.catch(() => {});
    const authenticatedSocket = nextSocket();
    phase = 'valid-password-submit';
    await page.locator('input[name="username"]').fill('alice');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    phase = 'completion-delivery';
    assert.equal((await completion).status(), 204);
    phase = 'authenticated-reconnect';
    await authenticatedSocket;
    await ready();
    assert.ok(sockets.length >= 2, 'Login must reconnect through a fresh physical handshake');
    assert.equal(new URL(sockets[1].url).pathname, firstView, 'Completion must reconnect the original view');
    authorizedBaseline(sockets[1]);
    await authorizedView(false);
    const session = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(session?.httpOnly, 'Session cookie must be delivered as HttpOnly');

    phase = 'sensitive-disclosure';
    await page.getByRole('button', {name: 'Show recovery codes', exact: true}).click();
    await page.locator('#message').getByText('Recovery codes disclosed.', {exact: true}).waitFor({state: 'visible'});
    await page.locator('#disclosure-count').getByText('Successful disclosures: 1', {exact: true}).waitFor({state: 'visible'});

    phase = 'same-identity-reconnect';
    const reconnectedSocket = nextSocket();
    await page.evaluate(() => window.Spoonbill.disconnect(true));
    const resumedSocket = await reconnectedSocket;
    await ready();
    assert.equal(new URL(sockets.at(-1).url).pathname, firstView, 'Same-identity reconnect must retain the view');
    authorizedBaseline(sockets.at(-1));
    await authorizedView(true);
    // A retained message alone does not prove callbacks survived reconnect.
    const redisclosure = resumedSocket.waitForEvent('framereceived', {predicate: event => frameCode(event) === 22, timeout: 15000});
    redisclosure.catch(() => {});
    await page.getByRole('button', {name: 'Show recovery codes', exact: true}).click();
    await redisclosure;
    // This count advances only after the server receives BrowserProcessed,
    // proving a new callback, disclosure and successful acknowledgement.
    await page.locator('#disclosure-count').getByText('Successful disclosures: 2', {exact: true}).waitFor({state: 'visible'});

    phase = 'authenticated-replacement';
    const predecessorCookies = await context.cookies();
    const oldSensitiveHost = await page.locator('sb-secret').elementHandle();
    const oldSocket = sockets.at(-1);
    const replacementCompletion = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/complete');
    replacementCompletion.catch(() => {});
    const successorSocket = nextSocket();
    await page.locator('#replacement-form input[name="replacement-password"]').fill('password');
    await page.getByRole('button', {name: 'Replace session', exact: true}).click();
    assert.equal((await replacementCompletion).status(), 204);
    await successorSocket;
    await ready();
    assert.ok(oldSocket.codes.includes(18), 'Replacement must use the fixed completion command after predecessor revocation');
    assert.equal(new URL(sockets.at(-1).url).pathname, firstView, 'Replacement must reconnect the same view');
    authorizedBaseline(sockets.at(-1));
    await authorizedView(false);
    assert.equal(await oldSensitiveHost.evaluate(element => element.isConnected), false,
      'Replacement must retire the old sensitive presentation host with its old DOM');
    await oldSensitiveHost.dispose();
    const successorCookie = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(successorCookie?.httpOnly && successorCookie.value !== session.value,
      'Replacement must install a fresh HttpOnly credential');
    const stale = await browser.newContext();
    try {
      await stale.addCookies(predecessorCookies);
      const denied = await stale.request.get(origin + '/dashboard');
      // ZIO HTTP currently maps service effect failures through fromThrowable
      // (500); adapters with explicit access-denied mapping return 403.
      assert.ok([403, 500].includes(denied.status()),
        'The predecessor cookie must remain denied after successor activation');
      assert.equal((await denied.text()).includes('Authenticated account: alice'), false,
        'A denied predecessor must never receive protected application content');
    } finally { await stale.close(); }
    assert.equal(await (await context.request.get(origin + '/fixture/replacements')).text(), String(initialReplacements + 1),
      'The password proof and replacement mutation must commit once');

    phase = 'rolled-back-replacement-observer';
    const beforeRollback = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session').value;
    const rollbackSocket = sockets.at(-1);
    const negativeResponse = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/recover' && response.request().postData()?.length === 36);
    negativeResponse.catch(() => {});
    const unchangedSessionSocket = nextSocket();
    await page.locator('#rolled-back-replacement-form input[name="replacement-password"]').fill('password');
    await page.getByRole('button', {name: 'Recover rolled-back replacement', exact: true}).click();
    const negative = await negativeResponse;
    assert.equal(negative.status(), 204, 'An uncertain rolled-back attempt must reach checked negative reconciliation');
    assert.equal(negative.headers()['set-cookie'], undefined, 'Negative reconciliation must not replace the old credential');
    await unchangedSessionSocket;
    await ready();
    await authorizedView(false);
    assert.ok(rollbackSocket.codes.includes(26), 'A current predecessor must receive only the ordinary-authorized recovery instruction');
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'spoonbill_session').value, beforeRollback);
    assert.equal(await (await context.request.get(origin + '/fixture/replacements')).text(), String(initialReplacements + 1),
      'Negative reconciliation must not replay rolled-back host work');

    phase = 'lost-replacement-observer';
    const recoveredObserver = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/recover' && response.status() === 204);
    recoveredObserver.catch(() => {});
    const observerSuccessor = nextSocket();
    await page.locator('#interrupted-replacement-form input[name="replacement-password"]').fill('password');
    await page.getByRole('button', {name: 'Replace with lost observer', exact: true}).click();
    await recoveredObserver;
    await observerSuccessor;
    await ready();
    authorizedBaseline(sockets.at(-1));
    await authorizedView(false);
    assert.equal(await (await context.request.get(origin + '/fixture/replacements')).text(), String(initialReplacements + 2),
      'A thrown observer must trigger recovery without repeating the committed mutation');

    let recoveredReplacement = false;
    if (first === 'programmatic') {
      phase = 'lost-replacement-response';
      let dropped = 0;
      const dropCompletion = async route => { dropped++; await route.abort('failed'); };
      await page.route(origin + '/auth/complete', dropCompletion);
      const recovered = page.waitForResponse(response => response.request().method() === 'POST' &&
        new URL(response.url()).pathname === '/auth/recover' && response.status() === 204);
      recovered.catch(() => {});
      const activation = page.waitForEvent('websocket', {predicate: socket => new URL(socket.url()).pathname === '/auth/activate'});
      activation.catch(() => {});
      const restoredApplication = nextSocket();
      try {
        await page.locator('#replacement-form input[name="replacement-password"]').fill('password');
        await page.getByRole('button', {name: 'Replace session', exact: true}).click();
        await recovered;
        await activation;
        await restoredApplication;
        await ready();
        await authorizedView(false);
        assert.equal(dropped, 3, 'Lost delivery retries must remain bounded before framework bootstrap');
        assert.equal(await (await context.request.get(origin + '/fixture/replacements')).text(), String(initialReplacements + 3),
          'Recovery must not re-run the replacement mutation or consume another proof');
        recoveredReplacement = true;
      } finally { await page.unroute(origin + '/auth/complete', dropCompletion); }
    }

    // No context reset between completion and either navigation.
    await navigate(first);
    phase = 'return-home';
    const homeSocket = nextSocket();
    await page.evaluate(() => { window.location.assign('/'); });
    await page.getByText('Authenticated account: alice', {exact: true}).waitFor({state: 'visible'});
    await homeSocket;
    await ready();
    await navigate(second);
    assert.deepEqual(errors, [], `${engine}: browser runtime errors`);
    console.log(JSON.stringify({engine, order: [first, second], completion: 204, reconnect: true,
      disclosure: true, authorizedBaseline: true, sameIdentityReconnect: true, replacement: true,
      predecessorDenied: true, recoveredObserver: true, reconciledRollback: true, recoveredReplacement, dashboardNavigations: 2}));
  } catch (error) {
    const failure = error.name === 'TimeoutError' ? 'timeout' : error.name === 'AssertionError' ? 'assertion' : 'runtime-error';
    // Paths, statuses and frame codes only: never cookies, handles or payloads.
    console.error(JSON.stringify({kind: 'zio-http-browser-auth-failure', engine, first, phase, failure,
      message: error.name === 'AssertionError' ? error.message : undefined, responses, pageErrors: errors,
      sockets: sockets.map(socket => ({openedAt: socket.phase, closed: socket.closed, codes: socket.codes}))}));
    throw new Error(`${engine}/${first}: ${phase}: ${failure}`);
  } finally { await context.close(); }
}

(async () => {
  for (const engine of engines) {
    const type = {chromium, webkit}[engine];
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR, 'Use the pinned Linux WebKit EGL vendor');
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    let browser, timer;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${engine}: bounded ZIO HTTP browser-auth deadline exceeded`)), 120000);
    });
    try {
      await Promise.race([deadline, (async () => {
        browser = await type.launch({headless: true, env, timeout: 15000});
        await exercise(browser, engine, 'native');
        await exercise(browser, engine, 'programmatic');
      })()]);
    } finally { clearTimeout(timer); if (browser) await browser.close(); }
  }
  console.log(`PASS: ZIO HTTP browser authentication in ${engines.join(', ')}`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
