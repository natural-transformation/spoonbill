// F2: actual Scala service + real HTML5 SSR + assembled client in Chromium and WebKit.
// Each run loads the server-rendered page, checks the parsed nodes and their ids,
// performs one ordinary click, checks the first DOM patch and its effect, and then
// dispatches real events to prove that the current handlers run exactly once.
'use strict';
const assert = require('node:assert/strict');
const {setTimeout: pause} = require('node:timers/promises');
const {scenarios, decodeOps, sortOps} = require('./renderer-acceptance-cases.cjs');
assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use nix develop .#browser');
assert.ok(process.env.SPOONBILL_F2_ORIGIN, 'Start through scripts/test-renderer-acceptance.sh');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const origin = new URL(process.env.SPOONBILL_F2_ORIGIN).origin;
assert.match(origin, /^http:\/\/127\.0\.0\.1:\d+$/);

// Selections default to the full matrix; the runner script narrows them only for its fault probe.
function selection(name, all) {
  const chosen = (process.env[name] || all.join(',')).split(',');
  for (const item of chosen) assert.ok(all.includes(item), `${name}: unknown ${item}`);
  return chosen;
}
const engines = selection('SPOONBILL_F2_ENGINES', ['chromium', 'webkit']);
const idModes = selection('SPOONBILL_F2_ID_MODES', ['plain', 'preset']);
const cases = selection('SPOONBILL_F2_SCENARIOS', Object.keys(scenarios));
const fault = selection('SPOONBILL_F2_FAULT', ['none', 'truncate-names'])[0];

async function control(context, command, timeout = 10000) {
  const response = await context.request.post(`${origin}/fixture/${command}`, {timeout});
  assert.equal(response.status(), 200, `Control ${command} must finish within its bound`);
  return JSON.parse(await response.text());
}

// Polls the fixture until `condition` holds, within a strict 5 s wall-clock
// budget: each status request gets only the remaining time (never 0, which
// Playwright reads as no timeout), and a result arriving after the deadline is
// not accepted.
async function waitStatus(context, condition, message) {
  const deadline = Date.now() + 5000;
  for (;;) {
    const remaining = deadline - Date.now();
    assert.ok(remaining > 0, message);
    let status;
    try {
      status = await control(context, 'status', remaining);
    } catch (error) {
      assert.fail(`${message} (status request: ${String(error.message).split('\n')[0]})`);
    }
    assert.ok(Date.now() <= deadline, message);
    if (condition(status)) return status;
    await pause(Math.min(25, Math.max(0, deadline - Date.now())));
  }
}

// Lets a delivered frame's message task and one rendering opportunity pass.
async function settle(page) {
  await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => resolve())));
  await pause(150);
}

// Runs in the page: locate a described node and report what the browser parsed.
function inspect({descriptors, remember, compare}) {
  const locate = d => d.select ? document.querySelector(d.select)
    : document.querySelector(d.parent)?.childNodes[d.index] ?? null;
  if (remember) window.__f2Before = {};
  return descriptors.map(d => {
    const node = locate(d);
    if (!node) return {key: d.key, missing: true};
    if (remember) window.__f2Before[d.key] = node;
    // The minified client renames its vId property; its public registry lookup is the id authority.
    const out = {key: d.key, registered: window.Spoonbill.element(d.vId) === node};
    if (compare) out.same = window.__f2Before[d.key] === node;
    if (node.nodeType === Node.ELEMENT_NODE) {
      out.localName = node.localName;
      out.attrs = Object.fromEntries(Array.from(node.attributes, a => [a.name, a.value]));
      out.styles = Object.fromEntries((d.styleNames || []).map(n => [n, node.style.getPropertyValue(n).trim()]));
      // Engines serialize equal CSS differently (WebKit re-quotes 'x' as "x"), so the
      // intended value is serialized by the same engine on a detached probe element.
      out.canonical = Object.fromEntries(Object.entries(d.styles || {}).map(([n, value]) => {
        const probe = document.createElement('div');
        probe.style.setProperty(n, value);
        return [n, probe.style.getPropertyValue(n).trim()];
      }));
      out.text = node.textContent;
      out.children = node.childNodes.length;
    } else out.text = node.data;
    return out;
  });
}

const clip = value => { const text = String(value); return text.length > 120 ? `${text.slice(0, 120)}…(${text.length})` : text; };

