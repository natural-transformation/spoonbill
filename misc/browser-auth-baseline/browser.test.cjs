// Actual reference-server/browser correctness. No mocked transport or app script.
// Start MemoryReferenceServer separately, then run through nix develop .#browser.
const assert = require('node:assert/strict');
const {inflateRawSync} = require('node:zlib');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use the repository Nix browser environment');
assert.ok(process.env.SPOONBILL_AUTH_BASELINE_ORIGIN, 'Supply the running reference server origin');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_AUTH_BASELINE_ORIGIN).origin;
const provider = process.env.SPOONBILL_AUTH_BASELINE_PROVIDER ?? 'memory';
assert.ok(/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(origin), 'Synthetic loopback server required');

function frameCode(event) {
  try { return JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload)[0]; }
  catch (_) { return undefined; }
}

function decodedFrame(message) {
  const bytes = Buffer.isBuffer(message) ? message : Buffer.from(message);
  try { return JSON.parse(bytes.toString('utf8')); }
  catch (_) {
    try { return JSON.parse(inflateRawSync(bytes, {maxOutputLength: 1024 * 1024}).toString('utf8')); }
    catch (_) { return null; }
  }
}

async function exercise(browser, engine, username, factor) {
  const context = await browser.newContext();
  try {
    const page = await context.newPage();
    page.setDefaultTimeout(30000);
    page.setDefaultNavigationTimeout(30000);
    const errors = [], sockets = [];
    page.on('pageerror', error => errors.push(error.message));
    page.on('websocket', socket => {
      sockets.push({url: socket.url(), ready: socket.waitForEvent('framereceived', {
        predicate: event => frameCode(event) === 21, timeout: 30000,
      }).then(() => null, error => error)});
    });
    async function ready() {
      assert.ok(sockets.length, `${engine}: physical WebSocket missing`);
      const error = await sockets.at(-1).ready;
      if (error) throw error;
      await page.waitForFunction(() => window.Spoonbill?.ready === true);
    }
    function nextSocket() {
      const pending = page.waitForEvent('websocket');
      // Navigation and the socket are awaited in order; retain an early socket
      // failure for that await instead of causing an unhandled rejection.
      pending.catch(() => {});
      return pending;
    }
    const firstSocket = nextSocket();
    const initial = await page.goto(origin + '/');
    assert.equal(initial.status(), 200);
    await firstSocket; await ready();
    const originalSocket = new URL(sockets[0].url).pathname;
    const binding = (await context.cookies()).find(cookie => cookie.name === 'baseline_binding');
    assert.ok(binding?.httpOnly, `${engine}: initial binding must be HttpOnly`);
    assert.equal(binding.sameSite, 'Lax');
    assert.match(binding.value, /^[A-Za-z0-9_-]{43}$/);

    // Hold the real, already prepared completion before its browser fetch can
    // deliver a cookie. APIRequestContext bypasses this page-only route.
    let completionAttempt;
    await page.route(origin + '/auth/complete', async route => {
      const request = route.request();
      if (request.method() !== 'POST') return route.continue();
      completionAttempt = request.postData();
      assert.match(completionAttempt, /^[0-9a-f-]{36}$/);
      assert.equal((await context.cookies()).find(cookie => cookie.name === 'baseline_session'), undefined);
      const forbidden = await context.request.post(origin + '/auth/complete', {
        headers: {'Origin': 'http://invalid.example', 'Sec-Fetch-Site': 'same-origin', 'Content-Type': 'text/plain'},
        data: completionAttempt, maxRedirects: 0, timeout: 10000,
      });
      assert.equal(forbidden.status(), 403, `${engine}: foreign origin accepted a deliverable completion`);
      assert.equal((await context.cookies()).find(cookie => cookie.name === 'baseline_session'), undefined);
      await route.continue();
    });
    const permittedCompletion = page.waitForResponse(response =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/auth/complete');

    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').waitFor({state: 'visible'});
    const passwordForm = page.locator('form').filter({has: page.locator('input[name="password"]')});
    assert.equal(await passwordForm.getAttribute('method'), 'post');
    assert.equal(await passwordForm.getAttribute('action'), '/reference-submit-unavailable');
    const ceremony = await passwordForm.locator('input[name="ceremony"]').inputValue();
    assert.match(ceremony, /^[0-9a-f-]{36}$/);
    await page.locator('input[name="username"]').fill(username);
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    if (factor) {
      await page.locator('input[name="factor"]').waitFor({state: 'visible'});
      const factorForm = page.locator('form').filter({has: page.locator('input[name="factor"]')});
      assert.equal(await factorForm.getAttribute('method'), 'post');
      assert.equal(await factorForm.getAttribute('action'), '/reference-submit-unavailable');
      assert.equal(await factorForm.locator('input[name="ceremony"]').inputValue(), ceremony,
        `${engine}: challenge must retain the original ceremony`);
      assert.match(await page.locator('input[name="challenge"]').inputValue(), /^[0-9a-f-]{36}$/);
      await page.locator('input[name="factor"]').fill(factor);
      await page.getByRole('button', {name: 'Verify factor', exact: true}).click();
    }
    assert.equal((await permittedCompletion).status(), 204, `${engine}: permitted delivery of the same attempt failed`);
    if (provider === 'memory') assert.equal(completionAttempt, ceremony, `${engine}: delivery must use the original attempt`);
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    await ready();
    assert.ok(sockets.length >= 2, `${engine}: login must use a fresh physical handshake`);
    assert.equal(new URL(sockets[1].url).pathname, originalSocket,
      `${engine}: completion must first reconnect the same view`);
    const session = (await context.cookies()).find(cookie => cookie.name === 'baseline_session');
    assert.ok(session?.httpOnly, `${engine}: session credential must be HttpOnly`);
    assert.equal(session.sameSite, 'Lax');
    assert.match(session.value, /^[A-Za-z0-9_-]{43}$/);
    assert.ok(!(await page.content()).includes(session.value), 'Credential leaked into presentation');

    await page.getByRole('button', {name: 'Protected increment', exact: true}).click();
    await page.getByText('Protected increment committed.', {exact: true}).waitFor({state: 'visible'});
    const counter = await page.getByText(/This view's last confirmed increment: \d+/).textContent();
    assert.ok(Number(counter.match(/(\d+)$/)[1]) > 0);

    const fallback = await context.request.post(origin + '/reference-submit-unavailable', {
      headers: {'Origin': origin, 'Content-Type': 'application/x-www-form-urlencoded'},
      data: 'username=synthetic&password=unused-test-value', timeout: 10000,
    });
    assert.equal(fallback.status(), 404, `${engine}: credential form fallback must have no HTTP authentication handler`);
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'baseline_session').value, session.value);

    const protectedSocket = nextSocket();
    const protectedResponse = await page.goto(origin + '/protected');
    assert.equal(protectedResponse.status(), 200);
    await protectedSocket; await ready();
    await page.getByRole('heading', {name: 'Protected page', exact: true}).waitFor({state: 'visible'});
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});

    await page.getByRole('link', {name: 'Sign out', exact: true}).click();
    await page.waitForURL(origin + '/sign-out');
    const signOutResponse = page.waitForResponse(response =>
      response.request().method() === 'POST' && new URL(response.url()).pathname === '/sign-out');
    const afterLogoutSocket = nextSocket();
    await page.getByRole('button', {name: 'Confirm sign out', exact: true}).click();
    assert.equal((await signOutResponse).status(), 303);
    await page.waitForURL(origin + '/');
    await afterLogoutSocket; await ready();
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).waitFor({state: 'visible'});
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'baseline_session'), undefined);
    assert.equal(await page.getByText(`Authenticated account: ${username}`, {exact: true}).count(), 0);

    // Restore only the old credential, retaining this browser's fenced binding.
    // The real HTTP authority must reject it; no page script can grant authority.
    await context.addCookies([session]);
    const stale = await page.goto(origin + '/protected');
    assert.equal(stale.status(), 403, `${engine}: stale cookie passed protected rendering`);
    await page.getByRole('heading', {name: 'Authentication required', exact: true}).waitFor({state: 'visible'});
    assert.equal(await page.getByText(`Authenticated account: ${username}`, {exact: true}).count(), 0);
    assert.deepEqual(errors, [], `${engine}: browser runtime errors`);
    console.log(JSON.stringify({provider, engine, flow: factor ? 'challenged' : 'password-only',
      realCookieDelivery: true, sameViewReconnect: true, protectedAction: true,
      protectedHttp: true, logout: true, staleCookieDenied: true, foreignOriginDenied: true}));
  } finally { await context.close(); }
}

