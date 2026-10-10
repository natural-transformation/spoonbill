import http from 'node:http';
import {createHash} from 'node:crypto';

const invalid = code => { throw new Error(code); };
const positive = n => Number.isSafeInteger(n) && n > 0;

/** Client-to-server RFC 6455 header counter. Only this 14-byte header is retained;
 * masked payload is skipped in the incoming buffer, never decoded or copied. */
export class WebSocketWireCounter {
  #header = Buffer.alloc(14);
  #used = 0;
  #needed = 2;
  #remaining = null;
  #opcode = 0;
  #fin = false;
  #fragmented = false;
  #failed = false;
  #closed = false;
  #frames = 0;
  #data = 0;
  #continuations = 0;
  #messages = 0;
  #control = 0;
  #payloadBytes = 0;
  #wireBytes = 0;
  #limits;
  #message;
  #close;
  constructor({maxFrames = 128, maxFrameBytes = 2 * 1024 * 1024, maxWireBytes = 16 * 1024 * 1024,
    onMessage = () => {}, onClose = () => {}} = {}) {
    if (![maxFrames, maxFrameBytes, maxWireBytes].every(positive)) invalid('invalid-wire-limit');
    this.#limits = {maxFrames, maxFrameBytes, maxWireBytes}; this.#message = onMessage; this.#close = onClose;
  }
  push(bytes) {
    if (!Buffer.isBuffer(bytes) || this.#failed || this.#closed) invalid('wire-counter-unavailable');
    try {
      this.#wireBytes += bytes.length;
      if (this.#wireBytes > this.#limits.maxWireBytes) invalid('wire-byte-limit');
      let offset = 0;
      while (offset < bytes.length) {
        if (this.#closed) invalid('bytes-after-close');
        if (this.#remaining === null) {
          while (offset < bytes.length && this.#used < this.#needed) this.#header[this.#used++] = bytes[offset++];
          if (this.#used < this.#needed) break;
          if (this.#needed === 2) {
            const first = this.#header[0], length = this.#header[1] & 127;
            this.#opcode = first & 15; this.#fin = Boolean(first & 128);
            if ((first & 112) || !(this.#header[1] & 128) || ![0, 1, 2, 8, 9, 10].includes(this.#opcode)) invalid('unsupported-frame-header');
            this.#needed = 2 + (length === 126 ? 2 : length === 127 ? 8 : 0) + 4;
            continue;
          }
          const encoded = this.#header[1] & 127;
          const length = encoded === 126 ? BigInt(this.#header.readUInt16BE(2)) :
            encoded === 127 ? this.#header.readBigUInt64BE(2) : BigInt(encoded);
          if ((encoded === 126 && length < 126n) || (encoded === 127 && length < 65536n) ||
              length > BigInt(this.#limits.maxFrameBytes)) invalid('invalid-frame-length');
          if (this.#opcode >= 8 && (!this.#fin || length > 125n || (this.#opcode === 8 && length === 1n))) invalid('invalid-control-frame');
          if (this.#opcode === 0 ? !this.#fragmented : this.#opcode < 8 && this.#fragmented) invalid('invalid-fragment-sequence');
          if (++this.#frames > this.#limits.maxFrames) invalid('wire-frame-limit');
          this.#remaining = Number(length); this.#payloadBytes += Number(length);
        }
        const skipped = Math.min(this.#remaining, bytes.length - offset);
        offset += skipped; this.#remaining -= skipped;
        if (this.#remaining === 0) {
          const opcode = this.#opcode, fin = this.#fin;
          this.#remaining = null; this.#used = 0; this.#needed = 2;
          if (opcode < 8) {
            this.#data++;
            if (opcode === 0) this.#continuations++;
            this.#fragmented = !fin;
            if (fin) { this.#messages++; this.#message(); }
          } else {
            this.#control++;
            if (opcode === 8) { this.#closed = true; this.#close(); }
          }
        }
      }
    } catch (error) { this.#failed = true; throw error; }
  }
  snapshot() {
    return Object.freeze({status: this.#failed ? 'inconclusive' : 'observing',
      physicalDataFrames: this.#failed ? null : this.#data, continuationFrames: this.#failed ? null : this.#continuations,
      completedMessages: this.#failed ? null : this.#messages, controlFrames: this.#control,
      payloadBytes: this.#payloadBytes, wireBytes: this.#wireBytes, bufferedHeaderBytes: this.#used,
      allocatedParserBytes: 14, retainedPayloadBytes: 0});
  }
  finish() {
    if (this.#used || this.#remaining !== null || this.#fragmented) this.#failed = true;
    return Object.freeze({...this.snapshot(), status: this.#failed ? 'inconclusive' : 'complete'});
  }
}

const ack = Buffer.from([0x81, 3, 65, 67, 75]); // Fixed synthetic text ACK, never an echo.
const close = Buffer.from([0x88, 0]);

export async function createWebSocketCalibrationServer({timeoutMs = 10000} = {}) {
  if (!positive(timeoutMs) || timeoutMs > 60000) invalid('invalid-server-timeout');
  const sockets = new Set(); let counter = null, failure = null, upgrades = 0, stopped = false;
  const server = http.createServer((_, response) => {
    response.writeHead(200, {'Content-Type': 'text/html', 'Cache-Control': 'no-store'});
    response.end('<!doctype html><title>Synthetic WebSocket calibration</title>');
  });
  server.headersTimeout = timeoutMs; server.requestTimeout = timeoutMs;
  server.on('connection', socket => {
    sockets.add(socket);
    if (sockets.size > 8) { failure = 'socket-limit'; socket.destroy(); }
    socket.setTimeout(timeoutMs, () => { failure ??= 'socket-timeout'; socket.destroy(); });
    socket.on('error', () => { if (!stopped) failure ??= 'socket-error'; });
    socket.on('close', () => sockets.delete(socket));
  });
  server.on('clientError', (_, socket) => { failure ??= 'http-protocol-error'; socket.destroy(); });
  server.on('upgrade', (request, socket, head) => {
    // Only handshake protocol fields are read. No request URL, cookies or frame
    // payload are accessed. The caller must use a fresh synthetic browser context.
    const key = request.headers['sec-websocket-key'];
    if (++upgrades !== 1 || typeof key !== 'string' || !/^[A-Za-z0-9+/]{22}==$/.test(key) ||
        request.headers['sec-websocket-version'] !== '13') { failure = 'invalid-upgrade'; socket.destroy(); return; }
    const accept = createHash('sha1').update(key + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64');
    socket.write('HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ' + accept + '\r\n\r\n');
    counter = new WebSocketWireCounter({onMessage: () => socket.write(ack), onClose: () => socket.end(close)});
    const consume = bytes => { try { counter.push(bytes); } catch (_) { failure = 'websocket-protocol-error'; socket.destroy(); } };
    socket.on('data', consume);
    if (head.length) consume(head);
  });
  try {
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', resolve); });
  } catch (error) { for (const socket of sockets) socket.destroy(); server.close(); throw error; }
  const timer = setTimeout(() => { failure ??= 'server-timeout'; for (const socket of sockets) socket.destroy(); }, timeoutMs);
  return {
    origin: `http://127.0.0.1:${server.address().port}`,
    snapshot: () => ({failure, upgrades, wire: counter?.snapshot() ?? null}),
    async close() {
      stopped = true; clearTimeout(timer);
      const released = [...sockets].map(socket => new Promise(resolve => { socket.once('close', resolve); socket.destroy(); }));
      await Promise.all([...released, new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()))]);
      return {failure, upgrades, wire: counter?.finish() ?? null, activeSockets: sockets.size};
    },
  };
}

/** Public events/CDP metadata only. Deliberately never read payload, payloadData,
 * URL, headers or cookies. Counts remain notification quantities until compared. */
export async function observeWebSocketEvents(page, engine, work) {
  if (!['chromium', 'webkit'].includes(engine)) invalid('unsupported-engine');
  const listeners = [], ids = new Set();
  let publicSent = 0, publicSockets = 0, failure = null, session;
  const cdp = {all: 0, text: 0, binary: 0, continuation: 0, control: 0, other: 0};
  const opened = socket => {
    if (++publicSockets > 4) { failure = 'public-socket-limit'; return; }
    const sent = () => { if (++publicSent > 128) failure = 'public-event-limit'; };
    socket.on('framesent', sent); listeners.push([socket, sent]);
  };
  const created = event => {
    if (typeof event.requestId !== 'string' || event.requestId.length > 256 || ids.size >= 4) { failure = 'cdp-socket-limit'; return; }
    ids.add(event.requestId);
  };
  const sent = event => {
    if (!ids.has(event.requestId) || ++cdp.all > 128) { failure = 'cdp-event-limit-or-unowned'; return; }
    const opcode = event.response?.opcode;
    cdp[opcode === 1 ? 'text' : opcode === 2 ? 'binary' : opcode === 0 ? 'continuation' : [8, 9, 10].includes(opcode) ? 'control' : 'other']++;
  };
  page.on('websocket', opened);
  try {
    if (engine === 'chromium') {
      session = await page.context().newCDPSession(page);
      session.on('Network.webSocketCreated', created); session.on('Network.webSocketFrameSent', sent);
      await session.send('Network.enable');
    }
    await work();
    // A command/reply on the owned protocol session drains earlier event delivery;
    // the workload itself already waits for one physical server ACK per message.
    if (session) await session.send('Runtime.evaluate', {expression: '0', returnByValue: true});
    return {status: failure ? 'inconclusive' : 'observed', failure, publicSockets, publicFrameSentEvents: publicSent,
      cdpSentEvents: session ? {...cdp} : null, physicalFramesFromBrowserEvents: null};
  } finally {
    page.removeListener('websocket', opened);
    for (const [socket, listener] of listeners) socket.removeListener('framesent', listener);
    if (session) {
      session.removeListener('Network.webSocketCreated', created); session.removeListener('Network.webSocketFrameSent', sent);
      await session.detach();
    }
  }
}

export const websocketCalibrationCases = Object.freeze([
  {kind: 'blob', bytes: 128, messages: 3}, {kind: 'text', bytes: 128, messages: 3},
  {kind: 'arraybuffer', bytes: 128, messages: 3}, {kind: 'blob', bytes: 0, messages: 3},
  {kind: 'text', bytes: 0, messages: 3}, {kind: 'arraybuffer', bytes: 0, messages: 3},
  {kind: 'gzip-blob', bytes: 128, messages: 3},
  {kind: 'deflate-raw-blob', bytes: 128, messages: 3},
].map(Object.freeze));

export async function sendSyntheticWebSocketMessages(page, origin, fixture) {
  await page.evaluate(async ({origin, fixture}) => {
    const socket = new WebSocket(origin.replace(/^http:/, 'ws:'));
    let deadline;
    const timeout = new Promise((_, reject) => { deadline = setTimeout(() => reject(new Error('Synthetic socket deadline')), 5000); });
    try {
      await Promise.race([timeout, (async () => {
      await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = () => reject(new Error('Synthetic socket open failed')); });
      for (let i = 0; i < fixture.messages; i++) {
        let payload;
        if (fixture.kind === 'text') payload = 'x'.repeat(fixture.bytes);
        else if (fixture.kind === 'arraybuffer') payload = new Uint8Array(fixture.bytes).buffer;
        else if (fixture.kind === 'blob') payload = new Blob([new Uint8Array(fixture.bytes)]);
        else payload = await new Response(new Blob([new Uint8Array(fixture.bytes)]).stream().pipeThrough(
          new CompressionStream(fixture.kind === 'deflate-raw-blob' ? 'deflate-raw' : 'gzip'))).blob();
        await new Promise((resolve, reject) => {
          socket.onmessage = event => event.data === 'ACK' ? resolve() : reject(new Error('Unexpected synthetic ACK'));
          socket.onerror = () => reject(new Error('Synthetic send failed'));
          socket.onclose = () => reject(new Error('Synthetic socket closed before ACK'));
          socket.send(payload);
        });
      }
      await new Promise(resolve => { socket.onclose = resolve; socket.close(); });
      })()]);
    } finally { clearTimeout(deadline); socket.close(); }
  }, {origin, fixture});
}
