// Real process-loss recovery. Run only through the owned disposable-PG wrapper.
const assert = require('node:assert/strict');
const {spawn, spawnSync} = require('node:child_process');
const {randomUUID} = require('node:crypto');
const fs = require('node:fs');
const net = require('node:net');
const {setTimeout: pause} = require('node:timers/promises');
const proofProfile = process.env.SPOONBILL_AUTH_BASELINE_PROOF ?? 'short';
assert.ok(['short', 'representative'].includes(proofProfile),
  'SPOONBILL_AUTH_BASELINE_PROOF must be short or representative');

for (const name of ['PLAYWRIGHT_DRIVER_PATH', 'SPOONBILL_AUTH_BASELINE_CLASSPATH',
  'SPOONBILL_AUTH_BASELINE_JAVA', 'SPOONBILL_AUTH_BASELINE_PSQL', 'SPOONBILL_JDBC_TEST_URL'])
  assert.ok(process.env[name], `Missing owned fixture setting: ${name}`);
const java = fs.realpathSync(process.env.SPOONBILL_AUTH_BASELINE_JAVA);
const psql = fs.realpathSync(process.env.SPOONBILL_AUTH_BASELINE_PSQL);
assert.ok(java.startsWith('/nix/store/') && psql.startsWith('/nix/store/'), 'Nix-managed tools required');
let db;
try { db = new URL(process.env.SPOONBILL_JDBC_TEST_URL.replace(/^jdbc:/, '')); }
catch (_) { throw new Error('An owned loopback PostgreSQL test URL is required'); }
assert.equal(db.hostname, '127.0.0.1', 'The test owns a loopback PostgreSQL instance');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;

async function freePort() {
  const server = net.createServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  await new Promise(resolve => server.close(resolve));
  return port;
}

function launchServer(port, schema, first, databaseUrl = process.env.SPOONBILL_JDBC_TEST_URL) {
  const child = spawn(java, ['-Xms128m', '-Xmx512m', '-cp', process.env.SPOONBILL_AUTH_BASELINE_CLASSPATH,
    'spoonbill.browserauthbaseline.JdbcReferenceServer', String(port), `--proof=${proofProfile}`,
    ...(first ? ['--initialize', '--pause-after-preparation'] : [])], {
    detached: true, stdio: ['ignore', 'pipe', 'pipe'],
    env: {...process.env, SPOONBILL_JDBC_TEST_URL: databaseUrl,
      SPOONBILL_BASELINE_SCHEMA: schema, SPOONBILL_BASELINE_INITIALIZE: 'false'},
  });
  let output = '', errorOutput = '', marker;
  let resolveMarker, rejectMarker;
  const committed = new Promise((resolve, reject) => { resolveMarker = resolve; rejectMarker = reject; });
  committed.catch(() => {});
  const exited = new Promise(resolve => child.once('close', (code, signal) => resolve({code, signal})));
  child.stdout.on('data', bytes => {
    output = (output + bytes.toString()).slice(-65536);
    const found = output.match(/SYNTHETIC_PREPARATION_COMMITTED:([0-9a-f-]{36})/);
    if (found && !marker) { marker = found[1]; resolveMarker(marker); }
  });
  child.stderr.on('data', bytes => { errorOutput = (errorOutput + bytes.toString()).slice(-65536); });
  child.once('error', rejectMarker);
  child.once('close', () => { if (!marker) rejectMarker(new Error('Server exited before the committed checkpoint')); });
  return {child, committed, exited, diagnostics: () => ({stdout: output, stderr: errorOutput})};
}

async function stop(server, signal = 'SIGTERM') {
  if (!server || server.child.exitCode !== null || server.child.signalCode !== null) return;
  try { process.kill(-server.child.pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw error; }
  const timer = setTimeout(() => {
    try { process.kill(-server.child.pid, 'SIGKILL'); } catch (_) {}
  }, 7000);
  try { await server.exited; } finally { clearTimeout(timer); }
}

async function ready(server, origin) {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    if (server.child.exitCode !== null || server.child.signalCode !== null)
      throw new Error('Reference server exited during startup');
    try {
      const response = await fetch(origin + '/sign-out', {signal: AbortSignal.timeout(1000)});
      await response.arrayBuffer();
      if (response.status === 200) return;
    } catch (_) {}
    await pause(100);
  }
  throw new Error('Reference server startup deadline exceeded');
}

function sql(schema, query) {
  assert.match(schema, /^[a-z][a-z0-9_]{0,62}$/);
  const result = spawnSync(psql, ['-X', '-A', '-t', '-v', 'ON_ERROR_STOP=1',
    '--dbname', db.toString(), '-c', `SET search_path TO "${schema}"; ${query}`], {
    encoding: 'utf8', timeout: 10000,
  });
  assert.equal(result.status, 0, 'Owned PostgreSQL assertion failed');
  return result.stdout.trim().split(/\r?\n/).filter(line => line !== 'SET');
}

