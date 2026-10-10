// Explicit native calibration; absent browsers are failures, never silent skips.
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {verifyPlaywrightPin} from './browser-heap.mjs';
import {createWebSocketCalibrationServer, observeWebSocketEvents, websocketCalibrationCases,
  sendSyntheticWebSocketMessages} from './websocket-events.mjs';

const root = process.env.PLAYWRIGHT_DRIVER_PATH;
verifyPlaywrightPin(root);
const playwright = createRequire(import.meta.url)(root);
for (const engine of ['chromium', 'webkit']) test(`${engine}: browser send notifications versus actual loopback wire frames`, {timeout: 90000}, async () => {
  const env = {...process.env};
  if (engine === 'webkit' && process.platform === 'linux') {
    assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
    env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
    env.LIBGL_ALWAYS_SOFTWARE = 'true';
  }
  const browser = await playwright[engine].launch({headless: true, env, timeout: 15000});
  try {
    for (const fixture of websocketCalibrationCases) {
      const server = await createWebSocketCalibrationServer();
      let context;
      try {
        context = await browser.newContext();
        const page = await context.newPage();
        await page.goto(server.origin, {timeout: 5000});
        const notifications = await observeWebSocketEvents(page, engine, () => sendSyntheticWebSocketMessages(page, server.origin, fixture));
        const physical = server.snapshot();
        assert.equal(notifications.status, 'observed');
        assert.equal(notifications.publicSockets, 1);
        assert.equal(physical.failure, null);
        assert.equal(physical.wire.completedMessages, fixture.messages);
        assert.equal(physical.wire.physicalDataFrames, fixture.messages);
        assert.equal(physical.wire.continuationFrames, 0);
        assert.equal(physical.wire.retainedPayloadBytes, 0);
        console.log(JSON.stringify({engine, browserVersion: browser.version(), playwrightVersion: '1.56.1', platform: process.platform,
          fixture, physical: physical.wire, notifications,
          publicEventsEqualPhysical: notifications.publicFrameSentEvents === physical.wire.physicalDataFrames,
          cdpDataEventsEqualPhysical: notifications.cdpSentEvents ? notifications.cdpSentEvents.text + notifications.cdpSentEvents.binary + notifications.cdpSentEvents.continuation === physical.wire.physicalDataFrames : null}));
      } finally {
        try { if (context) await context.close(); }
        finally { const released = await server.close(); assert.equal(released.activeSockets, 0); }
      }
    }
  } finally { await browser.close(); }
});
