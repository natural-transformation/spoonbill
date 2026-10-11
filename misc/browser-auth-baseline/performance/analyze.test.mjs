import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {evaluate, identity, interval, manifestIssues, parseEvidence, requiredMetrics} from './analyze.mjs';

const proposed = JSON.parse(readFileSync(new URL('./manifest.json', import.meta.url), 'utf8'));
import {syntheticFixture as fixture} from './test-fixture.mjs';
const report = data => evaluate(data.manifest, data.comparison, data.calibration);
const rows = (data, pass, variant = 'candidate') => data.comparison.samples.filter(row => row.pass === pass && row.variant === variant);

test('checked-in contract enumerates the matrix but cannot claim Phase 0 or measurements passed', () => {
  const result = evaluate(proposed);
  assert.equal(result.status, 'inconclusive');
  assert.ok(result.issues.some(issue => issue.includes('not frozen')));
  assert.ok(result.issues.some(issue => issue.includes('operationCeilings')));
  assert.equal(proposed.cells.length, 94);
  assert.equal(proposed.protocol.familySize, proposed.cells.reduce((sum, cell) => sum + requiredMetrics(proposed, cell).length, 0));
  const tail = .05 / (4 * proposed.protocol.familySize);
  assert.ok(proposed.protocol.bootstrapReplicates * tail >= 20 - 1e-10);
  for (const name of ['browserDownloadNs', 'browserStartupNs', 'browserParseNs', 'browserCompileNs',
    'browserExecutionNs', 'browserEventToRenderNs', 'browserRetainedHeapBytes'])
    assert.ok(proposed.cells.some(cell => requiredMetrics(proposed, cell).includes(name)));
  for (const cell of proposed.cells) {
    assert.equal(cell.operationCeilings, null);
  }
  assert.equal(proposed.requiredOperationCounters.applicationAuthJavaScriptLines.fixedCeiling, 0);
  assert.ok(Object.hasOwn(proposed.requiredOperationCounters, 'httpExchangesPerOperation'));
  assert.ok(Object.hasOwn(proposed.requiredOperationCounters, 'gzipAuthBundleBytes'));
});

test('complete faster synthetic evidence passes and throughput orientation is reversed', () => {
  const result = report(fixture());
  assert.equal(result.status, 'pass');
  assert.ok(result.cells[0].metrics.every(metric => metric.bounds[1] < 0));
});

test('omitting a required cell from BOTH files is inconclusive', () => {
  const data = fixture();
  data.manifest.cells.push({...structuredClone(data.manifest.cells[0]), id: 'omitted-required-cell'});
  data.manifest.protocol.familySize *= 2;
  for (const evidence of [data.comparison, data.calibration]) evidence.metadata.manifestSha256 = identity(data.manifest);
  const result = report(data);
  assert.equal(result.status, 'inconclusive');
  assert.ok(result.issues.some(issue => issue.includes('omitted-required-cell')));
});

test('requires exactly 30 distinct complete process pairs for every pass', () => {
  for (const mutate of [
    data => data.comparison.samples.pop(),
    data => data.comparison.samples.push({...data.comparison.samples[0]}),
    data => { data.comparison.samples[0].block = 30; },
    data => { data.comparison.samples[0].processId = data.calibration.samples[0].processId; },
    data => { data.comparison.samples[0].orderInBlock = 1; },
  ]) {
    const data = fixture(); mutate(data);
    data.comparison.completion.observations = data.comparison.samples.length;
    assert.equal(report(data).status, 'inconclusive');
  }
});

test('zero baselines use absolute changes without losing throughput orientation', () => {
  const data = fixture();
  for (const evidence of [data.comparison, data.calibration]) for (const row of evidence.samples)
    row.metrics.allocatedBytesPerOperation = 0;
  assert.equal(report(data).status, 'pass');
  for (const row of rows(data, 'memory')) row.metrics.allocatedBytesPerOperation = 1;
  const result = report(data);
  assert.equal(result.status, 'regression');
  assert.equal(result.cells[0].metrics.find(metric => metric.name === 'allocatedBytesPerOperation').scale, 'absolute');
});

