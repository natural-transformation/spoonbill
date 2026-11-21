/** @enum {number} */
export const ConnectionType = {WEB_SOCKET: 0, LONG_POLLING: 1};
export const MIN_RECONNECT_TIMEOUT = 200;
export const MAX_RECONNECT_TIMEOUT = 5000;
const MAX_RECOVERY_ATTEMPTS = 3;

/** Pure lifecycle data. Epochs are opaque identities supplied by the transport. */
export function initialConnection() {
  return {
    phase: 'dormant',
    transport: ConnectionType.LONG_POLLING,
    selected: null,
    epoch: null,
    // How many times the connection has started to open. The page builds a new
    // bridge on every open, and long polling sends this number so that the
    // server can give that bridge a new view.
    opening: 0,
    scheduleEpoch: null,
    reconnect: true,
    presence: 'active',
    authentication: null,
    deliveredEpoch: null,
    recoveryAttempts: 0,
    reconnectDelay: MIN_RECONNECT_TIMEOUT
  };
}

export const connectionOpened = state => state.phase === 'opened' || state.phase === 'ready';
export const connectionReady = state => state.phase === 'ready' || state.phase === 'ready-before-open';
export const currentTransport = (state, epoch) => state.epoch === epoch;
export const currentSchedule = (state, epoch) => state.scheduleEpoch === epoch;
export const authenticationPending = state => state.authentication !== null;

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
      // A transport that replaces an open one dispatches no open event: the
      // page keeps its bridge, so the transport serves the same opening.
      return {...state, transport: event.transport, epoch: event.epoch,
        opening: connectionOpened(state) ? state.opening : state.opening + 1,
        deliveredEpoch: event.transport === ConnectionType.WEB_SOCKET ? null : state.deliveredEpoch};
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
    default:
      return state;
  }
}
