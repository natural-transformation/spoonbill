import {ConnectionType, initialConnection, connectionStep, connectionOpened, connectionReady,
  currentTransport, currentSchedule, authenticationPending, nextTransport, socketSenderStep,
  linkUp, noticeShown, pollDelay, pollAnswer, reloadWait, transportNote, frameKind, afterFrame,
  reloadStarted, messageSent, repollsAfterMessage, pollDeadline} from './connection-machine.js';
export {ConnectionType} from './connection-machine.js';

const MAX_PENDING_SENDS = 128;
// The tab remembers its last framework reload and, after a fallback, its
// transport. The keys carry the root path of the application, so that two
// applications on one origin keep theirs apart.
const RELOAD_KEY = 'spoonbill:reload:';
const TRANSPORT_KEY = 'spoonbill:transport:';

/** The sessionStorage of this tab, or null when the browser has none or refuses it. */
function sessionStore() {
  try {
    const storage = window.sessionStorage;
    return storage ? storage : null;
  } catch (_) {
    return null;
  }
}

/** A random UUID for this document. getRandomValues also works outside secure contexts. */
function randomPageId() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  let hex = '';
  for (let i = 0; i < bytes.length; i++) hex += (bytes[i] + 0x100).toString(16).slice(1);
  return hex.slice(0, 8) + '-' + hex.slice(8, 12) + '-' + hex.slice(12, 16) + '-' + hex.slice(16, 20) + '-' + hex.slice(20);
}

/**
 * Reconnectable WebSocket connection
 * with fallback to Long Polling.
 */
export class Connection {

