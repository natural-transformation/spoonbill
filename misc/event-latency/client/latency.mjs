// End-to-end click latency against a running counter app.
// Usage: node latency.mjs <baseUrl> <users> <clicksPerUser> <warmupClicks> [thinkMs]
//
// Each simulated user loads the page over HTTP (cookies + session id), opens
// the Spoonbill WebSocket, waits until the view is ready, then repeatedly
// clicks the counter button. A click's latency is the time from sending the
// DOM event until the DOM patch carrying the new count arrives. The next
// click waits for the event-counter update, exactly like the real browser.
// A closed socket is not fatal: the user reconnects to the same view, as the
// browser would, and the reconnect is counted. The count the server shows after
// a reconnect is adopted, so a discarded presentation is also tolerated.
//
// Environment, set by run.sh for the authenticated snapshot modes:
//   BENCH_LOGIN=1      sign in first. POST /bench-login provisions a session and
//                      answers with the binding cookie and a completion id; the
//                      framework's POST /auth/complete turns the id into the
//                      session cookie, as it does for a browser.
//   BENCH_PHASES=1     open every user before anyone clicks, and record
//                      /bench-stats and the proxy's round-trip count
//                      (BENCH_COUNT_FILE) at that point, so startup is reported
//                      apart from steady-state saves.
//   BENCH_RECONNECT=1  after warmup every user drops its socket and reconnects
//                      to the same view, which restores the saved projection.
import { readFileSync } from 'node:fs';
const [base, usersArg = '1', clicksArg = '300', warmupArg = '50', thinkArg = '0'] = process.argv.slice(2);
const users = Number(usersArg), clicks = Number(clicksArg), warmup = Number(warmupArg), think = Number(thinkArg);
const login = process.env.BENCH_LOGIN === '1';
const phased = process.env.BENCH_PHASES === '1';
const reconnectAfterWarmup = process.env.BENCH_RECONNECT === '1';
const countFile = process.env.BENCH_COUNT_FILE;
const BUTTON = '1_2_1';
const decoder = new TextDecoder();
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const totals = { reconnects: 0, reconnectMs: [], openMs: [], restoreMs: [], restoreAttempts: 0 };

// The cookie jar as one Cookie header value. A Set-Cookie with an empty value
// clears that cookie, which is how the server retires a stale session cookie.
function withCookies(cookie, response) {
  const jar = new Map(cookie.split('; ').filter(Boolean).map(pair => [pair.split('=')[0], pair]));
  for (const header of response.headers.getSetCookie()) {
    const pair = header.split(';')[0];
    if (pair.endsWith('=')) jar.delete(pair.slice(0, -1)); else jar.set(pair.split('=')[0], pair);
  }
  return [...jar.values()].join('; ');
}

async function signIn() {
  const provisioned = await fetch(base + '/bench-login', { method: 'POST' });
  if (provisioned.status !== 200) throw new Error(`bench-login status ${provisioned.status}`);
  const completion = (await provisioned.text()).trim();
  const cookie = withCookies('', provisioned);
  const delivered = await fetch(base + '/auth/complete', {
    method: 'POST', headers: { Cookie: cookie, Origin: base, 'Content-Type': 'text/plain' }, body: completion });
  if (delivered.status !== 204) throw new Error(`auth/complete status ${delivered.status}`);
  return withCookies(cookie, delivered);
}

async function loadPage(cookie) {
  const response = await fetch(base + '/', { redirect: 'manual', headers: cookie ? { Cookie: cookie } : {} });
  const html = await response.text();
  if (response.status !== 200) throw new Error(`page status ${response.status}`);
  const sid = /sid:'([^']+)'/.exec(html)[1];
  // Unguarded apps are ready at open; guarded apps announce readiness (21).
  return { sid, cookie: withCookies(cookie, response), guarded: html.includes(',auth:true') };
}

// A reconnect tells the server the page's current location, as the bridge
// does; without it a view whose local presentation is gone can only be reloaded.
async function connect(page, reconnecting = false) {
  const location = reconnecting ? '?__spoonbill_location=' + encodeURIComponent('/') : '';
  const ws = new WebSocket(base.replace(/^http/, 'ws') + '/bridge/web-socket/' + page.sid + location,
    { protocols: ['json'], headers: { Cookie: page.cookie, Origin: base } });
  ws.binaryType = 'arraybuffer';
  const session = { ws, counters: new Map(), view: null, revision: null, ready: false, waiters: [], closed: false,
    lastText: '', shownCount: null };
  ws.onmessage = event => {
    const text = typeof event.data === 'string' ? event.data : decoder.decode(event.data);
    session.lastText = text;
    const shown = /count=(\d+)/.exec(text);
    if (shown) session.shownCount = Number(shown[1]);
    const frame = JSON.parse(text);
    switch (frame[0]) {
      case 0: session.counters.set(frame[1] + ':' + frame[2], frame[3]); break;
      case 17: session.counters.clear(); break;
      case 19: session.view = frame[2]; session.revision = frame[3]; break;
      case 20: session.revision = frame[3]; break;
      case 21: session.ready = true; break;
      case 1: session.closed = true; break;
    }
    for (const waiter of session.waiters.splice(0)) waiter(text, frame);
  };
  ws.onclose = () => { session.closed = true; for (const waiter of session.waiters.splice(0)) waiter(null, null); };
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = () => reject(new Error('socket error')); });
  if (page.guarded) await until(session, () => session.ready, 10000);
  return session;
}

