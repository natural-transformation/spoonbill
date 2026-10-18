// Actual Scala service + assembled client; all actions are native DOM submits.
const assert = require('node:assert/strict');
assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_UPDATE_TEST_ORIGIN, 'Start through test-sensitive-acknowledgement.sh');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_UPDATE_TEST_ORIGIN).origin;
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);
const modes = ['no-update', 'identity', 'modify', 'revocation'];

async function control(context, command) {
  const response = await context.request.post(`${origin}/fixture/${command}`, {timeout: 10000});
  assert.equal(response.status(), 200, `Control ${command} must finish within its bound`);
  const text = await response.text();
  return text === 'ready' ? null : JSON.parse(text);
}

async function exercise(browser, engine, mode) {
  const context = await browser.newContext();
  let phase = 'start';
  const sockets = [], errors = [];
  try {
    await control(context, `start/${mode}`);
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    page.setDefaultNavigationTimeout(10000);
    page.on('pageerror', () => errors.push('pageerror'));
    page.on('websocket', socket => {
      const observation = {connection: null, revision: null, codes: [], closed: false};
      sockets.push(observation);
      socket.on('framereceived', event => {
        const frame = JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload);
        if (observation.codes.length < 64) observation.codes.push(frame[0]);
        if (frame[0] === 19) { observation.connection = frame[2]; observation.revision = frame[3]; }
        if (frame[0] === 20) observation.revision = frame[3];
      });
      socket.on('close', () => { observation.closed = true; });
    });
    phase = 'ready';
    assert.equal((await page.goto(origin)).status(), 200);
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    assert.equal(sockets.length, 1);
    const socket = sockets[0];
    assert.match(socket.connection, /^[0-9a-f-]{36}$/);
    assert.match(socket.revision, /^\d+$/);
    const delivered = {connection: socket.connection, revision: socket.revision};
    await control(context, 'arm');
    phase = 'reveal';
    await page.getByRole('button', {name: 'Reveal setup', exact: true}).click();
    const acknowledged = await control(context, 'await-reveal');
    assert.equal(acknowledged.acknowledgements, 1, 'Normal browser acknowledgement must reach the callback');
    assert.equal(acknowledged.completed, 1, 'Wait for event completion, not only callback entry');
    assert.ok(socket.codes.includes(22), 'The real disclosure must reach the browser');
    assert.equal(await page.locator('#value').textContent(), '0');
    if (mode === 'identity' || mode === 'modify') {
      phase = 'publication';
      const published = await control(context, 'await-patch');
      assert.ok(published.patches >= 1);
      assert.equal(published.modifications, 1);
      assert.ok(BigInt(published.revision) > BigInt(delivered.revision));
    } else {
      assert.equal(acknowledged.modifications, 0);
      assert.equal(acknowledged.patches, 0);
    }
    if (mode === 'revocation') await control(context, 'revoke');
    assert.equal(socket.revision, delivered.revision, 'Held patches must not advance the browser view');
    phase = 'confirm';
    await page.getByRole('button', {name: 'Confirm setup', exact: true}).click();
    const result = await control(context, mode === 'no-update' ? 'await-confirm' : 'await-close');
    assert.equal(result.confirmations, mode === 'no-update' ? 1 : 0);
    if (mode === 'no-update') {
      assert.equal(result.completed, 2);
      assert.equal(result.closed, false);
      assert.equal(result.patches, 0);
      assert.equal(socket.closed, false);
      assert.equal(sockets.length, 1, 'No reconnect may stand in for the next action');
    }
    assert.equal(result.submissionCount, 2, 'The service must receive exactly the two actual UI submits');
    assert.deepEqual(result.submissions, [delivered, delivered], 'Both actual UI submits use the delivered view');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({engine, mode, acknowledged: true, admitted: result.confirmations === 1,
      publicationHeld: result.patches > 0, closed: result.closed}));
  } catch (error) {
    // Only bounded metadata; never log WebSocket payloads, documents or cookies.
    console.error(JSON.stringify({engine, mode, phase, failure: error.name,
      sockets: sockets.map(({codes, closed}) => ({codes, closed}))}));
    throw error;
  } finally {
    try { await control(context, 'release'); }
    finally { await context.close(); }
  }
}

(async () => {
  for (const [engine, type] of Object.entries({chromium, webkit})) {
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    let browser;
    try {
      browser = await type.launch({headless: true, env, timeout: 15000});
      for (const mode of modes) await exercise(browser, engine, mode);
    } finally { if (browser) await browser.close(); }
  }
  console.log('PASS: real-service sensitive acknowledgement in Chromium and WebKit');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