  /**
   * @param {string} sessionId
   * @param {string} serverRootPath
   * @param {Location} location
   * @param {Object} [options]
   */
  constructor(sessionId, serverRootPath, location, options) {
    this._lifecycle = initialConnection();
    // The page id names this document for its whole life.
    this._lifecycle = connectionStep(this._lifecycle, {type: 'page', page: randomPageId()});
    this._pollDeadline = pollDeadline(options && options['lpw'] ? Number(options['lpw']) : undefined);
    /** Polls again at once, giving up the poll that waits. Only long polling has one. */
    this._repoll = () => {};
    /** Gives up the request of the heartbeat in flight. Only long polling has one. */
    this._abortHeartbeat = () => {};
    // The long-polling transport counts a message that could not be sent as a failed poll.
    this._linkFailed = () => {};
    // The transport that is replaced gives up its poll and its deadline.
    this._retire = () => {};
    this._sessionId = sessionId;
    this._serverRootPath = serverRootPath;

    this._hostPort = location.host;
    this._useSSL = location.protocol === "https:";
    this._location = location;

    this._reconnectTimer = null;
    /** @type {?WebSocket} */
    this._webSocket = null;
    /** @type {?TextEncoder} */
    this._textEncoder = null;
    const wsEnabled = !(options && options['ws'] === false);
    this._webSocketProtocolsEnabled = !(options && options['wsp'] === false);
    this._webSocketCompressionEnabled = options && options['wsc'] === true;
    this._guarded = options && options['auth'] === true;
    this._replacementEnabled = this._guarded && options['replacement'] === true;
    this._replacementAbort = null;
    if (this._replacementEnabled) {
      // WebKit can close a socket and disallow new fetches before pagehide.
      // This hint blocks only discovery; ordinary reconnect remains available
      // if a canceled departure leaves this document alive.
      window.addEventListener('beforeunload', () => { this._lifecycle = connectionStep(this._lifecycle, {type: 'before-unload'}); });
      const resumeAfterCanceledDeparture = event => {
        if (event.isTrusted === true && !(this._lifecycle.presence === 'departed') && document.visibilityState !== 'hidden')
          this._lifecycle = connectionStep(this._lifecycle, {type: 'interaction', trusted: true, visible: true});
      };
      window.addEventListener('pointerdown', resumeAfterCanceledDeparture, true);
      window.addEventListener('keydown', resumeAfterCanceledDeparture, true);
      window.addEventListener('pagehide', () => {
        this._lifecycle = connectionStep(this._lifecycle, {type: 'hide', scheduleEpoch: {}});
        if (this._replacementAbort) this._replacementAbort.abort();
        if (this._reconnectTimer !== null) {
          clearTimeout(this._reconnectTimer);
          this._reconnectTimer = null;
        }
      });
      window.addEventListener('pageshow', event => {
        const departed = (this._lifecycle.presence === 'departed');
        this._lifecycle = connectionStep(this._lifecycle, {type: 'show'});
        if (event.persisted && departed && this._lifecycle.reconnect && this._reconnectTimer === null) {
          if (this._webSocket && this._webSocket.readyState !== WebSocket.CLOSED) {
            // pagehide made the old Bridge terminal. Retire a restored live
            // transport before opening another; its close handler owns cleanup
            // and the normal fresh-authority reconnect. CLOSING already waits.
            if (this._webSocket.readyState === WebSocket.OPEN || this._webSocket.readyState === WebSocket.CONNECTING) {
              this._clearPendingSends();
              this._webSocket.close();
            }
            return;
          }
          const needsClose = connectionOpened(this._lifecycle) || connectionReady(this._lifecycle);
          this._lifecycle = connectionStep(this._lifecycle, {type: 'reset-lifecycle'});
          if (needsClose) this._dispatcher.dispatchEvent(this._createEvent('close'));
          this.connect();
        }
      });
    }
    this._webSocketsSupported = wsEnabled && window.WebSocket !== undefined;
    this._reloadKey = RELOAD_KEY + serverRootPath;
    this._transportKey = TRANSPORT_KEY + serverRootPath;
    // A page that fell back to long polling and was told to reload starts with it.
    if (!this._guarded) {
      const storage = sessionStore();
      try {
        if (storage !== null && storage.getItem(this._transportKey) === 'long-polling')
          this._lifecycle = connectionStep(this._lifecycle, {type: 'prefer-long-polling'});
      } catch (_) {}
    }
    // On long polling a send can say whether the server took the message.
    /** @type {?function(string, function(boolean)=): *} */
    this._send = null;
    this._clearPendingSends = () => {};
    this._dispatcher = window.document.createDocumentFragment();
  }

  get dispatcher() { return this._dispatcher }
  get authenticationPending() { return authenticationPending(this._lifecycle) }

  /**
   * @param {string} type
   * @private
   * @return Event
   */
  _createEvent(type) {
    if (typeof Event === "function") {
      return new Event(type);
    } else {
      let event = document.createEvent('Event');
      event.initEvent(type, false, false);
      return event
    }
  }

  /**
   * @param {ConnectionType} connectionType
   * @private
   */
  _connectUsingConnectionType(connectionType) {
    this._retire();
    this._retire = () => {};
    switch (connectionType) {
      case ConnectionType.LONG_POLLING:
        if (this._guarded) this._onError();
        else this._connectUsingLongPolling();
        break;
      case ConnectionType.WEB_SOCKET:
        this._webSocketsSupported
          ? this._connectUsingWebSocket()
          : (this._guarded ? this._onError() : this._connectUsingLongPolling());
        break;
    }
  }

