import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {createRequire} from 'node:module';
import test from 'node:test';
const {BrowserExchangeObserver, createExchangeOutput, inspectExchangeOutput} =
  createRequire(import.meta.url)('./browser-exchanges.cjs');

const hash = 'a'.repeat(64);
const metadata = {provider: 'memory', engine: 'chromium', flow: 'password-only', artifactSha256: hash};
function request(type = 'document') {
  return {resourceType: () => type,
    get url() { throw new Error('URL must not be inspected'); },
    get headers() { throw new Error('Headers must not be inspected'); },
    get postData() { throw new Error('Credentials must not be inspected'); }};
}
function completeHttp(page, value = request()) {
  page.emit('request', value); page.emit('response', {request: () => value}); page.emit('requestfinished', value);
}
function completeSocket(page) {
  const socket = new EventEmitter(); page.emit('websocket', socket);
  const opaque = {get payload() { throw new Error('Payload must not be inspected'); }};
  socket.emit('framesent', opaque); socket.emit('framereceived', opaque); socket.emit('close');
  return socket;
}
function record(engine = 'chromium', flow = 'password-only') {
  const page = new EventEmitter();
  const observer = new BrowserExchangeObserver(page, {...metadata, engine, flow});
  completeHttp(page); completeSocket(page);
  return observer.finish({completed: true});
}
class Cdp extends EventEmitter {
  commands = [];
  detachCalls = 0;
  async send(command) { this.commands.push(command); }
  async detach() { ++this.detachCalls; }
}
function temporary(body) {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'spoonbill-exchange-test-'));
  try { return body(path.join(directory, 'new.jsonl')); }
  finally { fs.rmSync(directory, {recursive: true, force: true}); }
}
function records(output) {
  for (const engine of ['chromium', 'webkit'])
    for (const flow of ['password-only', 'challenged']) output.record(record(engine, flow));
}

test('counts actual event types without reading URLs, credentials or frame payloads', () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata);
  const first = request(); page.emit('request', first);
  observer.phase('begin');
  page.emit('response', {request: () => first}); page.emit('requestfinished', first);
  const failed = request('script'); page.emit('request', failed); page.emit('requestfailed', failed);
  const socket = completeSocket(page);
  const result = observer.finish({completed: true});
  assert.equal(result.observerStatus, 'observed');
  assert.equal(result.counters.httpRequests, 2);
  assert.equal(result.counters.httpResponses, 1);
  assert.equal(result.counters.httpFailed, 1);
  assert.equal(result.counters.httpCompletedExchanges, 1);
  assert.equal(result.phases.bootstrap.httpCompletedExchanges, 1, 'request owns its terminal phase');
  assert.equal(result.phases.begin.httpCompletedExchanges, 0);
  assert.equal(result.phases.begin.webSocketFramesSent, 1);
  assert.equal(result.webSocketProtocolExchanges, null);
  assert.equal(result.resources.retainedRequestHandlesAfterStop, 0);
  assert.equal(result.resources.retainedSocketHandlesAfterStop, 0);
  for (const event of ['request', 'response', 'requestfailed', 'requestfinished', 'websocket'])
    assert.equal(page.listenerCount(event), 0);
  for (const event of ['framesent', 'framereceived', 'close', 'socketerror']) assert.equal(socket.listenerCount(event), 0);
  assert.strictEqual(observer.finish({completed: true}), result);
});

test('counts redirects as separate HTTP exchanges and excludes exposed upgrades', () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata);
  completeHttp(page); completeHttp(page);
  const upgrade = request('websocket');
  page.emit('request', upgrade); page.emit('response', {request: () => upgrade}); page.emit('requestfinished', upgrade);
  completeSocket(page);
  const result = observer.finish({completed: true});
  assert.equal(result.counters.httpCompletedExchanges, 2);
  assert.equal(result.counters.httpUpgradeRequestEventsExcluded, 1);
  assert.equal(result.counters.webSocketsOpened, 1);
});

test('cannot certify missing frame capability, unfinished requests or failed workflows', () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata);
  completeHttp(page); page.emit('request', request());
  const result = observer.finish({completed: false});
  assert.equal(result.observerStatus, 'inconclusive');
  assert.ok(result.issues.includes('workflow-incomplete'));
  assert.ok(result.issues.includes('websocket-frame-events-unobserved'));
  assert.equal(result.usableCounts.webSocketFramesSent, null);
  assert.ok(result.issues.includes('pending-http-at-stop'));
  assert.equal(result.resources.pendingRequestsAtStop, 1);
  assert.equal(result.resources.retainedRequestHandlesAfterStop, 0);
});

