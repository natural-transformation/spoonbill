// Public v4 example correctness against the actual server and browser engines.
// No client authentication implementation, fabricated frames, or performance sampling.
const assert = require('node:assert/strict');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_AUTH_ORIGIN, 'Start the public example through scripts/test-browser-auth.sh');
assert.equal(process.env.SPOONBILL_AUTH_DELIVERY_LIMIT, '1', 'Run the final-delivery acceptance cases with limit 1');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_AUTH_ORIGIN).origin;
const provider = process.env.SPOONBILL_AUTH_PROVIDER ?? 'memory';
assert.match(origin, /^http:\/\/(localhost|127\.0\.0\.1):\d+$/);
assert.ok(['memory', 'jdbc', 'io'].includes(provider), 'Unknown example provider');
const engines = (process.env.SPOONBILL_AUTH_ENGINES ?? 'chromium,webkit').split(',').map(value => value.trim());
assert.ok(engines.length > 0 && new Set(engines).size === engines.length &&
  engines.every(value => ['chromium', 'webkit'].includes(value)), 'Select chromium, webkit, or both');
const recoveryScenarios = ['lost-command', 'second-tab', 'request-loss', 'unaccepted-cookie-loss', 'accepted-cookie-loss'];
const availableFlows = ['password', 'factor', ...recoveryScenarios];
const flows = (process.env.SPOONBILL_AUTH_FLOWS ?? availableFlows.join(',')).split(',').map(value => value.trim());
assert.ok(flows.length > 0 && new Set(flows).size === flows.length &&
  flows.every(value => availableFlows.includes(value)), 'Select distinct nonempty browser-auth flows: ' + availableFlows.join(', '));

function frameCode(event) {
  try { return JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload)[0]; }
  catch (_) { return undefined; }
}

