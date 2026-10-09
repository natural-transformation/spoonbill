import test from 'node:test';
import assert from 'node:assert/strict';
import {evaluate, validate, percentile, parseReport, permutationCalibration, policy} from './performance-regression.mjs';

function fixture({candidateScale = 1, sqlDelta = 0, blocks = 30, samples = 34} = {}) {
  const rows = [];
  for (let block = 0; block < blocks; block++) for (let iteration = 0; iteration < samples; iteration++)
    for (const variant of ['baseline', 'candidate']) {
      const scale = variant === 'candidate' ? candidateScale : 1;
      rows.push({scenario: 'fixture', variant, block, iteration, operations: 1, concurrency: 1,
        orderInBlock: (iteration + block + (variant === 'candidate' ? 1 : 0)) % 2,
        dbDelayMicros: 0, elapsedNanos: (10000 + block * 13 + iteration) * scale,
        processCpuNanos: 2000 * scale, allocatedBytes: 3000 * scale,
        sqlExecutions: 4 + (variant === 'candidate' ? sqlDelta : 0), commits: 1,
        connections: 1, batchRows: 8, activeConnectionsBefore: 0, activeConnectionsAfter: 0});
    }
  return {schemaVersion: 1, metadata: {comparison: 'baseline-candidate', baselineReference: 'a'.repeat(40),
    calibrationDesign: {scheme: 'randomized-balanced-blocks-v1', seed: 73129, cellMode: 'sequential-independent'},
    workingSourceSha256: {fixture: 'b'.repeat(64)}, frameworkArtifactSha256: 'c'.repeat(64),
    environment: {javaVersion: '25', javaVm: 'fixture', osName: 'fixture', osVersion: '1', osArch: 'fixture',
      processors: '4', jvmMaxHeapBytes: '1000000', postgresVersion: '17',
      nixFlakeSha256: 'd'.repeat(64), nixLockSha256: 'e'.repeat(64)}}, samples: rows};
}
const calibration = () => { const data = fixture(); data.metadata.comparison = 'baseline-baseline'; return data; };
test('rejects missing, invalid and unpaired data instead of passing an empty comparison', () => {
  assert.throws(() => validate({schemaVersion: 1, metadata: {}, samples: []}), /Empty/);
  const data = fixture({blocks: 1, samples: 1}); data.samples.pop();
  assert.throws(() => validate(data), /paired/);
  const invalid = fixture({blocks: 1, samples: 1}); invalid.samples[0].elapsedNanos = NaN;
  assert.throws(() => validate(invalid), /elapsedNanos/);
});
test('tail quantiles use the observed nearest-rank sample', () => {
  assert.equal(percentile([1, 2, 3, 4, 5], .95), 5);
});
test('rejects partial streaming output and calibration from different inputs', () => {
  assert.throws(() => parseReport('{"kind":"metadata"}\n{"kind":"sample"}'), /Incomplete/);
  const data = fixture({blocks: 1, samples: 1});
  const stream = [{kind: 'metadata', ...data.metadata}, ...data.samples.map(row => ({kind: 'sample', ...row})),
    {kind: 'completion', samples: data.samples.length}].map(row => JSON.stringify(row)).join('\n');
  assert.deepEqual(parseReport(stream).samples.length, 2);
  const other = calibration(); other.metadata.frameworkArtifactSha256 = 'd'.repeat(64);
  assert.equal(evaluate(fixture({candidateScale: .8}), other).status, 'inconclusive');
});
test('passes a demonstrably faster candidate with matching work and adequate calibration', () => {
  const result = evaluate(fixture({candidateScale: .8}), calibration());
  assert.equal(result.status, 'pass');
  assert.ok(result.cells[0].timing.every(metric => metric.status === 'pass'));
});
test('fails slower candidate and extra database work without widening a tolerance', () => {
  assert.equal(evaluate(fixture({candidateScale: 1.2}), calibration()).status, 'regression');
  const result = evaluate(fixture({candidateScale: .8, sqlDelta: 1}), calibration());
  assert.equal(result.status, 'regression');
  assert.equal(result.cells[0].work[0].candidate, 5);
});
test('does not penalize batching the same domain work into fewer executions', () => {
  const data = fixture({candidateScale: .8, sqlDelta: -1});
  for (const row of data.samples) if (row.variant === 'candidate') row.batchRows += 8;
  assert.equal(evaluate(data, calibration()).status, 'pass');
});
test('small samples and absent calibration remain inconclusive even with apparent speedup', () => {
  assert.equal(evaluate(fixture({candidateScale: .1, blocks: 2, samples: 2})).status, 'inconclusive');
  assert.equal(evaluate(fixture({candidateScale: .8})).status, 'inconclusive');
});
test('profiling diagnostics cannot pass as comparison or calibration evidence', () => {
  const profiled = fixture({candidateScale: .8}); profiled.metadata.profilingEnabled = true;
  const comparison = evaluate(profiled, calibration());
  assert.equal(comparison.status, 'inconclusive');
  assert.ok(comparison.metadataIssues.some(reason => reason.includes('Profiling diagnostics')));
  const control = calibration(); control.metadata.profilingEnabled = true;
  assert.equal(evaluate(fixture({candidateScale: .8}), control).status, 'inconclusive');
});
test('unsupported allocation and leaked connections cannot silently pass', () => {
  const unknown = fixture({candidateScale: .8}); unknown.samples[0].allocatedBytes = -1;
  assert.equal(evaluate(unknown, calibration()).status, 'inconclusive');
  const leak = fixture({candidateScale: .8}); leak.samples[0].activeConnectionsAfter = 1;
  assert.equal(evaluate(leak, calibration()).status, 'regression');
});
test('A/A drift blocks even when the candidate appears faster', () => {
  const drifting = fixture({candidateScale: .8}); drifting.metadata.comparison = 'baseline-baseline';
  const result = evaluate(fixture({candidateScale: .7}), drifting);
  assert.equal(result.status, 'inconclusive');
  assert.ok(result.cells[0].timing.every(metric => metric.status === 'inconclusive'));
  assert.equal(result.calibrationFamily.status, 'drift');
  assert.ok(result.calibrationFamily.pValue <= policy.calibrationAlpha);
  assert.ok(result.calibrationFamily.diagnostics.some(metric => metric.name === 'latencyP50Ms' && metric.orientedDifference < 0));
});
test('A/A cannot substitute for candidate data or incomplete calibration metrics', () => {
  const same = calibration();
  assert.equal(evaluate(same, same).status, 'inconclusive');
  const small = calibration(); small.samples = small.samples.filter(row => row.iteration < 2);
  assert.equal(evaluate(fixture({candidateScale: .8}), small).status, 'inconclusive');
  const unknown = calibration(); unknown.samples[0].allocatedBytes = -1;
  assert.equal(evaluate(fixture({candidateScale: .8}), unknown).status, 'inconclusive');
});
test('missing environment or fake provenance cannot pass, while artifact map order is irrelevant', () => {
  const missing = fixture({candidateScale: .8}); delete missing.metadata.environment;
  assert.equal(evaluate(missing, calibration()).status, 'inconclusive');
  const fake = fixture({candidateScale: .8}); fake.metadata.workingSourceSha256 = {fixture: 'unknown'};
  assert.equal(evaluate(fake, calibration()).status, 'inconclusive');
  const data = fixture({candidateScale: .8}), control = calibration();
  data.metadata.frameworkArtifactSha256 = {core: 'a'.repeat(64), jdbc: 'b'.repeat(64)};
  control.metadata.frameworkArtifactSha256 = {jdbc: 'b'.repeat(64), core: 'a'.repeat(64)};
  assert.equal(evaluate(data, control).status, 'pass');
});

