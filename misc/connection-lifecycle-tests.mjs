import assert from 'node:assert/strict';
import {after, test} from 'node:test';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {pathToFileURL} from 'node:url';

const directory = mkdtempSync(join(tmpdir(), 'spoonbill-connection-lifecycle-'));
const moduleFile = join(directory, 'connection.mjs');
writeFileSync(join(directory, 'package.json'), '{"type":"module"}');
writeFileSync(join(directory, 'connection-machine.js'), readFileSync(new URL('../modules/spoonbill/src/main/es6/connection-machine.js', import.meta.url)));
writeFileSync(moduleFile, readFileSync(new URL('../modules/spoonbill/src/main/es6/connection.js', import.meta.url)));
const {Connection, ConnectionType} = await import(pathToFileURL(moduleFile));
after(() => rmSync(directory, {recursive: true, force: true}));

async function environment(check, {session = new Map()} = {}) {
  const keys = ['window', 'document', 'WebSocket', 'XMLHttpRequest', 'FileReader', 'setTimeout', 'clearTimeout'];
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
    getResponseHeader(name) { return this.responseHeaders?.[name.toLowerCase()] ?? null; }
    // As the XHR standard says: aborting a sent request moves it to DONE with
    // status 0 and fires readystatechange; aborting a done request fires nothing.
    abort() {
      this.aborted = true;
      if (this.readyState === 4) return;
      this.status = 0; this.responseText = ''; this.readyState = 4;
      this.dispatchEvent(new Event('readystatechange'));
    }
    respond(status, responseText = '', responseHeaders = {}) {
      this.status = status; this.responseText = responseText; this.responseHeaders = responseHeaders; this.readyState = 4;
      this.dispatchEvent(new Event('readystatechange'));
    }
    /** A frame of a long-polling view: its number and the DOM revision after it. */
    frame(number, dom, body) { this.respond(200, body, {'spoonbill-frame': String(number), 'spoonbill-dom': String(dom)}); }
  }
  const document = {createDocumentFragment: () => new EventTarget(), visibilityState: 'visible'};
  const window = new EventTarget();
  const reloads = {count: 0};
  // A tab's sessionStorage; `null` means a browser without one.
  const sessionStorage = session === null ? undefined : {
    getItem: key => session.has(key) ? session.get(key) : null,
    setItem: (key, value) => { session.set(key, String(value)); },
    removeItem: key => { session.delete(key); }
  };
  Object.assign(window, {document, WebSocket: Socket, sessionStorage, location: {reload: () => { reloads.count++; }}});
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
  const polls = () => requests.filter(request => request.method === 'GET');
  try { await check({create, sockets, requests, polls, timers, nextTimer, flush, reloads, session}); }
  finally {
    timers.clear();
    for (const connection of connections) await connection.disconnect(false);
    await flush(); timers.clear();
    for (const [key, saved] of previous) {
      if (saved.present) globalThis[key] = saved.value; else delete globalThis[key];
    }
  }
}

const PAGE = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const query = url => Object.fromEntries(new URL(url).searchParams);
/** The pending timer with this delay, run. */
const runTimer = (env, delay) => {
  const entry = [...env.timers.entries()].find(([, timer]) => timer.delay === delay);
  assert.ok(entry, `Expected a timer of ${delay} ms, found ${[...env.timers.values()].map(t => t.delay)}`);
  env.timers.delete(entry[0]); entry[1].callback();
};

