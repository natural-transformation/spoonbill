// Correctness only: real separate web JVMs share PostgreSQL. The proxy only
// forwards bytes and injects transport loss; it does not implement auth logic.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const net = require('node:net');
const {spawn, execFile} = require('node:child_process');
const {promisify} = require('node:util');
const {setTimeout: pause} = require('node:timers/promises');
const {randomUUID} = require('node:crypto');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
for (const name of ['SPOONBILL_CLUSTER_CLASSPATH_FILE', 'SPOONBILL_AUTH_JAVA', 'SPOONBILL_AUTH_PSQL', 'SPOONBILL_JDBC_TEST_URL'])
  assert.ok(process.env[name], 'Use scripts/test-clustered-browser-auth.sh');
const classpath = fs.readFileSync(process.env.SPOONBILL_CLUSTER_CLASSPATH_FILE, 'utf8').trim();
assert.ok(classpath.startsWith('/') && !classpath.includes('\n'), 'Expected one absolute compiled classpath');
const database = new URL(process.env.SPOONBILL_JDBC_TEST_URL.replace(/^jdbc:/, ''));
assert.ok(['localhost', '127.0.0.1', '::1', '[::1]'].includes(database.hostname), 'Disposable loopback database required');
const engines = (process.env.SPOONBILL_AUTH_ENGINES ?? 'chromium,webkit').split(',');
assert.ok(engines.length && new Set(engines).size === engines.length &&
  engines.every(engine => ['chromium', 'webkit'].includes(engine)), 'Select chromium, webkit, or both');
const browsers = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const run = promisify(execFile);
const owned = new Set();