function checkNodes(actual, descriptors, ids, label) {
  descriptors.forEach((d, i) => {
    const a = actual[i];
    const where = `${label} ${d.key}`;
    assert.ok(!a.missing, `${where} is missing`);
    assert.ok(a.registered, `${where} must be the client registry entry for server id ${d.vId}`);
    if (d.localName) assert.equal(a.localName, d.localName, `${where} local name ${clip(a.localName)}`);
    if (d.attrs) {
      const expected = {...d.attrs};
      if (d.styleNames) expected.style = a.attrs.style;
      // Preset ids carry the server's last id segment in `k`, emitted after styles.
      if (ids === 'preset') expected.k = d.vId.split('_').pop();
      const names = Object.keys(a.attrs).sort();
      assert.deepEqual(names, Object.keys(expected).sort(), `${where} attribute names: ${names.map(clip)}`);
      for (const [name, value] of Object.entries(expected))
        assert.equal(a.attrs[name], value, `${where} attribute ${clip(name)}`);
    }
    for (const name of Object.keys(d.styles || {})) {
      assert.notEqual(a.canonical[name], '', `${where} intended style ${name} must be valid CSS`);
      assert.equal(a.styles[name], a.canonical[name], `${where} style ${name}`);
    }
    if (d.text !== undefined) assert.equal(a.text, d.text, `${where} text`);
    if (d.children !== undefined) assert.equal(a.children, d.children, `${where} child count`);
  });
}

