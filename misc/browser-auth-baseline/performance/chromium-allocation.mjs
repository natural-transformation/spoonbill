import {verifyPlaywrightPin} from './browser-heap.mjs';

export const chromiumAllocationCapability = Object.freeze({
  experimental: true,
  supportedForAcceptance: false,
  browserAllocatedBytesPerOperation: null,
  chromiumVersion: '141.0.7390.37',
  v8Revision: 'ad8af0fc661d278e87627fcaa3a7cf795ee80dd8',
  exactSourceAuditComplete: false,
  scope: 'Allocation trace counters reported by one inspected V8 isolate; backing stores, other isolates and complete coverage unvalidated',
});

const traceFields = ['id', 'function_info_index', 'count', 'size', 'children'];
function positiveInteger(value, label) {
  if (!Number.isSafeInteger(value) || value < 1) throw new Error(`Invalid ${label}`);
}
function unsignedCounter(value, label) {
  if (!Number.isSafeInteger(value) || value < 0 || value > 0xffffffff) throw new Error(`Invalid ${label}`);
  return value;
}

/** Sum each trace node's OWN cumulative counters, never retained node self_size.
 * No names, source locations, strings, edges or individual trace records escape.
 * A valid uint32 value cannot prove that the native counter never wrapped;
 * exact-version coverage/overflow audit remains an acceptance prerequisite.
 */
export function summarizeChromiumAllocationTrace(snapshot, {maximumTraceNodes = 100000, maximumTraceDepth = 128} = {}) {
  positiveInteger(maximumTraceNodes, 'trace node limit');
  positiveInteger(maximumTraceDepth, 'trace depth limit');
  const fields = snapshot?.snapshot?.meta?.trace_node_fields;
  if (!Array.isArray(fields) || fields.length !== traceFields.length || new Set(fields).size !== fields.length ||
      !traceFields.every(field => fields.includes(field))) throw new Error('Unsupported V8 allocation trace schema');
  const functionCount = snapshot.snapshot.trace_function_count;
  positiveInteger(functionCount, 'allocation trace function count');
  const tree = snapshot.trace_tree;
  if (!Array.isArray(tree) || tree.length !== fields.length) throw new Error('Missing or malformed allocation trace tree');
  const at = Object.fromEntries(fields.map((name, index) => [name, index]));
  const pending = [{values: tree, depth: 1}];
  const ids = new Set();
  let allocationCount = 0, allocatedBytes = 0, traceNodeCount = 0;
  while (pending.length) {
    const {values, depth} = pending.pop();
    if (!Array.isArray(values) || values.length % fields.length !== 0 || depth > maximumTraceDepth)
      throw new Error('Malformed or over-depth allocation trace');
    for (let offset = 0; offset < values.length; offset += fields.length) {
      if (++traceNodeCount > maximumTraceNodes) throw new Error('Allocation trace exceeds node limit');
      const id = unsignedCounter(values[offset + at.id], 'trace ID');
      if (ids.has(id)) throw new Error('Duplicate allocation trace ID');
      ids.add(id);
      const functionIndex = unsignedCounter(values[offset + at.function_info_index], 'trace function index');
      if (functionIndex >= functionCount) throw new Error('Allocation trace function index out of range');
      allocationCount += unsignedCounter(values[offset + at.count], 'allocation count');
      allocatedBytes += unsignedCounter(values[offset + at.size], 'allocation size');
      if (!Number.isSafeInteger(allocationCount) || !Number.isSafeInteger(allocatedBytes))
        throw new Error('Allocation trace aggregate overflow');
      const children = values[offset + at.children];
      if (!Array.isArray(children)) throw new Error('Malformed allocation trace children');
      if (children.length) pending.push({values: children, depth: depth + 1});
    }
  }
  return Object.freeze({engine: 'chromium', experimental: true, allocatedBytes, allocationCount, traceNodeCount,
    scope: chromiumAllocationCapability.scope, supportedForAcceptance: false, browserAllocatedBytesPerOperation: null});
}

function bounded(operation, milliseconds, message) {
  let timer;
  return Promise.race([Promise.resolve().then(operation), new Promise((_, reject) => {
    timer = setTimeout(() => reject(new Error(message)), milliseconds);
  })]).finally(() => clearTimeout(timer));
}