  /** @private */
  _connectUsingWebSocket() {

    this._clearPendingSends();

    let messages = []; // Message processing queue
    let url = (this._useSSL ? "wss://" : "ws://") + this._hostPort;
    let path = this._serverRootPath + `bridge/web-socket/${this._sessionId}`;
    let uri = url + path;
    if (this._guarded) {
      // Keep application routing separate from the transport endpoint. Read the
      // current location on every reconnect, including history/query changes.
      // Location.pathname is already URL-encoded. Normalize the configured
      // mount the same way before comparing/slicing (spaces, Unicode, or an
      // already-encoded mount), without changing legacy socket URL construction.
      const normalizeEscapes = value => value.replace(/%[0-9a-f]{2}/gi, escape => escape.toUpperCase());
      const mount = normalizeEscapes(new URL(this._serverRootPath, url).pathname).replace(/\/$/, '');
      const pathname = this._location.pathname || '/';
      const comparablePathname = normalizeEscapes(pathname);
      if (mount && comparablePathname !== mount && !comparablePathname.startsWith(mount + '/')) {
        this._onError();
        return;
      }
      const applicationPath = (pathname.slice(mount.length) || '/') + (this._location.search || '');
      uri += '?__spoonbill_location=' + encodeURIComponent(applicationPath);
    }

    let protocols = null;

    // Some servers do not echo Sec-WebSocket-Protocol; allow disabling negotiation.
    if (this._webSocketProtocolsEnabled) {
      protocols = [ 'json' ];
      if (this._webSocketCompressionEnabled && typeof CompressionStream != 'undefined') {
        protocols.push('json-deflate');
      }
    }

    /** @type {Promise} */
    this._processing = null;
    this._textEncoder = new TextEncoder();
    this._webSocket = protocols ? new WebSocket(uri, protocols) : new WebSocket(uri);
    this._lifecycle = connectionStep(this._lifecycle, {type: 'clear-delivery'});
    this._webSocket.binaryType = 'blob';
    // Cache typed reference; protocol is negotiated once per connection.
    const webSocketWithProtocol = /** @type {{protocol: string}} */ (this._webSocket);
    const sendingSocket = this._webSocket;
    const epoch = {};
    const isCurrent = () => currentTransport(this._lifecycle, epoch) && sendingSocket === this._webSocket;
    // Compression must preserve callback order: an older user action cannot
    // cross a departure/revalidation request. Each physical socket owns a
    // bounded, releasable queue; a replacement never waits for its compressor.
    const pendingSends = [];
    let senderPhase = 'idle';
    const clearPendingSends = () => {
      senderPhase = socketSenderStep(senderPhase, 'close');
      for (const item of pendingSends.splice(0)) { item.message = null; item.resolve(); }
    };
    this._clearPendingSends = clearPendingSends;
    const canSend = () => senderPhase !== 'closed' && !authenticationPending(this._lifecycle) &&
      isCurrent() && sendingSocket.readyState === WebSocket.OPEN;
    const drainSends = async () => {
      if (senderPhase !== 'idle') return;
      senderPhase = socketSenderStep(senderPhase, 'start');
      try {
        while (pendingSends.length && canSend()) {
          const item = pendingSends.shift();
          try {
            let blob = new Blob([this._textEncoder.encode(item.message)]);
            item.message = null;
            if (webSocketWithProtocol.protocol == 'json-deflate') {
              const stream = /** @type {{stream: function(): *}} */ (blob)
                .stream().pipeThrough(new CompressionStream('deflate-raw'));
              blob = await new Response(stream).blob();
            }
            if (canSend()) sendingSocket.send(blob);
          } finally { item.message = null; item.resolve(); }
        }
        if (!canSend()) clearPendingSends();
      } catch (_) {
        clearPendingSends();
        sendingSocket.close();
      } finally { senderPhase = socketSenderStep(senderPhase, 'finish'); }
    };
    this._send = message => {
      if (!canSend()) return Promise.resolve();
      if (pendingSends.length >= MAX_PENDING_SENDS) {
        clearPendingSends();
        sendingSocket.close();
        return Promise.resolve();
      }
      return new Promise(resolve => {
        pendingSends.push({message, resolve});
        drainSends();
      });
    };
    this._lifecycle = connectionStep(this._lifecycle, {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch});

    this._webSocket.addEventListener('open', (event) => {
      if (isCurrent()) this._onOpen();
    });
    this._webSocket.addEventListener('close', (event) => {
      clearPendingSends();
      if (isCurrent()) this._onClose();
    });
    this._webSocket.addEventListener('error', (event) => {
      clearPendingSends();
      if (isCurrent()) this._onError();
    });

    let processMessage = async (data) => {
      if (!isCurrent()) return;
      if (data instanceof Blob) {
        if (webSocketWithProtocol.protocol == 'json-deflate') {
          let stream = /** @type {{stream: function(): *}} */ (data)
            .stream()
            .pipeThrough(new DecompressionStream('deflate-raw'));
          data = await new Response(stream).blob();
        }

        // Check is Blob.text supported
        if(data.text) {
          data = await data.text();
          if (isCurrent()) this._onMessage(data);
        } else {
          const text = await new Promise((resolve, reject) => {
            const reader = new FileReader();
            reader.onload = () => resolve(reader.result);
            reader.onerror = () => reject(reader.error || new Error('Unable to decode websocket frame'));
            reader.onabort = () => reject(new Error('Websocket frame decoding aborted'));
            reader.readAsText(data);
          });
          if (isCurrent()) this._onMessage(text);
        }
      } else if (data instanceof ArrayBuffer) {
        const decoder = typeof TextDecoder === 'undefined' ? null : new TextDecoder();
        this._onMessage(decoder ? decoder.decode(new Uint8Array(data)) : String(data));
      } else {
        this._onMessage(String(data));
      }
    }

    let processing = null;
    let tryProcessMessage = async () => {
      if (processing || messages.length == 0) {
        return false;
      }

      const drain = (async () => {
        while (messages.length > 0) {
          const message = messages.shift();
          await processMessage(message);
        }
      })();
      processing = this._processing = drain.catch(() => {
        messages.length = 0;
        if (isCurrent()) {
          this._onError();
          sendingSocket.close();
        }
      }).finally(() => {
        if (this._processing === processing) this._processing = null;
        processing = null;
      });
    }

    this._webSocket.addEventListener('message', (event) => {
      if (!isCurrent()) return;
      messages.push(event.data);
      tryProcessMessage();
    });

    // Application query values need not be suitable for diagnostics.
    console.log(`Trying to open connection to ${url + path} using WebSocket`);
  }