test('zero throughput or unresolvable elapsed timing never certifies positive completed work', () => {
  for (const name of ['latencyP50Ns', 'operationsPerSecond']) {
    const data = fixture();
    for (const evidence of [data.comparison, data.calibration]) for (const row of evidence.samples)
      row.metrics[name] = 0;
    const result = report(data);
    assert.equal(result.status, 'inconclusive');
    assert.equal(result.cells[0].metrics.find(metric => metric.name === name).status, 'inconclusive');
  }
  // Even a single unusable observation in either variant or A/A invalidates
  // the metric; the other positive observations cannot hide it.
  for (const mode of ['comparison', 'calibration']) for (const variant of ['baseline', 'candidate']) {
    const data = fixture();
    data[mode].samples.find(row => row.pass === 'latency' && row.variant === variant).metrics.latencyP50Ns = 1 - Number.EPSILON;
    const result = report(data);
    assert.equal(result.status, 'inconclusive');
    assert.match(result.cells[0].metrics.find(metric => metric.name === 'latencyP50Ns').reason, /resolution/);
  }
});

test('elapsed timing uses calibrated resolution rather than the manifest ceiling and accepts the exact boundary', () => {
  const data = fixture();
  data.manifest.metricDefinitions.latencyP50Ns.resolution = 10;
  for (const evidence of [data.comparison, data.calibration]) {
    evidence.metadata.manifestSha256 = identity(data.manifest);
    for (const row of evidence.samples) {
      row.measuredResolutions.latencyP50Ns = 2;
      row.metrics.latencyP50Ns = 2;
    }
  }
  assert.equal(report(data).status, 'pass');
  rows(data, 'latency')[0].metrics.latencyP50Ns = 1.5;
  assert.equal(report(data).status, 'inconclusive');
  const clockLimited = fixture();
  for (const evidence of [clockLimited.comparison, clockLimited.calibration]) for (const row of evidence.samples) {
    row.measuredResolutions.latencyP50Ns = .25;
    row.metrics.latencyP50Ns = 1;
  }
  assert.equal(report(clockLimited).status, 'pass');
  rows(clockLimited, 'latency')[0].metrics.latencyP50Ns = .5;
  assert.equal(report(clockLimited).status, 'inconclusive');
});

test('zero bytes, operation/resource counts and absent lock wait remain meaningful', () => {
  const data = fixture();
  data.manifest.cells[0].metrics.push('lockWaitNsPerOperation');
  data.manifest.protocol.familySize += 1;
  for (const evidence of [data.comparison, data.calibration]) {
    evidence.metadata.manifestSha256 = identity(data.manifest);
    for (const row of evidence.samples) {
      row.metrics.allocatedBytesPerOperation = 0;
      row.metrics.lockWaitNsPerOperation = 0;
      row.measuredResolutions.lockWaitNsPerOperation = 1;
    }
  }
  const result = report(data);
  assert.equal(result.status, 'pass');
  for (const name of ['allocatedBytesPerOperation', 'lockWaitNsPerOperation']) {
    const metric = result.cells[0].metrics.find(metric => metric.name === name);
    assert.equal(metric.scale, 'absolute');
    assert.deepEqual(metric.bounds, [0, 0]);
  }
});

test('null and missing metrics are inconclusive rather than zero or omitted', () => {
  for (const value of [undefined, null, NaN, -1]) {
    const data = fixture(); rows(data, 'memory')[0].metrics.allocatedBytesPerOperation = value;
    assert.equal(report(data).status, 'inconclusive');
  }
});