/** Measurement-only synthetic workload. Ownership of session transfers here.
 * Time/size failures stop profiling and detach the session. The callback receives
 * an AbortSignal; cancellation does not prove arbitrary page work has stopped,
 * so the caller must dispose its owned page/browser after a failed measurement.
 */
export async function collectChromiumAllocationTrace(session, workload, {
  maximumSnapshotBytes = 64 * 1024 * 1024,
  maximumDurationMs = 30000,
  cleanupTimeoutMs = 5000,
  maximumTraceNodes = 100000,
  maximumTraceDepth = 128,
} = {}) {
  const chunks = [];
  const controller = new AbortController();
  const cleanupBudget = Number.isSafeInteger(cleanupTimeoutMs) && cleanupTimeoutMs > 0 ? cleanupTimeoutMs : 5000;
  const requireActive = () => {
    if (controller.signal.aborted) throw new Error('Allocation measurement is no longer active');
  };
  let receivedBytes = 0, captureError, failed = false;
  const receive = ({chunk}) => {
    if (captureError) return;
    if (typeof chunk !== 'string') captureError = new Error('Malformed allocation snapshot chunk');
    else {
      receivedBytes += Buffer.byteLength(chunk);
      if (!Number.isSafeInteger(receivedBytes) || receivedBytes > maximumSnapshotBytes)
        captureError = new Error('Allocation snapshot exceeds capture limit');
      else chunks.push(chunk);
    }
    if (captureError) chunks.length = 0;
  };
  session.on('HeapProfiler.addHeapSnapshotChunk', receive);
  try {
    for (const [name, value] of Object.entries({maximumSnapshotBytes, maximumDurationMs, cleanupTimeoutMs, maximumTraceNodes, maximumTraceDepth}))
      positiveInteger(value, name);
    if (typeof workload !== 'function') throw new Error('Allocation measurement requires an acknowledged workload');
    return await bounded(async () => {
      await session.send('HeapProfiler.enable');
      requireActive();
      await session.send('HeapProfiler.collectGarbage');
      requireActive();
      await session.send('HeapProfiler.startTrackingHeapObjects', {trackAllocations: true});
      requireActive();
      await workload(Object.freeze({signal: controller.signal,
        collectGarbage: () => { requireActive(); return session.send('HeapProfiler.collectGarbage'); }}));
      requireActive();
      await session.send('HeapProfiler.stopTrackingHeapObjects', {reportProgress: false});
      requireActive();
      if (captureError) throw captureError;
      let snapshot;
      try { snapshot = JSON.parse(chunks.join('')); }
      catch { throw new Error('Malformed allocation snapshot JSON'); }
      chunks.length = 0;
      return summarizeChromiumAllocationTrace(snapshot, {maximumTraceNodes, maximumTraceDepth});
    }, maximumDurationMs, 'Chromium allocation measurement timed out');
  } catch (error) { failed = true; throw error; }
  finally {
    controller.abort();
    chunks.length = 0;
    session.removeListener('HeapProfiler.addHeapSnapshotChunk', receive);
    let cleanupFailed = false;
    try { await bounded(() => session.send('HeapProfiler.disable'), cleanupBudget, 'Allocation profiler cleanup timed out'); }
    catch { cleanupFailed = true; }
    try { await bounded(() => session.detach(), cleanupBudget, 'Allocation session disposal timed out'); }
    catch { cleanupFailed = true; }
    if (cleanupFailed && !failed) throw new Error('Could not release Chromium allocation profiler');
  }
}

/** Public Playwright CDP only; no private WebKit bridge and no application JS. */
export async function measureChromiumAllocations(page, workload, options = {}) {
  verifyPlaywrightPin(options.driverRoot ?? process.env.PLAYWRIGHT_DRIVER_PATH);
  if (page.context().browser()?.version() !== chromiumAllocationCapability.chromiumVersion)
    throw new Error('Unsupported Chromium allocation profiler version');
  const session = await page.context().newCDPSession(page);
  return collectChromiumAllocationTrace(session, workload, options);
}
