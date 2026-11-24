const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const NAME_PATTERN = /^[a-z][a-z0-9_.-]{0,63}$/;

/**
 * A long-polling bridge starts with a view: its connection is the page id, and
 * revision 0 is the page as the server rendered it. Its user events carry that
 * revision, and every applied DOM frame adds one.
 */
export function initialBridge(guarded, page = null) {
  // `outstandingHeartbeats` counts the heartbeats that the server took and has
  // not answered, `repolled` says that the limit gave a re-poll since the last
  // reply, and `heartbeatInFlight` is the number of a long-polling heartbeat
  // whose request has no answer yet. `heartbeatTransport` is the transport
  // that these count for (see heartbeatTick).
  return {view: page === null ? null : {connection: page, revision: '0'}, gate: guarded ? 'waiting' : 'ready',
    application: 'idle', navigationCounter: 0, outstandingHeartbeats: undefined, repolled: false,
    heartbeatInFlight: null, heartbeatSeq: 0, heartbeatTransport: undefined};
}

// The callback types (CallbackType of protocol.js) that messageKind tells
// apart: HISTORY, and the replies to a request of the server,
// EXTRACT_PROPERTY_RESPONSE, EVALJS_RESPONSE and EXTRACT_EVENT_DATA_RESPONSE.
// misc/bridge-machine-tests.mjs checks them against protocol.js.
const HISTORY_CALLBACK = 3;
const REPLY_CALLBACKS = [2, 4, 5];

/**
 * What a callback is to a paused page (outgoingMessage of the connection
 * machine): 'history', a 'reply' to a request of the server, which the server
 * waits for, or 'other'.
 */
export function messageKind(type) {
  if (type === HISTORY_CALLBACK) return 'history';
  return REPLY_CALLBACKS.includes(type) ? 'reply' : 'other';
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
    case 'heartbeat-configured': return {...state, outstandingHeartbeats: 0, repolled: false, heartbeatInFlight: null};
    // A reply starts the count again. It never goes below zero: a reply to a
    // heartbeat that was written off changes nothing more.
    case 'heartbeat-reply':
      return {...state, outstandingHeartbeats: Math.max(0, (state.outstandingHeartbeats || 0) - 1), repolled: false};
    default: return state;
  }
}

/**
 * One tick of the heartbeat. `limit` is the heartbeat limit (0 for none),
 * `longPolling` the transport, `reloading` whether a framework reload has
 * started; then nothing goes out. On long polling a heartbeat whose request
 * still has no answer when the next one is due could not be sent: it is a
 * failed poll (`failed`), not an unanswered heartbeat. Below the limit a
 * heartbeat is sent (`seq` names a long-polling one). At the limit a
 * long-polling page polls again once and reloads at the next limit: a dead
 * link shows the notice, and a dead input ends in a reload. A WebSocket page
 * reloads at the limit. Decided by the user on 2026-11-22, after the review of
 * PR 42.
 */
export function heartbeatTick(state, limit, longPolling, reloading, transport = state.heartbeatTransport) {
  if (reloading) return {state, command: 'skip', failed: false};
  // Heartbeats count for one transport. A page whose long polling is paused
  // has none: it sends nothing and spends nothing, and the count starts over,
  // as it does on a new transport (the review of PR 39 at f55be0f2).
  const current = state.heartbeatTransport === transport ? state :
    {...state, heartbeatTransport: transport, heartbeatInFlight: null, outstandingHeartbeats: 0, repolled: false};
  if (transport === null) return {state: current, command: 'skip', failed: false};
  return heartbeatCounted(current, limit, longPolling);
}

function heartbeatCounted(state, limit, longPolling) {
  const failed = longPolling && state.heartbeatInFlight !== null;
  // A heartbeat that failed gives the re-poll back: what the page knew of
  // its poll before the link failed says nothing about the poll after it.
  const counted = failed ?
    {...state, heartbeatInFlight: null, outstandingHeartbeats: Math.max(0, state.outstandingHeartbeats - 1), repolled: false} :
    state;
  if (!(limit > 0)) return heartbeatSent(counted, longPolling, failed);
  const outstanding = counted.outstandingHeartbeats + 1;
  if (outstanding < limit) return heartbeatSent({...counted, outstandingHeartbeats: outstanding}, longPolling, failed);
  if (longPolling && !counted.repolled) return {state: {...counted, outstandingHeartbeats: 0, repolled: true}, command: 'repoll', failed};
  return {state: {...counted, outstandingHeartbeats: 0}, command: 'reload', failed};
}

function heartbeatSent(state, longPolling, failed) {
  if (!longPolling) return {state, command: 'send', failed, seq: null};
  const seq = state.heartbeatSeq + 1;
  return {state: {...state, heartbeatSeq: seq, heartbeatInFlight: seq}, command: 'send', failed, seq};
}

/**
 * The request of long-polling heartbeat `seq` has its answer. One that the
 * server did not take is a failed poll, is not counted as unanswered, and
 * gives the re-poll back, as a heartbeat written off at a tick does; an
 * answer of a heartbeat that was written off changes nothing.
 */
export function heartbeatAnswer(state, seq, sent) {
  if (state.heartbeatInFlight !== seq) return {state, failed: false};
  const next = {...state, heartbeatInFlight: null};
  return sent ? {state: next, failed: false} :
    {state: {...next, outstandingHeartbeats: Math.max(0, next.outstandingHeartbeats - 1), repolled: false}, failed: true};
}

/**
 * Long-polling heartbeat `seq` was refused: no transport took it, so no
 * request went out. It is not in flight and not counted as unanswered, and it
 * is no failed poll either. Another heartbeat changes nothing.
 */
export function heartbeatRefused(state, seq) {
  if (state.heartbeatInFlight !== seq) return state;
  return {...state, heartbeatInFlight: null, outstandingHeartbeats: Math.max(0, state.outstandingHeartbeats - 1)};
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
