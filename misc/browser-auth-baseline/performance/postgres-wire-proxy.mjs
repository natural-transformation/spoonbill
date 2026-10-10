import net from 'node:net';

/** Diagnostics intentionally contain only closed codes, never wire contents. */
export class ProtocolDiagnostic extends Error {
  constructor(code) { super(code); this.name = 'ProtocolDiagnostic'; this.code = code; }
}
const diagnostic = code => { throw new ProtocolDiagnostic(code); };
const counts = () => ({frontendBytes: 0, backendBytes: 0, frontendMessages: 0, backendMessages: 0,
  startupRequests: 0, syncRequests: 0, completedStartupExchanges: 0, completedSyncExchanges: 0});

/** Retains only an eight-byte/five-byte header. Payload bytes are skipped without
 * copying, decoding or retaining the caller's Buffer. Message size is bounded.
 */
class HeaderParser {
  constructor(startup, maximum, header, complete) {
    this.startup = startup; this.maximum = maximum; this.onHeader = header; this.onComplete = complete;
    this.header = Buffer.alloc(startup ? 8 : 5); this.used = 0; this.remaining = 0; this.type = null;
  }
  feed(bytes) {
    let offset = 0;
    while (offset < bytes.length) {
      if (this.remaining) {
        const skipped = Math.min(this.remaining, bytes.length - offset);
        this.remaining -= skipped; offset += skipped;
        if (!this.remaining) this.complete();
      } else {
        const wanted = (this.startup ? 8 : 5) - this.used;
        const copied = Math.min(wanted, bytes.length - offset);
        bytes.copy(this.header, this.used, offset, offset + copied);
        this.used += copied; offset += copied;
        if (this.used === (this.startup ? 8 : 5)) {
          const length = this.header.readUInt32BE(this.startup ? 0 : 1);
          if (length < (this.startup ? 8 : 4)) diagnostic('INVALID_MESSAGE_LENGTH');
          if (length > this.maximum) diagnostic('MESSAGE_TOO_LARGE');
          if (this.startup) {
            const version = this.header.readUInt32BE(4);
            if (version === 80877103) diagnostic('SSL_UNSUPPORTED');
            if (version === 80877104) diagnostic('GSS_ENCRYPTION_UNSUPPORTED');
            if (version === 80877102) diagnostic('CANCEL_CONNECTION_UNSUPPORTED');
            if (version !== 196608) diagnostic('PROTOCOL_VERSION_UNSUPPORTED');
          }
          this.type = this.startup ? 'startup' : String.fromCharCode(this.header[0]);
          this.remaining = length - (this.startup ? 8 : 4);
          this.used = 0;
          this.onHeader(this.type, length);
          if (!this.remaining) this.complete();
        }
      }
    }
  }
  complete() {
    const type = this.type;
    this.type = null; this.startup = false;
    this.onComplete(type);
  }
  get incomplete() { return this.used !== 0 || this.remaining !== 0; }
}

/** Q and S each end in one Z; startup Z is separate. Strict sequential mode is
 * the default. The opt-in observer permits bounded anonymous FIFO cycles, never
 * treating overlapping cycles as measured physical round trips.
 */