async function stalledDatabaseStartup() {
  const sockets = new Set();
  let accepted;
  const connected = new Promise(resolve => { accepted = resolve; });
  const peer = net.createServer(socket => {
    sockets.add(socket);
    socket.on('error', () => {});
    socket.on('close', () => sockets.delete(socket));
    // Deliberately never answer PostgreSQL startup; discard every received byte.
    socket.resume();
    accepted();
  });
  await new Promise(resolve => peer.listen(0, '127.0.0.1', resolve));
  let server, timer;
  try {
    const url = `jdbc:postgresql://127.0.0.1:${peer.address().port}/postgres?user=synthetic&sslmode=disable`;
    server = launchServer(await freePort(), 'stalled_startup', false, url);
    // JVM startup has the same allowance as ready(); the socket-timeout check
    // below starts only after the peer has actually accepted the connection.
    await Promise.race([connected, server.exited.then(() => {
      throw new Error('Owned server exited before the stalled startup connection');
    }), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Stalled startup connection deadline exceeded')), 45000);
    })]);
    clearTimeout(timer);
    const result = await Promise.race([server.exited, new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('Stalled PostgreSQL startup did not release owned resources')), 35000);
    })]);
    assert.equal(result.signal, null, 'The server must release itself without a forced kill');
    assert.equal(result.code, 0);
    assert.match(server.diagnostics().stderr, /could not initialize or bind/);
    console.log(JSON.stringify({provider: 'jdbc', stalledStartupPeer: true, boundedSocketWait: true, ownedServerReleased: true}));
  } catch (error) {
    if (server) console.error(JSON.stringify({fixture: 'stalled-startup-server', ...server.diagnostics()}));
    throw error;
  } finally {
    clearTimeout(timer);
    await stop(server);
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => peer.close(resolve));
  }
}

async function exercise(engine, type) {
  const schema = 'restart_' + randomUUID().replaceAll('-', '');
  const port = await freePort(), origin = `http://localhost:${port}`;
  let server, browser, context;
  const owned = [];
  const deadline = setTimeout(() => {
    for (const child of owned) { try { process.kill(-child.child.pid, 'SIGKILL'); } catch (_) {} }
  }, 180000);
  try {
    server = launchServer(port, schema, true); owned.push(server);
    await ready(server, origin);
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    browser = await type.launch({headless: true, env, timeout: 15000});
    context = await browser.newContext();
    const page = await context.newPage(); page.setDefaultTimeout(30000);
    const errors = []; page.on('pageerror', error => errors.push(error.message));
    await page.goto(origin + '/');
    await page.waitForFunction(() => window.Spoonbill && window.Spoonbill.ready === true);
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').waitFor({state: 'visible'});
    const ceremony = await page.locator('form').filter({has: page.locator('input[name="password"]')})
      .locator('input[name="ceremony"]').inputValue();
    assert.match(ceremony, uuid);
    await page.locator('input[name="username"]').fill('alice');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    let commitTimer, attempt;
    try {
      attempt = await Promise.race([server.committed, new Promise((_, reject) => {
        commitTimer = setTimeout(() => reject(new Error('Committed checkpoint deadline exceeded')), 30000);
      })]);
    } finally { clearTimeout(commitTimer); }
    assert.match(attempt, uuid);
    assert.equal((await context.cookies()).find(cookie => cookie.name === 'baseline_session'), undefined);
    assert.deepEqual(sql(schema, 'SELECT (SELECT count(*) FROM baseline_session), (SELECT count(*) FROM baseline_audit);'), ['1|1']);
    assert.deepEqual(sql(schema, `SELECT attempt FROM baseline_ceremony WHERE id='${ceremony}';`), [attempt]);

    // The original program has committed, but no receipt/cookie was delivered.
    await stop(server, 'SIGKILL');
    server = launchServer(port, schema, false); owned.push(server);
    await ready(server, origin);
    // Recovery is also visible on the old proof form. Wait for the restarted
    // backend's fresh metadata/presentation, rather than accepting that old DOM.
    await page.getByText('Recover the interrupted sign-in for alice without resubmitting credentials.', {exact: true})
      .waitFor({state: 'visible'});
    await page.getByRole('button', {name: 'Recover this attempt', exact: true}).waitFor({state: 'visible'});
    const recovery = page.locator('form').filter({has: page.getByRole('button', {name: 'Recover this attempt', exact: true})});
    assert.equal(await recovery.locator('input[name="ceremony"]').inputValue(), ceremony);
    await page.waitForFunction(() => window.Spoonbill && window.Spoonbill.ready === true);
    await page.getByRole('button', {name: 'Recover this attempt', exact: true}).click();
    await page.getByText('Authenticated account: alice', {exact: true}).waitFor({state: 'visible'});
    const credential = (await context.cookies()).find(cookie => cookie.name === 'baseline_session');
    assert.ok(credential?.httpOnly);
    assert.deepEqual(sql(schema, 'SELECT (SELECT count(*) FROM baseline_session), (SELECT count(*) FROM baseline_audit);'), ['1|1']);
    assert.deepEqual(sql(schema, `SELECT attempt FROM baseline_ceremony WHERE id='${ceremony}';`), [attempt]);
    assert.equal(await page.locator('input[name="password"]').count(), 0);
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({provider: 'jdbc', engine, realProcessRestart: true,
      commitBeforeResponseLoss: true, originalAttemptRecovered: true, proofNotRepeated: true}));
  } catch (error) {
    for (const child of owned) console.error(JSON.stringify({fixture: 'restart-server', ...child.diagnostics()}));
    throw error;
  } finally {
    clearTimeout(deadline);
    if (context) await context.close();
    if (browser) await browser.close();
    for (const child of owned) await stop(child);
  }
}

(async () => {
  await stalledDatabaseStartup();
  for (const [engine, type] of Object.entries({chromium, webkit})) await exercise(engine, type);
  console.log('PASS: JDBC committed delivery recovered across real process loss without proof replay');
})().catch(error => { console.error(error); process.exitCode = 1; });