function until(session, predicate, timeoutMs) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('timeout')), timeoutMs);
    const check = () => {
      if (session.closed) { clearTimeout(timer); reject(new Error('closed')); return; }
      if (predicate()) { clearTimeout(timer); resolve(); return; }
      session.waiters.push(check);
    };
    check();
  });
}

async function click(session, expected) {
  const key = BUTTON + ':click';
  const counter = session.counters.get(key) ?? 0;
  const event = counter + ':' + BUTTON + ':click';
  const message = session.view
    ? JSON.stringify([7, `${session.view}:${session.revision}:0:${event}`])
    : JSON.stringify([0, event]);
  let patched = false;
  const marker = `"count=${expected}"`;
  const started = performance.now();
  let patchAt = 0;
  const done = until(session, () => {
    if (!patched && session.lastText.includes(marker)) { patched = true; patchAt = performance.now(); }
    return patched && (session.counters.get(key) ?? 0) > counter;
  }, 15000);
  session.ws.send(message);
  await done;
  return { patch: patchAt - started, ready: performance.now() - started };
}

// Sign in when asked, load the page and open the socket; the time until the
// view is ready covers the session open and, with snapshots, the initial
// publication of the empty view's projection.
async function open() {
  const started = performance.now();
  const page = await loadPage(login ? await signIn() : '');
  const session = await connect(page);
  totals.openMs.push(performance.now() - started);
  return { page, session };
}

// Close the socket, wait for the close to complete and connect again to the
// same view. The server keeps one attachment per view and refuses a handshake
// that arrives before the old socket's cleanup, as it would for a browser
// reload, so the handshake is retried; only the accepted attempt is timed.
async function reopen(page, session) {
  session.ws.close();
  await new Promise(resolve => {
    if (session.closed) resolve(); else session.ws.addEventListener('close', resolve, { once: true });
  });
  await sleep(100); // most of the server's cleanup of the old attachment
  for (let attempt = 1; ; attempt++) {
    const started = performance.now();
    try {
      const next = await connect(page, true);
      totals.restoreMs.push(performance.now() - started);
      totals.restoreAttempts += attempt;
      return next;
    } catch (error) {
      if (error.message !== 'socket error' || attempt >= 200) throw error;
      await sleep(25);
    }
  }
}

async function run(opened, results) {
  let { page, session } = opened;
  let count = 0;
  for (let i = 1; i <= warmup + clicks; i++) {
    if (reconnectAfterWarmup && i === warmup + 1) {
      // Restoration: drop the socket and reconnect to the same view, which
      // loads the saved projection; the count it shows is adopted.
      session = await reopen(page, session);
      if (session.shownCount !== null) count = session.shownCount;
    }
    try {
      const sample = await click(session, count + 1);
      count += 1;
      if (i > warmup) results.push(sample);
    } catch (error) {
      if (error.message !== 'closed') throw error;
      // The server closed the socket, as it does for a denied or rejected check.
      // Reconnect to the same view and adopt the count it shows now.
      const started = performance.now();
      session = await connect(page);
      totals.reconnects += 1;
      totals.reconnectMs.push(performance.now() - started);
      if (session.shownCount !== null) count = session.shownCount;
      i -= 1;
    }
    if (think > 0) await sleep(think);
  }
  session.ws.close();
}

// The server's counters and the proxy's round-trip count at a phase boundary.
async function phaseStats() {
  await sleep(120); // the proxy writes its counter file every 50 ms
  const stats = await (await fetch(base + '/bench-stats')).json();
  const roundTrips = countFile ? Number(readFileSync(countFile, 'utf8')) : null;
  return { stats, roundTrips, atMs: Math.round(performance.now() - started) };
}

const pct = (values, q) => values[Math.min(values.length - 1, Math.max(0, Math.ceil(q * values.length) - 1))];
const results = [];
const started = performance.now();
let afterOpen = null;
if (phased) {
  const opened = await Promise.all(Array.from({ length: users }, () => open()));
  afterOpen = await phaseStats();
  await Promise.all(opened.map(value => run(value, results)));
} else {
  await Promise.all(Array.from({ length: users }, () => open().then(value => run(value, results))));
}
const elapsed = (performance.now() - started) / 1000;
const summary = values => {
  values = [...values].sort((a, b) => a - b);
  if (values.length === 0) return null;
  return { p50: pct(values, .5), p90: pct(values, .9), p95: pct(values, .95), p99: pct(values, .99), max: values.at(-1),
    mean: values.reduce((sum, value) => sum + value, 0) / values.length };
};
const round = object => object && Object.fromEntries(Object.entries(object).map(([key, value]) => [key, Math.round(value * 100) / 100]));
console.log(JSON.stringify({ base, users, clicksPerUser: clicks, warmup, thinkMs: think, samples: results.length,
  clicksPerSecond: Math.round(results.length / elapsed), reconnects: totals.reconnects,
  reconnectMs: round(summary(totals.reconnectMs)),
  openMs: round(summary(totals.openMs)), restores: totals.restoreMs.length, restoreAttempts: totals.restoreAttempts,
  restoreMs: round(summary(totals.restoreMs)),
  patchMs: round(summary(results.map(sample => sample.patch))), readyMs: round(summary(results.map(sample => sample.ready))),
  afterOpen }));
