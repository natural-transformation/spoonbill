import assert from 'node:assert/strict';
import { copyFile, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { pathToFileURL } from 'node:url';
import test from 'node:test';

// No DOM library or source rewrites: load the actual client modules as ESM.
// Browser fakes implement only the bounded surfaces used by these regressions.
class EventTargetFake {
  listeners = new Map();

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? new Set();
    listeners.add(listener);
    this.listeners.set(type, listeners);
  }

  removeEventListener(type, listener) {
    this.listeners.get(type)?.delete(listener);
  }

  dispatchEvent(event) {
    for (const listener of this.listeners.get(event.type) ?? []) listener(event);
    return true;
  }
}

class ElementFake {
  getAttribute(name) {
    if (name === 'data-spoonbill-action-fields') return this.fields ?? null;
    if (name === 'data-spoonbill-native-submit') return this.nativeSubmit ?? null;
    return null;
  }
}

class FormControlsFake {
  constructor(controls) { this.controls = controls; }
  namedItem(name) { return this.controls[name] ?? null; }
}

class FormFake extends ElementFake {
  nodeType = 1;
  childNodes = [];
  vId = '1_1';

  constructor(fields, controls) {
    super();
    this.fields = JSON.stringify(fields);
    this.controls = new FormControlsFake(controls);
  }

  get elements() { return this.controls; }
}

class WebSocketFake extends EventTargetFake {
  static CONNECTING = 0;
  static OPEN = 1;
  static CLOSED = 3;
  static instances = [];
  protocol = 'json-deflate';
  readyState = WebSocketFake.OPEN;
  sent = [];

  constructor(url, protocols) {
    super();
    this.url = url;
    this.protocols = protocols;
    WebSocketFake.instances.push(this);
  }

  send(message) {
    assert.equal(this.readyState, WebSocketFake.OPEN, 'send requires an open socket');
    this.sent.push(message);
  }

  close() {
    this.readyState = WebSocketFake.CLOSED;
  }
}

function browser(form = null) {
  const document = new EventTargetFake();
  document.children = [{ nodeType: 1, childNodes: form ? [form] : [] }];
  document.documentElement = document.children[0];
  document.documentElement.attributes = [];
  document.documentElement.replaceChildren = () => { document.documentElement.childNodes = []; };
  document.createDocumentFragment = () => new EventTargetFake();
  const window = new EventTargetFake();
  window.document = document;
  window.WebSocket = WebSocketFake;
  window.location = { host: 'example.invalid', protocol: 'https:', pathname: '/' };
  const storage = new Map();
  window.localStorage = {
    getItem: key => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
  };
  Object.assign(globalThis, { window, document, Element: ElementFake, HTMLFormElement: FormFake,
    HTMLFormControlsCollection: FormControlsFake, WebSocket: WebSocketFake });
  return { document, window };
}

function submit(document, form, submitter = null, expectedPrevented = true) {
  let prevented = false;
  const event = {
    type: 'submit',
    target: form,
    submitter,
    composedPath: () => [form],
    preventDefault: () => { prevented = true; },
  };
  document.dispatchEvent(event);
  assert.equal(prevented, expectedPrevented);
  return event;
}

function deferred() {
  let resolve;
  const promise = new Promise(complete => { resolve = complete; });
  return { promise, resolve };
}

function delayedCompression() {
  const entered = deferred();
  const release = deferred();
  globalThis.CompressionStream = class extends TransformStream {
    constructor(format) {
      assert.equal(format, 'deflate-raw');
      super({
        async transform(chunk, controller) {
          entered.resolve();
          await release.promise;
          controller.enqueue(chunk);
        },
      });
    }
  };
  return { entered: entered.promise, release: release.resolve };
}