test('completed work with missing sent events retains null usable counts and inconclusive capability', () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata);
  completeHttp(page);
  const socket = new EventEmitter(); page.emit('websocket', socket);
  socket.emit('framereceived', {}); socket.emit('close');
  const result = observer.finish({completed: true});
  assert.equal(result.counters.webSocketFramesSent, 0, 'diagnostic event count only');
  assert.equal(result.usableCounts.webSocketFramesSent, null);
  assert.equal(result.usableCounts.webSocketFramesReceived, 1);
  assert.equal(result.usableCounts.httpCompletedExchanges, 1);
  assert.equal(result.observerStatus, 'inconclusive');
});

test('public per-page CDP counts empty/Blob sent events without inspecting URL or response payload', async () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata), cdp = new Cdp();
  await observer.attachChromium({newCDPSession: async selected => { assert.strictEqual(selected, page); return cdp; }}, page);
  assert.deepEqual(cdp.commands, ['Network.enable']);
  cdp.emit('Network.webSocketCreated', {requestId: 'opaque-1', get url() { throw new Error('URL read'); }});
  const socket = new EventEmitter(); page.emit('websocket', socket);
  completeHttp(page);
  observer.phase('begin');
  for (let index = 0; index < 3; ++index)
    cdp.emit('Network.webSocketFrameSent', {requestId: 'opaque-1', response: {opcode: 2,
      get payloadData() { throw new Error('Payload read'); }}});
  for (const opcode of [0, 8, 9, 10])
    cdp.emit('Network.webSocketFrameSent', {requestId: 'opaque-1', response: {opcode,
      get payloadData() { throw new Error('Payload read'); }}});
  socket.emit('framereceived', {});
  await observer.stopCdp(); await observer.stopCdp();
  socket.emit('close');
  const result = observer.finish({completed: true});
  assert.equal(result.observerStatus, 'observed');
  assert.equal(result.counters.webSocketFramesSent, 0);
  assert.equal(result.counters.cdpWebSocketFramesSent, 7);
  assert.equal(result.phases.begin.cdpWebSocketFramesSent, 7);
  assert.equal(result.counters.cdpWebSocketTextBinaryFramesSent, 3);
  assert.equal(result.counters.cdpWebSocketControlFramesSent, 3);
  assert.equal(result.counters.cdpWebSocketContinuationFramesSent, 1);
  assert.equal(result.usableCounts.webSocketFramesSent, 3);
  assert.equal(result.sentFrameSource, 'chromium-cdp-text-binary-events');
  assert.equal(result.resources.cdpSocketIdsAtDetach, 1, 'CDP does not claim to observe context teardown');
  assert.equal(result.resources.retainedCdpSocketIdsAfterStop, 0);
  assert.equal(result.resources.retainedCdpSessionHandlesAfterStop, 0);
  assert.equal(result.cdp.detachAcknowledged, true);
  assert.equal(cdp.detachCalls, 1);
  assert.equal(cdp.eventNames().length, 0);
});

test('requires actual detach acknowledgment before accepting a CDP usable count', async () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata), cdp = new Cdp();
  let acknowledge;
  cdp.detach = () => new Promise(resolve => { acknowledge = resolve; });
  await observer.attachChromium({newCDPSession: async () => cdp}, page);
  completeHttp(page); completeSocket(page);
  cdp.emit('Network.webSocketCreated', {requestId: 'one'});
  cdp.emit('Network.webSocketFrameSent', {requestId: 'one', response: {opcode: 2}});
  cdp.emit('Network.webSocketClosed', {requestId: 'one'});
  const stopped = observer.stopCdp();
  await Promise.resolve();
  assert.equal(observer.cdpDetachAcknowledged, false);
  acknowledge(); await stopped;
  assert.equal(observer.finish({completed: true}).cdp.detachAcknowledged, true);
});

