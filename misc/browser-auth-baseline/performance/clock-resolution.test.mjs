import assert from 'node:assert/strict';
import test from 'node:test';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {spawn} from 'node:child_process';
import {setTimeout as pause} from 'node:timers/promises';
import {bounded, ownClockProcesses, runClocks, sampleClock, settings, summarize} from './clock-resolution.mjs';

test('zero/coarse clocks expose observed differences without inferring hardware resolution', () => {
  const zero = summarize([0, 0, 0], {unit: 'milliseconds'});
  assert.equal(zero.status, 'inconclusive'); assert.equal(zero.minimumPositiveDelta, null);
  assert.deepEqual(zero.issues, ['no-positive-deltas']);
  const coarse = summarize([0, 10, 0, 20, 10], {unit: 'nanoseconds'});
  assert.equal(coarse.minimumPositiveDelta, 10); assert.equal(coarse.maximumPositiveDelta, 20);
  assert.equal(coarse.zeroDeltas, 2); assert.equal(coarse.positiveDeltas, 3);
  assert.equal(coarse.hardwareResolution, null); assert.equal(coarse.acceptanceEvidence, false);
  assert.equal(Object.hasOwn(coarse, 'measurementResolutionVerified'), false);
});

test('nonmonotonic, missing and truncated samples cannot become verified resolution', () => {
  const result = summarize([1, -1, null, NaN, Infinity], {unit: 'nanoseconds', expectedSamples: 8, timedOut: true});
  assert.equal(result.status, 'inconclusive'); assert.equal(result.minimumPositiveDelta, 1);
  assert.equal(result.negativeDeltas, 1); assert.equal(result.missingDeltas, 3);
  assert.deepEqual(result.issues, ['sampling-deadline', 'incomplete-sample-count', 'missing-or-nonfinite-reading', 'nonmonotonic-reading']);
  const fractional = summarize([0, 0.0999999999999, 0.100000000001], {unit: 'milliseconds'});
  assert.equal(fractional.minimumPositiveDelta, 0.0999999999999, 'do not round away floating-point evidence');
  assert.equal(fractional.hardwareResolution, null);
});

test('sampling excludes exact warmup, preserves zero/negative steps and bounds a stuck clock', () => {
  const readings = [0n, 10n, 20n, 20n, 25n, 24n];
  const raw = sampleClock(() => readings.shift(), {samples: 3, warmup: 2, samplingBudgetMs: 10}, () => 0);
  assert.deepEqual(raw, {deltas: [0, 5, -1], timedOut: false});
  const stuck = sampleClock(() => 0, {samples: 4, warmup: 0, samplingBudgetMs: 1}, () => 0);
  assert.deepEqual(stuck.deltas, [0, 0, 0, 0]);
  const missing = sampleClock(() => undefined, {samples: 2, warmup: 0, samplingBudgetMs: 1}, () => 0);
  assert.deepEqual(missing.deltas, [null, null]);
  let time = 0;
  const expired = sampleClock(() => 0, {samples: 4, warmup: 0, samplingBudgetMs: 1}, () => time++);
  assert.deepEqual(expired, {deltas: [], timedOut: true});
});

test('sampling rejects unbounded parameters and never loses BigInt integer precision', () => {
  for (const input of [{samples: 100001}, {samples: 0}, {warmup: -1}, {samplingBudgetMs: Infinity}, {secret: 1}])
    assert.throws(() => settings(input), /invalid/);
  let count = 0;
  const raw = sampleClock(() => ++count === 1 ? 0n : BigInt(Number.MAX_SAFE_INTEGER) + 1n,
    {samples: 1, warmup: 0, samplingBudgetMs: 1}, () => 0);
  assert.deepEqual(raw.deltas, [null]);
  assert.throws(() => summarize([], {unit: 'ticks'}), /invalid/);
});

test('outer deadlines and interruption are explicit, and exclusive output is preserved', async () => {
  await assert.rejects(bounded(() => new Promise(() => {}), 10), /operation-deadline/);
  const aborted = new AbortController(); aborted.abort();
  await assert.rejects(bounded(() => 1, 10, aborted.signal), /interrupted/);
  assert.equal(await bounded(() => 7, 100), 7);
  const root = mkdtempSync(path.join(tmpdir(), 'clock-diagnostic-test-'));
  try {
    const file = path.join(root, 'report.jsonl'); writeFileSync(file, 'existing');
    await assert.rejects(runClocks(file), /EEXIST/);
    assert.equal(readFileSync(file, 'utf8'), 'existing');
  } finally { rmSync(root, {recursive: true, force: true}); }
});

