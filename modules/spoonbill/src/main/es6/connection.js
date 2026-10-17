const MIN_RECONNECT_TIMEOUT = 200;
const MAX_RECONNECT_TIMEOUT = 5000;
const MAX_PENDING_SENDS = 128;
const MAX_REPLACEMENT_RECOVERY_ATTEMPTS = 3;

/** @enum {number} */
export const ConnectionType = {
  WEB_SOCKET: 0,
  LONG_POLLING: 1
};

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
    this._reconnect = true;
    this._sessionId = sessionId;
    this._serverRootPath = serverRootPath;

    this._hostPort = location.host;
    this._useSSL = location.protocol === "https:";
    this._location = location;

    this._reconnectTimeout = MIN_RECONNECT_TIMEOUT;
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
    this._committingAuthentication = false;
    this._authenticationDeliveredSocket = null;
    this._replacementRecoveryAttempts = 0;
    this._replacementDeparted = false;
    this._replacementNavigationPending = false;
    this._replacementAbort = null;
    if (this._replacementEnabled) {
      // WebKit can close a socket and disallow new fetches before pagehide.
      // This hint blocks only discovery; ordinary reconnect remains available
      // if a canceled departure leaves this document alive.
      window.addEventListener('beforeunload', () => { this._replacementNavigationPending = true; });
      const resumeAfterCanceledDeparture = event => {
        if (event.isTrusted === true && !this._replacementDeparted && document.visibilityState !== 'hidden')
          this._replacementNavigationPending = false;
      };
      window.addEventListener('pointerdown', resumeAfterCanceledDeparture, true);
      window.addEventListener('keydown', resumeAfterCanceledDeparture, true);
      window.addEventListener('pagehide', () => {
        this._replacementDeparted = true;
        if (this._replacementAbort) this._replacementAbort.abort();
        if (this._reconnectTimer !== null) {
          clearTimeout(this._reconnectTimer);
          this._reconnectTimer = null;
        }
      });
      window.addEventListener('pageshow', event => {
        const departed = this._replacementDeparted;
        this._replacementDeparted = false;
        this._replacementNavigationPending = false;
        if (event.persisted && departed && this._reconnect && this._reconnectTimer === null) {
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
          const needsClose = this._wasConnected || this._wasReady;
          this._wasConnected = false;
          this._wasReady = false;
          if (needsClose) this._dispatcher.dispatchEvent(this._createEvent('close'));
          this.connect();
        }
      });
    }
    this._webSocketsSupported = wsEnabled && window.WebSocket !== undefined;
    this._connectionType = ConnectionType.LONG_POLLING;
    this._wasConnected = false;
    this._wasReady = false;

    /** @type {?ConnectionType} */
    this._selectedConnectionType = null;

    /** @type {?function(string)} */
    this._send = null;
    this._clearPendingSends = () => {};
    this._dispatcher = window.document.createDocumentFragment();
  }

  get dispatcher() { return this._dispatcher }
  get authenticationPending() { return this._committingAuthentication }

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
    this._authenticationDeliveredSocket = null;
    this._webSocket.binaryType = 'blob';
    // Cache typed reference; protocol is negotiated once per connection.
    const webSocketWithProtocol = /** @type {{protocol: string}} */ (this._webSocket);
    const sendingSocket = this._webSocket;
    // Compression must preserve callback order: an older user action cannot
    // cross a departure/revalidation request. Each physical socket owns a
    // bounded, releasable queue; a replacement never waits for its compressor.
    const pendingSends = [];
    let sending = false;
    let sendsClosed = false;
    const clearPendingSends = () => {
      sendsClosed = true;
      for (const item of pendingSends.splice(0)) { item.message = null; item.resolve(); }
    };
    this._clearPendingSends = clearPendingSends;
    const canSend = () => !sendsClosed && !this._committingAuthentication &&
      sendingSocket === this._webSocket && sendingSocket.readyState === WebSocket.OPEN;
    const drainSends = async () => {
      if (sending) return;
      sending = true;
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
      } finally { sending = false; }
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
    this._connectionType = ConnectionType.WEB_SOCKET;

    this._webSocket.addEventListener('open', (event) => {
      if (sendingSocket === this._webSocket) this._onOpen();
    });
    this._webSocket.addEventListener('close', (event) => {
      clearPendingSends();
      if (sendingSocket === this._webSocket) this._onClose();
    });
    this._webSocket.addEventListener('error', (event) => {
      clearPendingSends();
      if (sendingSocket === this._webSocket) this._onError();
    });

    let processMessage = async (data) => {
      if (sendingSocket !== this._webSocket) return;
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
          if (sendingSocket === this._webSocket) this._onMessage(data);
        } else {
          let reader = new FileReader();
          reader.onload = async () => {
            const text = /** @type {string} */ (reader.result);
            if (sendingSocket === this._webSocket) this._onMessage(text);
          }
          reader.readAsText(data);
        }
      } else if (data instanceof ArrayBuffer) {
        const decoder = typeof TextDecoder === 'undefined' ? null : new TextDecoder();
        this._onMessage(decoder ? decoder.decode(new Uint8Array(data)) : String(data));
      } else {
        this._onMessage(String(data));
      }
    }

    let tryProcessMessage = async () => {
      if (this._processing || messages.length == 0) {
        return false;
      }

      this._processing = new Promise(async (resolve) => {
        while (messages.length > 0) {
          const message = messages.shift();
          await processMessage(message);
        }

        this._processing = null;
        resolve()
      });
    }

    this._webSocket.addEventListener('message', (event) => {
      messages.push(event.data);
      tryProcessMessage();
    });

    // Application query values need not be suitable for diagnostics.
    console.log(`Trying to open connection to ${url + path} using WebSocket`);
  }

  /** @private */
  _connectUsingLongPolling() {

    let url = (this._useSSL ? "https://" : "http://") + this._hostPort;
    let path = this._serverRootPath + `bridge/long-polling/${this._sessionId}/`;
    let uriPrefix = url + path;

    /** @type {function(boolean)} */
    let subscribe = (firstTime) => {

      let onReadyStateChange = (event) => {
        let request = event.target;
        if (request.readyState !== 4)
          return;
        switch (request.status) {
          case 200:
            if (firstTime)
              this._onOpen();
            this._onReady();
            this._onMessage(request.responseText);
          case 503:
            // Poll again
            subscribe(false);
            break;
          default:
            this._onError();
            this._onClose();
            break;
        }
      };

      let request = new XMLHttpRequest();
      request.addEventListener('readystatechange', onReadyStateChange);
      request.open('GET', uriPrefix + 'subscribe', true);
      request.send('');
    };

    /** @type {function(string)} */
    let publish = (data) => {

      let onReadyStateChange = (event) => {
        let request = event.target;
        if (request.readyState !== 4)
          return;
        switch (request.status) {
          case 0:
          case 400:
            this._onError();
            break;
        }
      };

      let request = new XMLHttpRequest();

      request.open('POST', uriPrefix + 'publish', true);
      request.setRequestHeader("Content-Type", "application/json");
      request.addEventListener('readystatechange', onReadyStateChange);
      request.send(data);
    }

    this._connectionType = ConnectionType.LONG_POLLING;
    this._send = publish;

    subscribe(true);
    // Treat long-polling as connected immediately so Spoonbill can attach listeners
    // before the first subscribe response arrives.
    this._onOpen();
    console.log(`Trying to open connection to ${uriPrefix} using long polling`);
  }

  /** @private */
  _onOpen() {
    console.log("Connection opened");
    if (this._wasConnected) {
      return;
    }
    let event = this._createEvent('open');
    this._wasConnected = true;
    this._reconnectTimeout = MIN_RECONNECT_TIMEOUT;
    this._selectedConnectionType = this._connectionType;
    this._dispatcher.dispatchEvent(event);
    if (this._connectionType !== ConnectionType.LONG_POLLING && !this._guarded) {
      this._onReady();
    }
  }

  /** Guarded application readiness follows its authorized DOM baseline. */
  applicationReady() {
    this._onReady();
  }

  /** @private */
  _onReady() {
    if (this._wasReady) {
      return;
    }
    let event = this._createEvent('ready');
    this._wasReady = true;
    this._replacementRecoveryAttempts = 0;
    this._replacementNavigationPending = false;
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
    console.log('Connection closed');
    if (this._processing) {
      console.log('Await processing last message')
      await this._processing;
    }
    if (this._webSocket !== disconnectedSocket) return;

    // Allow reconnect to re-emit open and reinitialize the bridge.
    this._wasConnected = false;
    this._wasReady = false;
    let event = this._createEvent('close');
    this._dispatcher.dispatchEvent(event);
    if (this._reconnect) {
      // A replacement can commit before its fixed completion command arrives.
      // Use a bounded number of probes across the existing reconnect backoff:
      // a lost first response must not strand recovery after node failover.
      // Never replay the submitting application action.
      if (this._replacementEnabled && !this._replacementDeparted && !this._replacementNavigationPending &&
          !this._committingAuthentication &&
          (!this._authenticationDeliveredSocket || this._authenticationDeliveredSocket !== disconnectedSocket) &&
          this._replacementRecoveryAttempts < MAX_REPLACEMENT_RECOVERY_ATTEMPTS) {
        this._replacementRecoveryAttempts++;
        await this._recoverReplacement();
      }
      if (this._reconnect && !this._replacementDeparted && this._webSocket === disconnectedSocket &&
          (!this._replacementEnabled || this._reconnectTimer === null)) this.connect();
    }
  }

  /** Bounded discovery; cookies remain HttpOnly and no proof is retained. */
  _recoverReplacement() {
    if (this._replacementDeparted || this._replacementNavigationPending) return Promise.resolve(false);
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
   */
  send(data) {
    if (!this._committingAuthentication && this._send) this._send(data);
  }

  /** Deliver only an opaque completion handle over HTTP, then rebind the same view. */
  async recoverAuthentication(ceremonyId) {
    if (!this._replacementEnabled) throw new Error('Replacement recovery unavailable');
    return this.commitAuthentication(ceremonyId, true);
  }

  async commitAuthentication(completionId, recovery = false) {
    if (!this._guarded || this._connectionType !== ConnectionType.WEB_SOCKET ||
        typeof completionId !== 'string' ||
        !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(completionId)) {
      throw new Error('Authentication handoff unavailable');
    }
    if (this._committingAuthentication) return;
    this._committingAuthentication = true;
    this._clearPendingSends();
    const socket = this._webSocket;
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
      if (socket !== this._webSocket) throw new Error('Authentication connection changed');
      // close is asynchronous, and final message processing can delay its
      // handler further. Preserve this exact socket's successful HTTP delivery
      // beyond the in-flight flag; its intentional rebind needs no discovery.
      this._authenticationDeliveredSocket = socket;
      await this.disconnect(true);
    } finally {
      this._committingAuthentication = false;
    }
  }

  /**
   * @param {boolean} reconnect
   */
  async disconnect(reconnect = true) {
    this._reconnect = reconnect;
    if (!reconnect && this._replacementEnabled && this._reconnectTimer !== null) {
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
    this._reconnectTimer = setTimeout(() => {
      this._reconnectTimer = null;
      if (!this._replacementDeparted) this._connectUsingConnectionType(connectionType);
    }, this._reconnectTimeout);
  }

  connect() {

    if (this._wasConnected)
      console.log('Reconnecting...');

    if (this._selectedConnectionType !== null) {
      let ct = this._selectedConnectionType;
      this._scheduleConnection(ct);
    } else {
      switch (this._connectionType) {
        case ConnectionType.WEB_SOCKET:
          this._scheduleConnection(this._guarded ? ConnectionType.WEB_SOCKET : ConnectionType.LONG_POLLING);
          break;
        case ConnectionType.LONG_POLLING:
          this._scheduleConnection(ConnectionType.WEB_SOCKET);
          break;
      }
    }

    this._reconnectTimeout = Math.min(this._reconnectTimeout * 2, MAX_RECONNECT_TIMEOUT);
  }

}
