import {createHash} from 'node:crypto';
import {readFileSync, writeFileSync} from 'node:fs';
import {pathToFileURL} from 'node:url';

const passes = ['latency', 'memory', 'operations'];
const finite = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;
const validSeed = value => Number.isInteger(value) && value > 0 && value <= 0xffffffff;
const hash = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const text = value => typeof value === 'string' && value.trim().length > 0;
const equal = (a, b) => JSON.stringify(a) === JSON.stringify(b);
export const identity = value => createHash('sha256').update(JSON.stringify(value)).digest('hex');
const mean = values => values.reduce((sum, value) => sum + value, 0) / values.length;
const baselineRevisions = {
  historical: '0c6df154a185815d187b4f5d04aad3aab0570f8b',
  feature: 'ab95a4fe65399814931c6ddd7f29dd68f33bbc5c',
};
export const requiredOperationCounters = [
  'poolAcquisitionsPerOperation', 'transactionsPerOperation', 'sqlExecutionsPerOperation',
  'sqlStatementsPerOperation', 'wireRoundTripsPerOperation', 'lockAcquisitionsPerOperation',
  'cleanupOperationsPerOperation', 'scheduledTasksPerOperation', 'nodeEntriesVisitedPerOperation',
  'nodeBytesCopiedPerOperation', 'httpExchangesPerOperation', 'webSocketExchangesPerOperation',
  'handwrittenAuthClientLines', 'handwrittenClientModules', 'generatedClientBytes',
  'emittedAuthBundleBytes', 'minifiedAuthBundleBytes', 'gzipAuthBundleBytes', 'brotliAuthBundleBytes',
  'emittedApplicationBundleBytes', 'minifiedApplicationBundleBytes', 'gzipApplicationBundleBytes',
  'brotliApplicationBundleBytes', 'applicationAuthJavaScriptLines', 'newClientDomainPolicyBranches',
];
export const requiredResources = [
  'activeConnectionsAfterRelease', 'activeViewsAfterRelease', 'activeTransactionsAfterRelease',
  'pendingCallbacksAfterRelease', 'sensitiveOwnersAfterRelease', 'totalMetadataEntriesAtPeak',
  'retainedMetadataEntriesAfterQuiescence',
];
export const completedWork = (manifest, cell, pass) => cell.passes?.[pass] ?? manifest.protocol.defaultPasses?.[pass];
export const requiredMetrics = (manifest, cell) => cell.metrics ?? manifest.metricSets?.[cell.metricSet] ?? [];
export const dimensions = (manifest, cell) => ({...manifest.protocol.defaultDimensions, ...cell.dimensions});

/** JSONL deliberately has a required final record: truncated runs never pass. */
export function parseEvidence(content) {
  const records = content.trim().split(/\r?\n/).map(line => JSON.parse(line));
  const first = records[0], last = records.at(-1), samples = records.slice(1, -1);
  if (first?.kind !== 'metadata' || last?.kind !== 'completion' ||
      samples.some(row => row.kind !== 'sample') || last.observations !== samples.length)
    throw new Error('Incomplete evidence or recorded process failure; preserve the raw run.');
  return {metadata: first, samples, completion: last};
}

