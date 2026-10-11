import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import {once, EventEmitter} from 'node:events';
import {WebSocketWireCounter, createWebSocketCalibrationServer, observeWebSocketEvents} from './websocket-events.mjs';

function frame(opcode, length = 0, fin = true) {
  const extended = length >= 65536 ? 8 : length >= 126 ? 2 : 0;
  const bytes = Buffer.alloc(2 + extended + 4 + length, 120);
  bytes[0] = (fin ? 128 : 0) | opcode; bytes[1] = 128 | (extended === 8 ? 127 : extended === 2 ? 126 : length);
  if (extended === 8) bytes.writeBigUInt64BE(BigInt(length), 2);
  if (extended === 2) bytes.writeUInt16BE(length, 2);
  return bytes;
}

test('fixed header storage handles split headers, empty frames and all length encodings without payload retention', () => {
  let completed = 0;
  const counter = new WebSocketWireCounter({onMessage: () => completed++});
  for (const length of [0, 1, 125, 126, 65535, 65536]) {
    const bytes = frame(2, length);
    for (let i = 0; i < bytes.length; i += 7) {
      counter.push(bytes.subarray(i, i + 7));
      assert.ok(counter.snapshot().bufferedHeaderBytes <= 14);
      assert.equal(counter.snapshot().allocatedParserBytes, 14);
      assert.equal(counter.snapshot().retainedPayloadBytes, 0);
    }
  }
  assert.equal(completed, 6);
  assert.equal(counter.finish().status, 'complete');
  assert.equal(counter.snapshot().physicalDataFrames, 6);
});

test('physical data frames and completed logical messages differ for continuations with interleaved control', () => {
  const counter = new WebSocketWireCounter();
  counter.push(Buffer.concat([frame(2, 128, false), frame(9, 1), frame(0, 0, false), frame(0, 5), frame(1), frame(8)]));
  const result = counter.finish();
  assert.equal(result.physicalDataFrames, 4);
  assert.equal(result.continuationFrames, 2);
  assert.equal(result.completedMessages, 2);
  assert.equal(result.controlFrames, 2);
  assert.equal(result.status, 'complete');
});

test('malformed masking, reserved flags, continuations, controls, bounds and truncation fail closed', () => {
  const cases = [Buffer.from([0x82, 0]), Buffer.from([0xc2, 0x80]), frame(0), frame(9, 0, false), frame(8, 1), frame(3)];
  for (const bytes of cases) {
    const counter = new WebSocketWireCounter(); assert.throws(() => counter.push(bytes));
    assert.equal(counter.finish().physicalDataFrames, null);
  }
  const fragmented = new WebSocketWireCounter(); fragmented.push(frame(2, 1, false));
  assert.throws(() => fragmented.push(frame(1)));
  const truncated = new WebSocketWireCounter(); truncated.push(frame(2, 128).subarray(0, 40));
  assert.equal(truncated.finish().status, 'inconclusive');
  const bounded = new WebSocketWireCounter({maxFrameBytes: 10}); assert.throws(() => bounded.push(frame(2, 11)));
  const frames = new WebSocketWireCounter({maxFrames: 1}); assert.throws(() => frames.push(Buffer.concat([frame(2), frame(2)])));
  const bytes = new WebSocketWireCounter({maxWireBytes: 2}); assert.throws(() => bytes.push(frame(2)));
});

test('synthetic loopback server acknowledges completed physical messages and releases sockets', async () => {
  const server = await createWebSocketCalibrationServer();
  const address = new URL(server.origin), socket = net.connect(Number(address.port), '127.0.0.1');
  socket.on('error', () => {});
  try {
    await once(socket, 'connect');
    const upgraded = once(socket, 'data');
    socket.write('GET / HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: AAAAAAAAAAAAAAAAAAAAAA==\r\nSec-WebSocket-Version: 13\r\n\r\n');
    assert.match((await upgraded)[0].toString(), /^HTTP\/1.1 101 /);
    const acknowledged = once(socket, 'data'); socket.write(Buffer.concat([frame(2, 128, false), frame(0, 128)]));
    assert.deepEqual((await acknowledged)[0], Buffer.from([0x81, 3, 65, 67, 75]));
    assert.equal(server.snapshot().wire.physicalDataFrames, 2);
    assert.equal(server.snapshot().wire.completedMessages, 1);
  } finally {
    socket.destroy();
    const result = await server.close();
    assert.equal(result.activeSockets, 0);
  }
});

test('browser observers touch metadata only, preserve duplicate events and remove listeners on work failure', async () => {
  const page = new EventEmitter(), session = new EventEmitter(), socket = new EventEmitter();
  let detached = 0;
  page.context = () => ({newCDPSession: async () => session});
  session.send = async () => {}; session.detach = async () => { detached++; };
  Object.defineProperty(socket, 'url', {get() { throw new Error('URL read'); }});
  const response = {opcode: 2}; Object.defineProperty(response, 'payloadData', {get() { throw new Error('Payload read'); }});
  const result = await observeWebSocketEvents(page, 'chromium', async () => {
    page.emit('websocket', socket);
    session.emit('Network.webSocketCreated', {requestId: 'owned'});
    socket.emit('framesent', new Proxy({}, {get() { throw new Error('Public payload read'); }}));
    session.emit('Network.webSocketFrameSent', {requestId: 'owned', response});
    session.emit('Network.webSocketFrameSent', {requestId: 'owned', response});
  });
  assert.equal(result.publicFrameSentEvents, 1); assert.equal(result.cdpSentEvents.binary, 2);
  assert.equal(result.physicalFramesFromBrowserEvents, null); assert.equal(detached, 1);
  assert.equal(page.listenerCount('websocket'), 0); assert.equal(socket.listenerCount('framesent'), 0);
  await assert.rejects(observeWebSocketEvents(page, 'chromium', async () => { throw new Error('controlled'); }), /controlled/);
  assert.equal(detached, 2); assert.equal(session.listenerCount('Network.webSocketFrameSent'), 0);
});