async function until(check, message, timeout = 25000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if (await check()) return;
    await pause(100);
  }
  throw new Error(message);
}
async function freePort() {
  const server = net.createServer();
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}
async function stop(child, signal = 'SIGTERM') {
  if (!child || child.exitCode !== null || child.signalCode !== null) return;
  child.kill('SIGCONT');
  child.kill(signal);
  try { await until(() => child.exitCode !== null || child.signalCode !== null, 'JVM shutdown timed out', 18000); }
  catch (error) {
    child.kill('SIGKILL');
    await until(() => child.exitCode !== null || child.signalCode !== null, 'JVM kill timed out', 3000);
    if (signal === 'SIGTERM') throw error;
  } finally { owned.delete(child); }
}
async function exercise(engine) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'spoonbill-cluster-browser-'));
  const schema = 'cluster_' + randomUUID().replaceAll('-', '');
  const ports = {a: await freePort(), b: await freePort()};
  let target = 'a', dropResponses = false, phase = 'start', browser, context, page, a, b;
  const connections = new Set(), errors = [], sockets = [], requests = [];
  const proxy = net.createServer(client => {
    const upstream = net.connect(ports[target], '127.0.0.1');
    const connection = {client, upstream};
    connections.add(connection);
    client.pipe(upstream);
    upstream.on('data', bytes => {
      if (!dropResponses && !client.destroyed && !client.write(bytes)) upstream.pause();
    });
    client.on('drain', () => upstream.resume());
    const close = () => { client.destroy(); upstream.destroy(); connections.delete(connection); };
    client.on('error', close); upstream.on('error', close);
    client.on('close', close); upstream.on('close', close);
  });
  await new Promise((resolve, reject) => proxy.once('error', reject).listen(0, '127.0.0.1', resolve));
  const origin = 'http://localhost:' + proxy.address().port;
  async function start(node, initialize = false, snapshotVersion = node === 'a' ? 1 : 2) {
    const log = fs.openSync(path.join(directory, node + '.log'), 'a');
    const child = spawn(process.env.SPOONBILL_AUTH_JAVA,
      ['-Xms128m', '-Xmx512m', '-cp', classpath, 'example.browserauth.Main', String(ports[node]),
        ...(initialize ? ['--initialize'] : [])], {
        env: {...process.env, SPOONBILL_AUTH_PROVIDER: 'jdbc', SPOONBILL_AUTH_CLUSTERED: 'true',
          SPOONBILL_AUTH_ORIGIN: origin, SPOONBILL_AUTH_SCHEMA: schema,
          SPOONBILL_AUTH_SNAPSHOT_VERSION: String(snapshotVersion), SPOONBILL_AUTH_TEST_FIXTURES: 'true',
          SPOONBILL_AUTH_MAX_ACTIVE_VIEWS: '2'},
        stdio: ['ignore', log, log],
      });
    fs.closeSync(log); owned.add(child);
    await until(async () => {
      assert.equal(child.exitCode, null, 'Web JVM exited during startup');
      try {
        const response = await fetch('http://127.0.0.1:' + ports[node] + '/health', {signal: AbortSignal.timeout(1000)});
        await response.arrayBuffer(); return response.status === 200;
      } catch (_) { return false; }
    }, 'Web JVM startup timed out', 45000);
    return child;
  }
  async function sql(statement) {
    const {stdout} = await run(process.env.SPOONBILL_AUTH_PSQL,
      ['-X', '-qAt', '--no-password', '--dbname', database.href,
        '-c', statement],
      {timeout: 5000, env: {...process.env, PGUSER: process.env.SPOONBILL_JDBC_TEST_USER ?? 'spoonbill_test'}});
    return stdout.trim();
  }
  async function count() {
    const result = await sql(`SELECT value FROM ${schema}.example_counter c JOIN ${schema}.example_account a ON a.id=c.subject WHERE a.name='alice'`);
    assert.match(result, /^\d+$/); return Number(result);
  }
  async function snapshotState() {
    return JSON.parse(await sql(`SELECT COALESCE(json_agg(json_build_object('view',view_id,'revision',revision,
      'format',schema_version,'digest',md5(payload)) ORDER BY view_id)::text,'[]') FROM ${schema}.spoonbill_view_snapshot`));
  }
  async function activeViews() {
    return Number(await sql(`SELECT count(*) FROM ${schema}.spoonbill_browser_view WHERE owner_id IS NOT NULL`));
  }
  async function proofEffects(ceremony) {
    assert.match(ceremony, /^[0-9a-f-]{36}$/);
    return JSON.parse(await sql(`SELECT json_build_object(
      'proofs',(SELECT count(*) FROM ${schema}.example_consumed_proof WHERE ceremony='${ceremony}'),
      'sessions',(SELECT count(*) FROM ${schema}.example_session s JOIN ${schema}.example_account a ON a.id=s.subject WHERE a.name='bob'),
      'audits',(SELECT count(*) FROM ${schema}.example_audit e JOIN ${schema}.example_account a ON a.id=e.subject WHERE a.name='bob' AND e.kind='login'))`));
  }
  function routeTo(node) {
    target = node; dropResponses = false;
    for (const {client, upstream} of connections) { client.destroy(); upstream.destroy(); }
  }
  try {
    a = await start('a', true); b = await start('b');
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    browser = await browsers[engine].launch({headless: true, env, timeout: 15000});
    context = await browser.newContext();
    page = await context.newPage();
    page.setDefaultTimeout(25000); page.setDefaultNavigationTimeout(25000);
    page.on('pageerror', error => errors.push(error.name));
    let socketCount = 0;
    page.on('response', response => {
      const path = new URL(response.url()).pathname;
      requests.push({phase, method: response.request().method(), status: response.status(),
        kind: path === '/auth/complete' ? 'complete' : path === '/auth/logout' ? 'logout' :
          path.includes('/form-data') ? 'form-data' : 'resource'});
      if (requests.length > 32) requests.shift();
    });
    page.on('websocket', socket => {
      socketCount++;
      const observation = {phase, url: socket.url(), ready: false, closed: false, frames: []};
      sockets.push(observation);
      function frame(direction, event) {
        try {
          const text = Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload;
          const code = JSON.parse(text)[0];
          if (Number.isInteger(code)) {
            observation.frames.push({direction, code});
            if (observation.frames.length > 32) observation.frames.shift();
          }
          if (direction === 'received' && code === 21) observation.ready = true;
        } catch (_) { /* Observe only the documented VIEW_READY marker. */ }
      }
      socket.on('framereceived', event => frame('received', event));
      socket.on('framesent', event => frame('sent', event));
      socket.on('close', () => { observation.closed = true; });
    });
    async function ready(after = 0) {
      await until(() => socketCount > after && sockets.at(-1).ready && !sockets.at(-1).closed,
        'Browser did not reach VIEW_READY on a new physical socket');
      await page.waitForFunction(() => window.Spoonbill?.ready === true);
    }
    async function recovered(draft, selected, before) {
      await ready(before);
      await page.getByText('Authenticated account: alice', {exact: true}).waitFor();
      await until(async () => await page.locator('textarea[name="draft"]').inputValue() === draft &&
        await page.locator('input[name="selectedItem"]').inputValue() === selected,
      'Acknowledged presentation was not restored');
    }
    async function save(draft, selected) {
      await page.locator('textarea[name="draft"]').fill(draft);
      await page.locator('input[name="selectedItem"]').fill(selected);
      await page.getByRole('button', {name: 'Save draft', exact: true}).click();
      await until(async () => await page.locator('#saved-draft').textContent() === 'Saved draft: ' + draft,
        'The server did not acknowledge the saved draft');
    }
    phase = 'http-a-first-websocket-b';
    let firstScript = true;
    const initialHtml = page.waitForResponse(response => response.url() === origin + '/' &&
      response.request().isNavigationRequest());
    initialHtml.catch(() => {});
    await page.route(origin + '/static/spoonbill-client.min.js', async route => {
      if (!firstScript) return route.continue();
      firstScript = false;
      const response = await route.fetch();
      await (await initialHtml).finished();
      routeTo('b');
      await route.fulfill({response});
    });
    assert.equal((await page.goto(origin + '/')).status(), 200); await ready();
    assert.equal(firstScript, false, 'The first socket must start after routing changed from A to B');
    await page.unroute(origin + '/static/spoonbill-client.min.js');
    phase = 'unknown-view-refusal';
    const knownViews = await sql(`SELECT count(*) FROM ${schema}.spoonbill_browser_view`);
    const probe = await context.newPage();
    try {
      await probe.goto(origin + '/health');
      const result = await probe.evaluate(url => new Promise(resolve => {
        let ready = false;
        const socket = new WebSocket(url, ['json']);
        const timer = setTimeout(() => { socket.close(); resolve({closed: false, ready}); }, 10000);
        socket.onmessage = event => { if (JSON.parse(event.data)[0] === 21) ready = true; };
        socket.onclose = () => { clearTimeout(timer); resolve({closed: true, ready}); };
      }), origin.replace(/^http/, 'ws') + '/bridge/web-socket/' + randomUUID() + '?__spoonbill_location=%2F');
      assert.deepEqual(result, {closed: true, ready: false}, 'An unregistered view must never become interactive');
      assert.equal(await sql(`SELECT count(*) FROM ${schema}.spoonbill_browser_view`), knownViews);
    } finally { await probe.close(); }
    phase = 'factor-challenge-handoff';
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').fill('bob');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await page.locator('input[name="factor"]').waitFor();
    const ceremony = await page.locator('form').filter({has: page.locator('input[name="factor"]')})
      .locator('input[name="ceremony"]').inputValue();
    let before = socketCount; routeTo('a'); await ready(before);
    await page.locator('input[name="factor"]').waitFor();
    assert.equal(await page.locator('form').filter({has: page.locator('input[name="factor"]')})
      .locator('input[name="ceremony"]').inputValue(), ceremony, 'Handoff must retain the original challenge');
    phase = 'factor-commit-websocket-response-loss';
    await page.locator('input[name="factor"]').fill('123456');
    dropResponses = true;
    await page.getByRole('button', {name: 'Verify factor', exact: true}).click();
    await until(async () => (await proofEffects(ceremony)).proofs === 1, 'The original factor preparation did not commit');
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'spoonbill_session'), undefined,
      'Suppressing the committed WebSocket response must prevent initial credential delivery');
    before = socketCount; routeTo('b'); await ready(before);
    await page.getByRole('button', {name: 'Recover this attempt', exact: true}).waitFor();
    assert.equal(await page.locator('form').filter({has: page.getByRole('button', {name: 'Recover this attempt', exact: true})})
      .locator('input[name="ceremony"]').inputValue(), ceremony);
    phase = 'http-delivery-response-loss';
    let discardedDelivery = false, deliveryAttempts = 0;
    await page.route(origin + '/auth/complete', async route => {
      deliveryAttempts++;
      if (discardedDelivery) return route.continue();
      const response = await route.fetch();
      assert.equal(response.status(), 204);
      discardedDelivery = true;
      await context.clearCookies({name: 'spoonbill_session'});
      await route.abort('failed');
    });
    before = socketCount;
    await page.getByRole('button', {name: 'Recover this attempt', exact: true}).click();
    await page.getByText('Authenticated account: bob', {exact: true}).waitFor();
    await ready(before);
    await page.unroute(origin + '/auth/complete');
    assert.ok(discardedDelivery && deliveryAttempts >= 2, 'HTTP response loss must exercise bounded delivery retry');
    assert.deepEqual(await proofEffects(ceremony), {proofs: 1, sessions: 1, audits: 1},
      'Recovering the original ceremony must not duplicate host proof/session/audit effects');
    before = socketCount;
    await page.getByRole('button', {name: 'Sign out', exact: true}).click();
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).waitFor();
    await ready(before);
    before = socketCount; routeTo('a'); await ready(before);
    phase = 'authenticate-a';
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').fill('alice');
    await page.locator('input[name="password"]').fill('password');
    before = socketCount;
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await page.getByText('Authenticated account: alice', {exact: true}).waitFor(); await ready(before);
    const session = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(session?.httpOnly);

    phase = 'first-draft-before-pause';
    await save('Acknowledged unfinished work on A', 'item-a');
    phase = 'overlap-and-delayed-old-owner';
    a.kill('SIGSTOP');
    before = socketCount; routeTo('b');
    await recovered('Acknowledged unfinished work on A', 'item-a', before);
    a.kill('SIGCONT');
    await stop(a); a = undefined;
    phase = 'draft-after-old-owner-cleanup';
    await save('Acknowledged work on B after old cleanup', 'item-b');
    a = await start('a');

    phase = 'graceful-handoff';
    before = socketCount; target = 'a';
    await stop(b); b = undefined;
    await recovered('Acknowledged work on B after old cleanup', 'item-b', before);
    b = await start('b');
    await save('Acknowledged work before abrupt failure', 'item-c');

    phase = 'lost-business-response';
    const previousCount = await count();
    dropResponses = true;
    await page.getByRole('button', {name: 'Protected increment', exact: true}).click();
    await until(async () => await count() === previousCount + 1, 'Protected operation did not commit');
    assert.equal(await page.getByText('Protected increment committed.', {exact: true}).count(), 0,
      'The response-loss injection must hide the committed operation acknowledgement');
    phase = 'abrupt-owner-loss';
    before = socketCount;
    await stop(a, 'SIGKILL'); a = undefined;
    routeTo('b');
    await recovered('Acknowledged work before abrupt failure', 'item-c', before);
    await page.getByText(`This view's last confirmed increment: ${previousCount + 1}`, {exact: true}).waitFor();
    assert.equal(await count(), previousCount + 1, 'Reconnect must not replay a committed action');
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'spoonbill_session')?.value, session.value,
      'Changing serving node must retain the current session');

    phase = 'incompatible-reader-preserves-snapshot';
    a = await start('a', false, 3);
    const stored = await snapshotState();
    const sameView = new URL(sockets.at(-1).url).pathname;
    const logStart = fs.statSync(path.join(directory, 'a.log')).size;
    before = socketCount; routeTo('a');
    await until(() => fs.readFileSync(path.join(directory, 'a.log'), 'utf8').slice(logStart)
      .includes('SPOONBILL_FIXTURE_SCHEMA_INCOMPATIBLE'), 'The incompatible reader did not refuse projection initialization');
    assert.deepEqual(await snapshotState(), stored, 'An incompatible reader must preserve revision, format and payload');
    assert.ok(sockets.slice(before).every(socket => !socket.ready), 'Incompatible presentation must not reach VIEW_READY');
    before = socketCount; routeTo('b');
    await recovered('Acknowledged work before abrupt failure', 'item-c', before);
    assert.equal(new URL(sockets.at(-1).url).pathname, sameView, 'Schema failure must retain the original view ID');
    await stop(a); a = await start('a');

    phase = 'view-capacity-refusal';
    const extra = await context.newPage();
    let rejected;
    try {
      await extra.goto(origin + '/');
      await extra.waitForFunction(() => window.Spoonbill?.ready === true);
      await until(async () => await activeViews() === 2, 'The second view did not claim the configured capacity');
      rejected = await context.newPage();
      let rejectedReady = false, rejectedClosed = false;
      rejected.on('websocket', socket => {
        socket.on('framereceived', event => { if (JSON.parse(String(event.payload))[0] === 21) rejectedReady = true; });
        socket.on('close', () => { rejectedClosed = true; });
      });
      await rejected.goto(origin + '/');
      await until(() => rejectedClosed, 'Excess view was not refused at the configured capacity');
      assert.equal(rejectedReady, false);
      assert.equal(await activeViews(), 2);
    } finally { if (rejected) await rejected.close(); await extra.close(); }
    await until(async () => await activeViews() === 1, 'Closing a current view must release its active capacity');

    phase = 'revocation-during-takeover';
    await sql(`UPDATE ${schema}.example_session SET enabled=FALSE WHERE subject=(SELECT id FROM ${schema}.example_account WHERE name='alice')`);
    before = socketCount; routeTo('a');
    await until(() => sockets.length > before && sockets.slice(before).some(socket => socket.closed),
      'Revoked authority was not denied during takeover');
    assert.ok(sockets.slice(before).every(socket => !socket.ready), 'An earlier snapshot cannot revive a revoked session');
    assert.equal((await context.request.get(origin + '/protected')).status(), 403);

    phase = 'logout';
    const logout = await context.request.post(origin + '/auth/logout', {headers: {Origin: origin}, maxRedirects: 0});
    assert.equal(logout.status(), 303);
    await page.goto(origin + '/');
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).waitFor();
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'spoonbill_session'), undefined);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({engine, separateJvms: true, sharedPostgres: true, overlap: true,
      firstHttpAndSocketDifferentNodes: true, factorChallengeHandoff: true,
      unknownViewRefused: true, originalCeremonyRecovered: true, lostHttpDeliveryResponseRetried: true,
      incompatibleSnapshotPreserved: true, sameViewCompatibleRecovery: true,
      activeViewCapacityRefused: true, revokedTakeoverDenied: true,
      mutuallyReadableSnapshotVersions: [1, 2],
      delayedOldOwnerCleanup: true, gracefulHandoff: true, abruptLoss: true,
      acknowledgedDraftRestored: true, lostBusinessResponseNotReplayed: true, currentSessionRetained: true, logout: true}));
  } catch (error) {
    let ui = {unavailable: true}, timer;
    try {
      if (page) ui = await Promise.race([
        page.evaluate(() => ({ready: window.Spoonbill?.ready === true,
          draftLength: document.querySelector('textarea[name="draft"]')?.value?.length,
          selectionLength: document.querySelector('input[name="selectedItem"]')?.value?.length,
          savedLength: document.querySelector('#saved-draft')?.textContent?.length})),
        new Promise(resolve => { timer = setTimeout(() => resolve({unavailable: true}), 1000); }),
      ]);
    } catch (_) { /* A terminated browser still has protocol metadata. */ }
    finally { clearTimeout(timer); }
    console.error(JSON.stringify({kind: 'clustered-browser-failure', engine, phase, error: error.message,
      ui, pageErrors: errors, sockets: sockets.slice(-8).map(({url, ...socket}) => socket), requests}));
    for (const node of ['a', 'b']) {
      const file = path.join(directory, node + '.log');
      if (fs.existsSync(file)) console.error(fs.readFileSync(file, 'utf8').slice(-12000));
    }
    throw error;
  } finally {
    if (context) await context.close();
    if (browser) await browser.close();
    for (const {client, upstream} of connections) { client.destroy(); upstream.destroy(); }
    await new Promise(resolve => proxy.close(resolve));
    await Promise.allSettled([stop(a), stop(b)]);
    fs.rmSync(directory, {recursive: true, force: true});
  }
}

for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => {
  for (const child of owned) { child.kill('SIGCONT'); child.kill('SIGKILL'); }
  process.exit(signal === 'SIGINT' ? 130 : 143);
});
(async () => {
  for (const engine of engines) await exercise(engine);
  console.log(`PASS: clustered browser authentication in ${engines.join(', ')}`);
})().catch(error => {
  for (const child of owned) { child.kill('SIGCONT'); child.kill('SIGKILL'); }
  console.error(error.message); process.exitCode = 1;
});