export function manifestIssues(manifest) {
  const issues = [];
  const p = manifest?.protocol;
  if (manifest?.schemaVersion !== 1 || !p || !Array.isArray(manifest.cells) || !manifest.cells.length)
    return ['Invalid or empty manifest.'];
  if (manifest.status !== 'frozen' || manifest.sourceReview?.referenceReviewed !== true ||
      !text(manifest.sourceReview?.reviewedBy) || !text(manifest.sourceReview?.reviewedAt))
    issues.push('Reference review is incomplete; the manifest is not frozen.');
  if (manifest.unresolved?.length) issues.push(...manifest.unresolved.map(issue => `Unresolved: ${issue}`));
  if (p.pairsPerMode !== 30 || !equal(p.modes, ['comparison', 'calibration']) || !equal(p.passes, passes))
    issues.push('Expected exactly 30 independent pairs in comparison and calibration for each pass.');
  if (p.confidenceFamily !== .95 || p.comparisonAllowance !== 0 ||
      p.order !== 'alternating-baseline-first-candidate-first-by-block')
    issues.push('Unsupported statistical confidence, allowance or process-order protocol.');
  if (!validSeed(p.seed)) issues.push('Bootstrap seed must be a nonzero unsigned 32-bit integer.');
  const family = manifest.cells.reduce((sum, cell) => sum + requiredMetrics(manifest, cell).length, 0);
  // Both comparison AND calibration intervals belong to this simultaneous family.
  const tail = .05 / (4 * family);
  if (p.familySize !== family || !Number.isSafeInteger(p.bootstrapReplicates) ||
      p.bootstrapReplicates < Math.max(20000, Math.ceil(p.minimumBootstrapTailObservations / tail)) ||
      p.minimumBootstrapTailObservations < 20 || p.intervalFamilies !== 2)
    issues.push('Bootstrap family size or adjusted-tail resolution is not sufficient and frozen.');
  for (const key of ['maximumProcessSeconds', 'maximumCollectionSeconds', 'maximumAnalysisSeconds', 'timingClockResolutionNs'])
    if (!finite(p[key]) || p[key] === 0) issues.push(`Missing positive protocol parameter: ${key}.`);
  for (const key of ['maximumAbsoluteMeanRelativeChange', 'maximumRelativeIntervalWidth',
    'maximumAbsoluteMeanResolutionUnits', 'maximumAbsoluteIntervalWidthResolutionUnits'])
    if (!finite(p.calibration?.[key])) issues.push(`Missing numerical calibration limit: ${key}.`);
  for (const key of ['maximumWarmLatencyCompilationMilliseconds', 'maximumColdLatencyCompilationMilliseconds',
    'maximumLatencyGcMilliseconds'])
    if (!finite(p.diagnostics?.[key])) issues.push(`Missing numerical diagnostic limit: ${key}.`);
  if (!equal(Object.keys(manifest.baselines ?? {}).sort(), ['feature', 'historical']))
    issues.push('Both frozen feature and historical baselines are required.');
  for (const [kind, names] of [['requiredOperationCounters', requiredOperationCounters], ['requiredResources', requiredResources]])
    if (!equal(Object.keys(manifest[kind] ?? {}).sort(), [...names].sort())) issues.push(`Incomplete required schema: ${kind}.`);
  for (const [name, rule] of Object.entries(manifest.requiredOperationCounters ?? {})) {
    const expected = ['applicationAuthJavaScriptLines', 'newClientDomainPolicyBranches'].includes(name) ? 0 : null;
    if (rule.fixedCeiling !== expected) issues.push(`Invalid fixed counter rule: ${name}.`);
  }
  for (const [name, rule] of Object.entries(manifest.requiredResources ?? {}))
    if (rule.fixedCeiling !== (name.endsWith('AfterRelease') ? 0 : null)) issues.push(`Invalid fixed resource rule: ${name}.`);
  for (const [name, source] of Object.entries(manifest.baselines ?? {})) {
    if (source.gitCommit !== baselineRevisions[name] || !hash(source.referenceIntegrationSha256))
      issues.push(`Baseline ${name} lacks immutable source/reference integration identity.`);
  }
  const ids = new Set();
  for (const cell of manifest.cells) {
    if (!text(cell.id) || ids.has(cell.id)) issues.push('Duplicate or missing workload cell ID.');
    ids.add(cell.id);
    if (!manifest.baselines?.[cell.baseline]) issues.push(`${cell.id}: unknown baseline.`);
    if (!hash(cell.completedWorkContractSha256)) issues.push(`${cell.id}: completed-work contract is not frozen.`);
    const metrics = requiredMetrics(manifest, cell);
    if (!metrics.length || new Set(metrics).size !== metrics.length)
      issues.push(`${cell.id}: empty or duplicate metric family.`);
    for (const name of metrics) {
      const metric = manifest.metricDefinitions?.[name];
      if (!metric || !passes.includes(metric.pass) || typeof metric.higherIsBetter !== 'boolean' ||
          !finite(metric.resolution) || !metric.resolution)
        issues.push(`${cell.id}: invalid metric ${name}.`);
    }
    for (const pass of passes) {
      const work = completedWork(manifest, cell, pass);
      if (!Number.isSafeInteger(work?.warmupOperations) || work.warmupOperations < 0 ||
          !Number.isSafeInteger(work?.measuredOperations) || work.measuredOperations <= 0)
        issues.push(`${cell.id}/${pass}: work counts are not frozen.`);
    }
    for (const [kind, schema] of [['operationCeilings', manifest.requiredOperationCounters], ['resourceCeilings', manifest.requiredResources]]) {
      if (!schema || !equal(Object.keys(cell[kind] ?? {}).sort(), Object.keys(schema).sort()) ||
          Object.entries(cell[kind] ?? {}).some(([name, value]) => !finite(value) ||
            (schema[name]?.fixedCeiling !== null && value !== schema[name]?.fixedCeiling)))
        issues.push(`${cell.id}: measured ${kind} are incomplete.`);
    }
  }
  return issues;
}

