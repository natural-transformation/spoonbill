import {verifyPlaywrightPin} from './browser-heap.mjs';
import {createHash} from 'node:crypto';
import {readFileSync} from 'node:fs';
import {join} from 'node:path';

export const webkitNetworkPin = Object.freeze({
  'lib/server/webkit/wkConnection.js': '4a8fea1d92004216a99fbd8321cb245348a88d5e231f6ac9e6d80b90164a36c1',
  'lib/server/webkit/wkProvisionalPage.js': '3c1f7b53ac2391c291e0b2783f19f62f1b9a25d1389a6b587c70884e00df84d6',
});
export function verifyWebKitNetworkPin(root) {
  verifyPlaywrightPin(root);
  for (const [file, expected] of Object.entries(webkitNetworkPin))
    if (createHash('sha256').update(readFileSync(join(root, file))).digest('hex') !== expected)
      throw new Error('Unsupported WebKit network lifecycle source pin');
}

/** Measurement-only listener on a borrowed pinned WebKit session. Never detach
 * or disable the shared session/domain; only remove this observer's listeners. */
export async function observeWebKitSessionMetadata(session, work) {
  if (!session?.on || !session?.removeListener || !session?.send)
    throw new Error('WebKit metadata session unavailable');
  const ids = new Set(), retiredIds = new Set();
  const sent = {all: 0, text: 0, binary: 0, continuation: 0, control: 0, other: 0};
  let created = 0, closed = 0, closeEvents = 0, duplicateCloseEvents = 0, peakIds = 0, failure = null;
  const opened = event => {
    if (failure) return;
    const id = event.requestId;
    if (typeof id !== 'string' || !id.length || id.length > 256 || ids.has(id) || retiredIds.has(id) || ids.size >= 4 || created >= 128) {
      failure ??= 'webkit-created-bound-or-identity'; return;
    }
    ++created;
    ids.add(id); peakIds = Math.max(peakIds, ids.size);
  };
  const frame = event => {
    if (failure) return;
    if (!ids.has(event.requestId) || sent.all >= 128) { failure = 'webkit-sent-bound-or-identity'; return; }
    ++sent.all;
    const opcode = event.response?.opcode;
    const key = opcode === 1 ? 'text' : opcode === 2 ? 'binary' : opcode === 0 ? 'continuation' :
      [8, 9, 10].includes(opcode) ? 'control' : 'other';
    ++sent[key];
    if (key === 'other') failure = 'webkit-opcode-unavailable';
  };
  const ended = event => {
    if (failure) return;
    if (closeEvents >= 128) { failure = 'webkit-close-bound-or-identity'; return; }
    ++closeEvents;
    if (retiredIds.has(event.requestId)) { ++duplicateCloseEvents; return; }
    if (!ids.delete(event.requestId)) { failure = 'webkit-close-bound-or-identity'; return; }
    retiredIds.add(event.requestId); ++closed;
  };
  const listeners = [['Network.webSocketCreated', opened], ['Network.webSocketFrameSent', frame],
    ['Network.webSocketClosed', ended]];
  let snapshot;
  try {
    for (const [name, listener] of listeners) session.on(name, listener);
    await work();
    // Flush earlier session event delivery using an effect-free literal. The
    // synthetic workload already waited for physical server ACKs and close.
    await session.send('Runtime.evaluate', {expression: '0', returnByValue: true});
    snapshot = {status: failure || ids.size ? 'inconclusive' : 'observed', failure,
      sent: {...sent}, created, closed, closeEvents, duplicateCloseEvents,
      activeIdsAtStop: ids.size, retiredIdsAtStop: retiredIds.size, peakIds};
  } finally {
    for (const [name, listener] of listeners) session.removeListener(name, listener);
    ids.clear(); retiredIds.clear();
  }
  return {...snapshot, retainedIdsAfterStop: ids.size + retiredIds.size, borrowedSessionDisposed: false};
}

export async function observePinnedWebKitMetadata(page, work, {driverRoot = process.env.PLAYWRIGHT_DRIVER_PATH} = {}) {
  verifyWebKitNetworkPin(driverRoot);
  const session = page?._connection?.toImpl?.(page)?.delegate?._session;
  if (!session) throw new Error('Pinned local WebKit session bridge unavailable');
  return observeWebKitSessionMetadata(session, work);
}
