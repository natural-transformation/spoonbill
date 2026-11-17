import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory, repeatsArg = '3', cellArgs] = process.argv.slice(2);
const repeats = Number(repeatsArg);
assert.ok(Number.isInteger(repeats) && repeats > 0);
const combined = join(directory, 'trials.jsonl');
const rows = existsSync(combined)
  ? readFileSync(combined, 'utf8').trim().split(/\r?\n/).map(JSON.parse)
  : readdirSync(directory).filter(name => name.endsWith('.jsonl')).map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
const cells = cellArgs ? cellArgs.split(',').map(value => {
  const [scenario, writers] = value.split(':');
  assert.ok(['leases', 'snapshots', 'guarded'].includes(scenario));
  assert.ok(Number.isInteger(Number(writers)) && Number(writers) > 0);
  return [scenario, Number(writers)];
}) : [['leases', 1], ['leases', 4], ['snapshots', 1], ['guarded', 1], ['guarded', 4]];
assert.equal(rows.length, cells.length * repeats * 2);
const median = values => {
  const sorted = [...values].sort((a, b) => a - b);
  return (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2;
};
const range = values => ({median: median(values), min: Math.min(...values), max: Math.max(...values)});
const settings = ['protocol', 'scenario', 'writers', 'warmupSeconds', 'seconds', 'java', 'processors', 'maxHeapBytes'];
const protocols = [...new Set(rows.map(row => row.protocol ?? 'ownership-cost-v1'))];
assert.equal(protocols.length, 1, 'Mixed measurement protocols');
const verified = protocols[0] === 'ownership-cost-v2';
const results = cells.map(([scenario, writers]) => {
  let expected;
  const versions = Object.fromEntries(['before', 'after'].map(variant => {
    const selected = Array.from({length: repeats}, (_, index) => {
      const label = `${scenario}-${writers}-${variant}-${index + 1}`;
      const found = rows.filter(row => row.label === label);
      assert.equal(found.length, 1, label);
      const row = found[0];
      assert.equal(row.correct, true, label);
      if (verified) {
        assert.equal(row.allocationVerified, true, label);
        assert.equal(row.workersAliveAtSample, row.writers, label);
        assert.equal(row.warmupWorkersAliveAtSample, row.writers, label);
        assert.ok(row.allocatedBytes >= row.producerAllocatedBytes, label);
        assert.ok(row.warmupAllocatedBytes >= row.warmupProducerAllocatedBytes, label);
      }
      assert.ok(Number.isSafeInteger(row.completed) && row.completed > 0, label);
      for (const field of ['elapsedSeconds', 'operationsPerSecond', 'allocatedBytes', 'bytesPerOperation'])
        assert.ok(Number.isFinite(row[field]) && row[field] >= 0, `${label}: ${field}`);
      const actual = settings.map(field => row[field]);
      if (expected) assert.deepEqual(actual, expected, `${label}: mismatched settings`);
      else expected = actual;
      return row;
    });
    return [variant, {
      operationsPerSecond: range(selected.map(row => row.operationsPerSecond)),
      bytesPerOperation: range(selected.map(row => row.bytesPerOperation)),
      producerBytesPerOperation: verified ? range(selected.map(row => row.producerBytesPerOperation)) : null,
    }];
  }));
  return {scenario, writers, ...versions,
    throughputRatio: versions.after.operationsPerSecond.median / versions.before.operationsPerSecond.median,
    allocationRatio: versions.before.bytesPerOperation.median < .001 ? null
      : versions.after.bytesPerOperation.median / versions.before.bytesPerOperation.median,
    allocationDeltaBytes: versions.after.bytesPerOperation.median - versions.before.bytesPerOperation.median,
  };
});
const summary = {protocol: protocols[0], scope: cellArgs ? 'targeted screen, not full acceptance' : 'full five-cell comparison', repeats, trials: rows.length,
  allocationStatus: verified ? 'verified while all producers were alive; self-counter cross-check passed'
    : 'provisional: global counter sampled after producer exit; do not use allocation for acceptance',
  statistics: 'median/range of independent JVM trials; no confidence interval',
  nearZeroAllocation: 'Ratios omitted when baseline allocation is below 0.001 bytes/operation; use the absolute delta.', results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
