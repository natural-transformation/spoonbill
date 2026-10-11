import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import {createRequire} from 'node:module';
import {createPinnedWebKitNetworkObserver} from './webkit-network-observer.mjs';
import {verifyWebKitNetworkPin} from './webkit-network-probe.mjs';
import {createWebSocketCalibrationServer, websocketCalibrationCases, sendSyntheticWebSocketMessages} from './websocket-events.mjs';

const root = process.env.PLAYWRIGHT_DRIVER_PATH;
verifyWebKitNetworkPin(root);
const {webkit} = createRequire(import.meta.url)(root);
const env = {...process.env};
if (process.platform === 'linux') {
  assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
  env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR; env.LIBGL_ALWAYS_SOFTWARE = 'true';
}

async function heldHttp() {
  const sockets = new Set(); let arrive;
  const arrived = new Promise(resolve => { arrive = resolve; });
  const server = http.createServer(() => arrive());
  server.on('connection', socket => {
    sockets.add(socket); socket.on('close', () => sockets.delete(socket)); socket.on('error', () => {});
    socket.setTimeout(5000, () => socket.destroy());
    if (sockets.size > 4) socket.destroy();
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return {origin: `http://localhost:${server.address().port}`, arrived,
    async close() {
      const pending = [...sockets].map(socket => new Promise(resolve => { socket.once('close', resolve); socket.destroy(); }));
      await Promise.all([...pending, new Promise(resolve => server.close(resolve))]);
    }};
}
async function bounded(value, milliseconds) {
  let timer;
  try { return await Promise.race([value, new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error('Synthetic navigation checkpoint timed out')), milliseconds);
  })]); }
  finally { clearTimeout(timer); }
}

test('pinned WebKit session rotation preserves wire-calibrated events across synthetic navigation', {timeout: 90000}, async () => {
  const browser = await webkit.launch({headless: true, env, timeout: 15000}); let context, observer;
  const events = {data: 0, control: 0, created: 0, closed: 0, duplicateClose: 0};
  try {
    context = await browser.newContext(); const page = await context.newPage();
    observer = createPinnedWebKitNetworkObserver(page, {onEvent: (kind, opcode) => {
      if (kind === 'sent') { if ([0, 1, 2].includes(opcode)) ++events.data; else if ([8, 9, 10].includes(opcode)) ++events.control; }
      else if (kind === 'duplicate-close') ++events.duplicateClose;
      else if (kind === 'created' || kind === 'closed') ++events[kind];
    }});
    for (const [index, fixture] of websocketCalibrationCases.entries()) {
      const server = await createWebSocketCalibrationServer();
      try {
        const origin = index % 2 ? server.origin.replace('127.0.0.1', 'localhost') : server.origin;
        await page.goto(origin, {timeout: 5000});
        const before = events.data;
        await sendSyntheticWebSocketMessages(page, origin, fixture);
        const session = page._connection.toImpl(page).delegate._session;
        await session.send('Runtime.evaluate', {expression: '0', returnByValue: true});
        const physical = server.snapshot();
        console.log(JSON.stringify({engine: 'webkit', fixture, physical: physical.wire,
          rawDataEvents: events.data - before, lifecycle: observer.snapshot()}));
        assert.equal(physical.failure, null);
        assert.equal(physical.wire.physicalDataFrames, fixture.messages);
        assert.equal(events.data - before, physical.wire.physicalDataFrames);
        assert.deepEqual(observer.snapshot().failures, []);
      } finally { assert.equal((await server.close()).activeSockets, 0); }
    }
    const outcome = await observer.stop();
    console.log(JSON.stringify({engine: 'webkit', navigationCalibration: true, events, outcome}));
    assert.equal(outcome.status, 'observed'); assert.ok(outcome.sessionsCreated > 1);
    assert.ok(outcome.peakSessions <= 2); assert.equal(outcome.activeSessions, 0);
    assert.equal(outcome.retainedSocketIds, 0); assert.equal(outcome.pendingRetirements, 0);
  } finally {
    try { if (observer) await observer.stop(); }
    finally { try { if (context) await context.close(); } finally { await browser.close(); } }
  }
});

test('pinned WebKit canceled provisional navigation releases bounded observer ownership', {timeout: 30000}, async () => {
  const browser = await webkit.launch({headless: true, env, timeout: 15000});
  const servers = []; let context, observer;
  try {
    const first = await createWebSocketCalibrationServer(); servers.push(first);
    const held = await heldHttp(); servers.push(held);
    const final = await createWebSocketCalibrationServer(); servers.push(final);
    context = await browser.newContext(); const page = await context.newPage();
    observer = createPinnedWebKitNetworkObserver(page);
    await page.goto(first.origin, {timeout: 5000});
    const canceled = page.goto(held.origin, {timeout: 5000}).then(() => false, () => true);
    await bounded(held.arrived, 5000);
    await page.goto(final.origin, {timeout: 5000});
    assert.equal(await canceled, true);
    const outcome = await observer.stop();
    console.log(JSON.stringify({engine: 'webkit', provisionalCancellation: true, outcome}));
    assert.equal(outcome.status, 'observed'); assert.ok(outcome.peakSessions <= 2);
    assert.equal(outcome.activeSessions, 0); assert.equal(outcome.retainedSocketIds, 0);
    assert.equal(outcome.pendingRetirements, 0);
  } finally {
    try { if (observer) await observer.stop(); }
    finally {
      try { if (context) await context.close(); }
      finally { try { await Promise.all(servers.map(server => server.close())); } finally { await browser.close(); } }
    }
  }
});
