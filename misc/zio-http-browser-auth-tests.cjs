// ZIO HTTP transport check: real cookie delivery, authenticated reconnect,
// sensitive disclosure and then navigation in the same browser context.
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
  const known = new Set(['/', '/dashboard', '/auth/complete']);
  try {
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
      const observation = {url: socket.url(), phase, codes: [], closed: false};
      socket.on('framereceived', event => {
        const code = frameCode(event);
        if (Number.isInteger(code)) { observation.codes.push(code); if (observation.codes.length > 48) observation.codes.shift(); }
      });
      socket.on('close', () => { observation.closed = true; });
      observation.ready = socket.waitForEvent('framereceived', {predicate: event => frameCode(event) === 21, timeout: 15000})
        .then(() => null, error => error);
      sockets.push(observation);
    });
    const nextSocket = () => { const pending = page.waitForEvent('websocket'); pending.catch(() => {}); return pending; };
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

    const completion = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/complete');
    completion.catch(() => {});
    phase = 'begin-sign-in';
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').waitFor({state: 'visible'});
    phase = 'password-submit';
    await page.locator('input[name="username"]').fill('alice');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    phase = 'completion-delivery';
    assert.equal((await completion).status(), 204);
    phase = 'authenticated-reconnect';
    await page.getByText('Authenticated account: alice', {exact: true}).waitFor({state: 'visible'});
    await ready();
    assert.ok(sockets.length >= 2, 'Login must reconnect through a fresh physical handshake');
    assert.equal(new URL(sockets[1].url).pathname, firstView, 'Completion must reconnect the original view');
    const session = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(session?.httpOnly, 'Session cookie must be delivered as HttpOnly');

    phase = 'sensitive-disclosure';
    await page.getByRole('button', {name: 'Show recovery codes', exact: true}).click();
    await page.locator('#message').getByText('Recovery codes disclosed.', {exact: true}).waitFor({state: 'visible'});

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
      disclosure: true, dashboardNavigations: 2}));
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