  /**
   * Long polling that resumes. Every request names the page and the last frame
   * that it applied, and the server answers with exactly the next one. A
   * failed poll is not a closed connection: the page polls again after the
   * back-off and keeps its bridge. Everything uncertain ends in a reload.
   * @private
   */
  _connectUsingLongPolling() {

    this._clearPendingSends();
    const epoch = {};
    this._processing = null;
    const isCurrent = () => currentTransport(this._lifecycle, epoch);

    let url = (this._useSSL ? "https://" : "http://") + this._hostPort;
    let path = this._serverRootPath + `bridge/long-polling/${this._sessionId}/`;
    let uriPrefix = url + path;
    const cursor = () => `page=${this._lifecycle.page}&seen=${this._lifecycle.seen}`;

    // The poll that this transport waits for and its deadline, the timer of a
    // poll that waits out its back-off, and the request of the heartbeat in
    // flight with its listener: handles only. An answer to a request that was
    // given up changes nothing.
    let pending = null;
    let deadline = null;
    let retry = null;
    let heartbeat = null;

    const poll = () => {
      if (!isCurrent()) return;
      const request = new XMLHttpRequest();
      pending = request;
      deadline = setTimeout(() => {
        if (pending !== request || !isCurrent()) return;
        pending = null;
        request.abort();
        failed();
      }, this._pollDeadline);
      request.addEventListener('readystatechange', () => {
        if (request.readyState !== 4 || pending !== request || !isCurrent()) return;
        pending = null;
        clearTimeout(deadline);
        answered(request);
      });
      request.open('GET', uriPrefix + 'subscribe?' + cursor(), true);
      request.send('');
    };

    const answered = request => {
      const body = request.responseText;
      const kind = request.status === 200 ? frameKind(body) : null;
      switch (pollAnswer(this._lifecycle, request.status, request.getResponseHeader('spoonbill-frame'),
          request.getResponseHeader('spoonbill-dom'), kind)) {
        case 'apply': {
          resumed();
          if (!isCurrent()) return;
          // Applied means applied: a frame that the bridge could not apply reloads.
          // The bridge may change the lifecycle while it applies, so it is read after.
          const applied = this._deliver(body);
          const {state, next} = afterFrame(this._lifecycle, applied, kind === 'dom');
          this._lifecycle = state;
          return next === 'poll' ? poll() : this.reloadPage();
        }
        case 'empty':
          resumed();
          return poll();
        case 'failed':
          return failed();
        default:
          // The reload frame, another status, or numbers that do not fit.
          return this.reloadPage();
      }
    };

    // Any answer ends an interruption.
    const resumed = () => {
      const shown = noticeShown(this._lifecycle);
      this._lifecycle = connectionStep(this._lifecycle, {type: 'poll-answered'});
      if (shown) this._dispatcher.dispatchEvent(this._createEvent('resume'));
      this._onReady();
    };

    // The link is interrupted: the notice shows from the second failure in a
    // row. `type` says what failed: a poll, or a message (a heartbeat), which
    // leaves the mark of a replaced poll alone.
    const linkFailed = type => {
      const shown = noticeShown(this._lifecycle);
      this._lifecycle = connectionStep(this._lifecycle, {type});
      if (!shown && noticeShown(this._lifecycle)) this._dispatcher.dispatchEvent(this._createEvent('interrupt'));
    };

    const failed = () => {
      // A transport that was replaced counts nothing.
      if (!isCurrent()) return;
      linkFailed('poll-failed');
      retry = setTimeout(() => { retry = null; poll(); }, pollDelay(this._lifecycle));
    };

    /** @type {function(string)} */
    let publish = (data, onAnswer) => {
      if (!isCurrent()) return false;

      let onReadyStateChange = (event) => {
        let request = event.target;
        if (request.readyState !== 4)
          return;
        if (heartbeat !== null && heartbeat.request === request) heartbeat = null;
        // Whether the server took the message, for a heartbeat.
        const sent = messageSent(request.status);
        if (onAnswer) onAnswer(sent);
        if (!isCurrent())
          return;
        if (repollsAfterMessage(this._lifecycle, sent)) {
          this._lifecycle = connectionStep(this._lifecycle, {type: 'poll-replaced'});
          this._repoll();
        }
        switch (request.status) {
          case 0:
          case 400:
            this._onError();
            break;
        }
      };

      let request = new XMLHttpRequest();

      request.open('POST', uriPrefix + 'publish?' + cursor(), true);
      request.setRequestHeader("Content-Type", "application/json");
      request.addEventListener('readystatechange', onReadyStateChange);
      request.send(data);
      // Only a heartbeat has an answer callback.
      if (onAnswer) heartbeat = {request, listener: onReadyStateChange};
      return true;
    }

    this._lifecycle = connectionStep(this._lifecycle, {type: 'install', transport: ConnectionType.LONG_POLLING, epoch});
    this._send = publish;
    this._linkFailed = () => { if (isCurrent()) linkFailed('message-failed'); };
    this._retire = () => {
      clearTimeout(retry);
      retry = null;
      if (pending === null) return;
      const request = pending;
      pending = null;
      clearTimeout(deadline);
      request.abort();
    };
    this._abortHeartbeat = () => {
      if (heartbeat === null) return;
      const {request, listener} = heartbeat;
      heartbeat = null;
      request.removeEventListener('readystatechange', listener);
      request.abort();
    };
    this._repoll = () => {
      if (!isCurrent()) return;
      // A poll that waits out its back-off goes now, and only once.
      if (retry !== null) {
        clearTimeout(retry);
        retry = null;
        poll();
        return;
      }
      if (pending === null) return;
      const request = pending;
      pending = null;
      clearTimeout(deadline);
      request.abort();
      poll();
    };

    poll();
    // Treat long-polling as connected immediately so Spoonbill can attach listeners
    // before the first subscribe response arrives.
    this._onOpen();
    console.log(`Trying to open connection to ${uriPrefix} using long polling`);
  }