export function provenanceIssues(manifest, evidence, mode) {
  const issues = [], metadata = evidence?.metadata;
  if (!metadata || metadata.mode !== mode || metadata.manifestSha256 !== identity(manifest))
    return [`${mode}: missing metadata or wrong manifest identity.`];
  const provenance = metadata.provenance;
  for (const name of [...Object.keys(manifest.baselines), 'candidate']) {
    const source = name === 'candidate' ? provenance?.candidate : provenance?.baselines?.[name];
    if (!/^[a-f0-9]{40}$/.test(source?.gitCommit ?? '')) issues.push(`${mode}/${name}: missing Git commit.`);
    for (const field of ['dirtyDiffSha256', 'artifactSha256', 'referenceIntegrationSha256', 'flakeSha256',
      'flakeLockSha256', 'dependencyGraphSha256'])
      if (!hash(source?.[field])) issues.push(`${mode}/${name}: missing ${field}.`);
    if (name !== 'candidate' && (source?.gitCommit !== manifest.baselines[name].gitCommit ||
        source?.referenceIntegrationSha256 !== manifest.baselines[name].referenceIntegrationSha256))
      issues.push(`${mode}/${name}: baseline source differs from the frozen reference.`);
  }
  const shared = metadata.shared;
  for (const field of ['fixtureSha256', 'runnerSha256', 'analyzerSha256', 'referenceDataSha256',
    'clientInventorySha256', 'measurementConfigSha256'])
    if (!hash(shared?.[field])) issues.push(`${mode}: missing ${field}.`);
  for (const [part, fields] of Object.entries({os: ['name', 'version', 'arch'],
    hardware: ['machineId', 'cpu'], jvm: ['vendor', 'version', 'gc'],
    database: ['engine', 'version'], browsers: ['chromium', 'webkit']}))
    for (const field of fields) if (!text(shared?.[part]?.[field])) issues.push(`${mode}: missing ${part}.${field}.`);
  if (!hash(shared?.database?.configSha256)) issues.push(`${mode}: missing database configuration hash.`);
  for (const [part, field] of [['hardware', 'cores'], ['hardware', 'memoryBytes'], ['jvm', 'maxHeapBytes']])
    if (!finite(shared?.[part]?.[field]) || !shared[part][field]) issues.push(`${mode}: missing ${part}.${field}.`);
  if (!Array.isArray(shared?.jvm?.flags) || !shared.jvm.flags.every(text)) issues.push(`${mode}: missing JVM flags.`);
  if (!text(metadata.startedAt) || !finite(metadata.durationSeconds) ||
      metadata.durationSeconds > manifest.protocol.maximumCollectionSeconds)
    issues.push(`${mode}: missing or exceeded collection duration.`);
  if (!Array.isArray(evidence.samples) || evidence.completion?.observations !== evidence.samples?.length)
    issues.push(`${mode}: incomplete collection.`);
  return issues;
}

