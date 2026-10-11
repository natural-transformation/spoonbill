import {verifyWebKitNetworkPin} from './webkit-network-probe.mjs';

/** Measurement-only per-instance hooks. No payload, URL, cookie or header read;
 * shared Playwright sessions and Network domains are never disposed/disabled. */
export class WebKitNetworkObserver {
  #delegate;
  #proxy;
  #records = new Map();
  #hooks = [];
  #failures = new Set();
  #closing = false;
  #released = false;
  #stopping;
  #onEvent;
  #createdSessions = 0;
  #peakSessions = 0;
  #retirements = 0;
  #retiredActiveIds = 0;
  #peakIds = 0;
  #events = 0;
  #destroyed;
  constructor(delegate, {onEvent = () => {}} = {}) {
    if (!delegate?._session || !delegate?._pageProxySession?.on || !delegate?._pageProxySession?.removeListener ||
        typeof delegate._initializeSession !== 'function' || typeof delegate._setSession !== 'function' ||
        ['_initializeSession', '_setSession'].some(name => Object.hasOwn(delegate, name)))
      throw new Error('Pinned WebKit per-instance observation unavailable');
    this.#delegate = delegate; this.#proxy = delegate._pageProxySession; this.#onEvent = onEvent;
    const self = this;
    const initialize = delegate._initializeSession;
    this.#hook('_initializeSession', function(...args) {
      self.#safe(() => self.#attach(args[0]));
      return initialize.apply(this, args);
    });
    const set = delegate._setSession;
    this.#hook('_setSession', function(...args) {
      const previous = this._session;
      const result = set.apply(this, args);
      self.#safe(() => {
        if (self.#closing) self.#fail('session-changed-during-drain');
        if (!self.#records.has(args[0])) self.#attach(args[0]);
        if (previous !== args[0]) self.#retire(previous);
      });
      return result;
    });
    this.#destroyed = event => this.#safe(() => {
      const record = [...this.#records.values()].find(value => value.id === event.targetId);
      if (record) {
        if (this.#closing) this.#fail('session-changed-during-drain');
        this.#retire(record.session);
      }
    });
    this.#proxy.on('Target.targetDestroyed', this.#destroyed);
    try {
      this.#attach(delegate._session);
      if (delegate._provisionalPage?._session) this.#attach(delegate._provisionalPage._session);
    } catch (error) { this.#release(); throw error; }
  }
  #fail(code) { if (this.#failures.size < 16) this.#failures.add(code); }
  #safe(body) { if (this.#released) return; try { body(); } catch (_) { this.#fail('metadata-or-callback-failure'); } }
  #emit(kind, opcode) {
    if (this.#events >= 1000000) { this.#fail('event-limit'); return; }
    ++this.#events; this.#safe(() => this.#onEvent(kind, opcode));
  }
  #hook(name, wrapper) {
    const previous = Object.getOwnPropertyDescriptor(this.#delegate, name);
    Object.defineProperty(this.#delegate, name, {value: wrapper, configurable: true, writable: true});
    this.#hooks.push({name, previous, wrapper});
  }
  #attach(session) {
    if (this.#records.has(session)) return;
    if (this.#released || this.#closing) { this.#fail('session-changed-during-drain'); return; }
    if (this.#records.size >= 2 || this.#createdSessions >= 64) { this.#fail('session-limit'); return; }
    if (!session?.on || !session?.removeListener || !session?.send || !session?.isDisposed ||
        typeof session.sessionId !== 'string' || session.sessionId.length > 256)
      throw new Error('Unsupported WebKit session');
    const record = {session, id: session.sessionId, active: new Set(), retired: new Set(), listeners: [], retiring: false};
    const listen = (name, body) => {
      const listener = event => this.#safe(() => { if (!this.#released && this.#records.has(session)) body(event); });
      record.listeners.push([name, listener]); session.on(name, listener);
    };
    this.#records.set(session, record); ++this.#createdSessions;
    this.#peakSessions = Math.max(this.#peakSessions, this.#records.size);
    listen('Network.webSocketCreated', event => {
      const id = event.requestId;
      if (typeof id !== 'string' || !id.length || id.length > 256 || record.active.has(id) || record.retired.has(id) ||
          record.active.size >= 16 || record.active.size + record.retired.size >= 128) {
        this.#fail('socket-limit-or-identity'); return;
      }
      record.active.add(id); this.#peakIds = Math.max(this.#peakIds, this.#activeIds()); this.#emit('created');
    });
    listen('Network.webSocketFrameSent', event => {
      if (!record.active.has(event.requestId)) { this.#fail('unowned-frame'); return; }
      const opcode = event.response?.opcode;
      if (![0, 1, 2, 8, 9, 10].includes(opcode)) this.#fail('unsupported-opcode');
      this.#emit('sent', opcode);
    });
    listen('Network.webSocketClosed', event => {
      if (record.retired.has(event.requestId)) { this.#emit('duplicate-close'); return; }
      if (!record.active.delete(event.requestId)) { this.#fail('unowned-close'); return; }
      record.retired.add(event.requestId); this.#emit('closed');
    });
  }
  #activeIds() { return [...this.#records.values()].reduce((sum, record) => sum + record.active.size, 0); }
  #retire(session) {
    const record = this.#records.get(session);
    if (!record || record.retiring) return;
    record.retiring = true; ++this.#retirements;
    // The pinned WKSession.dispatchMessage queues metadata via Promise.then.
    // Keep listeners for already queued callbacks; do not immediately clear.
    Promise.resolve().then(() => {
      try {
        if (this.#records.get(session) !== record) return;
        if (record.active.size) {
          this.#retiredActiveIds += record.active.size;
          this.#fail('active-ids-at-session-retirement');
        }
        this.#remove(record);
      } finally { --this.#retirements; }
    });
  }
  #remove(record) {
    for (const [event, listener] of record.listeners) record.session.removeListener(event, listener);
    record.listeners.length = 0; record.active.clear(); record.retired.clear(); this.#records.delete(record.session);
  }
  #release() {
    if (this.#released) return;
    this.#released = true;
    this.#proxy.removeListener('Target.targetDestroyed', this.#destroyed);
    for (const record of this.#records.values()) this.#remove(record);
    for (const {name, previous, wrapper} of this.#hooks) {
      if (this.#delegate[name] !== wrapper) { this.#fail('hook-replaced'); continue; }
      if (previous) Object.defineProperty(this.#delegate, name, previous);
      else delete this.#delegate[name];
    }
    this.#hooks.length = 0;
    this.#delegate = null; this.#proxy = null; this.#destroyed = null; this.#onEvent = null;
  }
  snapshot() {
    return {status: this.#failures.size ? 'inconclusive' : 'observing', failures: [...this.#failures],
      sessionsCreated: this.#createdSessions, peakSessions: this.#peakSessions,
      activeSessions: this.#records.size, activeSocketIds: this.#activeIds(),
      retainedSocketIds: [...this.#records.values()].reduce((sum, r) => sum + r.active.size + r.retired.size, 0),
      peakActiveSocketIds: this.#peakIds, activeIdsAtRetiredBoundaries: this.#retiredActiveIds,
      pendingRetirements: this.#retirements, observedEvents: this.#events, released: this.#released,
      retainedPageHandles: this.#delegate ? 1 : 0};
  }
  stop() {
    if (this.#stopping) return this.#stopping;
    this.#closing = true;
    const records = [...this.#records.values()];
    this.#stopping = (async () => {
      try {
        await Promise.all(records.map(async record => {
          if (record.session.isDisposed()) { await Promise.resolve(); return; }
          try { await record.session.send('Runtime.evaluate', {expression: '0', returnByValue: true}); }
          catch (_) { this.#fail('session-drain-failed'); }
        }));
        if (this.#activeIds()) this.#fail('active-ids-at-stop');
      } finally { records.length = 0; this.#release(); }
      await Promise.resolve();
      return {...this.snapshot(), status: this.#failures.size ? 'inconclusive' : 'observed'};
    })();
    return this.#stopping;
  }
}

export function createPinnedWebKitNetworkObserver(page, options = {}) {
  verifyWebKitNetworkPin(options.driverRoot ?? process.env.PLAYWRIGHT_DRIVER_PATH);
  const delegate = page?._connection?.toImpl?.(page)?.delegate;
  return new WebKitNetworkObserver(delegate, options);
}
