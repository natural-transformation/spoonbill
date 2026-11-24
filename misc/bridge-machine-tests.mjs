import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../modules/spoonbill/src/main/es6/bridge-machine.js', import.meta.url), 'utf8');
const {initialBridge, bridgeStep, callbackAdmission, validBaseline, patchDecision, validReadiness,
  initialSensitive, sensitiveStep, departurePending, navigationPending, sensitiveTerminal,
  sensitiveBlocked, validSensitiveBinding, sensitivePresentationAllowed, regionCapacityAvailable,
  heartbeatTick, heartbeatAnswer, heartbeatRefused, messageKind, navigationAnswer, navigationConfirmed,
  navigationDeadline, pageUrlEntry} =
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

// The history-ordering fix: an unguarded page drops its user events from a
// history event until its view confirms that the route is published.
const sent = (state, posted = true) => bridgeStep(state, {type: 'navigation-sent', posted});
const started = (state, confirms) => bridgeStep(state, {type: 'view-started', confirms});
const confirming = () => started(initialBridge(false), true);
const legacy = () => started(initialBridge(false), false);

test('an unguarded page holds a navigation until its view says whether it confirms navigations', () => {
  const barriers = initialSensitive();
  // The review of 2026-11-24, finding 3: Back before the first frame of the view.
  const early = sent(initialBridge(false));
  assert.equal(early.confirmsNavigation, null);
  assert.deepEqual(early.navigation, {pending: 1, waitsFor: 'answer', confirmed: 0});
  assert.equal(callbackAdmission(early, barriers, false, false), 'drop');
  assert.equal(callbackAdmission(early, barriers, false, true), 'send');
  // A view that confirms navigations keeps the page waiting for this one.
  assert.deepEqual(started(early, true).navigation, {pending: 1, waitsFor: 'answer', confirmed: 0});
  assert.equal(callbackAdmission(started(early, true), barriers, false, false), 'drop');
  // A view of an older server confirms none: its first frame releases the page.
  const released = started(early, false);
  assert.deepEqual(released.navigation, {pending: null, waitsFor: null, confirmed: 0});
  assert.equal(callbackAdmission(released, barriers, false, false), 'send');
  // Once the view is known to confirm none, a navigation only counts.
  const older = sent(legacy());
  assert.equal(older.navigationCounter, 1);
  assert.equal(older.navigation.pending, null);
  assert.equal(navigationConfirmed(older, 1).action, 'none');
  // On a WebSocket there is no POST to answer: the wait for the confirmation starts at once.
  assert.equal(sent(confirming(), false).navigation.waitsFor, 'confirmation');
  // A guarded page keeps its own gate.
  assert.equal(callbackAdmission(initialBridge(true), barriers, true, false), 'drop');
});

test('the answer to a history POST says whether the navigation was delivered', () => {
  const waiting = sent(confirming());
  const taken = navigationAnswer(waiting, 1, true);
  assert.deepEqual([taken.action, taken.delivered], ['expect', true]);
  assert.equal(taken.state.navigation.waitsFor, 'confirmation');
  const lost = navigationAnswer(waiting, 1, false);
  assert.deepEqual([lost.action, lost.delivered], ['recover', false]);
  // The review of 2026-11-24, finding 1: Back confirmed, then its answer
  // lost, while Forward waits behind it. Back was delivered: Forward goes on.
  const forward = navigationConfirmed(sent(waiting), 1).state;
  const late = navigationAnswer(forward, 1, false);
  assert.deepEqual([late.action, late.delivered], ['none', true]);
  assert.equal(late.state.navigation.pending, 2);
  // Back unconfirmed and its POST failed: the navigation is unresolved.
  assert.deepEqual([navigationAnswer(sent(waiting), 1, false).action, navigationAnswer(sent(waiting), 1, false).delivered],
    ['recover', false]);
  assert.deepEqual([navigationAnswer(sent(waiting), 1, true).action, navigationAnswer(sent(waiting), 1, true).delivered],
    ['none', true]);
  // With an older server a failed POST recovers too: the server may not know the URL.
  assert.deepEqual([navigationAnswer(sent(legacy()), 1, false).action, navigationAnswer(sent(legacy()), 1, true).delivered],
    ['recover', true]);
});