export class ProtocolCounter {
  #cycles;
  #head = 0;
  #size = 0;
  constructor({maxMessageBytes = 16 * 1024 * 1024, mode = 'strict-sequential',
    maxPendingCycles = mode === 'strict-sequential' ? 1 : 32} = {}) {
    if (!Number.isSafeInteger(maxMessageBytes) || maxMessageBytes < 8 || maxMessageBytes > 128 * 1024 * 1024)
      throw new Error('maxMessageBytes must be between 8 and 134217728');
    if (!['strict-sequential', 'pipelined-cycles'].includes(mode) ||
        !Number.isInteger(maxPendingCycles) || maxPendingCycles < 1 || maxPendingCycles > 1024 ||
        (mode === 'strict-sequential' && maxPendingCycles !== 1))
      throw new Error('Explicit supported mode and bounded maxPendingCycles required');
    this.mode = mode; this.maxPendingCycles = maxPendingCycles;
    this.#cycles = new Uint8Array(maxPendingCycles);
    this.peakPendingCycles = 0; this.overlapObserved = false;
    this.counts = counts(); this.failures = {};
    this.extended = false; this.finished = false; this.terminated = false;
    this.front = new HeaderParser(true, maxMessageBytes, (type, length) => this.frontHeader(type, length), type => {
      this.counts.frontendMessages++;
      if (type === 'startup') { this.enqueue(1); this.counts.startupRequests++; }
      else if (type === 'Q' || type === 'S') { this.enqueue(2); this.extended = false; this.counts.syncRequests++; }
      else if ('PBDEC'.includes(type)) this.extended = true;
      else if (type === 'X') this.terminated = true;
    });
    this.back = new HeaderParser(false, maxMessageBytes, (type, length) => {
      if ('GHWdc'.includes(type)) diagnostic('COPY_UNSUPPORTED');
      if (!'RSKZTD CENA123tnsIV'.replaceAll(' ', '').includes(type)) diagnostic('BACKEND_MESSAGE_UNSUPPORTED');
      if (type === 'Z' && length !== 5) diagnostic('INVALID_READY_LENGTH');
    }, type => {
      this.counts.backendMessages++;
      if (type === 'Z') {
        if (!this.pending) diagnostic('UNEXPECTED_READY_FOR_QUERY');
        if (this.pending === 'startup') this.counts.completedStartupExchanges++;
        else this.counts.completedSyncExchanges++;
        this.#cycles[this.#head] = 0;
        this.#head = (this.#head + 1) % this.maxPendingCycles;
        this.#size--;
      }
    });
  }
  get pending() { return this.#size ? (this.#cycles[this.#head] === 1 ? 'startup' : 'sync') : null; }
  enqueue(token) {
    if (this.#size >= this.maxPendingCycles) diagnostic('PENDING_CYCLE_CAPACITY');
    this.#cycles[(this.#head + this.#size) % this.maxPendingCycles] = token;
    this.#size++;
    this.peakPendingCycles = Math.max(this.peakPendingCycles, this.#size);
  }
  frontHeader(type, length) {
    if (this.terminated) diagnostic('MESSAGE_AFTER_TERMINATE');
    if (type === 'startup') return;
    if ('dcf'.includes(type)) diagnostic('COPY_UNSUPPORTED');
    if (type === 'H') diagnostic('FLUSH_UNSUPPORTED');
    if (!'QSPBDECpX'.includes(type)) diagnostic('FRONTEND_MESSAGE_UNSUPPORTED');
    if ((type === 'S' || type === 'X') && length !== 4) diagnostic('INVALID_CONTROL_LENGTH');
    if (type === 'p') {
      if (this.pending !== 'startup') diagnostic('AUTHENTICATION_OUTSIDE_STARTUP');
      return;
    }
    if (type === 'X') return;
    if (this.pending) {
      if (this.mode === 'strict-sequential' || this.pending === 'startup') diagnostic('OVERLAPPING_EXCHANGE');
      this.overlapObserved = true;
      if (this.#size >= this.maxPendingCycles) diagnostic('PENDING_CYCLE_CAPACITY');
    }
    if (type === 'Q' && this.extended) diagnostic('EXTENDED_CYCLE_WITHOUT_SYNC');
  }
  recordFailure(code) { this.failures[code] = (this.failures[code] ?? 0) + 1; }
  feed(side, bytes) {
    if (this.finished) throw new ProtocolDiagnostic('CONNECTION_CLOSED');
    if (Object.keys(this.failures).length) throw new ProtocolDiagnostic('CONNECTION_ALREADY_UNSUPPORTED');
    if (!Buffer.isBuffer(bytes)) throw new TypeError('Wire input must be a Buffer');
    this.counts[side === 'frontend' ? 'frontendBytes' : 'backendBytes'] += bytes.length;
    try { (side === 'frontend' ? this.front : this.back).feed(bytes); }
    catch (error) {
      const code = error instanceof ProtocolDiagnostic ? error.code : 'PARSER_FAILURE';
      this.recordFailure(code); throw new ProtocolDiagnostic(code);
    }
  }
  frontend(bytes) { this.feed('frontend', bytes); }
  backend(bytes) { this.feed('backend', bytes); }
  finish() {
    if (!this.finished) {
      if (this.front.incomplete || this.back.incomplete) this.recordFailure('TRUNCATED_MESSAGE');
      if (this.pending) this.recordFailure('MISSING_READY_FOR_QUERY');
      if (this.extended) this.recordFailure('EXTENDED_CYCLE_WITHOUT_SYNC');
      this.finished = true;
    }
    return this.snapshot();
  }
  snapshot() {
    const supported = Object.keys(this.failures).length === 0;
    return Object.freeze({...this.counts, status: supported ? (this.finished ? 'complete' : 'measuring') : 'inconclusive',
      startupExchanges: supported ? this.counts.completedStartupExchanges : null,
      syncExchanges: supported && !this.overlapObserved ? this.counts.completedSyncExchanges : null,
      completedProtocolSyncCycles: supported ? this.counts.completedSyncExchanges : null,
      physicalRoundTrips: null, overlapObserved: this.overlapObserved,
      mode: this.mode, maxPendingCycles: this.maxPendingCycles,
      pendingExchanges: this.#size, pendingCycleSlots: this.#size, peakPendingCycleSlots: this.peakPendingCycles,
      bufferedHeaderBytes: this.front.used + this.back.used,
      retainedPayloadBytes: 0, headerStorageBytes: 13, cycleStorageBytes: this.#cycles.byteLength,
      // Legacy alias counts fixed header buffers ONLY, not object/queue/heap.
      allocatedParserBytes: 13,
      diagnostics: Object.freeze({...this.failures})});
  }
}

/** Disposable local test infrastructure only. TLS, remote target selection,
 * COPY, Flush and cancellation connections are intentionally absent. Pipelined
 * cycle observation requires an explicit mode; strict defaults remain unchanged.
 * Forwarding observes Node socket backpressure; no message payload is collected.
 */
export async function createPostgresWireProxy({targetPort, listenPort = 0, maxMessageBytes = 16 * 1024 * 1024,
  maxConnections = 32, socketTimeoutMs = 10000, mode = 'strict-sequential',
  maxPendingCycles = mode === 'strict-sequential' ? 1 : 32} = {}) {
  if (!Number.isInteger(targetPort) || targetPort < 1 || targetPort > 65535) throw new Error('Explicit loopback targetPort required');
  if (!Number.isInteger(listenPort) || listenPort < 0 || listenPort > 65535) throw new Error('Invalid listenPort');
  if (!Number.isInteger(maxConnections) || maxConnections < 1 || maxConnections > 1024) throw new Error('Invalid maxConnections');
  if (!Number.isInteger(socketTimeoutMs) || socketTimeoutMs < 1 || socketTimeoutMs > 120000) throw new Error('Invalid socketTimeoutMs');
  new ProtocolCounter({maxMessageBytes, mode, maxPendingCycles}); // Validate before acquiring the listener.
  const active = new Set(), totals = counts(), failures = {};
  let opened = 0, closed = 0, closing = false, closePromise;
  let overlapObserved = false, peakPendingCycleSlots = 0, peakConnectionPendingCycleSlots = 0;
  function observeResources() {
    let pending = 0;
    for (const pair of active) {
      const value = pair.counter.snapshot();
      overlapObserved ||= value.overlapObserved;
      peakConnectionPendingCycleSlots = Math.max(peakConnectionPendingCycleSlots, value.peakPendingCycleSlots);
      if (!pair.counter.finished) pending += value.pendingCycleSlots;
    }
    peakPendingCycleSlots = Math.max(peakPendingCycleSlots, pending);
  }
  const failure = code => { failures[code] = (failures[code] ?? 0) + 1; };
  const server = net.createServer(client => {
    if (closing || active.size >= maxConnections) { failure('CONNECTION_CAPACITY'); client.destroy(); return; }
    opened++;
    const counter = new ProtocolCounter({maxMessageBytes, mode, maxPendingCycles});
    const backend = net.createConnection({host: '127.0.0.1', port: targetPort});
    const pair = {client, backend, counter, done: null};
    let settled = false, resolveDone;
    pair.done = new Promise(resolve => { resolveDone = resolve; });
    active.add(pair);
    client.pause(); client.setNoDelay(true); backend.setNoDelay(true);
    client.setTimeout(socketTimeoutMs); backend.setTimeout(socketTimeoutMs);
    function dispose(code) {
      if (settled) return;
      settled = true;
      if (code) counter.recordFailure(code);
      client.destroy(); backend.destroy();
      const final = counter.finish();
      overlapObserved ||= final.overlapObserved;
      peakConnectionPendingCycleSlots = Math.max(peakConnectionPendingCycleSlots, final.peakPendingCycleSlots);
      for (const name of Object.keys(totals)) totals[name] += final[name];
      for (const [name, count] of Object.entries(final.diagnostics)) failures[name] = (failures[name] ?? 0) + count;
      // Keep the pair counted until both owned sockets have actually closed.
      Promise.all([client, backend].map(socket => socket.closed ? Promise.resolve() : new Promise(resolve => socket.once('close', resolve))))
        .then(() => { active.delete(pair); closed++; resolveDone(); });
    }
    for (const socket of [client, backend]) {
      socket.on('error', () => dispose('SOCKET_FAILURE'));
      socket.on('timeout', () => dispose('SOCKET_TIMEOUT'));
      socket.on('end', () => dispose());
      socket.on('close', () => dispose());
    }
    function forward(source, destination, side) {
      source.on('data', bytes => {
        if (settled) return;
        try { counter[side](bytes); observeResources(); }
        catch (_) { observeResources(); dispose(); return; }
        if (!destination.write(bytes)) source.pause();
      });
      destination.on('drain', () => { if (!settled) source.resume(); });
    }
    forward(client, backend, 'frontend'); forward(backend, client, 'backend');
    backend.once('connect', () => { if (!settled) client.resume(); });
  });
  await new Promise((resolve, reject) => {
    server.once('error', reject);
    server.listen(listenPort, '127.0.0.1', () => { server.removeListener('error', reject); resolve(); });
  });
  const address = server.address();
  if (address.port === targetPort) { await new Promise(resolve => server.close(resolve)); throw new Error('Proxy cannot target itself'); }
  server.on('error', () => failure('LISTENER_FAILURE'));
  function snapshot() {
    const observed = {...totals}, diagnostics = {...failures};
    let pending = 0, headers = 0;
    for (const pair of active) {
      // Settled sockets are included in totals while their close events drain.
      if (pair.counter.finished) continue;
      const current = pair.counter.snapshot();
      for (const name of Object.keys(observed)) observed[name] += current[name];
      for (const [name, count] of Object.entries(current.diagnostics)) diagnostics[name] = (diagnostics[name] ?? 0) + count;
      pending += current.pendingExchanges; headers += current.bufferedHeaderBytes;
    }
    const valid = !Object.keys(diagnostics).length;
    return Object.freeze({...observed, connections: opened, closedConnections: closed, activeConnections: active.size,
      pendingExchanges: pending, bufferedHeaderBytes: headers, retainedPayloadBytes: 0,
      allocatedParserBytes: active.size * 13,
      status: valid ? (closing && active.size === 0 ? 'complete' : 'measuring') : 'inconclusive',
      startupExchanges: valid ? observed.completedStartupExchanges : null,
      syncExchanges: valid && !overlapObserved ? observed.completedSyncExchanges : null,
      completedProtocolSyncCycles: valid ? observed.completedSyncExchanges : null,
      physicalRoundTrips: null, overlapObserved, mode, maxPendingCycles,
      pendingCycleSlots: pending, peakPendingCycleSlots, peakConnectionPendingCycleSlots,
      headerStorageBytes: active.size * 13, cycleStorageBytes: active.size * maxPendingCycles,
      diagnostics: Object.freeze(diagnostics)});
  }
  return Object.freeze({port: address.port, snapshot,
    close() {
      if (!closePromise) {
        closing = true;
        closePromise = (async () => {
          const listenerClosed = new Promise(resolve => server.close(resolve));
          const owned = [...active];
          for (const pair of owned) { pair.client.destroy(); pair.backend.destroy(); }
          await Promise.all([listenerClosed, ...owned.map(pair => pair.done)]);
          return snapshot();
        })();
      }
      return closePromise;
    }});
}
