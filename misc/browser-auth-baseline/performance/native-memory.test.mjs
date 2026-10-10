import test from 'node:test';
import assert from 'node:assert/strict';
import {ownedProcessMemory, parseHeaptrackHistogram, parseLinuxMemory, parseLinuxProcessIdentity} from './native-memory.mjs';

test('procfs RSS high water and current proportional RSS remain separate metrics', () => {
  const observed = parseLinuxMemory('VmHWM:\t100 kB\nVmRSS:\t80 kB\n', 'Rss: 80 kB\nPss: 50 kB\nPrivate_Dirty: 25 kB\n');
  assert.equal(observed.peakRssBytes, 102400); assert.equal(observed.rssBytes, 81920);
  assert.equal(observed.proportionalRssBytes, 51200); assert.ok(Object.isFrozen(observed));
  assert.equal(Object.hasOwn(observed, 'browserAggregatePeakRssBytes'), false);
});
test('missing, changed units and non-owned process requests fail closed', async () => {
  assert.throws(() => parseLinuxMemory('VmHWM: 100 bytes', 'Rss: 10 kB'));
  await assert.rejects(ownedProcessMemory({pid: process.pid}), /owned ChildProcess/);
  assert.throws(() => parseLinuxProcessIdentity('broken'));
});
test('process identity parser handles spaces and parentheses in process names', () => {
  const fields = ['S', '123', ...Array(17).fill('0'), '456'];
  assert.deepEqual(parseLinuxProcessIdentity(`10 (owned (probe)) ${fields.join(' ')}`), {ppid: 123, startTimeTicks: 456, state: 'S'});
});
test('malloc histogram is allocator traffic, never a fabricated JS allocation figure', () => {
  const result = parseHeaptrackHistogram('8\t10\n32\t3\n');
  assert.equal(result.allocations, 13); assert.equal(result.nativeAllocatorRequestedBytes, 176);
  assert.equal(result.logicalJavaScriptAllocatedBytes, null);
  assert.throws(() => parseHeaptrackHistogram(''));
  assert.throws(() => parseHeaptrackHistogram('rounded 1.5 MiB'));
});
