import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import test from 'node:test';
import {classpathIdentity, legacyPlan, legacyWorkContract, parseLegacyRecord} from './legacy-collector.mjs';

// Synthetic records exercise parser rejection only; these tests create no
// measurement samples and supply no operation ceilings or baseline evidence.
function fixture(core = false) {
  const request = {cell: {id: core ? 'historical-core-lifecycle' : 'historical-echo-c8-p128',
    group: core ? 'historical-core' : 'historical-transport', dimensions: {concurrency: core ? 1 : 8, payloadBytes: 128}},
    pass: 'latency', variant: 'baseline', block: 3, artifactSha256: 'a'.repeat(64),
    work: {warmupOperations: 40000, measuredOperations: 80000}};
  const record = {schema: core ? 'spoonbill-core-guarded-session-v1' : 'spoonbill-websocket-transport-v1',
    identity: request.artifactSha256, variant: 'baseline', block: '3', javaVersion: 'synthetic',
    elapsedNs: 1000000, ...(core ? {operationLatencyNs: {p50: 10, p95: 20, p99: 30}, operationsPerSecond: 80000000,
      warmupIterations: 40000, iterations: 80000, completedOperations: 80000, guardsOpened: 120000, guardsClosed: 120000,
      remainingGuards: 0, remainingInputs: 0, remainingApplications: 0, initialOutputBytes: 160000,
      teardownMeasured: true, executionContext: 'direct-serial'} : {
      roundTripLatencyNs: {p50: 10, p95: 20, p99: 30}, messagesPerSecond: 80000000,
      connections: 8, payloadBytes: 128, warmupPerConnection: 5000, messages: 80000,
      setupLatencyNs: Array(8).fill(100), teardownMeasured: false})};
  return {request, record};
}

test('transport uses exact total work divided across connections, never rounded', () => {
  const {request, record} = fixture();
  assert.deepEqual(legacyPlan(request).args.slice(-4), ['8', '128', '5000', '10000']);
  assert.equal(parseLegacyRecord(JSON.stringify(record), request).metrics.operationsPerSecond, 80000000);
  request.work.measuredOperations++;
  assert.throws(() => legacyPlan(request), /divide/);
});

test('identity, omitted exchanges, malformed timing and duplicate output are rejected', () => {
  for (const mutate of [r => r.messages--, r => r.warmupPerConnection--, r => r.payloadBytes++,
    r => r.setupLatencyNs.pop(), r => r.identity = 'b'.repeat(64), r => r.block = 4,
    r => r.roundTripLatencyNs.p50 = 0, r => r.messagesPerSecond /= 2]) {
    const {request, record} = fixture(); mutate(record);
    assert.throws(() => parseLegacyRecord(JSON.stringify(record), request));
  }
  const {request, record} = fixture();
  assert.throws(() => parseLegacyRecord(`${JSON.stringify(record)}\n${JSON.stringify(record)}`, request), /exactly one/);
});

test('core completion requires balanced ownership and delivered live output', () => {
  const {request, record} = fixture(true);
  assert.ok(parseLegacyRecord(JSON.stringify(record), request));
  for (const mutate of [r => r.guardsClosed--, r => r.remainingInputs++, r => r.initialOutputBytes = 0, r => r.teardownMeasured = false]) {
    const copy = structuredClone(record); mutate(copy);
    assert.throws(() => parseLegacyRecord(JSON.stringify(copy), request), /completed-work/);
  }
});

test('unsupported passes and Pekko cannot be represented by a legacy workload', () => {
  const {request} = fixture();
  for (const pass of ['memory', 'operations']) assert.throws(() => legacyPlan({...request, pass}), /unimplemented/);
  assert.throws(() => legacyPlan({...request, cell: {...request.cell, group: 'pekko-transport'}}), /supports/);
  assert.throws(() => legacyPlan({...request, work: {...request.work, warmupOperations: 0}}), /positive/);
  assert.equal(legacyWorkContract(request).teardownMeasured, false);
});

test('artifact hash identifies contents/order and detects changed output independently of checkout path', () => {
  const directory = mkdtempSync(join(tmpdir(), 'legacy-artifact-test-'));
  try {
    const first = join(directory, 'first'), second = join(directory, 'second');
    for (const path of [first, second]) { mkdirSync(path); writeFileSync(join(path, 'a.class'), 'synthetic bytecode'); }
    assert.equal(classpathIdentity(first), classpathIdentity(second));
    writeFileSync(join(second, 'a.class'), 'changed synthetic bytecode');
    assert.notEqual(classpathIdentity(first), classpathIdentity(second));
    assert.throws(() => classpathIdentity(''), /nonempty/);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});
