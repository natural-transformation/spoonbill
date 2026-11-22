const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME_PATTERN = /^[a-z][a-z0-9_.-]{0,63}$/;

/**
 * A long-polling bridge starts with a view: its connection is the page id, and
 * revision 0 is the page as the server rendered it. Its user events carry that
 * revision, and every applied DOM frame adds one.
 */
export function initialBridge(guarded, page = null) {
  return {view: page === null ? null : {connection: page, revision: '0'}, gate: guarded ? 'waiting' : 'ready',
    application: 'idle', navigationCounter: 0, outstandingHeartbeats: undefined};
}

export function bridgeStep(state, event) {
  switch (event.type) {
    case 'suspend': return {...state, gate: state.gate === 'recovering' ? 'recovering' : 'waiting'};
    case 'ready': return {...state, gate: state.gate === 'recovering' ? 'recovering' : 'ready'};
    case 'recover': return {...state, gate: 'recovering'};
    case 'begin-application': return {...state, application: 'applying'};
    case 'restore-application': return {...state, application: event.phase};
    case 'baseline-applied': return {...state, view: {connection: event.connection, revision: event.revision}};
    case 'patch-applied': return {...state, view: {...state.view, revision: event.revision}};
    // A DOM frame begins. Its revision is the view's from now on, so an event
    // that the patch itself makes belongs to the display that the frame makes.
    // A patch that then fails makes the page reload.
    case 'dom-frame':
      return state.view !== null ?
        {...state, view: {...state.view, revision: String(Number(state.view.revision) + 1)}} : state;
    case 'begin-navigation': return {...state, navigationCounter: state.navigationCounter + 1};
    case 'heartbeat-configured': return {...state, outstandingHeartbeats: 0};
    case 'heartbeat-tick': return {...state, outstandingHeartbeats: state.outstandingHeartbeats + 1};
    case 'heartbeat-reply': return {...state, outstandingHeartbeats: state.outstandingHeartbeats - 1};
    default: return state;
  }
}

/** This decision does not allocate a transition for ordinary user callbacks. */
export function callbackAdmission(state, barriers, guarded, history) {
  if (guarded && sensitiveTerminal(barriers)) return 'drop';
  if (guarded && !history && (departurePending(barriers) || navigationPending(barriers))) return 'drop';
  if (state.gate !== 'ready' || state.application === 'applying')
    return history && guarded ? 'blocked-history' : 'drop';
  if (guarded && history && state.navigationCounter >= Number.MAX_SAFE_INTEGER) return 'exhausted';
  return 'send';
}

export function validBaseline(state, epoch, connection, revision, dom) {
  return state.view === null && typeof epoch === 'string' && /^(0|[1-9][0-9]{0,18})$/.test(epoch) &&
    typeof connection === 'string' && /^[0-9a-f-]{36}$/.test(connection) && revision === '0' &&
    Array.isArray(dom) && dom[0] === 4;
}

export function patchDecision(state, connection, previous, next, dom) {
  const currentConnection = state.view === null ? null : state.view.connection;
  const revision = state.view === null ? null : state.view.revision;
  if (currentConnection !== connection) return 'obsolete';
  if (typeof previous !== 'string' || typeof next !== 'string' ||
      !/^(0|[1-9][0-9]*)$/.test(previous) || !/^[1-9][0-9]*$/.test(next) ||
      !Number.isSafeInteger(Number(next)) || !Array.isArray(dom) || dom[0] !== 4) return 'invalid';
  if (Number(next) <= Number(revision)) return 'obsolete';
  if (previous !== revision || Number(next) !== Number(previous) + 1) return 'mismatch';
  return 'apply';
}

export function validReadiness(state, durable) {
  return typeof durable === 'boolean' && durable === (state.view !== null);
}

export const acceptsSensitiveView = (state, connection) => state.view === null || state.view.connection === connection;
export const validBarrier = commands => commands.length === 1 && Number.isSafeInteger(commands[0]) && commands[0] > 0;
export const regionCapacityAvailable = (replacing, active) => replacing || active < 4;

export function initialSensitive() {
  return {phase: 'open', connection: null, seen: [],
    navigation: {phase: 'open', counter: null}, departure: {issued: 0, pending: null}};
}

export const departurePending = state => state.departure.pending !== null;
export const navigationPending = state => state.navigation.counter !== null;
export const sensitiveTerminal = state => state.phase === 'closed' || state.navigation.phase === 'permanent';
export const sensitiveBlocked = state => state.navigation.phase === 'permanent' || navigationPending(state) || departurePending(state);

export function sensitiveStep(state, event) {
  switch (event.type) {
    case 'begin-navigation':
      return state.navigation.phase === 'permanent' ? state :
        {...state, navigation: {phase: 'pending', counter: event.counter}};
    case 'complete-navigation':
      return state.navigation.phase !== 'permanent' && state.navigation.counter === event.counter ?
        {...state, navigation: {phase: 'open', counter: null}} : state;
    case 'block-navigation':
      return {...state, navigation: {...state.navigation, phase: 'permanent'}};
    case 'history':
      return state.navigation.counter === null ? sensitiveStep(state, {type: 'block-navigation'}) : state;
    case 'departure':
      if (sensitiveTerminal(state)) return state;
      if (state.departure.issued >= Number.MAX_SAFE_INTEGER) return sensitiveStep(state, {type: 'block-navigation'});
      return {...state, departure: {issued: state.departure.issued + 1, pending: state.departure.issued + 1}};
    case 'complete-departure':
      return !sensitiveTerminal(state) && state.departure.pending === event.counter ?
        {...state, departure: {...state.departure, pending: null}} : state;
    case 'rejected-presentation':
      return state.seen.length < 256 && !state.seen.includes(event.presentation) ?
        {...state, seen: [...state.seen, event.presentation]} : state;
    case 'installed-presentation':
      return {...state, connection: event.connection,
        seen: state.seen.includes(event.presentation) ? state.seen : [...state.seen, event.presentation]};
    case 'close': return {...state, phase: 'closed'};
    case 'forget': return {...state, seen: []};
    default: return state;
  }
}

export function validSensitiveBinding(connection, presentation, region) {
  return typeof connection === 'string' && UUID_PATTERN.test(connection) &&
    typeof presentation === 'string' && UUID_PATTERN.test(presentation) &&
    typeof region === 'string' && NAME_PATTERN.test(region);
}

/** Called after the interpreter has checked barriers and sampled its clock. */
export function sensitivePresentationAllowed(state, connection, presentation, purpose, remaining, expires, now) {
  return state.phase !== 'closed' && (state.connection === null || state.connection === connection) &&
    typeof purpose === 'string' && NAME_PATTERN.test(purpose) &&
    Number.isInteger(remaining) && remaining > 0 && remaining <= 300000 &&
    Number.isSafeInteger(expires) && expires > now && !state.seen.includes(presentation) && state.seen.length < 256;
}
