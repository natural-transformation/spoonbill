// Explicit Linux native-profiler validation; never silently skips another host.
import test from 'node:test';
import assert from 'node:assert/strict';
import {execFileSync, spawn} from 'node:child_process';
import {once} from 'node:events';
import {mkdtempSync, readFileSync, readdirSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {ownedProcessMemory, parseHeaptrackHistogram} from './native-memory.mjs';
import {findNixTool} from './profiling-capabilities.mjs';
import {observe} from './run.mjs';

test('Linux profiling environment supplies the declared tools without host fallback', () => {
  assert.equal(process.platform, 'linux', 'Use an actual Linux profiling host');
  assert.equal(process.env.SPOONBILL_PROFILING_ENVIRONMENT, '1');
  for (const name of ['jcmd', 'jfr', 'heaptrack', 'heaptrack_print', 'time', 'ps', 'lsns'])
    assert.ok(findNixTool(name), `Missing declared Nix profiler: ${name}`);
});

test('Linux owned-process RSS sensor observes touched synthetic memory', {timeout: 15000}, async () => {
  assert.equal(process.platform, 'linux', 'Use an actual Linux host in the profiling shell; this test never skips');
  assert.ok(process.execPath.startsWith('/nix/store/'));
  const child = spawn(process.execPath, ['-e',
    'global.keep = Buffer.alloc(64 * 1024 * 1024, 1); process.send("ready"); setInterval(() => {}, 1000)'],
    {stdio: ['ignore', 'ignore', 'ignore', 'ipc']});
  try {
    await once(child, 'message', {signal: AbortSignal.timeout(5000)});
    const value = await ownedProcessMemory(child);
    assert.equal(value.status, 'measured'); assert.ok(value.rssBytes >= 64 * 1024 * 1024);
    assert.ok(value.peakRssBytes >= 64 * 1024 * 1024);
    console.log(JSON.stringify({sensor: 'owned-process-procfs', ...value}));
  } finally {
    if (child.exitCode === null && child.signalCode === null) { const stopped = once(child, 'close'); child.kill('SIGKILL'); await stopped; }
  }
});

test('Linux heaptrack records an exact synthetic native allocator request', {timeout: 45000}, async t => {
  assert.equal(process.platform, 'linux', 'heaptrack validation requires an actual Linux host');
  const tracker = findNixTool('heaptrack'), printer = findNixTool('heaptrack_print');
  assert.ok(tracker && printer, 'The repository profiling shell must supply heaptrack and heaptrack_print');
  const directory = mkdtempSync(join(tmpdir(), 'spoonbill-allocator-probe-'));
  t.after(() => rmSync(directory, {recursive: true, force: true}));
  // The harness marker is synthetic test metadata, never benchmark provenance.
  const request = {runId: 'allocator-capability-probe', cell: {id: 'synthetic-allocator'}, pass: 'memory',
    variant: 'baseline', block: 0, orderInBlock: 0, artifactSha256: '0'.repeat(64), completedWorkContractSha256: '0'.repeat(64)};
  const script = 'global.keep = Buffer.alloc(64 * 1024 * 1024, 1); console.log(JSON.stringify(' +
    JSON.stringify({kind: 'sample', artifactSha256: request.artifactSha256, completedWorkContractSha256: request.completedWorkContractSha256}) + '));';
  const raw = [];
  await observe([tracker, '-o', join(directory, 'allocation'), process.execPath, '-e', script], request, 30000, row => raw.push(row));
  const profiles = readdirSync(directory).filter(name => /\.(gz|zst)$/.test(name));
  assert.equal(profiles.length, 1, 'Expected exactly one owned heaptrack profile');
  const histogram = join(directory, 'histogram.tsv');
  execFileSync(printer, ['-H', histogram, join(directory, profiles[0])], {timeout: 15000, maxBuffer: 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe']});
  const data = readFileSync(histogram, 'utf8');
  assert.match(data, /^67108864\s+[1-9]\d*$/m, 'The known 64 MiB malloc-family request was not captured');
  const summary = parseHeaptrackHistogram(data);
  assert.ok(summary.nativeAllocatorRequestedBytes >= 64 * 1024 * 1024);
  assert.equal(summary.logicalJavaScriptAllocatedBytes, null);
  console.log(JSON.stringify({sensor: 'heaptrack-native-allocator', ...summary}));
});
