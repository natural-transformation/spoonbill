import assert from 'node:assert/strict';
import {readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory] = process.argv.slice(2);
const rows = readdirSync(directory).filter(name => name.endsWith('.jsonl'))
  .map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
assert.equal(rows.length, 12);
const metrics = ['pullsPerSecond', 'bytesPerPull', 'p99Nanos'];
const results = [1, 128].map(sources => {
  const summaries = Object.fromEntries(['baseline', 'model'].map(version => {
    const trials = [1, 2, 3].map(round => {
      const selected = rows.filter(row => row.label === `sources${sources}-${version}-${round}`);
      assert.equal(selected.length, 1);
      const row = selected[0];
      assert.equal(row.correct, true);
      assert.equal(row.sources, sources);
      assert.equal(row.seconds, 2);
      assert.equal(row.sampleStride, 1024);
      assert.equal(row.sampleCapacity, 1 << 22);
      metrics.forEach(metric => assert.ok(Number.isFinite(row[metric]) && row[metric] >= 0));
      return row;
    });
    return [version, Object.fromEntries(metrics.map(metric => {
      const values = trials.map(row => row[metric]).toSorted((a, b) => a - b);
      return [metric, {median: values[1], min: values[0], max: values[2]}];
    }))];
  }));
  const throughputRatio = summaries.model.pullsPerSecond.median / summaries.baseline.pullsPerSecond.median;
  const p99Ratio = summaries.model.p99Nanos.median / summaries.baseline.p99Nanos.median;
  const allocationWithinGuide = summaries.model.bytesPerPull.median <= summaries.baseline.bytesPerPull.median * 1.1;
  return {sources, ...summaries, throughputRatio, p99Ratio, allocationWithinGuide,
    withinGuide: throughputRatio >= .95 && p99Ratio <= 1.1 && allocationWithinGuide};
});
const summary = {trials: 12, repetitions: 3, scope: 'direct coupled input only, sampled p99, no confidence interval', results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
