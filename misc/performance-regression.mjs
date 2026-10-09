import {readFile, writeFile} from 'node:fs/promises';
import {pathToFileURL} from 'node:url';

// No tolerated slowdown: uncertain measurements block, rather than becoming a
// silently accepted percentage regression. Resample paired BLOCKS, not requests.
export const policy = Object.freeze({minBlocks: 30, p50Samples: 60, p95Samples: 300,
  p99Samples: 1000, bootstrapReplicates: 2000, confidence: 0.95,
  calibrationPermutations: 4095, calibrationAlpha: 0.05, calibrationSeed: 246813579});
const workFields = ['sqlExecutions', 'commits', 'connections'];
const metrics = [
  {name: 'latencyP50Ms', field: 'elapsedNanos', quantile: .50, scale: 1e-6, minimum: policy.p50Samples},
  {name: 'latencyP95Ms', field: 'elapsedNanos', quantile: .95, scale: 1e-6, minimum: policy.p95Samples},
  {name: 'latencyP99Ms', field: 'elapsedNanos', quantile: .99, scale: 1e-6, minimum: policy.p99Samples},
  {name: 'throughputOpsPerSecond', throughput: true, minimum: policy.p50Samples},
  {name: 'cpuNanosPerOperation', field: 'processCpuNanos', perOperation: true, minimum: policy.p50Samples},
  {name: 'allocatedBytesPerOperation', field: 'allocatedBytes', perOperation: true, minimum: policy.p50Samples},
];
function requireValid(condition, reason) { if (!condition) throw new Error(reason); }
const cellKey = row => JSON.stringify([row.scenario, row.concurrency, row.dbDelayMicros]);
const mean = values => values.reduce((sum, value) => sum + value, 0) / values.length;
export function percentile(values, q) {
  const ordered = [...values].sort((a, b) => a - b);
  return ordered[Math.max(0, Math.ceil(q * ordered.length) - 1)];
}
function random(seed) {
  let state = seed >>> 0;
  return () => {
    state += 0x6d2b79f5;
    let value = state;
    value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
}
function estimate(rows, metric) {
  if (metric.throughput) return rows.reduce((sum, row) => sum + row.operations, 0) * 1e9 /
    rows.reduce((sum, row) => sum + row.elapsedNanos, 0);
  const values = rows.map(row => row[metric.field] * (metric.scale ?? 1) / (metric.perOperation ? row.operations : 1));
  return metric.quantile ? percentile(values, metric.quantile) : mean(values);
}

export function validate(input) {
  requireValid(input && input.schemaVersion === 1 && input.metadata && Array.isArray(input.samples), 'Expected schemaVersion 1, metadata and samples');
  requireValid(input.samples.length > 0, 'Empty benchmark cannot pass');
  const cells = new Map();
  for (const row of input.samples) {
    requireValid(typeof row.scenario === 'string' && row.scenario.length > 0 && row.scenario.length <= 160, 'Invalid scenario');
    requireValid(['baseline', 'candidate'].includes(row.variant), 'Invalid variant');
    for (const key of ['concurrency', 'operations', 'elapsedNanos'])
      requireValid(Number.isFinite(row[key]) && row[key] > 0, `Missing/invalid ${key}`);
    requireValid(Number.isSafeInteger(row.concurrency) && Number.isSafeInteger(row.operations), 'Counts must be whole completed operations');
    for (const key of ['block', 'iteration', 'dbDelayMicros', 'batchRows', ...workFields])
      requireValid(Number.isSafeInteger(row[key]) && row[key] >= 0, `Missing/invalid ${key}`);
    const key = cellKey(row);
    if (!cells.has(key)) cells.set(key, new Map());
    const blocks = cells.get(key);
    if (!blocks.has(row.block)) blocks.set(row.block, {baseline: [], candidate: []});
    blocks.get(row.block)[row.variant].push(row);
  }
  for (const blocks of cells.values()) for (const pair of blocks.values()) {
    requireValid(pair.baseline.length > 0 && pair.baseline.length === pair.candidate.length, 'Missing or unequal paired observations');
    const ids = rows => rows.map(row => row.iteration).sort((a, b) => a - b);
    const baseline = ids(pair.baseline), candidate = ids(pair.candidate);
    requireValid(new Set(baseline).size === baseline.length && JSON.stringify(baseline) === JSON.stringify(candidate), 'Duplicate or mismatched paired iterations');
    requireValid(pair.baseline.every(row => row.operations === pair.candidate.find(other => other.iteration === row.iteration).operations), 'Paired work differs');
  }
  return cells;
}

export function compareMetric(pairs, metric, seed) {
  const baselineRows = pairs.flatMap(pair => pair.baseline), candidateRows = pairs.flatMap(pair => pair.candidate);
  if (metric.field && [...baselineRows, ...candidateRows].some(row => !Number.isFinite(row[metric.field]) || row[metric.field] < 0))
    return {name: metric.name, status: 'inconclusive', reason: 'Unavailable measurements are not silently omitted'};
  if (metric.field === 'processCpuNanos' && [baselineRows, candidateRows].some(rows => rows.every(row => row.processCpuNanos === 0)))
    return {name: metric.name, status: 'inconclusive', reason: 'CPU observations are below measurement resolution'};
  const baseline = estimate(baselineRows, metric), candidate = estimate(candidateRows, metric);
  const delta = (candidate - baseline) * (metric.throughput ? -1 : 1); // Positive always means worse.
  const rng = random(seed);
  const differences = [];
  for (let i = 0; i < policy.bootstrapReplicates; i++) {
    const sampled = Array.from({length: pairs.length}, () => pairs[Math.floor(rng() * pairs.length)]);
    const a = estimate(sampled.flatMap(pair => pair.baseline), metric);
    const b = estimate(sampled.flatMap(pair => pair.candidate), metric);
    differences.push((b - a) * (metric.throughput ? -1 : 1));
  }
  const low = percentile(differences, .025), high = percentile(differences, .975);
  const sufficient = pairs.length >= policy.minBlocks && baselineRows.length >= metric.minimum;
  const status = sufficient ? (low > 0 ? 'regression' : high <= 0 ? 'pass' : 'inconclusive') : 'inconclusive';
  return {name: metric.name, status, baseline, candidate, ratio: baseline === 0 ? null : candidate / baseline,
    orientedDifference: delta, confidenceInterval: [low, high], pairedBlocks: pairs.length,
    observationsPerVariant: baselineRows.length, requiredObservationsPerVariant: metric.minimum,
    ...(sufficient ? {} : {reason: 'Insufficient independent blocks or tail observations'})};
}

const validCalibrationDesign = design => design?.scheme === 'randomized-balanced-blocks-v1' &&
  design.cellMode === 'sequential-independent' && Number.isSafeInteger(design.seed) &&
  design.seed >= 0 && design.seed <= 0xffffffff;

function calibrationRandom(seed) {
  let state = seed >>> 0;
  return () => {
    // Keep the permutation stream in uint32 even across millions of block flips.
    // The existing A/B bootstrap generator and its sequence remain untouched.
    state = (state + 0x6d2b79f5) >>> 0;
    let value = state;
    value = Math.imul(value ^ value >>> 15, value | 1);
    value ^= value + Math.imul(value ^ value >>> 7, value | 61);
    return ((value ^ value >>> 14) >>> 0) / 4294967296;
  };
}

function balancedBlocks(cells) {
  for (const blocks of cells.values()) for (const pair of blocks.values()) {
    if (pair.baseline.length < 2 || pair.baseline.length % 2 !== 0) return false;
    let baselineFirst = 0;
    for (const row of pair.baseline) {
      const other = pair.candidate.find(candidate => candidate.iteration === row.iteration);
      if (![0, 1].includes(row.orderInBlock) || other.orderInBlock !== 1 - row.orderInBlock) return false;
      if (row.orderInBlock === 0) baselineFirst++;
    }
    if (baselineFirst * 2 !== pair.baseline.length) return false;
  }
  return true;
}

/** One complete-null A/A family test. Blocks, not observations, exchange labels.
  * Cells must represent declared independent collection runs. Positive pooled
  * scales are label invariant; they balance units, not estimated standard errors.
  * Exported separately so small permutation spaces can be checked exhaustively.
  */
export function permutationCalibration(cells) {
  let blockOffset = 0;
  const coordinates = [];
  for (const [key, blocks] of [...cells.entries()].sort(([a], [b]) => a.localeCompare(b))) {
    const records = [];
    for (const [, pair] of [...blocks.entries()].sort(([a], [b]) => a - b)) {
      for (const [side, variant] of ['baseline', 'candidate'].entries())
        for (const row of [...pair[variant]].sort((a, b) => a.iteration - b.iteration))
          records.push({row, side, block: blockOffset});
      blockOffset++;
    }
    for (const metric of metrics) {
      const values = records.map(({row, side, block}) => ({side, block, operations: row.operations,
        value: metric.throughput ? row.elapsedNanos : row[metric.field] * (metric.scale ?? 1) /
          (metric.perOperation ? row.operations : 1)}));
      if (metric.quantile) values.sort((a, b) => a.value - b.value);
      const sum = values.reduce((total, entry) => total + entry.value, 0);
      const pooled = metric.throughput ? values.reduce((total, entry) => total + entry.operations, 0) * 1e9 / sum
        : metric.quantile ? values[Math.ceil(metric.quantile * values.length) - 1].value : sum / values.length;
      // A zero pooled quantile can coexist with nonzero tails. Only an all-zero
      // coordinate receives unit scale, in which case every difference is zero.
      const scale = pooled > 0 ? pooled : sum > 0 ? sum / values.length : 1;
      coordinates.push({cell: key, metric: metric.name, values, scale, quantile: metric.quantile,
        throughput: metric.throughput, count: values.length / 2});
    }
  }
  const difference = (coordinate, signs) => {
    const counts = [0, 0], sums = [0, 0], operations = [0, 0], quantiles = [0, 0];
    const rank = Math.ceil(coordinate.count * (coordinate.quantile ?? 0));
    for (const entry of coordinate.values) {
      const side = entry.side ^ signs[entry.block];
      counts[side]++;
      if (coordinate.quantile) {
        if (counts[side] === rank) quantiles[side] = entry.value;
      } else {
        sums[side] += entry.value;
        operations[side] += entry.operations;
      }
    }
    const estimates = coordinate.quantile ? quantiles : coordinate.throughput
      ? sums.map((sum, side) => operations[side] * 1e9 / sum)
      : sums.map((sum, side) => sum / counts[side]);
    return (estimates[1] - estimates[0]) * (coordinate.throughput ? -1 : 1);
  };
  const signs = new Uint8Array(blockOffset);
  const observed = coordinates.map(coordinate => difference(coordinate, signs));
  const statistics = observed.map((delta, index) => Math.abs(delta) / coordinates[index].scale);
  const maximum = Math.max(...statistics);
  const exact = blockOffset <= 12;
  const permutations = exact ? 2 ** blockOffset : policy.calibrationPermutations;
  const rng = calibrationRandom(policy.calibrationSeed);
  const maxima = [];
  for (let permutation = 0; permutation < permutations; permutation++) {
    for (let block = 0; block < blockOffset; block++) signs[block] = exact
      ? (permutation >>> block) & 1 : rng() < .5 ? 0 : 1;
    let largest = 0;
    for (const coordinate of coordinates)
      largest = Math.max(largest, Math.abs(difference(coordinate, signs)) / coordinate.scale);
    maxima.push(largest);
  }
  // Include near-ties conservatively to avoid floating-point anti-conservatism.
  const exceeds = value => value >= maximum - 100 * Number.EPSILON * Math.max(1, maximum);
  const extreme = maxima.filter(exceeds).length;
  const pValue = exact ? extreme / permutations : (extreme + 1) / (permutations + 1);
  const criticalValue = percentile(maxima, 1 - policy.calibrationAlpha);
  return {status: pValue <= policy.calibrationAlpha ? 'drift' : 'stable', pValue,
    alpha: policy.calibrationAlpha, seed: policy.calibrationSeed, permutations, exact,
    metricCount: coordinates.length, blocks: blockOffset, maximum, criticalValue,
    inference: 'One complete-null family diagnostic; no individual metric significance claims',
    diagnostics: coordinates.map((coordinate, index) => ({cell: coordinate.cell, name: coordinate.metric,
      orientedDifference: observed[index], pooledScale: coordinate.scale, statistic: statistics[index],
      exceedsFamilyCriticalValue: statistics[index] > criticalValue}))};
}

function calibrationFamily(cells, calibrationCells, metadataIssues) {
  const reasons = [];
  if (metadataIssues.length) reasons.push('Matched provenance and randomized collection design are required');
  if (cells.size !== calibrationCells.size || [...cells.keys()].some(key => !calibrationCells.has(key)))
    reasons.push('The complete comparison and calibration cell families must match');
  for (const [key, blocks] of calibrationCells) {
    const rows = [...blocks.values()].flatMap(pair => [...pair.baseline, ...pair.candidate]);
    for (const metric of metrics) {
      if (blocks.size < policy.minBlocks || rows.length / 2 < metric.minimum)
        reasons.push(`${key}/${metric.name}: insufficient blocks or observations`);
      if (metric.field && rows.some(row => !Number.isFinite(row[metric.field]) || row[metric.field] < 0))
        reasons.push(`${key}/${metric.name}: unavailable observations`);
      if (metric.field === 'processCpuNanos' && ['baseline', 'candidate'].some(variant =>
        rows.filter(row => row.variant === variant).every(row => row.processCpuNanos === 0)))
        reasons.push(`${key}/${metric.name}: below measurement resolution`);
    }
  }
  return reasons.length ? {status: 'inconclusive', pValue: null, seed: policy.calibrationSeed,
    permutations: 0, metricCount: cells.size * metrics.length, reasons, diagnostics: []}
    : permutationCalibration(calibrationCells);
}

export function evaluate(input, calibration) {
  const cells = validate(input);
  const calibrationCells = calibration ? validate(calibration) : new Map();
  const metadataIssues = [];
  if (input.metadata.profilingEnabled === true || calibration?.metadata.profilingEnabled === true)
    metadataIssues.push('Profiling diagnostics cannot establish performance acceptance');
  const hash = value => typeof value === 'string' && /^[0-9a-f]{64}$/i.test(value);
  const hashMap = value => value && typeof value === 'object' && !Array.isArray(value) &&
    Object.keys(value).length > 0 && Object.values(value).every(hash);
  const environmentKeys = ['javaVersion', 'javaVm', 'osName', 'osVersion', 'osArch',
    'processors', 'jvmMaxHeapBytes', 'postgresVersion', 'nixFlakeSha256', 'nixLockSha256'];
  const validEnvironment = environment => environment && environmentKeys.every(key =>
    typeof environment[key] === 'string' && environment[key].length > 0) &&
    hash(environment.nixFlakeSha256) && hash(environment.nixLockSha256) &&
    /^[1-9][0-9]*$/.test(environment.processors) && /^[1-9][0-9]*$/.test(environment.jvmMaxHeapBytes);
  if (input.metadata.comparison !== 'baseline-candidate') metadataIssues.push('Input must measure baseline versus candidate, not calibration');
  if (!/^[0-9a-f]{40}$/i.test(input.metadata.baselineReference ?? '')) metadataIssues.push('A full baseline commit identity is required');
  if (!hashMap(input.metadata.workingSourceSha256)) metadataIssues.push('Valid working source SHA-256 fingerprints are required');
  if (!hash(input.metadata.frameworkArtifactSha256) && !hashMap(input.metadata.frameworkArtifactSha256)) metadataIssues.push('Valid loaded framework artifact SHA-256 fingerprints are required');
  if (!validEnvironment(input.metadata.environment)) metadataIssues.push('Complete runtime/database/Nix environment identity is required');
  if (!validCalibrationDesign(input.metadata.calibrationDesign) || !balancedBlocks(cells))
    metadataIssues.push('Randomized balanced blocks with declared independent cell collection are required');
  if (!calibration || calibration.metadata.comparison !== 'baseline-baseline') metadataIssues.push('Matched baseline/baseline calibration is required');
  if (calibration) {
    const stable = value => value && typeof value === 'object' ? JSON.stringify(Object.entries(value).sort()) : value;
    for (const key of ['baselineReference', 'frameworkArtifactSha256', 'environment', 'calibrationDesign'])
      if (stable(calibration.metadata[key]) !== stable(input.metadata[key])) metadataIssues.push(`Calibration ${key} differs`);
    if (!validEnvironment(calibration.metadata.environment)) metadataIssues.push('Complete calibration environment identity is required');
    if (!validCalibrationDesign(calibration.metadata.calibrationDesign) || !balancedBlocks(calibrationCells))
      metadataIssues.push('Calibration must use the declared randomized balanced block design');
    const fingerprints = metadata => JSON.stringify(Object.entries(metadata.workingSourceSha256 ?? {}).sort());
    if (fingerprints(calibration.metadata) !== fingerprints(input.metadata)) metadataIssues.push('Calibration source fingerprints differ');
  }
  const family = calibrationFamily(cells, calibrationCells, metadataIssues);
  const reports = [];
  let seed = 928374;
  for (const [key, blocks] of cells) {
    const pairs = [...blocks.values()];
    const [scenario, concurrency, dbDelayMicros] = JSON.parse(key);
    const work = workFields.map(field => {
      const baseline = mean(pairs.flatMap(pair => pair.baseline).map(row => row[field] / row.operations));
      const candidate = mean(pairs.flatMap(pair => pair.candidate).map(row => row[field] / row.operations));
      return {name: field + 'PerOperation', baseline, candidate, status: candidate > baseline ? 'regression' : 'pass'};
    });
    // Switching identical mutations from individual executes into a batch raises
    // batchRows while reducing calls. Domain postconditions establish equal work;
    // batch entries are diagnostics, not an automatic regression penalty.
    const batchEntriesPerOperation = Object.fromEntries(['baseline', 'candidate'].map(variant =>
      [variant, mean(pairs.flatMap(pair => pair[variant]).map(row => row.batchRows / row.operations))]));
    const observedTiming = metrics.map(metric => compareMetric(pairs, metric, seed++));
    // Preserve the existing A/B bootstrap seed schedule; A/A now has a separate
    // family permutation stream rather than six uncorrected tests per cell.
    if (calibrationCells.has(key)) seed += metrics.length;
    const calibrationReport = family.diagnostics.filter(metric => metric.cell === key);
    const calibrationStatus = family.status === 'inconclusive' ? 'missing-or-insufficient' : 'usable';
    const drift = family.status === 'drift';
    const timing = observedTiming.map(metric => {
      const usable = family.status === 'stable';
      return usable ? metric : {...metric, uncalibratedStatus: metric.status, status: 'inconclusive',
        calibrationReason: 'The complete matched A/A family must be sufficiently sampled and free of detected drift'};
    });
    const allRows = pairs.flatMap(pair => [...pair.baseline, ...pair.candidate]);
    const missingConnectionEvidence = allRows.some(row =>
      !Number.isSafeInteger(row.activeConnectionsBefore) || row.activeConnectionsBefore < 0 ||
      !Number.isSafeInteger(row.activeConnectionsAfter) || row.activeConnectionsAfter < 0);
    const leaks = allRows.some(row => row.activeConnectionsAfter > row.activeConnectionsBefore);
    const status = work.some(metric => metric.status === 'regression') || timing.some(metric => metric.status === 'regression') || leaks
      ? 'regression' : calibrationStatus !== 'usable' || drift || missingConnectionEvidence || timing.some(metric => metric.status !== 'pass')
        ? 'inconclusive' : 'pass';
    reports.push({scenario, concurrency, dbDelayMicros, status,
      latencyScope: concurrency > 1 ? 'batch completion, not individual request tails' : 'one domain operation',
      delayScope: dbDelayMicros ? 'synthetic JDBC-call sensitivity, not network RTT' : 'local database',
      calibrationStatus, calibrationDrift: drift, connectionLeak: leaks, missingConnectionEvidence,
      work, batchEntriesPerOperation, timing, calibration: calibrationReport});
  }
  const status = reports.some(report => report.status === 'regression') ? 'regression'
    : metadataIssues.length || reports.some(report => report.status !== 'pass') ? 'inconclusive' : 'pass';
  return {schemaVersion: 1, status, releaseReady: false, policy, metadataIssues, calibrationFamily: family, cells: reports,
    scope: 'Only supplied domain-operation workloads; not a complete browser/runtime release approval',
    unmeasuredReleaseGates: ['browser submit/render/reconnect latency', 'real network delay/loss',
      'shared-pool sustained load and queue depth', 'post-GC retained memory and peak memory',
      'production password cost and host data distributions']};
}

export function parseReport(source) {
  try {
    const document = JSON.parse(source);
    if (document.schemaVersion === 1) return document;
  } catch { /* Streaming reports have one JSON record per line. */ }
  const rows = source.trim().split(/\r?\n/).map(line => JSON.parse(line));
  requireValid(rows.length >= 3 && rows[0].kind === 'metadata' && rows.at(-1).kind === 'completion', 'Incomplete streaming benchmark');
  const samples = rows.slice(1, -1);
  requireValid(samples.every(row => row.kind === 'sample') && rows.at(-1).samples === samples.length, 'Incomplete or unexpected streaming records');
  return {schemaVersion: 1, metadata: rows[0], samples};
}

async function main(args) {
  const options = {};
  for (let index = 0; index < args.length; index += 2) {
    requireValid(['--input', '--calibration', '--report'].includes(args[index]) && args[index + 1], 'Usage: --input file [--calibration file] --report file');
    requireValid(!options[args[index]], 'Repeated option');
    options[args[index]] = args[index + 1];
  }
  requireValid(options['--input'] && options['--report'], 'Input and report paths are required');
  const input = parseReport(await readFile(options['--input'], 'utf8'));
  const calibration = options['--calibration'] ? parseReport(await readFile(options['--calibration'], 'utf8')) : undefined;
  const result = evaluate(input, calibration);
  await writeFile(options['--report'], JSON.stringify(result, null, 2) + '\n');
  console.log(JSON.stringify({status: result.status, cells: result.cells.map(({scenario, concurrency, dbDelayMicros, status}) =>
    ({scenario, concurrency, dbDelayMicros, status})), metadataIssues: result.metadataIssues}));
  if (result.status !== 'pass') process.exitCode = 1;
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href)
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 2; });