for (const mode of ['unmatched', 'overflow', 'detach-failure', 'attachment-failure'])
  test(`CDP ${mode} leaves counts inconclusive and removes identities/listeners`, async () => {
    const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata,
      {limits: {activeSockets: 1}}), cdp = new Cdp();
    if (mode === 'attachment-failure') cdp.send = async () => { throw new Error('secret failure'); };
    if (mode === 'detach-failure') cdp.detach = async () => { throw new Error('secret failure'); };
    await observer.attachChromium({newCDPSession: async () => cdp}, page);
    if (mode === 'unmatched') cdp.emit('Network.webSocketFrameSent', {requestId: 'unseen'});
    if (mode === 'overflow') {
      cdp.emit('Network.webSocketCreated', {requestId: 'first'});
      cdp.emit('Network.webSocketCreated', {requestId: 'second'});
    }
    await observer.stopCdp();
    const result = observer.finish({completed: true});
    assert.equal(result.observerStatus, 'inconclusive');
    assert.equal(result.usableCounts.webSocketFramesSent, null);
    assert.equal(result.resources.retainedCdpSocketIdsAfterStop, 0);
    assert.equal(result.resources.retainedCdpSessionHandlesAfterStop, 0);
    assert.equal(cdp.eventNames().length, 0);
    assert.ok(!JSON.stringify(result).includes('secret failure'));
  });

test('late CDP allocation after observer stop is detached without enabling observation', async () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata), cdp = new Cdp();
  let allocated;
  const attaching = observer.attachChromium({newCDPSession: () => new Promise(resolve => { allocated = resolve; })}, page);
  observer.stop(); allocated(cdp); await attaching;
  assert.equal(cdp.detachCalls, 1);
  assert.deepEqual(cdp.commands, []);
  assert.equal(observer.finish({completed: false}).resources.retainedCdpSessionHandlesAfterStop, 0);
});

test('unknown CDP opcode preserves raw events but cannot certify the usable counter', async () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata), cdp = new Cdp();
  await observer.attachChromium({newCDPSession: async () => cdp}, page);
  completeHttp(page); completeSocket(page);
  cdp.emit('Network.webSocketCreated', {requestId: 'one'});
  cdp.emit('Network.webSocketFrameSent', {requestId: 'one', response: {opcode: 2}});
  cdp.emit('Network.webSocketFrameSent', {requestId: 'one', response: {opcode: 15}});
  await observer.stopCdp();
  const result = observer.finish({completed: true});
  assert.equal(result.counters.cdpWebSocketFramesSent, 2);
  assert.equal(result.counters.cdpWebSocketUnknownFramesSent, 1);
  assert.equal(result.usableCounts.webSocketFramesSent, null);
  assert.equal(result.observerStatus, 'inconclusive');
});

test('unsupported WebKit private pin cannot fall back to a completeness claim', async () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, {...metadata, engine: 'webkit'});
  await observer.attachWebKit(page, {driverRoot: '/host-driver'});
  completeHttp(page); completeSocket(page); await observer.stopRawSessions();
  const result = observer.finish({completed: true});
  assert.equal(result.observerStatus, 'inconclusive');
  assert.ok(result.issues.includes('webkit-attachment-failed'));
  assert.equal(result.usableCounts.webSocketFramesSent, null);
  assert.equal(result.physicalWebSocketDataFramesSent, null);
});

for (const [name, limits, events, issue] of [
  ['requests', {pendingRequests: 1}, page => { page.emit('request', request()); page.emit('request', request()); }, 'pending-request-limit'],
  ['sockets', {activeSockets: 1}, page => { page.emit('websocket', new EventEmitter()); page.emit('websocket', new EventEmitter()); }, 'active-socket-limit'],
  ['counters', {counter: 1}, page => { completeHttp(page); completeHttp(page); }, 'counter-limit'],
]) test(`bounds ${name}, stops observation and drops retained handles on overflow`, () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata, {limits});
  events(page);
  const result = observer.finish({completed: true});
  assert.equal(result.observerStatus, 'inconclusive');
  assert.ok(result.issues.includes(issue));
  assert.equal(result.resources.retainedRequestHandlesAfterStop, 0);
  assert.equal(result.resources.retainedSocketHandlesAfterStop, 0);
  assert.equal(page.eventNames().length, 0);
  assert.ok(Object.values(result.counters).every(value => value <= result.limits.counter));
});

test('rejects invalid metadata and phases without retaining arbitrary strings', () => {
  assert.throws(() => new BrowserExchangeObserver({}, metadata), /unavailable/);
  assert.throws(() => new BrowserExchangeObserver(new EventEmitter(), {...metadata, provider: 'secret'}), /provider/);
  assert.throws(() => new BrowserExchangeObserver(new EventEmitter(), {...metadata, artifactSha256: 'secret'}), /hash/);
  const observer = new BrowserExchangeObserver(new EventEmitter(), metadata);
  assert.throws(() => observer.phase('private-url'), /phase/);
  assert.throws(() => observer.phase('bootstrap'), /repeated/);
  observer.finish({completed: false});
});

