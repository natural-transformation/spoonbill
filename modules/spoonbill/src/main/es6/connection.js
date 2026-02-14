const MIN_RECONNECT_TIMEOUT = 200;
const MAX_RECONNECT_TIMEOUT = 5000;

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

    this._reconnectTimeout = MIN_RECONNECT_TIMEOUT;
    /** @type {?WebSocket} */
    this._webSocket = null;
    /** @type {?TextEncoder} */
    this._textEncoder = null;
    const wsEnabled = !(options && options['ws'] === false);
    this._webSocketProtocolsEnabled = !(options && options['wsp'] === false);
    this._webSocketsSupported = wsEnabled && window.WebSocket !== undefined;
    this._connectionType = ConnectionType.LONG_POLLING;
    this._wasConnected = false;
    this._wasReady = false;

    /** @type {?ConnectionType} */
    this._selectedConnectionType = null;

    /** @type {?function(string)} */
    this._send = null;
    this._dispatcher = window.document.createDocumentFragment();
  }

  get dispatcher() { return this._dispatcher }

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
        this._connectUsingLongPolling();
        break;
      case ConnectionType.WEB_SOCKET:
        this._webSocketsSupported
          ? this._connectUsingWebSocket()
          : this._connectUsingLongPolling();
        break;
    }
  }

  /** @private */
  _connectUsingWebSocket() {

    let messages = []; // Message processing queue
    let url = (this._useSSL ? "wss://" : "ws://") + this._hostPort;
    let path = this._serverRootPath + `bridge/web-socket/${this._sessionId}`;
    let uri = url + path;

    let protocols = null;

    // Some servers do not echo Sec-WebSocket-Protocol; allow disabling negotiation.
    if (this._webSocketProtocolsEnabled) {
      protocols = [ 'json' ];
      if (typeof CompressionStream != 'undefined') {
        protocols.push('json-deflate');
      }
    }

    /** @type {Promise} */
    this._processing = null;
    this._textEncoder = new TextEncoder();
    this._webSocket = protocols ? new WebSocket(uri, protocols) : new WebSocket(uri);
    this._webSocket.binaryType = 'blob';
    // Cache typed reference; protocol is negotiated once per connection.
    const webSocketWithProtocol = /** @type {{protocol: string}} */ (this._webSocket);
    this._send = async (message) => {
      let blob = new Blob([this._textEncoder.encode(message)]);
      if (webSocketWithProtocol.protocol == 'json-deflate') {
        let stream = /** @type {{stream: function(): *}} */ (blob)
          .stream()
          .pipeThrough(new CompressionStream('deflate-raw'))
        blob = await new Response(stream).blob();
      }
      this._webSocket.send(blob);
    }
    this._connectionType = ConnectionType.WEB_SOCKET;

    this._webSocket.addEventListener('open', (event) => this._onOpen());
    this._webSocket.addEventListener('close', (event) => this._onClose());
    this._webSocket.addEventListener('error', (event) => this._onError());

    let processMessage = async (data) => {
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
          this._onMessage(data);
        } else {
          let reader = new FileReader();
          reader.onload = async () => {
            const text = /** @type {string} */ (reader.result);
            this._onMessage(text);
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

    console.log(`Trying to open connection to ${uri} using WebSocket`);
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
    if (this._connectionType !== ConnectionType.LONG_POLLING) {
      this._onReady();
    }
  }

  /** @private */
  _onReady() {
    if (this._wasReady) {
      return;
    }
    let event = this._createEvent('ready');
    this._wasReady = true;
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
    console.log('Connection closed');
    if (this._processing) {
      console.log('Await processing last message')
      await this._processing;
    }

    // Allow reconnect to re-emit open and reinitialize the bridge.
    this._wasConnected = false;
    this._wasReady = false;
    let event = this._createEvent('close');
    this._dispatcher.dispatchEvent(event);
    if (this._reconnect) {
      this.connect();
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
    this._send(data);
  }

  /**
   * @param {boolean} reconnect
   */
  async disconnect(reconnect = true) {
    this._reconnect = reconnect;
    if (this._webSocket != null) {
        this._webSocket.close();
    } else {
        console.log("Disconnect allowed only for WebSocket connections")
    }
  }

  connect() {

    if (this._wasConnected)
      console.log('Reconnecting...');

    if (this._selectedConnectionType !== null) {
      let ct = this._selectedConnectionType;
      setTimeout(
        () => this._connectUsingConnectionType(ct),
        this._reconnectTimeout
      );
    } else {
      switch (this._connectionType) {
        case ConnectionType.WEB_SOCKET:
          setTimeout(
            () => this._connectUsingConnectionType(ConnectionType.LONG_POLLING),
            this._reconnectTimeout
          );
          break;
        case ConnectionType.LONG_POLLING:
          setTimeout(
            () => this._connectUsingConnectionType(ConnectionType.WEB_SOCKET),
            this._reconnectTimeout
          );
          break;
      }
    }

    this._reconnectTimeout = Math.min(this._reconnectTimeout * 2, MAX_RECONNECT_TIMEOUT);
  }

}

