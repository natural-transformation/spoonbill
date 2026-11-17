import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory, repetitionsArg = '3', scenariosArg = 'read,read-clock,write,snapshot,lease'] = process.argv.slice(2);
const repetitions = Number(repetitionsArg);
assert.ok(Number.isInteger(repetitions) && repetitions > 0);
const combined = join(directory, 'trials.jsonl');
const rows = existsSync(combined)
  ? readFileSync(combined, 'utf8').trim().split(/\r?\n/).map(JSON.parse)
  : readdirSync(directory).filter(name => name.endsWith('.jsonl')).map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
const allScenarios = ['read', 'read-clock', 'write', 'snapshot', 'lease'];
const scenarios = scenariosArg.split(',');
assert.ok(scenarios.length > 0 && new Set(scenarios).size === scenarios.length);
assert.ok(scenarios.every(scenario => allScenarios.includes(scenario)));
assert.equal(rows.length, scenarios.length * repetitions * 2);
for (const row of rows) {
  assert.equal(row.protocol, 'presentation-current-thread-v1');
  assert.equal(row.correct, true);
  assert.equal(row.sampleStride, 512);
  assert.ok(row.escapingControlBytes >= 65536 && row.warmupControlBytes >= 65536);
  assert.ok(Number.isSafeInteger(row.operations) && row.operations > 0);
  assert.ok(row.elapsedSeconds > 0 && row.samples > 0 && row.p99Nanos > 0);
  assert.equal(row.operationsPerSecond, row.operations / row.elapsedSeconds);
  assert.equal(row.bytesPerOperation, row.allocatedBytes / row.operations);
}
const metrics = ['operationsPerSecond', 'bytesPerOperation', 'p99Nanos'];
const distribution = values => {
  const sorted = values.toSorted((a, b) => a - b);
  return {median: (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2,
    min: sorted[0], max: sorted.at(-1)};
};
const results = scenarios.map(scenario => {
  let reference;
  const versions = Object.fromEntries(['baseline', 'candidate'].map(version => {
    const trials = Array.from({length: repetitions}, (_, index) => {
      const selected = rows.filter(row => row.label === `${scenario}-${version}-${index + 1}`);
      assert.equal(selected.length, 1);
      const row = selected[0];
      assert.equal(row.scenario, scenario);
      const settings = ['seconds', 'warmupSeconds', 'sampleStride', 'java', 'processors', 'maxHeapBytes'].map(key => row[key]);
      if (reference) assert.deepEqual(settings, reference); else reference = settings;
      for (const metric of metrics) assert.ok(Number.isFinite(row[metric]) && row[metric] >= 0);
      return row;
    });
    return [version, Object.fromEntries(metrics.map(metric => [metric, distribution(trials.map(row => row[metric]))]))];
  }));
  const throughputRatio = versions.candidate.operationsPerSecond.median / versions.baseline.operationsPerSecond.median;
  const p99Ratio = versions.candidate.p99Nanos.median / versions.baseline.p99Nanos.median;
  const allocationRatio = versions.baseline.bytesPerOperation.median < .001 ? null
    : versions.candidate.bytesPerOperation.median / versions.baseline.bytesPerOperation.median;
  const allocationWithinGuide = allocationRatio === null ? versions.candidate.bytesPerOperation.median < .001 : allocationRatio <= 1.1;
  return {scenario, ...versions, throughputRatio, p99Ratio, allocationRatio, allocationWithinGuide,
    withinGuide: throughputRatio >= .95 && p99Ratio <= 1.1 && allocationWithinGuide};
});
const summary = {trials: rows.length, repetitions,
  scope: repetitions < 3 ? 'correctness smoke; not acceptance'
    : scenarios.length < allScenarios.length ? 'targeted screen; not full acceptance' : 'five public-operation workloads',
  allocationProtocol: 'current live measuring thread; escaping allocation controls excluded from measured deltas',
  statistics: 'median/range of independent JVM trials; sampled p99; no confidence interval',
  passed: repetitions >= 3 && scenarios.length === allScenarios.length && results.every(row => row.withinGuide), results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