  /** @return {string} The page id of this document. */
  get page() { return this._lifecycle.page; }

  /** @return {boolean} The connection runs on long polling. */
  get longPolling() { return this._lifecycle.transport === ConnectionType.LONG_POLLING; }

  /** @return {boolean} No poll failed since the last answer. */
  get linkUp() { return linkUp(this._lifecycle); }

  /** Gives up the poll that waits, and polls again with the same cursor. */
  repoll() { this._repoll(); }

  /**
   * Gives up the request of the heartbeat in flight, which the bridge wrote
   * off, so that requests do not pile up on a link that hangs. Its listener
   * goes first: an answer that comes all the same changes nothing.
   */
  abortHeartbeat() { this._abortHeartbeat(); }

  /**
   * Gives a frame to the bridge, and says whether the bridge applied it.
   * @param {string} data
   * @return {boolean}
   * @private
   */
  _deliver(data) {
    const event = this._createEvent('message');
    event.data = data;
    event.applied = true;
    this._dispatcher.dispatchEvent(event);
    return event.applied !== false;
  }

  /**
   * A reload that the framework starts for an unguarded page. The guard allows
   * one in its window for this tab; a second one shows the notice and waits for
   * the end of the window. Without sessionStorage the page reloads at once.
   */
  reloadPage() {
    // A page reloads once: another word to reload while one waits changes nothing.
    if (reloadStarted(this._lifecycle)) return;
    const storage = sessionStore();
    let wait = 0;
    // The note and the time of the last reload, each in a try of its own.
    try {
      if (storage !== null) {
        const note = transportNote(this._lifecycle);
        if (note === 'write') storage.setItem(this._transportKey, 'long-polling');
        else if (note === 'remove') storage.removeItem(this._transportKey);
      }
    } catch (_) {}
    try {
      if (storage !== null) {
        const stored = storage.getItem(this._reloadKey);
        wait = reloadWait(stored === null ? null : Number(stored), Date.now());
      }
    } catch (_) {
      wait = 0;
    }
    const shown = noticeShown(this._lifecycle);
    // From now on no transport is current: nothing is sent, polled or reconnected.
    this._lifecycle = connectionStep(this._lifecycle, {type: 'reload', wait, scheduleEpoch: {}});
    if (this._reconnectTimer !== null) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    this._clearPendingSends();
    if (!shown && noticeShown(this._lifecycle)) this._dispatcher.dispatchEvent(this._createEvent('interrupt'));
    const reload = () => {
      try { if (storage !== null) storage.setItem(this._reloadKey, String(Date.now())); } catch (_) {}
      window.location.reload();
    };
    // The timer is only the handle of the wait; the machine knows that the page waits.
    if (wait === 0) reload();
    else setTimeout(reload, wait);
  }

