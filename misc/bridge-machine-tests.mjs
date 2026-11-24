import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../modules/spoonbill/src/main/es6/bridge-machine.js', import.meta.url), 'utf8');
const {initialBridge, bridgeStep, callbackAdmission, validBaseline, patchDecision, validReadiness,
  initialSensitive, sensitiveStep, departurePending, navigationPending, sensitiveTerminal,
  sensitiveBlocked, validSensitiveBinding, sensitivePresentationAllowed, regionCapacityAvailable,
  heartbeatTick, heartbeatAnswer, heartbeatRefused, messageKind} =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
const {CallbackType} = await import('data:text/javascript;base64,' +
  readFileSync(new URL('../modules/spoonbill/src/main/es6/protocol.js', import.meta.url)).toString('base64'));
const connection = '11111111-1111-4111-8111-111111111111';
const presentation = '22222222-2222-4222-8222-222222222222';

test('view admission waits for readiness, rejects partial views, and cannot reopen recovery', () => {
  let state = initialBridge(true);
  const barriers = initialSensitive();
  assert.equal(callbackAdmission(state, barriers, true, false), 'drop');
  assert.equal(callbackAdmission(state, barriers, true, true), 'blocked-history');
  state = bridgeStep(state, {type: 'ready'});
  assert.equal(callbackAdmission(state, barriers, true, false), 'send');
  state = bridgeStep(state, {type: 'begin-application'});
  assert.equal(callbackAdmission(state, barriers, true, false), 'drop');
  state = bridgeStep(state, {type: 'recover'});
  state = bridgeStep(state, {type: 'restore-application', phase: 'idle'});
  state = bridgeStep(state, {type: 'ready'});
  assert.equal(callbackAdmission(state, barriers, true, false), 'drop');
});

test('durable view decisions preserve obsolete, malformed and skipped-revision precedence', () => {
  const initial = initialBridge(true);
  assert.equal(validBaseline(initial, '0', connection, '0', [4]), true);
  assert.equal(validReadiness(initial, false), true);
  assert.equal(patchDecision(initial, null, '0', '1', [4]), 'mismatch');
  const bound = bridgeStep(initial, {type: 'baseline-applied', connection, revision: '2'});
  assert.equal(validBaseline(bound, '0', connection, '0', [4]), false);
  assert.equal(validReadiness(bound, true), true);
  assert.equal(patchDecision(bound, 'old', 'invalid', 'invalid', null), 'obsolete');
  assert.equal(patchDecision(bound, connection, '0', '1', [4]), 'obsolete');
  assert.equal(patchDecision(bound, connection, 'invalid', '1', [4]), 'invalid');
  assert.equal(patchDecision(bound, connection, '2', '4', [4]), 'mismatch');
  assert.equal(patchDecision(bound, connection, '2', '3', [4]), 'apply');
});

test('navigation and departure barriers complete independently in either order', () => {
  for (const order of [['complete-navigation', 'complete-departure'], ['complete-departure', 'complete-navigation']]) {
    let state = sensitiveStep(initialSensitive(), {type: 'departure'});
    state = sensitiveStep(state, {type: 'begin-navigation', counter: 1});
    const first = sensitiveStep(state, {type: order[0], counter: 1});
    assert.equal(sensitiveBlocked(first), true);
    const last = sensitiveStep(first, {type: order[1], counter: 1});
    assert.equal(sensitiveBlocked(last), false);
    assert.equal(sensitiveBlocked(state), true);
  }
});

test('history without an admitted counter fences permanently while stale departure completion is ignored', () => {
  const initial = initialSensitive();
  assert.equal(sensitiveTerminal(sensitiveStep(initial, {type: 'history'})), true);
  let state = sensitiveStep(initial, {type: 'departure'});
  state = sensitiveStep(state, {type: 'departure'});
  assert.equal(sensitiveStep(state, {type: 'complete-departure', counter: 1}), state);
  state = sensitiveStep(state, {type: 'block-navigation'});
  assert.equal(sensitiveStep(state, {type: 'complete-departure', counter: 2}), state);
  assert.equal(departurePending(state), true);
});

