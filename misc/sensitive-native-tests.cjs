// From Spoonbill: nix develop .#browser --command bash scripts/test-native-browsers.sh
// Uses real Chromium/WebKit DOM, shadow roots and canvas; no application server.
const assert = require('node:assert/strict');
const fs = require('node:fs/promises');
const path = require('node:path');
assert.ok(process.env.PLAYWRIGHT_DRIVER_PATH, 'Use the Nix Playwright environment');
const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
const sourceRoot = path.resolve(__dirname, '../modules/spoonbill/src/main/es6');
const origin = 'http://127.0.0.1:18989';

(async () => {
  const modules = new Map(await Promise.all(['bridge.js', 'spoonbill.js', 'connection.js', 'utils.js', 'sensitive.js']
    .map(async name => ['/' + name, await fs.readFile(path.join(sourceRoot, name), 'utf8')])));
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
      const errors = [];
      page.on('pageerror', error => errors.push(error.message));
      await page.route('**/*', route => {
        const url = new URL(route.request().url());
        if (url.origin !== origin) return route.abort();
        const source = modules.get(url.pathname);
        return route.fulfill({status: source || url.pathname === '/' ? 200 : 404,
          contentType: source ? 'text/javascript' : 'text/html', body: source ||
            '<!doctype html><html><head></head><body><p id="ordinary">Ordinary</p>' +
            ['codes', 'setup', 'expiry', 'lost'].map(name =>
              `<sb-secret data-sb-region="${name}"></sb-secret>`).join('') +
            '</body></html>'});
      });
      await page.goto(origin);
      const result = await page.evaluate(async () => {
        const check = (condition, message) => { if (!condition) throw new Error(message); };
        // Test-only trusted instrumentation observes closed roots to assert
        // clearing. Production exposes neither the roots nor their plaintext.
        const roots = new WeakMap();
        const attach = Element.prototype.attachShadow;
        Element.prototype.attachShadow = function(options) {
          const root = attach.call(this, options); roots.set(this, root); return root;
        };
        const removalChecks = [];
        customElements.define('sb-secret', class extends HTMLElement {
          disconnectedCallback() {
            const root = roots.get(this);
            if (root) removalChecks.push(root.childNodes.length === 0);
          }
        });
        const {Bridge} = await import('/bridge.js');
        const firstConnection = '11111111-1111-4111-8111-111111111111';
        const secondConnection = '22222222-2222-4222-8222-222222222222';
        const presentation = n => `33333333-3333-4333-8333-${String(n).padStart(12, '0')}`;
        const sent = [];
        let lostAcks = 0;
        let expired;
        const expiryNotice = new Promise(resolve => { expired = resolve; });
        const connection = {dispatcher: document.createDocumentFragment(), applicationReady() {},
          disconnect: () => { throw new Error('Unexpected reconnect'); }, send: raw => {
            const frame = JSON.parse(raw);
            if (frame[0] === 8 && frame[1].includes(':lost:')) { lostAcks++; return; }
            sent.push(frame);
            if (frame[0] === 9 && frame[1].endsWith(':expiry')) expired();
          }};
        let bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
        const deliver = frame => {
          // Normal fixtures carry the actual absolute protocol deadline too;
          // explicit eighth fields below test stale transport delivery.
          if (frame[0] === 22 && frame.length === 7) frame.push(Date.now() + frame[5]);
          const event = new Event('message'); event.data = JSON.stringify(frame);
          connection.dispatcher.dispatchEvent(event);
        };
        const host = name => document.querySelector(`[data-sb-region="${name}"]`);
        const secret = 'synthetic-recovery-code-not-in-ordinary-DOM';
        const uri = 'otpauth://totp/Example:synthetic?secret=JBSWY3DPEHPK3PXP&issuer=Example';
        try {
          deliver([21, false]);
          deliver([22, firstConnection, presentation(90), 'codes', 'mfa.recovery', 30000,
            [0, [secret]], Date.now() - 1]);
          check(!roots.has(host('codes')), 'A delayed expired frame created visible content');
          check(sent.at(-1)[1].endsWith(':failed'), 'Expired delivery was acknowledged as processed');
          deliver([22, firstConnection, presentation(1), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          const codeHost = host('codes');
          const codeRoot = roots.get(codeHost);
          check(codeRoot.textContent === secret, 'Text presentation missing');
          check(codeHost.shadowRoot === null, 'Sensitive root must be closed');
          check(!document.documentElement.outerHTML.includes(secret), 'Secret leaked into ordinary DOM serialization');
          deliver([3, 'ordinary-rpc', codeHost.vId, 'outerHTML']);
          check(sent.filter(frame => frame[0] === 2).every(frame => !frame[1].includes(secret)), 'Secret leaked through property RPC');
          bridge._spoonbill.listenEvent('click', false);
          codeRoot.querySelector('li').dispatchEvent(new MouseEvent('click', {bubbles: true, composed: true}));
          check(Object.keys(bridge._spoonbill.eventData).length === 0, 'Sensitive event retained in extraction cache');
          check(!sent.some(frame => frame[0] === 0 || frame[0] === 7), 'Sensitive event dispatched an ordinary action');
          deliver([4, 3, document.getElementById('ordinary').vId, 0, 'class', 'changed', false]);
          check(codeRoot.textContent === secret, 'Unrelated render cleared the region');

          const rows = Array.from({length: 21}, () => '1' + '0'.repeat(20));
          deliver([22, firstConnection, presentation(2), 'setup', 'mfa.enrollment', 30000, [1, uri, rows]]);
          const setupRoot = roots.get(host('setup'));
          const canvas = setupRoot.querySelector('canvas');
          check(setupRoot.querySelector('code').textContent === uri, 'TOTP URI missing');
          check(canvas.width === 116 && canvas.height === 116, 'QR size or quiet zone incorrect');
          const pixels = canvas.getContext('2d');
          check(pixels.getImageData(0, 0, 1, 1).data[0] === 255 && pixels.getImageData(16, 16, 1, 1).data[0] === 0,
            'QR modules were not rendered locally');
          check(!document.documentElement.outerHTML.includes('JBSWY'), 'TOTP seed leaked into ordinary DOM');
          check(setupRoot.querySelectorAll('img,script,a').length === 0, 'Sensitive presentation created executable/remote content');

          history.replaceState(null, '', '/caf%C3%A9?tab=details');
          deliver([6, '/café?tab=details']);
          check(codeRoot.textContent === secret && setupRoot.childNodes.length !== 0,
            'Equivalent router URL cleared sensitive output during an unrelated render');

          window.dispatchEvent(new PopStateEvent('popstate', {state: '/next'}));
          check(codeRoot.childNodes.length === 0 && setupRoot.childNodes.length === 0, 'History navigation did not clear');
          check(canvas.width === 0 && canvas.height === 0, 'Cleared QR retained canvas backing pixels');
          check(sent.filter(frame => frame[0] === 9).length === 2, 'Local navigation must retire each region once');

          // A frame authorized before local history may still be in transport.
          // It must never paint while this browser awaits the matching barrier.
          deliver([22, firstConnection, presentation(91), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          check(codeRoot.childNodes.length === 0, 'Late pre-navigation frame painted');
          deliver([4]);
          deliver([24, 99]);
          deliver([22, firstConnection, presentation(92), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          check(codeRoot.childNodes.length === 0, 'Generic patch or unmatched barrier reopened disclosure');
          window.dispatchEvent(new PopStateEvent('popstate', {state: '/newer'}));
          deliver([24, 1]);
          deliver([22, firstConnection, presentation(93), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          check(codeRoot.childNodes.length === 0, 'Earlier navigation barrier reopened the newer navigation');
          deliver([24, 2]);
          deliver([22, firstConnection, presentation(94), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          check(codeRoot.textContent === secret, 'Matching barrier did not allow fresh disclosure');
          deliver([23, firstConnection, presentation(94), 'codes']);
          deliver([22, firstConnection, presentation(91), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          check(codeRoot.childNodes.length === 0, 'Blocked frame replayed after barrier');

          deliver([22, firstConnection, presentation(3), 'expiry', 'mfa.recovery', 1000, [0, [secret]]]);
          let timeout;
          try {
            await Promise.race([expiryNotice, new Promise((_, reject) => {
              timeout = setTimeout(() => reject(new Error('Region expiry did not run')), 5000);
            })]);
          } finally { clearTimeout(timeout); }
          check(roots.get(host('expiry')).childNodes.length === 0, 'Deadline did not clear plaintext');

          deliver([22, firstConnection, presentation(4), 'lost', 'mfa.recovery', 30000, [0, [secret]]]);
          const lostHost = host('lost');
          const lostRoot = roots.get(lostHost);
          check(lostRoot.textContent === secret && lostAcks === 1, 'Ack-loss fixture did not display and lose exactly one ack');
          const notices = sent.filter(frame => frame[0] === 9).length;
          bridge.destroy();
          check(lostRoot.childNodes.length === 0, 'Disconnect retained sensitive content');
          check(sent.filter(frame => frame[0] === 9).length === notices, 'Disconnect echoed clear notices');
          bridge = new Bridge({auth: true, heartbeat: {interval: '0'}}, connection);
          deliver([21, false]);
          check(lostRoot.childNodes.length === 0 && lostAcks === 1, 'Reconnect replayed secret/ack');
          deliver([22, secondConnection, presentation(5), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          check(codeRoot.textContent === secret, 'Fresh disclosure could not reuse an empty closed marker');
          deliver([23, firstConnection, presentation(5), 'codes']);
          check(codeRoot.textContent === secret, 'Old connection cleared a new owner');
          deliver([23, secondConnection, presentation(5), 'codes']);
          check(codeRoot.childNodes.length === 0, 'Explicit clear failed');
          check(sent.filter(frame => frame[0] === 9).length === notices, 'Explicit server clear echoed a notice');

          deliver([22, secondConnection, presentation(6), 'codes', 'mfa.recovery', 30000, [0, [secret]]]);
          deliver([4, 2, codeHost.parentNode.vId, codeHost.vId]);
          check(removalChecks.length === 1 && removalChecks[0], 'Marker detached before plaintext was cleared');
          check(sent.filter(frame => frame[0] === 9).length === notices + 1, 'Marker removal did not retire the presentation');
          deliver([22, secondConnection, presentation(7), 'setup', 'mfa.enrollment', 30000, [1, uri, null]]);
          deliver([6, '/different-page']);
          check(setupRoot.childNodes.length === 0, 'Actual server navigation retained sensitive output');
          deliver([22, secondConnection, presentation(8), 'setup', 'mfa.enrollment', 30000, [1, uri, null]]);
          deliver([19, '1', secondConnection, '0', [4]]);
          check(setupRoot.childNodes.length === 0, 'DOM reset retained sensitive region');
          return {closedRoots: true, ordinaryDomAndRpcClean: true, eventCacheClean: true,
            unrelatedRenderPreserved: true, qrCanvasCleared: true, expiryCleared: true,
            lostAcks, reconnectReplay: false, removedBeforeDetach: true, resetCleared: true, navigationFenced: true};
        } finally { bridge.destroy(); Element.prototype.attachShadow = attach; }
      });
      assert.deepEqual(errors, [], `${engine}: browser errors`);
      assert.equal(result.lostAcks, 1);
      console.log(JSON.stringify({engine, ...result}));
    } finally { await browser.close(); }
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