test('captures malformed event capability as static inconclusive evidence', () => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata);
  page.emit('response', {request() { throw new Error('sensitive value'); }});
  const result = observer.finish({completed: false});
  assert.ok(result.issues.includes('response-capability'));
  assert.ok(!JSON.stringify(result).includes('sensitive value'));
});

test('exclusive raw output contains scope/provenance and exactly four distinct completed workflows', () => temporary(file => {
  const output = createExchangeOutput(file, metadata);
  assert.throws(() => createExchangeOutput(file, metadata), {code: 'EEXIST'});
  records(output); output.close({completed: true}); output.close({completed: true});
  const text = fs.readFileSync(file, 'utf8');
  assert.deepEqual(inspectExchangeOutput(text), {complete: true, records: 4});
  const header = JSON.parse(text.split('\n')[0]);
  assert.equal(header.artifactSha256, hash);
  assert.equal(header.scope.classification, 'correctness-fixture-diagnostic');
  assert.match(header.scope.contamination, /foreign-Origin/);
  assert.throws(() => output.record(record()), /closed|Invalid/);
  assert.equal(fs.statSync(file).mode & 0o777, 0o600);
}));

test('incomplete and truncated output cannot be treated as complete', () => temporary(file => {
  const output = createExchangeOutput(file, metadata);
  output.record(record()); output.close({completed: false});
  const text = fs.readFileSync(file, 'utf8');
  assert.equal(inspectExchangeOutput(text).complete, false);
  assert.equal(inspectExchangeOutput(text.slice(0, -3)).complete, false);
  assert.equal(inspectExchangeOutput(text.split('\n').slice(0, -2).join('\n') + '\n').complete, false);
  assert.equal(inspectExchangeOutput('{\n').complete, false);
}));

test('record whitelist refuses sensitive additions and duplicated work', () => temporary(file => {
  const output = createExchangeOutput(file, metadata);
  assert.throws(() => output.record({...record(), cookie: 'private'}), /Invalid/);
  assert.throws(() => output.record({...record(), issues: ['private']}), /Invalid/);
  assert.throws(() => output.record({...record(), physicalWebSocketDataFramesSent: 1}), /Invalid/);
  output.record(record());
  assert.throws(() => output.record(record()), /Invalid/);
  output.close({completed: false});
  assert.ok(!fs.readFileSync(file, 'utf8').includes('private'));
}));

test('observed records require usable counts, bounded resource peaks and complete handle release', () => temporary(file => {
  const output = createExchangeOutput(file, metadata);
  for (const field of ['pendingRequestsAtStop', 'activeSocketsAtStop', 'retainedRequestHandlesAfterStop',
    'retainedSocketHandlesAfterStop', 'retainedCdpSocketIdsAfterStop', 'retainedCdpSessionHandlesAfterStop']) {
    const bad = record(); bad.resources[field] = 1;
    assert.throws(() => output.record(bad), /Invalid/);
  }
  const unusable = record(); Object.keys(unusable.usableCounts).forEach(key => { unusable.usableCounts[key] = null; });
  assert.throws(() => output.record(unusable), /Invalid/);
  const overflow = record(); overflow.resources.peakActiveSockets = overflow.limits.activeSockets + 1;
  assert.throws(() => output.record(overflow), /Invalid/);
  const unresolved = record(); unresolved.resources.webkitActiveIdsAtRetiredBoundaries = 1;
  assert.throws(() => output.record(unresolved), /Invalid/);
  records(output); output.close({completed: true});
  const rows = fs.readFileSync(file, 'utf8').trim().split('\n').map(JSON.parse);
  rows[1].usableCounts.webSocketFramesSent = null;
  assert.equal(inspectExchangeOutput(rows.map(JSON.stringify).join('\n') + '\n').complete, false);
}));

