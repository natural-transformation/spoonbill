import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';
const [directory] = process.argv.slice(2);
const combined = join(directory, 'trials.jsonl');
const rows = existsSync(combined)
  ? readFileSync(combined, 'utf8').trim().split(/\r?\n/).map(JSON.parse)
  : readdirSync(directory).filter(x => x.endsWith('.jsonl')).map(x => JSON.parse(readFileSync(join(directory, x), 'utf8')));
assert.equal(rows.length, 27);
const median = values => [...values].sort((a, b) => a - b)[1];
const stats = values => ({median: median(values), min: Math.min(...values), max: Math.max(...values)});
const approximately = (actual, expected) => assert.ok(Math.abs(actual - expected) <= Math.max(1, Math.abs(expected)) * 1e-12);
const results = ['hub', 'sort', 'buffer'].map(scenario => {
  let settings;
  const versions = Object.fromEntries(['original', 'current', 'candidate'].map(variant => {
    const selected = [1, 2, 3].map(round => {
      const label = `${scenario}-${variant}-${round}`;
      const found = rows.filter(row => row.label === label);
      assert.equal(found.length, 1, label);
      const row = found[0];
      assert.equal(row.protocol, 'stream-compat-v3');
      assert.equal(row.scenario, scenario);
      assert.equal(row.correct, true);
      assert.equal(row.threadAlive, true);
      assert.equal(row.allocationCounter, 'current-live-thread');
      assert.equal(row.deliveries, row.rounds * (scenario === 'buffer' ? 1 : 2));
      for (const field of ['rounds', 'deliveries', 'elapsedSeconds', 'allocatedBytes', 'deliveriesPerSecond', 'bytesPerDelivery', 'roundsPerSecond', 'bytesPerRound'])
        assert.ok(Number.isFinite(row[field]) && row[field] >= 0, `${label}: ${field}`);
      assert.ok(row.rounds > 0 && row.elapsedSeconds > 0);
      approximately(row.deliveriesPerSecond, row.deliveries / row.elapsedSeconds);
      approximately(row.roundsPerSecond, row.rounds / row.elapsedSeconds);
      approximately(row.bytesPerDelivery, row.allocatedBytes / row.deliveries);
      approximately(row.bytesPerRound, row.allocatedBytes / row.rounds);
      const current = ['scenario', 'warmupSeconds', 'seconds', 'java', 'processors', 'maxHeapBytes'].map(key => row[key]);
      if (settings) assert.deepEqual(current, settings); else settings = current;
      const stderr = join(directory, label + '.stderr');
      if (existsSync(stderr)) assert.equal(readFileSync(stderr, 'utf8').trim(), '', label + ' stderr');
      return row;
    });
    return [variant, Object.fromEntries(['deliveriesPerSecond', 'bytesPerDelivery', 'roundsPerSecond', 'bytesPerRound'].map(metric => [metric, stats(selected.map(row => row[metric]))]))];
  }));
  const comparisons = Object.fromEntries(['original', 'current'].map(reference => {
    const throughputRatio = versions.candidate.deliveriesPerSecond.median / versions[reference].deliveriesPerSecond.median;
    const allocationRatio = versions.candidate.bytesPerDelivery.median / versions[reference].bytesPerDelivery.median;
    return [reference, {throughputRatio, allocationRatio, passesGuide: throughputRatio >= 0.95 && allocationRatio <= 1.10}];
  }));
  return {scenario, ...versions, comparisons};
});
const summary = {
  protocol: 'stream-compat-v3',
  scope: 'short compatibility-path cost screen; not whole-semantics equivalence, p99, or final aggregate acceptance',
  trials: rows.length,
  blocks: 3,
  statistics: 'three independent JVM medians/ranges per variant and cell; balanced cyclic order; no confidence interval',
  allocation: 'live main-thread allocated bytes; timed source/delivery thread assertions and separate all-continuation smoke',
  passesGuide: results.every(result => Object.values(result.comparisons).every(comparison => comparison.passesGuide)),
  results
};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(summary, null, 2) + '\n');
console.log(JSON.stringify(summary, null, 2));
