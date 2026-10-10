import {spawn} from 'node:child_process';
import {appendFileSync, closeSync, openSync, readFileSync, writeFileSync} from 'node:fs';
import {createHash, randomUUID} from 'node:crypto';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {setTimeout as pause} from 'node:timers/promises';
import path from 'node:path';
import {createPostgresWireProxy} from './postgres-wire-proxy.mjs';
import {classpathIdentity} from './legacy-collector.mjs';
import {identity} from './analyze.mjs';
import {HarnessInterrupted, observe} from './run.mjs';

const repository = fileURLToPath(new URL('../../../', import.meta.url));
const suites = ['JdbcReferenceHostSpec', 'JdbcBrowserSecuritySpec'];
const fail = code => { throw new Error(code); };
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
export const scope = Object.freeze({
  kind: 'whole-jdbc-conformance-fixture', acceptanceEvidence: false, expectedSuites: 2,
  includes: ['fixture schema/account setup and teardown', 'ordinary and fault/recovery transactions',
    'durable clock checkpoints', 'maintenance and diagnostic/control SQL'],
  excludes: ['HTTP and WebSocket exchanges', 'browser workflows', 'per-login or per-cell operation budgets',
    'latency/memory calibration', 'physical network round trips', 'encrypted PostgreSQL protocol'],
  quantity: 'completed frontend Query/Sync to backend ReadyForQuery cycles, with startup counted separately; overlapping cycles never become round trips',
});

export function disposableTarget(env) {
  if (env.SPOONBILL_JDBC_TEST_USER !== 'spoonbill_test' || env.SPOONBILL_JDBC_TEST_PASSWORD || env.PGPASSWORD)
    fail('synthetic-trust-database-required');
  let url;
  try {
    if (!env.SPOONBILL_JDBC_TEST_URL?.startsWith('jdbc:postgresql://')) fail('invalid-disposable-target');
    url = new URL(env.SPOONBILL_JDBC_TEST_URL.slice(5));
  } catch (_) { fail('invalid-disposable-target'); }
  if (url.hostname !== '127.0.0.1' || url.pathname !== '/postgres' || url.username || url.password || url.hash ||
      !/^\d+$/.test(url.port) || Number(url.port) < 1 || Number(url.port) > 65535 ||
      [...url.searchParams].length !== 1 || url.searchParams.get('user') !== 'spoonbill_test')
    fail('invalid-disposable-target');
  return Number(url.port);
}

export function proxyEnvironment(env, port) {
  disposableTarget(env);
  if (!Number.isInteger(port) || port < 1 || port > 65535) fail('invalid-proxy-port');
  return {...env, SPOONBILL_JDBC_TEST_URL:
    `jdbc:postgresql://127.0.0.1:${port}/postgres?user=spoonbill_test&sslmode=disable&gssEncMode=disable`};
}

export function conformanceSummary(stdout) {
  const text = stdout.replace(/\x1b\[[0-9;]*m/g, '');
  const tests = [...text.matchAll(/^Total number of tests run:\s*(\d+)\s*$/gm)];
  const completed = [...text.matchAll(/^Suites: completed (\d+), aborted (\d+)\s*$/gm)];
  const results = [...text.matchAll(/^Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)\s*$/gm)];
  if (tests.length !== 1 || completed.length !== 1 || results.length !== 1 || !/^All tests passed\.\s*$/m.test(text))
    fail('missing-conformance-completion');
  const total = Number(tests[0][1]), values = results[0].slice(1).map(Number);
  if (!Number.isSafeInteger(total) || total < 1 || Number(completed[0][1]) !== 2 || Number(completed[0][2]) !== 0 ||
      values[0] !== total || values.slice(1).some(value => value !== 0)) fail('incomplete-conformance-work');
  return {testsCompleted: total, suitesCompleted: 2, failed: 0, canceled: 0, ignored: 0, pending: 0};
}

/** Only launch thread-only fixture processes here. The outer observe() owns the
 * detached process group; this Java child must inherit it, never detach. */
