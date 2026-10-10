// Explicit physical PostgreSQL test: missing disposable-test configuration FAILS.
// Run only via the documented with-test-postgres.sh command, not the no-PG glob.
import test from 'node:test';
import assert from 'node:assert/strict';
import net from 'node:net';
import {once} from 'node:events';
import {createPostgresWireProxy} from './postgres-wire-proxy.mjs';

function packet(type, body = Buffer.alloc(0)) {
  const bytes = Buffer.alloc(5 + body.length);
  bytes[0] = type.charCodeAt(0); bytes.writeUInt32BE(4 + body.length, 1); body.copy(bytes, 5);
  return bytes;
}
// Independent small reply reader for this synthetic SELECT; not proxy code.
function ready(socket) {
  return new Promise((resolve, reject) => {
    let buffered = Buffer.alloc(0);
    const timeout = setTimeout(() => finish(new Error('Disposable PostgreSQL did not become ready')), 5000);
    function finish(error) {
      clearTimeout(timeout); socket.removeListener('data', data); socket.removeListener('error', failed);
      socket.removeListener('end', ended);
      if (error) reject(error); else resolve();
    }
    const failed = () => finish(new Error('Disposable PostgreSQL socket failed'));
    const ended = () => finish(new Error('Disposable PostgreSQL closed before ReadyForQuery'));
    function data(bytes) {
      buffered = Buffer.concat([buffered, bytes]);
      while (buffered.length >= 5) {
        const length = buffered.readUInt32BE(1);
        if (length < 4 || length > 1024 * 1024) { finish(new Error('Unexpected test reply size')); return; }
        if (buffered.length < length + 1) return;
        const type = buffered[0];
        if (type === 82 && (length !== 8 || buffered.readUInt32BE(5) !== 0)) {
          finish(new Error('The physical test requires disposable trust authentication')); return;
        }
        buffered = buffered.subarray(length + 1);
        if (type === 90) { finish(); return; }
        if (type === 69) { finish(new Error('Disposable PostgreSQL rejected the synthetic operation')); return; }
      }
    }
    socket.on('data', data); socket.once('error', failed); socket.once('end', ended);
  });
}

test('real disposable PostgreSQL separates startup and simple-query ReadyForQuery exchanges', {timeout: 15000}, async t => {
  assert.ok(process.env.SPOONBILL_JDBC_TEST_URL, 'Run through scripts/with-test-postgres.sh; this physical test never silently skips');
  assert.ok(process.env.SPOONBILL_JDBC_TEST_USER === 'spoonbill_test', 'Synthetic test user required');
  let target;
  try { target = new URL(process.env.SPOONBILL_JDBC_TEST_URL.replace(/^jdbc:/, '')); }
  catch (_) { throw new Error('Invalid disposable PostgreSQL test URL'); }
  assert.ok(target.protocol === 'postgresql:' && target.hostname === '127.0.0.1' &&
    target.pathname === '/postgres' && !target.password && !target.username, 'Expected a credential-free disposable loopback test URL');
  const proxy = await createPostgresWireProxy({targetPort: Number(target.port)});
  t.after(() => proxy.close());
  const client = net.createConnection({host: '127.0.0.1', port: proxy.port});
  t.after(() => client.destroy());
  await once(client, 'connect');
  const parameters = Buffer.from('user\0spoonbill_test\0database\0postgres\0application_name\0spoonbill-wire-probe\0\0');
  const startup = Buffer.alloc(8 + parameters.length);
  startup.writeUInt32BE(startup.length, 0); startup.writeUInt32BE(196608, 4); parameters.copy(startup, 8);
  let completed = ready(client); client.write(startup); await completed;
  assert.equal(proxy.snapshot().startupExchanges, 1); assert.equal(proxy.snapshot().syncExchanges, 0);
  completed = ready(client); client.write(packet('Q', Buffer.from('SELECT 1\0'))); await completed;
  const measured = proxy.snapshot();
  assert.equal(measured.startupExchanges, 1); assert.equal(measured.syncExchanges, 1);
  assert.equal(measured.pendingExchanges, 0); assert.equal(measured.retainedPayloadBytes, 0);
  assert.ok(!JSON.stringify(measured).includes('SELECT'));
  const closed = once(client, 'close'); client.end(packet('X')); await closed;
  const final = await proxy.close();
  assert.equal(final.status, 'complete'); assert.equal(final.activeConnections, 0);
  assert.equal(final.closedConnections, 1); assert.equal(final.syncExchanges, 1);
});
