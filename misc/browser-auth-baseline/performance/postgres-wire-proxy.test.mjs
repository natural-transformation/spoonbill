import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import {once} from 'node:events';
import {ProtocolCounter, ProtocolDiagnostic, createPostgresWireProxy} from './postgres-wire-proxy.mjs';

const startup = Buffer.from([0, 0, 0, 8, 0, 3, 0, 0]);
function message(type, payload = Buffer.alloc(0)) {
  const bytes = Buffer.alloc(5 + payload.length);
  bytes[0] = type.charCodeAt(0); bytes.writeUInt32BE(payload.length + 4, 1); payload.copy(bytes, 5);
  return bytes;
}
const ready = message('Z', Buffer.from('I'));
const query = () => message('Q', Buffer.from('SELECT 1\0'));
function started(options) { const counter = new ProtocolCounter(options); counter.frontend(startup); counter.backend(ready); return counter; }

test('fragmented headers and coalesced replies count completed sync cycles, not packets/messages', () => {
  const counter = new ProtocolCounter();
  for (const byte of startup) counter.frontend(Buffer.from([byte]));
  const authentication = Buffer.concat([message('R', Buffer.alloc(4)), message('S', Buffer.from('ignored\0value\0')), ready]);
  for (const byte of authentication) counter.backend(Buffer.from([byte]));
  const sql = query();
  for (const byte of sql) counter.frontend(Buffer.from([byte]));
  counter.backend(Buffer.concat([message('T', Buffer.from('description')), message('D', Buffer.from('row')),
    message('C', Buffer.from('SELECT 1\0')), ready]));
  assert.equal(counter.snapshot().startupExchanges, 1);
  assert.equal(counter.snapshot().syncExchanges, 1);
  assert.equal(counter.snapshot().backendMessages, 7);
  assert.equal(counter.snapshot().frontendMessages, 2);
  assert.equal(counter.snapshot().frontendBytes, startup.length + sql.length);
  assert.equal(counter.finish().status, 'complete');
});

test('extended Parse/Bind/Describe/Execute/Sync is one exchange', () => {
  const counter = started();
  counter.frontend(Buffer.concat(['P', 'B', 'D', 'E'].map(type => message(type, Buffer.from('payload'))).concat(message('S'))));
  counter.backend(Buffer.concat([message('1'), message('2'), message('T'), message('D'), message('C'), ready]));
  assert.equal(counter.snapshot().syncRequests, 1);
  assert.equal(counter.snapshot().syncExchanges, 1);
  assert.equal(counter.snapshot().frontendMessages, 6);
  assert.equal(counter.finish().status, 'complete');
});

test('authentication payload is skipped during startup and never retained in snapshots', () => {
  const counter = new ProtocolCounter();
  counter.frontend(startup);
  counter.backend(message('R', Buffer.from([0, 0, 0, 3])));
  counter.frontend(message('p', Buffer.from('synthetic-private-password\0')));
  counter.backend(Buffer.concat([message('R', Buffer.alloc(4)), ready]));
  const snapshot = counter.snapshot();
  assert.equal(snapshot.startupExchanges, 1);
  assert.equal(snapshot.syncExchanges, 0);
  assert.equal(JSON.stringify(snapshot).includes('synthetic-private-password'), false);
  assert.equal(snapshot.retainedPayloadBytes, 0);
});

test('large payloads retain only fixed headers, and snapshots are detached frozen values', () => {
  const counter = started();
  const bytes = message('Q', Buffer.alloc(2 * 1024 * 1024, 120));
  for (let offset = 0; offset < bytes.length; offset += 4096) {
    counter.frontend(bytes.subarray(offset, offset + 4096));
    assert.ok(counter.snapshot().bufferedHeaderBytes <= 13);
    assert.equal(counter.snapshot().retainedPayloadBytes, 0);
    assert.equal(counter.snapshot().allocatedParserBytes, 13);
  }
  const before = counter.snapshot();
  counter.backend(ready);
  assert.equal(before.syncExchanges, 0);
  assert.equal(counter.snapshot().syncExchanges, 1);
  assert.ok(Object.isFrozen(before)); assert.ok(Object.isFrozen(before.diagnostics));
  assert.throws(() => { before.diagnostics.extra = 1; }, TypeError);
});

