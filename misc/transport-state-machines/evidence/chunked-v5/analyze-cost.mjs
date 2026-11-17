import assert from 'node:assert/strict';
import {readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory, kind] = process.argv.slice(2);
const scenarios = kind === 'frontend' ? ['control', 'property', 'patch', 'action']
  : kind === 'frame' ? ['small', 'large', 'fragmented', 'coalesced'] : ['small', 'large', 'fragmented', 'trailers'];
const rows = readdirSync(directory).filter(name => name.endsWith('.jsonl'))
  .map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
assert.equal(rows.length, 24);
const metrics = ['messagesPerSecond', 'bytesPerMessage', 'p99Nanos'];
const results = scenarios.map(scenario => {
  const selected = rows.filter(row => row.scenario === scenario);
  assert.equal(selected.length, 6);
  const settings = selected.map(row => [row.seconds, row.chunks, row.payloadBytes, row.sampleStride, row.sampleCapacity]);
  settings.forEach(value => assert.deepEqual(value, settings[0]));
  selected.forEach(row => {
    assert.equal(row.correct, true);
    assert.ok(row.samples > 0 && row.samples <= row.sampleCapacity && row.messages > 0);
    metrics.forEach(metric => assert.ok(Number.isFinite(row[metric]) && row[metric] > 0));
  });
  const summaries = Object.fromEntries(['baseline', 'model'].map(version => {
    const trials = [1, 2, 3].map(round => {
      const matches = selected.filter(row => row.label === `${scenario}-${version}-${round}`);
      assert.equal(matches.length, 1);
      return matches[0];
    });
    return [version, Object.fromEntries(metrics.map(metric => {
      const values = trials.map(row => row[metric]).toSorted((a, b) => a - b);
      return [metric, {median: values[1], min: values[0], max: values[2]}];
    }))];
  }));
  const ratios = Object.fromEntries(metrics.map(metric => [metric, summaries.model[metric].median / summaries.baseline[metric].median]));
  return {scenario, ...summaries, ratios,
    withinGuide: ratios.messagesPerSecond >= .95 && ratios.bytesPerMessage <= 1.10 && ratios.p99Nanos <= 1.10};
});
const summary = {trials: rows.length, repetitions: 3,
  scope: `${kind}: single-thread public-path synthetic workload; live-thread allocation; sampled operation p99; no confidence interval`, results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
