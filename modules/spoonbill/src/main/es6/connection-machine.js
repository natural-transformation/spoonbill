/** @enum {number} */
export const ConnectionType = {WEB_SOCKET: 0, LONG_POLLING: 1};
export const MIN_RECONNECT_TIMEOUT = 200;
export const MAX_RECONNECT_TIMEOUT = 5000;
const MAX_RECOVERY_ATTEMPTS = 3;
/** One framework reload of an unguarded page in this many milliseconds; a second one waits. */
export const RELOAD_WINDOW = 30000;
const PAGE_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const COUNT_PATTERN = /^(0|[1-9][0-9]{0,15})$/;

/** Pure lifecycle data. Epochs are opaque identities supplied by the transport. */
export function initialConnection() {
  return {
    phase: 'dormant',
    transport: ConnectionType.LONG_POLLING,
    selected: null,
    epoch: null,
    scheduleEpoch: null,
    reconnect: true,
    presence: 'active',
    authentication: null,
    deliveredEpoch: null,
    recoveryAttempts: 0,
    reconnectDelay: MIN_RECONNECT_TIMEOUT,
    // Long polling that resumes. The page id names this document for its whole
    // life; `seen` is the last frame that it applied, `dom` the DOM frames among
    // them, and `failures` the polls that failed in a row.
    page: null,
    seen: 0,
    dom: 0,
    failures: 0,
    // A framework reload of an unguarded page has started: 'now', or 'waiting'
    // for the end of the reload window. From then on no transport is current.
    reloading: null,
    // For the note "start with long polling": this document tried a WebSocket,
    // or it started with long polling because of the note.
    triedWebSocket: false,
    fromNote: false
  };
}

export const connectionOpened = state => state.phase === 'opened' || state.phase === 'ready';
export const connectionReady = state => state.phase === 'ready' || state.phase === 'ready-before-open';
export const currentTransport = (state, epoch) => state.epoch === epoch;
export const currentSchedule = (state, epoch) => state.scheduleEpoch === epoch;
export const authenticationPending = state => state.authentication !== null;
/** No poll failed since the last answer. */
export const linkUp = state => state.failures === 0;
/** The connection-lost notice shows from the second failed poll in a row, and while a reload waits. */
export const noticeShown = state => state.failures >= 2 || state.reloading === 'waiting';
/** A framework reload has started; a page reloads once. */
export const reloadStarted = state => state.reloading !== null;
/** The wait before the next poll: none after an answer, the reconnect back-off after failures. */
export const pollDelay = state => state.failures === 0 ? 0 :
  Math.min(MIN_RECONNECT_TIMEOUT * 2 ** (state.failures - 1), MAX_RECONNECT_TIMEOUT);

/** A count in a response header: digits without sign or leading zeros, at most a safe integer. */
export function headerCount(value) {
  if (typeof value !== 'string' || !COUNT_PATTERN.test(value)) return null;
  const count = Number(value);
  return Number.isSafeInteger(count) ? count : null;
}

/** The body of an answer: a DOM frame ('dom'), another frame ('other'), or no frame at all (null). */
export function frameKind(body) {
  try {
    const frame = JSON.parse(body);
    return Array.isArray(frame) && frame[0] === 4 ? 'dom' : 'other';
  } catch (_) {
    return null;
  }
}

/**
 * What the long-polling transport does with an answer to its poll. A `200` is
 * applied only when its body is a frame (`kind`, from `frameKind`), it is frame
 * `seen + 1`, and it names the DOM revision that the page has after it: its own
 * count, plus one for a DOM frame. Anything else that is not a failure or `204`
 * makes the page reload.
 */
export function pollAnswer(state, status, frameHeader, domHeader, kind) {
  if (status === 204) return 'empty';
  if (requestFailed(status)) return 'failed';
  if (status !== 200 || kind === null) return 'reload';
  const frame = headerCount(frameHeader), dom = headerCount(domHeader);
  if (frame === null || dom === null) return 'reload';
  if (frame !== state.seen + 1 || dom !== state.dom + (kind === 'dom' ? 1 : 0)) return 'reload';
  return 'apply';
}

/**
 * A request that did not reach the server, or that it asks to repeat later:
 * no answer (status 0), a `5xx`, `408` and `429`. A poll so answered fails and
 * is sent again after the back-off; it does not reload the page.
 */
function requestFailed(status) {
  return status === 0 || status === 408 || status === 429 || (status >= 500 && status < 600);
}

/** Whether the server took a message, by the status of its request. */
export const messageSent = status => !requestFailed(status);

/**
 * A message that the server took while the link is interrupted: the link is
 * back, so a poll that hangs on the broken link or waits out its back-off is
 * replaced at once. Added after the second review of PR 42.
 */
export const repollsAfterMessage = (state, sent) => sent && !linkUp(state);

/**
 * The deadline of a poll in milliseconds: the poll wait that the page
 * configuration names (`lpw`), plus a margin of at most five seconds and at
 * most half the wait. The server bounds the wait to half the idle timeout, so
 * the deadline stays below the idle timeout, and below what a timer can hold.
 */
export function pollDeadline(wait) {
  const lpw = Number.isFinite(wait) && wait > 0 ? wait : 15000;
  return Math.min(lpw + Math.min(5000, Math.floor(lpw / 2)), 2147483647);
}

/**
 * After the transport handed a frame to the bridge. Only a DOM patch must
 * apply: one that did not makes the page reload, and nothing is counted. Every
 * other frame is delivered once, also one that threw in the bridge (a command
 * whose result cannot be sent, a property of an element that is gone), as on a
 * WebSocket: it is counted and the next poll goes out. Decided by the user on
 * 2026-11-22, after the review of PR 42.
 */