test('incomplete budgets and missing counters fail closed', () => {
  const data = fixture(); data.manifest.cells[0].operationCeilings.sqlExecutionsPerOperation = null;
  assert.ok(manifestIssues(data.manifest).some(issue => issue.includes('operationCeilings')));
  const other = fixture(); delete rows(other, 'operations')[0].counters.sqlExecutionsPerOperation;
  assert.equal(report(other).status, 'inconclusive');
  const reduced = fixture(); delete reduced.manifest.cells[0].operationCeilings.wireRoundTripsPerOperation;
  assert.ok(manifestIssues(reduced.manifest).some(issue => issue.includes('operationCeilings')));
  const missingBaseline = fixture(); delete missingBaseline.manifest.baselines.historical;
  assert.ok(manifestIssues(missingBaseline.manifest).some(issue => issue.includes('historical')));
  const newerBaseline = fixture(); newerBaseline.manifest.baselines.feature.gitCommit = 'a'.repeat(40);
  assert.ok(manifestIssues(newerBaseline.manifest).some(issue => issue.includes('Baseline feature')));
});

test('deterministic resource, operation and custom client-JS regressions override faster timing', () => {
  for (const mutate of [
    data => { data.comparison.samples[0].resources.activeConnectionsAfterRelease = 1; },
    data => { rows(data, 'operations')[0].counters.sqlExecutionsPerOperation = 3; },
    data => { rows(data, 'operations')[0].counters.applicationAuthJavaScriptLines = 1; },
    data => { rows(data, 'operations', 'baseline')[0].counters.sqlExecutionsPerOperation = 1; },
  ]) {
    const data = fixture(); mutate(data);
    assert.equal(report(data).status, 'regression');
  }
});

test('requires full comparable provenance and rejects candidate artifacts in A/A', () => {
  for (const mutate of [
    data => { delete data.comparison.metadata.shared.clientInventorySha256; },
    data => { delete data.calibration.metadata.provenance.candidate.dirtyDiffSha256; },
    data => { data.calibration.metadata.shared.browsers.webkit = 'different'; },
    data => { data.calibration.samples[0].artifactSha256 = data.calibration.metadata.provenance.candidate.artifactSha256; },
    data => { data.comparison.metadata.provenance.baselines.feature.gitCommit = 'a'.repeat(40); },
  ]) {
    const data = fixture(); mutate(data);
    assert.equal(report(data).status, 'inconclusive');
  }
});

test('deterministic regression is still reported alongside a missing metric', () => {
  const data = fixture();
  rows(data, 'memory')[0].metrics.allocatedBytesPerOperation = null;
  data.comparison.samples[0].resources.activeConnectionsAfterRelease = 1;
  const result = report(data);
  assert.equal(result.status, 'regression');
  assert.ok(result.regressions.some(issue => issue.includes('activeConnectionsAfterRelease')));
});

test('work completion, diagnostics, profiling and durations are mandatory', () => {
  for (const mutate of [
    row => { row.completedWorkVerified = false; },
    row => { row.completedOperations -= 1; },
    row => { row.profilingEnabled = true; },
    row => { row.durationSeconds = 601; },
    row => { row.clockResolutionNs = 1000; },
    row => { delete row.diagnostics.gcCount; },
    row => { row.diagnostics.gcMilliseconds = 5001; },
    row => { row.measurementResolutionVerified = false; },
    row => { row.measuredResolutions.latencyP50Ns = 100; },
  ]) {
    const data = fixture(); mutate(data.comparison.samples[0]);
    assert.equal(report(data).status, 'inconclusive');
  }
});

test('JIT activity in a warmed latency pass fails the frozen stability check', () => {
  const data = fixture();
  data.manifest.cells[0].passes.latency.warmupOperations = 1;
  for (const evidence of [data.comparison, data.calibration]) {
    evidence.metadata.manifestSha256 = identity(data.manifest);
    for (const row of evidence.samples) if (row.pass === 'latency') row.warmupOperations = 1;
  }
  data.comparison.samples[0].diagnostics.jitCompilationMilliseconds = 1;
  assert.equal(report(data).status, 'inconclusive');
});