test('unsupported TLS/GSS/cancel/protocols/Flush/Copy return closed diagnostics and null measurements', () => {
  for (const [version, code] of [[80877103, 'SSL_UNSUPPORTED'], [80877104, 'GSS_ENCRYPTION_UNSUPPORTED'],
    [80877102, 'CANCEL_CONNECTION_UNSUPPORTED'], [196609, 'PROTOCOL_VERSION_UNSUPPORTED']]) {
    const counter = new ProtocolCounter(), bytes = Buffer.from(startup); bytes.writeUInt32BE(version, 4);
    assert.throws(() => counter.frontend(bytes), error => error instanceof ProtocolDiagnostic && error.code === code);
    assert.equal(counter.snapshot().syncExchanges, null);
  }
  for (const [side, type, code] of [['frontend', 'H', 'FLUSH_UNSUPPORTED'], ['frontend', 'd', 'COPY_UNSUPPORTED'],
    ['backend', 'G', 'COPY_UNSUPPORTED'], ['backend', 'H', 'COPY_UNSUPPORTED'], ['backend', 'W', 'COPY_UNSUPPORTED']]) {
    const counter = started();
    assert.throws(() => counter[side](message(type)), {code});
    assert.equal(counter.snapshot().status, 'inconclusive');
    assert.equal(counter.snapshot().startupExchanges, null);
  }
});

test('overlap, pipelined extended work, missing Sync, invalid size and unsolicited Ready fail closed', () => {
  for (const packet of [query(), message('S'), message('P', Buffer.from('payload'))]) {
    const counter = started(); counter.frontend(query());
    assert.throws(() => counter.frontend(packet), {code: 'OVERLAPPING_EXCHANGE'});
  }
  const missing = started(); missing.frontend(message('P'));
  assert.throws(() => missing.frontend(query()), {code: 'EXTENDED_CYCLE_WITHOUT_SYNC'});
  const unsolicited = started(); assert.throws(() => unsolicited.backend(ready), {code: 'UNEXPECTED_READY_FOR_QUERY'});
  const oversized = started({maxMessageBytes: 32});
  const header = message('Q'); header.writeUInt32BE(33, 1);
  assert.throws(() => oversized.frontend(header), {code: 'MESSAGE_TOO_LARGE'});
  const malformed = started(); const short = message('Q'); short.writeUInt32BE(3, 1);
  assert.throws(() => malformed.frontend(short), {code: 'INVALID_MESSAGE_LENGTH'});
  const wrongReady = started(); wrongReady.frontend(query());
  assert.throws(() => wrongReady.backend(message('Z')), {code: 'INVALID_READY_LENGTH'});
});

test('EOF detects missing Ready and truncated headers/payload without retaining them', () => {
  const counter = started(); counter.frontend(query());
  assert.equal(counter.finish().diagnostics.MISSING_READY_FOR_QUERY, 1);
  assert.equal(counter.finish().diagnostics.MISSING_READY_FOR_QUERY, 1, 'finish must be idempotent');
  assert.equal(counter.snapshot().syncExchanges, null);
  const partial = started(); partial.frontend(query().subarray(0, 7));
  assert.equal(partial.finish().diagnostics.TRUNCATED_MESSAGE, 1);
  assert.equal(partial.snapshot().retainedPayloadBytes, 0);
  const noSync = started(); noSync.frontend(message('P'));
  assert.equal(noSync.finish().diagnostics.EXTENDED_CYCLE_WITHOUT_SYNC, 1);
});

