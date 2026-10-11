import test from 'node:test';
import assert from 'node:assert/strict';
import {allocationCapability, summarizeChromiumSnapshot, summarizeWebKitSnapshot, verifyPlaywrightPin} from './browser-heap.mjs';

test('WebKit retained-size summary counts all node self estimates without leaking names/edges', () => {
  const result = summarizeWebKitSnapshot({version: 3, type: 'Inspector', nodes: [0, 0, 0, 0, 1, 32, 1, 0, 2, 64, 2, 0],
    nodeClassNames: ['root', 'synthetic-private-string'], edges: []});
  assert.equal(result.bytes, 96); assert.equal(result.nodeCount, 3);
  assert.equal(JSON.stringify(result).includes('synthetic-private-string'), false);
  assert.ok(Object.isFrozen(result));
});
test('V8 parser discovers self-size position instead of assuming WebKit layout', () => {
  const result = summarizeChromiumSnapshot({snapshot: {meta: {node_fields: ['id', 'self_size', 'name']}, node_count: 2},
    nodes: [1, 48, 0, 2, 96, 1], strings: ['private']});
  assert.equal(result.bytes, 144); assert.equal(result.nodeCount, 2);
});
test('schema drift, malformed sizes and non-pinned bridge fail closed', () => {
  for (const snapshot of [{version: 2, type: 'Inspector', nodes: []}, {version: 3, type: 'Inspector', nodes: [1, 8]},
    {version: 3, type: 'Inspector', nodes: [1, -1, 0, 0]}]) assert.throws(() => summarizeWebKitSnapshot(snapshot));
  assert.throws(() => summarizeChromiumSnapshot({snapshot: {meta: {node_fields: ['id']}, node_count: 1}, nodes: [1]}));
  assert.throws(() => verifyPlaywrightPin('/host-playwright'), /Nix/);
});
test('retained memory is never relabeled as cumulative JavaScript allocation', () => {
  assert.equal(allocationCapability.supported, false); assert.equal(allocationCapability.value, null);
  assert.ok(Object.isFrozen(allocationCapability));
});
