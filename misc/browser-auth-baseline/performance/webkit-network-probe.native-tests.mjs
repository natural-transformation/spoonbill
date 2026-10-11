import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {verifyPlaywrightPin} from './browser-heap.mjs';
import {observePinnedWebKitMetadata} from './webkit-network-probe.mjs';
import {createWebSocketCalibrationServer, observeWebSocketEvents, websocketCalibrationCases,
  sendSyntheticWebSocketMessages} from './websocket-events.mjs';

const root = process.env.PLAYWRIGHT_DRIVER_PATH;
verifyPlaywrightPin(root);
const {webkit} = createRequire(import.meta.url)(root);
test('pinned WebKit raw network metadata versus physical frames, including empty sends', {timeout: 90000}, async () => {
  const env = {...process.env};
  if (process.platform === 'linux') {
    assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
    env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
    env.LIBGL_ALWAYS_SOFTWARE = 'true';
  }
  const browser = await webkit.launch({headless: true, env, timeout: 15000});
  try {
    for (const fixture of websocketCalibrationCases) {
      const server = await createWebSocketCalibrationServer(); let context;
      try {
        context = await browser.newContext(); const page = await context.newPage();
        await page.goto(server.origin, {timeout: 5000});
        let publicEvents;
        const raw = await observePinnedWebKitMetadata(page, async () => {
          publicEvents = await observeWebSocketEvents(page, 'webkit', () => sendSyntheticWebSocketMessages(page, server.origin, fixture));
        });
        const physical = server.snapshot();
        console.log(JSON.stringify({engine: 'webkit', browserVersion: browser.version(), playwrightVersion: '1.56.1',
          platform: process.platform, fixture, raw, publicFrameSentEvents: publicEvents.publicFrameSentEvents,
          physical: physical.wire, rawDataEventsEqualPhysical:
            raw.sent.text + raw.sent.binary + raw.sent.continuation === physical.wire?.physicalDataFrames}));
        assert.equal(raw.status, 'observed');
        assert.equal(physical.failure, null);
        assert.equal(physical.wire.physicalDataFrames, fixture.messages);
        assert.equal(physical.wire.completedMessages, fixture.messages);
        assert.equal(physical.wire.retainedPayloadBytes, 0);
        assert.equal(raw.sent.text + raw.sent.binary + raw.sent.continuation, physical.wire.physicalDataFrames);
        assert.equal(raw.retainedIdsAfterStop, 0);
      } finally {
        try { if (context) await context.close(); }
        finally { assert.equal((await server.close()).activeSockets, 0); }
      }
    }
  } finally { await browser.close(); }
});