function randomGenerator(seed) {
  // Xorshift32's zero state is absorbing. Reject aliases rather than silently
  // truncating a larger/signed seed to a different frozen configuration.
  if (!validSeed(seed)) throw new RangeError('Bootstrap seed must be a nonzero unsigned 32-bit integer.');
  return () => {
    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
    return (seed >>> 0) / 4294967296;
  };
}

/** Percentiles describe independent process-block estimates, never pooled tails. */
export function interval(differences, replicates, tail, seed) {
  const random = randomGenerator(seed);
  const estimate = mean(differences);
  // Exact degenerate bootstrap distribution; this shortcut preserves all quantiles.
  if (differences.every(value => value === differences[0])) return {estimate, bounds: [estimate, estimate]};
  const values = new Float64Array(replicates);
  for (let replicate = 0; replicate < replicates; replicate++) {
    let total = 0;
    for (let i = 0; i < differences.length; i++) total += differences[Math.floor(random() * differences.length)];
    values[replicate] = total / differences.length;
  }
  values.sort();
  return {estimate, bounds: [values[Math.floor(tail * (replicates - 1))],
    values[Math.ceil((1 - tail) * (replicates - 1))]]};
}

export function evaluate(manifest, comparison, calibration) {
  const issues = manifestIssues(manifest), regressions = [], results = [];
  if (issues.length) return {status: 'inconclusive', issues, cells: []};
  for (const [mode, evidence] of [['comparison', comparison], ['calibration', calibration]])
    issues.push(...provenanceIssues(manifest, evidence, mode));
  if (issues.length) return {status: 'inconclusive', issues, cells: []};
  if (!equal(comparison.metadata.provenance, calibration.metadata.provenance) ||
      !equal(comparison.metadata.shared, calibration.metadata.shared))
    return {status: 'inconclusive', issues: ['Comparison/calibration provenance differs.'], cells: []};
  const start = Date.now(), p = manifest.protocol, processes = new Set();
  const expected = new Map();
  for (const cell of manifest.cells) for (const pass of passes)
    expected.set(`${cell.id}/${pass}`, {cell, pass, comparison: new Map(), calibration: new Map()});
  for (const [mode, evidence] of [['comparison', comparison], ['calibration', calibration]]) {
    for (const row of evidence.samples) {
      const entry = expected.get(`${row.cell}/${row.pass}`);
      if (!entry || !['baseline', 'candidate'].includes(row.variant) ||
          !Number.isSafeInteger(row.block) || row.block < 0 || row.block >= p.pairsPerMode) {
        issues.push(`${mode}: unexpected cell, pass, variant or block.`); continue;
      }
      const key = `${row.block}/${row.variant}`;
      if (entry[mode].has(key)) { issues.push(`${mode}/${row.cell}/${row.pass}: duplicate sample.`); continue; }
      entry[mode].set(key, row);
      if (!text(row.processId) || processes.has(row.processId)) issues.push(`${mode}/${row.cell}: process identity missing/reused.`);
      processes.add(row.processId);
      const source = mode === 'calibration' || row.variant === 'baseline'
        ? evidence.metadata.provenance.baselines[entry.cell.baseline] : evidence.metadata.provenance.candidate;
      if (row.artifactSha256 !== source.artifactSha256) issues.push(`${mode}/${row.cell}: sample artifact identity differs.`);
      if (row.completedWorkContractSha256 !== entry.cell.completedWorkContractSha256 ||
          row.completedOperations !== completedWork(manifest, entry.cell, row.pass).measuredOperations ||
          row.warmupOperations !== completedWork(manifest, entry.cell, row.pass).warmupOperations || row.completedWorkVerified !== true)
        issues.push(`${mode}/${row.cell}/${row.pass}: completed-work mismatch.`);
      const expectedOrder = row.variant === 'baseline' ? row.block % 2 : 1 - row.block % 2;
      if (row.orderInBlock !== expectedOrder) issues.push(`${mode}/${row.cell}: unbalanced process order.`);
      if (!finite(row.durationSeconds) || !row.durationSeconds || row.durationSeconds > p.maximumProcessSeconds)
        issues.push(`${mode}/${row.cell}: process duration missing/exceeded.`);
      if (row.profilingEnabled !== (row.pass === 'memory')) issues.push(`${mode}/${row.cell}: wrong profiling pass.`);
      if (!finite(row.clockResolutionNs) || !row.clockResolutionNs || row.clockResolutionNs > p.timingClockResolutionNs)
        issues.push(`${mode}/${row.cell}: unsupported timing resolution.`);
      for (const diagnostic of ['jitCompilationMilliseconds', 'gcCount', 'gcMilliseconds'])
        if (!finite(row.diagnostics?.[diagnostic])) issues.push(`${mode}/${row.cell}: missing ${diagnostic}.`);
      if (row.pass === 'latency') {
        const cold = completedWork(manifest, entry.cell, row.pass).warmupOperations === 0;
        const maximumCompilation = cold ? p.diagnostics.maximumColdLatencyCompilationMilliseconds : p.diagnostics.maximumWarmLatencyCompilationMilliseconds;
        if (row.diagnostics?.jitCompilationMilliseconds > maximumCompilation || row.diagnostics?.gcMilliseconds > p.diagnostics.maximumLatencyGcMilliseconds)
          issues.push(`${mode}/${row.cell}: JIT/GC instability exceeds the frozen diagnostic limits.`);
      }
      if (row.measurementResolutionVerified !== true) issues.push(`${mode}/${row.cell}: actual sensor resolution has not been calibrated.`);
      for (const name of requiredMetrics(manifest, entry.cell).filter(name => manifest.metricDefinitions[name].pass === row.pass)) {
        const resolution = row.measuredResolutions?.[name];
        if (!finite(resolution) || !resolution || resolution > manifest.metricDefinitions[name].resolution)
          issues.push(`${mode}/${row.cell}: unsupported observed sensor resolution for ${name}.`);
      }
      for (const [name, ceiling] of Object.entries(entry.cell.resourceCeilings)) {
        const value = row.resources?.[name];
        if (!finite(value)) issues.push(`${mode}/${row.cell}: missing resource ${name}.`);
        else if (value > ceiling) regressions.push(`${mode}/${row.cell}: ${name} exceeds ${ceiling}.`);
      }
      if (row.pass === 'operations') for (const [name, ceiling] of Object.entries(entry.cell.operationCeilings)) {
        const value = row.counters?.[name];
        if (!finite(value)) issues.push(`${mode}/${row.cell}: missing counter ${name}.`);
        else if (value > ceiling) regressions.push(`${mode}/${row.cell}: ${name} exceeds ${ceiling}.`);
      }
    }
  }
  for (const entry of expected.values()) for (const mode of ['comparison', 'calibration'])
    if (entry[mode].size !== p.pairsPerMode * 2) issues.push(`${mode}/${entry.cell.id}/${entry.pass}: expected exactly 30 complete pairs.`);
  if (issues.length) return {status: regressions.length ? 'regression' : 'inconclusive', issues, regressions, cells: []};
  const tail = (1 - p.confidenceFamily) / (4 * p.familySize);
  for (const cell of manifest.cells) {
    const metrics = [];
    for (const name of requiredMetrics(manifest, cell)) {
      if ((Date.now() - start) / 1000 > p.maximumAnalysisSeconds)
        return {status: regressions.length ? 'regression' : 'inconclusive', issues: ['Analysis duration exceeded; retain all evidence.'], regressions, cells: results};
      const metric = manifest.metricDefinitions[name], entry = expected.get(`${cell.id}/${metric.pass}`);
      const pairs = mode => Array.from({length: 30}, (_, block) =>
        ['baseline', 'candidate'].map(variant => entry[mode].get(`${block}/${variant}`).metrics?.[name]));
      const observed = pairs('comparison'), control = pairs('calibration');
      if ([...observed.flat(), ...control.flat()].some(value => !finite(value))) {
        metrics.push({name, status: 'inconclusive', reason: 'Missing, null or unsupported measurement.'}); continue;
      }
      const samples = [...entry.comparison.values(), ...entry.calibration.values()];
      if (name === 'operationsPerSecond' && samples.some(row => row.completedOperations > 0 && row.metrics[name] === 0)) {
        metrics.push({name, status: 'inconclusive', reason: 'Zero throughput cannot represent positive completed work.'}); continue;
      }
      // Elapsed latency observations must be resolvable by their actual sensor.
      // Byte/count metrics and genuine absence of lock wait can legitimately be zero.
      if (metric.pass === 'latency' && name.endsWith('Ns') && samples.some(row =>
        row.metrics[name] < Math.max(row.measuredResolutions[name], row.clockResolutionNs))) {
        metrics.push({name, status: 'inconclusive', reason: 'Timing measurement is below the observed sensor/clock resolution.'}); continue;
      }
      // One common scale for comparison and A/A; mixed zero/nonzero baseline uses absolute units.
      const absolute = [...observed, ...control].some(pair => pair[0] === 0);
      const differences = rows => rows.map(([base, candidate]) =>
        (absolute ? candidate - base : candidate / base - 1) * (metric.higherIsBetter ? -1 : 1));
      const actual = interval(differences(observed), p.bootstrapReplicates, tail, p.seed);
      const aa = interval(differences(control), p.bootstrapReplicates, tail, p.seed);
      const limits = p.calibration;
      const meanLimit = absolute ? limits.maximumAbsoluteMeanResolutionUnits * metric.resolution : limits.maximumAbsoluteMeanRelativeChange;
      const widthLimit = absolute ? limits.maximumAbsoluteIntervalWidthResolutionUnits * metric.resolution : limits.maximumRelativeIntervalWidth;
      const calibrated = aa.bounds[0] <= 0 && aa.bounds[1] >= 0 && Math.abs(aa.estimate) <= meanLimit &&
        aa.bounds[1] - aa.bounds[0] <= widthLimit;
      const status = calibrated ? actual.bounds[0] > 0 ? 'regression' : actual.bounds[1] <= 0 ? 'pass' : 'inconclusive' : 'inconclusive';
      metrics.push({name, status, scale: absolute ? 'absolute' : 'relative', ...actual, calibration: aa,
        ...(calibrated ? {} : {reason: 'Calibration drift or uncertainty exceeds the frozen numerical limits.'})});
    }
    const entry = expected.get(`${cell.id}/operations`);
    for (let block = 0; block < 30; block++) for (const name of Object.keys(cell.operationCeilings)) {
      if (entry.comparison.get(`${block}/candidate`).counters[name] > entry.comparison.get(`${block}/baseline`).counters[name])
        regressions.push(`${cell.id}: candidate ${name} exceeds paired baseline in block ${block}.`);
    }
    results.push({id: cell.id, metrics});
  }
  const statuses = results.flatMap(cell => cell.metrics.map(metric => metric.status));
  return {status: regressions.length || statuses.includes('regression') ? 'regression' : statuses.every(status => status === 'pass') ? 'pass' : 'inconclusive',
    confidenceFamily: .95, method: 'Bonferroni paired-process-block percentile bootstrap', replicates: p.bootstrapReplicates,
    familySize: p.familySize, intervalFamilies: 2, adjustedTail: tail, issues, regressions, cells: results};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  let result;
  const [manifestFile, comparisonFile, calibrationFile, outputFile] = process.argv.slice(2);
  try {
    if (!manifestFile) throw new Error('Usage: node analyze.mjs manifest.json [comparison.jsonl calibration.jsonl report.json]');
    const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'));
    result = evaluate(manifest, comparisonFile ? parseEvidence(readFileSync(comparisonFile, 'utf8')) : undefined,
      calibrationFile ? parseEvidence(readFileSync(calibrationFile, 'utf8')) : undefined);
  } catch (error) { result = {status: 'inconclusive', issues: [error.message], cells: []}; }
  if (outputFile) writeFileSync(outputFile, JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify(result));
  if (result.status !== 'pass') process.exitCode = 1;
}
