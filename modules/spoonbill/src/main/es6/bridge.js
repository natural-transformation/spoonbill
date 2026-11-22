import { Spoonbill } from './spoonbill.js';
import { Procedure, CallbackType, PropertyType } from './protocol.js';
import { Connection } from './connection.js';
import { SensitiveRegions } from './sensitive.js';
import {initialBridge, bridgeStep, callbackAdmission, validBaseline, patchDecision, validReadiness,
  acceptsSensitiveView, validBarrier, heartbeatTick, heartbeatAnswer} from './bridge-machine.js';

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
    // A long-polling bridge starts with the view of its page: its user events
    // carry the page id and the DOM revision that the page shows.
    this._lifecycle = initialBridge(config['auth'] === true, connection.longPolling ? connection.page : null);
    this._guarded = config['auth'] === true;
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
      this._heartbeatLimit = config['heartbeat']['limit'] ? parseInt(config['heartbeat']['limit'], 10) : 0;
      this._lifecycle = bridgeStep(this._lifecycle, {type: 'heartbeat-configured'});

      this._intervalId = setInterval(() => {
        // The bounded cookie handoff temporarily suspends socket callbacks.
        if (this._connection.authenticationPending) return;
        // The machine decides: a heartbeat, a re-poll, a reload, or nothing
        // once a reload has started. The heartbeat goes on while long polling
        // is interrupted: its reply answers the poll that the server holds,
        // so the notice hides soon after the link is back.
        const out = heartbeatTick(this._lifecycle, this._heartbeatLimit, this._connection.longPolling,
          this._connection.reloadStarted);
        this._lifecycle = out.state;
        if (out.failed) this._connection.messageFailed();
        switch (out.command) {
          case 'send':
            this._sendHeartbeat(out.seq);
            break;
          case 'repoll':
            console.log('Too many lost heartbeats, polling again');
            this._connection.repoll();
            break;
          case 'reload':
            console.log('Too many lost heartbeats, reloading');
            this._connection.disconnect(false);
            // A guarded page reloads in flows of its own; an unguarded page
            // passes the reload guard, as on the reload frame.
            if (this._guarded) window.location.reload();
            else this._connection.reloadPage();
            break;
        }
      }, interval);
    }
  }

  /**
   * One heartbeat. On long polling (`seq` is its number) the transport says
   * whether the server took it; one that could not be sent is a failed poll.
   * @param {?number} seq
   */
  _sendHeartbeat(seq) {
    const message = JSON.stringify([CallbackType.HEARTBEAT]);
    if (protocolDebugEnabled)
      console.log('<- callback', CallbackType.HEARTBEAT);
    const onAnswer = seq === null ? undefined : sent => {
      const out = heartbeatAnswer(this._lifecycle, seq, sent);
      this._lifecycle = out.state;
      if (out.failed) this._connection.messageFailed();
    };
    // A heartbeat that no transport took is not in flight.
    if (!this._connection.send(message, onAnswer) && seq !== null)
      this._lifecycle = heartbeatAnswer(this._lifecycle, seq, true).state;
  }

  /**
   * @param {CallbackType} type
   * @param {string} [args]
   */
  _onCallback(type, args) {
    if (type === CallbackType.HISTORY) this._sensitive.clearAll();
    const userEvent = type === CallbackType.DOM_EVENT || type === CallbackType.CUSTOM_CALLBACK || type === CallbackType.HISTORY;
    if (userEvent) {
      const admission = callbackAdmission(this._lifecycle, this._sensitive.lifecycle, this._guarded, type === CallbackType.HISTORY);
      if (admission === 'drop') return;
      if (admission === 'blocked-history') {
        this._sensitive.blockNavigation();
        // A partial/unready history event has no authorized revision to send.
        if (this._lifecycle.gate !== 'recovering') {
          this._lifecycle = bridgeStep(this._lifecycle, {type: 'recover'});
          if (!this._connection.authenticationPending) this._connection.disconnect(true);
        }
        return;
      }
      if (admission === 'exhausted') {
        this._sensitive.blockNavigation();
        this._connection.disconnect(true);
        return;
      }
      if (type === CallbackType.HISTORY && this._guarded) {
        this._lifecycle = bridgeStep(this._lifecycle, {type: 'begin-navigation'});
        this._sensitive.beginNavigation(this._lifecycle.navigationCounter);
      }
    }
    const view = this._lifecycle.view;
    let message = view && userEvent
      ? JSON.stringify([CallbackType.VIEW_EVENT, view.connection + ':' + view.revision + ':' + type + ':' + (args === undefined ? '' : args)])
      : JSON.stringify(args !== undefined ? [type, args] : [type]);
    if (protocolDebugEnabled)
      console.log('<- callback', type);
    this._connection.send(message);
  }

  _applyView(update) {
    const previousApplication = this._lifecycle.application;
    this._lifecycle = bridgeStep(this._lifecycle, {type: 'begin-application'});
    try {
      // Native custom-element lifecycle callbacks run synchronously inside DOM
      // mutations. Drop their user actions: the partial view has no valid
      // revision, and replaying or retagging them could target replacement nodes.
      // RPC responses and heartbeats remain independent of this user-event gate.
      update();
    } finally {
      this._lifecycle = bridgeStep(this._lifecycle, {type: 'restore-application', phase: previousApplication});
    }
  }

  /** @return {boolean} Every command found its node. */
  _modifyDom(commands) {
    this._sensitive.beforePatch(commands);
    const complete = this._spoonbill.modifyDom(commands);
    this._sensitive.reconcile();
    return complete;
  }

  _onMessage(event) {
    let commands = /** @type {Array} */ (JSON.parse(event.data));
    let pCode = commands.shift();
    if (protocolDebugEnabled)
      console.log('-> procedure', pCode);
    let k = this._spoonbill;
    try {
      switch (pCode) {
        case Procedure.SET_EVENT_COUNTER: k.setEventCounter.apply(k, commands); break;
        case Procedure.RELOAD:
          this._sensitive.clearAll();
          this._connection.disconnect(false);
          // A guarded page reloads in flows of its own. An unguarded page
          // passes the reload guard, on both transports.
          if (this._guarded) window.location.reload();
          else this._connection.reloadPage();
          break;
        case Procedure.LISTEN_EVENT: k.listenEvent.apply(k, commands); break;
        case Procedure.EXTRACT_PROPERTY: k.extractProperty.apply(k, commands); break;
        case Procedure.MODIFY_DOM:
          // The revision of the frame is the view's from the start of the
          // patch. The transport learns whether the frame was applied; a
          // long-polling page reloads after one that was not.
          this._lifecycle = bridgeStep(this._lifecycle, {type: 'dom-frame'});
          if (!this._modifyDom(commands)) event.applied = false;
          break;
        case Procedure.FOCUS: k.focus.apply(k, commands); break;
        case Procedure.CHANGE_PAGE_URL: {
          // The router may emit an equivalent raw Unicode/space URL on every
          // render. Compare browser-normalized URLs before clearing a region.
          const currentUrl = new URL(window.location.href ||
            window.location.protocol + '//' + window.location.host + window.location.pathname + (window.location.search || ''));
          if (new URL(commands[0], currentUrl).href !== currentUrl.href) this._sensitive.clearAll();
          k.changePageUrl.apply(k, commands);
          break;
        }
        case Procedure.UPLOAD_FORM: k.uploadForm.apply(k, commands); break;
        case Procedure.RELOAD_CSS: k.reloadCss.apply(k, commands); break;
        case Procedure.KEEP_ALIVE: break;
        case Procedure.EVAL_JS: k.evalJs.apply(k, commands); break;
        case Procedure.EXTRACT_EVENT_DATA: k.extractEventData.apply(k, commands); break;
        case Procedure.LIST_FILES: k.listFiles.apply(k, commands); break;
        case Procedure.UPLOAD_FILE: k.uploadFile.apply(k, commands); break;
        case Procedure.REST_FORM: k.resetForm.apply(k, commands); break;
        case Procedure.DOWNLOAD_FILE: k.downloadFile.apply(k, commands); break;
        case Procedure.HEARTBEAT: this._lifecycle = bridgeStep(this._lifecycle, {type: 'heartbeat-reply'}); break;
        case Procedure.RESET_EVENT_COUNTERS: k.resetEventCounters.apply(k, commands); break;
        case Procedure.COMPLETE_AUTHENTICATION:
          this._lifecycle = bridgeStep(this._lifecycle, {type: 'suspend'});
          this._sensitive.clearAll();
          this._connection.commitAuthentication(commands[0]).catch(() => {
            console.error('Authentication handoff failed');
            // A lost reply may already have installed the cookie. A clean
            // bootstrap validates it; credentials and submit events are not replayed.
            this._connection.disconnect(false);
            window.location.reload();
          });
          break;
        case Procedure.RECOVER_AUTHENTICATION:
          this._lifecycle = bridgeStep(this._lifecycle, {type: 'suspend'});
          this._sensitive.clearAll();
          this._connection.recoverAuthentication(commands[0]).catch(() => {
            console.error('Authentication recovery unavailable');
            this._connection.disconnect(false);
            window.location.reload();
          });
          break;
        case Procedure.RESET_VIEW: {
          const [epoch, connection, revision, dom] = commands;
          if (!validBaseline(this._lifecycle, epoch, connection, revision, dom)) throw new Error('Invalid view baseline');
          this._applyView(() => {
            this._sensitive.clearAll();
            k.resetView(dom.slice(1));
            this._lifecycle = bridgeStep(this._lifecycle, {type: 'baseline-applied', connection, revision});
          });
          break;
        }
        case Procedure.PATCH_VIEW: {
          const [connection, previous, next, dom] = commands;
          // A replaced connection's output is obsolete, including decoded frames
          // that were queued before its close. Never apply it to a newer view.
          const decision = patchDecision(this._lifecycle, connection, previous, next, dom);
          if (decision === 'obsolete') break;
          if (decision === 'invalid') throw new Error('Invalid view revision');
          if (decision === 'mismatch') throw new Error('View revision mismatch');
          this._applyView(() => {
            this._modifyDom(dom.slice(1));
            this._lifecycle = bridgeStep(this._lifecycle, {type: 'patch-applied', revision: next});
          });
          break;
        }
        case Procedure.READY_FOR_USER_EVENTS:
          if (!validReadiness(this._lifecycle, commands[0]))
            throw new Error('View baseline is not ready');
          this._lifecycle = bridgeStep(this._lifecycle, {type: 'ready'});
          this._connection.applicationReady();
          break;
        case Procedure.PRESENT_SENSITIVE:
          if (acceptsSensitiveView(this._lifecycle, commands[0]))
            this._sensitive.show.apply(this._sensitive, commands);
          break;
        case Procedure.CLEAR_SENSITIVE: this._sensitive.clear.apply(this._sensitive, commands); break;
        case Procedure.SENSITIVE_NAVIGATION_COMPLETE:
          if (!validBarrier(commands))
            throw new Error('Invalid sensitive navigation barrier');
          this._sensitive.completeNavigation(commands[0]);
          break;
        case Procedure.SENSITIVE_DEPARTURE_COMPLETE:
          if (!validBarrier(commands))
            throw new Error('Invalid sensitive departure barrier');
          this._sensitive.completeDeparture(commands[0]);
          break;
        default: console.error(`Procedure ${pCode} is undefined`);
      }
    } catch (error) {
      event.applied = false;
      if (pCode === Procedure.PRESENT_SENSITIVE || pCode === Procedure.CLEAR_SENSITIVE) {
        this._sensitive.clearAll(false);
        console.error('Sensitive presentation failed');
        return;
      }
      if (pCode === Procedure.RESET_VIEW || pCode === Procedure.PATCH_VIEW || pCode === Procedure.READY_FOR_USER_EVENTS || pCode === Procedure.SENSITIVE_NAVIGATION_COMPLETE || pCode === Procedure.SENSITIVE_DEPARTURE_COMPLETE) {
        console.error('Spoonbill view recovery failed');
        this._lifecycle = bridgeStep(this._lifecycle, {type: 'recover'});
        this._sensitive.blockNavigation();
        this._connection.disconnect(true);
        return;
      }
      console.error(`Spoonbill bridge failed for procedure ${pCode}`, error);
      if (pCode === Procedure.EXTRACT_PROPERTY && commands.length > 0) {
        // Ensure server-side extractProperty never hangs if the client throws.
        const descriptor = commands[0];
        this._onCallback(
          CallbackType.EXTRACT_PROPERTY_RESPONSE,
          `${descriptor}:${PropertyType.ERROR}:extractProperty failed`
        );
      } else if (pCode === Procedure.EXTRACT_EVENT_DATA && commands.length > 0) {
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