export function afterFrame(state, applied, domFrame) {
  return applied || !domFrame ?
    {state: connectionStep(state, {type: 'frame-applied', domFrame}), next: 'poll'} : {state, next: 'reload'};
}

/**
 * The wait before a framework reload of an unguarded page: none for the first
 * one in a window, the rest of the window for a second one. `last` is the time
 * of the last such reload in this tab, or null.
 */
export function reloadWait(last, now) {
  if (typeof last !== 'number' || !Number.isFinite(last) || now < last || now - last >= RELOAD_WINDOW) return 0;
  return RELOAD_WINDOW - (now - last);
}

/**
 * What a long-polling page that is told to reload does with the note "start
 * with long polling". A document that tried a WebSocket, fell back, and applied
 * no frame may have lost its fresh-page record to a socket that a proxy broke:
 * it writes the note ('write'). A document that started with long polling
 * because of the note and applied no frame takes it away ('remove'), so that
 * the next one tries a WebSocket again: that ends the loop on a network where
 * long polling is what fails. Anything else keeps it. Decided by the user on
 * 2026-11-22, after the review of PR 42.
 */
export function transportNote(state) {
  if (state.transport !== ConnectionType.LONG_POLLING || state.seen !== 0) return 'keep';
  if (state.fromNote) return 'remove';
  return state.triedWebSocket ? 'write' : 'keep';
}

export function nextTransport(state, guarded) {
  if (state.selected !== null) return state.selected;
  if (state.transport === ConnectionType.WEB_SOCKET)
    return guarded ? ConnectionType.WEB_SOCKET : ConnectionType.LONG_POLLING;
  return ConnectionType.WEB_SOCKET;
}

/** Per-socket drain ownership; queued message/callback handles stay outside. */
export function socketSenderStep(phase, event) {
  if (event === 'close') return 'closed';
  if (phase === 'idle' && event === 'start') return 'sending';
  if (phase === 'sending' && event === 'finish') return 'idle';
  return phase;
}

/** No transport handles, timers, callbacks, clock reads or asynchronous work. */
export function connectionStep(state, event) {
  switch (event.type) {
    case 'clear-delivery':
      return {...state, deliveredEpoch: null};
    case 'install':
      // A page that reloads takes no new transport.
      if (state.reloading !== null) return state;
      // The page id and the counts belong to the document: a second transport
      // of it, after a connect(), goes on from the frame that it has applied.
      return event.transport === ConnectionType.LONG_POLLING ?
        {...state, transport: event.transport, epoch: event.epoch, failures: 0} :
        {...state, transport: event.transport, epoch: event.epoch, deliveredEpoch: null, triedWebSocket: true};
    case 'open':
      if (!currentTransport(state, event.epoch) || connectionOpened(state)) return state;
      return {...state, phase: connectionReady(state) ? 'ready' : 'opened',
        reconnectDelay: MIN_RECONNECT_TIMEOUT, selected: state.transport};
    case 'ready':
      if (!currentTransport(state, event.epoch) || connectionReady(state)) return state;
      return {...state, phase: connectionOpened(state) ? 'ready' : 'ready-before-open',
        recoveryAttempts: 0, presence: state.presence === 'departed' ? 'departed' : 'active'};
    case 'close':
      return currentTransport(state, event.epoch) ? {...state, phase: 'dormant'} : state;
    case 'disconnect':
      return {...state, reconnect: event.reconnect,
        scheduleEpoch: event.reconnect ? state.scheduleEpoch : event.scheduleEpoch};
    case 'advance-backoff':
      return {...state, reconnectDelay: Math.min(state.reconnectDelay * 2, MAX_RECONNECT_TIMEOUT)};
    case 'before-unload':
      return state.presence === 'departed' ? state : {...state, presence: 'navigating'};
    case 'interaction':
      return event.trusted && event.visible && state.presence !== 'departed' ? {...state, presence: 'active'} : state;
    case 'hide':
      return {...state, presence: 'departed', scheduleEpoch: event.scheduleEpoch};
    case 'show':
      return {...state, presence: 'active'};
    case 'reset-lifecycle':
      return {...state, phase: 'dormant'};
    case 'claim-recovery':
      if (!currentTransport(state, event.epoch) || !event.enabled || !state.reconnect || state.presence !== 'active' || authenticationPending(state) ||
          (state.deliveredEpoch !== null && state.deliveredEpoch === event.epoch) ||
          state.recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) return state;
      return {...state, recoveryAttempts: state.recoveryAttempts + 1};
    case 'begin-authentication':
      return authenticationPending(state) ? state : {...state, authentication: {epoch: state.epoch}};
    case 'authentication-delivered':
      return currentTransport(state, event.epoch) ? {...state, deliveredEpoch: event.epoch} : state;
    case 'finish-authentication':
      return state.authentication && state.authentication.epoch === event.epoch ? {...state, authentication: null} : state;
    case 'page':
      return state.page === null && typeof event.page === 'string' && PAGE_PATTERN.test(event.page) ?
        {...state, page: event.page.toLowerCase()} : state;
    case 'prefer-long-polling':
      return state.selected === null ? {...state, selected: ConnectionType.LONG_POLLING, fromNote: true} : state;
    case 'poll-answered':
      return state.failures === 0 ? state : {...state, failures: 0};
    case 'frame-applied':
      return {...state, seen: state.seen + 1, dom: state.dom + (event.domFrame ? 1 : 0)};
    case 'poll-failed':
      return {...state, failures: state.failures + 1};
    case 'reload':
      // Once only. The transport is no one's any more, and nothing reconnects.
      if (state.reloading !== null) return state;
      return {...state, reloading: event.wait > 0 ? 'waiting' : 'now', epoch: null, reconnect: false,
        scheduleEpoch: event.scheduleEpoch};
    default:
      return state;
  }
}