async function exercise(browser, engine, scenario, ids) {
  const context = await browser.newContext();
  const errors = [], frames = [], sockets = [];
  let phase = 'start';
  try {
    const run = await control(context, `start/${scenario}/${ids}/${fault}`);
    assert.equal(run.witnessesCollide, true);
    const spec = scenarios[scenario](run.names);
    const page = await context.newPage();
    page.setDefaultTimeout(10000);
    page.setDefaultNavigationTimeout(10000);
    page.on('pageerror', error => errors.push(`pageerror ${clip(error.message)}`));
    page.on('console', message => { if (message.type() === 'error') errors.push(`console ${clip(message.text())}`); });
    page.on('websocket', socket => {
      sockets.push(socket);
      socket.on('framereceived', event => {
        frames.push(JSON.parse(Buffer.isBuffer(event.payload) ? event.payload.toString('utf8') : event.payload));
      });
    });

    phase = 'ssr';
    const response = await page.goto(origin);
    assert.equal(response.status(), 200);
    const html = await response.text();
    const doctype = '<!DOCTYPE html>\n';
    assert.ok(html.startsWith(ids === "preset" ? `${doctype}<html k="1">` : `${doctype}<html>`), "Actual HTML5 response");
    assert.ok(!/>\s+</.test(html.slice(doctype.length)), 'SSR is not pretty-printed');
    assert.ok(!/\son[a-z]+\s*=\s*["']/i.test(html), 'No serialized event-handler attribute');
    assert.ok(!html.includes('<img') && !html.includes('<script>window.__f2'), 'Markup-bearing data stays data');
    for (const fragment of spec.rawIncludes || []) assert.ok(html.includes(fragment), `SSR contains ${clip(fragment)}`);
    await page.waitForFunction(() => window.Spoonbill?.ready === true);
    assert.equal(sockets.length, 1);

    phase = 'parsed';
    checkNodes(await page.evaluate(inspect, {descriptors: spec.before, remember: true}), spec.before, ids, 'parsed');
    for (const selector of spec.absentBefore || [])
      assert.equal(await page.locator(selector).count(), 0, `${selector} absent before the patch`);

    phase = 'first-patch';
    const mark = frames.length;
    await page.click('#advance');
    await waitStatus(context, status => status.advance === 1, 'The advance handler must run');
    await page.waitForFunction(spec.sentinel);
    await settle(page);
    const patches = frames.slice(mark).filter(frame => frame[0] === 4);
    assert.equal(patches.length, 1, 'One ordinary event produces exactly one DOM patch frame');

    phase = 'patched-dom';
    const after = await page.evaluate(inspect, {descriptors: spec.after, compare: true});
    checkNodes(after, spec.after, ids, 'patched');
    for (const selector of spec.absentAfter || [])
      assert.equal(await page.locator(selector).count(), 0, `${selector} absent after the patch`);
    after.forEach((a, i) => {
      const key = spec.after[i].key;
      if (spec.retained.includes(key)) assert.equal(a.same, true, `${key} keeps its DOM identity`);
      if (spec.replaced.includes(key)) assert.equal(a.same, false, `${key} is a new node`);
    });

    phase = 'patch-ops';
    // Exact operations, compared as a multiset of decoded operations.
    assert.deepEqual(sortOps(decodeOps(patches[0])), sortOps(spec.ops), 'First patch targets the parsed nodes');

    phase = 'events';
    // An element without a handler must not reach any handler, even through misaddressing.
    await page.click(spec.inert);
    for (const action of spec.actions || []) await action(page);
    await settle(page);
    let status = await control(context, 'status');
    assert.deepEqual([status.advance, status.previous, status.current, status.component], [1, 0, 0, 0]);
    await page.click('#current');
    await waitStatus(context, s => s.current === 1, 'The current handler must run');
    if (scenario === 'first-patch') {
      await page.click('#fp-component-button');
      await waitStatus(context, s => s.component === 1, 'The component handler must run');
    }
    await settle(page);
    status = await control(context, 'status');
    assert.deepEqual([status.advance, status.previous, status.current, status.component],
      [1, 0, 1, scenario === 'first-patch' ? 1 : 0], 'Each current handler runs exactly once; the replaced one never');
    assert.equal(await page.evaluate(() => window.__f2Injected), undefined, 'No injected handler ran');
    const handlerAttributes = await page.evaluate(() => Array.from(document.querySelectorAll('*'))
      .flatMap(element => Array.from(element.attributes, a => a.name)).filter(name => /^on/i.test(name)));
    assert.deepEqual(handlerAttributes, [], 'No event-handler attribute exists');
    assert.equal(sockets.length, 1, 'No reconnect may stand in for the patch');
    assert.deepEqual(errors, []);
    console.log(JSON.stringify({engine, scenario, ids, fault, parsed: spec.before.length,
      patchOps: spec.ops.length, checked: spec.after.length, retained: spec.retained.length, result: 'pass'}));
  } catch (error) {
    error.f2 = {phase, errors};
    throw error;
  } finally {
    await context.close();
  }
}

// Strict expected failures: a defect this suite discovered and has not fixed.
// The run must fail exactly as recorded; a changed or vanished failure fails the
// suite, so a fix has to promote the case to an ordinary passing one.
const knownDefects = {
  // Spoonbill 10.0.0 client, registerRoot with kid (presetIds): `lastId` holds
  // the string from the `k` attribute, so `lastId + 1` concatenates. A text node
  // after an element with k="1" is registered as 1_2_1_11 instead of 1_2_1_2,
  // and the first patch of that text appends a duplicate instead of replacing it.
  'first-patch/preset': {phase: 'parsed', failure: 'parsed loose must be the client registry entry for server id 1_2_1_2'},
};

async function attempt(browser, engine, scenario, ids) {
  const known = fault === 'none' ? knownDefects[`${scenario}/${ids}`] : undefined;
  try {
    await exercise(browser, engine, scenario, ids);
  } catch (error) {
    const {phase = 'setup', errors = []} = error.f2 || {};
    if (known && phase === known.phase && error.message === known.failure && errors.length === 0) {
      console.log(JSON.stringify({engine, scenario, ids, fault, phase, result: 'known-defect', failure: known.failure}));
      return;
    }
    // Synthetic data only; messages are clipped and never include cookies.
    console.error(JSON.stringify({engine, scenario, ids, fault, phase, failure: clip(error.message), errors}));
    throw error;
  }
  if (known) throw new Error(`Known defect ${scenario}/${ids} no longer reproduces; make it an ordinary case`);
}

(async () => {
  const types = {chromium, webkit};
  for (const engine of engines) {
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      assert.ok(env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR);
      env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    let browser;
    try {
      browser = await types[engine].launch({headless: true, env, timeout: 15000});
      console.log(JSON.stringify({engine, version: browser.version()}));
      for (const ids of idModes) for (const scenario of cases) await attempt(browser, engine, scenario, ids);
    } finally { if (browser) await browser.close(); }
  }
  console.log(`PASS: F2 real-service renderer acceptance (${engines.join(', ')}; ${idModes.join(', ')} ids; ${cases.length} cases)`);
})().catch(error => { console.error(clip(error.message)); process.exitCode = 1; });
