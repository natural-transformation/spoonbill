import assert from 'node:assert/strict';
import test from 'node:test';
import {EventEmitter} from 'node:events';
import {observeWebKitSessionMetadata, observePinnedWebKitMetadata} from './webkit-network-probe.mjs';

class Session extends EventEmitter {
  commands = [];
  async send(name, value) { this.commands.push([name, value]); }
  detach() { throw new Error('Borrowed session must not detach'); }
  dispose() { throw new Error('Borrowed session must not dispose'); }
}
test('borrowed WebKit metadata counts empty events without payload access and preserves other listeners', async () => {
  const session = new Session();
  const other = () => {}; session.on('Network.webSocketFrameSent', other);
  const result = await observeWebKitSessionMetadata(session, async () => {
    session.emit('Network.webSocketCreated', {requestId: 'opaque', get url() { throw new Error('URL read'); }});
    for (const opcode of [1, 2, 2]) session.emit('Network.webSocketFrameSent', {requestId: 'opaque', response: {
      opcode, get payloadData() { throw new Error('Payload read'); },
    }});
    session.emit('Network.webSocketClosed', {requestId: 'opaque'});
    session.emit('Network.webSocketClosed', {requestId: 'opaque'});
  });
  assert.equal(result.status, 'observed');
  assert.deepEqual(result.sent, {all: 3, text: 1, binary: 2, continuation: 0, control: 0, other: 0});
  assert.equal(result.retainedIdsAfterStop, 0);
  assert.equal(result.closeEvents, 2);
  assert.equal(result.closed, 1);
  assert.equal(result.duplicateCloseEvents, 1);
  assert.deepEqual(session.commands, [['Runtime.evaluate', {expression: '0', returnByValue: true}]]);
  assert.deepEqual(session.listeners('Network.webSocketFrameSent'), [other]);
});
test('missing lifecycle and capacity overflow produce no usable completeness claim', async () => {
  for (const mode of ['unknown', 'unknown-close', 'overflow']) {
    const session = new Session();
    const result = await observeWebKitSessionMetadata(session, async () => {
      if (mode === 'unknown') session.emit('Network.webSocketFrameSent', {requestId: 'unseen', response: {opcode: 2}});
      else if (mode === 'unknown-close') session.emit('Network.webSocketClosed', {requestId: 'unseen'});
      else for (let index = 0; index < 5; ++index) session.emit('Network.webSocketCreated', {requestId: `opaque-${index}`});
    });
    assert.equal(result.status, 'inconclusive');
    assert.equal(result.retainedIdsAfterStop, 0);
    assert.equal(session.eventNames().length, 0);
    assert.ok(result.peakIds <= 4);
  }
});
test('work failure always removes borrowed-session observers', async () => {
  const session = new Session();
  await assert.rejects(observeWebKitSessionMetadata(session, async () => {
    session.emit('Network.webSocketCreated', {requestId: 'opaque'});
    throw new Error('Synthetic workload failed');
  }), /Synthetic workload/);
  assert.equal(session.eventNames().length, 0);
  await assert.rejects(observePinnedWebKitMetadata({}, async () => {}, {driverRoot: '/host-driver'}), /Nix/);
});
