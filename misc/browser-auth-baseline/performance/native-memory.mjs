import {ChildProcess} from 'node:child_process';
import {readFile} from 'node:fs/promises';

const identities = new WeakMap();
function kilobytes(text, field) {
  const match = text.match(new RegExp(`^${field}:\\s+(\\d+)\\s+kB$`, 'm'));
  if (!match) throw new Error(`Missing kernel memory counter: ${field}`);
  const value = Number(match[1]) * 1024;
  if (!Number.isSafeInteger(value)) throw new Error('Memory counter overflow');
  return value;
}
export function parseLinuxMemory(status, rollup) {
  return Object.freeze({peakRssBytes: kilobytes(status, 'VmHWM'), rssBytes: kilobytes(rollup, 'Rss'),
    proportionalRssBytes: kilobytes(rollup, 'Pss'), privateDirtyBytes: kilobytes(rollup, 'Private_Dirty'),
    resolutionBytes: 1024,
    scope: 'One process: kernel RSS high-water accounting and a current smaps_rollup snapshot; excludes child processes'});
}
export function parseLinuxProcessIdentity(stat) {
  const end = stat.lastIndexOf(')');
  if (end < 1) throw new Error('Unsupported process identity');
  const fields = stat.slice(end + 1).trim().split(/\s+/);
  const ppid = Number(fields[1]), startTimeTicks = Number(fields[19]);
  if (!Number.isSafeInteger(ppid) || ppid < 1 || !Number.isSafeInteger(startTimeTicks) || startTimeTicks < 0)
    throw new Error('Invalid process identity');
  return Object.freeze({ppid, startTimeTicks, state: fields[0]});
}

/** Reads only a live direct child owned by this Node collector. Does not inspect
 * arbitrary desktop/user processes, aggregate peaks or silently sample past exit.
 */
export async function ownedProcessMemory(child) {
  if (!(child instanceof ChildProcess) || !Number.isInteger(child.pid)) throw new Error('An owned ChildProcess is required');
  if (process.platform !== 'linux') return Object.freeze({status: 'unsupported', value: null,
    reason: 'Linux procfs collector cannot run on this host; no native browser-process figure is substituted'});
  if (child.exitCode !== null || child.signalCode !== null) return Object.freeze({status: 'inconclusive', value: null, reason: 'Owned process exited before collection'});
  try {
    const prefix = `/proc/${child.pid}`;
    const before = parseLinuxProcessIdentity(await readFile(`${prefix}/stat`, 'utf8'));
    if (before.ppid !== process.pid || before.state === 'Z' || (identities.has(child) && identities.get(child) !== before.startTimeTicks))
      throw new Error('Owned process identity changed');
    identities.set(child, before.startTimeTicks);
    const [status, rollup] = await Promise.all([readFile(`${prefix}/status`, 'utf8'), readFile(`${prefix}/smaps_rollup`, 'utf8')]);
    const after = parseLinuxProcessIdentity(await readFile(`${prefix}/stat`, 'utf8'));
    if (after.ppid !== process.pid || after.startTimeTicks !== before.startTimeTicks || after.state === 'Z')
      throw new Error('Owned process identity changed');
    return Object.freeze({status: 'measured', pid: child.pid, ...parseLinuxMemory(status, rollup)});
  } catch (_) { return Object.freeze({status: 'inconclusive', value: null, reason: 'Owned process counters unavailable or identity changed'}); }
}

/** heaptrack_print -H records intercepted allocator size/count pairs. This is
 * native allocator traffic, never logical JS object allocation inside pools.
 */
export function parseHeaptrackHistogram(text) {
  let allocations = 0, requestedBytes = 0;
  const lines = text.trim().split(/\r?\n/);
  if (!text.trim()) throw new Error('Empty heaptrack histogram is unsupported');
  for (const line of lines) {
    const match = line.match(/^(\d+)\s+(\d+)$/);
    if (!match) throw new Error('Unsupported heaptrack histogram format');
    const size = Number(match[1]), count = Number(match[2]);
    allocations += count; requestedBytes += size * count;
    if (![size, count, allocations, requestedBytes].every(Number.isSafeInteger)) throw new Error('Allocator counter overflow');
  }
  return Object.freeze({allocations, nativeAllocatorRequestedBytes: requestedBytes,
    logicalJavaScriptAllocatedBytes: null,
    scope: 'Intercepted malloc-family calls only; custom allocator suballocations, stacks and uninstrumented children are excluded'});
}
