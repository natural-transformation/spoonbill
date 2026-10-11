import assert from 'node:assert/strict';
import test from 'node:test';
import net from 'node:net';
import {mkdtempSync, mkdirSync, readFileSync, rmSync, statSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {disposableTarget, proxyEnvironment, conformanceSummary, measureWire, probe} from './reference-wire-probe.mjs';

const env = port => ({...process.env, SPOONBILL_JDBC_TEST_USER: 'spoonbill_test',
  SPOONBILL_JDBC_TEST_PASSWORD: '', PGPASSWORD: '',
  SPOONBILL_JDBC_TEST_URL: `jdbc:postgresql://127.0.0.1:${port}/postgres?user=spoonbill_test`});
const summary = 'Total number of tests run: 72\nSuites: completed 2, aborted 0\nTests: succeeded 72, failed 0, canceled 0, ignored 0, pending 0\nAll tests passed.\n';

test('only the disposable synthetic loopback target can be forwarded; encryption is explicitly disabled', () => {
  assert.equal(disposableTarget(env(5432)), 5432);
  const forwarded = proxyEnvironment(env(5432), 5433).SPOONBILL_JDBC_TEST_URL;
  const url = new URL(forwarded.slice(5));
  assert.equal(url.port, '5433');
  assert.equal(url.searchParams.get('sslmode'), 'disable');
  assert.equal(url.searchParams.get('gssEncMode'), 'disable');
  for (const change of [e => e.SPOONBILL_JDBC_TEST_PASSWORD = 'not-accepted',
    e => e.SPOONBILL_JDBC_TEST_URL = 'jdbc:postgresql://remote.invalid:5432/postgres?user=spoonbill_test',
    e => e.SPOONBILL_JDBC_TEST_URL += '&password=not-accepted',
    e => e.SPOONBILL_JDBC_TEST_URL += '&options=unreviewed',
    e => e.SPOONBILL_JDBC_TEST_URL = 'jdbc:postgresql://127.0.0.1/postgres?user=spoonbill_test']) {
    const config = env(5432); change(config);
    assert.throws(() => disposableTarget(config), error => !error.message.includes('not-accepted') && !error.message.includes('jdbc:'));
  }
});

test('test exit alone cannot stand in for complete conformance work', () => {
  assert.equal(conformanceSummary(summary).testsCompleted, 72);
  assert.equal(conformanceSummary(`\x1b[32m${summary}\x1b[0m`).suitesCompleted, 2);
  for (const text of [summary.replace('completed 2', 'completed 1'), summary.replace('failed 0', 'failed 1'),
    summary.replaceAll('72', '0'), summary.replace('canceled 0', 'canceled 1'), summary + summary, 'All tests passed.'])
    assert.throws(() => conformanceSummary(text));
});

async function peer(t) {
  const sockets = new Set();
  const server = net.createServer(socket => {
    sockets.add(socket); socket.on('error', () => {}); socket.once('close', () => sockets.delete(socket));
    let startup = true, pending = Buffer.alloc(0);
    socket.on('data', bytes => {
      pending = Buffer.concat([pending, bytes]);
      while (pending.length >= (startup ? 4 : 5)) {
        const length = pending.readUInt32BE(startup ? 0 : 1) + (startup ? 0 : 1);
        if (pending.length < length) break;
        const type = startup ? 'startup' : String.fromCharCode(pending[0]);
        pending = pending.subarray(length); startup = false;
        if (type === 'X') socket.end();
        else socket.write(Buffer.from([90, 0, 0, 0, 5, 73]));
      }
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(async () => { for (const socket of sockets) socket.destroy(); await new Promise(resolve => server.close(resolve)); });
  return server.address().port;
}

test('real child + local fake peer counts whole-fixture cycles after owned sockets settle without payload output', {timeout: 15000}, async t => {
  const port = await peer(t), logs = [];
  // This fake peer only tests orchestration. Real JDBC validation is a separate
  // explicitly documented disposable-PG invocation, never simulated evidence.
  const code = `const net=require('node:net'); const u=new URL(process.env.SPOONBILL_JDBC_TEST_URL.slice(5));
    const s=net.createConnection({host:u.hostname,port:Number(u.port)});let n=0;
    s.on('connect',()=>s.write(Buffer.from([0,0,0,8,0,3,0,0])));
    s.on('data',()=>{if(++n===1){const p=Buffer.from('synthetic-payload-not-a-metric\\0');const b=Buffer.alloc(p.length+5);b[0]=81;b.writeUInt32BE(p.length+4,1);p.copy(b,5);s.write(b);}
    else{s.end(Buffer.from([88,0,0,0,4]));}});
    s.on('close',()=>{process.stdout.write(${JSON.stringify(summary)});process.stderr.write('synthetic raw diagnostic');});`;
  const result = await measureWire({targetPort: port, command: [process.execPath, '-e', code], env: env(port),
    timeoutMs: 5000, onLog: (stream, bytes) => logs.push([stream, bytes.toString()])});
  assert.equal(result.status, 'complete');
  assert.equal(result.wire.startupExchanges, 1); assert.equal(result.wire.syncExchanges, 1);
  assert.equal(result.wire.activeConnections, 0); assert.equal(result.wire.retainedPayloadBytes, 0);
  assert.deepEqual(result.socketsBeforeProxyClose, {active: 0, pending: 0});
  assert.equal(result.scope.acceptanceEvidence, false);
  assert.ok(logs.some(([name, value]) => name === 'stderr' && value.includes('raw diagnostic')));
  for (const forbidden of ['synthetic-payload', 'jdbc:', 'postgresql:', 'spoonbill_test']) assert.ok(!JSON.stringify(result).includes(forbidden));
});

test('failed, missing-work, oversized-log and timed-out children remain inconclusive with bounded owned cleanup', {timeout: 15000}, async t => {
  const port = await peer(t);
  for (const [code, expected, timeoutMs, maxLogBytes] of [
    ['process.exit(3)', 'fixture-failed', 5000, 1024],
    ['process.stdout.write("no completion")', 'missing-conformance-completion', 5000, 1024],
    ['process.stdout.write("x".repeat(2048))', 'fixture-log-limit', 5000, 1024],
    ['setInterval(()=>{},1000)', 'fixture-deadline', 100, 1024]]) {
    const result = await measureWire({targetPort: port, command: [process.execPath, '-e', code], env: env(port), timeoutMs, maxLogBytes});
    assert.equal(result.status, 'inconclusive'); assert.equal(result.diagnostic, expected);
    assert.equal(result.wire.activeConnections, 0);
  }
});

test('opt-in probe completes protocol-cycle diagnostics while keeping overlapped round-trip metrics null', {timeout: 15000}, async t => {
  const port = await peer(t);
  const code = `const net=require('node:net');const u=new URL(process.env.SPOONBILL_JDBC_TEST_URL.slice(5));
    const s=net.createConnection({host:u.hostname,port:Number(u.port)});let seen=0,pending=Buffer.alloc(0);
    s.on('connect',()=>s.write(Buffer.from([0,0,0,8,0,3,0,0])));
    s.on('data',bytes=>{pending=Buffer.concat([pending,bytes]);while(pending.length>=6){pending=pending.subarray(6);seen++;
      if(seen===1)s.write(Buffer.from([81,0,0,0,5,0,81,0,0,0,5,0]));
      if(seen===3)s.end(Buffer.from([88,0,0,0,4]));}});
    s.on('close',()=>process.stdout.write(${JSON.stringify(summary)}));`;
  const result = await measureWire({targetPort: port, command: [process.execPath, '-e', code], env: env(port),
    mode: 'pipelined-cycles', maxPendingCycles: 2, timeoutMs: 5000});
  assert.equal(result.status, 'complete'); assert.equal(result.observationKind, 'protocol-cycles-diagnostic');
  assert.equal(result.wire.completedProtocolSyncCycles, 2); assert.equal(result.wire.syncExchanges, null);
  assert.equal(result.wire.physicalRoundTrips, null); assert.equal(result.wire.overlapObserved, true);
  assert.equal(result.wire.pendingCycleSlots, 0); assert.equal(result.wire.peakPendingCycleSlots, 2);
  assert.equal(result.wire.cycleStorageBytes, 0); assert.equal(result.scope.acceptanceEvidence, false);
});

test('exclusive diagnostic destinations preserve actual failed Java output privately without a PostgreSQL dependency', {timeout: 15000}, async () => {
  const root = mkdtempSync(path.join(tmpdir(), 'wire-probe-failure-'));
  const saved = Object.fromEntries(['SPOONBILL_JDBC_TEST_URL', 'SPOONBILL_JDBC_TEST_USER', 'SPOONBILL_JDBC_TEST_PASSWORD', 'PGPASSWORD'].map(name => [name, process.env[name]]));
  try {
    const configured = env(1);
    for (const name of Object.keys(saved)) process.env[name] = configured[name];
    const empty = path.join(root, 'empty-classpath'); mkdirSync(empty);
    const cp = path.join(root, 'classpath.txt'); writeFileSync(cp, empty);
    const destination = path.join(root, 'report.jsonl');
    // Real Java cannot load ScalaTest from this deliberately empty classpath.
    // No synthetic success result or wire observation is emitted.
    await assert.rejects(probe(cp, destination), /inconclusive/);
    const before = readFileSync(destination, 'utf8');
    const rows = before.trim().split('\n').map(JSON.parse);
    assert.equal(rows.at(-1).kind, 'failure');
    assert.equal(rows[1].status, 'inconclusive');
    assert.equal(rows[1].completion, null);
    const raw = readFileSync(`${destination}.private-processes.jsonl`, 'utf8');
    assert.match(raw, /ClassNotFoundException|Could not find or load main class/);
    assert.equal(statSync(`${destination}.private-processes.jsonl`).mode & 0o777, 0o600);
    assert.equal(statSync(destination).mode & 0o777, 0o600);
    await assert.rejects(probe(cp, destination), /EEXIST/);
    assert.equal(readFileSync(destination, 'utf8'), before);
  } finally {
    for (const [name, value] of Object.entries(saved)) if (value === undefined) delete process.env[name]; else process.env[name] = value;
    rmSync(root, {recursive: true, force: true});
  }
});
