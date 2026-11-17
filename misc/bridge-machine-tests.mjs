import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../modules/spoonbill/src/main/es6/bridge-machine.js', import.meta.url), 'utf8');
const {initialBridge, bridgeStep, callbackAdmission, validBaseline, patchDecision, validReadiness,
  initialSensitive, sensitiveStep, departurePending, navigationPending, sensitiveTerminal,
  sensitiveBlocked, validSensitiveBinding, sensitivePresentationAllowed, regionCapacityAvailable} =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));
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
  assert.equal(initial.seen.size, 0);
  assert.equal(validSensitiveBinding(connection, presentation, 'codes'), true);
  assert.equal(sensitivePresentationAllowed(initial, connection, presentation, 'mfa', 10, 110, 100), true);
  assert.equal(sensitivePresentationAllowed(installed, connection, presentation, 'mfa', 10, 110, 100), false);
  let bounded = installed;
  for (let i = 0; i < 300; i++) bounded = sensitiveStep(bounded, {type: 'rejected-presentation', presentation: String(i)});
  assert.equal(bounded.seen.size, 256);
  const pending = sensitiveStep(bounded, {type: 'begin-navigation', counter: 1});
  const closed = sensitiveStep(pending, {type: 'close'});
  assert.equal(navigationPending(closed), true);
  assert.equal(closed.seen.size, 256);
  assert.equal(sensitiveStep(closed, {type: 'forget'}).seen.size, 0);
  assert.equal(regionCapacityAvailable(false, 4), false);
  assert.equal(regionCapacityAvailable(true, 4), true);
});

test('exhausted counters fence without emitting a wrapped barrier', () => {
  const bridge = {...initialBridge(false), navigationCounter: Number.MAX_SAFE_INTEGER};
  assert.equal(callbackAdmission(bridge, initialSensitive(), true, true), 'exhausted');
  const sensitive = {...initialSensitive(), departure: {issued: Number.MAX_SAFE_INTEGER, pending: null}};
  const refused = sensitiveStep(sensitive, {type: 'departure'});
  assert.equal(sensitiveTerminal(refused), true);
  assert.equal(refused.departure.issued, Number.MAX_SAFE_INTEGER);
});