test('legacy transport selection, long-poll renewal and publish use observable requests', () => environment(async env => {
  const connection = env.create({ws: false});
  const events = [];
  for (const name of ['open', 'ready', 'message'])
    connection.dispatcher.addEventListener(name, event => events.push(name === 'message' ? event.data : name));
  connection.connect();
  assert.equal(env.nextTimer(), 200);
  assert.equal(env.sockets.length, 0);
  assert.equal(env.requests[0].method, 'GET');
  // Every request names its page and the last frame that it applied.
  const first = new URL(env.requests[0].url);
  assert.equal(first.origin + first.pathname, 'http://example.test/app/bridge/long-polling/session/subscribe');
  assert.match(query(env.requests[0].url).page, PAGE);
  assert.equal(query(env.requests[0].url).seen, '0');
  assert.deepEqual(events, ['open']);
  env.requests[0].frame(1, 0, '["frame"]');
  assert.deepEqual(events, ['open', 'ready', '["frame"]']);
  assert.equal(env.polls().length, 2);
  assert.equal(query(env.polls()[1].url).seen, '1');
  assert.equal(query(env.polls()[1].url).page, query(env.requests[0].url).page);
  connection.send('callback');
  const published = env.requests.at(-1);
  assert.equal(published.method, 'POST');
  assert.equal(published.body, 'callback');
  assert.deepEqual(query(published.url), {page: query(env.requests[0].url).page, seen: '1'});
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

test('permanent disconnect cancels a legacy connection attempt that has not started', () => environment(async env => {
  const connection = env.create();
  connection.connect();
  await connection.disconnect(false);
  while (env.timers.size) env.nextTimer();
  assert.equal(env.sockets.length, 0);
  assert.equal(env.requests.length, 0);
}));

test('permanent disconnect invalidates every previously scheduled connection attempt', () => environment(async env => {
  const connection = env.create();
  connection.connect();
  connection.connect();
  await connection.disconnect(false);
  while (env.timers.size) env.nextTimer();
  assert.equal(env.sockets.length, 0);
  assert.equal(env.requests.length, 0);
}));

test('an explicit connect after permanent disconnect still starts a new attempt', () => environment(async env => {
  const connection = env.create();
  connection.connect();
  await connection.disconnect(false);
  connection.connect(); env.nextTimer();
  assert.equal(env.sockets.length, 1);
}));

test('document departure invalidates old attempts and restoration schedules a fresh one', () => environment(async env => {
  const connection = env.create({auth: true, replacement: true});
  connection.connect(); connection.connect();
  window.dispatchEvent(new Event('pagehide'));
  while (env.timers.size) env.nextTimer();
  assert.equal(env.sockets.length, 0);
  const restored = new Event('pageshow');
  Object.defineProperty(restored, 'persisted', {value: true});
  window.dispatchEvent(restored);
  env.nextTimer();
  assert.equal(env.sockets.length, 1);
}));

test('an old socket decoder cannot let later frames overtake a new socket decoder', () => environment(async env => {
  const deferredBlob = () => {
    let complete;
    const pending = new Promise(resolve => { complete = resolve; });
    return {blob: new class extends Blob { text() { return pending; } }(), complete};
  };
  const old = deferredBlob(), first = deferredBlob();
  const connection = env.create();
  const received = [];
  connection.dispatcher.addEventListener('message', event => received.push(event.data));
  try {
    connection.connect(); env.nextTimer(); env.sockets[0].open();
    env.sockets[0].message(old.blob);
    await env.flush();
    // A second socket while the decoder of the first is pending. No public
    // path makes one any more: connect() leaves an open socket alone, and a
    // close waits for the decoder before it reconnects. The guard stays.
    connection._connectUsingConnectionType(ConnectionType.WEB_SOCKET); env.sockets[1].open();
    env.sockets[1].message(first.blob);
    await env.flush();
    old.complete('stale');
    await env.flush();
    env.sockets[0].message('stale-late');
    await env.flush();
    env.sockets[1].message('second');
    await env.flush();
    assert.deepEqual(received, []);
    first.complete('first');
    await env.flush();
    assert.deepEqual(received, ['first', 'second']);
  } finally {
    old.complete('stale'); first.complete('first'); await env.flush();
  }
}));

test('a failed Blob decoder reports an error and releases the close lifecycle', () => environment(async env => {
  const connection = env.create();
  const events = [];
  for (const name of ['error', 'close', 'message'])
    connection.dispatcher.addEventListener(name, () => events.push(name));
  connection.connect(); env.nextTimer(); env.sockets[0].open();
  env.sockets[0].message(new class extends Blob {
    text() { return Promise.reject(new Error('invalid encoded frame')); }
  }());
  await env.flush();
  assert.deepEqual(events, ['error', 'close']);
  assert.equal(env.sockets[0].readyState, WebSocket.CLOSED);
  assert.equal(env.timers.size, 1, 'decoder failure must leave the normal reconnect path available');
}));

test('legacy FileReader decoding keeps frames ordered until reading completes', () => environment(async env => {
  const readers = [];
  globalThis.FileReader = class {
    constructor() { readers.push(this); }
    readAsText() {}
    complete(text) { this.result = text; this.onload(); }
  };
  const connection = env.create();
  const messages = [];
  connection.dispatcher.addEventListener('message', event => messages.push(event.data));
  connection.connect(); env.nextTimer(); env.sockets[0].open();
  const blob = new Blob();
  Object.defineProperty(blob, 'text', {value: undefined});
  env.sockets[0].message(blob);
  env.sockets[0].message('second');
  await env.flush();
  assert.deepEqual(messages, []);
  assert.equal(readers.length, 1);
  readers[0].complete('first');
  await env.flush();
  assert.deepEqual(messages, ['first', 'second']);
}));

test('a superseded long-poll response cannot publish or renew its old subscription', () => environment(async env => {
  const connection = env.create({ws: false});
  const received = [];
  connection.dispatcher.addEventListener('message', event => received.push(event.data));
  connection.connect(); env.nextTimer();
  const old = env.requests[0];
  // Long polling opens at once, and an open transport resets the back-off.
  connection.connect(); runTimer(env, 200);
  const current = env.requests[1];
  old.frame(1, 0, '["stale"]');
  assert.deepEqual(received, []);
  assert.equal(env.requests.length, 2);
  current.frame(1, 0, '["current"]');
  assert.deepEqual(received, ['["current"]']);
  assert.equal(env.requests.length, 3);
}));

test('a second long-polling transport of the document keeps its cursor, and the first one counts nothing', () => environment(async env => {
  const {connection, events} = await polling(env);
  env.polls()[0].frame(1, 0, '["one"]');
  env.polls()[1].frame(2, 0, '["two"]');
  const first = env.polls()[2];
  assert.equal(query(first.url).seen, '2');
  // A page script reconnects, for example on the online event.
  connection.connect();
  for (const [id, timer] of [...env.timers.entries()].filter(([, entry]) => entry.delay <= 200)) {
    env.timers.delete(id); timer.callback();
  }
  const second = env.polls().at(-1);
  assert.notEqual(second, first);
  // The cursor and the page id belong to the document, not to the transport.
  assert.equal(query(second.url).seen, '2');
  assert.equal(query(second.url).page, query(first.url).page);
  // The deadline of the first transport is cleared, and a late run of it counts nothing.
  const late = [...env.timers.values()].filter(entry => entry.delay === 1500);
  assert.equal(late.length, 1, 'only the deadline of the current poll is set');
  first.respond(0);
  assert.equal(connection.linkUp, true);
  assert.equal(events.includes('interrupt'), false);
  second.frame(3, 0, '["three"]');
  assert.deepEqual(events.filter(name => name.startsWith('[')), ['["one"]', '["two"]', '["three"]']);
  assert.equal(env.reloads.count, 0);
}));

test('a websocket superseded by long polling cannot publish late frames', () => environment(async env => {
  const connection = env.create();
  const received = [];
  connection.dispatcher.addEventListener('message', event => received.push(event.data));
  connection.connect(); env.nextTimer();
  const old = env.sockets[0];
  old.close(); await env.flush();
  env.nextTimer();
  assert.equal(env.requests.length, 1);
  old.message('stale'); await env.flush();
  // A reload frame of a socket that is not current changes nothing either: the
  // server answers a late WebSocket of a session whose view a poll owns so.
  old.message('[1]'); await env.flush();
  assert.deepEqual(received, []);
  assert.equal(env.reloads.count, 0);
  env.requests[0].frame(1, 0, '["current"]');
  assert.deepEqual(received, ['["current"]']);
}));

// Long polling that resumes: the page keeps its bridge after a failed poll,
// and every poll says which frame the page has applied.
async function polling(env, options = {}) {
  const connection = env.create({ws: false, lpw: 1000, ...options});
  const events = [];
  for (const name of ['open', 'ready', 'close', 'error', 'interrupt', 'resume', 'message'])
    connection.dispatcher.addEventListener(name, event => events.push(name === 'message' ? event.data : name));
  connection.connect(); env.nextTimer();
  return {connection, events};
}

test('a failed poll keeps the bridge, and the next poll carries the same cursor after the back-off', () => environment(async env => {
  const {events} = await polling(env);
  env.polls()[0].frame(1, 0, '["one"]');
  env.polls()[1].respond(500);
  assert.equal(events.includes('close'), false);
  assert.equal(env.polls().length, 2, 'the next poll waits for the back-off');
  runTimer(env, 200);
  assert.equal(query(env.polls()[2].url).seen, '1');
  assert.equal(env.reloads.count, 0);
}));

test('an answer without fitting frame numbers makes the page reload and applies nothing', () => environment(async env => {
  for (const respond of [
    request => request.respond(200, '[4]'),
    request => request.frame(2, 0, '["two"]'),
    request => request.frame(1, 1, '["not a DOM frame"]'),
    request => request.frame(1, 0, '[4]'),
    request => request.respond(404),
    request => request.respond(200, '[1]')
  ]) {
    const {events} = await polling(env);
    const before = env.reloads.count;
    respond(env.polls().at(-1));
    assert.equal(env.reloads.count, before + 1);
    assert.equal(events.filter(name => name.startsWith('[')).length, 0);
    env.session.clear();
  }
}));

test('a frame that the bridge cannot apply makes the page reload, and it polls no more', () => environment(async env => {
  const {connection} = await polling(env);
  connection.dispatcher.addEventListener('message', event => { event.applied = false; });
  const polls = env.polls().length;
  env.polls()[0].frame(1, 1, '[4]');
  assert.equal(env.reloads.count, 1);
  assert.equal(env.polls().length, polls, 'no poll for the next frame');
}));

test('a poll that is not answered by its deadline fails, and its late answer changes nothing', () => environment(async env => {
  const {events} = await polling(env, {lpw: 1000});
  const hanging = env.polls()[0];
  runTimer(env, 1500);
  assert.equal(hanging.aborted, true);
  runTimer(env, 200);
  assert.equal(query(env.polls()[1].url).seen, '0');
  hanging.respond(200, '["late"]', {'spoonbill-frame': '1', 'spoonbill-dom': '0'});
  assert.equal(events.filter(name => name.startsWith('[')).length, 0);
}));

test('503 again and again retries with the back-off, and the notice shows at the second one', () => environment(async env => {
  const {events} = await polling(env);
  for (const [delay, notices] of [[200, 0], [400, 1], [800, 1]]) {
    env.polls().at(-1).respond(503);
    assert.equal(events.filter(name => name === 'interrupt').length, notices, `after the ${delay} ms back-off`);
    runTimer(env, delay);
  }
  assert.equal(env.polls().length, 4);
  assert.equal(env.reloads.count, 0);
}));

test('a frame that arrives while the notice shows is applied, and the notice hides', () => environment(async env => {
  const {events} = await polling(env);
  env.polls().at(-1).respond(500);
  runTimer(env, 200);
  env.polls().at(-1).respond(0);
  assert.equal(events.filter(name => name === 'interrupt').length, 1);
  runTimer(env, 400);
  // The link is back: the next poll gets the first frame.
  env.polls().at(-1).frame(1, 0, '["one"]');
  assert.deepEqual(events.filter(name => name.startsWith('[')), ['["one"]']);
  assert.equal(events.filter(name => name === 'resume').length, 1);
  assert.equal(query(env.polls().at(-1).url).seen, '1');
  assert.equal(env.reloads.count, 0);
}));

test('the notice shows at the second failure in a row and hides at the next answer, a 204 too', () => environment(async env => {
  const {events} = await polling(env);
  env.polls().at(-1).respond(500);
  assert.equal(events.includes('interrupt'), false);
  runTimer(env, 200);
  env.polls().at(-1).respond(0);
  assert.equal(events.filter(name => name === 'interrupt').length, 1);
  runTimer(env, 400);
  env.polls().at(-1).respond(204);
  assert.equal(events.filter(name => name === 'resume').length, 1);
  assert.equal(events.includes('close'), false);
  assert.equal(query(env.polls().at(-1).url).seen, '0');
}));

test('an unguarded page reloads at once, and a second reload in the window waits for its end', () => environment(async env => {
  await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1);
  // The reloaded page of the same tab is told to reload again at once.
  const {events} = await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1, 'the second reload waits');
  assert.equal(events.includes('interrupt'), true);
  const [id, timer] = [...env.timers.entries()].find(([, entry]) => entry.delay > 25000 && entry.delay <= 30000);
  env.timers.delete(id); timer.callback();
  assert.equal(env.reloads.count, 2);
}));

test('while a reload waits, the page sends and polls nothing, and a second reload schedules no other', () => environment(async env => {
  await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1);
  const {connection, events} = await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1, 'the second reload waits');
  assert.equal(events.filter(name => name === 'interrupt').length, 1);
  const sent = env.requests.length;
  // The user clicks while the page waits for its reload.
  connection.send('["click"]');
  assert.equal(env.requests.length, sent, 'a request left while the reload waits');
  // The server says reload once more, on another path: still one reload.
  connection.reloadPage();
  const waiting = [...env.timers.values()].filter(timer => timer.delay > 25000 && timer.delay <= 30000);
  assert.equal(waiting.length, 1, 'a second reload was scheduled');
  assert.equal(events.filter(name => name === 'interrupt').length, 1);
}));

