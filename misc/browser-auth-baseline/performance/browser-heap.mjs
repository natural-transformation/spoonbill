import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';

// Measurement-only WebKit transport access. Never included in application code.
// The private bridge must be re-reviewed and probed when this exact pin changes.
export const playwrightPin = Object.freeze({version: '1.56.1', files: Object.freeze({
  'lib/inProcessFactory.js': '8b57f4ea5b2ad1de9f335871df7d0b797b29893974069ff1442f121df694f05b',
  'lib/server/webkit/wkPage.js': '2c6d28527231682a3c59b6f36205eb953b2f3089f8c5d1395139484e32d76cb3',
  'lib/server/page.js': 'f954b7dff26e27dac1b0ddbe55e29e8e051b7635661af15f3a03dfef7e338f8d',
  'lib/client/channelOwner.js': '7052cae26b3fc156572494b9cf8a6cdddc8d3845aea7dec9ef7642f44f866c7e',
  'browsers.json': '21f3da7cdcb4a99fd6cd130200076b09f85b8deac6818f3d16114bd351c90d12',
})});
export function verifyPlaywrightPin(root) {
  if (!root?.startsWith('/nix/store/')) throw new Error('Use the pinned Nix Playwright driver');
  const packageInfo = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
  if (packageInfo.version !== playwrightPin.version) throw new Error('Unsupported Playwright version; review and validate a new profiling pin');
  for (const [path, expected] of Object.entries(playwrightPin.files)) {
    const actual = createHash('sha256').update(readFileSync(join(root, path))).digest('hex');
    if (actual !== expected) throw new Error(`Profiling source pin changed: ${path}`);
  }
  return playwrightPin;
}

function sumNodeSizes(nodes, stride, offset) {
  if (!Array.isArray(nodes) || !Number.isSafeInteger(stride) || stride < 1 || nodes.length % stride !== 0)
    throw new Error('Unsupported heap node layout');
  let total = 0;
  for (let index = offset; index < nodes.length; index += stride) {
    if (!Number.isSafeInteger(nodes[index]) || nodes[index] < 0) throw new Error('Invalid heap node size');
    total += nodes[index];
    if (!Number.isSafeInteger(total)) throw new Error('Heap byte counter overflow');
  }
  return total;
}

export function summarizeWebKitSnapshot(snapshot) {
  if (snapshot?.version !== 3 || snapshot.type !== 'Inspector') throw new Error('Unsupported WebKit snapshot schema');
  return Object.freeze({engine: 'webkit', bytes: sumNodeSizes(snapshot.nodes, 4, 1), nodeCount: snapshot.nodes.length / 4,
    semantics: 'Post-full-GC sum of WebKit Inspector node size estimates; not native memory or cumulative allocation'});
}

export function summarizeChromiumSnapshot(snapshot) {
  const fields = snapshot?.snapshot?.meta?.node_fields;
  if (!Array.isArray(fields) || fields.filter(name => name === 'self_size').length !== 1)
    throw new Error('Unsupported Chromium heap snapshot schema');
  const bytes = sumNodeSizes(snapshot.nodes, fields.length, fields.indexOf('self_size'));
  if (snapshot.snapshot.node_count !== snapshot.nodes.length / fields.length) throw new Error('Heap snapshot node count mismatch');
  return Object.freeze({engine: 'chromium', bytes, nodeCount: snapshot.snapshot.node_count,
    semantics: 'Post-full-GC sum of V8 snapshot node self sizes; not native memory or cumulative allocation'});
}

/** Invasive memory-pass observation. The caller owns a synthetic browser/page.
 * Snapshot text is held only long enough to summarize it and is never logged or
 * persisted. It can contain page strings, so this must not inspect real accounts.
 */
export async function retainedJavaScriptHeap(page, engine, {driverRoot = process.env.PLAYWRIGHT_DRIVER_PATH,
  maximumSnapshotBytes = 64 * 1024 * 1024} = {}) {
  if (!Number.isSafeInteger(maximumSnapshotBytes) || maximumSnapshotBytes < 1024)
    throw new Error('Invalid heap snapshot capture limit');
  verifyPlaywrightPin(driverRoot);
  if (engine === 'webkit') {
    const implementation = page?._connection?.toImpl?.(page);
    const session = implementation?.delegate?._session;
    if (!session?.send) throw new Error('Pinned local WebKit session bridge unavailable');
    // Own the Heap domain for this observation and clear its snapshot history on
    // release. Leaving profiler snapshots resident would contaminate later probes.
    await session.send('Heap.enable');
    try {
      await session.send('Heap.gc');
      const result = await session.send('Heap.snapshot');
      if (typeof result.snapshotData !== 'string' || Buffer.byteLength(result.snapshotData) > maximumSnapshotBytes)
        throw new Error('Heap snapshot unsupported or exceeds the capture limit');
      return summarizeWebKitSnapshot(JSON.parse(result.snapshotData));
    } finally { await session.send('Heap.disable'); }
  }
  if (engine !== 'chromium') throw new Error('Unsupported browser engine');
  const session = await page.context().newCDPSession(page);
  const chunks = [];
  let size = 0, overflow = false;
  const receive = ({chunk}) => {
    size += Buffer.byteLength(chunk);
    if (size > maximumSnapshotBytes) overflow = true;
    if (!overflow) chunks.push(chunk);
  };
  session.on('HeapProfiler.addHeapSnapshotChunk', receive);
  try {
    await session.send('HeapProfiler.enable');
    await session.send('HeapProfiler.collectGarbage');
    await session.send('HeapProfiler.takeHeapSnapshot', {reportProgress: false});
    if (overflow) throw new Error('Heap snapshot exceeds the capture limit');
    return summarizeChromiumSnapshot(JSON.parse(chunks.join('')));
  } finally {
    chunks.length = 0;
    session.removeListener('HeapProfiler.addHeapSnapshotChunk', receive);
    await session.detach();
  }
}

export const allocationCapability = Object.freeze({supported: false, value: null,
  metric: 'browserAllocatedBytesPerOperation',
  reason: 'Neither retained-heap snapshots nor malloc interception measures cumulative logical JavaScript allocation; no validated complete collector is installed.'});
