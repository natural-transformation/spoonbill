import assert from 'node:assert/strict';
import test from 'node:test';
import {createCompletionLoss} from './browser-auth-completion-loss.cjs';

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