test('requires fresh randomized balanced collection and matching complete cell families', () => {
  const data = fixture({candidateScale: .8});
  const old = calibration(); delete old.metadata.calibrationDesign;
  assert.equal(evaluate(data, old).calibrationFamily.status, 'inconclusive');
  const unbalanced = calibration();
  for (const row of unbalanced.samples) row.orderInBlock = row.variant === 'baseline' ? 0 : 1;
  assert.equal(evaluate(data, unbalanced).calibrationFamily.status, 'inconclusive');
  const dependent = calibration(); dependent.metadata.calibrationDesign.cellMode = 'shared';
  assert.equal(evaluate(data, dependent).calibrationFamily.status, 'inconclusive');
  const extra = calibration(); extra.samples.push(...extra.samples.map(row => ({...row, scenario: 'extra'})));
  assert.equal(evaluate(data, extra).calibrationFamily.status, 'inconclusive');
  const missing = calibration(); missing.samples[0].processCpuNanos = -1;
  const result = evaluate(data, missing);
  assert.equal(result.calibrationFamily.status, 'inconclusive');
  assert.equal(result.calibrationFamily.metricCount, 6);
  assert.equal(result.calibrationFamily.permutations, 0);
  assert.ok(result.cells[0].timing.every(metric => metric.status === 'inconclusive'));
});

