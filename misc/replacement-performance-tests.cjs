// Frozen, bounded browser measurements. Never retain or print cookies, proofs,
// recovery plaintext, raw WebSocket frames, HTML, or application screenshots.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const {performance} = require('node:perf_hooks');
const {setTimeout: pause} = require('node:timers/promises');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use the repository Nix browser/profiling shell');
const {chromium} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_AUTH_ORIGIN).origin;
assert.match(origin, /^http:\/\/(localhost|127\.0\.0\.1):\d+$/);
const provider = process.env.SPOONBILL_AUTH_PROVIDER;
const variant = process.env.SPOONBILL_PERF_VARIANT;
const flow = process.env.SPOONBILL_PERF_FLOW;
const output = process.env.SPOONBILL_PERF_OUTPUT;
assert.ok(['memory', 'jdbc'].includes(provider));
assert.ok(['baseline', 'candidate'].includes(variant));
assert.ok(['ordinary', 'replacement'].includes(flow));
assert.ok(variant === 'candidate' || flow === 'ordinary');
assert.ok(output?.startsWith('/'), 'Provide an absolute output file');
const warmups = 5, samples = 20;
const evidence = {protocol: 'authenticated-replacement-browser-v1', provider, variant, flow,
  engine: 'chromium', warmups, samples, warmupsCompleted: 0, measured: [], phase: 'launch'};
function checkpoint(status, fields = {}) {
  Object.assign(evidence, fields, {status});
  const temporary = output + '.tmp';
  fs.writeFileSync(temporary, JSON.stringify(evidence, null, 2) + '\n');
  fs.renameSync(temporary, output);
}

function frameCode(event) {
  try { return JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload)[0]; }
  catch (_) { return undefined; }
}
function summary(values) {
  const sorted = [...values].sort((a, b) => a - b);
  return {n: values.length, medianMs: (sorted[9] + sorted[10]) / 2, p95Ms: sorted[Math.ceil(values.length * .95) - 1],
    minimumMs: sorted[0], maximumMs: sorted.at(-1)};
}

async function session(browser) {
  delete evidence.currentCounts;
  const context = await browser.newContext();
  const page = await context.newPage();
  page.setDefaultTimeout(25000);
  page.setDefaultNavigationTimeout(25000);
  const changes = new Set(), errors = [], counts = {completePosts: 0, recoveryPosts: 0, activationSockets: 0,
    applicationSockets: 0, completionFrames: 0, sensitiveFrames: 0};
  let socketOrdinal = 0, latestApplication;
  const changed = () => { for (const wake of [...changes]) wake(); };
  function deadlineError() {
    const error = new Error('Fresh physical application readiness deadline exceeded');
    error.name = 'TimeoutError';
    return error;
  }
  function waitForChange(remaining) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { changes.delete(wake); reject(deadlineError()); }, remaining);
      function wake() { clearTimeout(timer); changes.delete(wake); resolve(); }
      changes.add(wake);
    });
  }
  evidence.currentCounts = counts;
  page.on('pageerror', error => errors.push(error.name));
  page.on('request', request => {
    if (request.method() !== 'POST') return;
    const path = new URL(request.url()).pathname;
    if (path === '/auth/complete') counts.completePosts++;
    if (path === '/auth/recover') counts.recoveryPosts++;
  });
  page.on('websocket', socket => {
    const activation = new URL(socket.url()).pathname.endsWith('/auth/activate');
    const observed = {ordinal: ++socketOrdinal, closed: false, readySeen: false};
    if (activation) counts.activationSockets++; else counts.applicationSockets++;
    if (!activation) latestApplication = observed;
    socket.on('close', () => { observed.closed = true; changed(); });
    socket.on('framereceived', event => {
      const code = frameCode(event);
      if (code === 18) counts.completionFrames++;
      if (code === 22) counts.sensitiveFrames++;
      if (code === 21) { observed.readySeen = true; changed(); }
    });
    changed();
  });
  const nextApplication = () => ({after: socketOrdinal, deadline: performance.now() + 25000});
  async function ready(expected) {
    for (;;) {
      const remaining = expected.deadline - performance.now();
      if (remaining <= 0) throw deadlineError();
      const observed = latestApplication;
      evidence.readiness = {afterOrdinal: expected.after, latestOrdinal: observed?.ordinal,
        closed: observed?.closed, readySeen: observed?.readySeen};
      if (observed && observed.ordinal > expected.after && !observed.closed && observed.readySeen) {
        await page.waitForFunction(() => window.Spoonbill?.ready === true, null, {timeout: remaining});
        if (latestApplication === observed && !observed.closed && observed.readySeen) return;
      } else await waitForChange(remaining);
    }
  }
  const first = nextApplication();
  assert.equal((await page.goto(origin + '/')).status(), 200);
  await ready(first);
  async function login() {
    const connection = nextApplication();
    const start = performance.now();
    await page.getByRole('button', {name: 'Begin sign-in', exact: true}).click();
    await page.locator('input[name="username"]').fill('alice');
    await page.locator('input[name="password"]').fill('password');
    await page.getByRole('button', {name: 'Sign in', exact: true}).click();
    await ready(connection);
    await page.getByText('Authenticated account: alice', {exact: true}).waitFor({state: 'visible'});
    const elapsed = performance.now() - start;
    assert.equal(counts.completePosts, 1);
    assert.equal(counts.completionFrames, 1);
    return elapsed;
  }
  async function replace() {
    const before = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(before?.httpOnly);
    await page.locator('select[name="securityChange"]').selectOption('regenerate-recovery');
    await page.locator('input[name="replacementPassword"]').fill('password');
    const connection = nextApplication();
    const start = performance.now();
    await page.getByRole('button', {name: 'Apply security change', exact: true}).click();
    await ready(connection);
    await page.getByText('Authenticated account: alice', {exact: true}).waitFor({state: 'visible'});
    const elapsed = performance.now() - start;
    const after = (await context.cookies()).find(cookie => cookie.name === 'spoonbill_session');
    assert.ok(after?.httpOnly && before.value !== after.value, 'Replacement must install a fresh HttpOnly credential');
    return elapsed;
  }
  async function reveal() {
    const start = performance.now();
    await page.getByRole('button', {name: 'Reveal recovery codes once', exact: true}).click();
    await page.getByText('Recovery codes disclosed once. Store them safely.', {exact: true}).waitFor({state: 'visible'});
    const elapsed = performance.now() - start;
    evidence.assertionId = 'sensitive-frame-count';
    assert.equal(counts.sensitiveFrames, 1, 'Exactly one sensitive payload frame');
    evidence.assertionId = 'ordinary-dom-secret-absence';
    assert.ok(!(await page.content()).includes('SYN-'), 'Recovery codes must not enter ordinary DOM serialization');
    delete evidence.assertionId;
    return elapsed;
  }
  return {context, page, counts, errors, login, replace, reveal};
}