test('a page without sessionStorage reloads at once', () => environment(async env => {
  await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 2);
}, {session: null}));

test('a page that fell back and is told to reload starts with long polling after the reload', () => environment(async env => {
  const connection = env.create({lpw: 1000});
  connection.connect(); env.nextTimer();
  // The WebSocket closes before it opens: the page falls back to long polling.
  env.sockets[0].close(); await env.flush();
  runTimer(env, 400);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1);
  const reloaded = env.create({lpw: 1000});
  reloaded.connect(); env.nextTimer();
  assert.equal(env.sockets.length, 1, 'no WebSocket after the reload');
  assert.equal(env.polls().length, 2);
}));

test('a page without a WebSocket that reloads at its first poll notes nothing', () => environment(async env => {
  const {connection} = await polling(env);
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1);
  assert.equal([...env.session.keys()].some(key => key.startsWith('spoonbill:transport')), false);
}));

test('a noted page that is told to reload at its first poll ends the note, and the next document opens a WebSocket', () => environment(async env => {
  // The note of a fallback.
  const first = env.create({lpw: 1000});
  first.connect(); env.nextTimer();
  env.sockets[0].close(); await env.flush();
  runTimer(env, 400);
  env.polls().at(-1).respond(200, '[1]');
  // The next document starts with long polling, and is told to reload at once: long polling is what fails here.
  const noted = env.create({lpw: 1000});
  noted.connect(); env.nextTimer();
  assert.equal(env.sockets.length, 1, 'the noted document starts with long polling');
  env.polls().at(-1).respond(200, '[1]');
  for (const [id, timer] of [...env.timers.entries()].filter(([, entry]) => entry.delay > 25000)) { env.timers.delete(id); timer.callback(); }
  const next = env.create({lpw: 1000});
  next.connect(); env.nextTimer();
  assert.equal(env.sockets.length, 2, 'the document after it tries a WebSocket again');
}));

