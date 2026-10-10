import {createHash, randomUUID} from 'node:crypto';
import {spawn} from 'node:child_process';
import {appendFileSync, closeSync, openSync, readFileSync, writeFileSync} from 'node:fs';
import {performance} from 'node:perf_hooks';
import {fileURLToPath, pathToFileURL} from 'node:url';
import {completedWork, dimensions, identity, manifestIssues, provenanceIssues, requiredMetrics} from './analyze.mjs';

const digest = bytes => createHash('sha256').update(bytes).digest('hex');

export class HarnessInterrupted extends Error {
  constructor(signal) {
    super(`Measurement harness interrupted by ${signal}`);
    this.name = 'HarnessInterrupted';
    this.signal = signal;
    this.exitCode = signal === 'SIGINT' ? 130 : 143;
  }
}

// One process listener per signal, shared by concurrent observations/collections.
// Removing one owner must not disable another owner's cleanup or replace the
// embedding application's listeners. Prepend cleanup so its synchronous group
// termination precedes existing listeners that may exit immediately. Such an
// exit can still prevent asynchronous evidence finalization; SIGKILL runs none.
const interruptionOwners = new Set();
const interruptionHandlers = Object.fromEntries(['SIGINT', 'SIGTERM'].map(signal => [signal, () => {
  process.exitCode = signal === 'SIGINT' ? 130 : 143;
  for (const notify of [...interruptionOwners]) notify(signal);
}]));
function ownInterruption(notify) {
  if (!interruptionOwners.size)
    for (const [signal, handler] of Object.entries(interruptionHandlers)) process.prependListener(signal, handler);
  interruptionOwners.add(notify);
  return () => {
    interruptionOwners.delete(notify);
    if (!interruptionOwners.size)
      for (const [signal, handler] of Object.entries(interruptionHandlers)) process.removeListener(signal, handler);
  };
}

export function commandFor(commands, source, pass) {
  const command = commands?.[source]?.[pass];
  if (!Array.isArray(command) || !command.length || command.some(arg => typeof arg !== 'string' || !arg.length))
    throw new Error(`Missing collector argument array: ${source}/${pass}`);
  return command;
}

/** The collector owns workload instrumentation. This harness owns a fresh
 * process, balanced ordering, deadlines and explicit bounded raw capture. No shell or
 * automatic retry is used. Unsupported metrics must be null, never invented.
 */