  /** @private */
  _onOpen() {
    console.log("Connection opened");
    if (connectionOpened(this._lifecycle)) {
      return;
    }
    let event = this._createEvent('open');
    this._lifecycle = connectionStep(this._lifecycle, {type: 'open', epoch: this._lifecycle.epoch});
    this._dispatcher.dispatchEvent(event);
    if (this._lifecycle.transport !== ConnectionType.LONG_POLLING && !this._guarded) {
      this._onReady();
    }
  }

  /** Guarded application readiness follows its authorized DOM baseline. */
  applicationReady() {
    this._onReady();
  }

  /** @private */
  _onReady() {
    if (connectionReady(this._lifecycle)) {
      return;
    }
    let event = this._createEvent('ready');
    this._lifecycle = connectionStep(this._lifecycle, {type: 'ready', epoch: this._lifecycle.epoch});
    this._dispatcher.dispatchEvent(event);
  }

  /** @private */
  _onError() {
    console.log('Connection error');
    let event = this._createEvent('error');
    this._dispatcher.dispatchEvent(event);
  }

  /** @private */
  async _onClose() {
    const disconnectedSocket = this._webSocket;
    const disconnectedEpoch = this._lifecycle.epoch;
    console.log('Connection closed');
    if (this._processing) {
      console.log('Await processing last message')
      await this._processing;
    }
    if (this._webSocket !== disconnectedSocket || this._lifecycle.epoch !== disconnectedEpoch) return;

    // Allow reconnect to re-emit open and reinitialize the bridge.
    this._lifecycle = connectionStep(this._lifecycle, {type: 'close', epoch: disconnectedEpoch});
    let event = this._createEvent('close');
    this._dispatcher.dispatchEvent(event);
    if (this._lifecycle.reconnect) {
      // A replacement can commit before its fixed completion command arrives.
      // Use a bounded number of probes across the existing reconnect backoff:
      // a lost first response must not strand recovery after node failover.
      // Never replay the submitting application action.
      const recovering = connectionStep(this._lifecycle, {type: 'claim-recovery',
        enabled: this._replacementEnabled, epoch: disconnectedEpoch});
      if (recovering !== this._lifecycle) {
        this._lifecycle = recovering;
        await this._recoverReplacement();
      }
      if (this._lifecycle.reconnect && !(this._lifecycle.presence === 'departed') && this._webSocket === disconnectedSocket &&
          this._lifecycle.epoch === disconnectedEpoch &&
          (!this._replacementEnabled || this._reconnectTimer === null)) this.connect();
    }
  }