async function faultEvidence(browser) {
  evidence.faultProgress = {completed: 0, steps: []};
  async function step(id, operation, http = false) {
    evidence.phase = 'fault/' + id;
    const observed = {id, status: 'running'};
    evidence.faultProgress.steps.push(observed);
    checkpoint('running');
    const start = performance.now();
    try {
      const result = await operation();
      observed.durationMs = performance.now() - start;
      if (http) observed.httpStatus = result;
      observed.status = 'complete';
      evidence.faultProgress.completed++;
      checkpoint('running');
      return result;
    } catch (error) {
      observed.durationMs = performance.now() - start;
      observed.status = 'failed';
      observed.failureName = /^[A-Za-z]{1,32}$/.test(error.name) ? error.name : 'Error';
      const location = String(error.stack).match(/replacement-performance-tests\.cjs:(\d+):(\d+)/);
      if (location) observed.location = `replacement-performance-tests.cjs:${location[1]}:${location[2]}`;
      checkpoint('failed');
      throw error;
    }
  }
  const s = await step('create-page', () => session(browser));
  try {
    await step('login', () => s.login());
    let abortedCompletions = 0;
    await s.page.route(origin + '/auth/complete', route => {
      abortedCompletions++;
      return route.abort('failed'); // No delivery request reaches the host; the committed mutation remains.
    });
    await step('replace', () => s.replace());
    assert.equal(abortedCompletions, 3, 'Live delivery retries must be bounded');
    assert.ok(s.counts.recoveryPosts >= 1 && s.counts.recoveryPosts <= 3);
    assert.equal(s.counts.activationSockets, 1, 'Recovery bootstrap must activate on its dedicated fresh socket');
    await step('reveal', () => s.reveal());
    await step('duplicate-consume-click', () => s.page.getByRole('button', {name: 'Reveal recovery codes once', exact: true}).click());
    await step('duplicate-consume-presentation', () => s.page.getByText('No unused recovery codes remain. Regenerate to obtain new codes.', {exact: true}).waitFor({state: 'visible'}));
    assert.equal(s.counts.sensitiveFrames, 1, 'Repeated consumption must not produce another sensitive frame');
    const headers = {'Origin': origin, 'Content-Type': 'text/plain', 'Sec-Fetch-Site': 'same-origin'};
    let repeatedRecovery = 0, deniedRequests = 0;
    const activatedRecoveryStatuses = [];
    for (let i = 0; i < 3; i++) {
      await step(`repeated-api-recover-${i}`, async () => {
        const response = await s.context.request.post(origin + '/auth/recover', {headers, data: ''});
        // An activated memory slot has retired its discovery reservation; JDBC
        // can resolve retained exact-successor linkage. Both must avoid redelivery.
        assert.ok([204, 403].includes(response.status()));
        activatedRecoveryStatuses.push(response.status());
        assert.equal(response.headers()['set-cookie'], undefined, 'Activated recovery must not redisclose credentials');
        repeatedRecovery++;
        return response.status();
      }, true);
    }
    for (const requestHeaders of [{...headers, Origin: 'http://invalid.example'}, headers]) {
      for (let i = 0; i < 3; i++) {
        await step(`denial-${deniedRequests}`, async () => {
          const response = await s.context.request.post(origin + '/auth/recover', {
            headers: requestHeaders, data: requestHeaders.Origin === origin ? 'invalid-handle' : '',
          });
          assert.equal(response.status(), 403);
          deniedRequests++;
          return response.status();
        }, true);
      }
    }
    assert.deepEqual(s.errors, []);
    return {abortedCompletions, repeatedRecovery, activatedRecoveryStatuses, deniedRequests, duplicateConsumptionDenied: true,
      counts: s.counts};
  } finally { await s.context.close(); }
}

