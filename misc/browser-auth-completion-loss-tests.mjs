import assert from 'node:assert/strict';
import test from 'node:test';
import {createCompletionLoss, createCookieDeliveryLoss, createRequestDeliveryLoss} from './browser-auth-completion-loss.cjs';

function fixture() {
  const faults = [];
  return {faults, loss: createCompletionLoss(source => faults.push(source)), predecessor: {}, successor: {}};
}

test('a witnessed predecessor close resolves loss even when no completion frame arrives', () => {
  const {faults, loss, predecessor} = fixture();
  loss.arm(predecessor);
  assert.equal(loss.upstreamClosed(predecessor), true);
  assert.deepEqual(faults, ['upstream-close']);
  assert.deepEqual(loss.snapshot(), {faultSource: 'upstream-close', faultCount: 1,
    observedControls: 0, droppedControls: 0, forwardedControls: 0, witnessedTargetClose: true});
});

test('close then late Blob completion drops and counts the real control without another fault', () => {
  const {faults, loss, predecessor} = fixture();
  loss.arm(predecessor);
  loss.upstreamClosed(predecessor);
  assert.equal(loss.dropCompletion(predecessor), true);
  loss.upstreamClosed(predecessor);
  assert.deepEqual(faults, ['upstream-close']);
  assert.deepEqual(loss.snapshot(), {faultSource: 'upstream-close', faultCount: 1,
    observedControls: 1, droppedControls: 1, forwardedControls: 0, witnessedTargetClose: true});
});

test('completion then close records one intercepted-command fault', () => {
  const {faults, loss, predecessor} = fixture();
  loss.arm(predecessor);
  assert.equal(loss.dropCompletion(predecessor), true);
  assert.equal(loss.upstreamClosed(predecessor), true);
  assert.deepEqual(faults, ['completion-command']);
  assert.deepEqual(loss.snapshot(), {faultSource: 'completion-command', faultCount: 1,
    observedControls: 1, droppedControls: 1, forwardedControls: 0, witnessedTargetClose: true});
});

test('setup closures and a successor closure cannot stand in for the armed predecessor fault', () => {
  const {faults, loss, predecessor, successor} = fixture();
  const setup = {};
  assert.equal(loss.upstreamClosed(setup), false);
  assert.equal(loss.dropCompletion(setup), false);
  loss.arm(predecessor);
  assert.equal(loss.upstreamClosed(setup), false);
  assert.equal(loss.upstreamClosed(successor), false);
  assert.deepEqual(faults, []);
  assert.equal(loss.snapshot().faultCount, 0);
  assert.equal(loss.snapshot().witnessedTargetClose, false);
  assert.equal(loss.upstreamClosed(predecessor), true);
  assert.deepEqual(faults, ['upstream-close']);
});

test('unexpected successor and duplicate completion controls remain visible to security assertions', () => {
  const {loss, predecessor, successor} = fixture();
  loss.arm(predecessor);
  assert.equal(loss.dropCompletion(predecessor), true);
  assert.equal(loss.dropCompletion(predecessor), true);
  assert.equal(loss.dropCompletion(successor), false);
  assert.equal(loss.snapshot().observedControls, 3);
  assert.equal(loss.snapshot().droppedControls, 2);
  assert.equal(loss.snapshot().forwardedControls, 1);
  assert.equal(loss.snapshot().faultCount, 1);
});

test('a closed setup transport cannot be armed or replaced by a successor target', () => {
  const {loss, predecessor, successor} = fixture();
  loss.upstreamClosed(predecessor);
  assert.throws(() => loss.arm(predecessor), /live predecessor/);
  loss.arm(successor);
  assert.throws(() => loss.arm({}), /live predecessor/);
});

test('cookie loss ignores setup, other endpoints, and non-POST requests', () => {
  const loss = createCookieDeliveryLoss();
  assert.equal(loss.route('POST', '/auth/complete'), 'continue');
  loss.arm();
  assert.equal(loss.route('GET', '/auth/recover'), 'continue');
  assert.equal(loss.route('POST', '/auth/logout'), 'continue');
  assert.equal(loss.snapshot().deliveryPath, undefined);
  assert.throws(() => loss.arm(), /only be armed once/);
});

for (const first of ['/auth/complete', '/auth/recover']) {
  test(`cookie loss captures one ${first} response and leaves checked recovery available`, () => {
    const loss = createCookieDeliveryLoss();
    loss.arm();
    assert.equal(loss.route('POST', first), 'fetch');
    assert.equal(loss.route('POST', '/auth/complete'), 'abort');
    assert.equal(loss.route('POST', '/auth/recover'), 'continue');
    assert.equal(loss.route('POST', '/auth/recover'), 'continue');
    assert.deepEqual(loss.snapshot(), {deliveryPath: first});
  });

  test(`request loss aborts two ${first} attempts and holds exactly the third without forwarding`, () => {
    const loss = createRequestDeliveryLoss();
    assert.equal(loss.route('POST', first), 'continue');
    loss.arm();
    assert.equal(loss.route('GET', first), 'continue');
    assert.equal(loss.route('POST', '/auth/logout'), 'continue');
    assert.equal(loss.route('POST', first), 'abort');
    assert.equal(loss.route('POST', first), 'abort');
    assert.equal(loss.route('POST', first), 'hold');
    assert.deepEqual(loss.snapshot(), {deliveryPath: first, attempts: 3, mixedPath: false, overBudget: false});
    assert.equal(loss.route('POST', first), 'abort');
    assert.equal(loss.snapshot().overBudget, true, 'Late attempts must remain failures after the fault resolves');
    assert.equal(loss.snapshot().attempts, 4);
  });
}

test('request loss records mixed paths instead of treating them as one bounded retry chain', () => {
  const loss = createRequestDeliveryLoss();
  loss.arm();
  assert.equal(loss.route('POST', '/auth/complete'), 'abort');
  assert.equal(loss.route('POST', '/auth/recover'), 'abort');
  assert.equal(loss.route('POST', '/auth/complete'), 'abort');
  assert.deepEqual(loss.snapshot(), {deliveryPath: '/auth/complete', attempts: 3, mixedPath: true, overBudget: false});
  assert.throws(() => loss.arm(), /only be armed once/);
});
