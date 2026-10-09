const REGION_ATTRIBUTE = 'data-sb-region';
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME_PATTERN = /^[a-z][a-z0-9_.-]{0,63}$/;
// A reconnect may reuse an empty marker. Closed roots cannot be attached twice;
// keep only weak DOM ownership, and clear contents on every instance's destroy.
const REGION_ROOTS = new WeakMap();

/** Transient isolated presentation. Records retain ownership/DOM/timers only;
 * plaintext exists only in the incoming call and the deliberately visible DOM.
 * This is not a defense against arbitrary trusted same-origin JavaScript.
 */
export class SensitiveRegions {
  constructor(acknowledge, retired, resolveElement, recoverDeparture = () => {}) {
    this._acknowledge = acknowledge;
    this._retired = retired;
    this._resolveElement = resolveElement;
    this._connection = null;
    this._active = new Map();
    this._roots = REGION_ROOTS;
    this._seen = new Set();
    this._closed = false;
    this._navigationBarrier = null;
    this._navigationPermanent = false;
    this._departureBarrier = null;
    this._departureCounter = 0;
    this._navigation = () => this.blockNavigation();
    this._departure = () => {
      if (this._closed || this._navigationPermanent) return;
      if (this._departureCounter >= Number.MAX_SAFE_INTEGER) { this.blockNavigation(); return; }
      this._departureBarrier = ++this._departureCounter;
      this.clearAll();
      recoverDeparture(this._departureBarrier);
    };
    // Spoonbill's history callback runs first and establishes the counter. If
    // it was suppressed, no matching server barrier can arrive on this Bridge.
    this._history = () => {
      if (this._navigationBarrier === null) this.blockNavigation();
      else this.clearAll();
    };
    this._visibility = () => this.reconcile();
    window.addEventListener('pagehide', this._navigation);
    window.addEventListener('beforeunload', this._departure);
    window.addEventListener('popstate', this._history);
    document.addEventListener('visibilitychange', this._visibility);
    if (typeof MutationObserver !== 'undefined') {
      this._observer = new MutationObserver(() => this.reconcile());
      this._observer.observe(document.documentElement, {subtree: true, childList: true,
        attributes: true, attributeFilter: [REGION_ATTRIBUTE]});
    }
  }

  show(connection, presentation, region, purpose, remainingMillis, payload, expiresAtMillis) {
    const validBinding = typeof connection === 'string' && UUID_PATTERN.test(connection) &&
      typeof presentation === 'string' && UUID_PATTERN.test(presentation) &&
      typeof region === 'string' && NAME_PATTERN.test(region);
    if (!validBinding) return;
    const binding = `${connection}:${presentation}:${region}`;
    try {
      if (this._navigationPermanent || this._navigationBarrier !== null || this._departureBarrier !== null) {
        if (this._seen.size < 256) this._seen.add(presentation);
        throw new Error('Sensitive navigation pending');
      }
      const now = Date.now();
      if (this._closed || (this._connection !== null && this._connection !== connection) ||
          typeof purpose !== 'string' || !NAME_PATTERN.test(purpose) ||
          !Number.isInteger(remainingMillis) || remainingMillis <= 0 || remainingMillis > 300000 ||
          !Number.isSafeInteger(expiresAtMillis) || expiresAtMillis <= now ||
          this._seen.has(presentation) || this._seen.size >= 256)
        throw new Error('Sensitive presentation rejected');
      this._validatePayload(payload);
      const hosts = document.querySelectorAll(`sb-secret[${REGION_ATTRIBUTE}="${region}"]`);
      if (hosts.length !== 1 || hosts[0].childNodes.length !== 0 ||
          (!this._active.has(region) && this._active.size >= 4))
        throw new Error('Sensitive region unavailable');
      const host = hosts[0];
      const old = this._active.get(region);
      if (old) this._clearRecord(old, true);
      let root = this._roots.get(host);
      if (!root) {
        root = host.attachShadow({mode: 'closed'});
        this._roots.set(host, root);
      }
      this._connection = connection;
      this._seen.add(presentation);
      const record = {connection, presentation, region, host, root,
        deadline: Math.min(now + remainingMillis, expiresAtMillis), timer: null};
      this._active.set(region, record);
      try {
        this._render(root, payload);
        if (Date.now() >= record.deadline) throw new Error('Sensitive presentation expired');
        this._arm(record, record.deadline - Date.now());
      } catch (_) {
        this._clearRecord(record, false);
        throw new Error('Sensitive presentation failed');
      }
      this._acknowledge(binding + ':ok');
    } catch (_) {
      // No raw payload or exception is logged, retried, or retained for replay.
      this._acknowledge(binding + ':failed');
    }
  }