test('enumerates small block permutation spaces and preserves clustered observations', () => {
  const data = fixture({candidateScale: 1.2, blocks: 3, samples: 2});
  const result = permutationCalibration(validate(data));
  assert.equal(result.exact, true);
  assert.equal(result.permutations, 8);
  assert.equal(result.pValue, .25); // Only both unswapped/all-swapped assignments are as extreme.
  const repeated = {...data, samples: data.samples.flatMap(row => Array.from({length: 10}, (_, index) =>
    ({...row, iteration: row.iteration * 10 + index})))};
  const clustered = permutationCalibration(validate(repeated));
  assert.equal(clustered.blocks, 3);
  assert.equal(clustered.permutations, 8);
  assert.equal(clustered.pValue, result.pValue);
});

test('family calibration is deterministic and symmetric under ordering and label reversal', () => {
  const data = fixture({candidateScale: 1.15, blocks: 32, samples: 2});
  const result = permutationCalibration(validate(data));
  assert.equal(result.exact, false);
  assert.equal(result.permutations, 4095);
  assert.deepEqual(permutationCalibration(validate(data)), result);
  assert.deepEqual(permutationCalibration(validate({...data, samples: [...data.samples].reverse()})), result);
  const reversed = {...data, samples: data.samples.map(row => ({...row,
    variant: row.variant === 'baseline' ? 'candidate' : 'baseline'}))};
  const opposite = permutationCalibration(validate(reversed));
  assert.equal(opposite.status, 'drift');
  assert.equal(opposite.pValue, result.pValue);
  assert.ok(opposite.diagnostics.every((metric, index) =>
    Math.abs(metric.orientedDifference + result.diagnostics[index].orientedDifference) < 1e-8));
});

test('uses the same tail statistic and detects drift confined to the upper latency tail', () => {
  const data = fixture({blocks: 32, samples: 100});
  for (const row of data.samples) if (row.variant === 'candidate' && row.iteration >= 96)
    row.elapsedNanos *= 20;
  const result = permutationCalibration(validate(data));
  assert.equal(result.status, 'drift');
  const tail = result.diagnostics.find(metric => metric.name === 'latencyP99Ms');
  const base = data.samples.filter(row => row.variant === 'baseline').map(row => row.elapsedNanos * 1e-6);
  const candidate = data.samples.filter(row => row.variant === 'candidate').map(row => row.elapsedNanos * 1e-6);
  assert.equal(tail.orientedDifference, percentile(candidate, .99) - percentile(base, .99));
  assert.ok(tail.exceedsFamilyCriticalValue);
});

test('correlated symmetric noise across a family is calibrated jointly without inventing drift', () => {
  const data = fixture({blocks: 32, samples: 4});
  for (const row of data.samples) {
    const noise = (row.block % 7 + 1) * 10 * (row.iteration % 2 ? -1 : 1) *
      (row.variant === 'baseline' ? 1 : -1);
    row.elapsedNanos = 10000 + row.block * 7 + noise;
    row.processCpuNanos = row.elapsedNanos * 2;
    row.allocatedBytes = row.elapsedNanos * 3;
  }
  data.samples.push(...data.samples.map(row => ({...row, scenario: 'another-independent-cell'})));
  const result = permutationCalibration(validate(data));
  assert.equal(result.metricCount, 12);
  assert.equal(result.status, 'stable');
  assert.equal(result.pValue, 1);
  assert.ok(result.diagnostics.every(metric => metric.orientedDifference === 0));
});

test('zero pooled scales remain finite and missing family evidence never reduces the test family', () => {
  const data = fixture({blocks: 3, samples: 2});
  for (const row of data.samples) row.allocatedBytes = 0;
  const result = permutationCalibration(validate(data));
  assert.equal(result.status, 'stable');
  assert.ok(result.diagnostics.every(metric => Number.isFinite(metric.statistic)));
  const insufficient = calibration(); insufficient.samples = insufficient.samples.filter(row => row.iteration < 2);
  assert.equal(evaluate(fixture({candidateScale: .8}), insufficient).calibrationFamily.status, 'inconclusive');
});
