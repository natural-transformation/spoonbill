import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../modules/spoonbill/src/main/es6/connection-machine.js', import.meta.url), 'utf8');
const {ConnectionType, initialConnection, connectionStep: step, connectionOpened, connectionReady,
  currentTransport, currentSchedule, authenticationPending, nextTransport, socketSenderStep} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

test('transport epochs reject stale closure while preserving the selected transport', () => {
  const first = {}, second = {};
  const initial = initialConnection();
  const opened = step(step(initial, {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch: first}), {type: 'open', epoch: first});
  const replacement = step(opened, {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch: second});
  assert.equal(step(replacement, {type: 'close', epoch: first}), replacement);
  assert.equal(step(replacement, {type: 'ready', epoch: first}), replacement);
  assert.equal(step(replacement, {type: 'claim-recovery', enabled: true, epoch: first}), replacement);
  assert.equal(currentTransport(replacement, first), false);
  assert.equal(currentTransport(replacement, second), true);
  assert.equal(nextTransport(step(replacement, {type: 'close', epoch: second}), false), ConnectionType.WEB_SOCKET);
  assert.equal(initial.epoch, null);
  assert.equal(initial.phase, 'dormant');
});

test('open and readiness keep their independent notification order', () => {
  for (const order of [['open', 'ready'], ['ready', 'open']]) {
    let state = initialConnection();
    for (const type of order) state = step(state, {type, epoch: null});
    assert.equal(connectionOpened(state), true);
    assert.equal(connectionReady(state), true);
    assert.equal(step(state, {type: 'open', epoch: null}), state);
    assert.equal(step(state, {type: 'ready', epoch: null}), state);
  }
});

test('fallback, backoff and explicit stop remain separate decisions', () => {
  let state = initialConnection();
  assert.equal(nextTransport(state, false), ConnectionType.WEB_SOCKET);
  state = step(state, {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch: {}});
  assert.equal(nextTransport(state, false), ConnectionType.LONG_POLLING);
  assert.equal(nextTransport(state, true), ConnectionType.WEB_SOCKET);
  for (let i = 0; i < 100; i++) state = step(state, {type: 'advance-backoff'});
  assert.equal(state.reconnectDelay, 5000);
  state = step(step(state, {type: 'disconnect', reconnect: false, scheduleEpoch: {}}), {type: 'open', epoch: state.epoch});
  assert.equal(state.reconnectDelay, 200);
  assert.equal(state.reconnect, false);
});

test('only readiness replenishes recovery and successful delivery fences its own epoch', () => {
  const epoch = {};
  const claim = {type: 'claim-recovery', enabled: true, epoch};
  let state = step(initialConnection(), {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch});
  for (let i = 0; i < 10; i++) state = step(state, claim);
  assert.equal(state.recoveryAttempts, 3);
  assert.equal(step(state, claim), state);
  state = step(state, {type: 'ready', epoch});
  assert.equal(state.recoveryAttempts, 0);
  state = step(state, {type: 'authentication-delivered', epoch});
  assert.equal(step(state, claim), state);
  const next = {};
  state = step(state, {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch: next});
  assert.equal(step(state, {...claim, epoch: next}).recoveryAttempts, 1);
});

test('departure and authentication cannot be reopened by unrelated lifecycle events', () => {
  let state = step(initialConnection(), {type: 'hide', scheduleEpoch: {}});
  state = step(state, {type: 'ready', epoch: null});
  assert.equal(state.presence, 'departed');
  assert.equal(step(state, {type: 'interaction', trusted: true, visible: true}), state);
  state = step(state, {type: 'show'});
  state = step(state, {type: 'begin-authentication'});
  assert.equal(authenticationPending(state), true);
  assert.equal(step(state, {type: 'claim-recovery', enabled: true, epoch: null}), state);
  state = step(state, {type: 'install', transport: ConnectionType.WEB_SOCKET, epoch: {}});
  assert.equal(authenticationPending(state), true);
  assert.equal(authenticationPending(step(state, {type: 'finish-authentication', epoch: null})), false);
});

test('a closed socket sender cannot be reopened by its old drain finishing', () => {
  const active = socketSenderStep('idle', 'start');
  assert.equal(active, 'sending');
  assert.equal(socketSenderStep(active, 'start'), active);
  assert.equal(socketSenderStep(active, 'finish'), 'idle');
  const closed = socketSenderStep(active, 'close');
  assert.equal(socketSenderStep(closed, 'finish'), 'closed');
  assert.equal(socketSenderStep(closed, 'start'), 'closed');
});

test('stop and document departure invalidate old schedules without forbidding a later explicit start', () => {
  const initial = initialConnection();
  const stopped = step(initial, {type: 'disconnect', reconnect: false, scheduleEpoch: {}});
  assert.equal(currentSchedule(stopped, initial.scheduleEpoch), false);
  assert.equal(currentSchedule(stopped, stopped.scheduleEpoch), true);
  const departed = step(stopped, {type: 'hide', scheduleEpoch: {}});
  assert.equal(currentSchedule(departed, stopped.scheduleEpoch), false);
  assert.equal(currentSchedule(step(departed, {type: 'show'}), departed.scheduleEpoch), true);
});
