import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {chromiumAllocationCapability, summarizeChromiumAllocationTrace, collectChromiumAllocationTrace} from './chromium-allocation.mjs';

const fields = ['id', 'function_info_index', 'count', 'size', 'children'];
const snapshot = (tree = [1, 0, 1, 8, [2, 1, 3, 96, [], 3, 1, 2, 64, []]]) => ({
  snapshot: {meta: {trace_node_fields: fields}, trace_function_count: 2}, trace_tree: tree,
  nodes: [], strings: ['synthetic-private-page-string'],
});
class Session extends EventEmitter {
  constructor({chunks = [JSON.stringify(snapshot())], failDisable = false} = {}) {
    super(); this.chunks = chunks; this.failDisable = failDisable; this.calls = []; this.detached = false;
  }
  async send(command, parameters) {
    this.calls.push([command, parameters]);
    if (command === 'HeapProfiler.stopTrackingHeapObjects')
      for (const chunk of this.chunks) this.emit('HeapProfiler.addHeapSnapshotChunk', {chunk});
    if (command === 'HeapProfiler.disable' && this.failDisable) throw new Error('synthetic cleanup failure');
    return {};
  }
  async detach() { this.detached = true; }
}

test('trace totals include dead allocations even when the retained node list is empty', () => {
  const result = summarizeChromiumAllocationTrace(snapshot());
  assert.equal(result.allocatedBytes, 168);
  assert.equal(result.allocationCount, 6);
  assert.equal(result.traceNodeCount, 3);
  assert.equal(JSON.stringify(result).includes('synthetic-private-page-string'), false);
  assert.equal(result.browserAllocatedBytesPerOperation, null);
  assert.equal(result.supportedForAcceptance, false);
  assert.ok(Object.isFrozen(result));
});
test('trace layout follows metadata and sums each own-node counter exactly once', () => {
  const value = snapshot([8, [], 1, 0, 1]);
  value.snapshot.meta.trace_node_fields = ['size', 'children', 'id', 'function_info_index', 'count'];
  assert.equal(summarizeChromiumAllocationTrace(value).allocatedBytes, 8);
});
test('malformed metadata, overflowing counters, duplicate IDs and capture bounds fail closed', () => {
  const bad = [snapshot([]), snapshot([1, 0, -1, 8, []]), snapshot([1, 0, 1, 2 ** 32, []]),
    snapshot([1, 2, 1, 8, []]), snapshot([1, 0, 1, 8, [1, 0, 2, 16, []]]), snapshot([1, 0, 1, 8, {}])];
  for (const value of bad) assert.throws(() => summarizeChromiumAllocationTrace(value));
  const drift = snapshot(); drift.snapshot.meta.trace_node_fields = ['id', 'count'];
  assert.throws(() => summarizeChromiumAllocationTrace(drift));
  assert.throws(() => summarizeChromiumAllocationTrace(snapshot(), {maximumTraceNodes: 2}), /node limit/);
  assert.throws(() => summarizeChromiumAllocationTrace(snapshot(), {maximumTraceDepth: 1}), /depth/);
});
test('acknowledged workload is bracketed by tracking and all owned profiling state is released', async () => {
  const session = new Session(); let ran = false, signal;
  const result = await collectChromiumAllocationTrace(session, async controls => {
    ran = true; signal = controls.signal;
    assert.equal(signal.aborted, false);
    assert.deepEqual(session.calls.at(-1), ['HeapProfiler.startTrackingHeapObjects', {trackAllocations: true}]);
    await controls.collectGarbage();
  });
  assert.ok(ran); assert.equal(result.allocatedBytes, 168);
  assert.equal(session.calls.at(-1)[0], 'HeapProfiler.disable');
  assert.equal(signal.aborted, true); assert.equal(session.detached, true);
  assert.equal(session.listenerCount('HeapProfiler.addHeapSnapshotChunk'), 0);
});
test('failed workloads preserve their failure and still detach when profiler cleanup fails', async () => {
  const session = new Session({failDisable: true});
  const failure = new Error('synthetic workload failure');
  await assert.rejects(collectChromiumAllocationTrace(session, async () => { throw failure; }), error => error === failure);
  assert.equal(session.detached, true);
  assert.equal(session.calls.some(([name]) => name === 'HeapProfiler.stopTrackingHeapObjects'), false);
});
test('oversized/malformed snapshots expose no page text and release their buffers/session', async () => {
  for (const [chunks, options, expected] of [
    [['synthetic-private-text'], {}, /Malformed allocation snapshot JSON/],
    [['x'.repeat(2000)], {maximumSnapshotBytes: 1000}, /capture limit/],
  ]) {
    const session = new Session({chunks});
    await assert.rejects(collectChromiumAllocationTrace(session, async () => {}, options), error => {
      assert.match(error.message, expected); assert.ok(!error.message.includes('synthetic-private-text')); return true;
    });
    assert.equal(session.detached, true);
    assert.equal(session.listenerCount('HeapProfiler.addHeapSnapshotChunk'), 0);
  }
});
test('timeout aborts the callback signal and relinquishes profiling even if the callback never settles', async () => {
  const session = new Session(); let signal;
  await assert.rejects(collectChromiumAllocationTrace(session, controls => {
    signal = controls.signal; return new Promise(() => {});
  }, {maximumDurationMs: 20}), /timed out/);
  assert.equal(signal.aborted, true); assert.equal(session.detached, true);
});
test('experimental VM counters never certify the required cross-browser metric', () => {
  assert.equal(chromiumAllocationCapability.supportedForAcceptance, false);
  assert.equal(chromiumAllocationCapability.browserAllocatedBytesPerOperation, null);
  assert.equal(chromiumAllocationCapability.exactSourceAuditComplete, false);
});
