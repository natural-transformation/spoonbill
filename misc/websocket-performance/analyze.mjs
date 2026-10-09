import {readFileSync, writeFileSync} from 'node:fs';

const [comparisonFile, calibrationFile, outputFile] = process.argv.slice(2);
if (!outputFile) throw new Error('Usage: node analyze.mjs comparison.jsonl calibration.jsonl report.json');
function read(file, mode) {
  const lines = readFileSync(file, 'utf8').trim().split('\n').map(line => JSON.parse(line));
  const metadata = lines[0], completion = lines.at(-1), samples = lines.slice(1, -1);
  if (metadata.kind !== 'metadata' || metadata.mode !== mode || completion.kind !== 'completion' ||
      completion.observations !== samples.length || samples.some(row => row.kind !== 'sample'))
    throw new Error(`Incomplete or failed ${mode} collection`);
  return {metadata, samples};
}
const comparison = read(comparisonFile, 'comparison'), calibration = read(calibrationFile, 'calibration');
for (const key of ['suite', 'artifactSha256', 'fixtureSha256', 'flakeLockSha256', 'jvmOptions', 'runtime', 'cells'])
  if (JSON.stringify(comparison.metadata[key]) !== JSON.stringify(calibration.metadata[key]))
    throw new Error(`Comparison/calibration provenance differs: ${key}`);
const count = row => row.messages ?? row.completedOperations;
const latency = row => row.roundTripLatencyNs ?? row.operationLatencyNs;
const metrics = [
  ['blockP50Ns', row => latency(row).p50, false],
  ['blockP95Ns', row => latency(row).p95, false],
  ['blockP99Ns', row => latency(row).p99, false],
  ['operationsPerSecond', row => row.messagesPerSecond ?? row.operationsPerSecond, true],
  ['cpuNsPerOperation', row => row.processCpuNs / count(row), false],
  ['allocatedBytesPerOperation', row => row.jvmTotalAllocatedBytes === null ? NaN : row.jvmTotalAllocatedBytes / count(row), false],
];
const cell = row => `${row.schema}:${row.connections ?? 1}:${row.payloadBytes ?? 0}`;
const cells = [...new Set(comparison.samples.map(cell))].sort();
if (JSON.stringify(cells) !== JSON.stringify([...new Set(calibration.samples.map(cell))].sort()))
  throw new Error('Calibration workload cells differ');
const metricCount = cells.length * metrics.length;
// Family-wise conservative intervals; no tolerated slowdown is added.
const tail = .05 / metricCount / 2;
let seed = 246813579;
function random() {
  seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
  return (seed >>> 0) / 4294967296;
}
const mean = values => values.reduce((sum, value) => sum + value, 0) / values.length;
const quantile = (sorted, q) => sorted[Math.min(sorted.length - 1, Math.floor(q * sorted.length))];
function pairs(samples, key) {
  const blocks = new Map();
  for (const row of samples.filter(row => cell(row) === key)) {
    if (!['baseline', 'candidate'].includes(row.variant)) throw new Error('Unknown variant');
    if (!blocks.has(row.block)) blocks.set(row.block, {});
    const pair = blocks.get(row.block);
    if (pair[row.variant]) throw new Error('Duplicate block variant');
    pair[row.variant] = row;
  }
  return [...blocks.values()].map(pair => {
    if (!pair.baseline || !pair.candidate || count(pair.baseline) !== count(pair.candidate) || count(pair.baseline) <= 0)
      throw new Error('Missing pair or unequal completed work');
    if (pair.baseline.initialOutputBytes !== pair.candidate.initialOutputBytes) throw new Error('Initial output work differs');
    for (const row of [pair.baseline, pair.candidate]) {
      if ([row.remainingGuards, row.remainingInputs, row.remainingApplications].some(value => value !== undefined && value !== 0))
        throw new Error('Lifecycle resource assertion failed');
    }
    return pair;
  });
}
function estimate(paired, extract, higherIsBetter) {
  const baseline = paired.map(pair => extract(pair.baseline));
  const candidate = paired.map(pair => extract(pair.candidate));
  if ([...baseline, ...candidate].some(value => !Number.isFinite(value) || value <= 0))
    return {status: 'inconclusive', reason: 'Unsupported or non-positive measurements'};
  const differences = baseline.map((base, i) => (candidate[i] - base) / base * (higherIsBetter ? -1 : 1));
  const resampled = [];
  for (let sample = 0; sample < 20000; sample++) {
    let total = 0;
    for (let i = 0; i < differences.length; i++) total += differences[Math.floor(random() * differences.length)];
    resampled.push(total / differences.length);
  }
  resampled.sort((a, b) => a - b);
  const interval = [quantile(resampled, tail), quantile(resampled, 1 - tail)];
  return {baseline: mean(baseline), candidate: mean(candidate), ratio: mean(candidate) / mean(baseline),
    worseFraction: mean(differences), confidenceInterval: interval,
    status: paired.length < 30 ? 'inconclusive' : interval[0] > 0 ? 'regression' : interval[1] <= 0 ? 'pass' : 'inconclusive'};
}
const report = cells.map(key => {
  const actual = pairs(comparison.samples, key), control = pairs(calibration.samples, key);
  const results = metrics.map(([name, extract, higherIsBetter]) => {
    const observed = estimate(actual, extract, higherIsBetter);
    const aa = estimate(control, extract, higherIsBetter);
    const usable = control.length >= 30 && aa.confidenceInterval && aa.confidenceInterval[0] <= 0 && aa.confidenceInterval[1] >= 0;
    return {name, ...observed, status: usable ? observed.status : 'inconclusive', calibration: aa,
      ...(usable ? {} : {reason: 'Baseline calibration is insufficient or shows drift'})};
  });
  return {cell: key, blocks: actual.length, metrics: results};
});
const statuses = report.flatMap(row => row.metrics.map(metric => metric.status));
const status = statuses.includes('regression') ? 'regression' : statuses.every(value => value === 'pass') ? 'pass' : 'inconclusive';
const result = {status, confidenceFamily: .95, correction: 'Bonferroni paired-block percentile bootstrap',
  replicates: 20000, minBlocks: 30, slowdownAllowance: 0, metadata: comparison.metadata, cells: report,
  scope: 'Only supplied workloads. Latency metrics compare means of independently measured block quantiles, not pooled tails.'};
writeFileSync(outputFile, JSON.stringify(result, null, 2) + '\n');
console.log(JSON.stringify({status, cells: report.map(row => ({cell: row.cell,
  metrics: row.metrics.map(metric => ({name: metric.name, ratio: metric.ratio, status: metric.status}))}))}));
if (status !== 'pass') process.exitCode = 1;