test('barriers block guarded user actions while preserving history and unguarded behavior', () => {
  const view = bridgeStep(initialBridge(true), {type: 'ready'});
  const barriers = sensitiveStep(initialSensitive(), {type: 'departure'});
  assert.equal(callbackAdmission(view, barriers, true, false), 'drop');
  assert.equal(callbackAdmission(view, barriers, true, true), 'send');
  assert.equal(callbackAdmission(view, barriers, false, false), 'send');
  const terminal = sensitiveStep(barriers, {type: 'close'});
  assert.equal(callbackAdmission(view, terminal, true, true), 'drop');
  assert.equal(callbackAdmission(view, terminal, false, false), 'send');
});

test('replay admission is bounded and immutable, and closure does not erase barriers prematurely', () => {
  const initial = initialSensitive();
  const installed = sensitiveStep(initial, {type: 'installed-presentation', connection, presentation});
  assert.equal(initial.seen.length, 0);
  assert.equal(validSensitiveBinding(connection, presentation, 'codes'), true);
  assert.equal(sensitivePresentationAllowed(initial, connection, presentation, 'mfa', 10, 110, 100), true);
  assert.equal(sensitivePresentationAllowed(installed, connection, presentation, 'mfa', 10, 110, 100), false);
  let bounded = installed;
  for (let i = 0; i < 300; i++) bounded = sensitiveStep(bounded, {type: 'rejected-presentation', presentation: String(i)});
  assert.equal(bounded.seen.length, 256);
  const pending = sensitiveStep(bounded, {type: 'begin-navigation', counter: 1});
  const closed = sensitiveStep(pending, {type: 'close'});
  assert.equal(navigationPending(closed), true);
  assert.equal(closed.seen.length, 256);
  assert.equal(sensitiveStep(closed, {type: 'forget'}).seen.length, 0);
  assert.equal(regionCapacityAvailable(false, 4), false);
  assert.equal(regionCapacityAvailable(true, 4), true);
});

test('persistent replay IDs preserve duplicate and SameValueZero membership without mutating snapshots', () => {
  const initial = initialSensitive();
  let state = initial;
  for (const presentation of ['same', 'same', NaN, NaN, 0, -0])
    state = sensitiveStep(state, {type: 'rejected-presentation', presentation});
  assert.deepEqual(state.seen, ['same', NaN, 0]);
  assert.deepEqual(initial.seen, []);
  const duplicate = sensitiveStep(state, {type: 'installed-presentation', connection, presentation: 'same'});
  assert.deepEqual(duplicate.seen, state.seen);
  const forgotten = sensitiveStep(duplicate, {type: 'forget'});
  assert.deepEqual(forgotten.seen, []);
  assert.deepEqual(state.seen, ['same', NaN, 0]);
});

test('exhausted counters fence without emitting a wrapped barrier', () => {
  const bridge = {...initialBridge(false), navigationCounter: Number.MAX_SAFE_INTEGER};
  assert.equal(callbackAdmission(bridge, initialSensitive(), true, true), 'exhausted');
  const sensitive = {...initialSensitive(), departure: {issued: Number.MAX_SAFE_INTEGER, pending: null}};
  const refused = sensitiveStep(sensitive, {type: 'departure'});
  assert.equal(sensitiveTerminal(refused), true);
  assert.equal(refused.departure.issued, Number.MAX_SAFE_INTEGER);
});

test('a long-polling bridge starts with the view of its page, and counts the DOM frames it applies', () => {
  assert.equal(initialBridge(false).view, null);
  let state = initialBridge(false, connection);
  assert.deepEqual(state.view, {connection, revision: '0'});
  assert.equal(callbackAdmission(state, initialSensitive(), false, false), 'send');
  // Each DOM frame is one more revision, from the moment it begins.
  state = bridgeStep(state, {type: 'dom-frame'});
  state = bridgeStep(state, {type: 'dom-frame'});
  assert.deepEqual(state.view, {connection, revision: '2'});
  // A bridge without a view has nothing to count.
  const plain = initialBridge(false);
  assert.equal(bridgeStep(plain, {type: 'dom-frame'}), plain);
});

