// Explicit native capability validation, not application performance evidence.
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {retainedJavaScriptHeap, verifyPlaywrightPin, allocationCapability} from './browser-heap.mjs';

const root = process.env.PLAYWRIGHT_DRIVER_PATH;
verifyPlaywrightPin(root);
const playwright = createRequire(import.meta.url)(root);
for (const engine of ['chromium', 'webkit']) test(`${engine}: retained heap sensor observes synthetic allocation and reclamation`, {timeout: 120000}, async () => {
  const env = {...process.env};
  if (engine === 'webkit' && process.platform === 'linux') {
    assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
    env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
    env.LIBGL_ALWAYS_SOFTWARE = 'true';
  }
  const browser = await playwright[engine].launch({headless: true, env, timeout: 15000});
  try {
    const page = await browser.newPage();
    await page.goto('about:blank');
    async function measure() {
      // End the evaluation's browser job before forcing collection, consistently
      // for all observations. This is fixed calibration work, never retry-until-pass.
      await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
      return retainedJavaScriptHeap(page, engine);
    }
    const before = await measure();
    // Calibration-only fixture code. No reference-app or delivered client change.
    await page.evaluate(() => { globalThis.__syntheticHeapProbe = Array.from({length: 100000}, (_, i) => ({index: i, label: `synthetic-${i}`})); });
    const retained = await measure();
    await page.evaluate(() => { delete globalThis.__syntheticHeapProbe; });
    const released = await measure();
    console.log(JSON.stringify({engine, browserVersion: browser.version(), platform: process.platform, architecture: process.arch,
      beforeBytes: before.bytes, retainedBytes: retained.bytes, releasedBytes: released.bytes,
      cumulativeAllocationBytes: null}));
    assert.ok(retained.bytes > before.bytes + 1000000, `${engine}: retained sensor did not observe the synthetic objects`);
    assert.ok(released.bytes < retained.bytes - 1000000, `${engine}: sensor did not observe reclamation`);
    assert.equal(allocationCapability.value, null);
  } finally { await browser.close(); }
});