export async function measureWire({targetPort, command, env, timeoutMs = 540000,
  settleMs = 5000, onLog = () => {}, maxLogBytes = 4 * 1024 * 1024,
  mode = 'strict-sequential', maxPendingCycles = mode === 'strict-sequential' ? 1 : 32}) {
  if (!Array.isArray(command) || !command.length || command.some(value => typeof value !== 'string' || !value)) fail('invalid-fixture-command');
  for (const [value, maximum] of [[timeoutMs, 540000], [settleMs, 10000], [maxLogBytes, 4 * 1024 * 1024]])
    if (!Number.isSafeInteger(value) || value < 1 || value > maximum) fail('invalid-probe-bound');
  const proxy = await createPostgresWireProxy({targetPort, maxConnections: 32, socketTimeoutMs: 10000, mode, maxPendingCycles});
  let processResult, fixtureFailure, beforeClose, wire;
  try {
    processResult = await new Promise(resolve => {
      const child = spawn(command[0], command.slice(1), {detached: false, stdio: ['ignore', 'pipe', 'pipe'],
        env: proxyEnvironment(env, proxy.port)});
      const chunks = [], seen = {stdout: 0, stderr: 0};
      let error, drain;
      const abort = code => {
        error ??= code;
        child.kill('SIGKILL');
        drain ??= setTimeout(() => { child.stdout.destroy(); child.stderr.destroy(); }, 2000);
      };
      const deadline = setTimeout(() => abort('fixture-deadline'), timeoutMs);
      child.once('error', () => { error ??= 'fixture-spawn-failed'; });
      for (const name of ['stdout', 'stderr']) child[name].on('data', bytes => {
        const remaining = Math.max(0, maxLogBytes - seen[name]);
        seen[name] += bytes.length;
        if (remaining) {
          const bounded = bytes.subarray(0, remaining);
          if (name === 'stdout') chunks.push(bounded);
          try { onLog(name, bounded); }
          catch (_) { abort('fixture-log-sink-failed'); }
        }
        if (seen[name] > maxLogBytes) abort('fixture-log-limit');
      });
      child.once('close', (status, signal) => {
        clearTimeout(deadline); clearTimeout(drain);
        resolve({status, signal, error, observedBytes: seen, stdout: Buffer.concat(chunks).toString('utf8')});
      });
    });
    if (processResult.error || processResult.status !== 0) fixtureFailure = processResult.error ?? 'fixture-failed';
    const deadline = Date.now() + settleMs;
    while (proxy.snapshot().activeConnections && Date.now() < deadline) await pause(10);
    beforeClose = proxy.snapshot();
    if (beforeClose.activeConnections || beforeClose.pendingExchanges) fixtureFailure ??= 'fixture-sockets-unsettled';
  } finally { wire = await proxy.close(); }
  let completion = null;
  if (!fixtureFailure) {
    try { completion = conformanceSummary(processResult.stdout); }
    catch (error) { fixtureFailure = error.message; }
  }
  if (wire.status !== 'complete' || !wire.startupExchanges || !wire.completedProtocolSyncCycles) fixtureFailure ??= 'wire-inconclusive';
  return {status: fixtureFailure ? 'inconclusive' : 'complete', diagnostic: fixtureFailure ?? null,
    observationKind: 'protocol-cycles-diagnostic', mode, maxPendingCycles,
    scope, completion, wire, fixture: {exitCode: processResult.status, signal: processResult.signal,
      observedLogBytes: processResult.observedBytes}, socketsBeforeProxyClose: {
      active: beforeClose.activeConnections, pending: beforeClose.pendingExchanges}};
}

function sourceHashes() {
  return Object.fromEntries(suites.map(name => [name, digest(readFileSync(path.join(repository,
    `modules/security-jdbc/src/test/scala/spoonbill/security/jdbc/${name}.scala`)))]));
}
function instrumentationHashes() {
  return Object.fromEntries(['reference-wire-probe.mjs', 'postgres-wire-proxy.mjs', 'run.mjs', 'legacy-collector.mjs']
    .map(name => [name, digest(readFileSync(new URL(name, import.meta.url)))]));
}
function readClasspath(file) {
  const text = readFileSync(file, 'utf8').trim();
  if (!text || /[\r\n]/.test(text) || text.split(path.delimiter).some(entry => !path.isAbsolute(entry) || entry.includes('*')))
    fail('invalid-test-classpath');
  return text;
}