test('two applications on one origin keep their notes apart', () => environment(async env => {
  const connection = env.create({lpw: 1000});
  connection.connect(); env.nextTimer();
  env.sockets[0].close(); await env.flush();
  runTimer(env, 400);
  env.polls().at(-1).respond(200, '[1]');
  // The application at /app/ noted long polling; a document of the one at /other/ did not.
  const other = new Connection('session', '/other/', {host: 'example.test', protocol: 'http:', pathname: '/other/', search: ''}, {lpw: 1000});
  other.connect(); env.nextTimer();
  assert.equal(env.sockets.length, 2, 'the other application tries a WebSocket');
  await other.disconnect(false);
}));

test('a page whose fallback works notes nothing', () => environment(async env => {
  const connection = env.create({lpw: 1000});
  connection.connect(); env.nextTimer();
  env.sockets[0].close(); await env.flush();
  runTimer(env, 400);
  env.polls().at(-1).frame(1, 0, '["one"]');
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1);
  const reloaded = env.create({lpw: 1000});
  reloaded.connect(); env.nextTimer();
  assert.equal(env.sockets.length, 2, 'the reloaded page tries a WebSocket first');
}));

// One connection at a time (survey entry WEB-1).
test('a connect() while an attempt is pending starts no second attempt', () => environment(async env => {
  const connection = env.create();
  connection.connect();
  connection.connect();
  assert.equal(env.timers.size, 1);
  assert.equal(env.nextTimer(), 200, 'the absorbed call does not advance the backoff');
  assert.equal(env.timers.size, 0);
  assert.equal(env.sockets.length, 1);
}));

