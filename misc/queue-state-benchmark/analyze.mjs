import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory, scenariosArg = 'roundtrip,batch,contended'] = process.argv.slice(2);
const combined = join(directory, 'trials.jsonl');
const rows = existsSync(combined)
  ? readFileSync(combined, 'utf8').trim().split(/\r?\n/).map(JSON.parse)
  : readdirSync(directory).filter(name => name.endsWith('.jsonl')).map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
const scenarios = scenariosArg.split(',');
assert.ok(scenarios.length > 0 && new Set(scenarios).size === scenarios.length);
assert.ok(scenarios.every(value => ['roundtrip', 'batch', 'contended'].includes(value)));
assert.equal(rows.length, scenarios.length * 6);
const metrics = ['operationsPerSecond', 'bytesPerOperation'];
const liveCounters = rows.some(row => 'allocationCounterValid' in row);
assert.ok(!liveCounters || rows.every(row => 'allocationCounterValid' in row), 'Mixed allocation-counter protocols');
for (const row of rows) {
  assert.equal(row.correct, true);
  assert.ok(row.operations > 0 && row.elapsedSeconds > 0 && row.seconds > 0);
  for (const metric of metrics) assert.ok(Number.isFinite(row[metric]) && row[metric] > 0);
  assert.equal(row.operationsPerSecond, row.operations / row.elapsedSeconds, row.label);
  assert.equal(row.bytesPerOperation, row.allocatedBytes / row.operations, row.label);
  if ('allocationCounterValid' in row) {
    assert.equal(row.allocationCounterValid, true, row.label);
    assert.equal(row.threadsAliveAtSnapshot, row.expectedLiveThreads, row.label);
    const workers = row.scenario === 'contended' ? 5 : 0;
    assert.equal(row.workerSelfAllocatedBytes.length, workers, row.label);
    assert.equal(row.expectedLiveThreads, workers * 2, row.label);
    assert.equal(row.escapingControlBytesPerThread, 65536, row.label);
    assert.ok(row.mainSelfAllocatedBytes >= row.escapingControlBytesPerThread, row.label);
    assert.ok(row.workerSelfAllocatedBytes.every(value => value >= row.escapingControlBytesPerThread), row.label);
    const selfTotal = row.mainSelfAllocatedBytes + row.workerSelfAllocatedBytes.reduce((sum, value) => sum + value, 0);
    assert.equal(row.selfAllocatedBytes, selfTotal, row.label);
    assert.ok(row.allocatedBytes >= selfTotal, row.label);
  }
}
const results = scenarios.map(scenario => {
  const versions = Object.fromEntries(['baseline', 'candidate'].map(version => {
    const trials = [1, 2, 3].map(round => {
      const matching = rows.filter(row => row.label === `${scenario}-${version}-${round}`);
      assert.equal(matching.length, 1);
      assert.equal(matching[0].scenario, scenario);
      assert.equal(matching[0].seconds, rows[0].seconds);
      return matching[0];
    });
    return [version, Object.fromEntries(metrics.map(metric => {
      const values = trials.map(row => row[metric]).toSorted((a, b) => a - b);
      return [metric, {median: values[1], min: values[0], max: values[2]}];
    }))];
  }));
  return {scenario, ...versions, ratios: Object.fromEntries(metrics.map(metric =>
    [metric, versions.candidate[metric].median / versions.baseline[metric].median]))};
});
const summary = {trials: rows.length, repetitions: 3, seconds: rows[0].seconds,
  allocationCounterProtocol: liveCounters
    ? 'live warmup and measured workers; self-byte lower bound and escaping controls'
    : 'legacy: contended allocation is provisional after worker termination',
  statistics: 'median and range of independent JVM trials; no confidence interval', results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