  _validatePayload(payload) {
    if (!Array.isArray(payload)) throw new Error('Invalid sensitive payload');
    const bytes = value => {
      if (typeof value !== 'string' || !value.length || value.length > 8192)
        throw new Error('Invalid sensitive text');
      // Reject unpaired surrogates rather than silently changing their value.
      for (let i = 0; i < value.length; i++) {
        const code = value.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff) {
          const next = value.charCodeAt(++i);
          if (!(next >= 0xdc00 && next <= 0xdfff)) throw new Error('Invalid sensitive text');
        } else if (code >= 0xdc00 && code <= 0xdfff) throw new Error('Invalid sensitive text');
      }
      return new TextEncoder().encode(value).length;
    };
    let total = 0;
    if (payload[0] === 0 && payload.length === 2 && Array.isArray(payload[1])) {
      if (!payload[1].length || payload[1].length > 64) throw new Error('Invalid sensitive list');
      for (const item of payload[1]) {
        const size = bytes(item);
        if (size > 8192) throw new Error('Sensitive text too large');
        total += size;
      }
    } else if (payload[0] === 1 && payload.length === 3) {
      total = bytes(payload[1]);
      if (total > 8192) throw new Error('Sensitive URI too large');
      const uri = new URL(payload[1]);
      const secrets = uri.searchParams.getAll('secret');
      if (uri.protocol !== 'otpauth:' || uri.hostname !== 'totp' || uri.username || uri.password || uri.port || uri.hash ||
          uri.pathname.length <= 1 || secrets.length !== 1 || !/^[A-Za-z2-7]+={0,6}$/.test(secrets[0]))
        throw new Error('Invalid sensitive URI');
      if (payload[2] !== null) {
        const rows = payload[2];
        if (!Array.isArray(rows) || rows.length < 21 || rows.length > 177 || (rows.length - 21) % 4 !== 0 ||
            !rows.every(row => typeof row === 'string' && row.length === rows.length && /^[01]+$/.test(row)))
          throw new Error('Invalid sensitive QR');
        total += rows.length * rows.length;
      }
    } else throw new Error('Invalid sensitive payload');
    if (total > 65536) throw new Error('Sensitive payload too large');
  }

  _render(root, payload) {
    root.replaceChildren();
    const container = document.createElement(payload[0] === 0 ? 'ul' : 'div');
    if (payload[0] === 0) {
      for (const value of payload[1]) {
        const item = document.createElement('li');
        item.textContent = value;
        container.appendChild(item);
      }
    } else {
      const text = document.createElement('code');
      text.textContent = payload[1];
      container.appendChild(text);
      if (payload[2] !== null) {
        const rows = payload[2];
        const canvas = document.createElement('canvas');
        const scale = 4;
        canvas.width = canvas.height = (rows.length + 8) * scale;
        canvas.setAttribute('role', 'img');
        canvas.setAttribute('aria-label', 'Authenticator setup QR code');
        const context = canvas.getContext('2d');
        if (!context) throw new Error('QR canvas unavailable');
        context.fillStyle = '#fff';
        context.fillRect(0, 0, canvas.width, canvas.height);
        context.fillStyle = '#000';
        rows.forEach((row, y) => {
          for (let x = 0; x < row.length; x++) if (row[x] === '1')
            context.fillRect((x + 4) * scale, (y + 4) * scale, scale, scale);
        });
        container.appendChild(canvas);
      }
    }
    root.appendChild(container);
  }

  _arm(record, remainingMillis) {
    // Separate scope: the timer never closes over the incoming secret payload.
    record.timer = setTimeout(() => this._clearRecord(record, true), remainingMillis);
  }

  _clearRecord(record, notify) {
    if (this._active.get(record.region) !== record) return;
    this._active.delete(record.region);
    clearTimeout(record.timer);
    // Clear canvas backing pixels as well as detaching its DOM node.
    for (const canvas of record.root.querySelectorAll('canvas')) canvas.width = canvas.height = 0;
    record.root.replaceChildren();
    if (notify) this._retired(`${record.connection}:${record.presentation}:${record.region}`);
  }

  clear(connection, presentation, region) {
    if (connection !== this._connection) return;
    if (presentation === '' && region === '') { this.clearAll(false); return; }
    const record = this._active.get(region);
    if (record && record.presentation === presentation) this._clearRecord(record, false);
  }

  clearAll(notify = true) {
    for (const record of this._active.values()) this._clearRecord(record, notify);
  }

  beginNavigation(counter) {
    if (!this._navigationPermanent) this._navigationBarrier = counter;
    this.clearAll();
  }

  completeNavigation(counter) {
    if (!this._navigationPermanent && this._navigationBarrier === counter) this._navigationBarrier = null;
  }

  get departurePending() { return this._departureBarrier !== null; }
  get navigationPending() { return this._navigationBarrier !== null; }
  get terminal() { return this._closed || this._navigationPermanent; }

  completeDeparture(counter) {
    if (!this._closed && !this._navigationPermanent && this._departureBarrier === counter)
      this._departureBarrier = null;
  }

  blockNavigation() {
    this._navigationPermanent = true;
    this.clearAll();
  }

  beforePatch(commands) {
    if (!this._active.size) return;
    const widths = [4, 3, 2, 5, 4, 3, 2];
    for (let i = 0; i < commands.length;) {
      const operation = commands[i++];
      const width = widths[operation];
      if (width === undefined || i + width > commands.length) { this.clearAll(); return; }
      const id = commands[i];
      const child = operation <= 2 ? this._resolveElement(commands[i + 1]) : null;
      const target = this._resolveElement(id);
      for (const record of this._active.values()) {
        const replacing = child && (child === record.host || child.contains(record.host));
        const writingInside = operation <= 2 && target && (target === record.host || record.host.contains(target));
        const changingMarker = (operation === 3 || operation === 4) && target === record.host && commands[i + 2] === REGION_ATTRIBUTE;
        const replacingContents = operation === 3 && commands[i + 4] === true &&
          ['innerHTML', 'outerHTML', 'textContent', 'innerText'].includes(commands[i + 2]) && target &&
          (target === record.host || target.contains(record.host));
        if (replacing || writingInside || changingMarker || replacingContents) this._clearRecord(record, true);
      }
      i += width;
    }
  }

  reconcile() {
    for (const record of this._active.values()) {
      if (!record.host.isConnected || record.host.getAttribute(REGION_ATTRIBUTE) !== record.region ||
          record.host.childNodes.length !== 0 || Date.now() >= record.deadline)
        this._clearRecord(record, true);
    }
  }

  destroy() {
    this._closed = true;
    this.clearAll(false);
    if (this._observer) this._observer.disconnect();
    window.removeEventListener('pagehide', this._navigation);
    window.removeEventListener('beforeunload', this._departure);
    window.removeEventListener('popstate', this._history);
    document.removeEventListener('visibilitychange', this._visibility);
    this._seen.clear();
  }
}