async function collector(classpathFile) {
  const request = JSON.parse(readFileSync(0, 'utf8'));
  if (!process.env.IN_NIX_SHELL) fail('nix-environment-required');
  const classpath = readClasspath(classpathFile), hashes = sourceHashes();
  const protocol = {mode: request.protocol?.mode, maxPendingCycles: request.protocol?.maxPendingCycles};
  if (classpathIdentity(classpath) !== request.artifactSha256 || identity({scope, suites, hashes, protocol}) !== request.completedWorkContractSha256 ||
      identity(instrumentationHashes()) !== identity(request.instrumentationSha256))
    fail('fixture-identity-changed');
  const command = ['java', '-Xms128m', '-Xmx512m', '-cp', classpath, 'org.scalatest.tools.Runner',
    ...suites.flatMap(name => ['-s', `spoonbill.security.jdbc.${name}`]), '-o'];
  const result = await measureWire({targetPort: disposableTarget(process.env), command, env: process.env, ...protocol,
    onLog: (_stream, bytes) => process.stderr.write(bytes)});
  if (classpathIdentity(classpath) !== request.artifactSha256 || identity(sourceHashes()) !== identity(hashes) ||
      identity(instrumentationHashes()) !== identity(request.instrumentationSha256)) fail('fixture-identity-changed');
  console.log(JSON.stringify({kind: 'sample', artifactSha256: request.artifactSha256,
    completedWorkContractSha256: request.completedWorkContractSha256, ...result}));
}

/** Exclusive report + private raw diagnostics. This is not collect() evidence. */
export async function probe(classpathFile, destination, {mode = 'strict-sequential',
  maxPendingCycles = mode === 'strict-sequential' ? 1 : 32} = {}) {
  if (!process.env.IN_NIX_SHELL) fail('nix-environment-required');
  disposableTarget(process.env);
  const classpath = readClasspath(classpathFile), hashes = sourceHashes(), protocol = {mode, maxPendingCycles};
  const request = {runId: randomUUID(), cell: {id: 'jdbc-conformance-wire-diagnostic'},
    pass: 'operations', variant: 'baseline', block: 0, orderInBlock: 0,
    artifactSha256: classpathIdentity(classpath), completedWorkContractSha256: identity({scope, suites, hashes, protocol}), protocol,
    instrumentationSha256: instrumentationHashes()};
  const output = openSync(destination, 'wx', 0o600);
  let raw;
  try {
    raw = openSync(`${destination}.private-processes.jsonl`, 'wx', 0o600);
    writeFileSync(output, JSON.stringify({kind: 'diagnostic-start', scope, protocol, artifactSha256: request.artifactSha256,
      artifactKind: 'actual-jdbc-test-classpath', suiteSourceSha256: hashes, instrumentationSha256: request.instrumentationSha256}) + '\n');
    const sample = await observe([process.execPath, fileURLToPath(import.meta.url), '--collector', path.resolve(classpathFile)],
      request, 600000, value => appendFileSync(raw, JSON.stringify(value) + '\n'));
    const {kind: _, ...data} = sample;
    appendFileSync(output, JSON.stringify({kind: 'jdbc-wire-diagnostic', ...data}) + '\n');
    if (data.status !== 'complete') fail('wire-probe-inconclusive');
    return data;
  } catch (error) {
    appendFileSync(output, JSON.stringify({kind: 'failure', code: error instanceof HarnessInterrupted ? error.signal : 'probe-failed'}) + '\n');
    throw error;
  } finally { closeSync(output); if (raw !== undefined) closeSync(raw); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    if (process.argv[2] === '--collector' && process.argv.length === 4) await collector(process.argv[3]);
    else if (process.argv.length === 4 || (process.argv.length === 5 && process.argv[4] === '--pipelined-cycles')) {
      const result = await probe(process.argv[2], process.argv[3], process.argv[4] ? {mode: 'pipelined-cycles'} : {});
      console.log(JSON.stringify({kind: 'jdbc-wire-diagnostic-result', status: result.status,
        testsCompleted: result.completion.testsCompleted, startupExchanges: result.wire.startupExchanges,
        completedProtocolSyncCycles: result.wire.completedProtocolSyncCycles,
        syncExchanges: result.wire.syncExchanges, physicalRoundTrips: result.wire.physicalRoundTrips,
        overlapObserved: result.wire.overlapObserved, mode: result.mode, acceptanceEvidence: false}));
    } else fail('usage-test-classpath-and-new-report-required');
  } catch (error) {
    console.error(error instanceof HarnessInterrupted ? error.signal : 'wire-probe-failed-retain-private-diagnostics');
    process.exitCode = error instanceof HarnessInterrupted ? error.exitCode : process.exitCode || 1;
  }
}