test('the heartbeat of long polling: a re-poll at the limit, a reload at the next one, and a reply starts again', () => {
  let state = bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'});
  const tick = () => { const out = heartbeatTick(state, 2, true, false); state = out.state; return out; };
  const answered = seq => { const out = heartbeatAnswer(state, seq, true); state = out.state; return out; };
  // A heartbeat is sent and the server takes it.
  let out = tick();
  assert.deepEqual([out.command, out.failed], ['send', false]);
  answered(out.seq);
  // No reply by the next tick: the limit. The page polls again once.
  assert.deepEqual([tick().command, state.outstandingHeartbeats, state.repolled], ['repoll', 0, true]);
  out = tick();
  assert.equal(out.command, 'send');
  answered(out.seq);
  // Still no reply at the next limit: the input is dead, the page reloads.
  assert.equal(tick().command, 'reload');
  // A reply in between starts the count again, and never goes below zero.
  state = bridgeStep(bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'}), {type: 'heartbeat-reply'});
  assert.equal(state.outstandingHeartbeats, 0);
  out = tick(); answered(out.seq);
  assert.equal(tick().command, 'repoll');
  state = bridgeStep(state, {type: 'heartbeat-reply'});
  assert.deepEqual([state.repolled, state.outstandingHeartbeats], [false, 0]);
  out = tick(); answered(out.seq);
  assert.equal(tick().command, 'repoll');
});

test('a heartbeat that cannot be sent is a failed poll, not an unanswered heartbeat', () => {
  let state = bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'});
  // Its request fails.
  let out = heartbeatTick(state, 2, true, false);
  const failed = heartbeatAnswer(out.state, out.seq, false);
  assert.deepEqual([failed.failed, failed.state.outstandingHeartbeats, failed.state.heartbeatInFlight], [true, 0, null]);
  // Its request has no answer when the next heartbeat is due: written off at that tick, and the next one goes out.
  state = failed.state;
  out = heartbeatTick(state, 2, true, false);
  const next = heartbeatTick(out.state, 2, true, false);
  assert.deepEqual([next.command, next.failed, next.state.outstandingHeartbeats], ['send', true, 1]);
  // A late answer of a heartbeat that was written off changes nothing.
  assert.deepEqual(heartbeatAnswer(next.state, out.seq, true), {state: next.state, failed: false});
  // The heartbeat goes on without a limit too, and still sees a request that hangs.
  const free = heartbeatTick(bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'}), 0, true, false);
  assert.equal(free.command, 'send');
  assert.equal(heartbeatTick(free.state, 0, true, false).failed, true);
});

test('a heartbeat that fails gives the re-poll back: the next limit polls again, not a reload', () => {
  let state = bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'});
  let out = heartbeatTick(state, 2, true, false);
  state = heartbeatAnswer(out.state, out.seq, true).state;
  out = heartbeatTick(state, 2, true, false);
  assert.deepEqual([out.command, out.state.repolled], ['repoll', true]);
  // The link fails after the re-poll: its request fails.
  let sent = heartbeatTick(out.state, 2, true, false);
  const failed = heartbeatAnswer(sent.state, sent.seq, false);
  assert.deepEqual([failed.failed, failed.state.repolled], [true, false]);
  // Or it has no answer by the next tick, and is written off there.
  sent = heartbeatTick(out.state, 2, true, false);
  const written = heartbeatTick(sent.state, 2, true, false);
  assert.deepEqual([written.failed, written.state.repolled], [true, false]);
  // So when the link is back, the limit gives one more re-poll before a reload.
  state = heartbeatAnswer(heartbeatTick(failed.state, 2, true, false).state, failed.state.heartbeatSeq + 1, true).state;
  assert.equal(heartbeatTick(state, 2, true, false).command, 'repoll');
});

test('the bridge state names the transport that its heartbeats count for, from the start', () => {
  // The review of PR 39 at 628bbd99, note 1.5.3.
  for (const guarded of [false, true]) {
    const state = initialBridge(guarded);
    assert.equal(Object.hasOwn(state, 'heartbeatTransport'), true);
    assert.equal(state.heartbeatTransport, undefined);
  }
});

test('heartbeats count for one transport: a paused page skips and starts over, and so does a new transport', () => {
  // The review of PR 39 at f55be0f2, finding 1: a page paused with a heartbeat
  // taken and its re-poll spent reloaded at the next tick.
  const transport = {};
  let state = bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'});
  let out = heartbeatTick(state, 2, true, false, transport);
  state = heartbeatAnswer(out.state, out.seq, true).state;
  out = heartbeatTick(state, 2, true, false, transport);
  assert.equal(out.command, 'repoll');
  out = heartbeatTick(out.state, 2, true, false, transport);
  const outstanding = heartbeatAnswer(out.state, out.seq, true).state;
  assert.deepEqual([outstanding.outstandingHeartbeats, outstanding.repolled], [1, true]);
  // Paused: no transport. Nothing is sent, nothing is spent, and the count starts over.
  const paused = heartbeatTick(outstanding, 2, true, false, null);
  assert.deepEqual([paused.command, paused.failed], ['skip', false]);
  assert.deepEqual([paused.state.outstandingHeartbeats, paused.state.heartbeatInFlight, paused.state.repolled], [0, null, false]);
  assert.equal(heartbeatTick(paused.state, 2, true, false, null).command, 'skip');
  // Resumed on a new transport: a fresh allowance.
  const resumed = heartbeatTick(paused.state, 2, true, false, {});
  assert.deepEqual([resumed.command, resumed.failed, resumed.state.outstandingHeartbeats], ['send', false, 1]);
  // A heartbeat in flight when the transport changes is written off without a failure.
  const flying = heartbeatTick(outstanding, 2, true, false, transport);
  const moved = heartbeatTick(flying.state, 2, true, false, {});
  assert.deepEqual([moved.failed, moved.command], [false, 'send']);
});

test('a heartbeat that no transport takes is neither counted nor a failed poll', () => {
  const state = bridgeStep(initialBridge(false, connection), {type: 'heartbeat-configured'});
  const out = heartbeatTick(state, 2, true, false);
  const refused = heartbeatRefused(out.state, out.seq);
  assert.deepEqual([refused.outstandingHeartbeats, refused.heartbeatInFlight], [0, null]);
  // So the next tick sends again, and does not reach the limit.
  assert.equal(heartbeatTick(refused, 2, true, false).command, 'send');
  // A heartbeat that is not the one in flight changes nothing.
  assert.equal(heartbeatRefused(out.state, out.seq + 1), out.state);
});

test('a WebSocket page reloads at the limit, and no heartbeat goes out once a reload has started', () => {
  let state = bridgeStep(initialBridge(false), {type: 'heartbeat-configured'});
  let out = heartbeatTick(state, 2, false, false);
  assert.deepEqual([out.command, out.failed, out.state.heartbeatInFlight], ['send', false, null]);
  assert.equal(heartbeatTick(out.state, 2, false, false).command, 'reload');
  for (const longPolling of [true, false])
    assert.deepEqual(heartbeatTick(state, 2, longPolling, true), {state, command: 'skip', failed: false});
});

test('a callback is a history message, a reply to a request of the server, or another message', () => {
  // The review of PR 39 at 17fd8ee0: a paused page notes a missed navigation and keeps a reply.
  assert.equal(messageKind(CallbackType.HISTORY), 'history');
  for (const type of [CallbackType.EXTRACT_PROPERTY_RESPONSE, CallbackType.EVALJS_RESPONSE, CallbackType.EXTRACT_EVENT_DATA_RESPONSE])
    assert.equal(messageKind(type), 'reply', String(type));
  for (const [name, type] of Object.entries(CallbackType))
    if (!['HISTORY', 'EXTRACT_PROPERTY_RESPONSE', 'EVALJS_RESPONSE', 'EXTRACT_EVENT_DATA_RESPONSE'].includes(name))
      assert.equal(messageKind(type), 'other', name);
});
