// Public v4 example correctness against the actual server and browser engines.
// No client authentication implementation, fabricated frames, or performance sampling.
const assert = require('node:assert/strict');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_AUTH_ORIGIN, 'Start the public example through scripts/test-browser-auth.sh');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_AUTH_ORIGIN).origin;
const provider = process.env.SPOONBILL_AUTH_PROVIDER ?? 'memory';
assert.match(origin, /^http:\/\/(localhost|127\.0\.0\.1):\d+$/);
assert.ok(['memory', 'jdbc', 'io'].includes(provider), 'Unknown example provider');
const engines = (process.env.SPOONBILL_AUTH_ENGINES ?? 'chromium,webkit').split(',').map(value => value.trim());
assert.ok(engines.length > 0 && new Set(engines).size === engines.length &&
  engines.every(value => ['chromium', 'webkit'].includes(value)), 'Select chromium, webkit, or both');

function frameCode(event) {
  try { return JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload)[0]; }
  catch (_) { return undefined; }
}

async function exercise(browser, engine, username, factor) {
  const context = await browser.newContext();
  let phase = 'create-page';
  let page;
  const errors = [], sockets = [], responses = [];
  const publicPaths = new Set(['/', '/protected', '/auth/complete', '/auth/logout', '/no-http-proof-submission']);
  try {
    page = await context.newPage();
    page.setDefaultTimeout(25000);
    page.setDefaultNavigationTimeout(25000);
    page.on('pageerror', error => errors.push({phase, name: /^[A-Za-z]+$/.test(error.name) ? error.name : 'Error'}));
    page.on('response', response => {
      const path = new URL(response.url()).pathname;
      responses.push({phase, path: publicPaths.has(path) ? path : 'resource', status: response.status()});
      if (responses.length > 32) responses.shift();
    });
    page.on('websocket', socket => {
      const observation = {url: socket.url(), phase, codes: [], unknownFrames: 0, closed: false, readySeen: false};
      socket.on('framereceived', event => {
        const code = frameCode(event);
        if (Number.isInteger(code) && code >= 0 && code <= 255) {
          observation.codes.push(code);
          if (observation.codes.length > 48) observation.codes.shift();
          if (code === 21) observation.readySeen = true;
        } else observation.unknownFrames++;
      });
      socket.on('close', () => { observation.closed = true; observation.closedAt = phase; });
      observation.ready = socket.waitForEvent('framereceived', {
        predicate: event => frameCode(event) === 21, timeout: 25000,
      }).then(() => null, error => error);
      sockets.push(observation);
    });
    async function ready() {
      assert.ok(sockets.length, `${engine}: a physical WebSocket is required`);
      const error = await sockets.at(-1).ready;
      if (error) throw error;
      await page.waitForFunction(() => window.Spoonbill?.ready === true);
    }
    function nextSocket() {
      const pending = page.waitForEvent('websocket');
      pending.catch(() => {});
      return pending;
    }
    const firstSocket = nextSocket();
    phase = 'initial-http';
    assert.equal((await page.goto(origin + '/')).status(), 200);
    await firstSocket;
    phase = 'initial-ready'; await ready();
    const firstView = new URL(sockets[0].url).pathname;
    const binding = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_binding');
    assert.ok(binding?.httpOnly, 'Binding must remain HttpOnly');
    assert.match(binding.value, /^[A-Za-z0-9_-]{43}$/);
    phase = 'anonymous-protected-http';
    assert.equal((await context.request.get(origin + '/protected')).status(), 403,
      'Anonymous protected HTTP rendering must be denied');

    // A real committed handle must be rejected for another origin and then
    // succeed through the unchanged browser completion exchange.
    await page.route(origin + '/auth/complete', async route => {
      const request = route.request();
      if (request.method() !== 'POST') return route.continue();
      assert.match(request.postData(), /^[0-9a-f-]{36}$/);
      const rejected = await context.request.post(origin + '/auth/complete', {
        headers: {'Origin': 'http://invalid.example', 'Sec-Fetch-Site': 'same-origin', 'Content-Type': 'text/plain'},
        data: request.postData(), timeout: 10000,
      });
      assert.equal(rejected.status(), 403);
      await route.continue();
    });
    const completion = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/complete');
    completion.catch(() => {});
    phase = 'begin-sign-in';
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').waitFor({state: 'visible'});
    const passwordForm = page.locator('form').filter({has: page.locator('input[name="password"]')});
    const ceremony = await passwordForm.locator('input[name="ceremony"]').inputValue();
    assert.equal(await passwordForm.getAttribute('action'), '/no-http-proof-submission');
    phase = 'password-submit';
    await page.locator('input[name="username"]').fill(username);
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    if (factor) {
      phase = 'factor-prompt';
      await page.locator('input[name="factor"]').waitFor({state: 'visible'});
      const factorForm = page.locator('form').filter({has: page.locator('input[name="factor"]')});
      assert.equal(await factorForm.locator('input[name="ceremony"]').inputValue(), ceremony);
      phase = 'factor-submit';
      await page.locator('input[name="factor"]').fill(factor);
      await page.getByRole('button', {name: 'Verify factor', exact: true}).click();
    }
    phase = 'completion-delivery';
    assert.equal((await completion).status(), 204);
    phase = 'authenticated-presentation';
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    phase = 'authenticated-ready'; await ready();
    assert.ok(sockets.length >= 2, 'Login must activate through a fresh physical handshake');
    assert.equal(new URL(sockets[1].url).pathname, firstView, 'Completion must reconnect the original view');
    const session = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(session?.httpOnly, 'Session must remain HttpOnly');
    assert.equal(session.sameSite, 'Lax');
    assert.ok(!(await page.content()).includes(session.value), 'Session credential must not enter presentation');
    phase = 'protected-action';
    await page.getByRole('button', {name: 'Protected increment', exact: true}).click();
    await page.getByText('Protected increment committed.', {exact: true}).waitFor({state: 'visible'});
    const counter = await page.getByText(/This view's last confirmed increment: \d+/).textContent();
    assert.ok(Number(counter.match(/(\d+)$/)[1]) > 0);
    phase = 'proof-fallback-rejection';
    assert.equal((await context.request.post(origin + '/no-http-proof-submission', {
      data: 'username=synthetic&password=unused', headers: {'Origin': origin}, timeout: 10000,
    })).status(), 404);

    const protectedSocket = nextSocket();
    phase = 'protected-http';
    assert.equal((await page.goto(origin + '/protected')).status(), 200);
    await protectedSocket;
    phase = 'protected-ready'; await ready();
    await page.getByRole('heading', {name: 'Protected page', exact: true}).waitFor({state: 'visible'});
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    const reloadSocket = nextSocket();
    phase = 'protected-reload-http';
    assert.equal((await page.reload()).status(), 200);
    await reloadSocket;
    phase = 'protected-reload-ready'; await ready();
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});

    const logout = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/logout');
    logout.catch(() => {});
    const publicSocket = nextSocket();
    phase = 'logout-submit';
    await page.getByRole('button', {name: 'Sign out', exact: true}).click();
    assert.equal((await logout).status(), 303);
    await page.waitForURL(origin + '/');
    await publicSocket;
    phase = 'logout-public-ready'; await ready();
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).waitFor({state: 'visible'});
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'spoonbill_session'), undefined);
    assert.equal(await page.getByText(`Authenticated account: ${username}`, {exact: true}).count(), 0);
    phase = 'stale-cookie-rejection';
    await context.addCookies([session]);
    assert.equal((await page.goto(origin + '/protected')).status(), 403, 'A logged-out cookie must stay fenced');
    await page.getByRole('heading', {name: 'Authentication required', exact: true}).waitFor({state: 'visible'});
    assert.deepEqual(errors, [], `${engine}: browser runtime errors`);
    console.log(JSON.stringify({provider, engine, flow: factor ? 'factor' : 'password',
      realCookieDelivery: true, sameViewActivation: true, protectedAction: true,
      protectedHttpAndReload: true, logout: true, staleCookieDenied: true, foreignOriginDenied: true}));
  } catch (error) {
    const failure = /Socket closed/.test(error.message) ? 'socket-closed-before-ready'
      : error.name === 'TimeoutError' ? 'timeout' : error.name === 'AssertionError' ? 'assertion' : 'runtime-error';
    let uiState = {unavailable: true}, diagnosticTimer;
    try {
      if (page) uiState = await Promise.race([
        page.evaluate(() => ({ready: window.Spoonbill?.ready === true, documentState: document.readyState,
          passwordForm: !!document.querySelector('input[name="password"]'),
          factorForm: !!document.querySelector('input[name="factor"]')})),
        new Promise(resolve => { diagnosticTimer = setTimeout(() => resolve({unavailable: true}), 1000); }),
      ]);
    } catch (_) { /* A closed page still has the bounded protocol trace. */ }
    finally { clearTimeout(diagnosticTimer); }
    console.error(JSON.stringify({kind: 'browser-auth-failure', provider, engine, username, phase, failure, uiState,
      responses, pageErrors: errors, socketCount: sockets.length,
      closeCount: sockets.filter(socket => socket.closed).length,
      sockets: sockets.slice(-16).map((socket, index) => ({ordinal: Math.max(0, sockets.length - 16) + index + 1,
        openedAt: socket.phase, closedAt: socket.closedAt, closed: socket.closed,
        readySeen: socket.readySeen, codes: socket.codes, unknownFrames: socket.unknownFrames}))}));
    throw new Error(`${provider}/${engine}/${username}: ${phase}: ${failure}`);
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
      timer = setTimeout(() => reject(new Error(`${engine}: bounded browser-auth smoke deadline exceeded`)), 120000);
    });
    try {
      await Promise.race([deadline, (async () => {
        browser = await type.launch({headless: true, env, timeout: 15000});
        await exercise(browser, engine, 'alice');
        await exercise(browser, engine, 'bob', '123456');
      })()]);
    } finally { clearTimeout(timer); if (browser) await browser.close(); }
  }
  console.log(`PASS: public ${provider} example in ${engines.join(', ')}`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
