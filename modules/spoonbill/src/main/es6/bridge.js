import { Spoonbill, CallbackType, PropertyType } from './spoonbill.js';
import { Connection } from './connection.js';

const ProtocolDebugEnabledKey = "$bridge.protocolDebugEnabled";

var protocolDebugEnabled = window.localStorage.getItem(ProtocolDebugEnabledKey) === 'true';

export class Bridge {

  /**
   * @param {Connection} connection
   */
  constructor(config, connection) {
    this._spoonbill = new Spoonbill(config, this._onCallback.bind(this));
    this._spoonbill.registerRoot(document.children[0]);
    this._connection = connection;
    this._messageHandler = this._onMessage.bind(this);

    connection.dispatcher.addEventListener("message", this._messageHandler);

    let interval = parseInt(config['heartbeat']['interval'], 10);

    if (interval > 0) {
      if (config['heartbeat']['limit']) {
        this._heartbeatLimit = parseInt(config['heartbeat']['limit'], 10)
        this._awaitingHeartbeat = 0
      }

      this._intervalId = setInterval(() => {
        if (this._heartbeatLimit) {
          this._awaitingHeartbeat += 1

          if (this._awaitingHeartbeat === this._heartbeatLimit) {
            console.log('Too many lost heartbeats, reloading')
            this._connection.disconnect(false);
            window.location.reload();
          } else {
            this._onCallback(CallbackType.HEARTBEAT)
          }
        } else {
          this._onCallback(CallbackType.HEARTBEAT)
        }
      }, interval);
    }
  }

  /**
   * @param {CallbackType} type
   * @param {string} [args]
   */
  _onCallback(type, args) {
    let message = JSON.stringify(args !== undefined ? [type, args] : [type]);
    if (protocolDebugEnabled)
      console.log('<-', message);
    this._connection.send(message);
  }

  _onMessage(event) {
    if (protocolDebugEnabled)
      console.log('->', event.data);
    let commands = /** @type {Array} */ (JSON.parse(event.data));
    let pCode = commands.shift();
    let k = this._spoonbill;
    try {
      switch (pCode) {
        case 0: k.setEventCounter.apply(k, commands); break;
        case 1:
          this._connection.disconnect(false);
          window.location.reload();
          break;
        case 2: k.listenEvent.apply(k, commands); break;
        case 3: k.extractProperty.apply(k, commands); break;
        case 4: k.modifyDom(commands); break;
        case 5: k.focus.apply(k, commands); break;
        case 6: k.changePageUrl.apply(k, commands); break;
        case 7: k.uploadForm.apply(k, commands); break;
        case 8: k.reloadCss.apply(k, commands); break;
        case 9: break;
        case 10: k.evalJs.apply(k, commands); break;
        case 11: k.extractEventData.apply(k, commands); break;
        case 12: k.listFiles.apply(k, commands); break;
        case 13: k.uploadFile.apply(k, commands); break;
        case 14: k.resetForm.apply(k, commands); break;
        case 15: k.downloadFile.apply(k, commands); break;
        case 16: this._awaitingHeartbeat -= 1;break;
        case 17: k.resetEventCounters.apply(k, commands); break;
        default: console.error(`Procedure ${pCode} is undefined`);
      }
    } catch (error) {
      console.error(`Spoonbill bridge failed for procedure ${pCode}`, error);
      if (pCode === 3 && commands.length > 0) {
        // Ensure server-side extractProperty never hangs if the client throws.
        const descriptor = commands[0];
        this._onCallback(
          CallbackType.EXTRACT_PROPERTY_RESPONSE,
          `${descriptor}:${PropertyType.ERROR}:extractProperty failed`
        );
      } else if (pCode === 11 && commands.length > 0) {
        // Ensure server-side extractEventData never hangs if the client throws.
        const descriptor = commands[0];
        this._onCallback(
          CallbackType.EXTRACT_EVENT_DATA_RESPONSE,
          `${descriptor}:${JSON.stringify({})}`
        );
      }
    }
  }

  destroy() {
    clearInterval(this._intervalId);
    this._connection.dispatcher.removeEventListener("message", this._messageHandler);
    this._spoonbill.destroy();
  }
}

/** @param {boolean} value */
export function setProtocolDebugEnabled(value) {
  window.localStorage.setItem(ProtocolDebugEnabledKey, value.toString());
  protocolDebugEnabled = value;
}