test('a connect() while a socket is open or connecting starts nothing', () => environment(async env => {
  // The review of PR 39 at f55be0f2, finding 3: a second socket got a new view,
  // which announced its listeners to the old bridge, and a click went out twice.
  const connection = env.create();
  const events = [];
  for (const name of ['open', 'close', 'error'])
    connection.dispatcher.addEventListener(name, () => events.push(name));
  connection.connect(); env.nextTimer();
  // Connecting: the handshake is not closed, and nothing falls back to long polling.
  connection.connect();
  assert.equal(env.timers.size, 0);
  env.sockets[0].open();
  connection.connect();
  assert.equal(env.timers.size, 0);
  assert.equal(env.sockets.length, 1);
  assert.equal(env.requests.length, 0);
  assert.equal(env.sockets[0].readyState, WebSocket.OPEN);
  await env.flush();
  assert.deepEqual(events, ['open']);
}));

test('a new transport aborts the long-poll subscription it replaces', () => environment(async env => {
  const connection = env.create({ws: false, lpw: 1000});
  const events = [];
  for (const name of ['close', 'error'])
    connection.dispatcher.addEventListener(name, () => events.push(name));
  connection.connect(); env.nextTimer();
  const old = env.polls()[0];
  connection.connect(); runTimer(env, 200);
  assert.equal(old.aborted, true);
  assert.equal(env.polls().length, 2, 'the aborted poll renews nothing');
  assert.notEqual(env.polls()[1].aborted, true);
  assert.deepEqual(events, []);
}));

