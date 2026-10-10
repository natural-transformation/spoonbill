import test from 'node:test';
import assert from 'node:assert/strict';
import {commandFor, observe, collect} from './run.mjs';
import {createHash} from 'node:crypto';
import {spawn} from 'node:child_process';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {evaluate, parseEvidence} from './analyze.mjs';
import {syntheticFixture} from './test-fixture.mjs';

const request = {runId: 'synthetic-test', cell: {id: 'cell'}, pass: 'latency', block: 4,
  variant: 'baseline', orderInBlock: 0, artifactSha256: 'a'.repeat(64), completedWorkContractSha256: 'b'.repeat(64)};

test('missing collectors and unfrozen reference contracts cannot launch collection', async () => {
  assert.throws(() => commandFor({}, 'feature', 'latency'), /Missing collector/);
  assert.throws(() => commandFor({feature: {latency: 'java -cp example'}}, 'feature', 'latency'), /argument array/);
  const manifest = JSON.parse(readFileSync(new URL('./manifest.json', import.meta.url), 'utf8'));
  await assert.rejects(collect(manifest, {}, {}, '/unreachable-output', 'comparison'), /unfrozen/);
});

test('fresh child process output keeps raw diagnostics and cannot substitute workload identity', async () => {
  const raw = [];
  const command = [process.execPath, '-e', `
    let input = ''; process.stdin.on('data', c => input += c); process.stdin.on('end', () => {
      const r = JSON.parse(input); console.error('synthetic JIT diagnostic');
      console.log(JSON.stringify({kind: 'sample', artifactSha256: r.artifactSha256,
        completedWorkContractSha256: r.completedWorkContractSha256, cell: 'forged', block: 99}));
    });`];
  const sample = await observe(command, request, 5000, row => raw.push(row));
  assert.equal(sample.cell, 'cell'); assert.equal(sample.block, 4);
  assert.equal(sample.processId, 'synthetic-test');
  assert(raw[0].stderr.includes('synthetic JIT diagnostic'));
  assert.equal(raw[0].status, 0);
});

test('failed collectors preserve raw output without retrying or manufacturing a sample', async () => {
  const raw = [];
  await assert.rejects(observe([process.execPath, '-e', 'console.error("synthetic failure"); process.exit(3)'],
    request, 5000, row => raw.push(row)), /Collector failed/);
  assert.equal(raw.length, 1); assert.equal(raw[0].status, 3);
  assert(raw[0].stderr.includes('synthetic failure'));
});

test('ambiguous sample output and wrong artifact identity fail collection', async () => {
  for (const output of [
    JSON.stringify({kind: 'sample', artifactSha256: 'wrong'}),
    JSON.stringify({kind: 'sample'}) + '\n' + JSON.stringify({kind: 'sample'}),
  ]) {
    const raw = [];
    await assert.rejects(observe([process.execPath, '-e', `process.stdout.write(${JSON.stringify(output)})`],
      request, 5000, row => raw.push(row)));
    assert.equal(raw.length, 1);
  }
});

function pipeline() {
  const data = syntheticFixture();
  const digest = path => createHash('sha256').update(readFileSync(new URL(path, import.meta.url))).digest('hex');
  for (const evidence of [data.comparison, data.calibration]) {
    evidence.metadata.shared.runnerSha256 = digest('./run.mjs');
    evidence.metadata.shared.analyzerSha256 = digest('./analyze.mjs');
  }
  const context = {provenance: data.comparison.metadata.provenance, shared: data.comparison.metadata.shared,
    kind: 'forged', mode: 'forged', manifestSha256: 'forged', startedAt: 'forged', durationSeconds: -1};
  const commands = Object.fromEntries(['feature', 'historical', 'candidate'].map(source =>
    [source, Object.fromEntries(data.manifest.protocol.passes.map(pass => [pass, ['synthetic-collector', source, pass]]))]));
  const requests = [];
  const observer = async (command, request, timeout, saveRaw) => {
    requests.push(request);
    assert.equal(command[1], request.source);
    assert.equal(command[2], request.pass);
    assert.ok(timeout > 0);
    assert.deepEqual(request.cell.metrics, data.manifest.cells[0].metrics);
    assert.equal(request.cell.dimensions.payloadBytes, 128);
    const candidate = request.source === 'candidate';
    const sample = data[candidate ? 'comparison' : 'calibration'].samples.find(row =>
      row.pass === request.pass && row.variant === request.variant && row.block === request.block);
    saveRaw({request, status: 0, stdout: 'synthetic raw data', stderr: ''});
    return {...structuredClone(sample), artifactSha256: request.artifactSha256, processId: request.runId};
  };
  return {...data, context, commands, requests, observer};
}

