// Explicit synthetic stock-browser calibration, not benchmark/acceptance data.
import test from 'node:test';
import assert from 'node:assert/strict';
import {createRequire} from 'node:module';
import {measureChromiumAllocations, chromiumAllocationCapability} from './chromium-allocation.mjs';
import {verifyPlaywrightPin} from './browser-heap.mjs';

const root = process.env.PLAYWRIGHT_DRIVER_PATH;
verifyPlaywrightPin(root);
const playwright = createRequire(import.meta.url)(root);
test('stock Chromium trace totals observe persistent and reclaimed short-lived allocations', {timeout: 120000}, async () => {
  const browser = await playwright.chromium.launch({headless: true, timeout: 15000});
  try {
    const page = await browser.newPage();
    await page.goto('about:blank');
    // Warm exactly the fixture shape before the predefined three observations.
    await page.evaluate(() => {
      globalThis.__allocateSynthetic = count => {
        const values = [];
        for (let i = 0; i < count; ++i) values.push({index: i, first: i + 1, second: i + 2, third: i + 3});
        globalThis.__syntheticWeak = new WeakRef(values[0]);
        globalThis.__syntheticAllocations = values;
      };
      globalThis.__allocateSynthetic(1000);
      delete globalThis.__syntheticAllocations;
    });
    const empty = await measureChromiumAllocations(page, async () => {});
    const persistent = await measureChromiumAllocations(page, async () => {
      await page.evaluate(() => globalThis.__allocateSynthetic(20000));
      await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
    });
    assert.equal(await page.evaluate(() => globalThis.__syntheticWeak.deref() !== undefined), true);
    await page.evaluate(() => { delete globalThis.__syntheticAllocations; });
    let reclaimedBeforeObservation = false;
    const transient = await measureChromiumAllocations(page, async controls => {
      await page.evaluate(() => {
        globalThis.__allocateSynthetic(40000);
        delete globalThis.__syntheticAllocations;
      });
      await page.evaluate(() => new Promise(resolve => setTimeout(resolve, 0)));
      await controls.collectGarbage();
      reclaimedBeforeObservation = await page.evaluate(() => globalThis.__syntheticWeak.deref() === undefined);
    });
    assert.equal(reclaimedBeforeObservation, true, 'Short-lived objects must be reclaimed before the trace snapshot');
    assert.ok(persistent.allocatedBytes > empty.allocatedBytes + 20000 * 16, 'Persistent allocation signal missing');
    assert.ok(transient.allocatedBytes > empty.allocatedBytes + 40000 * 16, 'Freed allocations were lost');
    assert.ok(transient.allocationCount > empty.allocationCount + 40000, 'Per-allocation counters did not retain the short-lived workload');
    assert.equal(transient.supportedForAcceptance, false);
    assert.equal(transient.browserAllocatedBytesPerOperation, null);
    console.log(JSON.stringify({engine: 'chromium', browserVersion: browser.version(), platform: process.platform,
      architecture: process.arch, emptyTraceBytes: empty.allocatedBytes, persistentTraceBytes: persistent.allocatedBytes,
      transientTraceBytes: transient.allocatedBytes, transientAllocations: transient.allocationCount,
      reclaimedBeforeObservation, acceptanceValue: chromiumAllocationCapability.browserAllocatedBytesPerOperation}));
  } finally { await browser.close(); }
});