test('only the confirmation of the latest navigation reopens the input', () => {
  const barriers = initialSensitive();
  // Back, then Forward before Back is confirmed.
  let state = sent(navigationAnswer(sent(confirming()), 1, true).state);
  assert.equal(state.navigation.pending, 2);
  // An unrelated patch, and the confirmation of Back, leave Forward pending.
  state = bridgeStep(state, {type: 'dom-frame'});
  const back = navigationConfirmed(state, 1);
  assert.equal(back.action, 'none');
  assert.deepEqual(back.state.navigation, {pending: 2, waitsFor: 'answer', confirmed: 1});
  assert.equal(callbackAdmission(back.state, barriers, false, false), 'drop');
  const forward = navigationConfirmed(back.state, 2);
  assert.equal(forward.action, 'open');
  assert.deepEqual(forward.state.navigation, {pending: null, waitsFor: null, confirmed: 2});
  assert.equal(callbackAdmission(forward.state, barriers, false, false), 'send');
  // A late or repeated confirmation changes nothing; one of a navigation never sent recovers.
  assert.equal(navigationConfirmed(forward.state, 1).action, 'none');
  assert.equal(navigationConfirmed(forward.state, 2).action, 'none');
  assert.equal(navigationConfirmed(forward.state, 3).action, 'recover');
  // A view whose state is not known yet confirms nothing.
  assert.equal(navigationConfirmed(sent(initialBridge(false)), 1).action, 'none');
});

test('a confirmation that does not come in time recovers; only a pause and a resume give it more time', () => {
  const waiting = navigationAnswer(sent(confirming()), 1, true).state;
  // `paused`, `resumed`: the page is paused now, or a paused page resumed since the wait began.
  assert.equal(navigationDeadline(waiting, 1, false, false), 'recover');
  assert.equal(navigationDeadline(waiting, 1, true, false), 'wait');
  assert.equal(navigationDeadline(waiting, 1, false, true), 'wait');
  // A deadline of a navigation that was confirmed, or whose place a later one took, needs nothing.
  assert.equal(navigationDeadline(navigationConfirmed(waiting, 1).state, 1, false, false), 'none');
  assert.equal(navigationDeadline(sent(waiting), 1, false, false), 'none');
  // While the POST has no answer, its own deadline counts, not this one.
  assert.equal(navigationDeadline(sent(confirming()), 1, false, false), 'none');
});

test('while a navigation waits for its confirmation, the page skips the URLs that the view sends', () => {
  // The review of 2026-11-24, finding 2: the URL of Back's route came while
  // Forward waited, replaced Forward's entry, and a failed Forward reloaded it.
  const waiting = sent(sent(confirming()));
  assert.equal(pageUrlEntry(waiting, false), 'skip');
  assert.equal(pageUrlEntry(navigationConfirmed(waiting, 1).state, false), 'skip');
  assert.equal(pageUrlEntry(navigationConfirmed(waiting, 2).state, false), 'push');
  assert.equal(pageUrlEntry(sent(initialBridge(false)), false), 'skip');
  assert.equal(pageUrlEntry(confirming(), false), 'push');
  // A page whose view confirms no navigation, and a guarded page, push as before.
  assert.equal(pageUrlEntry(sent(legacy()), false), 'push');
  assert.equal(pageUrlEntry(initialBridge(true), true), 'push');
});

test('the URL that the view sends for the navigation that the page waits for replaces the entry that the user chose', () => {
  // The re-review of 2026-11-24: skipping every URL while a navigation
  // waited lost the server's URL of the route that won as well.
  const waiting = sent(sent(confirming()));
  assert.equal(pageUrlEntry(waiting, false, 2), 'replace');
  // The URL of a navigation whose place a later one took is skipped.
  assert.equal(pageUrlEntry(waiting, false, 1), 'skip');
  assert.equal(pageUrlEntry(navigationConfirmed(waiting, 1).state, false, 2), 'replace');
  // After the confirmation, a URL is a transition of the application again.
  assert.equal(pageUrlEntry(navigationConfirmed(waiting, 2).state, false, 2), 'push');
  // A guarded page numbers no URL.
  assert.equal(pageUrlEntry(initialBridge(true), true, 1), 'push');
});
