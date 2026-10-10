import {accessSync, constants, realpathSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {delimiter, join} from 'node:path';
import {pathToFileURL} from 'node:url';
import {allocationCapability, verifyPlaywrightPin} from './browser-heap.mjs';

export function findNixTool(name, searchPath = process.env.PATH ?? '') {
  if (!/^[a-z][a-z0-9_-]*$/.test(name)) throw new Error('Invalid tool name');
  for (const directory of searchPath.split(delimiter).filter(path => path.startsWith('/nix/store/'))) {
    try {
      const path = realpathSync(join(directory, name));
      if (!path.startsWith('/nix/store/')) continue;
      accessSync(path, constants.X_OK);
      return path;
    } catch (_) { /* Only Nix-managed executable paths qualify. */ }
  }
  return null;
}
function tool(name, args) {
  const path = findNixTool(name);
  if (!path) return {available: false, path: null, reason: 'Not supplied by the active Nix shell'};
  const result = spawnSync(path, args, {encoding: 'utf8', timeout: 5000, maxBuffer: 32768});
  return {available: result.status === 0 && !result.error, path,
    banner: (result.stdout || result.stderr || '').split(/\r?\n/).find(line => line.trim())?.slice(0, 180) ?? null,
    ...(result.status === 0 && !result.error ? {} : {reason: 'Managed tool did not complete its bounded capability command'})};
}
export function profilingCapabilities() {
  let browserPin;
  try { browserPin = {validatedSource: true, ...verifyPlaywrightPin(process.env.PLAYWRIGHT_DRIVER_PATH)}; }
  catch (_) { browserPin = {validatedSource: false, reason: 'Matching pinned browser driver is unavailable in this shell'}; }
  const linux = process.platform === 'linux';
  return {schema: 'spoonbill-profiling-capabilities-v1', platform: process.platform, architecture: process.arch,
    profilingShell: process.env.SPOONBILL_PROFILING_ENVIRONMENT === '1',
    declaredPlatform: process.env.SPOONBILL_PROFILING_PLATFORM ?? null,
    tools: {jcmd: tool('jcmd', ['-h']), jfr: tool('jfr', ['--version']),
      heaptrack: linux ? tool('heaptrack', ['--version']) : {available: false, reason: 'heaptrack requires Linux'},
      heaptrackPrint: linux ? tool('heaptrack_print', ['--help']) : {available: false, reason: 'heaptrack requires Linux'},
      gnuTime: tool('time', ['--version']),
      processTools: linux ? tool('ps', ['--version']) : {available: false, reason: 'Linux procps protocol unavailable on this host'}},
    browserPin,
    capabilities: {
      browserRetainedHeap: {status: browserPin.validatedSource ? 'requires-native-probe' : 'unsupported',
        probe: 'browser-heap.native-tests.mjs', scope: 'Per-page JavaScript heap node sizes after full collection'},
      browserAllocatedBytesPerOperation: allocationCapability,
      ownedProcessRss: {status: linux ? 'requires-native-probe' : 'unsupported',
        scope: 'One owned process only; neither sum of per-process high waters nor controller RSS is a simultaneous browser-process-tree peak'},
      browserAggregatePeakRss: {status: 'unsupported', value: null,
        reason: 'No validated whole-browser simultaneous RSS collector; cgroup memory.peak is a different metric that includes charged cache/kernel memory'},
    },
    acceptance: 'inconclusive',
    reason: 'Tool availability and synthetic sensor validation do not supply the complete required workload measurements'};
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  console.log(JSON.stringify(profilingCapabilities(), null, 2));
  process.exitCode = 2;
}
