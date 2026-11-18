import assert from 'node:assert/strict';
import {readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory] = process.argv.slice(2);
const rows = readdirSync(directory).filter(name => name.endsWith('.jsonl'))
  .map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
assert.equal(rows.length, 24);
const metrics = ['messagesPerSecond', 'bytesPerMessage', 'p99Nanos'];
for (const row of rows) {
  assert.equal(row.correct, true);
  for (const key of metrics) assert.ok(Number.isFinite(row[key]) && row[key] > 0);
  assert.ok(row.samples > 0 && row.messages > 0);
}
const results = ['short', 'ascii', 'unicode', 'fragmented'].map(scenario => {
  const settings = rows.filter(row => row.scenario === scenario).map(row =>
    [row.seconds, row.chunks, row.characters, row.sampleStride ?? 256, row.sampleCapacity ?? (1 << 20)]);
  settings.forEach(value => assert.deepEqual(value, settings[0], `Mismatched workload settings: ${scenario}`));
  const summaries = Object.fromEntries(['baseline', 'scanner'].map(version => {
    const trials = [1, 2, 3].map(round => {
      const matches = rows.filter(row => row.label === `${scenario}-${version}-${round}`);
      assert.equal(matches.length, 1);
      assert.equal(matches[0].scenario, scenario);
      return matches[0];
    });
    return [version, Object.fromEntries(metrics.map(metric => {
      const values = trials.map(row => row[metric]).toSorted((a, b) => a - b);
      return [metric, {median: values[1], min: values[0], max: values[2]}];
    }))];
  }));
  const ratios = Object.fromEntries(metrics.map(metric => [metric,
    summaries.scanner[metric].median / summaries.baseline[metric].median]));
  return {scenario, ...summaries, ratios,
    withinGuide: ratios.messagesPerSecond >= .95 && ratios.bytesPerMessage <= 1.10 && ratios.p99Nanos <= 1.10};
});
const summary = {trials: rows.length, repetitions: 3,
  scope: 'single-thread synthetic text accumulation, sampled operation p99, no confidence interval', results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