export async function observe(command, request, timeoutMs, saveRaw) {
  if (process.platform === 'win32') throw new Error('Owned process-group collection requires a Unix Nix environment');
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new Error('Collector timeout must be positive');
  const started = performance.now();
  let interruption, abortOwnedGroup;
  // Install before spawning: a signal must never orphan a newly detached group.
  const releaseInterruption = ownInterruption(signal => {
    interruption ??= new HarnessInterrupted(signal);
    abortOwnedGroup?.(interruption.message);
  });
  try {
    const raw = await new Promise(resolve => {
      const child = spawn(command[0], command.slice(1), {detached: true, stdio: ['pipe', 'pipe', 'pipe']});
      const output = {stdout: [], stderr: []}, sizes = {stdout: 0, stderr: 0};
      const maxBytes = 16 * 1024 * 1024;
      let failure, truncated = false, remainingProcessGroup = false, groupKillSent = false;
      let resolved = false, drainTimer;
      const cleanupErrors = [];
      function killGroup() {
        if (!child.pid) return;
        try { process.kill(-child.pid, 'SIGKILL'); groupKillSent = true; }
        catch (error) { if (error.code !== 'ESRCH') cleanupErrors.push(error.message); }
      }
      function abort(reason) {
        if (resolved) return;
        failure ??= reason;
        killGroup();
        // A descendant that deliberately creates another session is outside the
        // supported collector contract. Bound pipe draining and report the failure.
        drainTimer ??= setTimeout(() => {
          child.stdout.destroy(); child.stderr.destroy(); child.stdin.destroy();
          finish(null, 'SIGKILL');
        }, 2000);
      }
      abortOwnedGroup = abort;
      function finish(status, signal) {
        if (resolved) return;
        resolved = true;
        clearTimeout(deadline); clearTimeout(drainTimer);
        resolve({request, durationSeconds: (performance.now() - started) / 1000,
          pid: child.pid ?? null, status, signal, error: failure ?? null,
          remainingProcessGroup, groupKillSent, cleanupErrors, truncated,
          observedBytes: sizes, stdout: Buffer.concat(output.stdout).toString('utf8'),
          stderr: Buffer.concat(output.stderr).toString('utf8')});
      }
      const deadline = setTimeout(() => abort('Collector process-group deadline exceeded'), timeoutMs);
      for (const stream of ['stdout', 'stderr']) child[stream].on('data', chunk => {
        const remaining = Math.max(0, maxBytes - sizes[stream]);
        if (remaining) output[stream].push(chunk.subarray(0, remaining));
        sizes[stream] += chunk.length;
        if (sizes[stream] > maxBytes) { truncated = true; abort(`Collector ${stream} exceeded ${maxBytes} bytes`); }
      });
      child.on('error', error => { failure ??= error.message; });
      child.stdin.on('error', error => { if (error.code !== 'EPIPE') abort(error.message); });
      child.on('exit', () => {
        // Check on exit, before close: orphaned children can keep pipes open.
        if (child.pid) {
          try { process.kill(-child.pid, 0); remainingProcessGroup = true; }
          catch (error) { if (error.code !== 'ESRCH') cleanupErrors.push(error.message); }
        }
        if (remainingProcessGroup) abort('Collector left child processes');
      });
      child.on('close', finish);
      child.stdin.end(JSON.stringify(request) + '\n');
    });
    raw.harnessSignal = interruption?.signal ?? null;
    saveRaw(raw);
    if (interruption) throw interruption;
    if (raw.error || raw.status !== 0 || raw.cleanupErrors.length || raw.truncated)
      throw new Error(`Collector failed: ${request.runId}; retain its raw process log.`);
    const rows = raw.stdout.split(/\r?\n/).filter(line => line.startsWith('{')).map(line => JSON.parse(line));
    if (rows.length !== 1 || rows[0].kind !== 'sample')
      throw new Error(`Expected exactly one sample: ${request.runId}; retain its raw process log.`);
    const sample = rows[0];
    if (sample.artifactSha256 !== request.artifactSha256 ||
        sample.completedWorkContractSha256 !== request.completedWorkContractSha256)
      throw new Error(`Collector identity mismatch: ${request.runId}`);
    // The collector cannot substitute a different cell, order, block or process.
    return {...sample, kind: 'sample', cell: request.cell.id, pass: request.pass,
      variant: request.variant, block: request.block, orderInBlock: request.orderInBlock,
      processId: request.runId, durationSeconds: raw.durationSeconds};
  } finally {
    abortOwnedGroup = undefined;
    releaseInterruption();
  }
}