test('calibration drift cannot be masked by a faster candidate', () => {
  const data = fixture();
  for (const row of data.calibration.samples) if (row.variant === 'candidate') row.metrics.latencyP50Ns = 110;
  assert.equal(report(data).status, 'inconclusive');
});

test('calibration includes a numerical interval-width gate even when its interval includes zero', () => {
  const data = fixture();
  for (const row of data.calibration.samples) if (row.variant === 'candidate') row.metrics.latencyP50Ns = row.block % 2 ? 190 : 10;
  const result = report(data);
  assert.equal(result.status, 'inconclusive');
  assert.ok(result.cells[0].metrics[0].calibration.bounds[1] - result.cells[0].metrics[0].calibration.bounds[0] > .1);
});

test('noise crossing zero is inconclusive and a slower path is never averaged away', () => {
  const data = fixture();
  for (const row of rows(data, 'latency')) row.metrics.latencyP50Ns = row.block % 2 ? 110 : 90;
  assert.equal(report(data).status, 'inconclusive');
  for (const row of rows(data, 'latency')) row.metrics.latencyP50Ns = 110;
  assert.equal(report(data).status, 'regression');
});

test('bootstrap is deterministic, uses paired block means and includes exact equality', () => {
  const values = [-.1, .1, -.2, .2];
  assert.deepEqual(interval(values, 20000, .01, 42), interval(values, 20000, .01, 42));
  assert.deepEqual(interval([0, 0, 0], 20000, .001, 42), {estimate: 0, bounds: [0, 0]});
});

test('zero, truncated and noncanonical bootstrap seeds fail closed before resampling or equality shortcuts', () => {
  for (const seed of [0, -0, 2 ** 32, 2 ** 33, -(2 ** 32), -1, 2 ** 32 + 1,
    Number.MAX_SAFE_INTEGER, Number.MAX_SAFE_INTEGER + 1, .5, NaN, Infinity, '42', undefined]) {
    const data = fixture();
    data.manifest.protocol.seed = seed;
    for (const evidence of [data.comparison, data.calibration]) evidence.metadata.manifestSha256 = identity(data.manifest);
    for (const row of rows(data, 'latency')) row.metrics.latencyP50Ns = row.block === 0 ? 90 : 110;
    assert.ok(manifestIssues(data.manifest).some(issue => issue.includes('seed')));
    assert.equal(report(data).status, 'inconclusive');
    assert.throws(() => interval([-.1, .1], 20000, .01, seed), /seed/);
    assert.throws(() => interval([0, 0], 20000, .01, seed), /seed/);
  }
});

test('accepted uint32 seed boundaries resample mixed pairs instead of certifying the first improving pair', () => {
  for (const seed of [1, 0x7fffffff, 0x80000000, 0xffffffff]) {
    const data = fixture();
    data.manifest.protocol.seed = seed;
    for (const evidence of [data.comparison, data.calibration]) evidence.metadata.manifestSha256 = identity(data.manifest);
    for (const row of rows(data, 'latency')) row.metrics.latencyP50Ns = row.block === 0 ? 90 : 110;
    assert.deepEqual(manifestIssues(data.manifest), []);
    const result = report(data);
    assert.equal(result.status, 'regression');
    const metric = result.cells[0].metrics.find(metric => metric.name === 'latencyP50Ns');
    assert.ok(metric.estimate > 0);
    assert.ok(metric.bounds[0] > 0);
    assert.ok(metric.bounds[1] > metric.bounds[0]);
  }
});

test('stream parsing retains failure/incomplete-run distinction', () => {
  const data = fixture().comparison;
  const records = [data.metadata, ...data.samples, data.completion];
  assert.equal(parseEvidence(records.map(JSON.stringify).join('\n')).samples.length, 180);
  assert.throws(() => parseEvidence(records.slice(0, -1).map(JSON.stringify).join('\n')), /Incomplete/);
  records.splice(1, 0, {kind: 'failure', reason: 'synthetic worker failure'});
  assert.throws(() => parseEvidence(records.map(JSON.stringify).join('\n')), /failure/);
});
