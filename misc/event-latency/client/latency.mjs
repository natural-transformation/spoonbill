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
const [base, usersArg = '1', clicksArg = '300', warmupArg = '50', thinkArg = '0'] = process.argv.slice(2);
const users = Number(usersArg), clicks = Number(clicksArg), warmup = Number(warmupArg), think = Number(thinkArg);
const BUTTON = '1_2_1';
const decoder = new TextDecoder();
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const totals = { reconnects: 0, reconnectMs: [] };

async function loadPage() {
  const response = await fetch(base + '/', { redirect: 'manual' });
  const html = await response.text();
  if (response.status !== 200) throw new Error(`page status ${response.status}`);
  const sid = /sid:'([^']+)'/.exec(html)[1];
  const cookie = response.headers.getSetCookie().map(value => value.split(';')[0]).filter(v => !v.endsWith('=')).join('; ');
  // Unguarded apps are ready at open; guarded apps announce readiness (21).
  return { sid, cookie, guarded: html.includes(',auth:true') };
}

async function connect(page) {
  const ws = new WebSocket(base.replace(/^http/, 'ws') + '/bridge/web-socket/' + page.sid,
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

async function user(index, results) {
  const page = await loadPage();
  let session = await connect(page);
  let count = 0;
  for (let i = 1; i <= warmup + clicks; i++) {
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

const pct = (values, q) => values[Math.min(values.length - 1, Math.max(0, Math.ceil(q * values.length) - 1))];
const results = [];
const started = performance.now();
await Promise.all(Array.from({ length: users }, (_, index) => user(index, results)));
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
  patchMs: round(summary(results.map(sample => sample.patch))), readyMs: round(summary(results.map(sample => sample.ready))) }));
