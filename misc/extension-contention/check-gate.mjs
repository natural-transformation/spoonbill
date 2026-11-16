import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

// The owner-confirmed pilot budget. Correctness checks must pass separately.
const [file, candidate = 'pilot'] = process.argv.slice(2);
assert.notEqual(candidate, 'baseline', 'The candidate must differ from the reference');
const summary = JSON.parse(readFileSync(file, 'utf8'));
const scenarios = ['isolated', 'contended', 'extensions', 'backlog'];
assert.deepEqual(summary.results.map(row => row.scenario).toSorted(), scenarios.toSorted());
assert.ok(Number.isInteger(summary.repetitions) && summary.repetitions >= 3,
  'Acceptance requires at least three trials per workload/version');
const gate = {throughputMin: .95, deliveryP99Max: 1.10, allocationMax: 1.10};
const median = (row, version, metric) => {
  const value = row[version]?.[metric]?.median;
  assert.ok(Number.isFinite(value) && value >= 0, `Invalid ${version}/${metric} in ${row.scenario}`);
  if (version === 'baseline') assert.ok(value > 0, `Zero baseline for ${metric}`);
  return value;
};
const rows = summary.results.map(row => {
  const ratio = metric => median(row, candidate, metric) / median(row, 'baseline', metric);
  const throughputRatio = ratio('notificationsPerSecond');
  const p99Ratio = ratio('deliveryP99Ms');
  const allocationRatio = ratio('bytesPerNotification');
  const metrics = version => ({throughput: row[version].notificationsPerSecond,
    p99: row[version].deliveryP99Ms, allocation: row[version].bytesPerNotification});
  return {scenario: row.scenario, throughputRatio, p99Ratio, allocationRatio,
    passed: throughputRatio >= gate.throughputMin && p99Ratio <= gate.deliveryP99Max && allocationRatio <= gate.allocationMax,
    main: metrics('baseline'), candidate: metrics(candidate)};
});
const passed = rows.every(row => row.passed);
console.log(JSON.stringify({passed, gate, rows}, null, 2));
if (!passed) process.exitCode = 1;