async function lostCompletion(browser, engine, username, factor) {
  const context = await browser.newContext();
  let timer;
  try {
    const page = await context.newPage();
    page.setDefaultTimeout(30000);
    const errors = [], delivered = [];
    let connections = 0, proofSubmissions = 0, droppedOnce = false;
    let resolveInitial, resolveDropped, resolveReconnect;
    const initialReady = new Promise(resolve => { resolveInitial = resolve; });
    const dropped = new Promise(resolve => { resolveDropped = resolve; });
    const reconnected = new Promise(resolve => { resolveReconnect = resolve; });
    page.on('pageerror', error => errors.push(error.message));
    // A transparent route connects to the real server. Only the first committed
    // completion command is discarded; no authentication response is fabricated.
    await page.routeWebSocket('**', socket => {
      const ordinal = ++connections;
      const server = socket.connectToServer();
      let injectedLoss = false, clientClosing = false, serverClosed = false;
      let upstreamCloseRequested = false, pageCloseForwarded = false, serverCloseOptions;
      function closeUpstream(code, reason) {
        if (upstreamCloseRequested) return;
        upstreamCloseRequested = true;
        // Playwright implements this through native browser WebSocket.close,
        // which accepts only 1000 or a private 3000..4999 application code.
        const allowedCode = code === 1000 || (code >= 3000 && code <= 4999) ? code : undefined;
        server.close({code: allowedCode, reason})
          .catch(() => errors.push('Fault-injector upstream cleanup failed'));
      }
      function forwardServerClose() {
        if (pageCloseForwarded) return;
        pageCloseForwarded = true;
        socket.close(serverCloseOptions)
          .catch(() => errors.push('Fault-injector page cleanup failed'));
      }
      socket.onClose((code, reason) => {
        if (clientClosing) return;
        clientClosing = true;
        closeUpstream(code, reason);
        if (serverClosed) forwardServerClose();
      });
      server.onClose((code, reason) => {
        serverClosed = true;
        serverCloseOptions = {code, reason};
        if (injectedLoss || clientClosing) forwardServerClose();
        // Pinned Playwright converts Blob messages asynchronously but dispatches
        // native Close immediately. Hold other server closes so a late real
        // Reload frame reaches the still-open mock; the browser's own reload
        // disposes it. No reload is fabricated: missing frames fail the deadline.
      });
      socket.onMessage(message => {
        const text = JSON.stringify(decodedFrame(message));
        if (text.includes('password') || text.includes('123456')) ++proofSubmissions;
        server.send(message);
      });
      server.onMessage(message => {
        const frame = decodedFrame(message);
        if (frame?.[0] === 21) {
          if (ordinal === 1) resolveInitial();
          else resolveReconnect();
        }
        if (frame?.[0] === 18) {
          if (!droppedOnce) {
            droppedOnce = true;
            injectedLoss = true;
            resolveDropped(frame[1]);
            closeUpstream(4001);
            return;
          }
          delivered.push(frame[1]);
        }
        socket.send(message);
      });
    });
    async function bounded(value) {
      try { return await Promise.race([value, new Promise((_, reject) => {
        timer = setTimeout(() => reject(new Error(`${engine}: completion-loss checkpoint timed out`)), 30000);
      })]); }
      finally { clearTimeout(timer); }
    }
    await page.goto(origin + '/'); await bounded(initialReady);
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    const passwordForm = page.locator('form').filter({has: page.locator('input[name="password"]')});
    await passwordForm.locator('input[name="username"]').waitFor({state: 'visible'});
    const ceremony = await passwordForm.locator('input[name="ceremony"]').inputValue();
    await passwordForm.locator('input[name="username"]').fill(username);
    await passwordForm.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    if (factor) {
      await page.locator('input[name="factor"]').fill(factor);
      await page.getByRole('button', {name: 'Verify factor', exact: true}).click();
    }
    assert.equal(await bounded(dropped), ceremony);
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'baseline_session'), undefined);
    await bounded(reconnected);
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    const recovery = page.locator('form').filter({has: page.getByRole('button', {name: 'Recover this attempt', exact: true})});
    assert.equal(await recovery.locator('input[name="ceremony"]').inputValue(), ceremony);
    assert.equal(proofSubmissions, factor ? 2 : 1);
    await recovery.getByRole('button', {name: 'Recover this attempt', exact: true}).click();
    try {
      await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    } catch (error) {
      console.error(JSON.stringify({fixture: 'completion-loss', engine, connections, proofSubmissions,
        completionCommands: delivered.length, ready: await page.evaluate(() => window.Spoonbill?.ready === true),
        sessionPresent: (await context.cookies()).some(cookie => cookie.name === 'baseline_session'),
        message: await page.locator('body > p').first().textContent()}));
      throw error;
    }
    assert.deepEqual(delivered, [ceremony], 'Recovery must deliver the original committed attempt');
    assert.equal(proofSubmissions, factor ? 2 : 1, 'Recovery resubmitted password/factor input');
    assert.ok((await context.cookies()).find(cookie => cookie.name === 'baseline_session')?.httpOnly);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({provider, engine, flow: factor ? 'challenged' : 'password-only',
      acknowledgedCompletionCommandLost: true, sameProcessRecovery: true,
      originalAttemptRecovered: true, proofNotRepeated: true}));
  } finally { clearTimeout(timer); await context.close(); }
}

(async () => {
  for (const [engine, type] of Object.entries({chromium, webkit})) {
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR, 'Use the flake-provided Linux WebKit EGL vendor');
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    let browser, timer;
    const deadline = new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error(`${engine}: 120-second reference-flow deadline exceeded`)), 120000);
    });
    try {
      await Promise.race([deadline, (async () => {
        browser = await type.launch({headless: true, env, timeout: 15000});
        await exercise(browser, engine, 'alice');
        await exercise(browser, engine, 'bob', '123456');
        if (provider === 'memory') {
          await lostCompletion(browser, engine, 'alice');
          await lostCompletion(browser, engine, 'bob', '123456');
        }
      })()]);
    } finally {
      clearTimeout(timer);
      if (browser) await browser.close();
    }
  }
  console.log(`PASS: real ${provider}-reference password/factor authentication in Chromium and WebKit`);
})().catch(error => { console.error(error); process.exitCode = 1; });
