// From Spoonbill: nix develop .#browser --command bash scripts/test-native-browsers.sh
// Real Connection + Bridge, native dialogs, and an owned loopback WebSocket.
// The bounded peer is a transport fixture, not an application authorization test.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const {createHash} = require('node:crypto');
assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use the Nix Playwright environment');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const sourceRoot = path.resolve(__dirname, '../modules/spoonbill/src/main/es6');
const binding = '11111111-1111-4111-8111-111111111111';
const presentation = n => `22222222-2222-4222-8222-${String(n).padStart(12, '0')}`;
const initialText = 'synthetic-departure-original';
const freshText = 'synthetic-departure-fresh';
const MAX_BYTES = 65536;

function serverFrame(opcode, payload) {
  const header = Buffer.alloc(payload.length < 126 ? 2 : 4);
  header[0] = 0x80 | opcode;
  header[1] = payload.length < 126 ? payload.length : 126;
  if (payload.length >= 126) header.writeUInt16BE(payload.length, 2);
  return Buffer.concat([header, payload]);
}

// Browser Connection sends binary Blob messages. Handle split/coalesced frames,
// masking, bounded fragmentation, ping and close without adding a dependency.
class Peer {
  constructor(socket, head) {
    this.socket = socket;
    this.buffer = Buffer.alloc(0);
    this.fragments = [];
    this.fragmentBytes = 0;
    this.fragmentOpcode = null;
    this.frames = [];
    this.waiters = new Set();
    this.errors = 0;
    this.closes = 0;
    this.revalidations = 0;
    socket.on('data', data => this.read(data));
    socket.on('error', () => this.fail());
    socket.on('close', () => { this.closes++; this.rejectWaiters(); });
    if (head.length) this.read(head);
  }
  rejectWaiters() {
    for (const waiter of this.waiters) waiter.reject(new Error('Local WebSocket closed or invalid'));
    this.waiters.clear();
  }
  fail() {
    this.errors++;
    this.rejectWaiters();
    this.socket.destroy();
  }
  read(chunk) {
    if (chunk.length + this.buffer.length > MAX_BYTES) { this.fail(); return; }
    this.buffer = Buffer.concat([this.buffer, chunk]);
    while (this.buffer.length >= 2) {
      const first = this.buffer[0];
      const opcode = first & 15;
      const final = (first & 0x80) !== 0;
      const masked = (this.buffer[1] & 0x80) !== 0;
      let length = this.buffer[1] & 127;
      let offset = 2;
      if ((first & 0x70) || !masked || length === 127) { this.fail(); return; }
      if (length === 126) {
        if (this.buffer.length < 4) return;
        length = this.buffer.readUInt16BE(2); offset = 4;
      }
      if (length + offset + 4 > MAX_BYTES || (opcode >= 8 && (!final || length > 125))) {
        this.fail(); return;
      }
      if (this.buffer.length < offset + 4 + length) return;
      const mask = this.buffer.subarray(offset, offset + 4);
      const payload = Buffer.from(this.buffer.subarray(offset + 4, offset + 4 + length));
      for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4];
      this.buffer = this.buffer.subarray(offset + 4 + length);
      if (opcode === 8) { this.socket.end(serverFrame(8, payload)); return; }
      if (opcode === 9) { this.socket.write(serverFrame(10, payload)); continue; }
      if (opcode === 10) continue;
      if ((opcode === 0 && this.fragmentOpcode === null) ||
          ((opcode === 1 || opcode === 2) && this.fragmentOpcode !== null) ||
          ![0, 1, 2].includes(opcode)) { this.fail(); return; }
      if (opcode !== 0) this.fragmentOpcode = opcode;
      this.fragmentBytes += payload.length;
      if (this.fragmentBytes > MAX_BYTES) { this.fail(); return; }
      this.fragments.push(payload);
      if (!final) continue;
      let frame;
      try { frame = JSON.parse(Buffer.concat(this.fragments).toString('utf8')); }
      catch (_) { this.fail(); return; }
      this.fragments = []; this.fragmentBytes = 0; this.fragmentOpcode = null;
      if (!Array.isArray(frame) || frame.length !== 2 || ![1, 2, 8, 9, 10].includes(frame[0]) ||
          typeof frame[1] !== 'string' || frame[1].length > 512 || this.frames.length >= 128) {
        this.fail(); return;
      }
      // Retain protocol metadata only; no disclosure payload returns here.
      this.frames.push(frame);
      for (const waiter of [...this.waiters]) {
        if (waiter.matches(frame)) { this.waiters.delete(waiter); waiter.resolve(frame); }
      }
    }
  }
  waitFor(matches) {
    const existing = this.frames.find(matches);
    if (existing) return Promise.resolve(existing);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        this.waiters.delete(waiter); reject(new Error('Local protocol metadata timeout'));
      }, 10000);
      const waiter = {matches, resolve: frame => { clearTimeout(timer); resolve(frame); },
        reject: error => { clearTimeout(timer); reject(error); }};
      this.waiters.add(waiter);
      if (this.socket.destroyed) { this.waiters.delete(waiter); waiter.reject(new Error('Local socket closed')); }
    });
  }
  send(frame) {
    assert.equal(this.socket.destroyed, false, 'Owned socket must remain open');
    const payload = Buffer.from(JSON.stringify(frame));
    assert.ok(payload.length < MAX_BYTES, 'Fixture frame must be bounded');
    this.socket.write(serverFrame(1, payload));
  }
  async show(number, expected, text = freshText) {
    const id = presentation(number);
    const before = this.frames.length;
    this.send([22, binding, id, 'codes', 'mfa.recovery', 60000, [0, [text]], Date.now() + 60000]);
    await this.waitFor(frame => this.frames.indexOf(frame) >= before && frame[0] === 8 &&
      frame[1] === `${binding}:${id}:codes:${expected}`);
  }
  async fence(id, element) {
    this.send([3, id, element, 'id']);
    await this.waitFor(frame => frame[0] === 2 && frame[1] === `${id}:0:ordinary`);
  }
  revalidate(counter) {
    assert.ok(this.frames.some(frame => frame[0] === 10 && frame[1] === String(counter)),
      'Recovery requires an observed departure request');
    // Explicit test-peer approval models the server clear-before-barrier order.
    // Host applications must verify their own grant/session revalidation.
    this.revalidations++;
    this.send([23, binding, '', '']);
    this.send([25, counter]);
  }
}

