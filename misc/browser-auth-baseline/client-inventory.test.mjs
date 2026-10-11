import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {bundleInventory, clientInventory} from './client-inventory.mjs';

test('delivered generated output counts even if handwritten source is unchanged', () => {
  const before = bundleInventory(Buffer.from('alert(1);'));
  const after = bundleInventory(Buffer.from('alert(1);\nalert(2);'));
  assert(after.emittedBytes > before.emittedBytes);
  assert.notEqual(after.sha256, before.sha256);
  assert.equal(before.gzipBytes, bundleInventory(Buffer.from('alert(1);')).gzipBytes);
});

test('application scripts and embedded Scala script candidates remain visible', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'spoonbill-client-inventory-'));
  try {
    const source = path.join(root, 'client'), app = path.join(root, 'application');
    mkdirSync(source); mkdirSync(app);
    writeFileSync(path.join(source, 'client.js'), 'export const VERSION = 1;\n');
    writeFileSync(path.join(app, 'auth.js'), 'window.fetch("/auth");\n');
    writeFileSync(path.join(app, 'Host.scala'), 'access.evalJs("example")\n');
    const bundle = path.join(root, 'client.min.js');
    writeFileSync(bundle, 'const VERSION=1;');
    const result = clientInventory(source, bundle, {host: app});
    assert.equal(result.sources.totals.files, 1);
    assert.equal(result.applicationScripts.length, 1);
    assert.deepEqual(result.embeddedScriptCandidates, [{category: 'host', file: 'Host.scala', line: 1}]);
    assert.equal(result.emittedClient.emittedBytes, 16);
  } finally { rmSync(root, {recursive: true, force: true}); }
});
