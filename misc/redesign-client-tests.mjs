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
    for (const name of ['spoonbill.js', 'utils.js', 'connection.js', 'connection-machine.js', 'bridge.js', 'bridge-machine.js', 'sensitive.js', 'protocol.js']) {
      await copyFile(new URL(`../modules/spoonbill/src/main/es6/${name}`, import.meta.url), join(directory, name));
    }
    browser(); // bridge.js reads localStorage when its module is initialized.
    const { Spoonbill } = await import(pathToFileURL(join(directory, 'spoonbill.js')));
    const { CallbackType } = await import(pathToFileURL(join(directory, 'protocol.js')));
    const { Bridge, setProtocolDebugEnabled } = await import(pathToFileURL(join(directory, 'bridge.js')));
    const { Connection, ConnectionType } = await import(pathToFileURL(join(directory, 'connection.js')));
    const { ConnectionLostWidget } = await import(pathToFileURL(join(directory, 'utils.js')));

    await t.test('preset ids register an unkeyed sibling after a keyed element at k + 1', () => {
      // Parsed SSR with presetIds: elements carry their last id segment in `k`;
      // text nodes have none and must continue the count numerically.
      const element = (k, ...childNodes) => ({nodeType: 1, childNodes, getAttribute: name => (name === 'k' ? k : null)});
      const text = () => ({nodeType: 3, childNodes: []});
      const inner = text(), loose = text(), tail = text(), last = text();
      const first = element('1', inner), third = element('3'), ninth = element('9');
      const root = element('1', first, loose, third, tail, ...Array.from({length: 4}, (_, n) => element(String(n + 5))), ninth, last);
      // registerRoot only reads config and the registry, so it runs without a document.
      const client = {config: {kid: true}, els: {}};
      Spoonbill.prototype.registerRoot.call(client, root);
      assert.equal(client.els['1_1'], first);
      assert.equal(client.els['1_1_1'], inner);
      assert.equal(client.els['1_2'], loose);
      assert.equal(client.els['1_4'], tail);
      assert.equal(client.els['1_10'], last, 'multi-digit counts are numeric');
      assert.equal(loose.vId, '1_2');
      assert.ok(!('1_11' in client.els) && !('1_31' in client.els) && !('1_91' in client.els), 'no concatenated ids');
    });

    await t.test('a removal whose child the page never had counts as applied; a missing parent does not', () => {
      // The page renderer writes an empty text as nothing, so the parsed page
      // has no node for it. When the view later removes that text, the patch
      // names a child that the page never had, and the page is still in step.
      const removed = [];
      const parent = {nodeType: 1, childNodes: [], removeChild: child => removed.push(child)};
      const span = {nodeType: 1, childNodes: []};
      const client = Object.assign(Object.create(Spoonbill.prototype), {els: {'1': parent, '1_1': span}});
      assert.equal(client.modifyDom([2, '1', '1_2']), true, 'the empty text 1_2 has no node');
      assert.deepEqual(removed, []);
      assert.equal(client.modifyDom([2, '1', '1_1']), true);
      assert.deepEqual(removed, [span]);
      assert.equal(client.modifyDom([2, '9', '9_1']), false, 'a missing parent');
    });

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
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:fresh');
          assert.deepEqual(sent.at(-1), [1, 'show:fresh']);
        } finally { bridge.destroy(); }
      }
    });

    await t.test('unguarded actions remain available after a canceled departure without a recovery protocol', () => {
      browser();
      const sent = [];
      // The history event below waits for a view whose capability is not known
      // yet, so the bridge reports it unresolved when it goes away.
      const connection = {dispatcher: new EventTargetFake(), send: value => sent.push(JSON.parse(value)),
        navigationUnresolved() {}, disconnect: () => assert.fail('Unexpected reconnect')};
      const bridge = new Bridge({heartbeat: {interval: '0'}}, connection);
      try {
        window.dispatchEvent({type: 'beforeunload'});
        bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'ordinary:fresh');
        bridge._onCallback(CallbackType.HISTORY, '/different');
        assert.deepEqual(sent, [[0, '0:1:click'], [1, 'ordinary:fresh'], [3, '/different']]);
      } finally { bridge.destroy(); }
    });

    // The history-ordering fix: an unguarded page drops its user events from a
    // history event until its view confirms the navigation (procedure 24).
    const navigationPage = (longPolling = true) => {
      const timers = new Set();
      const saved = {setTimeout: globalThis.setTimeout, clearTimeout: globalThis.clearTimeout};
      globalThis.setTimeout = (callback, delay) => { const timer = {callback, delay}; timers.add(timer); return timer; };
      globalThis.clearTimeout = timer => { timers.delete(timer); };
      browser();
      const sent = [], answers = [];
      const connection = {dispatcher: new EventTargetFake(), longPolling,
        page: longPolling ? '33333333-3333-4333-8333-333333333333' : undefined,
        transportEpoch: {}, paused: false, resumeCount: 0, navigationBound: 1500, recovered: 0,
        send(value, onAnswer, kind) { sent.push({kind, message: JSON.parse(value)}); if (onAnswer) answers.push(onAnswer); return true; },
        recoverNavigation() { this.recovered++; },
        unresolved: 0, navigationUnresolved() { this.unresolved++; },
        disconnect: () => assert.fail('Unexpected reconnect')};
      const bridge = new Bridge({heartbeat: {interval: '0'}}, connection);
      const frame = data => connection.dispatcher.dispatchEvent({type: 'message', data});
      const fire = () => {
        const [timer] = timers;
        assert.ok(timer, 'Expected a running deadline');
        timers.delete(timer); timer.callback();
      };
      const kinds = () => sent.map(entry => entry.kind);
      const restore = () => { bridge.destroy(); Object.assign(globalThis, saved); };
      return {connection, bridge, sent, answers, timers, frame, fire, kinds, restore};
    };

    await t.test('an unguarded page drops user events after Back until its view confirms the route', () => {
      const page = navigationPage();
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        page.bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'act:now');
        assert.deepEqual(page.kinds(), ['history'], 'a user event went out before the confirmation');
        assert.equal(page.timers.size, 0, 'the wait for the confirmation starts with the answer to the POST');
        page.answers[0](true);
        assert.deepEqual([...page.timers].map(timer => timer.delay), [1500]);
        page.frame('[24,1]');
        assert.equal(page.timers.size, 0, 'the confirmation ends the wait');
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.kinds(), ['history', 'other']);
        // The click keeps the page and revision of the display on which it was made.
        assert.deepEqual(page.sent.at(-1).message, [7, '33333333-3333-4333-8333-333333333333:0:0:0:1:click']);
        assert.equal(page.connection.recovered, 0);
      } finally { page.restore(); }
    });

    await t.test('Back then Forward: only the confirmation of Forward reopens the input', () => {
      const page = navigationPage();
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/1');
        page.answers[0](true);
        page.answers[1](true);
        page.frame('[24,1]');
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.kinds(), ['history', 'history'], 'the confirmation of Back reopened the input');
        page.frame('[24,2]');
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.kinds(), ['history', 'history', 'other']);
        assert.equal(page.timers.size, 0);
      } finally { page.restore(); }
    });

    await t.test('a confirmation that does not come in time reloads the page; a pause, and the resume after it, give it more time', () => {
      const page = navigationPage();
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        page.answers[0](true);
        page.connection.paused = true;
        page.fire();
        assert.equal(page.connection.recovered, 0, 'a paused page recovered');
        page.connection.paused = false;
        page.connection.resumeCount++;
        page.connection.transportEpoch = {};
        page.fire();
        assert.equal(page.connection.recovered, 0, 'a page that resumed recovered at once');
        page.fire();
        assert.equal(page.connection.recovered, 1);
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.kinds(), ['history'], 'the input reopened without a confirmation');
      } finally { page.restore(); }
    });

    await t.test('connect() on an active page gives a navigation no more time', () => {
      // The code review of 2026-11-24, finding 5: a new transport before every
      // deadline put the recovery off for ever.
      const page = navigationPage();
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        page.answers[0](true);
        page.connection.transportEpoch = {};
        page.fire();
        assert.equal(page.connection.recovered, 1, 'a replaced transport put the recovery off');
      } finally { page.restore(); }
    });

    await t.test('the answer callback of a history POST says whether the navigation was delivered', () => {
      // The code review of 2026-11-24, finding 1: Back was confirmed and its
      // answer lost while Forward waited; the connection must post Forward.
      const page = navigationPage();
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/1');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/2');
        page.frame('[24,1]');
        assert.equal(page.answers[0](false), true, 'a confirmed navigation counts as delivered');
        assert.equal(page.connection.recovered, 0);
        assert.equal(page.answers[1](false), false);
        assert.equal(page.connection.recovered, 1);
      } finally { page.restore(); }
    });

    await t.test('a navigation before the first frame of the view waits until the view says how it confirms', () => {
      // The code review of 2026-11-24, finding 3: Back before [17,true] went
      // unprotected, and a click reached the old route's handler.
      const capable = navigationPage();
      try {
        capable.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        capable.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        capable.frame('[17,true]');
        capable.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(capable.kinds(), ['history'], 'a click went out before the confirmation');
        capable.frame('[24,1]');
        capable.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(capable.kinds(), ['history', 'other']);
      } finally { capable.restore(); }
      // A view of an older server confirms none: its first frame lets the page go on.
      const older = navigationPage();
      try {
        older.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        older.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(older.kinds(), ['history']);
        older.frame('[17]');
        older.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(older.kinds(), ['history', 'other']);
        older.answers[0](true);
        assert.equal(older.timers.size, 0, 'a page that waits for nothing has no deadline');
      } finally { older.restore(); }
    });

    await t.test('a failed history POST recovers, but not after the confirmation came first', () => {
      const failed = navigationPage();
      try {
        failed.frame('[17,true]');
        failed.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        failed.answers[0](false);
        assert.equal(failed.connection.recovered, 1);
      } finally { failed.restore(); }
      const proven = navigationPage();
      try {
        proven.frame('[17,true]');
        proven.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        proven.frame('[24,1]');
        proven.answers[0](false);
        assert.equal(proven.connection.recovered, 0, 'a late POST error undid a confirmed navigation');
        assert.equal(proven.timers.size, 0);
      } finally { proven.restore(); }
    });

    await t.test('a page whose view does not confirm navigations waits for none', () => {
      // A view of an older server: its first frame has no flag.
      const page = navigationPage();
      try {
        page.frame('[17]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.kinds(), ['history', 'other']);
        page.answers[0](true);
        assert.equal(page.timers.size, 0);
        // A confirmation that it never announced changes nothing.
        page.frame('[24,1]');
        assert.equal(page.connection.recovered, 0);
      } finally { page.restore(); }
    });

    await t.test('on a WebSocket the wait for the confirmation starts with the history event', () => {
      const page = navigationPage(false);
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        assert.deepEqual(page.answers, [], 'a WebSocket message has no answer');
        assert.deepEqual([...page.timers].map(timer => timer.delay), [1500]);
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.kinds(), ['history']);
        page.frame('[24,1]');
        page.bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
        assert.deepEqual(page.sent.at(-1).message, [0, '0:1:click']);
      } finally { page.restore(); }
    });

    await t.test('the page skips the URLs of the view while a navigation waits, and adds the URLs after it', () => {
      // The code review of 2026-11-24, finding 2: the URL of Back's route came
      // while Forward waited and replaced Forward's entry of the tab.
      const page = navigationPage();
      const entries = [];
      window.history = {pushState: (state, title, url) => entries.push(['push', url]),
        replaceState: (state, title, url) => entries.push(['replace', url])};
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/1');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/2');
        page.frame('[6,"/orders/1"]');
        page.frame('[24,1]');
        page.frame('[6,"/orders/2"]');
        page.frame('[24,2]');
        // A transition of the application after the navigation adds an entry.
        page.frame('[6,"/orders/3"]');
        assert.deepEqual(entries, [['push', '/orders/3']]);
      } finally { page.restore(); delete window.history; }
    });

    await t.test('the URL that the view sends for the navigation that the page waits for takes the entry that the user chose', () => {
      // The re-review of 2026-11-24: the page skipped every URL while it
      // waited, the server's URL of the route that won too, so the tab kept
      // the alias that the user had opened.
      const page = navigationPage();
      const entries = [];
      window.history = {pushState: (state, title, url) => entries.push(['push', url]),
        replaceState: (state, title, url) => entries.push(['replace', url])};
      try {
        page.frame('[17,true]');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0001');
        page.bridge._onCallback(CallbackType.HISTORY, '/orders/0002');
        // Back's URLs come while Forward waits: skipped, numbered or not.
        page.frame('[6,"/orders/1"]');
        page.frame('[6,"/orders/1",1]');
        page.frame('[24,1]');
        page.frame('[6,"/orders/2"]');
        page.frame('[6,"/orders/2",2]');
        page.frame('[24,2]');
        assert.deepEqual(entries, [['replace', '/orders/2']]);
      } finally { page.restore(); delete window.history; }
    });

    await t.test('a bridge that goes away while a navigation waits tells its connection; one with none pending does not', () => {
      // The re-review of 2026-11-24: the bridge of a reconnect knew nothing of
      // the navigation, and took a click on the display that the user left.
      const page = navigationPage(false);
      page.frame('[17,true]');
      page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
      page.restore();
      assert.equal(page.connection.unresolved, 1);
      const settled = navigationPage(false);
      settled.frame('[17,true]');
      settled.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
      settled.frame('[24,1]');
      settled.restore();
      assert.equal(settled.connection.unresolved, 0);
    });

    await t.test('Back to an entry without history state sends the path and the query of that entry', () => {
      // The third review of 2026-11-24: the page sent the path that it had at
      // its start, without the query, so `/orders?stage=1` was routed as
      // `/orders`, and the URL of that route took the entry.
      const page = navigationPage(false);
      try {
        page.frame('[17,true]');
        window.location.pathname = '/orders';
        window.location.search = '?stage=1';
        window.dispatchEvent({type: 'popstate', state: null});
        assert.deepEqual(page.sent.at(-1).message, [3, '/orders?stage=1']);
      } finally { page.restore(); }
    });

    await t.test('a bridge that was destroyed takes no late answer to its history POST', () => {
      const page = navigationPage();
      page.frame('[17,true]');
      page.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
      page.restore();
      page.answers[0](false);
      assert.equal(page.connection.recovered, 0);
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
          if (terminal === 'pagehide') window.dispatchEvent({type: 'pagehide'});
          else bridge.destroy();
          bridge._onCallback(CallbackType.DOM_EVENT, '0:1:click');
          bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'show:departed');
          bridge._onCallback(CallbackType.HISTORY, '/different');
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
      const socket = WebSocketFake.instances.at(-1);
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
      const original = WebSocketFake.instances.at(-1);
      const active = connection._send('synthetic-active');
      await gate.entered;
      const queued = connection._send('synthetic-queued');
      connection._connectUsingWebSocket();
      const replacement = WebSocketFake.instances.at(-1);
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
      const socket = WebSocketFake.instances.at(-1);
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
      const socket = WebSocketFake.instances.at(-1);
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
      const originalSocket = WebSocketFake.instances.at(-1);
      // Await the actual internal send because the existing public send returns void.
      // Capture rejections immediately so a fail-closed implementation is not unhandled.
      const sending = connection._send('synthetic-password-before-reconnect').then(
        () => ({ rejected: false }), () => ({ rejected: true }),
      );
      await gate.entered;
      connection._connectUsingWebSocket();
      const replacementSocket = WebSocketFake.instances.at(-1);
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
      const socket = WebSocketFake.instances.at(-1);
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
      const socket = WebSocketFake.instances.at(-1);
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
      assert.deepEqual(WebSocketFake.instances.at(-1).protocols, ['json']);
    });

    await t.test('WebSocket compression can be explicitly enabled', () => {
      const { window } = browser();
      delayedCompression();
      const connection = new Connection('synthetic-session', '/', window.location, { wsc: true });
      connection._connectUsingWebSocket();
      assert.deepEqual(WebSocketFake.instances.at(-1).protocols, ['json', 'json-deflate']);
    });

    await t.test('a lost replacement command discovers its committed outcome without replaying application sends', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      connection._connectUsingWebSocket();
      const socket = WebSocketFake.instances.at(-1);
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
          else connection._connectUsingWebSocket();
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
        window.dispatchEvent({type: 'pagehide'});
        assert.equal(aborted, 1, 'settled discovery cannot be aborted again');
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
        connection.applicationReady();
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
      let readiness = 0;
      connection.dispatcher.addEventListener('ready', () => { readiness++; });
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
        assert.equal(requests, 1);
        // The first fresh socket still carries the old cookie and is denied.
        await connection._onClose();
        assert.equal(requests, 2);
        assert.equal(reconnects, 2);
        assert.equal(readiness, 1);
        await connection._onClose();
        await connection._onClose();
        assert.equal(requests, 4, 'authorized readiness restores the bounded discovery budget');
      } finally { globalThis.fetch = originalFetch; }
    });

    await t.test('BFCache restoration reschedules one canceled reconnect without duplicating live sockets or reviving explicit stop', async () => {
      const { window } = browser();
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true, replacement: true });
      const timers = [];
      let sockets = 0;
      let closes = 0;
      connection._connectUsingWebSocket();
      connection._onOpen();
      WebSocketFake.instances.at(-1).close();
      connection.dispatcher.addEventListener('close', () => { closes++; });
      connection._connectUsingConnectionType = () => {
        sockets++;
        connection._connectUsingWebSocket();
        connection._onOpen();
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
        assert.equal(WebSocketFake.instances.at(-1).readyState, WebSocketFake.CLOSED);
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
        const previous = WebSocketFake.instances.at(-1);
        previous.dispatchEvent({type: 'open'});
        connection._onMessage('[21,false]');
        const oldBridge = bridges[0];
        previous.close = () => {
          retired++;
          previous.readyState = WebSocketFake.CLOSED;
          previous.dispatchEvent({type: 'close'});
        };
        window.dispatchEvent({type: 'pagehide'});
        oldBridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'old:blocked');
        window.dispatchEvent({type: 'pageshow', persisted: true});
        window.dispatchEvent({type: 'pageshow', persisted: true});
        await reconnect.promise;
        assert.equal(retired, 1);
        assert.equal(reconnects, 1);
        assert.equal(previous.readyState, WebSocketFake.CLOSED);
        assert.equal(sent.length, 0);
        connection._connectUsingWebSocket();
        WebSocketFake.instances.at(-1).dispatchEvent({type: 'open'});
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
      const originalSocket = WebSocketFake.instances.at(-1);
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
        assert.notEqual(WebSocketFake.instances.at(-1), originalSocket);
        assert.equal(WebSocketFake.instances.at(-1).sent.length, 0, 'no old credential payload may replay on the new socket');
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
        const socket = WebSocketFake.instances.at(-1);
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
            socket.dispatchEvent({type: 'message', data: new class extends Blob {
              text() { return processing.promise; }
            }()});
            socket.dispatchEvent({type: 'close'});
          }
          delivery.resolve({status: 204});
          await committing;
          assert.equal(connection.authenticationPending, false);
          assert.equal(reconnects, 0);
          if (pendingProcessing) processing.resolve('[9]');
          else socket.dispatchEvent({type: 'close'});
          await closing;
          assert.equal(reconnects, 1);
          assert.deepEqual(requests, [{url: 'https://example.invalid/auth/complete', body: '123e4567-e89b-12d3-a456-426614174000'}]);
          const freshSocket = WebSocketFake.instances.at(-1);
          assert.notEqual(freshSocket, socket);
          assert.equal(freshSocket.sent.length, 0);
          freshSocket.dispatchEvent({type: 'close'});
          await closing;
          assert.equal(reconnects, 2);
          assert.deepEqual(requests[1], {url: 'https://example.invalid/auth/recover', body: ''});
        } finally { globalThis.fetch = originalFetch; }
      });
    }

    await t.test('lost completion responses retry only the same UUID handle', async () => {
      const { window } = browser();
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      connection._connectUsingWebSocket();
      WebSocketFake.instances.at(-1).protocol = 'json';
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
        assert.equal(WebSocketFake.instances.at(-1).sent.length, 0);
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
        assert.equal(WebSocketFake.instances.at(-1).sent.length, 0);
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
        const originalSocket = WebSocketFake.instances.at(-1);
        connection._connectUsingWebSocket();
        assert.notEqual(WebSocketFake.instances.at(-1), originalSocket);
        response.resolve({status: 204});
        await assert.rejects(committing, /Authentication connection changed/);
        assert.equal(connection.authenticationPending, false);
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
    });

    await t.test('slow authentication delivery pauses heartbeats without consuming the loss limit', async () => {
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const completionId = '123e4567-e89b-12d3-a456-426614174000';
      const connection = new Connection('synthetic-session', '/', window.location, { auth: true });
      connection._connectUsingWebSocket();
      WebSocketFake.instances.at(-1).protocol = 'json';
      const socket = WebSocketFake.instances.at(-1);
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
        assert.equal(reloads, 0);
        assert.equal(socket.sent.length, 0);

        delivery.resolve({status: 204});
        for (let attempt = 0; attempt < 20 && connection.authenticationPending; attempt++) await Promise.resolve();
        assert.equal(connection.authenticationPending, false);
        connection._connectUsingWebSocket();
        WebSocketFake.instances.at(-1).protocol = 'json';
        intervals[0].callback();
        assert.equal(reloads, 0, 'the first unpaused heartbeat must retain its ordinary allowance');
        assert.equal(WebSocketFake.instances.at(-1).sent.length, 1);
        intervals[0].callback();
        assert.equal(reloads, 1, 'the unchanged limit applies after two ordinary missing heartbeats');
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

    // Long polling that resumes, with the bridge on top of the transport.
    const longPolling = (window, options = {}) => {
      const requests = [];
      globalThis.XMLHttpRequest = class extends EventTargetFake {
        constructor() { super(); this.headers = {}; requests.push(this); }
        open(method, url) { this.method = method; this.url = url; }
        setRequestHeader() {}
        send(body) { this.body = body; }
        abort() { this.aborted = true; }
        getResponseHeader(name) { return this.responseHeaders?.[name.toLowerCase()] ?? null; }
        frame(number, dom, body) {
          this.status = 200; this.responseText = body; this.readyState = 4;
          this.responseHeaders = {'spoonbill-frame': String(number), 'spoonbill-dom': String(dom)};
          this.dispatchEvent({type: 'readystatechange', target: this});
        }
      };
      const timers = [];
      globalThis.setTimeout = (callback, delay) => { const timer = {callback, delay}; timers.push(timer); return timer; };
      globalThis.clearTimeout = timer => { if (timer) timer.cleared = true; };
      const intervals = [];
      globalThis.setInterval = (callback, delay) => { const interval = {callback, delay}; intervals.push(interval); return interval; };
      globalThis.clearInterval = () => {};
      const connection = new Connection('synthetic-session', '/', window.location, {ws: false, lpw: 1000, ...options});
      connection._connectUsingConnectionType(ConnectionType.LONG_POLLING);
      const bridge = new Bridge({heartbeat: {interval: '25', limit: '2'}, ...options}, connection);
      const polls = () => requests.filter(request => request.method === 'GET');
      const posts = () => requests.filter(request => request.method === 'POST');
      const sent = () => posts().map(request => request.body);
      const page = new URL(polls()[0].url).searchParams.get('page');
      return {connection, bridge, polls, posts, sent, page, timers, intervals};
    };
    const timing = ['setTimeout', 'clearTimeout', 'setInterval', 'clearInterval', 'XMLHttpRequest'];
    const keepTiming = () => new Map(timing.map(name => [name, globalThis[name]]));
    const restoreTiming = saved => { for (const [name, value] of saved) globalThis[name] = value; };

    await t.test('a user event of a long-polling page travels in the envelope with the page id and DOM revision', () => {
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window);
      try {
        lp.bridge._onCallback(CallbackType.DOM_EVENT, '0:1_1:click');
        assert.equal(lp.sent().at(-1), JSON.stringify([CallbackType.VIEW_EVENT, `${lp.page}:0:${CallbackType.DOM_EVENT}:0:1_1:click`]));
        // A DOM frame is one more revision of the page; another frame is not.
        lp.polls()[0].frame(1, 0, '[9]');
        lp.polls()[1].frame(2, 1, '[4]');
        lp.bridge._onCallback(CallbackType.DOM_EVENT, '0:1_1:click');
        assert.equal(lp.sent().at(-1), JSON.stringify([CallbackType.VIEW_EVENT, `${lp.page}:1:${CallbackType.DOM_EVENT}:0:1_1:click`]));
        assert.equal(new URL(lp.polls()[2].url).searchParams.get('seen'), '2');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('an event that the page sends while it applies a DOM frame names the revision of that frame', () => {
      // A custom element that a patch inserts can call back from its
      // connectedCallback; the event belongs to the display that the frame makes.
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window);
      try {
        lp.bridge._spoonbill.modifyDom = () => { lp.bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'ready:1'); return true; };
        lp.polls()[0].frame(1, 1, '[4]');
        assert.equal(lp.sent().at(-1), JSON.stringify([CallbackType.VIEW_EVENT, `${lp.page}:1:${CallbackType.CUSTOM_CALLBACK}:ready:1`]));
        lp.bridge._onCallback(CallbackType.CUSTOM_CALLBACK, 'later:1');
        assert.equal(lp.sent().at(-1), JSON.stringify([CallbackType.VIEW_EVENT, `${lp.page}:1:${CallbackType.CUSTOM_CALLBACK}:later:1`]));
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a command that throws on long polling is delivered once: no reload, and the next poll carries the next cursor', () => {
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      try {
        // evalJs whose result cannot be written as JSON: a cyclic object.
        lp.polls()[0].frame(1, 0, JSON.stringify([10, '7', '(function(){var a={};a.a=a;return a})()']));
        assert.equal(reloads, 0);
        assert.equal(lp.polls().length, 2);
        assert.equal(new URL(lp.polls()[1].url).searchParams.get('seen'), '1');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a DOM frame whose node is missing, or that throws, makes a long-polling page reload', () => {
      for (const patch of [
        JSON.stringify([4, 0, 'missing-parent', 'missing-parent_1', 0, 'div']),
        JSON.stringify([4, 3, 'missing-element', 0, 'title', 'x', false])
      ]) {
        const saved = keepTiming();
        const { window } = browser();
        let reloads = 0;
        window.location.reload = () => { reloads++; };
        const lp = longPolling(window);
        try {
          lp.polls()[0].frame(1, 1, patch);
          assert.equal(reloads, 1, patch);
          assert.equal(lp.polls().length, 1, 'no poll for the frame after it');
        } finally { lp.bridge.destroy(); restoreTiming(saved); }
      }
    });

    // The answer to a request: its status, and readystatechange.
    const answer = (request, status) => {
      request.status = status; request.readyState = 4; request.dispatchEvent({type: 'readystatechange', target: request});
    };
    const heartbeat = JSON.stringify([CallbackType.HEARTBEAT]);

    await t.test('a long-polling page whose heartbeats the server takes and does not answer polls again once, then reloads', () => {
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      try {
        lp.intervals[0].callback();
        assert.equal(lp.posts().at(-1).body, heartbeat);
        answer(lp.posts().at(-1), 200);
        // No reply by the next tick: the limit. The poll is given up and sent again, once.
        lp.intervals[0].callback();
        assert.equal(lp.polls()[0].aborted, true);
        assert.equal(lp.polls().length, 2);
        assert.equal(reloads, 0);
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        // Still no reply at the next limit: the input of the view is dead.
        lp.intervals[0].callback();
        assert.equal(reloads, 1);
        assert.equal(lp.polls().length, 2, 'no second re-poll');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('after an outage a heartbeat that the server takes replaces the poll that hangs, and the page does not reload', () => {
      // The second review of PR 42, probe 1A: the reply to a heartbeat is lost,
      // the page polls again, and that poll goes out while the link is down.
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      try {
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        lp.intervals[0].callback();
        assert.equal(lp.polls().length, 2, 'the re-poll at the limit');
        // The link is down: the next heartbeat fails, and the second poll hangs.
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 0);
        // The link is back: the server takes a heartbeat. The poll that hangs is replaced at once.
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        assert.equal(lp.polls()[1].aborted, true);
        assert.equal(lp.polls().length, 3);
        lp.polls()[2].frame(1, 0, '[16]');
        for (let tick = 0; tick < 3; tick++) lp.intervals[0].callback();
        assert.equal(reloads, 0);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('after an interruption only the first message that the server takes replaces the poll, so a quiet poll reaches its 204', () => {
      // The review at 82baf17a, finding 2: without a heartbeat limit the server
      // sends no reply, and every taken heartbeat aborted the poll for good.
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window, {heartbeat: {interval: '25'}});
      const events = [];
      for (const name of ['interrupt', 'resume']) lp.connection.dispatcher.addEventListener(name, () => events.push(name));
      try {
        answer(lp.polls()[0], 503);
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        answer(lp.polls().at(-1), 503);
        assert.deepEqual(events, ['interrupt']);
        // The poll after the back-off goes out, and the link is back.
        lp.timers.filter(timer => timer.delay === 400 && !timer.cleared).at(-1).callback();
        const quiet = lp.polls().length;
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        assert.equal(lp.polls().length, quiet + 1, 'the first taken message replaces the poll');
        for (let tick = 0; tick < 4; tick++) {
          lp.intervals[0].callback();
          answer(lp.posts().at(-1), 200);
        }
        assert.equal(lp.polls().length, quiet + 1, 'the later ones leave the new poll alone');
        assert.notEqual(lp.polls().at(-1).aborted, true);
        answer(lp.polls().at(-1), 204);
        assert.deepEqual(events, ['interrupt', 'resume']);
        assert.equal(lp.connection.linkUp, true);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a heartbeat that fails counts for the notice, but does not let the next one replace a sound poll', () => {
      // The review at fe313e1a, finding 1: heartbeats that alternate between
      // 200 and 503 aborted the replacement poll every other interval, so it
      // never reached its 204 and the notice stayed.
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window, {heartbeat: {interval: '25'}});
      const events = [];
      for (const name of ['interrupt', 'resume']) lp.connection.dispatcher.addEventListener(name, () => events.push(name));
      try {
        answer(lp.polls()[0], 503);
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        assert.equal(lp.polls().length, 2, 'the back-off is replaced by a poll at once');
        for (let tick = 0; tick < 6; tick++) {
          lp.intervals[0].callback();
          answer(lp.posts().at(-1), tick % 2 === 0 ? 503 : 200);
        }
        assert.deepEqual(events, ['interrupt'], 'a failed heartbeat still counts for the notice');
        assert.equal(lp.polls().length, 2, 'the replacement poll is left alone');
        assert.notEqual(lp.polls()[1].aborted, true);
        answer(lp.polls()[1], 204);
        assert.deepEqual(events, ['interrupt', 'resume']);
        assert.equal(lp.connection.linkUp, true);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a failed poll lets the next taken message replace the poll again', () => {
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window, {heartbeat: {interval: '25'}});
      try {
        answer(lp.polls()[0], 503);
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        assert.equal(lp.polls().length, 2, 'the back-off is replaced by a poll at once');
        // That poll fails too: the next taken message replaces its back-off again.
        answer(lp.polls().at(-1), 503);
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        assert.equal(lp.polls().length, 3);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a re-poll while the poll waits out its back-off polls at once', () => {
      // Probe 1B: the bridge spends its one re-poll, so the re-poll must not be lost.
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window);
      try {
        answer(lp.polls()[0], 503);
        const backOff = lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1);
        assert.ok(backOff, 'the poll waits out its back-off');
        lp.connection.repoll();
        assert.equal(lp.polls().length, 2, 'the poll goes at once');
        assert.equal(backOff.cleared, true, 'and not a second time when the back-off ends');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('on a hanging link a heartbeat that is written off is given up, so requests do not pile up', () => {
      // Probe 2: a browser opens about six requests to one origin at a time.
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window);
      try {
        for (let tick = 0; tick < 6; tick++) lp.intervals[0].callback();
        const heartbeats = lp.posts().filter(request => request.body === heartbeat);
        assert.equal(heartbeats.length, 6);
        assert.deepEqual(heartbeats.map(request => request.aborted === true), [true, true, true, true, true, false]);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a page paused with a heartbeat outstanding and its re-poll spent does not reload, and resumes with a fresh allowance', () => {
      // The review of PR 39 at f55be0f2, finding 1.
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      try {
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        lp.intervals[0].callback();
        assert.equal(lp.polls().length, 2, 'the re-poll at the limit');
        lp.intervals[0].callback();
        const outstanding = lp.posts().at(-1);
        lp.connection.disconnect(false);
        assert.equal(outstanding.aborted, true, 'the heartbeat in flight is given up');
        const posts = lp.posts().length;
        for (let tick = 0; tick < 4; tick++) lp.intervals[0].callback();
        assert.deepEqual([reloads, lp.posts().length], [0, posts], 'paused: no heartbeat, no reload');
        lp.connection.connect();
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        lp.intervals[0].callback();
        answer(lp.posts().at(-1), 200);
        lp.intervals[0].callback();
        assert.equal(reloads, 0, 'a fresh allowance: the limit gives a re-poll first');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('Back that an application handles first, with a pause, makes connect() reload: the server missed the navigation', () => {
      // The review of PR 39 at 17fd8ee0, finding 2: the application's popstate
      // listener paused the page when the URL already showed the destination,
      // so the URL of the pause fit, and the page resumed the view of the old route.
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      try {
        window.location.pathname = '/orders/0';
        lp.connection.disconnect(false);
        // Spoonbill's own listener comes second.
        lp.bridge._onCallback(CallbackType.HISTORY, '/orders/0');
        assert.deepEqual(lp.sent(), [], 'nothing is sent while paused');
        const polls = lp.polls().length;
        lp.connection.connect();
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        assert.equal(reloads, 1);
        assert.equal(lp.polls().length, polls, 'no poll resumes the old route');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a reply that the page makes while paused goes to the server once when it resumes; a user event does not', () => {
      // The review of PR 39 at 17fd8ee0, finding 3: the code of an evalJs
      // paused the page, its result was refused, and the server waited for
      // its RPC timeout.
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      globalThis.__pause = () => lp.connection.disconnect(false);
      try {
        // The command of access.evalJs("__pause(), 42").
        lp.polls()[0].frame(1, 0, JSON.stringify([10, '7', '__pause(), 42']));
        lp.bridge._onCallback(CallbackType.DOM_EVENT, '0:1_1:click');
        assert.deepEqual(lp.sent(), [], 'nothing is sent while paused');
        assert.equal(lp.polls().length, 1, 'and nothing polled');
        lp.connection.connect();
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        const reply = JSON.stringify([CallbackType.EVALJS_RESPONSE, '7:0:42']);
        assert.deepEqual(lp.sent(), [reply], 'the reply, once, and not the click');
        assert.equal(new URL(lp.posts()[0].url).searchParams.get('seen'), '1');
        assert.equal(new URL(lp.polls().at(-1).url).searchParams.get('seen'), '1');
        assert.equal(reloads, 0);
        // A second pause and resume does not send it again.
        lp.connection.disconnect(true);
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        assert.deepEqual(lp.sent(), [reply]);
      } finally { delete globalThis.__pause; lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a page that reloads after a pause sends none of the replies that it kept', () => {
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      try {
        lp.connection.disconnect(false);
        lp.bridge._onCallback(CallbackType.EVALJS_RESPONSE, '7:0:42');
        window.location.pathname = '/orders/0';
        lp.connection.connect();
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        assert.deepEqual([reloads, lp.sent()], [1, []]);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a long-polling heartbeat that no transport takes is not counted, and is no failed poll', () => {
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      const events = [];
      for (const name of ['interrupt', 'resume']) lp.connection.dispatcher.addEventListener(name, () => events.push(name));
      try {
        // The transport refuses every message: no request goes out.
        const send = lp.connection.send;
        lp.connection.send = () => false;
        for (let tick = 0; tick < 4; tick++) lp.intervals[0].callback();
        assert.equal(lp.polls().length, 1, 'no re-poll for heartbeats that never went out');
        assert.notEqual(lp.polls()[0].aborted, true);
        assert.deepEqual([events, reloads], [[], 0]);
        // The transport takes messages again: the heartbeat goes out with its full allowance.
        lp.connection.send = send;
        lp.intervals[0].callback();
        assert.equal(lp.posts().at(-1).body, heartbeat);
        answer(lp.posts().at(-1), 200);
        lp.intervals[0].callback();
        assert.equal(lp.polls().length, 2, 'then the limit counts as usual');
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('a long-polling page whose link hangs shows the notice, does not reload, and goes on when the link is back', () => {
      const saved = keepTiming();
      const { window } = browser();
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      const lp = longPolling(window);
      const events = [];
      for (const name of ['interrupt', 'resume']) lp.connection.dispatcher.addEventListener(name, () => events.push(name));
      try {
        // No heartbeat request is answered: each one is written off at the next tick, as a failed poll.
        lp.intervals[0].callback();
        lp.intervals[0].callback();
        assert.deepEqual(events, []);
        lp.intervals[0].callback();
        assert.deepEqual(events, ['interrupt'], 'the notice at the second failure');
        for (let tick = 0; tick < 6; tick++) lp.intervals[0].callback();
        assert.equal(reloads, 0);
        assert.equal(lp.polls().length, 1, 'no re-poll: no heartbeat was taken');
        // The link is back: a heartbeat is taken. The poll from before is
        // replaced at once, since it may hang on the broken link, and the
        // reply answers the new one.
        answer(lp.posts().at(-1), 200);
        assert.deepEqual([lp.polls()[0].aborted, lp.polls().length], [true, 2]);
        lp.polls()[1].frame(1, 0, '[16]');
        assert.deepEqual(events, ['interrupt', 'resume']);
        assert.equal(new URL(lp.polls().at(-1).url).searchParams.get('seen'), '1');
        assert.equal(reloads, 0);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('the heartbeat goes on while long polling is interrupted, and its reply hides the notice', () => {
      const saved = keepTiming();
      const { window } = browser();
      const lp = longPolling(window);
      const events = [];
      for (const name of ['interrupt', 'resume']) lp.connection.dispatcher.addEventListener(name, () => events.push(name));
      try {
        answer(lp.polls()[0], 500);
        lp.timers.filter(timer => timer.delay === 200 && !timer.cleared).at(-1).callback();
        answer(lp.polls().at(-1), 0);
        assert.deepEqual(events, ['interrupt']);
        lp.timers.filter(timer => timer.delay === 400 && !timer.cleared).at(-1).callback();
        // The third poll reaches the server and waits there. A heartbeat still goes out.
        const before = lp.sent().filter(body => body === heartbeat).length;
        lp.intervals[0].callback();
        assert.equal(lp.sent().filter(body => body === heartbeat).length, before + 1);
        answer(lp.posts().at(-1), 200);
        lp.polls().at(-1).frame(1, 0, '[16]');
        assert.deepEqual(events, ['interrupt', 'resume']);
      } finally { lp.bridge.destroy(); restoreTiming(saved); }
    });

    await t.test('an unguarded WebSocket page whose reload waits sends no heartbeat, and its limit does not reload it', () => {
      const saved = keepTiming();
      const { window } = browser();
      const session = new Map();
      window.sessionStorage = {getItem: key => session.get(key) ?? null, setItem: (key, value) => session.set(key, String(value)), removeItem: key => session.delete(key)};
      let reloads = 0;
      window.location.reload = () => { reloads++; };
      globalThis.setTimeout = (callback, delay) => ({callback, delay});
      globalThis.clearTimeout = () => {};
      const intervals = [];
      globalThis.setInterval = callback => { intervals.push(callback); return callback; };
      globalThis.clearInterval = () => {};
      try {
        for (let attempt = 0; attempt < 2; attempt++) {
          const connection = new Connection('synthetic-session', '/', window.location, {});
          connection._connectUsingWebSocket();
          const socket = WebSocketFake.instances.at(-1);
          socket.protocol = 'json';
          const bridge = new Bridge({heartbeat: {interval: '25', limit: '2'}}, connection);
          connection.dispatcher.dispatchEvent({type: 'message', data: '[1]'});
          if (attempt === 1) {
            // The second reload in the window waits. The heartbeat stops, and its limit reloads nothing.
            const sent = socket.sent.length;
            for (let tick = 0; tick < 4; tick++) intervals.at(-1)();
            assert.equal(reloads, 1);
            assert.equal(socket.sent.length, sent);
          }
          bridge.destroy();
        }
      } finally { restoreTiming(saved); }
    });

    await t.test('the reload frame of an unguarded WebSocket page passes the reload guard; a guarded page reloads as before', () => {
      for (const guarded of [false, true]) {
        const saved = keepTiming();
        const { window } = browser();
        const session = new Map();
        window.sessionStorage = {getItem: key => session.get(key) ?? null, setItem: (key, value) => session.set(key, String(value)), removeItem: key => session.delete(key)};
        let reloads = 0;
        window.location.reload = () => { reloads++; };
        const timers = [];
        globalThis.setTimeout = (callback, delay) => { const timer = {callback, delay}; timers.push(timer); return timer; };
        globalThis.clearTimeout = () => {};
        try {
          for (let attempt = 0; attempt < 2; attempt++) {
            const connection = new Connection('synthetic-session', '/', window.location, guarded ? {auth: true} : {});
            connection._connectUsingWebSocket();
            WebSocketFake.instances.at(-1).protocol = 'json';
            const bridge = new Bridge(guarded ? {auth: true, heartbeat: {interval: '0'}} : {heartbeat: {interval: '0'}}, connection);
            connection.dispatcher.dispatchEvent({type: 'message', data: '[1]'});
            bridge.destroy();
          }
          assert.equal(reloads, guarded ? 2 : 1, `guarded: ${guarded}`);
          if (!guarded) assert.ok(timers.some(timer => timer.delay > 25000 && timer.delay <= 30000));
        } finally { restoreTiming(saved); }
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