async function cancelNativeDeparture(page) {
  let timer;
  let onDialog;
  const observed = new Promise((resolve, reject) => {
    timer = setTimeout(() => reject(new Error('Native beforeunload dialog was not observed')), 10000);
    onDialog = async dialog => {
      try {
        assert.equal(dialog.type(), 'beforeunload', 'Expected native departure confirmation');
        await dialog.dismiss(); resolve();
      } catch (_) { reject(new Error('Native departure confirmation could not be dismissed')); }
    };
    page.on('dialog', onDialog);
  });
  try {
    await Promise.all([observed, page.locator('#leave').click({noWaitAfter: true})]);
  } finally {
    clearTimeout(timer); page.off('dialog', onDialog);
  }
}

(async () => {
  const modules = new Map(await Promise.all(['bridge.js', 'spoonbill.js', 'connection.js', 'utils.js', 'sensitive.js']
    .map(async name => ['/' + name, await fs.readFile(path.join(sourceRoot, name), 'utf8')])));
  const sockets = new Set();
  const peers = [];
  const server = http.createServer((request, response) => {
    response.setHeader('Cache-Control', 'no-store');
    const source = modules.get(request.url);
    if (source) {
      response.writeHead(200, {'Content-Type': 'text/javascript'}); response.end(source);
    } else if (request.url === '/' || request.url === '/leave') {
      response.writeHead(200, {'Content-Type': 'text/html'});
      response.end('<!doctype html><html><head></head><body><button id="activate">Edit draft</button>' +
        '<a id="leave" href="/leave">Leave page</a><button id="fresh">Fresh disclosure</button>' +
        '<p id="ordinary">Ordinary</p><sb-secret data-sb-region="codes"></sb-secret></body></html>');
    } else { response.writeHead(404); response.end(); }
  });
  server.on('connection', socket => {
    sockets.add(socket); socket.on('close', () => sockets.delete(socket));
  });
  server.on('upgrade', (request, socket, head) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (url.pathname !== '/bridge/web-socket/native-test' ||
        url.searchParams.get('__spoonbill_location') !== '/' ||
        typeof request.headers['sec-websocket-key'] !== 'string' ||
        request.headers['sec-websocket-protocol'] !== 'json') { socket.destroy(); return; }
    const accept = createHash('sha1').update(request.headers['sec-websocket-key'] +
      '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n' +
      'Sec-WebSocket-Protocol: json\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
    peers.push(new Peer(socket, head));
  });
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  const origin = `http://127.0.0.1:${server.address().port}`;
  try {
    for (const [engine, type] of Object.entries({chromium, webkit})) {
      const env = {...process.env};
      if (engine === 'webkit' && process.platform === 'linux') {
        const vendor = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
        assert.ok(vendor, 'Nix WebKit EGL vendor required');
        env.__EGL_VENDOR_LIBRARY_FILENAMES = vendor;
        env.LIBGL_ALWAYS_SOFTWARE = 'true';
      }
      const browser = await type.launch({headless: true, env});
      try {
        const page = await browser.newPage();
        page.setDefaultTimeout(10000);
        let pageErrors = 0;
        page.on('pageerror', () => pageErrors++);
        const peerIndex = peers.length;
        await page.goto(origin + '/');
        const ordinaryId = await page.evaluate(async ({initialText, freshText}) => {
          const roots = new WeakMap();
          const attach = Element.prototype.attachShadow;
          Element.prototype.attachShadow = function(options) {
            const root = attach.call(this, options); roots.set(this, root); return root;
          };
          const [{Connection}, {Bridge}] = await Promise.all([import('/connection.js'), import('/bridge.js')]);
          const config = {auth: true, heartbeat: {interval: '0'}};
          const connection = new Connection('native-test', '/', location, config);
          const originalDocument = document;
          let bridge, firstSocket;
          let opens = 0, closes = 0, errors = 0, beforeUnload = 0, pageHide = 0;
          let socketCloses = 0, socketErrors = 0;
          connection.dispatcher.addEventListener('open', () => {
            opens++; bridge = new Bridge(config, connection);
            if (!firstSocket) {
              firstSocket = connection._webSocket;
              firstSocket.addEventListener('close', () => socketCloses++);
              firstSocket.addEventListener('error', () => socketErrors++);
            }
          });
          connection.dispatcher.addEventListener('close', () => { closes++; bridge.destroy(); });
          connection.dispatcher.addEventListener('error', () => errors++);
          const opened = new Promise((resolve, reject) => {
            const timer = setTimeout(() => reject(new Error('Connection open timeout')), 10000);
            connection.dispatcher.addEventListener('open', () => { clearTimeout(timer); resolve(); }, {once: true});
          });
          connection.connect(); await opened;
          window.addEventListener('beforeunload', event => {
            beforeUnload++; event.preventDefault(); event.returnValue = '';
          });
          window.addEventListener('pagehide', () => pageHide++);
          document.getElementById('fresh').addEventListener('click', () =>
            bridge._spoonbill.invokeCustomCallback('fresh', 'request'));
          const host = document.querySelector('sb-secret');
          // Test-only closed-root inspection returns booleans/counts, never text.
          window.__departureFixture = {
            snapshot: () => {
              const root = roots.get(host);
              return {sameDocument: document === originalDocument, sameSocket: connection._webSocket === firstSocket,
                readyState: firstSocket.readyState, opens, closes, errors, socketCloses, socketErrors,
                beforeUnload, pageHide, pending: bridge._sensitive.departurePending,
                visibleNodes: root?.childNodes.length || 0, initialVisible: root?.textContent === initialText,
                freshVisible: root?.textContent === freshText, closedRoot: host.shadowRoot === null,
                ordinaryClean: !document.documentElement.outerHTML.includes(initialText) &&
                  !document.documentElement.outerHTML.includes(freshText)};
            },
            cleanup: () => { bridge.destroy(); connection.disconnect(false); Element.prototype.attachShadow = attach; }
          };
          return document.getElementById('ordinary').vId;
        }, {initialText, freshText});
        assert.equal(peers.length, peerIndex + 1, 'Exactly one physical connection must open');
        const peer = peers[peerIndex];
        const snapshot = () => page.evaluate(() => window.__departureFixture?.snapshot() || {sameDocument: false});
        const checkLive = state => {
          assert.equal(page.url(), origin + '/', 'Canceled departure must retain the URL');
          for (const field of ['sameDocument', 'sameSocket', 'closedRoot', 'ordinaryClean'])
            assert.equal(state[field], true, `${engine}: ${field}`);
          for (const field of ['closes', 'errors', 'socketCloses', 'socketErrors', 'pageHide'])
            assert.equal(state[field], 0, `${engine}: ${field}`);
          assert.equal(state.opens, 1); assert.equal(state.readyState, 1);
          assert.equal(peers.length, peerIndex + 1, 'Reconnection must not explain recovery');
          assert.equal(peer.closes, 0); assert.equal(peer.errors, 0); assert.equal(pageErrors, 0);
        };
        peer.send([21, false]);
        await peer.show(1, 'ok', initialText);
        await page.locator('#activate').click();
        const initial = await snapshot();
        checkLive(initial); assert.equal(initial.initialVisible, true);

        await cancelNativeDeparture(page);
        await peer.waitFor(frame => frame[0] === 10 && frame[1] === '1');
        await peer.waitFor(frame => frame[0] === 9 && frame[1] === `${binding}:${presentation(1)}:codes`);
        // Give native queued close/error signals time to arrive before asserting
        // that this cancellation really retained the physical WebSocket.
        await page.waitForTimeout(1000);
        const canceled = await snapshot();
        checkLive(canceled); assert.equal(canceled.beforeUnload, 1);
        assert.equal(canceled.pending, true); assert.equal(canceled.visibleNodes, 0);
        assert.equal(peer.revalidations, 0, 'Peer must withhold recovery');
        await peer.show(2, 'failed');
        await peer.show(1, 'failed', initialText);
        peer.send([4, 3, ordinaryId, 0, 'class', 'changed', false]);
        peer.send([24, 1]);
        peer.send([25, 99]);
        await peer.show(3, 'failed');
        await page.locator('#fresh').click();
        await peer.fence('pending-fence', ordinaryId);
        assert.equal(peer.frames.filter(frame => frame[0] === 1).length, 0,
          'Fresh user callback must not reach the server before recovery');
        const pending = await snapshot();
        checkLive(pending); assert.equal(pending.pending, true); assert.equal(pending.visibleNodes, 0);

        await cancelNativeDeparture(page);
        await peer.waitFor(frame => frame[0] === 10 && frame[1] === '2');
        peer.revalidate(1);
        await peer.show(4, 'failed');
        const stale = await snapshot();
        checkLive(stale); assert.equal(stale.beforeUnload, 2);
        assert.equal(stale.pending, true); assert.equal(stale.visibleNodes, 0);
        peer.revalidate(2);
        await peer.fence('revalidated-fence', ordinaryId);
        const recovered = await snapshot();
        checkLive(recovered); assert.equal(recovered.pending, false); assert.equal(recovered.visibleNodes, 0);
        await peer.show(1, 'failed', initialText);
        await peer.show(2, 'failed');
        await page.locator('#fresh').click();
        await peer.waitFor(frame => frame[0] === 1 && frame[1] === 'fresh:request');
        assert.equal(peer.frames.filter(frame => frame[0] === 1).length, 1,
          'Only the fresh post-revalidation callback may reach the server');
        await peer.show(5, 'ok');
        const final = await snapshot();
        checkLive(final); assert.equal(final.freshVisible, true); assert.equal(final.initialVisible, false);
        assert.equal(final.visibleNodes, 1);
        console.log(JSON.stringify({engine, nativeCancellations: 2, samePhysicalSocket: true,
          oldOutputCleared: true, pendingCallbacksBlocked: true, staleBarrierBlocked: true,
          genericPatchAndHistoryBlocked: true, replayBlocked: true, freshDisclosure: true,
          peerRevalidations: peer.revalidations, pageErrors}));
        await page.evaluate(() => window.__departureFixture.cleanup());
      } finally { await browser.close(); }
    }
  } finally {
    for (const socket of sockets) socket.destroy();
    await new Promise(resolve => server.close(resolve));
  }
})().catch(error => {
  // No frames, DOM content, or synthetic disclosure text enter failure output.
  console.error(`${error.name}: ${error.message}`); process.exitCode = 1;
});
