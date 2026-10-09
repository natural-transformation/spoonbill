import { Spoonbill, CallbackType, PropertyType } from './spoonbill.js';
import { Connection } from './connection.js';
import { SensitiveRegions } from './sensitive.js';

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
    this._viewConnection = null;
    this._viewRevision = null;
    this._viewRecovering = false;
    this._applyingView = false;
    this._callbacksReady = config['auth'] !== true;
    this._guarded = config['auth'] === true;
    this._sensitiveNavigation = 0;
    this._messageHandler = this._onMessage.bind(this);
    this._sensitive = new SensitiveRegions(
      message => this._onCallback(CallbackType.SENSITIVE_ACK, message),
      message => this._onCallback(CallbackType.SENSITIVE_CLEARED, message),
      id => this._spoonbill.element(id),
      counter => {
        if (this._guarded) this._onCallback(CallbackType.SENSITIVE_DEPARTURE, String(counter));
      });

    connection.dispatcher.addEventListener("message", this._messageHandler);

    let interval = parseInt(config['heartbeat']['interval'], 10);

    if (interval > 0) {
      if (config['heartbeat']['limit']) {
        this._heartbeatLimit = parseInt(config['heartbeat']['limit'], 10)
        this._awaitingHeartbeat = 0
      }

      this._intervalId = setInterval(() => {
        // The bounded cookie handoff temporarily suspends socket callbacks.
        if (this._connection.authenticationPending) return;
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
    if (type === CallbackType.HISTORY) this._sensitive.clearAll();
    const userEvent = type === CallbackType.DOM_EVENT || type === CallbackType.CUSTOM_CALLBACK || type === CallbackType.HISTORY;
    if (this._guarded && userEvent) {
      if (this._sensitive.terminal) return;
      // Real history changes keep their own ordered server barrier. Other user
      // actions wait for both barriers so one-read disclosure cannot be consumed
      // while its presentation remains fenced. Unguarded apps have no barrier.
      if (type !== CallbackType.HISTORY &&
          (this._sensitive.departurePending || this._sensitive.navigationPending)) return;
    }
    if ((!this._callbacksReady || this._viewRecovering || this._applyingView) && userEvent) {
      if (type === CallbackType.HISTORY && this._guarded) {
        this._sensitive.blockNavigation();
        // This route change cannot use the partial/unready view's revision.
        // Keep its old sensitive and action fences; a fresh authorized socket
        // reads the browser's current location and supplies a new baseline.
        if (!this._viewRecovering) {
          this._viewRecovering = true;
          if (!this._connection.authenticationPending) this._connection.disconnect(true);
        }
      }
      return;
    }
    if (type === CallbackType.HISTORY && this._guarded) {
      if (this._sensitiveNavigation >= Number.MAX_SAFE_INTEGER) {
        this._sensitive.blockNavigation();
        this._connection.disconnect(true);
        return;
      }
      this._sensitive.beginNavigation(++this._sensitiveNavigation);
    }
    let message = this._viewConnection && userEvent
      ? JSON.stringify([CallbackType.VIEW_EVENT, this._viewConnection + ':' + this._viewRevision + ':' + type + ':' + (args === undefined ? '' : args)])
      : JSON.stringify(args !== undefined ? [type, args] : [type]);
    if (protocolDebugEnabled)
      console.log('<- callback', type);
    this._connection.send(message);
  }

  _applyView(update) {
    const previouslyApplying = this._applyingView;
    this._applyingView = true;
    try {
      // Native custom-element lifecycle callbacks run synchronously inside DOM
      // mutations. Drop their user actions: the partial view has no valid
      // revision, and replaying or retagging them could target replacement nodes.
      // RPC responses and heartbeats remain independent of this user-event gate.
      update();
    } finally {
      this._applyingView = previouslyApplying;
    }
  }

  _modifyDom(commands) {
    this._sensitive.beforePatch(commands);
    this._spoonbill.modifyDom(commands);
    this._sensitive.reconcile();
  }

  _onMessage(event) {
    let commands = /** @type {Array} */ (JSON.parse(event.data));
    let pCode = commands.shift();
    if (protocolDebugEnabled)
      console.log('-> procedure', pCode);
    let k = this._spoonbill;
    try {
      switch (pCode) {
        case 0: k.setEventCounter.apply(k, commands); break;
        case 1:
          this._sensitive.clearAll();
          this._connection.disconnect(false);
          window.location.reload();
          break;
        case 2: k.listenEvent.apply(k, commands); break;
        case 3: k.extractProperty.apply(k, commands); break;
        case 4: this._modifyDom(commands); break;
        case 5: k.focus.apply(k, commands); break;
        case 6: {
          // The router may emit an equivalent raw Unicode/space URL on every
          // render. Compare browser-normalized URLs before clearing a region.
          const currentUrl = new URL(window.location.href ||
            window.location.protocol + '//' + window.location.host + window.location.pathname + (window.location.search || ''));
          if (new URL(commands[0], currentUrl).href !== currentUrl.href) this._sensitive.clearAll();
          k.changePageUrl.apply(k, commands);
          break;
        }
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
        case 18:
          this._sensitive.clearAll();
          this._connection.commitAuthentication(commands[0]).catch(() => {
            console.error('Authentication handoff failed');
            // A lost reply may already have installed the cookie. A clean
            // bootstrap validates it; credentials and submit events are not replayed.
            this._connection.disconnect(false);
            window.location.reload();
          });
          break;
        case 19: {
          const [epoch, connection, revision, dom] = commands;
          if (this._viewConnection !== null || typeof epoch !== 'string' || !/^(0|[1-9][0-9]{0,18})$/.test(epoch) ||
              typeof connection !== 'string' || !/^[0-9a-f-]{36}$/.test(connection) || revision !== '0' ||
              !Array.isArray(dom) || dom[0] !== 4) throw new Error('Invalid view baseline');
          this._applyView(() => {
            this._sensitive.clearAll();
            k.resetView(dom.slice(1));
            this._viewConnection = connection;
            this._viewRevision = revision;
          });
          break;
        }
        case 20: {
          const [connection, previous, next, dom] = commands;
          // A replaced connection's output is obsolete, including decoded frames
          // that were queued before its close. Never apply it to a newer view.
          if (this._viewConnection !== connection) break;
          if (typeof previous !== 'string' || typeof next !== 'string' ||
              !/^(0|[1-9][0-9]*)$/.test(previous) || !/^[1-9][0-9]*$/.test(next) ||
              !Number.isSafeInteger(Number(next)) || !Array.isArray(dom) || dom[0] !== 4)
            throw new Error('Invalid view revision');
          if (Number(next) <= Number(this._viewRevision)) break;
          if (previous !== this._viewRevision || Number(next) !== Number(previous) + 1)
            throw new Error('View revision mismatch');
          this._applyView(() => {
            this._modifyDom(dom.slice(1));
            this._viewRevision = next;
          });
          break;
        }
        case 21:
          if (typeof commands[0] !== 'boolean' || commands[0] !== (this._viewConnection !== null))
            throw new Error('View baseline is not ready');
          this._callbacksReady = true;
          this._connection.applicationReady();
          break;
        case 22:
          if (this._viewConnection === null || commands[0] === this._viewConnection)
            this._sensitive.show.apply(this._sensitive, commands);
          break;
        case 23: this._sensitive.clear.apply(this._sensitive, commands); break;
        case 24:
          if (commands.length !== 1 || !Number.isSafeInteger(commands[0]) || commands[0] <= 0)
            throw new Error('Invalid sensitive navigation barrier');
          this._sensitive.completeNavigation(commands[0]);
          break;
        case 25:
          if (commands.length !== 1 || !Number.isSafeInteger(commands[0]) || commands[0] <= 0)
            throw new Error('Invalid sensitive departure barrier');
          this._sensitive.completeDeparture(commands[0]);
          break;
        default: console.error(`Procedure ${pCode} is undefined`);
      }
    } catch (error) {
      if (pCode === 22 || pCode === 23) {
        this._sensitive.clearAll(false);
        console.error('Sensitive presentation failed');
        return;
      }
      if (pCode === 19 || pCode === 20 || pCode === 21 || pCode === 24 || pCode === 25) {
        console.error('Spoonbill view recovery failed');
        this._viewRecovering = true;
        this._sensitive.blockNavigation();
        this._connection.disconnect(true);
        return;
      }
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
    this._sensitive.destroy();
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
