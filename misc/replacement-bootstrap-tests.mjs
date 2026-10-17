import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {runInNewContext} from 'node:vm';
import test from 'node:test';

const service = readFileSync(new URL('../modules/spoonbill/src/main/scala/korolev/server/internal/services/AuthenticationCompletionService.scala', import.meta.url), 'utf8');
const source = service.match(/val replacementBootstrapScript: String = """([\s\S]*?)"""/)[1];

async function bootstrap({recoverStatus = 204, loseFirstActivation = false, activationAvailable = true,
  closeBeforeDecode = false} = {}) {
  const requests = [];
  const sockets = [];
  let reloads = 0;
  let finish;
  const finished = new Promise(resolve => { finish = resolve; });
  const status = {set textContent(value) { finish(value); }};
  class Socket {
    constructor(url, protocols) {
      assert.equal(JSON.stringify(protocols), '["json"]');
      sockets.push(url);
      queueMicrotask(() => {
        if (!activationAvailable || (loseFirstActivation && sockets.length === 1)) this.onclose();
        else if (closeBeforeDecode) {
          this.onmessage({data: {text: async () => { await Promise.resolve(); return 'activated'; }}});
          this.onclose();
        } else this.onmessage({data: 'activated'});
      });
    }
    close() {}
  }
  runInNewContext(source, {
    URL, AbortController, setTimeout, clearTimeout, WebSocket: Socket,
    document: {
      currentScript: {src: 'https://app.example/mount/auth/replacement-bootstrap.js'},
      getElementById: id => { assert.equal(id, 'spoonbill-recovery-status'); return status; }
    },
    window: {location: {origin: 'https://app.example', reload() { reloads++; finish(null); }}},
    fetch: async (url, options) => {
      requests.push({url: String(url), options});
      return {status: recoverStatus};
    }
  });
  const error = await finished;
  return {requests, sockets, reloads, error};
}

test('bootstrap activates an already accepted successor cookie without requiring another Set-Cookie', async () => {
  const result = await bootstrap();
  assert.equal(result.reloads, 1);
  assert.equal(result.requests.length, 1);
  assert.equal(result.requests[0].url, 'https://app.example/mount/auth/recover');
  assert.equal(result.requests[0].options.body, '');
  assert.equal(result.requests[0].options.method, 'POST');
  assert.equal(result.requests[0].options.credentials, 'same-origin');
  assert.equal(result.requests[0].options.redirect, 'error');
  assert.deepEqual(result.sockets, ['wss://app.example/mount/auth/activate']);
});

test('lost activation acknowledgment retries a fresh handshake without another delivery request', async () => {
  const result = await bootstrap({loseFirstActivation: true});
  assert.equal(result.requests.length, 1);
  assert.equal(result.sockets.length, 2);
  assert.equal(result.reloads, 1);
});

test('unavailable recovery stops after bounded attempts without a reload loop', async () => {
  const result = await bootstrap({recoverStatus: 403, activationAvailable: false});
  assert.equal(result.requests.length, 3);
  assert.equal(result.sockets.length, 3);
  assert.equal(result.reloads, 0);
  assert.match(result.error, /Reload to retry/);
});

test('another tab activating between the GET probe and POST still permits the current successor handshake', async () => {
  const result = await bootstrap({recoverStatus: 403});
  assert.equal(result.requests.length, 3);
  assert.equal(result.sockets.length, 1);
  assert.equal(result.reloads, 1);
});

test('terminal activation close waits for its already received binary acknowledgement to decode', async () => {
  const result = await bootstrap({closeBeforeDecode: true});
  assert.equal(result.requests.length, 1);
  assert.equal(result.sockets.length, 1);
  assert.equal(result.reloads, 1);
});
