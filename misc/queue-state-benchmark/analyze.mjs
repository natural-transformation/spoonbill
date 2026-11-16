import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory] = process.argv.slice(2);
const combined = join(directory, 'trials.jsonl');
const rows = existsSync(combined)
  ? readFileSync(combined, 'utf8').trim().split(/\r?\n/).map(JSON.parse)
  : readdirSync(directory).filter(name => name.endsWith('.jsonl')).map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
assert.equal(rows.length, 18);
const scenarios = ['roundtrip', 'batch', 'contended'];
const metrics = ['operationsPerSecond', 'bytesPerOperation'];
for (const row of rows) {
  assert.equal(row.correct, true);
  assert.ok(row.operations > 0 && row.elapsedSeconds > 0 && row.seconds > 0);
  for (const metric of metrics) assert.ok(Number.isFinite(row[metric]) && row[metric] > 0);
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
  statistics: 'median and range of independent JVM trials; no confidence interval', results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