function pageErrorInfo(error, phase) {
  const message = String(error.message ?? '');
  const safeMessage = message
    .replace(/SYN-[A-Za-z0-9_-]+/g, '[synthetic code]')
    .replace(/[A-Za-z0-9_-]{43}/g, '[credential]')
    .replace(/\b(?:password|123456|654321)\b/gi, '[fixture proof]')
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '[lookup]')
    .replace(/(?:https?|wss?):\/\/[^\s'"<>]+/g, '[endpoint]')
    .replace(/\/?localhost:\d+\/auth\/recover/g, '[recovery endpoint]').slice(0, 256);
  // The empty-body discovery endpoint has no lookup or proof in its URL. Keep
  // only its browser-generated network diagnostic, with every URL removed.
  const recoveryError = message.includes('/auth/recover') && message.length <= 512 &&
    !/[A-Za-z0-9_-]{43}/.test(message) && !message.includes('SYN-')
    ? message.replace(/https?:\/\/[^\s'"<>]+/g, '[recovery endpoint]') : undefined;
  const category = /abort|cancel/i.test(message) ? 'aborted-operation'
    : /socket/i.test(message) ? 'socket-operation'
    : /sensitive/i.test(message) ? 'sensitive-operation'
    : /view|revision/i.test(message) ? 'view-operation'
    : /json/i.test(message) ? 'json-operation' : 'other';
  return {phase, name: /^[A-Za-z]+$/.test(error.name) ? error.name : 'Error', category,
    recoveryError, safeMessage,
    messageHash: require('node:crypto').createHash('sha256').update(message).digest('hex').slice(0, 16),
    locations: [...String(error.stack ?? '').matchAll(/([A-Za-z0-9_.-]+\.js:\d+:\d+)/g)].slice(0, 6).map(match => match[1])};
}

async function exercise(browser, engine, username, factor) {
  const context = await browser.newContext();
  let phase = 'create-page';
  let page;
  const errors = [], sockets = [], responses = [];
    const publicPaths = new Set(['/', '/protected', '/auth/complete', '/auth/recover', '/auth/activate', '/auth/logout', '/no-http-proof-submission']);
  try {
    page = await context.newPage();
    page.setDefaultTimeout(25000);
    page.setDefaultNavigationTimeout(25000);
    page.on('pageerror', error => errors.push(pageErrorInfo(error, phase)));
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
      // A navigation handshake may close before readiness while the existing
      // client reconnects. Require the latest live socket's authorized frame
      // and browser readiness within one deadline, never the old ready flag.
      const deadline = Date.now() + 25000;
      const bound = checkpoint('Fresh physical socket did not become ready', 25000);
      const remaining = () => {
        const milliseconds = deadline - Date.now();
        assert.ok(milliseconds > 0, 'Fresh physical socket did not become ready');
        return milliseconds;
      };
      try {
        while (true) {
          const current = sockets.at(-1);
          if (!current.closed) {
            const error = await Promise.race([current.ready, bound.promise]);
            if (error && !current.closed) throw error;
            if (!error && !current.closed) {
              await page.waitForFunction(() => window.Spoonbill?.ready === true, undefined, {timeout: remaining()});
              if (sockets.at(-1) === current && !current.closed && current.readySeen) return;
            }
          }
          if (sockets.at(-1) === current) await page.waitForEvent('websocket', {timeout: remaining()});
        }
      } finally { bound.cancel(); }
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

    const predecessorView = new URL(sockets.at(-1).url).pathname;
    const replacementSocket = nextSocket();
    const replacementResponse = page.waitForResponse(response => response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/auth/complete');
    replacementResponse.catch(() => {});
    phase = 'replacement-submit';
    await page.locator('select[name="securityChange"]').selectOption('regenerate-recovery');
    await page.locator('input[name="replacementPassword"]').fill('password');
    await page.getByRole('button', {name: 'Apply security change', exact: true}).click();
    assert.equal((await replacementResponse).status(), 204);
    await replacementSocket;
    phase = 'replacement-activated'; await ready();
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    assert.equal(new URL(sockets.at(-1).url).pathname, predecessorView,
      'Replacement must activate through the same logical view');
    const successorSession = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(successorSession?.httpOnly && successorSession.value !== session.value,
      'Replacement must deliver a new HttpOnly credential');
    await denyPredecessor(browser, binding, session);
    phase = 'successor-disclosure';
    await discloseOnce(page);

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
    await context.addCookies([successorSession]);
    assert.equal((await page.goto(origin + '/protected')).status(), 403, 'A logged-out cookie must stay fenced');
    await page.getByRole('heading', {name: 'Authentication required', exact: true}).waitFor({state: 'visible'});
    assert.deepEqual(errors, [], `${engine}: browser runtime errors`);
    console.log(JSON.stringify({provider, engine, flow: factor ? 'factor' : 'password',
      realCookieDelivery: true, sameViewActivation: true, protectedAction: true,
      protectedHttpAndReload: true, replacementSameView: true, predecessorDenied: true,
      successorDisclosureOnce: true, logout: true, staleCookieDenied: true, foreignOriginDenied: true}));
  } catch (error) {
    const failure = /Socket closed/.test(error.message) ? 'socket-closed-before-ready'
      : error.name === 'TimeoutError' ? 'timeout' : error.name === 'AssertionError' ? 'assertion' : 'runtime-error';
    let retryObservation;
    if (failure === 'socket-closed-before-ready') {
      const closedCount = sockets.length;
      try {
        await page.waitForEvent('websocket', {timeout: 10000});
        const next = sockets.at(-1);
        const nextError = await next.ready;
        if (!nextError) await page.waitForFunction(() => window.Spoonbill?.ready === true);
        retryObservation = {subsequentPhysicalSocket: sockets.length > closedCount,
          readyFrame: next.readySeen, browserReady: !nextError};
      } catch (_) { retryObservation = {subsequentPhysicalSocket: sockets.length > closedCount, browserReady: false}; }
    }
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
      responses, pageErrors: errors, retryObservation, socketCount: sockets.length,
      closeCount: sockets.filter(socket => socket.closed).length,
      sockets: sockets.slice(-16).map((socket, index) => ({ordinal: Math.max(0, sockets.length - 16) + index + 1,
        openedAt: socket.phase, closedAt: socket.closedAt, closed: socket.closed,
        readySeen: socket.readySeen, codes: socket.codes, unknownFrames: socket.unknownFrames}))}));
    throw new Error(`${provider}/${engine}/${username}: ${phase}: ${failure}`);
  } finally { await context.close(); }
}

async function denyPredecessor(browser, binding, session) {
  const probe = await browser.newContext();
  try {
    await probe.addCookies([binding, session]);
    assert.equal((await probe.request.get(origin + '/protected')).status(), 403,
      'The exact predecessor must stay denied after replacement');
  } finally { await probe.close(); }
}

async function discloseOnce(page) {
  // The sensitive region has a closed shadow root. Observe its acknowledged
  // lifecycle only; never read, print, screenshot, or retain recovery plaintext.
  await page.getByRole('button', {name: 'Reveal recovery codes once', exact: true}).click();
  await page.getByText('Recovery codes disclosed once. Store them safely.', {exact: true}).waitFor({state: 'visible'});
  await page.getByRole('button', {name: 'Reveal recovery codes once', exact: true}).click();
  await page.getByText('No unused recovery codes remain. Regenerate to obtain new codes.', {exact: true}).waitFor({state: 'visible'});
}

function checkpoint(label, timeout = 25000) {
  let resolve, reject;
  const promise = new Promise((yes, no) => { resolve = yes; reject = no; });
  const timer = setTimeout(() => reject(new Error(label)), timeout);
  promise.finally(() => clearTimeout(timer)).catch(() => {});
  promise.catch(() => {});
  return {promise, resolve, reject, cancel: () => clearTimeout(timer)};
}

async function replacementRecovery(browser, engine, scenario, username) {
  const context = await browser.newContext();
  const setupStartedAt = Date.now();
  let operationStartedAt, fault, recoverySuccess, successorReady;
  const routeErrors = [], errors = [];
  let phase = 'login', armed = false, replacementStarted = false, completionRequests = 0, fetchedResponses = 0;
  let replacementControls = 0, completionHandle;
  let routingActive = false;
  let binding, predecessor, initialReplacementView;
  const paths = [], recoveries = [], recoveryCookies = [], activations = [];
  try {
    const page = await context.newPage();
    page.setDefaultTimeout(25000);
    page.setDefaultNavigationTimeout(25000);
    page.on('pageerror', error => errors.push(pageErrorInfo(error, phase)));
    function observe(target, routed = false) {
      target.on('response', response => {
        if (new URL(response.url()).pathname === '/auth/recover') {
          recoveries.push(response.status());
          if (replacementStarted && response.status() === 204) {
            response.allHeaders().then(headers => {
              recoveryCookies.push(Object.keys(headers).some(name => name.toLowerCase() === 'set-cookie'));
              recoverySuccess.resolve();
            }).catch(() => recoverySuccess.reject(new Error('Recovery response metadata unavailable')));
          }
        }
      });
      target.on('websocket', socket => {
        if (routed && routingActive) return;
        const path = new URL(socket.url()).pathname;
        if (path === '/auth/activate') activations.push(path);
        else {
          paths.push(path);
          const replacementConnection = replacementStarted;
          socket.on('framereceived', event => {
            const code = frameCode(event);
            if (replacementStarted && code === 18) replacementControls++;
            if (replacementConnection && code === 21) successorReady.resolve();
          });
        }
      });
    }
    const routed = scenario === 'lost-command' || scenario === 'second-tab';
    observe(page, routed);
    async function installFaultRoute(routedReady) {
      // Transparent transport fault injection forwards real traffic and drops
      // only the next real framework completion after the replacement submit.
      await page.routeWebSocket('**', socket => {
        paths.push(new URL(socket.url()).pathname);
        const replacementConnection = replacementStarted;
        const server = socket.connectToServer();
        let clientClosing = false, serverClosed = false, upstreamClosed = false;
        let downstreamClosed = false, lost = false, serverCloseOptions;
        function closeUpstream(code, reason) {
          if (upstreamClosed) return;
          upstreamClosed = true;
          const allowedCode = code === 1000 || (code >= 3000 && code <= 4999) ? code : undefined;
          server.close({code: allowedCode, reason}).catch(() => routeErrors.push('upstream-close'));
        }
        function closeDownstream() {
          if (downstreamClosed) return;
          downstreamClosed = true;
          socket.close(serverCloseOptions).catch(() => routeErrors.push('downstream-close'));
        }
        socket.onClose((code, reason) => {
          clientClosing = true;
          closeUpstream(code, reason);
          if (serverClosed) closeDownstream();
        });
        server.onClose((code, reason) => {
          serverClosed = true;
          serverCloseOptions = {code, reason};
          if (clientClosing || (lost && scenario === 'lost-command')) closeDownstream();
          // Keep other closes pending until the real final frame or browser
          // close: pinned WebKit may deliver Blob frames after the close event.
        });
        socket.onMessage(message => { if (!serverClosed) server.send(message); });
        server.onMessage(message => {
          const code = frameCode({payload: message});
          if (!replacementConnection && code === 21) routedReady.resolve();
          if (replacementStarted && code === 18) replacementControls++;
          if (replacementConnection && code === 21) successorReady.resolve();
          if (armed && code === 18) {
            armed = false;
            lost = true;
            fault.resolve();
            if (scenario === 'lost-command') {
              if (serverClosed) closeDownstream();
              else closeUpstream(4001, 'Synthetic completion loss');
            }
            return;
          }
          socket.send(message);
        });
      });
    }
    await page.route(origin + '/auth/complete', async route => {
      if (!armed || ['lost-command', 'second-tab'].includes(scenario)) return route.continue();
      completionRequests++;
      try {
        const handle = route.request().postData();
        assert.match(handle, /^[0-9a-f-]{36}$/);
        if (completionHandle === undefined) completionHandle = handle;
        else assert.ok(handle === completionHandle, 'Retries must use the same committed completion');
        if (scenario === 'request-loss') {
          // The server never sees these bounded attempts; bootstrap recovery
          // must still deliver the original committed outcome exactly once.
          await route.abort('failed');
          fault.resolve();
          return;
        }
        if (fetchedResponses > 0) return route.abort('failed');
        fetchedResponses++;
        const response = await route.fetch();
        assert.equal(response.status(), 204);
        const header = response.headers()['set-cookie'];
        assert.ok(typeof header === 'string' && /^spoonbill_session=[A-Za-z0-9_-]{43};/.test(header),
          'Expected one server-issued session cookie');
        assert.match(header, /;\s*HttpOnly(?:;|$)/i);
        assert.match(header, /;\s*SameSite=Lax(?:;|$)/i);
        if (scenario === 'accepted-cookie-loss') {
          // route.fetch may update the shared cookie jar. Install only the
          // exact server-issued HttpOnly value if the browser did not do so.
          const value = header.slice('spoonbill_session='.length).split(';', 1)[0];
          const installed = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
          if (installed?.value !== value)
            await context.addCookies([{...predecessor, value, httpOnly: true, sameSite: 'Lax'}]);
        } else {
          // Model loss before cookie acceptance even if route.fetch updated
          // its API request cookie jar as a side effect.
          await context.addCookies([predecessor]);
        }
        await route.abort('failed');
        fault.resolve();
      } catch (error) {
        fault.reject(new Error('Replacement HTTP fault injection failed'));
        try { await route.abort('failed'); } catch (_) {}
      }
    });
    await page.goto(origin + '/');
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').fill(username);
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    await page.goto(origin + '/protected');
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
    if (routed) {
      // Login is not the fault target. Keep its cookie handoff and close on
      // the native transport. Pinned WebKit installs routeWebSocket through an
      // init script, so a fresh protected document must establish the proxy.
      // Forward every binary frame unchanged; arm only after current readiness.
      phase = 'fault-transport-setup';
      const previousCookie = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
      const previousSockets = paths.length;
      const routedReady = checkpoint('Fault-injection transport did not become ready');
      try {
        await installFaultRoute(routedReady);
        routingActive = true;
        assert.equal((await page.goto(origin + '/protected')).status(), 200);
        await routedReady.promise;
        await page.waitForFunction(() => window.Spoonbill?.ready === true);
        assert.ok(paths.length > previousSockets, 'A real routed transport must be ready before arming');
        assert.equal((await context.cookies()).find(cookie => cookie.name === 'spoonbill_session')?.value,
          previousCookie?.value, 'Fault setup must preserve the authenticated credential');
        await page.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
      } finally { routedReady.cancel(); }
    }
    const cookies = await context.cookies();
    binding = cookies.find(cookie => cookie.name === 'spoonbill_binding');
    predecessor = cookies.find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(binding?.httpOnly && predecessor?.httpOnly);
    initialReplacementView = paths.at(-1);
    const socketCount = paths.length;
    phase = scenario;
    await page.locator('select[name="securityChange"]').selectOption('regenerate-recovery');
    await page.locator('input[name="replacementPassword"]').fill('password');
    // Each replacement operation gets the existing bounded recovery window;
    // native login, navigation and fault-transport setup use their own bounds.
    operationStartedAt = Date.now();
    fault = checkpoint('Replacement fault checkpoint expired');
    recoverySuccess = checkpoint('Replacement recovery response checkpoint expired');
    successorReady = checkpoint('Replacement successor readiness checkpoint expired');
    replacementStarted = true;
    armed = true;
    await page.getByRole('button', {name: 'Apply security change', exact: true}).click();
    await fault.promise;
    let recovered = page;
    if (scenario === 'second-tab') {
      recovered = await context.newPage();
      recovered.setDefaultTimeout(25000);
      observe(recovered);
      assert.equal((await recovered.goto(origin + '/protected')).status(), 200);
    }
    if (scenario === 'unaccepted-cookie-loss') {
      phase = 'committed-unavailable';
      await page.getByRole('heading', {name: 'Authentication required', exact: true}).waitFor({state: 'visible'});
      assert.equal((await context.request.post(origin + '/auth/recover', {
        headers: {'Origin': origin, 'Sec-Fetch-Site': 'same-origin', 'Content-Type': 'text/plain'}, data: '',
      })).status(), 403, 'Exhaustion must preserve the committed unavailable outcome');
      await denyPredecessor(browser, binding, predecessor);
      assert.equal(fetchedResponses, 1);
      assert.ok(completionRequests <= 3, 'Delivery retries must stay bounded');
      assert.equal((await context.request.post(origin + '/auth/logout', {
        headers: {'Origin': origin, 'Sec-Fetch-Site': 'same-origin'}, maxRedirects: 0,
      })).status(), 303);
      assert.equal((await page.goto(origin + '/')).status(), 200);
      await page.getByRole('button', {name: 'Begin sign-in', exact: true}).waitFor({state: 'visible'});
    } else {
      phase = 'successor-recovered';
      // Wait for the new physical activation, not the predecessor's still
      // visible authenticated text or its previous ready flag.
      await recoverySuccess.promise;
      await successorReady.promise;
      await recovered.getByText(`Authenticated account: ${username}`, {exact: true}).waitFor({state: 'visible'});
      await recovered.waitForFunction(() => window.Spoonbill?.ready === true);
      const successor = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
      assert.ok(successor?.httpOnly && successor.value !== predecessor.value);
      if (scenario === 'lost-command') {
        assert.ok(paths.length > socketCount);
        assert.equal(paths.at(-1), initialReplacementView, 'Lost command must recover the same logical view');
      } else {
        assert.ok(activations.length >= 1, 'Fresh-page recovery must use the activation-only bootstrap');
      }
      if (scenario === 'accepted-cookie-loss') {
        assert.equal(fetchedResponses, 1, 'The final delivery happened once');
        assert.ok(recoveries.includes(204), 'The held cookie must recover without another delivery');
        assert.ok(recoveryCookies.every(value => value === false), 'Held-cookie recovery must not redeliver');
      } else {
        assert.deepEqual(recoveryCookies, [true], 'Recovery must deliver the original successor once');
      }
      await denyPredecessor(browser, binding, predecessor);
      await discloseOnce(recovered);
    }
    assert.ok(completionRequests <= 3, 'Completion attempts must stay within the bridge retry bound');
    if (scenario === 'request-loss') assert.equal(completionRequests, 3, 'Exhaust pre-delivery retries before bootstrap');
    assert.equal(replacementControls, 1, 'Recovery must not submit another replacement completion');
    assert.deepEqual(errors, []);
    assert.deepEqual(routeErrors, []);
    console.log(JSON.stringify({provider, engine, scenario, predecessorDenied: true,
      successorDisclosureOnce: scenario !== 'unaccepted-cookie-loss', boundedDelivery: true}));
  } catch (error) {
    const failure = error.name === 'TimeoutError' ? 'timeout' : error.name === 'AssertionError' ? 'assertion' : 'runtime-error';
    // Error messages from browser APIs can contain field values. Retain only
    // explicitly non-secret phase/counter evidence.
    console.error(JSON.stringify({kind: 'replacement-recovery-failure', provider, engine, scenario, phase,
      failure, completionRequests, fetchedResponses, recoveries, activationCount: activations.length,
      setupElapsedMs: (operationStartedAt ?? Date.now()) - setupStartedAt,
      operationElapsedMs: operationStartedAt === undefined ? undefined : Date.now() - operationStartedAt,
      pageErrors: errors, routeErrors, diagnostic: pageErrorInfo(error, phase)}));
    throw new Error(`${provider}/${engine}/${scenario}: ${phase}: ${failure}`);
  } finally { fault?.cancel(); recoverySuccess?.cancel(); successorReady?.cancel(); await context.close(); }
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
      timer = setTimeout(() => reject(new Error(`${engine}: bounded browser-auth smoke deadline exceeded`)), 180000);
    });
    try {
      await Promise.race([deadline, (async () => {
        browser = await type.launch({headless: true, env, timeout: 15000});
        if (flows.includes('password')) await exercise(browser, engine, 'alice');
        if (flows.includes('factor')) await exercise(browser, engine, 'bob', '123456');
        for (const [index, scenario] of recoveryScenarios.entries())
          if (flows.includes(scenario))
            await replacementRecovery(browser, engine, scenario, `account-${String(3 + index * 2).padStart(4, '0')}`);
      })()]);
    } finally { clearTimeout(timer); if (browser) await browser.close(); }
  }
  console.log(`PASS: public ${provider} example in ${engines.join(', ')}; flows: ${flows.join(', ')}`);
})().catch(error => { console.error(error.message); process.exitCode = 1; });
