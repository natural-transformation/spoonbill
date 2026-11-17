import assert from 'node:assert/strict';
import {after, test} from 'node:test';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';

const directory = mkdtempSync(join(tmpdir(), 'spoonbill-connection-lifecycle-'));
const moduleFile = join(directory, 'connection.mjs');
writeFileSync(moduleFile, readFileSync(new URL('../modules/spoonbill/src/main/es6/connection.js', import.meta.url)));
const {Connection} = await import(pathToFileURL(moduleFile));
after(() => rmSync(directory, {recursive: true, force: true}));

async function environment(check) {
  const keys = ['window', 'document', 'WebSocket', 'XMLHttpRequest', 'setTimeout', 'clearTimeout'];
  const previous = new Map(keys.map(key => [key, {present: Object.hasOwn(globalThis, key), value: globalThis[key]}]));
  const sockets = [], requests = [], connections = [], timers = new Map();
  let serial = 0;
  class Socket extends EventTarget {
    static CONNECTING = 0; static OPEN = 1; static CLOSING = 2; static CLOSED = 3;
    constructor(url, protocols) {
      super(); this.url = url; this.protocols = protocols; this.protocol = protocols?.[0] ?? '';
      this.readyState = Socket.CONNECTING; this.sent = []; sockets.push(this);
    }
    open() { this.readyState = Socket.OPEN; this.dispatchEvent(new Event('open')); }
    close() {
      if (this.readyState === Socket.CLOSED) return;
      this.readyState = Socket.CLOSED; this.dispatchEvent(new Event('close'));
    }
    send(value) { this.sent.push(value); }
    message(value) { this.dispatchEvent(new MessageEvent('message', {data: value})); }
  }
  class Request extends EventTarget {
    constructor() { super(); this.headers = new Map(); requests.push(this); }
    open(method, url, async) { this.method = method; this.url = url; this.async = async; }
    setRequestHeader(name, value) { this.headers.set(name, value); }
    send(body) { this.body = body; }
    respond(status, responseText = '') {
      this.status = status; this.responseText = responseText; this.readyState = 4;
      this.dispatchEvent(new Event('readystatechange'));
    }
  }
  const document = {createDocumentFragment: () => new EventTarget(), visibilityState: 'visible'};
  const window = new EventTarget();
  Object.assign(window, {document, WebSocket: Socket});
  Object.assign(globalThis, {window, document, WebSocket: Socket, XMLHttpRequest: Request,
    setTimeout: (callback, delay) => { const id = ++serial; timers.set(id, {callback, delay}); return id; },
    clearTimeout: id => timers.delete(id)});
  const flush = async () => { for (let i = 0; i < 12; i++) await Promise.resolve(); };
  const nextTimer = () => {
    assert.ok(timers.size, 'Expected a pending transport attempt');
    const [id, timer] = timers.entries().next().value;
    timers.delete(id); timer.callback(); return timer.delay;
  };
  const create = (options = {}) => {
    const connection = new Connection('session', '/app/',
      {host: 'example.test', protocol: 'http:', pathname: '/app/', search: ''}, options);
    connections.push(connection);
    return connection;
  };
  try { await check({create, sockets, requests, timers, nextTimer, flush}); }
  finally {
    timers.clear();
    for (const connection of connections) await connection.disconnect(false);
    await flush(); timers.clear();
    for (const [key, saved] of previous) {
      if (saved.present) globalThis[key] = saved.value; else delete globalThis[key];
    }
  }
}

test('legacy transport selection, long-poll renewal and publish use observable requests', () => environment(async env => {
  const connection = env.create({ws: false});
  const events = [];
  for (const name of ['open', 'ready', 'message'])
    connection.dispatcher.addEventListener(name, event => events.push(name === 'message' ? event.data : name));
  connection.connect();
  assert.equal(env.nextTimer(), 200);
  assert.equal(env.sockets.length, 0);
  assert.equal(env.requests[0].method, 'GET');
  assert.equal(env.requests[0].url, 'http://example.test/app/bridge/long-polling/session/subscribe');
  assert.deepEqual(events, ['open']);
  env.requests[0].respond(503);
  assert.equal(env.requests.length, 2);
  assert.deepEqual(events, ['open']);
  env.requests[1].respond(200, 'frame');
  assert.deepEqual(events, ['open', 'ready', 'frame']);
  assert.equal(env.requests.length, 3);
  connection.send('callback');
  const published = env.requests[3];
  assert.equal(published.method, 'POST');
  assert.equal(published.body, 'callback');
  assert.equal(published.headers.get('Content-Type'), 'application/json');
}));

test('guarded connections never fall back to long polling', () => environment(async env => {
  const connection = env.create({ws: false, auth: true});
  let errors = 0;
  connection.dispatcher.addEventListener('error', () => errors++);
  connection.connect(); env.nextTimer();
  assert.equal(errors, 1);
  assert.equal(env.sockets.length, 0);
  assert.equal(env.requests.length, 0);
}));

test('websocket reopening emits a fresh open and ready lifecycle without private state reads', () => environment(async env => {
  const connection = env.create();
  const events = [];
  for (const name of ['open', 'ready', 'close', 'message'])
    connection.dispatcher.addEventListener(name, event => events.push(name === 'message' ? event.data : name));
  connection.connect(); env.nextTimer();
  assert.equal(env.sockets[0].url, 'ws://example.test/app/bridge/web-socket/session');
  assert.deepEqual(env.sockets[0].protocols, ['json']);
  env.sockets[0].open(); env.sockets[0].open(); env.sockets[0].message('first');
  await env.flush();
  env.sockets[0].close(); await env.flush();
  assert.equal(env.nextTimer(), 200);
  env.sockets[1].open();
  assert.deepEqual(events, ['open', 'ready', 'first', 'close', 'open', 'ready']);
}));

test('guarded reconnect backoff is capped and resets on transport open', () => environment(async env => {
  const connection = env.create({auth: true});
  let ready = 0;
  connection.dispatcher.addEventListener('ready', () => ready++);
  connection.connect();
  const delays = [];
  for (let i = 0; i < 8; i++) {
    delays.push(env.nextTimer());
    env.sockets.at(-1).close(); await env.flush();
  }
  assert.deepEqual(delays, [200, 400, 800, 1600, 3200, 5000, 5000, 5000]);
  env.nextTimer(); env.sockets.at(-1).open();
  assert.equal(ready, 0);
  connection.applicationReady();
  assert.equal(ready, 1);
  env.sockets.at(-1).close(); await env.flush();
  assert.equal(env.nextTimer(), 200);
}));