  /** Bounded discovery; cookies remain HttpOnly and no proof is retained. */
  _recoverReplacement() {
    if ((this._lifecycle.presence === 'departed') || (this._lifecycle.presence === 'navigating')) return Promise.resolve(false);
    let controller;
    let timeout;
    const cleanup = () => {
      clearTimeout(timeout);
      if (this._replacementAbort === controller) this._replacementAbort = null;
    };
    try {
      const origin = (this._useSSL ? 'https://' : 'http://') + this._hostPort;
      const endpoint = new URL(this._serverRootPath + 'auth/recover', origin);
      if (endpoint.origin !== origin) return Promise.resolve(false);
      controller = new AbortController();
      this._replacementAbort = controller;
      timeout = setTimeout(() => controller.abort(), 5000);
      // Network failure is an unavailable recovery attempt. Attach its handler
      // directly and release the timer in both settlement paths.
      return fetch(endpoint.toString(), {
        method: 'POST', credentials: 'same-origin', mode: 'same-origin', redirect: 'error', cache: 'no-store',
        headers: { 'Content-Type': 'text/plain' }, body: '', signal: controller.signal
      }).then(response => {
        cleanup();
        return response.status === 204;
      }, () => { cleanup(); return false; });
    } catch (_) {
      cleanup();
      return Promise.resolve(false);
    }
  }

  /**
   * @param {string} data
   * @private
   */
  _onMessage(data) {
    let event = this._createEvent('message');
    event.data = data;
    this._dispatcher.dispatchEvent(event);
  }

  /**
   * @param {string} data
   * @param {function(boolean)} [onAnswer] on long polling: whether the server took it
   * @return {boolean} whether a transport took the message
   */
  send(data, onAnswer) {
    if (authenticationPending(this._lifecycle) || !this._send) return false;
    return Boolean(this._send(data, onAnswer));
  }

  /** A message could not be sent: on long polling that is a failed poll. */
  messageFailed() { this._linkFailed(); }

