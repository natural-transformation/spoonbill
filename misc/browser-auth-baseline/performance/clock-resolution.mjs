import {spawn} from 'node:child_process';
import {appendFileSync, closeSync, openSync, readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {createRequire} from 'node:module';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {verifyPlaywrightPin} from './browser-heap.mjs';

const defaults = Object.freeze({samples: 20000, warmup: 2000, samplingBudgetMs: 2000});
const hash = value => createHash('sha256').update(value).digest('hex');
const fail = code => { throw new Error(code); };
const units = ['nanoseconds', 'milliseconds'];
export const limitations = Object.freeze([
  'Minimum positive difference is an observed step, not measured hardware resolution or an acceptance limit.',
  'Consecutive reads include call/loop overhead; JIT, scheduling, timer coarsening, floating-point subtraction and privacy jitter can change the distribution.',
  'No inference about elapsed-time accuracy, independent measurements, cross-process clocks or clocks on another host is made.',
  'Browser readings come from a fresh headless about:blank page, not the application workload or a cross-origin-isolated deployment.',
]);

export function settings(input = {}) {
  if (Object.keys(input).some(key => !Object.hasOwn(defaults, key))) fail('invalid-sampling-settings');
  const value = {...defaults, ...input};
  if (!Number.isSafeInteger(value.samples) || value.samples < 1 || value.samples > 100000 ||
      !Number.isSafeInteger(value.warmup) || value.warmup < 0 || value.warmup > 100000 ||
      !Number.isSafeInteger(value.samplingBudgetMs) || value.samplingBudgetMs < 1 || value.samplingBudgetMs > 10000)
    fail('invalid-sampling-settings');
  return value;
}

export function summarize(deltas, {unit, timedOut = false, expectedSamples = deltas?.length} = {}) {
  if (!units.includes(unit) || !Array.isArray(deltas) || deltas.length > 100000 ||
      !Number.isSafeInteger(expectedSamples) || expectedSamples < 1 || expectedSamples > 100000 || typeof timedOut !== 'boolean')
    fail('invalid-clock-observation');
  let positive = 0, zero = 0, negative = 0, missing = 0, minimum = null, maximum = null;
  for (const delta of deltas) {
    if (typeof delta !== 'number' || !Number.isFinite(delta)) missing++;
    else if (delta < 0) negative++;
    else if (delta === 0) zero++;
    else { positive++; minimum = minimum === null ? delta : Math.min(minimum, delta); maximum = maximum === null ? delta : Math.max(maximum, delta); }
  }
  const issues = [];
  if (timedOut) issues.push('sampling-deadline');
  if (deltas.length !== expectedSamples) issues.push('incomplete-sample-count');
  if (missing) issues.push('missing-or-nonfinite-reading');
  if (negative) issues.push('nonmonotonic-reading');
  if (!positive) issues.push('no-positive-deltas');
  return {status: issues.length ? 'inconclusive' : 'observed', unit, deltaSamples: deltas.length,
    positiveDeltas: positive, zeroDeltas: zero, negativeDeltas: negative, missingDeltas: missing,
    minimumPositiveDelta: minimum, maximumPositiveDelta: maximum, issues,
    hardwareResolution: null, acceptanceEvidence: false};
}

/** Count-bounded sampling; elapsed budget is independent of the sampled clock. */
export function sampleClock(read, config = defaults, budgetClock = () => Date.now()) {
  const {samples, warmup, samplingBudgetMs} = settings(config);
  const started = budgetClock(), deltas = [];
  let previous = read(), timedOut = false;
  for (let index = 0; index < warmup + samples; index++) {
    if (index % 256 === 0 && budgetClock() - started >= samplingBudgetMs) { timedOut = true; break; }
    const next = read();
    let delta = null;
    if (typeof next === 'bigint' && typeof previous === 'bigint') {
      const difference = next - previous;
      if (difference <= BigInt(Number.MAX_SAFE_INTEGER) && difference >= -BigInt(Number.MAX_SAFE_INTEGER)) delta = Number(difference);
    } else if (typeof next === 'number' && typeof previous === 'number' && Number.isFinite(next) && Number.isFinite(previous))
      delta = next - previous;
    if (index >= warmup) deltas.push(Number.isFinite(delta) ? delta : null);
    previous = next;
  }
  return {deltas, timedOut};
}

export async function bounded(work, milliseconds, signal) {
  if (!Number.isSafeInteger(milliseconds) || milliseconds < 1 || milliseconds > 60000) fail('invalid-operation-deadline');
  if (signal?.aborted) fail('interrupted');
  let timer, aborted;
  try {
    return await Promise.race([Promise.resolve().then(work), new Promise((_, reject) => {
      timer = setTimeout(() => reject(new Error('operation-deadline')), milliseconds);
      aborted = () => reject(new Error('interrupted'));
      signal?.addEventListener('abort', aborted, {once: true});
    })]);
  } finally { clearTimeout(timer); signal?.removeEventListener('abort', aborted); }
}

/** Synchronous cleanup precedes embedding listeners that may process.exit().
 * Only track process handles this probe created; never infer unrelated PIDs. */
export function ownClockProcesses(onSignal) {
  const kills = new Set();
  const terminate = () => { for (const kill of [...kills]) kill(); };
  const handlers = Object.fromEntries(['SIGINT', 'SIGTERM'].map(name => [name, () => {
    terminate(); onSignal(name);
  }]));
  for (const [name, handler] of Object.entries(handlers)) process.prependListener(name, handler);
  return {
    track(child, group = false) {
      const kill = () => {
        if (!child?.pid) return;
        if (group) {
          // A reaped leader can leave live descendants in its still-owned PGID.
          try { process.kill(-child.pid, 'SIGKILL'); return; } catch (_) { /* owned direct-child fallback */ }
        }
        if (child.exitCode !== null || child.signalCode !== null) return;
        try { child.kill('SIGKILL'); } catch (_) { /* already gone or no kill permission */ }
      };
      if (kills.size) { kill(); fail('owned-process-capacity'); }
      kills.add(kill);
      return () => kills.delete(kill);
    },
    close() {
      terminate(); kills.clear();
      for (const [name, handler] of Object.entries(handlers)) process.removeListener(name, handler);
    },
  };
}

async function javaClock(config, signal, owners) {
  const source = fileURLToPath(new URL('./clock-resolution.java', import.meta.url));
  const env = {...process.env};
  for (const key of ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) delete env[key];
  const child = spawn('java', [source, String(config.samples), String(config.warmup), String(config.samplingBudgetMs)],
    {stdio: ['ignore', 'pipe', 'pipe'], env});
  const release = owners.track(child);
  const output = [], stderr = createHash('sha256'); let bytes = 0, stderrBytes = 0, overflow = false;
  child.stdout.on('data', value => { bytes += value.length; if (bytes <= 4 * 1024 * 1024) output.push(value); else { overflow = true; child.kill('SIGKILL'); } });
  child.stderr.on('data', value => { stderrBytes += value.length; stderr.update(value); if (stderrBytes > 1024 * 1024) { overflow = true; child.kill('SIGKILL'); } });
  let spawnFailed = false;
  child.on('error', () => { spawnFailed = true; });
  const exited = new Promise(resolve => child.once('close', status => resolve(status)));
  try {
    const status = await bounded(() => exited, 30000, signal);
    if (spawnFailed || status !== 0 || overflow) fail('jvm-probe-failed');
    const raw = JSON.parse(Buffer.concat(output).toString('utf8'));
    if (raw.clock !== 'System.nanoTime' || raw.unit !== 'nanoseconds' || !/^[A-Za-z0-9._+-]{1,64}$/.test(raw.runtimeVersion) ||
        !Array.isArray(raw.deltas) || raw.deltas.length > config.samples || raw.deltas.some(value => !Number.isSafeInteger(value)) || typeof raw.timedOut !== 'boolean')
      fail('invalid-jvm-observation');
    return {...raw, processDiagnostics: {stderrBytes, stderrSha256: stderr.digest('hex')}};
  } finally {
    if (child.exitCode === null && child.signalCode === null) child.kill('SIGKILL');
    try { await bounded(() => exited, 3000).catch(() => fail('jvm-cleanup-deadline')); }
    finally { release(); }
  }
}

async function browserClock(engine, playwright, config, signal, owners) {
  const env = {...process.env};
  if (engine === 'webkit' && process.platform === 'linux') {
    if (!env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR) fail('missing-webkit-environment');
    env.__EGL_VENDOR_LIBRARY_FILENAMES = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
    env.LIBGL_ALWAYS_SOFTWARE = 'true';
  }
  // Public BrowserServer owns a kill handle even if renderer evaluation stalls.
  // Its native browser process may have its own group; do not rely on Node's PID.
  const server = await playwright[engine].launchServer({headless: true, env, timeout: 15000,
    handleSIGINT: false, handleSIGTERM: false, handleSIGHUP: false});
  const release = owners.track(server.process(), true);
  try {
    return await bounded(async () => {
      const browser = await playwright[engine].connect(server.wsEndpoint(), {timeout: 10000});
      const page = await browser.newPage();
      await page.goto('about:blank', {timeout: 10000});
      const raw = await page.evaluate(({samples, warmup, samplingBudgetMs}) => {
        const deltas = [], started = Date.now();
        let previous = performance.now(), timedOut = false;
        for (let index = 0; index < warmup + samples; index++) {
          if (index % 256 === 0 && Date.now() - started >= samplingBudgetMs) { timedOut = true; break; }
          const next = performance.now();
          if (index >= warmup) deltas.push(next - previous);
          previous = next;
        }
        return {deltas, timedOut, crossOriginIsolated: globalThis.crossOriginIsolated === true};
      }, config);
      return {clock: 'performance.now', engine, unit: 'milliseconds', runtimeVersion: browser.version(), ...raw};
    }, 25000, signal);
  } finally {
    // Kill is a public Playwright operation that terminates the owned group and
    // closes its protocol server; it also interrupts any outstanding evaluation.
    try {
      await bounded(() => server.kill(), 5000).catch(() => {
        const owned = server.process();
        if (owned?.pid) {
          try { process.kill(-owned.pid, 'SIGKILL'); } catch (_) { owned.kill('SIGKILL'); }
        }
        fail('browser-cleanup-deadline');
      });
    } finally { release(); }
  }
}

export async function runClocks(destination, config = defaults) {
  config = settings(config);
  if (!process.env.IN_NIX_SHELL) fail('nix-environment-required');
  const fd = openSync(destination, 'wx', 0o600), abort = new AbortController();
  let interrupted;
  const owners = ownClockProcesses(name => { interrupted = name; abort.abort(); });
  const write = value => appendFileSync(fd, JSON.stringify({...value, acceptanceEvidence: false}) + '\n');
  let complete = true;
  try {
    write({kind: 'clock-diagnostic-start', schemaVersion: 1, acceptanceEvidence: false, config,
      platform: process.platform, architecture: process.arch, nodeVersion: process.versions.node,
      fixtureSha256: Object.fromEntries(['clock-resolution.mjs', 'clock-resolution.java'].map(name => [name, hash(readFileSync(new URL(name, import.meta.url)))])), limitations});
    let playwright;
    const collect = [
      ['node', () => ({clock: 'process.hrtime.bigint', unit: 'nanoseconds', runtimeVersion: process.versions.node,
        ...sampleClock(() => process.hrtime.bigint(), config)})],
      ['jvm', () => javaClock(config, abort.signal, owners)],
      ...['chromium', 'webkit'].map(engine => [engine, async () => {
        if (!playwright) { verifyPlaywrightPin(process.env.PLAYWRIGHT_DRIVER_PATH); playwright = createRequire(import.meta.url)(process.env.PLAYWRIGHT_DRIVER_PATH); }
        return browserClock(engine, playwright, config, abort.signal, owners);
      }]),
    ];
    for (const [target, work] of collect) {
      if (abort.signal.aborted) fail('interrupted');
      try {
        const raw = await work();
        const summary = summarize(raw.deltas, {unit: raw.unit, timedOut: raw.timedOut, expectedSamples: config.samples});
        write({kind: 'clock-raw', target, ...raw});
        write({kind: 'clock-summary', target, ...summary});
        if (summary.status !== 'observed') complete = false;
      } catch (_) { complete = false; write({kind: 'clock-failure', target, code: abort.signal.aborted ? 'interrupted' : 'probe-unavailable-or-failed'}); }
    }
    write({kind: 'clock-diagnostic-end', complete, acceptanceEvidence: false});
    return complete;
  } catch (_) {
    write({kind: 'clock-diagnostic-failure', code: interrupted ?? 'collection-failed', acceptanceEvidence: false});
    return false;
  } finally {
    owners.close();
    closeSync(fd);
    if (interrupted) process.exitCode = interrupted === 'SIGINT' ? 130 : 143;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv.length !== 3) fail('new-clock-report-path-required');
    const complete = await runClocks(process.argv[2]);
    console.log(JSON.stringify({kind: 'clock-diagnostic-result', complete, acceptanceEvidence: false}));
    if (!complete) process.exitCode ||= 1;
  } catch (_) { console.error('clock-diagnostic-failed'); process.exitCode ||= 1; }
}