test('full synthetic collections preserve exact balanced pairs, provenance and calibration sources', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'spoonbill-pipeline-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  const data = pipeline(), files = {};
  for (const mode of ['comparison', 'calibration']) {
    const destination = join(directory, `${mode}.jsonl`);
    const result = await collect(data.manifest, data.commands, data.context, destination, mode,
      {observer: data.observer, progress: () => {}});
    assert.equal(result.observations, 180);
    files[mode] = parseEvidence(readFileSync(destination, 'utf8'));
    assert.equal(files[mode].metadata.kind, 'metadata');
    assert.equal(files[mode].metadata.mode, mode);
    assert.notEqual(files[mode].metadata.manifestSha256, 'forged');
    const raw = readFileSync(`${destination}.processes.jsonl`, 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(raw.length, 180);
    for (const row of raw) {
      const request = row.request;
      assert.equal(request.source, mode === 'calibration' || request.variant === 'baseline' ? 'feature' : 'candidate');
      assert.equal(request.orderInBlock, request.variant === 'baseline' ? request.block % 2 : 1 - request.block % 2);
    }
  }
  assert.equal(new Set(data.requests.map(request => request.runId)).size, 360);
  assert.equal(evaluate(data.manifest, files.comparison, files.calibration).status, 'pass');
});

test('existing destination or raw log is never overwritten and no collector starts', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'spoonbill-exclusive-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  for (const suffix of ['', '.processes.jsonl']) {
    const data = pipeline(), destination = join(directory, suffix ? 'raw-conflict.jsonl' : 'output-conflict.jsonl');
    writeFileSync(destination + suffix, 'keep me');
    await assert.rejects(collect(data.manifest, data.commands, data.context, destination, 'comparison',
      {observer: data.observer, progress: () => {}}), /EEXIST/);
    assert.equal(readFileSync(destination + suffix, 'utf8'), 'keep me');
    assert.equal(data.requests.length, 0);
  }
});

