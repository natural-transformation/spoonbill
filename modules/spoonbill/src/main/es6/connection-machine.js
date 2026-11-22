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
    failures: 0
  };
}

export const connectionOpened = state => state.phase === 'opened' || state.phase === 'ready';
export const connectionReady = state => state.phase === 'ready' || state.phase === 'ready-before-open';
export const currentTransport = (state, epoch) => state.epoch === epoch;
export const currentSchedule = (state, epoch) => state.scheduleEpoch === epoch;
export const authenticationPending = state => state.authentication !== null;
/** No poll failed since the last answer. */
export const linkUp = state => state.failures === 0;
/** The connection-lost notice shows from the second failed poll in a row. */
export const noticeShown = state => state.failures >= 2;
/** The wait before the next poll: none after an answer, the reconnect back-off after failures. */
export const pollDelay = state => state.failures === 0 ? 0 :
  Math.min(MIN_RECONNECT_TIMEOUT * 2 ** (state.failures - 1), MAX_RECONNECT_TIMEOUT);

/** A count in a response header: digits without sign or leading zeros, at most a safe integer. */
export function headerCount(value) {
  if (typeof value !== 'string' || !COUNT_PATTERN.test(value)) return null;
  const count = Number(value);
  return Number.isSafeInteger(count) ? count : null;
}

/**
 * What the long-polling transport does with an answer to its poll. A `200` is
 * applied only when it is frame `seen + 1` and names the DOM revision that the
 * page has after it: its own count, plus one for a DOM frame. Anything else that
 * is not a failure or `204` makes the page reload.
 */
export function pollAnswer(state, status, frameHeader, domHeader, domFrame) {
  if (status === 204) return 'empty';
  if (status === 0 || (status >= 500 && status < 600)) return 'failed';
  if (status !== 200) return 'reload';
  const frame = headerCount(frameHeader), dom = headerCount(domHeader);
  if (frame === null || dom === null) return 'reload';
  if (frame !== state.seen + 1 || dom !== state.dom + (domFrame ? 1 : 0)) return 'reload';
  return 'apply';
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
 * A long-polling page that is told to reload before it applied any frame may
 * have fallen back from a WebSocket that the server took, and so its fresh-page
 * record. It notes that it starts with long polling after the reload.
 */
export const notesLongPolling = state => state.transport === ConnectionType.LONG_POLLING && state.seen === 0;

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
      // A new long-polling transport is a first attach: it has applied nothing.
      return event.transport === ConnectionType.LONG_POLLING ?
        {...state, transport: event.transport, epoch: event.epoch, seen: 0, dom: 0, failures: 0} :
        {...state, transport: event.transport, epoch: event.epoch, deliveredEpoch: null};
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
      return state.selected === null ? {...state, selected: ConnectionType.LONG_POLLING} : state;
    case 'poll-answered':
      return state.failures === 0 ? state : {...state, failures: 0};
    case 'frame-applied':
      return {...state, seen: state.seen + 1, dom: state.dom + (event.domFrame ? 1 : 0)};
    case 'poll-failed':
      return {...state, failures: state.failures + 1};
    default:
      return state;
  }
}