test('redesign client regressions against real source modules', { timeout: 10000 }, async t => {
  const names = ['window', 'document', 'Element', 'HTMLFormElement', 'HTMLFormControlsCollection', 'WebSocket', 'CompressionStream', 'fetch', 'XMLHttpRequest',
    'setTimeout', 'clearTimeout', 'setInterval', 'clearInterval'];
  const originalGlobals = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const directory = await mkdtemp(join(tmpdir(), 'spoonbill-client-regressions-'));
  try {
    await writeFile(join(directory, 'package.json'), '{"type":"module"}\n');
    for (const name of ['spoonbill.js', 'utils.js', 'connection.js', 'bridge.js', 'sensitive.js', 'protocol.js']) {
      await copyFile(new URL(`../modules/spoonbill/src/main/es6/${name}`, import.meta.url), join(directory, name));
    }
    browser(); // bridge.js reads localStorage when its module is initialized.
    const { Spoonbill } = await import(pathToFileURL(join(directory, 'spoonbill.js')));
    const { CallbackType } = await import(pathToFileURL(join(directory, 'protocol.js')));
    const { Bridge, setProtocolDebugEnabled } = await import(pathToFileURL(join(directory, 'bridge.js')));
    const { Connection, ConnectionType } = await import(pathToFileURL(join(directory, 'connection.js')));
    const { ConnectionLostWidget } = await import(pathToFileURL(join(directory, 'utils.js')));

    await t.test('explicit native form submits without a callback or retained submit event despite named-control shadowing', () => {
      const form = new FormFake([], {});
      form.fields = null;
      form.nativeSubmit = 'true';
      form.getAttribute = {value: 'named control must not replace prototype lookup'};
      Object.defineProperty(form, 'elements', {value: {value: 'shadowed native getter'}});
      const {document} = browser(form);
      const callbacks = [];
      const client = new Spoonbill({}, (...args) => callbacks.push(args));
      submit(document, form, null, false);
      assert.deepEqual(callbacks, []);
      assert.deepEqual(client.eventData, {});
      client.destroy();
    });

    await t.test('typed submit wins over native opt-in and still extracts prototype-shadowing fields', () => {
      const form = new FormFake([['getAttribute', 'text'], ['elements', 'text']], {
        getAttribute: {value: 'first'}, elements: {value: 'second'},
      });
      form.nativeSubmit = 'true';
      form.getAttribute = {value: 'shadowed method'};
      Object.defineProperty(form, 'elements', {value: {value: 'shadowed getter'}});
      const {document} = browser(form);
      const callbacks = [];
      const client = new Spoonbill({}, (...args) => callbacks.push(args));
      submit(document, form);
      assert.deepEqual(callbacks, [[CallbackType.DOM_EVENT, '0:1_1:submit:getAttribute=first&elements=second']]);
      assert.deepEqual(client.eventData, {});
      client.destroy();
    });

    await t.test('native opt-in does not bypass non-submit events, non-form targets or a present empty action descriptor', () => {
      const form = new FormFake([], {});
      form.fields = null;
      form.nativeSubmit = 'true';
      const {document} = browser(form);
      const client = new Spoonbill({}, () => {});
      client.listenRoot('click', true);
      let prevented = false;
      document.dispatchEvent({type: 'click', target: form, composedPath: () => [form],
        preventDefault: () => { prevented = true; }});
      assert.equal(prevented, true);
      const nonForm = Object.assign(new ElementFake(), {nodeType: 1, vId: '1_2', nativeSubmit: 'true'});
      submit(document, nonForm);
      form.fields = '';
      submit(document, form);
      client.destroy();
    });

    await t.test('guarded reconnect carries the current mounted application route without logging query values', () => {
      browser();
      const location = {host: 'example.invalid', protocol: 'https:', pathname: '/app/invoices/42',
        search: '?tab=details&marker=synthetic-private-query'};
      const logs = [];
      const log = console.log;
      console.log = (...args) => logs.push(args.join(' '));
      try {
        const connection = new Connection('view', '/app/', location, {auth: true});
        connection._connectUsingWebSocket();
        const first = new URL(WebSocketFake.instances.at(-1).url);
        assert.equal(first.pathname, '/app/bridge/web-socket/view');
        assert.equal(first.searchParams.get('__spoonbill_location'), '/invoices/42?tab=details&marker=synthetic-private-query');
        location.pathname = '/app/invoices/43';
        location.search = '?tab=summary';
        connection._connectUsingWebSocket();
        const second = new URL(WebSocketFake.instances.at(-1).url);
        assert.equal(second.searchParams.get('__spoonbill_location'), '/invoices/43?tab=summary');
        assert.equal(logs.some(line => line.includes('synthetic-private-query')), false);
      } finally { console.log = log; }
    });

    await t.test('guarded mount matching uses the browser URL representation and preserves legacy transport paths', () => {
      browser();
      for (const mount of ['/', '/app/', '/my app/', '/café/', '/my%20app/', '/caf%C3%A9/']) {
        const location = new URL(mount + 'invoices/42?tab=details', 'https://example.invalid');
        for (const guarded of [false, true]) {
          const before = WebSocketFake.instances.length;
          const connection = new Connection('view', mount, location, {auth: guarded});
          connection.dispatcher.addEventListener('error', () => assert.fail(`Unexpected error for ${mount}`));
          connection._connectUsingWebSocket();
          assert.equal(WebSocketFake.instances.length, before + 1, `${mount}: one socket must be constructed`);
          const socket = new URL(WebSocketFake.instances.at(-1).url);
          assert.equal(socket.pathname, new URL(mount + 'bridge/web-socket/view', location).pathname);
          assert.equal(socket.searchParams.get('__spoonbill_location'), guarded ? '/invoices/42?tab=details' : null);
        }
      }
    });

    await t.test('encoded mount matching still rejects sibling prefixes and accepts the exact mount root', () => {
      browser();
      for (const mount of ['/app/', '/my app/', '/café/', '/my%20app/', '/caf%C3%A9/']) {
        const root = mount.slice(0, -1);
        const rejected = new Connection('view', mount,
          new URL(root + '-other/invoices/42', 'https://example.invalid'), {auth: true});
        let errors = 0;
        rejected.dispatcher.addEventListener('error', () => errors++);
        const before = WebSocketFake.instances.length;
        rejected._connectUsingWebSocket();
        assert.equal(errors, 1, `${mount}: sibling mount must be rejected`);
        assert.equal(WebSocketFake.instances.length, before, 'rejection must precede socket construction');

        const accepted = new Connection('view', mount, new URL(root + '?tab=details', 'https://example.invalid'), {auth: true});
        accepted._connectUsingWebSocket();
        assert.equal(new URL(WebSocketFake.instances.at(-1).url).searchParams.get('__spoonbill_location'), '/?tab=details');
      }
    });

    await t.test('mount matching ignores percent hex case without treating encoded slashes as boundaries', () => {
      browser();
      for (const [mount, path, accepted] of [
        ['/café/', '/caf%c3%a9/invoices/42', true],
        ['/caf%c3%a9/', '/caf%C3%A9/invoices/42', true],
        ['/app/', '/app%2fother/invoices/42', false],
      ]) {
        const connection = new Connection('view', mount, new URL(path + '?tab=details', 'https://example.invalid'), {auth: true});
        let errors = 0;
        connection.dispatcher.addEventListener('error', () => errors++);
        const before = WebSocketFake.instances.length;
        connection._connectUsingWebSocket();
        assert.equal(WebSocketFake.instances.length, before + (accepted ? 1 : 0));
        assert.equal(errors, accepted ? 0 : 1);
        if (accepted) assert.equal(new URL(WebSocketFake.instances.at(-1).url).searchParams.get('__spoonbill_location'), '/invoices/42?tab=details');
      }
    });

    await t.test('recovery hides the connection widget after its original body has been replaced', () => {
      browser();
      const widget = new ConnectionLostWidget('');
      let removed = 0;
      const element = {parentNode: {removeChild: node => { assert.equal(node, element); removed += 1; }}};
      document.body = {removeChild: () => assert.fail('Widget belongs to the discarded body')};
      widget._element = element;
      widget.hide();
      widget.hide();
      assert.equal(removed, 1);
      assert.equal(widget._element, null);
    });

    await t.test('guarded listeners suppress early events until the authorized baseline is ready', () => {
      browser();
      const sent = [];
      let ready = 0;
      const connection = { dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        applicationReady: () => { ready += 1; }, disconnect: () => assert.fail('Unexpected reconnect') };
      const bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
      const deliver = frame => connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
      const id = '11111111-1111-4111-8111-111111111111';
      try {
        bridge._onCallback(CallbackType.DOM_EVENT, '0:1:submit:password=synthetic-transient');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:too-early');
        bridge._onCallback(CallbackType.HEARTBEAT);
        assert.deepEqual(sent, [[6]], 'early user payloads are dropped rather than queued for replay');
        deliver([19, '3', id, '0', [4]]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:increment');
        assert.equal(sent.length, 1, 'reset alone does not enable handlers before setup is complete');
        deliver([21, true]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:increment');
        assert.deepEqual(sent, [[6], [7, `${id}:0:1:counter:increment`]]);
        assert.equal(ready, 1);
      } finally { bridge.destroy(); }
    });

    await t.test('departure recovery gates user callbacks but allows replies and never replays a dropped action', () => {
      browser();
      const sent = [];
      const connection = {dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        applicationReady() {}, disconnect: () => assert.fail('Departure recovery must retain its binding')};
      const bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
      const deliver = frame => connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
      const id = '11111111-1111-4111-8111-111111111111';
      try {
        deliver([19, '3', id, '0', [4]]);
        deliver([21, true]);
        window.dispatchEvent({type: 'beforeunload'});
        bridge._onCallback(CallbackType.DOM_EVENT, '0:1:submit:password=synthetic-transient');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:one-read');
        bridge._onCallback(CallbackType.HEARTBEAT);
        bridge._onCallback(CallbackType.EXTRACT_PROPERTY_RESPONSE, '0:0:reply');
        assert.deepEqual(sent, [[10, '1'], [6], [2, '0:0:reply']]);
        window.dispatchEvent({type: 'beforeunload'});
        deliver([25, 1]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:stale-barrier');
        assert.equal(sent.length, 4);
        deliver([25, 2]);
        assert.equal(sent.length, 4, 'recovery replayed a dropped user action');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:fresh');
        assert.deepEqual(sent.at(-1), [7, `${id}:0:1:show:fresh`]);
        window.dispatchEvent({type: 'beforeunload'});
        window.dispatchEvent({type: 'pagehide'});
        deliver([25, 3]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:departed');
        assert.deepEqual(sent.at(-1), [10, '3']);
      } finally { bridge.destroy(); }
    });

    await t.test('history during pending departure completes independently in either barrier order', () => {
      for (const order of [[24, 25], [25, 24]]) {
        browser();
        const sent = [];
        const connection = {dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
          applicationReady() {}, disconnect: () => assert.fail('Unexpected reconnect')};
        const bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
        const deliver = frame => connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
        try {
          deliver([21, false]);
          window.dispatchEvent({type: 'beforeunload'});
          bridge._onCallback(CallbackType.HISTORY, '/different');
          assert.deepEqual(sent, [[10, '1'], [3, '/different']], 'real history was suppressed');
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:both-pending');
          deliver([order[0], 1]);
          bridge._onCallback(CallbackType.DOM_EVENT, '0:1:submit:code=synthetic');
          assert.equal(sent.length, 2, 'one barrier released the other pending fence');
          deliver([order[1], 1]);
          assert.equal(sent.length, 2, 'recovery replayed a discarded action');
          assert.equal(bridge._sensitive.departurePending, false);
          assert.equal(bridge._sensitive.navigationPending, false);
          assert.equal(bridge._sensitive._navigationPermanent, false);
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:fresh');
          assert.deepEqual(sent.at(-1), [1, 'show:fresh']);
        } finally { bridge.destroy(); }
      }
    });

    await t.test('unguarded actions remain available after a canceled departure without a recovery protocol', () => {
      browser();
      const sent = [];
      const connection = {dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        disconnect: () => assert.fail('Unexpected reconnect')};
      const bridge = new Bridge({heartbeat: {interval: '0'}}, connection);
      try {
        window.dispatchEvent({type: 'beforeunload'});
        bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'ordinary:fresh');
        bridge._onCallback(CallbackType.HISTORY, '/different');
        assert.deepEqual(sent, [[0, '0:1:click'], [1, 'ordinary:fresh'], [3, '/different']]);
      } finally { bridge.destroy(); }
    });

    await t.test('terminal guarded controllers suppress actions even without a pending departure', () => {
      for (const terminal of ['pagehide', 'destroy']) {
        browser();
        const sent = [];
        const connection = {dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
          applicationReady() {}, disconnect: () => assert.fail('Unexpected reconnect')};
        const bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
        try {
          connection.dispatcher.dispatchEvent({type: 'message', data: '[21,false]'});
          assert.equal(bridge._sensitive.departurePending, false);
          if (terminal === 'pagehide') window.dispatchEvent({type: 'pagehide'});
          else bridge.destroy();
          bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:departed');
          bridge._onCallback(CallbackType.HISTORY, '/different');
          assert.equal(bridge._sensitive.terminal, true);
          assert.deepEqual(sent, [], `${terminal} allowed a guarded action`);
        } finally { bridge.destroy(); }
      }
    });

    await t.test('view reset discards old DOM/event payloads and stamps subsequent actions once', () => {
      const form = new FormFake([['password', 'text']], { password: { value: 'synthetic-transient' } });
      const { document } = browser(form);
      const sent = [];
      const disconnected = [];
      const connection = { dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        disconnect: reconnect => disconnected.push(reconnect) };
      const bridge = new Bridge({heartbeat: {interval: '0'}}, connection);
      const id = '11111111-1111-4111-8111-111111111111';
      try {
        bridge._spoonbill.eventData['old'] = { secret: 'synthetic-transient' };
        connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify([19, '42', id, '0', [4]])});
        assert.deepEqual(document.documentElement.childNodes, []);
        assert.deepEqual(bridge._spoonbill.eventData, {});
        assert.deepEqual(Object.keys(bridge._spoonbill.els), ['1']);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:increment');
        bridge._onCallback(CallbackType.HEARTBEAT);
        assert.deepEqual(sent, [[7, `${id}:0:1:counter:increment`], [6]]);
        assert.deepEqual(disconnected, []);
      } finally { bridge.destroy(); }
    });

    await t.test('view patches ignore duplicate/old-owner frames and recover revision gaps without replay', () => {
      browser();
      const disconnected = [];
      const sent = [];
      const connection = { dispatcher: new EventTargetFake(), send: value => sent.push(value),
        disconnect: reconnect => disconnected.push(reconnect) };
      const bridge = new Bridge({heartbeat: {interval: '0'}}, connection);
      const id = '11111111-1111-4111-8111-111111111111';
      const deliver = frame => connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
      const originalError = console.error;
      const errors = [];
      console.error = (...args) => errors.push(args.join(' '));
      try {
        deliver([19, '42', id, '0', [4]]);
        const applied = [];
        bridge._spoonbill.modifyDom = commands => applied.push(commands);
        deliver([20, id, '0', '1', [4]]);
        deliver([20, id, '0', '1', [4, 'synthetic-stale-payload']]);
        deliver([20, 'old-connection', '1', '2', [4, 'synthetic-stale-payload']]);
        assert.deepEqual(applied, [[]]);
        deliver([20, id, '2', '3', [4, 'synthetic-stale-payload']]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:increment');
        assert.deepEqual(disconnected, [true]);
        assert.deepEqual(sent, []);
        assert.deepEqual(applied, [[]]);
        assert.equal(errors.some(line => line.includes('synthetic-stale-payload')), false);
      } finally { console.error = originalError; bridge.destroy(); }
    });

    await t.test('suppressed unready history rebinds once or defers to the pending authentication handoff', () => {
      for (const authenticationPending of [false, true]) {
        browser();
        const sent = [], disconnected = [];
        const connection = {dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
          authenticationPending, applicationReady() {}, disconnect: reconnect => disconnected.push(reconnect)};
        const bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
        try {
          bridge._onCallback(CallbackType.HISTORY, '/first');
          bridge._onCallback(CallbackType.HISTORY, '/latest');
          assert.deepEqual(disconnected, authenticationPending ? [] : [true]);
          assert.equal(bridge._viewRecovering, true);
          assert.equal(bridge._sensitive.terminal, true);
          connection.dispatcher.dispatchEvent({type: 'message', data: '[21,false]'});
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:old');
          assert.deepEqual(sent, [], 'old-controller readiness reopened a permanently fenced action');
        } finally { bridge.destroy(); }
      }
    });

    await t.test('partial view history rebinds without blocking RPC or replaying dropped actions', async () => {
      browser();
      const sent = [];
      const disconnected = [];
      const connection = { dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        applicationReady: () => {}, disconnect: reconnect => disconnected.push(reconnect) };
      let bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
      const id = '11111111-1111-4111-8111-111111111111';
      const replacementId = '22222222-2222-4222-8222-222222222222';
      const deliver = frame => connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
      try {
        deliver([19, '42', id, '0', [4]]);
        deliver([21, true]);
        bridge._spoonbill.modifyDom = () => {
          assert.equal(bridge._viewRevision, '0', 'the next revision cannot describe a partially changed DOM');
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:during-patch');
          bridge._onCallback(CallbackType.DOM_EVENT, '0:1:submit:synthetic-transient');
          bridge._onCallback(CallbackType.HISTORY, '/during-patch');
          bridge._onCallback(CallbackType.EVALJS_RESPONSE, 'descriptor:0:result');
          bridge._onCallback(CallbackType.HEARTBEAT);
        };
        deliver([20, id, '0', '1', [4]]);
        await Promise.resolve();
        assert.deepEqual(sent, [[4, 'descriptor:0:result'], [6]], 'partial-view actions must be dropped, not retagged or replayed');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:after-patch');
        bridge._onCallback(CallbackType.HISTORY, '/second-suppressed');
        assert.deepEqual(sent, [[4, 'descriptor:0:result'], [6]], 'old controller admitted an action while permanently fenced');
        assert.deepEqual(disconnected, [true], 'suppressed history must request exactly one fresh authorized connection');
        assert.equal(bridge._applyingView, false);
        assert.equal(bridge._viewRecovering, true);
        assert.equal(bridge._sensitive.terminal, true);
        // Mirror launcher's close/open lifecycle. The new physical connection
        // must supply its own authorized baseline/readiness before fresh actions.
        bridge.destroy();
        bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:before-baseline');
        deliver([19, '43', replacementId, '0', [4]]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:before-ready');
        assert.equal(sent.length, 2);
        deliver([21, true]);
        assert.equal(sent.length, 2, 'replacement replayed an old callback');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:after-rebind');
        assert.deepEqual(sent[2], [7, `${replacementId}:0:1:counter:after-rebind`]);
      } finally { bridge.destroy(); }
    });

    await t.test('failed view mutations release the application gate but keep user callbacks closed for recovery', () => {
      browser();
      const sent = [];
      const disconnected = [];
      const connection = { dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        applicationReady: () => {}, disconnect: reconnect => disconnected.push(reconnect) };
      const bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
      const id = '11111111-1111-4111-8111-111111111111';
      const deliver = frame => connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify(frame)});
      const originalError = console.error;
      const errors = [];
      console.error = (...args) => errors.push(args.join(' '));
      try {
        deliver([19, '42', id, '0', [4]]);
        deliver([21, true]);
        bridge._spoonbill.modifyDom = () => {
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:during-failure');
          throw new Error('synthetic-private-DOM-value');
        };
        deliver([20, id, '0', '1', [4]]);
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'counter:after-failure');
        bridge._onCallback(CallbackType.HEARTBEAT);
        assert.deepEqual(sent, [[6]]);
        assert.deepEqual(disconnected, [true]);
        assert.equal(bridge._viewRevision, '0');
        assert.equal(bridge._applyingView, false);
        assert.deepEqual(errors, ['Spoonbill view recovery failed']);
      } finally { console.error = originalError; bridge.destroy(); }
    });

    await t.test('submit captures declared values in one message and retains no submit event', () => {
      const password = { value: 'synthetic-password:a&b=+é' };
      const email = { value: 'synthetic@example.invalid' };
      const checkbox = { checked: false };
      const undeclared = { get value() { assert.fail('undeclared inputs must not be read'); } };
      const form = new FormFake(
        [['email', 'text'], ['password', 'text'], ['remember', 'checkbox']],
        { email, password, remember: checkbox, undeclared },
      );
      const { document } = browser(form);
      const messages = [];
      const spoonbill = new Spoonbill({}, (type, args) => messages.push([type, args]));
      try {
        const event = submit(document, form);
        password.value = 'changed-after-submit';
        email.value = 'changed-after-submit';
        checkbox.checked = true;
        assert.equal(messages.length, 1, 'one callback, without a later property-extraction exchange');
        assert.equal(messages[0][0], CallbackType.DOM_EVENT);
        const prefix = `0:${form.vId}:submit:`;
        assert.ok(messages[0][1].startsWith(prefix));
        assert.deepEqual([...new URLSearchParams(messages[0][1].slice(prefix.length))], [
          ['email', 'synthetic@example.invalid'],
          ['password', 'synthetic-password:a&b=+é'],
          ['remember', 'false'],
        ]);
        assert.equal(Object.hasOwn(spoonbill.eventData, `${form.vId}_submit`), false);
        assert.equal(Object.values(spoonbill.eventData).includes(event), false);
        assert.deepEqual(Object.keys(spoonbill.eventData), []);
      } finally {
        spoonbill.destroy();
      }
    });

    await t.test('enabled protocol debug never logs outbound or inbound synthetic secrets', () => {
      const secret = 'synthetic-password-do-not-log';
      const form = new FormFake([['password', 'text']], { password: { value: secret } });
      const { document } = browser(form);
      const messages = [];
      const connection = { dispatcher: new EventTargetFake(), send: message => messages.push(message) };
      const logs = [];
      const originalLog = console.log;
      const bridge = new Bridge({ heartbeat: { interval: '0' } }, connection);
      try {
        setProtocolDebugEnabled(true);
        console.log = (...args) => logs.push(args.map(String).join(' '));
        submit(document, form);
        assert.equal(messages.length, 1);
        assert.ok(messages[0].includes(secret), 'test must actually send its synthetic secret');
        // Procedure 9 is a no-op, allowing a secret-bearing response to exercise
        // the real inbound debug path without changing the minimal fake DOM.
        connection.dispatcher.dispatchEvent({ type: 'message', data: JSON.stringify([9, secret]) });
        assert.ok(logs.some(line => line.includes('<-')), 'outbound debugging must be enabled');
        assert.ok(logs.some(line => line.includes('->')), 'inbound debugging must be enabled');
        assert.equal(logs.some(line => line.includes(secret)), false, 'protocol diagnostics must contain metadata only');
      } finally {
        console.log = originalLog;
        setProtocolDebugEnabled(false);
        bridge.destroy();
      }
    });

    await t.test('a declared submit field uses the chosen same-name button instead of namedItem fallback', () => {
      const selected = { name: 'role', value: 'reviewer' };
      const alternative = { name: 'role', value: 'operator' };
      const fallback = { get value() { assert.fail('same-name submit buttons must use event.submitter'); } };
      const form = new FormFake([['role', 'text']], { role: fallback });
      selected.form = form;
      alternative.form = form;
      const { document } = browser(form);
      const messages = [];
      const spoonbill = new Spoonbill({}, (type, args) => messages.push([type, args]));
      try {
        submit(document, form, selected);
        submit(document, form, alternative);
        assert.equal(messages.length, 2, 'one message for each explicit button submission');
        const prefix = `0:${form.vId}:submit:`;
        assert.deepEqual(messages.map(([type, payload]) => {
          assert.equal(type, CallbackType.DOM_EVENT);
          assert.ok(payload.startsWith(prefix));
          return [...new URLSearchParams(payload.slice(prefix.length))];
        }), [[['role', 'reviewer']], [['role', 'operator']]]);
        assert.deepEqual(Object.keys(spoonbill.eventData), []);
      } finally {
        spoonbill.destroy();
      }
    });

    await t.test('a ready recovery callback cannot overtake an older callback whose compression is stalled', async () => {
      const {window} = browser();
      const entered = deferred(), release = deferred();
      let compressors = 0;
      globalThis.CompressionStream = class extends TransformStream {
        constructor() {
          const index = compressors++;
          super({async transform(chunk, controller) {
            if (index === 0) { entered.resolve(); await release.promise; }
            controller.enqueue(chunk);
          }});
        }
      };
      const connection = new Connection('synthetic-session', '/', window.location, {wsc: true});
      connection._connectUsingWebSocket();
      const socket = connection._webSocket;
      const old = connection._send('[1,"show:old"]');
      await entered.promise;
      const recovery = connection._send('[10,"1"]');
      // All ready stream work can complete; only the earlier compressor is held.
      await new Promise(setImmediate);
      assert.equal(socket.sent.length, 0, 'the recovery callback overtook an older user action');
      release.resolve();
      await Promise.all([old, recovery]);
      assert.deepEqual(await Promise.all(socket.sent.map(blob => blob.text())), ['[1,"show:old"]', '[10,"1"]']);
    });

    await t.test('a replacement releases queued callbacks without waiting for the old compressor', async () => {
      const {window} = browser();
      const gate = delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, {wsc: true});
      connection._connectUsingWebSocket();
      const original = connection._webSocket;
      const active = connection._send('synthetic-active');
      await gate.entered;
      const queued = connection._send('synthetic-queued');
      connection._connectUsingWebSocket();
      const replacement = connection._webSocket;
      replacement.protocol = 'json';
      await queued;
      await connection._send('fresh');
      assert.equal(replacement.sent.length, 1, 'new socket waited for old compression');
      gate.release();
      await active;
      assert.equal(original.sent.length, 0);
      assert.equal(await replacement.sent[0].text(), 'fresh');
    });

    await t.test('pending callback queue overflow closes the socket and drops its queued payloads', async () => {
      const {window} = browser();
      const gate = delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, {wsc: true});
      connection._connectUsingWebSocket();
      const socket = connection._webSocket;
      const active = connection._send('synthetic-active');
      await gate.entered;
      const queued = Array.from({length: 129}, (_, index) => connection._send('synthetic-queued-' + index));
      await Promise.all(queued);
      assert.equal(socket.readyState, WebSocketFake.CLOSED);
      gate.release();
      await active;
      assert.equal(socket.sent.length, 0);
    });

    await t.test('authentication handoff drops queued and in-flight callbacks on the original socket', async () => {
      const {window} = browser();
      const gate = delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, {auth: true, wsc: true});
      connection._connectUsingWebSocket();
      const socket = connection._webSocket;
      const active = connection._send('synthetic-active');
      await gate.entered;
      const queued = connection._send('synthetic-queued');
      const originalFetch = globalThis.fetch;
      const delivery = deferred();
      globalThis.fetch = () => delivery.promise;
      try {
        const committing = connection.commitAuthentication('123e4567-e89b-12d3-a456-426614174000');
        await queued;
        gate.release();
        await active;
        assert.equal(socket.sent.length, 0);
        delivery.resolve({status: 204});
        await committing;
        assert.equal(socket.readyState, WebSocketFake.CLOSED);
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('compression finishing after reconnect never sends the old payload on either socket', async () => {
      const { window } = browser();
      const gate = delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, { wsc: true });
      connection._connectUsingWebSocket();
      const originalSocket = connection._webSocket;
      // Await the actual internal send because the existing public send returns void.
      // Capture rejections immediately so a fail-closed implementation is not unhandled.
      const sending = connection._send('synthetic-password-before-reconnect').then(
        () => ({ rejected: false }), () => ({ rejected: true }),
      );
      await gate.entered;
      connection._connectUsingWebSocket();
      const replacementSocket = connection._webSocket;
      assert.notEqual(originalSocket, replacementSocket);
      gate.release();
      await sending;
      assert.equal(originalSocket.sent.length, 0, 'a superseded socket cannot send after suspension');
      assert.equal(replacementSocket.sent.length, 0, 'a reconnect must not replay captured credentials');
    });

    await t.test('compression finishing after closure cannot send to a closed socket', async () => {
      const { window } = browser();
      const gate = delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, { wsc: true });
      connection._connectUsingWebSocket();
      const socket = connection._webSocket;
      let attempted = false;
      socket.send = () => { attempted = true; };
      const sending = connection._send('synthetic-password-before-close').then(() => undefined, () => undefined);
      await gate.entered;
      socket.close();
      gate.release();
      await sending;
      assert.equal(attempted, false, 'check OPEN before attempting the send');
    });

    await t.test('an unchanged open socket still receives exactly one captured message', async () => {
      const { window } = browser();
      const gate = delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, { wsc: true });
      connection._connectUsingWebSocket();
      const socket = connection._webSocket;
      const payload = 'synthetic-successful-submission';
      const sending = connection._send(payload);
      await gate.entered;
      gate.release();
      await sending;
      assert.equal(socket.sent.length, 1);
      assert.equal(await socket.sent[0].text(), payload);
    });

    await t.test('WebSocket compression is not offered by default even when CompressionStream exists', () => {
      const { window } = browser();
      delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location);
      connection._connectUsingWebSocket();
      assert.deepEqual(connection._webSocket.protocols, ['json']);
    });

    await t.test('WebSocket compression can be explicitly enabled', () => {
      const { window } = browser();
      delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, { wsc: true });
      connection._connectUsingWebSocket();
      assert.deepEqual(connection._webSocket.protocols, ['json', 'json-deflate']);
    });

    await t.test('a lost replacement command discovers its committed outcome without replaying application sends', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      connection._connectUsingWebSocket();
      const socket = connection._webSocket;
      const requests = [];
      let reloads = 0;
      let reconnects = 0;
      window.location.reload = () => { reloads++; };
      connection.connect = () => { reconnects++; };
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (url, options) => { requests.push({url, options}); return {status: 204}; };
      try {
        await connection._onClose();
        assert.equal(reloads, 0);
        assert.equal(reconnects, 1);
        assert.equal(requests.length, 1);
        assert.equal(requests[0].url, 'https://example.invalid/auth/recover');
        assert.equal(requests[0].options.body, '');
        assert.equal(requests[0].options.method, 'POST');
        assert.equal(requests[0].options.redirect, 'error');
        assert.equal(socket.sent.length, 0);
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('replacement discovery is bounded across failed reconnects and resets only after readiness', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      let requests = 0;
      let reconnects = 0;
      connection.connect = () => { reconnects++; };
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => { requests++; return {status: 403}; };
      try {
        for (let attempt = 0; attempt < 5; attempt++) await connection._onClose();
        assert.equal(requests, 3);
        assert.equal(reconnects, 5);
        connection.applicationReady();
        await connection._onClose();
        assert.equal(requests, 4);
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('late replacement discovery cannot revive a canceled or superseded connection', async () => {
      for (const cancel of [true, false]) {
        const { window } = browser();
        const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
        const response = deferred();
        let reconnects = 0;
        connection.connect = () => { reconnects++; };
        const originalFetch = globalThis.fetch;
        globalThis.fetch = () => response.promise;
        try {
          const closing = connection._onClose();
          if (cancel) await connection.disconnect(false);
          else connection._webSocket = {};
          response.resolve({status: 204});
          await closing;
          assert.equal(reconnects, 0);
        } finally { globalThis.fetch = originalFetch; }
      }
    });

    await t.test('departed documents never start replacement discovery and cancel an in-flight probe', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      let requests = 0;
      let aborted = 0;
      let reconnects = 0;
      connection.connect = () => { reconnects++; };
      const originalFetch = globalThis.fetch;
      globalThis.fetch = (_url, options) => {
        requests++;
        return new Promise((resolve, reject) => options.signal.addEventListener('abort', () => {
          aborted++;
          reject(new Error('Synthetic document departure'));
        }));
      };
      try {
        window.dispatchEvent({type: 'pagehide'});
        await connection._onClose();
        assert.equal(requests, 0, 'unload must not start a request in a departed WebKit document');
        assert.equal(reconnects, 0);
        window.dispatchEvent({type: 'pageshow'});
        const closing = connection._onClose();
        assert.equal(requests, 1);
        window.dispatchEvent({type: 'pagehide'});
        await closing;
        assert.equal(aborted, 1);
        assert.equal(reconnects, 0, 'a departed document must not reconnect after the probe settles');
        assert.equal(connection._replacementAbort, null);
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('early navigation blocks discovery before pagehide and a fresh ready view restores it after cancellation', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      let requests = 0;
      let reconnects = 0;
      connection.connect = () => { reconnects++; };
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => { requests++; return {status: 403}; };
      try {
        window.dispatchEvent({type: 'beforeunload'});
        await connection._onClose();
        assert.equal(requests, 0, 'early WebKit unload must not initiate discovery');
        assert.equal(reconnects, 1, 'canceled departure keeps the existing ordinary reconnect path');
        connection.applicationReady();
        await connection._onClose();
        assert.equal(requests, 1, 'fresh authority after cancellation permits later discovery');
        assert.equal(reconnects, 2);
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('trusted interaction after canceled departure restores recovery even before old authority can become ready', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      let requests = 0;
      connection.connect = () => {};
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => { requests++; return {status: 204}; };
      try {
        window.dispatchEvent({type: 'beforeunload'});
        await connection._onClose();
        window.dispatchEvent({type: 'pointerdown', isTrusted: false});
        await connection._onClose();
        assert.equal(requests, 0);
        window.dispatchEvent({type: 'keydown', isTrusted: true});
        await connection._onClose();
        assert.equal(requests, 1, 'recovery cannot depend on already-revoked authority reaching readiness');
        connection._replacementRecoveryAttempts = 0;
        window.dispatchEvent({type: 'pagehide'});
        window.dispatchEvent({type: 'pointerdown', isTrusted: true});
        await connection._onClose();
        assert.equal(requests, 1, 'interaction never reopens an actually departed document');
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('a lost recovery response permits a second bounded probe after the old-cookie socket is denied', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      let requests = 0;
      let reconnects = 0;
      let successorCookieAccepted = false;
      connection.connect = () => {
        reconnects++;
        if (successorCookieAccepted) connection.applicationReady();
      };
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (_url, options) => {
        assert.equal(options.body, '', 'discovery never repeats the application proof');
        requests++;
        if (requests === 1) throw new Error('Synthetic lost old-node response');
        successorCookieAccepted = true;
        return {status: 204};
      };
      try {
        await connection._onClose();
        assert.equal(connection._replacementRecoveryAttempts, 1);
        // The first fresh socket still carries the old cookie and is denied.
        await connection._onClose();
        assert.equal(requests, 2);
        assert.equal(reconnects, 2);
        assert.equal(connection._wasReady, true);
        assert.equal(connection._replacementRecoveryAttempts, 0, 'only authorized readiness resets the period');
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('BFCache restoration reschedules one canceled reconnect without duplicating live sockets or reviving explicit stop', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      const timers = [];
      let sockets = 0;
      let closes = 0;
      connection._webSocket = {readyState: WebSocketFake.CLOSED, close() { this.readyState = WebSocketFake.CLOSED; }};
      connection._wasConnected = true;
      connection.dispatcher.addEventListener('close', () => { closes++; });
      connection._connectUsingConnectionType = () => {
        sockets++;
        connection._webSocket = {readyState: WebSocketFake.OPEN, close() { this.readyState = WebSocketFake.CLOSED; }};
      };
      const originalSetTimeout = globalThis.setTimeout;
      const originalClearTimeout = globalThis.clearTimeout;
      globalThis.setTimeout = (callback, delay) => {
        const timer = {callback, delay, canceled: false}; timers.push(timer); return timer;
      };
      globalThis.clearTimeout = timer => { timer.canceled = true; };
      try {
        connection.connect();
        window.dispatchEvent({type: 'pagehide'});
        assert.equal(timers[0].canceled, true);
        window.dispatchEvent({type: 'pageshow', persisted: true});
        window.dispatchEvent({type: 'pageshow', persisted: true});
        assert.equal(timers.length, 2);
        assert.equal(closes, 1, 'the old bridge retires before opening a restored transport');
        timers[1].callback();
        assert.equal(sockets, 1);
        window.dispatchEvent({type: 'pagehide'});
        window.dispatchEvent({type: 'pageshow', persisted: true});
        assert.equal(timers.length, 2, 'a restored live socket retires before any fresh connection is scheduled');
        assert.equal(connection._webSocket.readyState, WebSocketFake.CLOSED);
        await connection.disconnect(false);
        window.dispatchEvent({type: 'pagehide'});
        window.dispatchEvent({type: 'pageshow', persisted: true});
        assert.equal(timers.length, 2, 'explicit disconnect remains stopped after restore');
      } finally {
        globalThis.setTimeout = originalSetTimeout;
        globalThis.clearTimeout = originalClearTimeout;
      }
    });

    await t.test('a restored live socket retires its terminal Bridge and accepts fresh callbacks only after new readiness', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      const bridges = [];
      const sent = [];
      const reconnect = deferred();
      let retired = 0;
      let reconnects = 0;
      connection.send = value => sent.push(JSON.parse(value));
      connection.connect = () => { reconnects++; reconnect.resolve(); };
      connection.dispatcher.addEventListener('open', () => {
        bridges.push(new Bridge({auth: true, heartbeat: {interval: '0'}}, connection));
      });
      connection.dispatcher.addEventListener('close', () => { bridges.at(-1)?.destroy(); });
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async () => ({status: 403});
      try {
        connection._connectUsingWebSocket();
        const previous = connection._webSocket;
        previous.dispatchEvent({type: 'open'});
        connection._onMessage('[21,false]');
        const oldBridge = bridges[0];
        previous.close = () => {
          retired++;
          previous.readyState = WebSocketFake.CLOSED;
          previous.dispatchEvent({type: 'close'});
        };
        window.dispatchEvent({type: 'pagehide'});
        assert.equal(oldBridge._sensitive.terminal, true);
        oldBridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'old:blocked');
        window.dispatchEvent({type: 'pageshow', persisted: true});
        window.dispatchEvent({type: 'pageshow', persisted: true});
        await reconnect.promise;
        assert.equal(retired, 1);
        assert.equal(reconnects, 1);
        assert.equal(previous.readyState, WebSocketFake.CLOSED);
        assert.equal(sent.length, 0);
        connection._connectUsingWebSocket();
        connection._webSocket.dispatchEvent({type: 'open'});
        const fresh = bridges.at(-1);
        assert.notEqual(fresh, oldBridge);
        fresh._onCallback(CallbackType.CUSTOM_CALLBACK, 'early:blocked');
        assert.equal(sent.length, 0);
        connection._onMessage('[21,false]');
        fresh._onCallback(CallbackType.CUSTOM_CALLBACK, 'fresh:allowed');
        assert.deepEqual(sent, [[CallbackType.CUSTOM_CALLBACK, 'fresh:allowed']]);
      } finally {
        globalThis.fetch = originalFetch;
        bridges.forEach(bridge => bridge.destroy());
      }
    });

    await t.test('authentication completion sends only its handle over HTTP and reconnects the guarded socket', async () => {
      const { window } = browser();
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      const initialSockets = WebSocketFake.instances.length;
      connection._connectUsingWebSocket();
      const originalSocket = connection._webSocket;
      originalSocket.protocol = 'json';
      connection.send('synthetic-login-action-before-handoff');
      assert.equal(originalSocket.sent.length, 1);
      const delivery = deferred();
      const requests = [];
      const originalFetch = globalThis.fetch;
      const originalSetTimeout = globalThis.setTimeout;
      const originalClearTimeout = globalThis.clearTimeout;
      globalThis.fetch = (url, options) => {
        requests.push({url, options});
        connection.send('synthetic-password-during-handoff');
        return delivery.promise;
      };
      globalThis.setTimeout = (callback, delay) => {
        if (delay !== 5000) callback();
        return {callback, delay};
      };
      globalThis.clearTimeout = () => {};
      originalSocket.close = () => {
        originalSocket.readyState = WebSocketFake.CLOSED;
        originalSocket.dispatchEvent({type: 'close'});
      };
      try {
        const committing = connection.commitAuthentication(completionId);
        assert.equal(requests.length, 1);
        assert.equal(requests[0].options.method, 'POST');
        assert.equal(requests[0].options.body, completionId);
        assert.deepEqual(requests[0].options.headers, {'Content-Type': 'text/plain'});
        assert.equal(requests[0].options.credentials, 'same-origin');
        assert.equal(requests[0].options.mode, 'same-origin');
        assert.equal(requests[0].options.redirect, 'error');
        assert.equal(requests[0].options.cache, 'no-store');
        assert.equal(originalSocket.sent.length, 1, 'an in-flight handoff must suppress later socket sends');
        delivery.resolve({status: 204});
        await committing;
        assert.equal(originalSocket.readyState, WebSocketFake.CLOSED);
        assert.equal(WebSocketFake.instances.length, initialSockets + 2, 'success should permit a fresh socket');
        assert.notEqual(connection._webSocket, originalSocket);
        assert.equal(connection._webSocket.sent.length, 0, 'no old credential payload may replay on the new socket');
      } finally {
        globalThis.fetch = originalFetch;
        globalThis.setTimeout = originalSetTimeout;
        globalThis.clearTimeout = originalClearTimeout;
      }
    });

    for (const pendingProcessing of [false, true]) {
      await t.test(`successful HTTP handoff skips discovery with ${pendingProcessing ? 'delayed message processing' : 'a delayed socket close'}`, async () => {
        const { window } = browser();
        const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
        connection._connectUsingWebSocket();
        const socket = connection._webSocket;
        const delivery = deferred();
        const processing = deferred();
        const requests = [];
        let reconnects = 0;
        let closing;
        connection.connect = () => { reconnects++; connection._connectUsingWebSocket(); };
        connection._onClose = () => { closing = Connection.prototype._onClose.call(connection); return closing; };
        const originalFetch = globalThis.fetch;
        globalThis.fetch = (url, options) => {
          requests.push({url, body: options.body});
          return requests.length === 1 ? delivery.promise : Promise.resolve({status: 204});
        };
        try {
          const committing = connection.commitAuthentication('123e4567-e89b-12d3-a456-426614174000');
          if (pendingProcessing) {
            connection._processing = processing.promise;
            socket.dispatchEvent({type: 'close'});
          }
          delivery.resolve({status: 204});
          await committing;
          assert.equal(connection.authenticationPending, false);
          assert.equal(reconnects, 0);
          if (pendingProcessing) processing.resolve();
          else socket.dispatchEvent({type: 'close'});
          await closing;
          assert.equal(reconnects, 1);
          assert.deepEqual(requests, [{url: 'https://example.invalid/auth/complete', body: '123e4567-e89b-12d3-a456-426614174000'}]);
          assert.equal(connection._replacementRecoveryAttempts, 0, 'intentional delivery preserves the discovery budget');
          const freshSocket = connection._webSocket;
          assert.notEqual(freshSocket, socket);
          assert.equal(freshSocket.sent.length, 0);
          freshSocket.dispatchEvent({type: 'close'});
          await closing;
          assert.equal(reconnects, 2);
          assert.deepEqual(requests[1], {url: 'https://example.invalid/auth/recover', body: ''});
          assert.equal(connection._replacementRecoveryAttempts, 1, 'an unexpected fresh-socket close still discovers');
        } finally { globalThis.fetch = originalFetch; }
      });
    }

    await t.test('lost completion responses retry only the same UUID handle', async () => {
      const { window } = browser();
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      connection._connectUsingWebSocket();
      connection._webSocket.protocol = 'json';
      const bodies = [];
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (_url, options) => {
        bodies.push(options.body);
        connection.send('synthetic-factor-during-retry');
        if (bodies.length < 3) throw new TypeError('simulated lost response');
        return {status: 204};
      };
      try {
        await connection.commitAuthentication(completionId);
        assert.deepEqual(bodies, [completionId, completionId, completionId]);
        assert.equal(connection._webSocket.sent.length, 0);
        assert.equal(bodies.some(body => body.includes('synthetic-')), false);
      } finally {
        globalThis.fetch = originalFetch;
      }
    });

    await t.test('explicit uncertain replacement recovery posts only its ceremony lookup and reconnects without replay', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      connection._connectUsingWebSocket();
      const ceremonyId = '123e4567-e89b-12d3-a456-426614174000';
      const requests = [];
      let reconnects = 0;
      connection.disconnect = async reconnect => { assert.equal(reconnect, true); reconnects++; };
      const originalFetch = globalThis.fetch;
      globalThis.fetch = async (url, options) => {
        requests.push({url, options});
        connection.send('never-replay-this-proof');
        return {status: 204}; // NotCommitted, with no replacement cookie.
      };
      try {
        await connection.recoverAuthentication(ceremonyId);
        assert.equal(requests.length, 1);
        assert.equal(requests[0].url, 'https://example.invalid/auth/recover');
        assert.equal(requests[0].options.body, ceremonyId);
        assert.equal(requests[0].options.credentials, 'same-origin');
        assert.equal(connection._webSocket.sent.length, 0);
        assert.equal(reconnects, 1);
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('a completion response from a replaced socket is rejected', async () => {
      const { window } = browser();
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      connection._connectUsingWebSocket();
      const response = deferred();
      const originalFetch = globalThis.fetch;
      globalThis.fetch = () => response.promise;
      try {
        const committing = connection.commitAuthentication(completionId);
        const originalSocket = connection._webSocket;
        connection._connectUsingWebSocket();
        assert.notEqual(connection._webSocket, originalSocket);
        response.resolve({status: 204});
        await assert.rejects(committing, /Authentication connection changed/);
        assert.equal(connection._committingAuthentication, false);
      } finally {
        globalThis.fetch = originalFetch;
      }
    });

    await t.test('guarded WebSocket failures never fall back to long polling', () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      let errors = 0;
      let xhrs = 0;
      connection.dispatcher.addEventListener('error', () => errors++);
      globalThis.XMLHttpRequest = class {
        constructor() { xhrs++; }
      };
      connection._connectUsingConnectionType(ConnectionType.LONG_POLLING);
      assert.equal(errors, 1);
      assert.equal(xhrs, 0);
      assert.equal(connection._connectionType, ConnectionType.LONG_POLLING);
    });

    await t.test('slow authentication delivery pauses heartbeats without consuming the loss limit', async () => {
      const { window } = browser();
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      connection._connectUsingWebSocket();
      connection._webSocket.protocol = 'json';
      const socket = connection._webSocket;
      const delivery = deferred();
      const originalFetch = globalThis.fetch;
      const originalSetTimeout = globalThis.setTimeout;
      const originalClearTimeout = globalThis.clearTimeout;
      const originalSetInterval = globalThis.setInterval;
      const originalClearInterval = globalThis.clearInterval;
      const intervals = [];
      globalThis.fetch = () => delivery.promise;
      globalThis.setTimeout = (callback, delay) => ({callback, delay});
      globalThis.clearTimeout = () => {};
      globalThis.setInterval = (callback, delay) => {
        const interval = {callback, delay};
        intervals.push(interval);
        return interval;
      };
      globalThis.clearInterval = () => {};
      const bridge = new Bridge({heartbeat: {interval: '25', limit: '2'}}, connection);
      try {
        connection.dispatcher.dispatchEvent({type: 'message', data: JSON.stringify([18, completionId])});
        assert.equal(connection.authenticationPending, true);
        assert.equal(intervals.length, 1);
        intervals[0].callback();
        intervals[0].callback();
        intervals[0].callback();
        assert.equal(bridge._awaitingHeartbeat, 0);
        assert.equal(socket.sent.length, 0);

        delivery.resolve({status: 204});
        for (let attempt = 0; attempt < 20 && connection.authenticationPending; attempt++) await Promise.resolve();
        assert.equal(connection.authenticationPending, false);
      } finally {
        bridge.destroy();
        globalThis.fetch = originalFetch;
        globalThis.setTimeout = originalSetTimeout;
        globalThis.clearTimeout = originalClearTimeout;
        globalThis.setInterval = originalSetInterval;
        globalThis.clearInterval = originalClearInterval;
      }
    });

    await t.test('authentication fetch attempts are abort-bounded without real timers', async () => {
      const { window } = browser();
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      connection._connectUsingWebSocket();
      const originalFetch = globalThis.fetch;
      const originalSetTimeout = globalThis.setTimeout;
      const originalClearTimeout = globalThis.clearTimeout;
      const timers = [];
      const bodies = [];
      let aborts = 0;
      globalThis.setTimeout = (callback, delay) => {
        const timer = {callback, delay, fired: false, cleared: false};
        timers.push(timer);
        return timer;
      };
      globalThis.clearTimeout = timer => { timer.cleared = true; };
      globalThis.fetch = (_url, options) => {
        bodies.push(options.body);
        return new Promise((_resolve, reject) => {
          options.signal.addEventListener('abort', () => {
            aborts++;
            reject(new Error('synthetic abort'));
          }, {once: true});
        });
      };
      try {
        const delivery = connection.commitAuthentication(completionId);
        const rejected = assert.rejects(delivery, /Authentication delivery unavailable/);
        for (let attempt = 0; attempt < 3; attempt++) {
          for (let spin = 0; spin < 20 && timers.length <= attempt; spin++) await Promise.resolve();
          const timer = timers[attempt];
          assert.ok(timer, `missing timeout for attempt ${attempt + 1}`);
          assert.equal(timer.delay, 5000);
          timer.fired = true;
          timer.callback();
          if (attempt < 2) {
            for (let spin = 0; spin < 20 && timers.length <= attempt + 1; spin++) await Promise.resolve();
          }
        }
        await rejected;
        assert.equal(timers.length, 3);
        assert.equal(aborts, 3);
        assert.deepEqual(bodies, [completionId, completionId, completionId]);
        assert.ok(timers.every(timer => timer.cleared));
      } finally {
        globalThis.fetch = originalFetch;
        globalThis.setTimeout = originalSetTimeout;
        globalThis.clearTimeout = originalClearTimeout;
      }
    });
  } finally {
    for (const [name, descriptor] of originalGlobals) {
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
    await rm(directory, { recursive: true, force: true });
  }
});
