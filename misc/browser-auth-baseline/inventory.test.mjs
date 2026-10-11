import test from 'node:test';
import assert from 'node:assert/strict';
import {mkdtempSync, mkdirSync, writeFileSync, rmSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {inventory} from './inventory.mjs';

test('the inventory includes nested provider helpers and changes when their content changes', () => {
  const root = mkdtempSync(path.join(tmpdir(), 'spoonbill-auth-inventory-'));
  try {
    mkdirSync(path.join(root, 'provider'));
    writeFileSync(path.join(root, 'Login.scala'), 'object Login\n\n// policy\n');
    writeFileSync(path.join(root, 'provider', 'Host.sql'), 'create table host_session (id uuid);\n');
    const before = inventory({integration: root});
    assert.equal(before.totals.files, 2);
    assert.equal(before.totals.nonblankLines, 3);
    assert.deepEqual(inventory({integration: root}), before);
    writeFileSync(path.join(root, 'provider', 'Host.sql'), 'create table host_session (id uuid, version bigint);\n');
    const after = inventory({integration: root});
    assert.equal(after.totals.nonblankLines, before.totals.nonblankLines);
    assert.notEqual(after.sourceSha256, before.sourceSha256);
  } finally { rmSync(root, {recursive: true, force: true}); }
});
