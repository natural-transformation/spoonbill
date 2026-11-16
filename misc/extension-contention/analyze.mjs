import assert from 'node:assert/strict';
import {existsSync, readFileSync, readdirSync, writeFileSync} from 'node:fs';
import {join} from 'node:path';

const [directory, repetitionsArg = '3', variantsArg = 'baseline,pilot'] = process.argv.slice(2);
const repetitions = Number(repetitionsArg);
const variants = variantsArg.split(',');
assert.equal(variants[0], 'baseline');
assert.ok(variants.length >= 2 && new Set(variants).size === variants.length);
assert.ok(variants.every(value => /^[a-z]+$/.test(value)));
const scenarios = ['isolated', 'contended', 'extensions', 'backlog'];
const packaged = join(directory, 'trials.jsonl');
const records = existsSync(packaged)
  ? readFileSync(packaged, 'utf8').trim().split(/\r?\n/).map(line => JSON.parse(line))
  : readdirSync(directory).filter(name => name.endsWith('.jsonl')).map(name => JSON.parse(readFileSync(join(directory, name), 'utf8')));
const rows = records.map(value => {
  const name = value.label;
  assert.equal(value.correct, true, name);
  assert.equal(value.callbacks, value.completed * value.extensions, name);
  assert.equal(value.delivery.count, value.completed, name);
  assert.equal(value.admission.count, value.completed, name);
  assert.equal(value.delivery.overflow, 0, name);
  assert.equal(value.admission.overflow, 0, name);
  for (const field of ['notificationsPerSecond', 'bytesPerNotification', 'allocatedBytesPerSecond', 'gcPauseTotalMs', 'gcPauseMaxMs']) {
    assert.ok(Number.isFinite(value[field]) && value[field] >= 0, `${name}: ${field}`);
  }
  return value;
});
assert.equal(rows.length, scenarios.length * variants.length * repetitions, 'Missing or extra trial');
const median = values => {
  const sorted = values.toSorted((a, b) => a - b);
  return (sorted[Math.floor((sorted.length - 1) / 2)] + sorted[Math.floor(sorted.length / 2)]) / 2;
};
const distribution = values => ({median: median(values), min: Math.min(...values), max: Math.max(...values)});
const metrics = {
  notificationsPerSecond: row => row.notificationsPerSecond,
  bytesPerNotification: row => row.bytesPerNotification,
  allocatedBytesPerSecond: row => row.allocatedBytesPerSecond,
  deliveryP99Ms: row => row.delivery.p99Ms,
  deliveryP999Ms: row => row.delivery.p999Ms,
  admissionP99Ms: row => row.admission.p99Ms,
  gcPauseMsPerSecond: row => row.gcPauseTotalMs / row.elapsedSeconds,
  gcPauseMaxMs: row => row.gcPauseMaxMs,
};
const results = scenarios.map(scenario => {
  const reference = rows.find(row => row.label === `${scenario}-baseline-1`);
  assert.ok(reference, `Missing baseline for ${scenario}`);
  const versions = Object.fromEntries(variants.map(version => {
    const trials = Array.from({length: repetitions}, (_, index) => {
      const selected = rows.filter(row => row.label === `${scenario}-${version}-${index + 1}`);
      assert.equal(selected.length, 1);
      for (const key of ['views', 'writers', 'extensions', 'windowPerView', 'callbackNanos', 'workers',
        'seconds', 'warmupSeconds', 'java', 'processors', 'maxHeapBytes']) {
        assert.equal(selected[0][key], reference[key], `Incomparable ${key} in ${selected[0].label}`);
      }
      return selected[0];
    });
    return [version, Object.fromEntries(Object.entries(metrics).map(([key, select]) => [key, distribution(trials.map(select))]))];
  }));
  const ratiosFor = version => Object.fromEntries(Object.keys(metrics).map(key => [key,
    versions.baseline[key].median === 0 ? null : versions[version][key].median / versions.baseline[key].median]));
  return {scenario, ...versions, ratios: variants.length === 2 ? ratiosFor(variants[1])
    : Object.fromEntries(variants.slice(1).map(version => [version, ratiosFor(version)]))};
});
const result = {repetitions, trials: rows.length, ...(variants.length > 2 ? {variants} : {}),
  statistics: 'median and range of independent JVM trials; no confidence interval', results};
writeFileSync(join(directory, 'summary.json'), JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify(result, null, 2));
