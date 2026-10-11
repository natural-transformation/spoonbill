import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';
import {appendFileSync, closeSync, openSync, readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import {delimiter, join} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {completedWork, dimensions, identity, requiredMetrics, requiredResources} from './analyze.mjs';
import {usefulWork} from './coverage.mjs';
import {HarnessInterrupted, observe} from './run.mjs';

const fixtures = {
  'historical-transport': {main: 'spoonbill.performance.StandaloneTransportBenchmark', file: 'StandaloneTransportBenchmark.scala', schema: 'spoonbill-websocket-transport-v1'},
  'historical-core': {main: 'spoonbill.performance.CoreGuardedSessionBenchmark', file: 'CoreGuardedSessionBenchmark.scala', schema: 'spoonbill-core-guarded-session-v1'},
};
const positive = value => typeof value === 'number' && Number.isFinite(value) && value > 0;
const count = value => Number.isSafeInteger(value) && value >= 0;
const sha = value => typeof value === 'string' && /^[a-f0-9]{64}$/.test(value);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');

// Same content ordering as misc/websocket-performance/run.mjs; absolute checkout
// locations do not alter identity. Recheck after execution to detect build races.
export function classpathIdentity(classpath) {
  if (typeof classpath !== 'string' || !classpath || /[\r\n]/.test(classpath) || classpath.split(delimiter).some(entry => !entry))
    throw new Error('Expected nonempty explicit classpath entries');
  const hash = createHash('sha256');
  function visit(file, relative) {
    const info = statSync(file);
    if (info.isDirectory()) for (const name of readdirSync(file).sort()) visit(join(file, name), `${relative}/${name}`);
    else if (info.isFile()) hash.update(relative).update('\0').update(readFileSync(file)).update('\0');
    else throw new Error('Classpath contains a non-file entry');
  }
  classpath.split(delimiter).forEach((entry, index) => visit(entry, String(index)));
  return hash.digest('hex');
}

export function legacyPlan(request) {
  const fixture = fixtures[request.cell?.group];
  if (!fixture || request.pass !== 'latency') throw new Error('Legacy collector supports historical transport/core latency only; memory and operations protocols are unimplemented');
  const {warmupOperations: warmup, measuredOperations: measured} = request.work ?? {};
  if (!count(warmup) || warmup < 1 || !count(measured) || measured < 1) throw new Error('Legacy fixtures require positive exact warmup and measured work');
  const {concurrency, payloadBytes} = request.cell.dimensions ?? {};
  const args = [request.variant, request.artifactSha256, String(request.block)];
  if (!['candidate', 'baseline'].includes(request.variant) || !count(request.block) || !sha(request.artifactSha256)) throw new Error('Invalid requested process identity');
  if (request.cell.group === 'historical-transport') {
    if (!count(concurrency) || concurrency < 1 || concurrency > 64 || !count(payloadBytes) || payloadBytes < 1 || payloadBytes > 65535 || warmup % concurrency || measured % concurrency)
      throw new Error('Exact transport counts must divide across valid connections');
    args.push(String(concurrency), String(payloadBytes), String(warmup / concurrency), String(measured / concurrency));
  } else {
    if (concurrency !== 1) throw new Error('Historical core fixture is direct-serial');
    args.push(String(warmup), String(measured));
  }
  return {fixture, args};
}

export function legacyWorkContract(request) {
  const {fixture} = legacyPlan(request);
  return {schema: 'spoonbill-legacy-latency-work-v1', group: request.cell.group,
    dimensions: request.cell.dimensions, work: request.work,
    fixtureSha256: digest(readFileSync(new URL(`../../websocket-performance/${fixture.file}`, import.meta.url))),
    assertions: usefulWork(request.cell, request.cell.dimensions),
    teardownMeasured: request.cell.group === 'historical-core'};
}

export function parseLegacyRecord(stdout, request) {
  const {fixture} = legacyPlan(request);
  const records = stdout.split(/\r?\n/).filter(line => line.startsWith('{')).map(line => JSON.parse(line));
  if (records.length !== 1) throw new Error('Expected exactly one legacy result');
  const row = records[0], {warmupOperations: warmup, measuredOperations: measured} = request.work;
  if (row.schema !== fixture.schema || row.identity !== request.artifactSha256 || row.variant !== request.variant || row.block !== String(request.block))
    throw new Error('Legacy process identity mismatch');
  const transport = request.cell.group === 'historical-transport';
  if (transport) {
    const {concurrency, payloadBytes} = request.cell.dimensions;
    if (row.connections !== concurrency || row.payloadBytes !== payloadBytes || row.messages !== measured || row.warmupPerConnection !== warmup / concurrency ||
        row.teardownMeasured !== false || !Array.isArray(row.setupLatencyNs) || row.setupLatencyNs.length !== concurrency || !row.setupLatencyNs.every(positive))
      throw new Error('Transport completed-work mismatch');
  } else if (row.iterations !== measured || row.completedOperations !== measured || row.warmupIterations !== warmup ||
      row.guardsOpened !== warmup + measured || row.guardsClosed !== warmup + measured || row.remainingGuards !== 0 ||
      row.remainingInputs !== 0 || row.remainingApplications !== 0 || !positive(row.initialOutputBytes) ||
      row.executionContext !== 'direct-serial' || row.teardownMeasured !== true) throw new Error('Core completed-work mismatch');
  const latency = transport ? row.roundTripLatencyNs : row.operationLatencyNs;
  const throughput = transport ? row.messagesPerSecond : row.operationsPerSecond;
  if (!positive(row.elapsedNs) || !positive(throughput) || !['p50', 'p95', 'p99'].every(name => positive(latency?.[name])) ||
      latency.p50 > latency.p95 || latency.p95 > latency.p99 ||
      Math.abs(throughput - measured * 1e9 / row.elapsedNs) > Math.max(1e-6, throughput * 1e-10)) throw new Error('Invalid legacy timing or throughput');
  if (typeof row.javaVersion !== 'string' || !row.javaVersion) throw new Error('Missing JVM identity');
  return {record: row, metrics: {latencyP50Ns: latency.p50, latencyP95Ns: latency.p95, latencyP99Ns: latency.p99, operationsPerSecond: throughput}};
}

export function collectLegacy(request, classpath, {java = 'java', timeoutMs = 180000} = {}) {
  if (!process.env.IN_NIX_SHELL) throw new Error('Run the collector inside the repository Nix environment');
  const {fixture, args} = legacyPlan(request);
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs < 1 || timeoutMs > 180000) throw new Error('Invalid bounded Java timeout');
  if (classpathIdentity(classpath) !== request.artifactSha256) throw new Error('Classpath differs from requested immutable artifact');
  const contract = legacyWorkContract(request);
  if (identity(contract) !== request.completedWorkContractSha256) throw new Error('Requested work contract differs from the actual legacy fixture');
  // JVM fixture creates threads only. It inherits the outer run.mjs collector
  // process group, so harness interruption also kills this child. Never detach.
  const result = spawnSync(java, ['-Xms512m', '-Xmx512m', '-cp', classpath, fixture.main, ...args],
    {encoding: 'utf8', timeout: timeoutMs, killSignal: 'SIGKILL', maxBuffer: 4 * 1024 * 1024});
  const raw = {status: result.status, signal: result.signal, error: result.error?.message ?? null, stdout: result.stdout ?? '', stderr: result.stderr ?? ''};
  // Always retain actual JVM output, including failed/truncated runs. The outer
  // harness captures stderr separately and rejects a collector that exits early.
  process.stderr.write(JSON.stringify({kind: 'legacy-process', ...raw}) + '\n');
  if (raw.error || raw.status !== 0) throw new Error('Legacy JVM failed; retain the raw process log');
  if (classpathIdentity(classpath) !== request.artifactSha256) throw new Error('Classpath changed during collection');
  const parsed = parseLegacyRecord(raw.stdout, request);
  const diagnostic = name => count(parsed.record[name]) ? parsed.record[name] : null;
  return {kind: 'sample', artifactSha256: request.artifactSha256, completedWorkContractSha256: identity(contract),
    completedWorkVerified: true, warmupOperations: request.work.warmupOperations, completedOperations: request.work.measuredOperations,
    profilingEnabled: false, clockResolutionNs: null, measurementResolutionVerified: false,
    measuredResolutions: Object.fromEntries(request.cell.metrics.map(name => [name, null])),
    metrics: {...Object.fromEntries(request.cell.metrics.map(name => [name, null])), ...parsed.metrics},
    resources: Object.fromEntries(requiredResources.map(name => [name, null])), counters: {},
    diagnostics: {jitCompilationMilliseconds: diagnostic('jitCompilationTimeMsDelta'), gcCount: diagnostic('gcCollectionCountDelta'), gcMilliseconds: diagnostic('gcCollectionTimeMsDelta')},
    limitations: ['Sensor resolutions have not been calibrated.', 'Full resource and memory accounting is unsupported.', 'This latency observation alone cannot pass Phase 0 or the performance gate.'],
    legacyRecord: parsed.record};
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv[2] === '--probe') {
      const [, manifestFile, cellId, classpathFile, destination, warmupArg, measuredArg] = process.argv.slice(2);
      if (!measuredArg) throw new Error('Usage: --probe manifest.json CELL classpath.txt NEW_OUTPUT.json WARMUP MEASURED');
      const manifest = JSON.parse(readFileSync(manifestFile, 'utf8')), cell = manifest.cells.find(cell => cell.id === cellId);
      if (!cell) throw new Error('Unknown probe cell');
      const classpath = readFileSync(classpathFile, 'utf8').trim();
      const request = {cell: {...cell, dimensions: dimensions(manifest, cell), metrics: requiredMetrics(manifest, cell)},
        pass: 'latency', variant: 'baseline', block: 0, artifactSha256: classpathIdentity(classpath),
        work: {...completedWork(manifest, cell, 'latency'), warmupOperations: Number(warmupArg), measuredOperations: Number(measuredArg)}};
      request.completedWorkContractSha256 = identity(legacyWorkContract(request));
      // Probe output cannot be mistaken for frozen evidence. Artifact identity
      // says what ran, not which historical Git revision it was built from.
      const output = openSync(destination, 'wx');
      let raw;
      try {
        raw = openSync(`${destination}.processes.jsonl`, 'wx');
        writeFileSync(output, JSON.stringify({kind: 'unfrozen-legacy-probe-start', acceptanceEvidence: false, request}) + '\n');
        request.runId = `legacy-probe-${process.pid}-${Date.now()}`;
        const sample = await observe([process.execPath, fileURLToPath(import.meta.url), classpathFile], request, 185000,
          record => appendFileSync(raw, JSON.stringify(record) + '\n'));
        appendFileSync(output, JSON.stringify({kind: 'unfrozen-legacy-probe', acceptanceEvidence: false, request, sample}) + '\n');
      } catch (error) {
        appendFileSync(output, JSON.stringify({kind: 'failure', error: error.message}) + '\n');
        throw error;
      } finally { closeSync(output); if (raw !== undefined) closeSync(raw); }
      console.log(`Wrote unfrozen diagnostic probe: ${destination}`);
    } else {
      if (process.argv.length !== 3) throw new Error('Usage: node legacy-collector.mjs classpath.txt < request.json (under run.mjs)');
      const request = JSON.parse(readFileSync(0, 'utf8'));
      console.log(JSON.stringify(collectLegacy(request, readFileSync(process.argv[2], 'utf8').trim())));
    }
  } catch (error) { console.error(error.message); process.exitCode = error instanceof HarnessInterrupted ? error.exitCode : process.exitCode || 1; }
}
