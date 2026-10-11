import test from 'node:test';
import assert from 'node:assert/strict';
import {findNixTool} from './profiling-capabilities.mjs';

test('profiler discovery never falls back to host tools or evaluates shell input', () => {
  assert.equal(findNixTool('node', '/usr/bin:/bin:/usr/local/bin'), null);
  assert.equal(findNixTool('heaptrack', ''), null);
  assert.throws(() => findNixTool('heaptrack; echo unsafe'));
  assert.throws(() => findNixTool('../time'));
});