test('writer and inspector reject impossible phase HTTP counts even when aggregate counts and phase sums are preserved', () => temporary(file => {
  const move = (row, name) => {
    row.phases.begin = Object.fromEntries(Object.keys(row.counters).map(key => [key, 0]));
    --row.phases.bootstrap[name]; ++row.phases.begin[name];
    for (const key of Object.keys(row.counters))
      assert.equal(Object.values(row.phases).reduce((sum, phase) => sum + phase[key], 0), row.counters[key]);
  };
  const output = createExchangeOutput(file, metadata);
  const fields = ['httpCompletedExchanges', 'httpRequests', 'httpResponses', 'httpFinished'];
  for (const field of fields) {
    const malformed = record(); move(malformed, field);
    assert.throws(() => output.record(malformed), /Invalid/);
  }
  records(output); output.close({completed: true});
  const valid = fs.readFileSync(file, 'utf8');
  assert.equal(inspectExchangeOutput(valid).complete, true);
  for (const field of fields) {
    const rows = valid.trim().split('\n').map(JSON.parse); move(rows[1], field);
    assert.equal(inspectExchangeOutput(rows.map(JSON.stringify).join('\n') + '\n').complete, false);
  }
}));

test('per-phase HTTP checks still allow socket lifecycles to cross phase boundaries', () => temporary(file => {
  const page = new EventEmitter(), observer = new BrowserExchangeObserver(page, metadata);
  completeHttp(page);
  const socket = new EventEmitter(); page.emit('websocket', socket);
  observer.phase('begin');
  socket.emit('framesent', {}); socket.emit('framereceived', {}); socket.emit('close');
  const crossing = observer.finish({completed: true});
  assert.equal(crossing.phases.bootstrap.webSocketsOpened, 1);
  assert.equal(crossing.phases.bootstrap.webSocketsClosed, 0);
  assert.equal(crossing.phases.begin.webSocketsOpened, 0);
  assert.equal(crossing.phases.begin.webSocketsClosed, 1);
  const output = createExchangeOutput(file, metadata);
  output.record(crossing);
  output.record(record('chromium', 'challenged'));
  output.record(record('webkit', 'password-only'));
  output.record(record('webkit', 'challenged'));
  output.close({completed: true});
  assert.equal(inspectExchangeOutput(fs.readFileSync(file, 'utf8')).complete, true);
}));

test('inspector requires exact scope, limits and boolean completion rather than a stripped or augmented header/footer', () => temporary(file => {
  const output = createExchangeOutput(file, metadata); records(output); output.close({completed: true});
  const valid = fs.readFileSync(file, 'utf8');
  for (const mutate of [
    rows => { delete rows[0].scope; },
    rows => { rows[0].scope.contamination = 'none'; },
    rows => { rows[0].maximumWorkflowRecords = 5; },
    rows => { rows[0].maximumRecordBytes = 0; },
    rows => { rows[0].extra = 'private'; },
    rows => { rows.at(-1).completedSuite = 'yes'; },
    rows => { rows.at(-1).extra = 'private'; },
    rows => { rows[1].counters.webSocketsClosed = 0; rows[1].phases.bootstrap.webSocketsClosed = 0; },
    rows => {
      for (const counts of [rows[1].counters, rows[1].phases.bootstrap]) {
        counts.cdpWebSocketFramesSent = 1; counts.cdpWebSocketUnknownFramesSent = 1;
      }
    },
  ]) {
    const rows = valid.trim().split('\n').map(JSON.parse); mutate(rows);
    assert.equal(inspectExchangeOutput(rows.map(JSON.stringify).join('\n') + '\n').complete, false);
  }
}));

test('writer handles short writes and closes after a non-progressing write without a success footer', () => temporary(file => {
  let zero = false, closed = 0;
  const io = {...fs,
    writeSync(fd, bytes, offset, length) { return zero ? 0 : fs.writeSync(fd, bytes, offset, Math.min(length, 7)); },
    closeSync(fd) { ++closed; fs.closeSync(fd); },
  };
  const output = createExchangeOutput(file, metadata, io);
  output.record(record()); zero = true;
  assert.throws(() => output.record(record('webkit')), /progress/);
  output.close({completed: true});
  assert.equal(closed, 1);
  assert.equal(inspectExchangeOutput(fs.readFileSync(file, 'utf8')).complete, false);
}));

test('failed header writing closes the owned descriptor and preserves incomplete evidence', () => temporary(file => {
  let closed = 0;
  const io = {...fs, writeSync() { throw new Error('synthetic write failure'); },
    closeSync(fd) { ++closed; fs.closeSync(fd); }};
  assert.throws(() => createExchangeOutput(file, metadata, io), /synthetic/);
  assert.equal(closed, 1);
  assert.equal(inspectExchangeOutput(fs.readFileSync(file, 'utf8')).complete, false);
}));