export async function collect(manifest, commands, context, destination, mode, {observer = observe, progress = console.log} = {}) {
  const problems = manifestIssues(manifest);
  if (problems.length) throw new Error(`Cannot collect against an unfrozen contract:\n${problems.join('\n')}`);
  if (!['comparison', 'calibration'].includes(mode)) throw new Error('Expected comparison or calibration mode');
  for (const source of [...Object.keys(manifest.baselines), 'candidate'])
    for (const pass of manifest.protocol.passes) commandFor(commands, source, pass);
  const started = performance.now();
  const metadata = {kind: 'metadata', mode, manifestSha256: identity(manifest),
    provenance: context.provenance, shared: context.shared,
    startedAt: new Date().toISOString(), durationSeconds: 0};
  const evidence = {metadata, samples: [], completion: {kind: 'completion', observations: 0}};
  const provenanceProblems = provenanceIssues(manifest, evidence, mode);
  if (provenanceProblems.length) throw new Error(provenanceProblems.join('\n'));
  if (context.shared.runnerSha256 !== digest(readFileSync(fileURLToPath(import.meta.url))) ||
      context.shared.analyzerSha256 !== digest(readFileSync(new URL('./analyze.mjs', import.meta.url))))
    throw new Error('Runner/analyzer hashes must identify the code actually executing');
  const output = openSync(destination, 'wx');
  let raw;
  try { raw = openSync(`${destination}.processes.jsonl`, 'wx'); }
  catch (error) { closeSync(output); throw error; }
  let observations = 0;
  let interruption;
  // Covers the whole collection, including gaps between collector processes.
  const releaseInterruption = ownInterruption(signal => { interruption ??= new HarnessInterrupted(signal); });
  try {
    writeFileSync(output, JSON.stringify(metadata) + '\n');
    for (const cell of manifest.cells) for (const pass of manifest.protocol.passes)
      for (let block = 0; block < manifest.protocol.pairsPerMode; block++) {
        const order = block % 2 ? ['candidate', 'baseline'] : ['baseline', 'candidate'];
        for (const [orderInBlock, variant] of order.entries()) {
          if (interruption) throw interruption;
          const source = mode === 'calibration' || variant === 'baseline' ? cell.baseline : 'candidate';
          const sourceIdentity = source === 'candidate' ? context.provenance.candidate : context.provenance.baselines[source];
          const remainingMs = manifest.protocol.maximumCollectionSeconds * 1000 - (performance.now() - started);
          if (remainingMs <= 0) throw new Error('Frozen collection deadline exceeded');
          const request = {schema: 'spoonbill-browser-auth-collection-v1', runId: randomUUID(),
            cell: {...cell, dimensions: dimensions(manifest, cell), metrics: requiredMetrics(manifest, cell)},
            pass, block, variant, orderInBlock, source,
            artifactSha256: sourceIdentity.artifactSha256,
            completedWorkContractSha256: cell.completedWorkContractSha256,
            work: completedWork(manifest, cell, pass), protocol: manifest.protocol};
          const sample = await observer(commandFor(commands, source, pass), request,
            Math.min(remainingMs, manifest.protocol.maximumProcessSeconds * 1000),
            row => appendFileSync(raw, JSON.stringify(row) + '\n'));
          if (interruption) throw interruption;
          appendFileSync(output, JSON.stringify(sample) + '\n');
          observations++;
        }
        progress(`${mode}: ${cell.id}/${pass} pair ${block + 1}/${manifest.protocol.pairsPerMode}`);
      }
    if (interruption) throw interruption;
    metadata.durationSeconds = (performance.now() - started) / 1000;
    if (metadata.durationSeconds > manifest.protocol.maximumCollectionSeconds) throw new Error('Frozen collection deadline exceeded');
    // Preserve every sample already captured and finalize only after all work.
    // This final rewrite updates the duration in our exclusively owned file.
    const lines = readFileSync(destination, 'utf8').split('\n');
    lines[0] = JSON.stringify(metadata);
    writeFileSync(destination, lines.join('\n') + JSON.stringify({kind: 'completion', observations}) + '\n');
    return {observations, durationSeconds: metadata.durationSeconds};
  } catch (error) {
    const failure = interruption ?? error;
    appendFileSync(output, JSON.stringify({kind: 'failure', observations, error: failure.message,
      ...(failure instanceof HarnessInterrupted ? {signal: failure.signal, exitCode: failure.exitCode} : {})}) + '\n');
    throw failure;
  } finally { releaseInterruption(); closeSync(raw); closeSync(output); }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [manifest, commands, context, destination, mode] = process.argv.slice(2);
  if (!mode) throw new Error('Usage: node run.mjs manifest.json collectors.json context.json NEW_OUTPUT.jsonl comparison|calibration');
  const read = file => JSON.parse(readFileSync(file, 'utf8'));
  try { await collect(read(manifest), read(commands), read(context), destination, mode); }
  catch (error) {
    console.error(error.message);
    process.exitCode = error instanceof HarnessInterrupted ? error.exitCode : process.exitCode || 1;
  }
}