  /** A framework reload has started; nothing is sent any more. */
  get reloadStarted() { return reloadStarted(this._lifecycle); }

  /** Deliver only an opaque completion handle over HTTP, then rebind the same view. */
  async recoverAuthentication(ceremonyId) {
    if (!this._replacementEnabled) throw new Error('Replacement recovery unavailable');
    return this.commitAuthentication(ceremonyId, true);
  }

  async commitAuthentication(completionId, recovery = false) {
    if (!this._guarded || this._lifecycle.transport !== ConnectionType.WEB_SOCKET ||
        typeof completionId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(completionId)) {
      throw new Error('Authentication handoff unavailable');
    }
    if (authenticationPending(this._lifecycle)) return;
    this._lifecycle = connectionStep(this._lifecycle, {type: 'begin-authentication'});
    this._clearPendingSends();
    const socket = this._webSocket;
    const epoch = this._lifecycle.epoch;
    try {
      const origin = (this._useSSL ? 'https://' : 'http://') + this._hostPort;
      const endpoint = new URL(this._serverRootPath + (recovery ? 'auth/recover' : 'auth/complete'), origin);
      if (endpoint.origin !== origin) throw new Error('Authentication origin mismatch');
      let delivered = false;
      for (let attempt = 0; attempt < 3 && !delivered; attempt += 1) {
        let response;
        const controller = new AbortController();
        const timeout = setTimeout(() => controller.abort(), 5000);
        try {
          response = await fetch(endpoint.toString(), {
            method: 'POST', credentials: 'same-origin', mode: 'same-origin',
            redirect: 'error', cache: 'no-store',
            headers: { 'Content-Type': 'text/plain' }, body: completionId,
            signal: controller.signal
          });
        } catch (_) {
          // Retrying this bounded handle never re-runs password or factor checks.
          if (attempt === 2) throw new Error('Authentication delivery unavailable');
          continue;
        } finally {
          clearTimeout(timeout);
        }
        if (response.status !== 204) throw new Error('Authentication delivery rejected');
        delivered = true;
      }
      if (socket !== this._webSocket || !currentTransport(this._lifecycle, epoch)) throw new Error('Authentication connection changed');
      // close is asynchronous, and final message processing can delay its
      // handler further. Preserve this exact socket's successful HTTP delivery
      // beyond the in-flight flag; its intentional rebind needs no discovery.
      this._lifecycle = connectionStep(this._lifecycle, {type: 'authentication-delivered', epoch});
      await this.disconnect(true);
    } finally {
      this._lifecycle = connectionStep(this._lifecycle, {type: 'finish-authentication', epoch});
    }
  }

  /**
   * @param {boolean} reconnect
   */
  async disconnect(reconnect = true) {
    this._lifecycle = connectionStep(this._lifecycle, {type: 'disconnect', reconnect, scheduleEpoch: {}});
    if (!reconnect && this._reconnectTimer !== null) {
      clearTimeout(this._reconnectTimer);
      this._reconnectTimer = null;
    }
    this._clearPendingSends();
    if (this._webSocket != null) {
        this._webSocket.close();
    } else {
        console.log("Disconnect allowed only for WebSocket connections")
    }
  }

  _scheduleConnection(connectionType) {
    const scheduleEpoch = this._lifecycle.scheduleEpoch;
    this._reconnectTimer = setTimeout(() => {
      if (!currentSchedule(this._lifecycle, scheduleEpoch)) return;
      this._reconnectTimer = null;
      if (!(this._lifecycle.presence === 'departed')) this._connectUsingConnectionType(connectionType);
    }, this._lifecycle.reconnectDelay);
  }

  connect() {

    if (connectionOpened(this._lifecycle))
      console.log('Reconnecting...');

    this._scheduleConnection(nextTransport(this._lifecycle, this._guarded));
    this._lifecycle = connectionStep(this._lifecycle, {type: 'advance-backoff'});
  }

}