// disconnect() and long polling that resumes (survey entry WEB-3): the polls
// stop, the page keeps its bridge and its view, and connect() goes on with them.
test('disconnect() stops long polling and keeps the bridge, and connect() goes on with the same page and cursor', () => environment(async env => {
  const {connection, events} = await polling(env);
  env.polls()[0].frame(1, 0, '["one"]');
  const outstanding = env.polls()[1];
  await connection.disconnect(false);
  assert.equal(outstanding.aborted, true);
  assert.equal(events.includes('close'), false, 'no close: the bridge stays');
  assert.equal(env.polls().length, 2, 'no poll goes out');
  assert.equal(connection.send('late'), false, 'nothing is sent');
  assert.equal(env.requests.filter(request => request.method === 'POST').length, 0);
  assert.deepEqual([...env.timers.values()].map(timer => timer.delay), [], 'no deadline, back-off or reconnect is left');
  connection.connect(); runTimer(env, 200);
  const next = env.polls().at(-1);
  assert.equal(query(next.url).seen, '1');
  assert.equal(query(next.url).page, query(outstanding.url).page);
  assert.deepEqual(events.filter(name => name === 'open'), ['open'], 'no second open: the same bridge goes on');
  next.frame(2, 0, '["two"]');
  assert.equal(events.at(-1), '["two"]');
  assert.equal(env.reloads.count, 0);
}));