test('collection stops on first failed observation and preserves samples, raw failure and incomplete marker', async t => {
  const directory = mkdtempSync(join(tmpdir(), 'spoonbill-failure-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  const data = pipeline(), destination = join(directory, 'failed.jsonl');
  let calls = 0;
  const observer = async (...args) => {
    if (++calls === 3) { args[3]({request: args[1], status: 3, stdout: 'partial', stderr: 'failure'}); throw new Error('synthetic failure'); }
    return data.observer(...args);
  };
  await assert.rejects(collect(data.manifest, data.commands, data.context, destination, 'comparison',
    {observer, progress: () => {}}), /synthetic failure/);
  assert.equal(calls, 3);
  const content = readFileSync(destination, 'utf8');
  const records = content.trim().split('\n').map(JSON.parse);
  assert.equal(records.filter(record => record.kind === 'sample').length, 2);
  assert.equal(records.at(-1).kind, 'failure');
  assert.throws(() => parseEvidence(content), /Incomplete/);
  const raw = readFileSync(`${destination}.processes.jsonl`, 'utf8').trim().split('\n').map(JSON.parse);
  assert.equal(raw.length, 3); assert.equal(raw[2].stdout, 'partial');
});

test('actual child timeout terminates inherited-pipe descendants and retains partial output', {timeout: 15000}, async t => {
  const directory = mkdtempSync(join(tmpdir(), 'spoonbill-timeout-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  const heartbeat = join(directory, 'heartbeat');
  const child = `const fs = require('node:fs'); let count = 0;
    const beat = () => fs.writeFileSync(${JSON.stringify(heartbeat)}, String(++count));
    beat(); console.log('descendant-ready'); setInterval(beat, 10);`;
  const parent = `require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(child)}], {stdio: 'inherit'});
    setInterval(() => {}, 1000);`;
  const raw = [], start = Date.now();
  // This deadline includes two real Node startups. Keep a bounded allowance for
  // loaded correctness CI; it is unrelated to the benchmark acceptance protocol.
  await assert.rejects(observe([process.execPath, '-e', parent], request, 10000, row => raw.push(row)), /Collector failed/);
  assert.ok(Date.now() - start < 14000);
  assert.equal(raw.length, 1);
  assert.ok(raw[0].stdout.includes('descendant-ready'));
  assert.ok(raw[0].error.includes('deadline'));
  assert.equal(raw[0].groupKillSent, true);
  const last = readFileSync(heartbeat, 'utf8');
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal(readFileSync(heartbeat, 'utf8'), last);
});

test('a successful parent cannot leave an inherited-pipe child running', async () => {
  const child = 'console.log("descendant-ready"); setInterval(() => {}, 1000)';
  const parent = `const child = require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(child)}], {stdio: ['ignore', 'inherit', 'inherit']});
    child.unref(); setTimeout(() => process.exit(0), 200);`;
  const raw = [];
  await assert.rejects(observe([process.execPath, '-e', parent], request, 3000, row => raw.push(row)), /Collector failed/);
  assert.equal(raw[0].remainingProcessGroup, true);
  assert.equal(raw[0].groupKillSent, true);
  assert.ok(raw[0].error.includes('left child'));
});

test('truncated collector output is preserved explicitly and can never become a successful sample', async () => {
  const raw = [];
  await assert.rejects(observe([process.execPath, '-e', 'process.stdout.write("x".repeat(17 * 1024 * 1024)); setInterval(() => {}, 1000)'],
    request, 3000, row => raw.push(row)), /Collector failed/);
  assert.equal(raw[0].truncated, true);
  assert.equal(Buffer.byteLength(raw[0].stdout), 16 * 1024 * 1024);
  assert.ok(raw[0].observedBytes.stdout > 16 * 1024 * 1024);
});

async function waitFor(check, label) {
  const deadline = Date.now() + 6000;
  while (Date.now() < deadline) {
    const value = check();
    if (value) return value;
    await new Promise(resolve => setTimeout(resolve, 10));
  }
  throw new Error(`Timed out waiting for ${label}`);
}
function readJsonIfReady(path) {
  try { return JSON.parse(readFileSync(path, 'utf8')); } catch (_) { return undefined; }
}
function terminated(pid) {
  try { process.kill(pid, 0); }
  catch (error) { if (error.code === 'ESRCH') return true; throw error; }
  // Some Linux test environments do not immediately reap orphan zombies. A
  // zombie is terminated; it cannot execute or contaminate another measurement.
  if (process.platform === 'linux') {
    try {
      const stat = readFileSync(`/proc/${pid}/stat`, 'utf8');
      return /^[ZX]\s/.test(stat.slice(stat.lastIndexOf(')') + 1).trim());
    } catch (error) { if (error.code === 'ENOENT') return true; throw error; }
  }
  return false;
}
function cleanupGroup(pid) {
  // An exited group leader can still have live descendants in its owned group.
  if (!pid) return;
  try { process.kill(-pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
}
function startHarness(args) {
  const child = spawn(process.execPath, args, {detached: true, stdio: ['ignore', 'pipe', 'pipe']});
  let diagnostics = '';
  child.stdout.on('data', bytes => { diagnostics = (diagnostics + bytes).slice(-8192); });
  child.stderr.on('data', bytes => { diagnostics = (diagnostics + bytes).slice(-8192); });
  const exited = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('close', (code, signal) => resolve({code, signal, diagnostics}));
  });
  return {child, exited};
}
function signalFixture(directory) {
  const groupFile = join(directory, 'group.json'), heartbeat = join(directory, 'heartbeat');
  const collector = join(directory, 'collector.cjs');
  const descendant = `const fs = require('node:fs'); let count = 0;
    const beat = () => fs.writeFileSync(${JSON.stringify(heartbeat)}, String(++count));
    beat(); process.send('ready'); setInterval(beat, 10);`;
  writeFileSync(collector, `const fs = require('node:fs'); let input = '';
    process.stdin.on('data', bytes => input += bytes);
    process.stdin.on('end', () => {
      const request = JSON.parse(input);
      if (request.pass === 'latency' && request.block === 0 && request.variant === 'baseline') {
        console.log(JSON.stringify({kind: 'sample', artifactSha256: request.artifactSha256,
          completedWorkContractSha256: request.completedWorkContractSha256}));
        return;
      }
      const child = require('node:child_process').spawn(process.execPath, ['-e', ${JSON.stringify(descendant)}],
        {stdio: ['ignore', 'inherit', 'inherit', 'ipc']});
      const group = {collector: process.pid, descendant: child.pid, ready: false};
      fs.writeFileSync(${JSON.stringify(groupFile)}, JSON.stringify(group));
      child.once('message', () => { group.ready = true; fs.writeFileSync(${JSON.stringify(groupFile)}, JSON.stringify(group)); });
      console.error('synthetic collector active'); setInterval(() => {}, 1000);
    });`);
  return {collector, groupFile, heartbeat};
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  for (const collection of [false, true]) test(`${collection ? 'collection' : 'observation'} cleanup precedes an existing ${signal} listener that exits immediately`, {timeout: 15000}, async t => {
    const directory = mkdtempSync(join(tmpdir(), 'spoonbill-immediate-exit-'));
    const fixture = signalFixture(directory), marker = join(directory, 'application-exited');
    const destination = join(directory, 'interrupted.jsonl'), script = join(directory, 'harness.mjs');
    const data = pipeline();
    const commands = Object.fromEntries(['feature', 'historical', 'candidate'].map(source =>
      [source, Object.fromEntries(data.manifest.protocol.passes.map(pass => [pass, [process.execPath, fixture.collector]]))]));
    writeFileSync(script, `import {appendFileSync, writeFileSync} from 'node:fs';
      import {observe, collect} from ${JSON.stringify(new URL('./run.mjs', import.meta.url).href)};
      process.on(${JSON.stringify(signal)}, () => {
        writeFileSync(${JSON.stringify(marker)}, 'earlier application listener ran');
        process.exit(0);
      });
      ${collection
        ? `await collect(${JSON.stringify(data.manifest)}, ${JSON.stringify(commands)}, ${JSON.stringify(data.context)},
            ${JSON.stringify(destination)}, 'comparison');`
        : `await observe([process.execPath, ${JSON.stringify(fixture.collector)}], ${JSON.stringify(request)}, 30000,
            row => appendFileSync(${JSON.stringify(destination)}, JSON.stringify(row) + '\\n'));`}`);
    const harness = startHarness([script]);
    t.after(async () => {
      cleanupGroup(harness.child.pid);
      cleanupGroup(readJsonIfReady(fixture.groupFile)?.collector);
      await harness.exited;
      rmSync(directory, {recursive: true, force: true});
    });
    const running = await waitFor(() => readJsonIfReady(fixture.groupFile)?.ready && readJsonIfReady(fixture.groupFile), 'immediate-exit collector descendant');
    harness.child.kill(signal);
    const exited = await harness.exited;
    assert.equal(exited.code, 0, exited.diagnostics); // The application still owns its chosen exit status.
    assert.equal(exited.signal, null);
    assert.equal(readFileSync(marker, 'utf8'), 'earlier application listener ran');
    // Check before teardown; it must not hide a leaked collector or descendant.
    await waitFor(() => terminated(running.collector) && terminated(running.descendant), 'cleanup before immediate exit');
    const last = readFileSync(fixture.heartbeat, 'utf8');
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(readFileSync(fixture.heartbeat, 'utf8'), last);
    if (collection) {
      const content = readFileSync(destination, 'utf8'), records = content.trim().split('\n').map(JSON.parse);
      assert.equal(records[0].kind, 'metadata');
      assert.equal(records.filter(row => row.kind === 'sample').length, 1);
      assert.equal(records.some(row => row.kind === 'completion'), false);
      assert.throws(() => parseEvidence(content), /Incomplete/);
      // Forced process.exit prevents promise continuations from draining raw
      // output or appending the normal interruption/failure record.
      assert.equal(records.some(row => row.kind === 'failure'), false);
      const raw = readFileSync(`${destination}.processes.jsonl`, 'utf8').trim().split('\n').map(JSON.parse);
      assert.equal(raw.length, 1);
      assert.equal(raw[0].harnessSignal, null);
    }
  });

  test(`library observation handles harness ${signal} after a concurrent observation releases its listeners`, {timeout: 15000}, async t => {
    const directory = mkdtempSync(join(tmpdir(), 'spoonbill-observe-signal-'));
    const fixture = signalFixture(directory), rawFile = join(directory, 'raw.jsonl');
    const readyFile = join(directory, 'harness-ready.json'), resultFile = join(directory, 'result.json');
    const script = join(directory, 'harness.mjs');
    writeFileSync(script, `import {appendFileSync, writeFileSync} from 'node:fs';
      import {observe} from ${JSON.stringify(new URL('./run.mjs', import.meta.url).href)};
      const signals = ['SIGINT', 'SIGTERM'];
      const before = Object.fromEntries(signals.map(signal => [signal, process.listenerCount(signal)]));
      let externalSignals = 0; const external = () => externalSignals++;
      for (const signal of signals) process.on(signal, external);
      const request = ${JSON.stringify(request)};
      const raw = row => appendFileSync(${JSON.stringify(rawFile)}, JSON.stringify(row) + '\\n');
      const slow = observe([process.execPath, ${JSON.stringify(fixture.collector)}], request, 30000, raw);
      const quickScript = 'console.log(' + JSON.stringify(JSON.stringify({kind:'sample', artifactSha256:request.artifactSha256,
        completedWorkContractSha256:request.completedWorkContractSha256})) + ')';
      const quick = observe([process.execPath, '-e', quickScript], {...request, runId:'quick'}, 5000, raw);
      const concurrent = Object.fromEntries(signals.map(signal => [signal, process.listenerCount(signal) - before[signal]]));
      await quick;
      writeFileSync(${JSON.stringify(readyFile)}, JSON.stringify({concurrent,
        afterQuick:Object.fromEntries(signals.map(signal => [signal, process.listenerCount(signal) - before[signal]]))}));
      try { await slow; throw new Error('Interrupted observation unexpectedly succeeded'); }
      catch (error) {
        writeFileSync(${JSON.stringify(resultFile)}, JSON.stringify({name:error.name, signal:error.signal, exitCode:error.exitCode,
          externalSignals, remaining:Object.fromEntries(signals.map(signal => [signal, process.listenerCount(signal) - before[signal]]))}));
      } finally { for (const signal of signals) process.removeListener(signal, external); }`);
    const harness = startHarness([script]);
    t.after(async () => {
      cleanupGroup(harness.child.pid);
      cleanupGroup(readJsonIfReady(fixture.groupFile)?.collector);
      await harness.exited;
      rmSync(directory, {recursive: true, force: true});
    });
    const running = await waitFor(() => readJsonIfReady(fixture.groupFile)?.ready && readJsonIfReady(fixture.groupFile), 'owned descendant');
    const readiness = await waitFor(() => readJsonIfReady(readyFile), 'concurrent observation cleanup');
    assert.deepEqual(readiness.concurrent, {SIGINT: 2, SIGTERM: 2});
    assert.deepEqual(readiness.afterQuick, {SIGINT: 2, SIGTERM: 2});
    harness.child.kill(signal);
    const exited = await harness.exited;
    assert.equal(exited.code, signal === 'SIGINT' ? 130 : 143, exited.diagnostics);
    assert.equal(exited.signal, null);
    const result = readJsonIfReady(resultFile);
    assert.equal(result.name, 'HarnessInterrupted'); assert.equal(result.signal, signal);
    assert.equal(result.externalSignals, 1);
    assert.deepEqual(result.remaining, {SIGINT: 1, SIGTERM: 1}, 'Only embedding application listeners may remain');
    const raw = readFileSync(rawFile, 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(raw.length, 2);
    assert.equal(raw.find(row => row.request.runId === 'quick').harnessSignal, null);
    const interrupted = raw.find(row => row.request.runId === request.runId);
    assert.equal(interrupted.harnessSignal, signal); assert.equal(interrupted.groupKillSent, true);
    await waitFor(() => terminated(running.collector) && terminated(running.descendant), 'collector group termination');
    const last = readFileSync(fixture.heartbeat, 'utf8');
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(readFileSync(fixture.heartbeat, 'utf8'), last);
  });

  test(`CLI harness ${signal} kills its collector group and preserves incomplete evidence`, {timeout: 15000}, async t => {
    const directory = mkdtempSync(join(tmpdir(), 'spoonbill-collect-signal-'));
    const fixture = signalFixture(directory), data = pipeline();
    const destination = join(directory, 'interrupted.jsonl');
    const commands = Object.fromEntries(['feature', 'historical', 'candidate'].map(source =>
      [source, Object.fromEntries(data.manifest.protocol.passes.map(pass => [pass, [process.execPath, fixture.collector]]))]));
    const inputs = ['manifest', 'commands', 'context'].map(name => join(directory, `${name}.json`));
    [data.manifest, commands, data.context].forEach((value, index) => writeFileSync(inputs[index], JSON.stringify(value)));
    const harness = startHarness([fileURLToPath(new URL('./run.mjs', import.meta.url)), ...inputs, destination, 'comparison']);
    t.after(async () => {
      cleanupGroup(harness.child.pid);
      cleanupGroup(readJsonIfReady(fixture.groupFile)?.collector);
      await harness.exited;
      rmSync(directory, {recursive: true, force: true});
    });
    const running = await waitFor(() => readJsonIfReady(fixture.groupFile)?.ready && readJsonIfReady(fixture.groupFile), 'CLI collector descendant');
    harness.child.kill(signal);
    const exited = await harness.exited;
    assert.equal(exited.code, signal === 'SIGINT' ? 130 : 143, exited.diagnostics);
    assert.equal(exited.signal, null);
    const content = readFileSync(destination, 'utf8'), records = content.trim().split('\n').map(JSON.parse);
    assert.equal(records[0].kind, 'metadata');
    assert.equal(records.filter(row => row.kind === 'sample').length, 1);
    assert.equal(records.some(row => row.kind === 'completion'), false);
    assert.equal(records.at(-1).kind, 'failure'); assert.equal(records.at(-1).signal, signal);
    assert.equal(records.at(-1).observations, 1);
    assert.throws(() => parseEvidence(content), /Incomplete/);
    const raw = readFileSync(`${destination}.processes.jsonl`, 'utf8').trim().split('\n').map(JSON.parse);
    assert.equal(raw.length, 2); assert.equal(raw[1].harnessSignal, signal);
    assert.equal(raw[1].groupKillSent, true); assert.ok(raw[1].stderr.includes('synthetic collector active'));
    await waitFor(() => terminated(running.collector) && terminated(running.descendant), 'CLI collector group termination');
    const last = readFileSync(fixture.heartbeat, 'utf8');
    await new Promise(resolve => setTimeout(resolve, 100));
    assert.equal(readFileSync(fixture.heartbeat, 'utf8'), last);
  });
}