test('owned cleanup registration preserves existing signal listeners', () => {
  const existing = () => {};
  process.on('SIGTERM', existing);
  const before = process.listeners('SIGTERM');
  const first = ownClockProcesses(() => {}), second = ownClockProcesses(() => {});
  const release = first.track({pid: null});
  assert.throws(() => first.track({pid: null}), /owned-process-capacity/);
  release();
  first.close();
  assert.ok(process.listeners('SIGTERM').includes(existing));
  assert.equal(process.listeners('SIGTERM').length, before.length + 1);
  second.close();
  assert.deepEqual(process.listeners('SIGTERM'), before);
  process.removeListener('SIGTERM', existing);
});

function alive(pid) {
  if (process.platform === 'linux') {
    try { if (/\) Z /.test(readFileSync(`/proc/${pid}/stat`, 'utf8'))) return false; }
    catch (_) { return false; }
  }
  try { process.kill(pid, 0); return true; } catch (_) { return false; }
}
for (const signal of ['SIGINT', 'SIGTERM']) for (const group of [false, true])
test(`synchronous ${group ? 'leader-exited group' : 'direct-child'} cleanup precedes an earlier immediate-exit ${signal} listener`, {timeout: 20000}, async () => {
  const module = new URL('./clock-resolution.mjs', import.meta.url).href;
  const exitCode = signal === 'SIGINT' ? 130 : 143;
  const groupedCode = `const{spawn}=require('node:child_process');
    const child=spawn(process.execPath,['-e','console.log("ready");setInterval(()=>{},1000)'],{stdio:['ignore','pipe','ignore']});
    child.stdout.once('data',()=>{console.log(JSON.stringify({descendant:child.pid}));process.exit(0);});`;
  const code = `import{spawn}from'node:child_process';import{ownClockProcesses}from ${JSON.stringify(module)};
    process.on(${JSON.stringify(signal)},()=>process.exit(${exitCode}));
    const owners=ownClockProcesses(()=>{});
    const child=spawn(process.execPath,['-e',${JSON.stringify(group ? groupedCode : 'console.log("ready");setInterval(()=>{},1000)')}],{detached:${group},stdio:['ignore','pipe','ignore']});owners.track(child,${group});
    console.log(JSON.stringify({child:child.pid}));
    const exited=new Promise(resolve=>child.once('exit',resolve));
    const output=await new Promise(resolve=>child.stdout.once('data',bytes=>resolve(bytes.toString())));
    ${group ? 'await exited;console.log(JSON.stringify({ready:true,leaderExitCode:child.exitCode,descendant:JSON.parse(output).descendant}));' : 'console.log(JSON.stringify({ready:true}));'}
    setInterval(()=>{},1000);`;
  const harness = spawn(process.execPath, ['--input-type=module', '-e', code], {detached: true, stdio: ['ignore', 'pipe', 'pipe']});
  const owned = {}; let buffered = '', errors = '';
  harness.stderr.on('data', bytes => { errors += bytes; });
  const exited = new Promise(resolve => harness.once('close', status => resolve(status)));
  const ready = new Promise((resolve, reject) => {
    harness.once('error', reject);
    harness.stdout.on('data', bytes => {
      buffered += bytes;
      for (;;) {
        const newline = buffered.indexOf('\n'); if (newline < 0) break;
        const row = JSON.parse(buffered.slice(0, newline)); buffered = buffered.slice(newline + 1);
        Object.assign(owned, row); if (row.ready) resolve();
      }
    });
    harness.once('close', () => { if (!owned.ready) reject(new Error(`Cleanup fixture exited before ready: ${errors}`)); });
  });
  try {
    await bounded(() => ready, 10000);
    if (group) { assert.equal(owned.leaderExitCode, 0); assert.equal(alive(owned.descendant), true); }
    harness.kill(signal);
    assert.equal(await bounded(() => exited, 5000), exitCode);
    const deadline = Date.now() + 3000;
    const children = [owned.child, owned.descendant].filter(Boolean);
    while (children.some(alive) && Date.now() < deadline) await pause(20);
    for (const pid of children) assert.equal(alive(pid), false, 'owned child or group descendant survived immediate parent exit');
  } finally {
    if (group && owned.child && [owned.child, owned.descendant].filter(Boolean).some(alive)) try { process.kill(-owned.child, 'SIGKILL'); } catch (_) {}
    if (alive(harness.pid)) try { process.kill(-harness.pid, 'SIGKILL'); } catch (_) {}
    for (const pid of [owned.child, owned.descendant]) if (pid && alive(pid)) try { process.kill(pid, 'SIGKILL'); } catch (_) {}
    await bounded(() => exited, 3000).catch(() => {});
  }
});