async function fakeServer(t, connected) {
  const clients = new Set();
  const server = net.createServer(socket => {
    clients.add(socket); socket.once('close', () => clients.delete(socket));
    socket.on('error', () => {}); connected(socket);
  });
  server.listen(0, '127.0.0.1'); await once(server, 'listening');
  t.after(async () => { for (const socket of clients) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  return server.address().port;
}
async function connect(port) { const socket = net.createConnection({host: '127.0.0.1', port}); socket.on('error', () => {}); await once(socket, 'connect'); return socket; }

test('owned loopback sockets forward fragmented/coalesced traffic and retire all resources', {timeout: 5000}, async t => {
  let accepted;
  const acceptedSocket = new Promise(resolve => { accepted = resolve; });
  const targetPort = await fakeServer(t, accepted);
  const proxy = await createPostgresWireProxy({targetPort});
  t.after(() => proxy.close());
  const client = await connect(proxy.port);
  t.after(() => client.destroy());
  // Use exact delivery barriers so counts never rely on arbitrary sleeps.
  const readBytes = (socket, bytes) => new Promise(resolve => {
    let observed = 0;
    const listener = chunk => { observed += chunk.length; if (observed === bytes) { socket.removeListener('data', listener); resolve(); } };
    socket.on('data', listener);
  });
  const serverSocket = await acceptedSocket;
  const started = readBytes(serverSocket, startup.length);
  client.write(startup.subarray(0, 3)); client.write(startup.subarray(3));
  await started;
  let delivered = readBytes(client, ready.length); serverSocket.write(ready); await delivered;
  const received = readBytes(serverSocket, query().length); client.write(query()); await received;
  const reply = Buffer.concat([message('C'), ready]);
  delivered = readBytes(client, reply.length); serverSocket.write(reply); await delivered;
  assert.equal(proxy.snapshot().startupExchanges, 1);
  assert.equal(proxy.snapshot().syncExchanges, 1);
  assert.equal(proxy.snapshot().activeConnections, 1);
  client.end(); await once(client, 'close');
  const final = await proxy.close();
  assert.equal(final.activeConnections, 0); assert.equal(final.closedConnections, 1);
  assert.equal(final.retainedPayloadBytes, 0); assert.equal(final.status, 'complete');
  assert.strictEqual(proxy.close(), proxy.close());
});

test('proxy rejects SSL before forwarding it and closes its own sockets', {timeout: 5000}, async t => {
  let forwarded = 0;
  const targetPort = await fakeServer(t, socket => socket.on('data', bytes => { forwarded += bytes.length; }));
  const proxy = await createPostgresWireProxy({targetPort}); t.after(() => proxy.close());
  const client = await connect(proxy.port), ended = once(client, 'close');
  const ssl = Buffer.from(startup); ssl.writeUInt32BE(80877103, 4); client.write(ssl);
  await ended;
  const final = await proxy.close();
  assert.equal(forwarded, 0); assert.equal(final.activeConnections, 0);
  assert.equal(final.diagnostics.SSL_UNSUPPORTED, 1); assert.equal(final.syncExchanges, null);
});

test('shutdown with a pending exchange is inconclusive, and stalled sockets are bounded', {timeout: 5000}, async t => {
  const targetPort = await fakeServer(t, socket => socket.on('data', () => {}));
  const proxy = await createPostgresWireProxy({targetPort, socketTimeoutMs: 30}); t.after(() => proxy.close());
  const client = await connect(proxy.port), ended = once(client, 'close'); client.write(startup);
  await ended;
  const final = await proxy.close();
  assert.equal(final.activeConnections, 0);
  assert.equal(final.startupExchanges, null);
  assert.ok(final.diagnostics.SOCKET_TIMEOUT >= 1);
  assert.ok(final.diagnostics.MISSING_READY_FOR_QUERY >= 1);
});

test('connection capacity fails closed and shutdown closes every owned socket', {timeout: 5000}, async t => {
  const targetPort = await fakeServer(t, socket => socket.on('data', () => {}));
  const proxy = await createPostgresWireProxy({targetPort, maxConnections: 1}); t.after(() => proxy.close());
  const first = await connect(proxy.port); t.after(() => first.destroy());
  const second = await connect(proxy.port); t.after(() => second.destroy());
  await once(second, 'close');
  const final = await proxy.close();
  assert.equal(final.connections, 1); assert.equal(final.closedConnections, 1);
  assert.equal(final.activeConnections, 0); assert.equal(final.allocatedParserBytes, 0);
  assert.equal(final.diagnostics.CONNECTION_CAPACITY, 1); assert.equal(final.syncExchanges, null);
});

test('connection churn retires parser metadata without retaining a connection registry', {timeout: 5000}, async t => {
  const targetPort = await fakeServer(t, socket => socket.on('data', () => {}));
  const proxy = await createPostgresWireProxy({targetPort}); t.after(() => proxy.close());
  for (let index = 0; index < 10; index++) {
    const client = await connect(proxy.port), closed = once(client, 'close');
    client.end(); await closed;
  }
  const final = await proxy.close();
  assert.equal(final.connections, 10); assert.equal(final.closedConnections, 10);
  assert.equal(final.activeConnections, 0); assert.equal(final.allocatedParserBytes, 0);
  assert.equal(final.retainedPayloadBytes, 0); assert.deepEqual(final.diagnostics, {});
});