test('disconnect(true) on long polling polls again at once with the same cursor, and keeps the bridge', () => environment(async env => {
  const {connection, events} = await polling(env);
  env.polls()[0].frame(1, 0, '["one"]');
  const outstanding = env.polls()[1];
  await connection.disconnect(true);
  assert.equal(outstanding.aborted, true);
  runTimer(env, 200);
  const next = env.polls().at(-1);
  assert.notEqual(next, outstanding);
  assert.equal(query(next.url).seen, '1');
  assert.equal(events.includes('close'), false);
  assert.equal(env.sockets.length, 0, 'the transport stays long polling');
}));

test('a disconnect() during the back-off of a failed poll sends no poll when the back-off ends', () => environment(async env => {
  const {connection} = await polling(env);
  env.polls()[0].respond(500);
  assert.ok([...env.timers.values()].some(timer => timer.delay === 200), 'the poll waits out its back-off');
  await connection.disconnect(false);
  assert.deepEqual([...env.timers.values()].map(timer => timer.delay), [], 'the back-off is cleared');
  assert.equal(env.polls().length, 1);
}));

test('a connect() after the view has ended makes the page reload once', () => environment(async env => {
  const {connection} = await polling(env);
  env.polls()[0].frame(1, 0, '["one"]');
  await connection.disconnect(false);
  connection.connect(); runTimer(env, 200);
  // The view ended while the page did not poll: the server sends the reload frame.
  env.polls().at(-1).respond(200, '[1]');
  assert.equal(env.reloads.count, 1);
}));

test('a connect() after Back during a pause reloads the page at its new URL, and does not resume', () => environment(async env => {
  // The review of PR 39 at f55be0f2, finding 2: the server never heard of the
  // Back, so the resumed view would run the handlers of the old route.
  const {connection} = await polling(env);
  env.polls()[0].frame(1, 0, '["one"]');
  connection._location.pathname = '/app/orders/1';
  await connection.disconnect(false);
  connection._location.pathname = '/app/orders/0';
  const polls = env.polls().length;
  connection.connect(); runTimer(env, 200);
  assert.equal(env.reloads.count, 1);
  assert.equal(env.polls().length, polls, 'no poll resumes the old route');
}));

test('a pause and a connect() at the same URL resume, and the notice that showed hides at the next answer', () => environment(async env => {
  // The review of PR 39 at f55be0f2: installing the transport cleared the
  // failures, so the answer never hid the notice.
  const {connection, events} = await polling(env);
  env.polls()[0].respond(500);
  runTimer(env, 200);
  env.polls()[1].respond(500);
  assert.deepEqual(events.filter(name => name === 'interrupt'), ['interrupt']);
  await connection.disconnect(false);
  connection.connect(); runTimer(env, 200);
  assert.equal(env.reloads.count, 0);
  env.polls().at(-1).respond(204);
  assert.deepEqual(events.filter(name => name === 'interrupt' || name === 'resume'), ['interrupt', 'resume']);
  assert.equal(connection.linkUp, true);
}));

test('re-polls by disconnect(true) on a sound link do not grow the back-off', () => environment(async env => {
  // The review of PR 39 at f55be0f2: 200, 400, 800 ms and on, for re-polls that were answered.
  const {connection} = await polling(env);
  for (let round = 0; round < 6; round++) {
    env.polls().at(-1).respond(204);
    await connection.disconnect(true);
    runTimer(env, 200);
  }
  assert.equal(env.reloads.count, 0);
}));
