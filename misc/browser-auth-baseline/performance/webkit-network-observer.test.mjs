import assert from 'node:assert/strict';
import test from 'node:test';
import {EventEmitter} from 'node:events';
import {WebKitNetworkObserver, createPinnedWebKitNetworkObserver} from './webkit-network-observer.mjs';

class Session extends EventEmitter {
  constructor(id) { super(); this.sessionId = id; this.disposed = false; this.commands = []; }
  isDisposed() { return this.disposed; }
  async send(method, params) { this.commands.push([method, params]); }
  detach() { throw new Error('Borrowed session must not detach'); }
  dispose() { this.disposed = true; }
}
class Delegate {
  constructor() { this._session = new Session('current'); this._pageProxySession = new EventEmitter(); this.calls = []; this.promise = Promise.resolve('original'); }
  _initializeSession(session) { this.calls.push(session); return this.promise; }
  _setSession(session) { this._session = session; return 17; }
}
function complete(session, id = 'opaque') {
  session.emit('Network.webSocketCreated', {requestId: id, get url() { throw new Error('URL read'); }});
  session.emit('Network.webSocketFrameSent', {requestId: id, response: {
    opcode: 2, get payloadData() { throw new Error('Payload read'); },
  }});
  session.emit('Network.webSocketClosed', {requestId: id});
  session.emit('Network.webSocketClosed', {requestId: id});
}

test('tracks current/provisional sessions, drains queued metadata on promotion and restores original methods', async () => {
  const delegate = new Delegate(), events = [];
  const initial = delegate._session, initialize = delegate._initializeSession, set = delegate._setSession;
  const observer = new WebKitNetworkObserver(delegate, {onEvent: (...event) => events.push(event)});
  const next = new Session('provisional');
  assert.strictEqual(delegate._initializeSession(next), delegate.promise);
  Promise.resolve().then(() => complete(initial));
  assert.equal(delegate._setSession(next), 17);
  await Promise.resolve();
  complete(next);
  const result = await observer.stop();
  assert.equal(result.status, 'observed');
  assert.equal(result.peakSessions, 2);
  assert.equal(result.activeSessions, 0);
  assert.equal(result.retainedSocketIds, 0);
  assert.equal(result.pendingRetirements, 0);
  assert.equal(result.retainedPageHandles, 0);
  assert.equal(events.filter(([name]) => name === 'sent').length, 2);
  assert.equal(events.filter(([name]) => name === 'duplicate-close').length, 2);
  assert.strictEqual(delegate._initializeSession, initialize);
  assert.strictEqual(delegate._setSession, set);
  assert.equal(Object.hasOwn(delegate, '_initializeSession'), false);
  assert.equal(Object.hasOwn(delegate, '_setSession'), false);
  assert.equal(initial.eventNames().length, 0); assert.equal(next.eventNames().length, 0);
  assert.equal(delegate._pageProxySession.eventNames().length, 0);
});

test('canceled provisional target releases only its listeners after already queued events', async () => {
  const delegate = new Delegate(), observer = new WebKitNetworkObserver(delegate);
  const provisional = new Session('provisional'); delegate._initializeSession(provisional);
  Promise.resolve().then(() => complete(provisional));
  provisional.dispose(); delegate._pageProxySession.emit('Target.targetDestroyed', {targetId: 'provisional'});
  await Promise.resolve();
  assert.equal(observer.snapshot().activeSessions, 1);
  assert.equal(provisional.eventNames().length, 0);
  assert.equal((await observer.stop()).status, 'observed');
});

test('retiring an unresolved socket boundary never certifies completeness after clearing IDs', async () => {
  const delegate = new Delegate(), observer = new WebKitNetworkObserver(delegate);
  delegate._session.emit('Network.webSocketCreated', {requestId: 'unclosed'});
  const next = new Session('next'); delegate._initializeSession(next); delegate._setSession(next);
  await Promise.resolve();
  const result = await observer.stop();
  assert.equal(result.status, 'inconclusive');
  assert.ok(result.failures.includes('active-ids-at-session-retirement'));
  assert.equal(result.activeIdsAtRetiredBoundaries, 1);
  assert.equal(result.retainedSocketIds, 0);
});

test('third session fails observation without changing the original initialization promise', async () => {
  const delegate = new Delegate(), observer = new WebKitNetworkObserver(delegate);
  const second = new Session('second'), third = new Session('third');
  delegate._initializeSession(second);
  assert.strictEqual(delegate._initializeSession(third), delegate.promise);
  assert.deepEqual(delegate.calls, [second, third]);
  assert.equal(observer.snapshot().peakSessions, 2);
  assert.equal(third.eventNames().length, 0);
  assert.ok((await observer.stop()).failures.includes('session-limit'));
});

test('session disposal while draining remains explicit inconclusive evidence', async () => {
  const delegate = new Delegate(), observer = new WebKitNetworkObserver(delegate);
  let acknowledge;
  delegate._session.send = () => new Promise(resolve => { acknowledge = resolve; });
  const stopping = observer.stop();
  delegate._session.dispose(); delegate._pageProxySession.emit('Target.targetDestroyed', {targetId: 'current'});
  acknowledge();
  const result = await stopping;
  assert.equal(result.status, 'inconclusive');
  assert.ok(result.failures.includes('session-changed-during-drain'));
  assert.equal(result.activeSessions, 0);
});

test('unknown close and replaced hook cannot produce an observed release', async () => {
  const delegate = new Delegate(), observer = new WebKitNetworkObserver(delegate);
  delegate._session.emit('Network.webSocketClosed', {requestId: 'unknown'});
  const replacement = () => 99; delegate._setSession = replacement;
  const result = await observer.stop();
  assert.ok(result.failures.includes('unowned-close'));
  assert.ok(result.failures.includes('hook-replaced'));
  assert.strictEqual(delegate._setSession, replacement);
  assert.equal(result.activeSessions, 0);
});

test('borrowed listener ownership and source pin guards fail before unsupported observation', () => {
  const delegate = new Delegate(); delegate._initializeSession = () => Promise.resolve();
  assert.throws(() => new WebKitNetworkObserver(delegate), /unavailable/);
  assert.throws(() => createPinnedWebKitNetworkObserver({}, {driverRoot: '/host'}), /Nix/);
});
