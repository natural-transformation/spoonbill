import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import test from 'node:test';

const source = readFileSync(new URL('../modules/spoonbill/src/main/es6/connection-machine.js', import.meta.url), 'utf8');
const {ConnectionType, initialConnection, connectionStep: step, connectionOpened, connectionReady,
  currentTransport, currentSchedule, authenticationPending, nextTransport, socketSenderStep,
  linkUp, noticeShown, pollDelay, headerCount, pollAnswer, reloadWait, notesLongPolling,
  RELOAD_WINDOW, MIN_RECONNECT_TIMEOUT, MAX_RECONNECT_TIMEOUT} =
  await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

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

const page = '11111111-1111-4111-8111-111111111111';

test('a page keeps one well formed page id for its whole life', () => {
  const initial = initialConnection();
  assert.equal(initial.page, null);
  assert.equal(step(initial, {type: 'page', page: 'not a page'}), initial);
  const named = step(initial, {type: 'page', page: page.toUpperCase()});
  assert.equal(named.page, page);
  assert.equal(step(named, {type: 'page', page: '22222222-2222-4222-8222-222222222222'}), named);
});

test('the cursor counts applied frames, and the DOM revision counts the DOM frames among them', () => {
  let state = initialConnection();
  assert.deepEqual([state.seen, state.dom], [0, 0]);
  state = step(state, {type: 'frame-applied', domFrame: false});
  state = step(state, {type: 'frame-applied', domFrame: true});
  state = step(state, {type: 'frame-applied', domFrame: true});
  assert.deepEqual([state.seen, state.dom], [3, 2]);
});

test('a frame is applied only when both headers name what the page has after it', () => {
  const state = {...initialConnection(), seen: 3, dom: 2};
  assert.equal(pollAnswer(state, 200, '4', '3', true), 'apply');
  assert.equal(pollAnswer(state, 200, '4', '2', false), 'apply');
  // The page counts itself: a header that differs makes it reload.
  for (const [frame, dom, domFrame] of [['4', '2', true], ['4', '3', false], ['5', '3', true], ['3', '2', false],
    [null, '3', true], ['4', null, true], ['04', '3', true], ['4', '-3', true], ['4', '3.0', true],
    ['9007199254740993', '3', true]])
    assert.equal(pollAnswer(state, 200, frame, dom, domFrame), 'reload', `${frame} ${dom} ${domFrame}`);
});

test('a poll answer is applied, empty, failed or a reason to reload', () => {
  const state = initialConnection();
  assert.equal(pollAnswer(state, 204, null, null, false), 'empty');
  for (const status of [0, 500, 502, 503, 504])
    assert.equal(pollAnswer(state, status, null, null, false), 'failed', String(status));
  for (const status of [400, 404, 410, 302])
    assert.equal(pollAnswer(state, status, '1', '0', false), 'reload', String(status));
});

test('failed polls interrupt the link, show the notice from the second one, and back off', () => {
  let state = initialConnection();
  assert.equal(linkUp(state), true);
  assert.equal(pollDelay(state), 0);
  state = step(state, {type: 'poll-failed'});
  assert.deepEqual([linkUp(state), noticeShown(state), pollDelay(state)], [false, false, MIN_RECONNECT_TIMEOUT]);
  state = step(state, {type: 'poll-failed'});
  assert.deepEqual([noticeShown(state), pollDelay(state)], [true, 2 * MIN_RECONNECT_TIMEOUT]);
  for (let i = 0; i < 10; i++) state = step(state, {type: 'poll-failed'});
  assert.equal(pollDelay(state), MAX_RECONNECT_TIMEOUT);
  // Any answer ends the interruption, a 204 too. The counts stay.
  const counted = {...state, seen: 4, dom: 1};
  const answered = step(counted, {type: 'poll-answered'});
  assert.deepEqual([linkUp(answered), noticeShown(answered), answered.seen, answered.dom], [true, false, 4, 1]);
  assert.equal(step(answered, {type: 'poll-answered'}), answered);
});

test('the reload guard allows one reload in its window, and a second one waits for its end', () => {
  assert.equal(reloadWait(null, 1000), 0);
  assert.equal(reloadWait(undefined, 1000), 0);
  assert.equal(reloadWait(Number.NaN, 1000), 0);
  assert.equal(reloadWait(1000, 1000 + RELOAD_WINDOW), 0);
  assert.equal(reloadWait(1000, 1000 + RELOAD_WINDOW - 1), 1);
  assert.equal(reloadWait(1000, 11000), RELOAD_WINDOW - 10000);
  // A clock that went back is not a reason to wait.
  assert.equal(reloadWait(5000, 1000), 0);
});

test('a long-polling page that is told to reload before its first frame starts with long polling', () => {
  const polling = {...initialConnection(), transport: ConnectionType.LONG_POLLING};
  assert.equal(notesLongPolling(polling), true);
  assert.equal(notesLongPolling({...polling, seen: 1}), false);
  assert.equal(notesLongPolling({...polling, transport: ConnectionType.WEB_SOCKET}), false);
  const preferred = step(initialConnection(), {type: 'prefer-long-polling'});
  assert.equal(nextTransport(preferred, false), ConnectionType.LONG_POLLING);
  assert.equal(nextTransport(initialConnection(), false), ConnectionType.WEB_SOCKET);
  // A transport that opened already stays the selected one.
  const opened = {...initialConnection(), selected: ConnectionType.WEB_SOCKET};
  assert.equal(step(opened, {type: 'prefer-long-polling'}), opened);
});

test('header counts are digits without sign or leading zeros, up to a safe integer', () => {
  assert.equal(headerCount('0'), 0);
  assert.equal(headerCount('42'), 42);
  assert.equal(headerCount('9007199254740991'), 9007199254740991);
  for (const value of [null, undefined, '', '01', '+1', '-1', '1e2', ' 1', '9007199254740992', '99999999999999999'])
    assert.equal(headerCount(value), null, String(value));
});