module.exports = {session, faultEvidence};
if (require.main === module) (async () => {
  checkpoint('running');
  const browser = await chromium.launch({headless: true, timeout: 15000});
  evidence.engineVersion = browser.version();
  const measured = evidence.measured;
  try {
    for (let index = 0; index < warmups + samples; index++) {
      evidence.iteration = index;
      evidence.phase = 'create-page';
      const s = await session(browser);
      try {
        evidence.phase = 'ordinary-login';
        const loginMs = await s.login();
        let replacementMs, consumptionMs;
        if (flow === 'replacement') {
          evidence.phase = 'replacement';
          replacementMs = await s.replace();
          evidence.phase = 'consumption';
          consumptionMs = await s.reveal();
          evidence.phase = 'replacement-protocol-checks';
          evidence.assertionId = 'completion-post-count';
          assert.equal(s.counts.completePosts, 2);
          evidence.assertionId = 'completion-frame-count';
          assert.equal(s.counts.completionFrames, 2);
          evidence.assertionId = 'recovery-post-count';
          // A known successful handoff marks its exact socket's intentional
          // close; only genuinely uncertain disconnections should discover.
          assert.equal(s.counts.recoveryPosts, 0);
          delete evidence.assertionId;
        }
        assert.deepEqual(s.errors, []);
        if (index >= warmups) measured.push({loginMs, replacementMs, consumptionMs,
          replacementAndConsumptionMs: replacementMs === undefined ? undefined : replacementMs + consumptionMs,
          counts: {...s.counts}});
        else evidence.warmupsCompleted++;
      } finally { await s.context.close(); }
      // Outside every timed action and after transport/context cleanup. Preserve
      // all completed samples even if a later sample or fault check fails.
      checkpoint('running');
    }
    evidence.phase = 'fault-checks';
    const faultChecks = flow === 'replacement' ? await faultEvidence(browser) : undefined;
    // Allow transport releases and the application's scheduled maintenance to settle.
    evidence.phase = 'maintenance-wait';
    await pause(2200);
    const metrics = {login: summary(measured.map(row => row.loginMs))};
    if (flow === 'replacement') {
      metrics.replacement = summary(measured.map(row => row.replacementMs));
      metrics.consumption = summary(measured.map(row => row.consumptionMs));
      metrics.replacementAndConsumption = summary(measured.map(row => row.replacementAndConsumptionMs));
    }
    checkpoint('complete', {phase: 'complete', metrics, faultChecks});
    console.log(JSON.stringify({provider, variant, flow, metrics, faultChecks}));
  } finally { await browser.close(); }
})().catch(error => {
  // Deliberately omit Playwright call logs, DOM and request payloads.
  const failure = /^[A-Za-z]{1,32}$/.test(error.name) ? error.name : 'Error';
  const safeValue = value => typeof value === 'number' || typeof value === 'boolean' ? value : null;
  checkpoint('failed', {failure: {name: failure, phase: evidence.phase, assertionId: evidence.assertionId,
    actual: safeValue(error.actual), expected: safeValue(error.expected)}});
  console.error(JSON.stringify({provider, variant, flow, failure, phase: evidence.phase,
    message: error.name === 'AssertionError' ? 'Measurement correctness assertion failed' : 'Measurement lane failed'}));
  process.exitCode = 1;
});
