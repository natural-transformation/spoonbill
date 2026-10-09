// Native custom-element and mounted-URL regressions against actual ESM modules.
// From the Spoonbill root:
//   nix develop .#browser --command bash scripts/test-native-browsers.sh
// No application server, database, network listener, or downloaded dependency.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');

assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use the Nix environment providing PLAYWRIGHT_DRIVER_PATH');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const sourceRoot = path.resolve(__dirname, '../modules/spoonbill/src/main/es6');
const origin = 'http://127.0.0.1:18987';

(async () => {
  const modules = new Map(await Promise.all(['bridge.js', 'spoonbill.js', 'connection.js', 'utils.js', 'sensitive.js'].map(async name =>
    ['/' + name, await fs.readFile(path.join(sourceRoot, name), 'utf8')])));
  const results = [];
  for (const [engine, type] of Object.entries({chromium, webkit})) {
    const env = {...process.env};
    if (engine === 'webkit' && process.platform === 'linux') {
      const vendor = env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
      assert.ok(vendor, 'Nix WebKit EGL vendor required');
      env.__EGL_VENDOR_LIBRARY_FILENAMES = vendor;
      env.LIBGL_ALWAYS_SOFTWARE = 'true';
    }
    const browser = await type.launch({headless: true, env});
    try {
      const page = await browser.newPage();
      const pageErrors = [];
      page.on('pageerror', error => pageErrors.push(error.message));
      await page.route('**/*', route => {
        const url = new URL(route.request().url());
        if (url.origin !== origin) return route.abort();
        const source = modules.get(url.pathname);
        if (source) return route.fulfill({status: 200, contentType: 'text/javascript', body: source});
        return route.fulfill({status: url.pathname === '/' ? 200 : 404, contentType: 'text/html',
          body: '<!doctype html><html><head></head><body></body></html>'});
      });
      await page.goto(origin + '/');
      const result = await page.evaluate(async () => {
        const {Bridge} = await import('/bridge.js');
        const {CallbackType} = await import('/spoonbill.js');
        const connectionId = '11111111-1111-4111-8111-111111111111';
        const replacementId = '22222222-2222-4222-8222-222222222222';
        const sent = [], disconnects = [], lifecycle = [], errors = [];
        const onError = event => { errors.push(event.message); event.preventDefault(); };
        window.addEventListener('error', onError);
        const connection = {dispatcher: document.createDocumentFragment(),
          send: raw => sent.push(JSON.parse(raw)), applicationReady() {},
          disconnect: reconnect => disconnects.push(reconnect)};
        let bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
        // Use the supported adapter installed by launcher.js on socket open.
        window.Spoonbill = {invokeCallback: (name, arg) => bridge._spoonbill.invokeCustomCallback(name, arg)};
        customElements.define('durable-counter', class extends HTMLElement {
          static get observedAttributes() { return ['count']; }
          connectedCallback() {
            lifecycle.push('connected');
            window.Spoonbill.invokeCallback('counter', 'during-baseline');
          }
          attributeChangedCallback() {
            lifecycle.push('attribute');
            window.Spoonbill.invokeCallback('counter', 'during-attribute');
            window.dispatchEvent(new Event('resize'));
            window.dispatchEvent(new PopStateEvent('popstate', {state: '/during-attribute'}));
            // Transport responses still progress during the same native reaction.
            bridge._spoonbill.extractProperty('native-rpc', this.vId, 'tagName');
            bridge._onCallback(CallbackType.HEARTBEAT);
          }
          disconnectedCallback() {
            lifecycle.push('disconnected');
            window.Spoonbill.invokeCallback('counter', 'during-removal');
          }
        });
        const deliver = frame => {
          const event = new Event('message');
          event.data = JSON.stringify(frame);
          connection.dispatcher.dispatchEvent(event);
        };
        try {
          deliver([19, '1', connectionId, '0', [4,
            0, '1', '1_1', 0, 'body',
            0, '1_1', '1_1_1', 0, 'durable-counter']]);
          deliver([21, true]);
          deliver([20, connectionId, '0', '1', [4,
            3, '1_1_1', 0, 'count', '1', false]]);
          await Promise.resolve();
          const afterAttribute = sent.slice();
          const displayedCount = document.querySelector('durable-counter').getAttribute('count');
          window.Spoonbill.invokeCallback('counter', 'normal-after-attribute');
          deliver([20, connectionId, '1', '2', [4, 2, '1_1', '1_1_1']]);
          await Promise.resolve();
          const afterRemoval = sent.slice();
          window.Spoonbill.invokeCallback('counter', 'normal-after-removal');
          window.dispatchEvent(new PopStateEvent('popstate', {state: '/second-suppressed'}));
          deliver([21, true]);
          window.Spoonbill.invokeCallback('counter', 'old-controller-ready');
          const oldController = {
            finalRevision: bridge._viewRevision, applyingView: bridge._applyingView,
            recovering: bridge._viewRecovering, terminal: bridge._sensitive.terminal,
            remainingCounters: document.querySelectorAll('durable-counter').length,
            sent: sent.slice()};
          // Mirror launcher's close/open lifecycle: only a fresh authorized
          // baseline and readiness may admit new actions after the rebind.
          bridge.destroy();
          bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
          window.Spoonbill.invokeCallback('counter', 'before-baseline');
          deliver([19, '2', replacementId, '0', [4, 0, '1', '1_1', 0, 'body']]);
          window.Spoonbill.invokeCallback('counter', 'before-ready');
          const beforeReady = sent.slice();
          deliver([21, true]);
          await Promise.resolve();
          const afterReady = sent.slice();
          window.Spoonbill.invokeCallback('counter', 'after-rebind');
          return {sent, afterAttribute, afterRemoval, lifecycle, disconnects, errors, displayedCount,
            oldController, beforeReady, afterReady,
            replacementRevision: bridge._viewRevision, applyingView: bridge._applyingView};
        } finally { bridge.destroy(); window.removeEventListener('error', onError); }
      });
      const mountCases = await page.evaluate(async () => {
        const {Connection} = await import('/connection.js');
        const originalSocket = window.WebSocket;
        const captures = [];
        class SocketFake extends EventTarget {
          static OPEN = 1;
          constructor(uri, protocols) {
            super();
            // Native URL parsing captures browser WebSocket URL normalization;
            // this fixture deliberately does not establish a network connection.
            this.url = new URL(uri).href;
            this.protocols = protocols;
            captures.push(this.url);
          }
          close() {}
          send() {}
        }
        window.WebSocket = SocketFake;
        const cases = [];
        const attempt = (mount, applicationPath, guarded, allowed, expectedLocation) => {
          history.replaceState(null, '', applicationPath);
          const start = captures.length;
          let errors = 0;
          const connection = new Connection('view', mount, window.location, {auth: guarded});
          connection.dispatcher.addEventListener('error', () => errors++);
          connection._connectUsingWebSocket();
          cases.push({mount, pathname: location.pathname, guarded, allowed, errors,
            socketUrls: captures.slice(start), expectedLocation,
            expectedTransportPath: new URL(mount + 'bridge/web-socket/view', location.origin).pathname});
        };
        try {
          for (const mount of ['/', '/app/', '/my app/', '/café/', '/my%20app/', '/caf%C3%A9/']) {
            for (const guarded of [false, true]) {
              attempt(mount, mount + 'invoices/42?tab=details', guarded, true,
                guarded ? '/invoices/42?tab=details' : null);
            }
            const mountRoot = mount.slice(0, -1);
            attempt(mount, (mountRoot || '/') + '?tab=details', true, true, '/?tab=details');
            if (mountRoot) attempt(mount, mountRoot + '-other/invoices/42', true, false, null);
          }
          attempt('/café/', '/caf%c3%a9/invoices/42?tab=details', true, true, '/invoices/42?tab=details');
          attempt('/caf%c3%a9/', '/caf%C3%A9/invoices/42?tab=details', true, true, '/invoices/42?tab=details');
          attempt('/app/', '/app%2fother/invoices/42', true, false, null);
          return cases;
        } finally {
          window.WebSocket = originalSocket;
          history.replaceState(null, '', '/');
        }
      });
      results.push({engine, pageErrors, mountCases, ...result});
      console.log(JSON.stringify({engine, pageErrors, mountCases, ...result}));
    } finally { await browser.close(); }
  }
  for (const result of results) {
    const replacementId = '22222222-2222-4222-8222-222222222222';
    const controlFrames = [[2, 'native-rpc:0:DURABLE-COUNTER'], [6]];
    assert.deepEqual(result.errors, [], `${result.engine}: native lifecycle error`);
    assert.deepEqual(result.pageErrors, [], `${result.engine}: page error`);
    assert.deepEqual(result.disconnects, [true], `${result.engine}: suppressed history must request exactly one rebind`);
    assert.deepEqual(result.lifecycle, ['connected', 'attribute', 'disconnected']);
    assert.equal(result.displayedCount, '1');
    assert.equal(result.oldController.remainingCounters, 0);
    assert.equal(result.oldController.finalRevision, '2');
    assert.equal(result.oldController.applyingView, false);
    assert.equal(result.oldController.recovering, true);
    assert.equal(result.oldController.terminal, true);
    assert.equal(result.replacementRevision, '0');
    assert.equal(result.applyingView, false);
    assert.deepEqual(result.afterAttribute, controlFrames, `${result.engine}: partial-view action was emitted or replayed`);
    assert.deepEqual(result.afterRemoval, controlFrames, `${result.engine}: removed-node action was retagged or replayed`);
    assert.deepEqual(result.oldController.sent, controlFrames, `${result.engine}: old-controller readiness reopened a fenced action`);
    assert.deepEqual(result.beforeReady, controlFrames, `${result.engine}: replacement admitted an unready callback`);
    assert.deepEqual(result.afterReady, controlFrames, `${result.engine}: replacement replayed a dropped callback`);
    assert.deepEqual(result.sent, [...controlFrames, [7, `${replacementId}:0:1:counter:after-rebind`]]);
    assert.equal(result.mountCases.length, 26);
    for (const mountCase of result.mountCases) {
      const label = `${result.engine}: ${mountCase.mount} at ${mountCase.pathname}, guarded=${mountCase.guarded}`;
      assert.equal(mountCase.errors, mountCase.allowed ? 0 : 1, label);
      assert.equal(mountCase.socketUrls.length, mountCase.allowed ? 1 : 0, label);
      if (mountCase.allowed) {
        const socketUrl = new URL(mountCase.socketUrls[0]);
        assert.equal(socketUrl.pathname, mountCase.expectedTransportPath, label);
        assert.equal(socketUrl.searchParams.get('__spoonbill_location'), mountCase.expectedLocation, label);
      }
    }
  }
  console.log('PASS: native durable-view callbacks and mounted URL normalization in Chromium and WebKit');
})().catch(error => { console.error(error); process.exitCode = 1; });
