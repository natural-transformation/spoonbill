import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {copyFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import test from 'node:test';
import {fileURLToPath} from 'node:url';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const exporter = 'scripts/export-browser-auth-schema.mjs';
const sources = 'modules/security-jdbc/src/main/scala/spoonbill/security/jdbc';
const migrations = 'modules/security-jdbc/src/main/resources/spoonbill/migrations/postgresql';
const baseline = `${migrations}/V4__browser_auth.sql`;
const upgrade = `${migrations}/V5__authenticated_session_replacement.sql`;

function withFixture(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'spoonbill-schema-test-'));
  try {
    for (const relative of [exporter, baseline, upgrade,
      ...['JdbcBrowserClock.scala', 'JdbcBrowserSessions.scala', 'JdbcBrowserPersistence.scala'].map(name => `${sources}/${name}`)]) {
      const destination = path.join(directory, relative);
      mkdirSync(path.dirname(destination), {recursive: true});
      copyFileSync(path.join(repository, relative), destination);
    }
    body(directory);
  } finally {
    rmSync(directory, {recursive: true, force: true});
  }
}

function run(directory, mode) {
  // Reuse the Nix-managed test runtime; no PATH lookup or installation occurs.
  const result = spawnSync(process.execPath, [path.join(directory, exporter), mode], {
    cwd: directory, encoding: 'utf8', timeout: 15000, maxBuffer: 1024 * 1024,
  });
  assert.ifError(result.error);
  assert.equal(result.signal, null, 'Exporter must finish without a signal');
  return result;
}

test('schema export updates stale V5 without rewriting the historical V4 bytes', () => withFixture(directory => {
  const baselineFile = path.join(directory, baseline);
  const upgradeFile = path.join(directory, upgrade);
  const originalBaseline = readFileSync(baselineFile);
  const expectedUpgrade = readFileSync(upgradeFile);
  writeFileSync(upgradeFile, '-- stale forward migration\n');

  const result = run(directory, '--write');
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(readFileSync(baselineFile), originalBaseline);
  assert.deepEqual(readFileSync(upgradeFile), expectedUpgrade);
  const checked = run(directory, '--check');
  assert.equal(checked.status, 0, checked.stderr);
}));

test('schema export rejects changed historical V4 bytes in both modes without rewriting files', () => withFixture(directory => {
  const baselineFile = path.join(directory, baseline);
  const upgradeFile = path.join(directory, upgrade);
  // A comment preserves SQL semantics but changes the applied migration bytes.
  const changedBaseline = Buffer.concat([readFileSync(baselineFile), Buffer.from('-- changed history\n')]);
  const untouchedUpgrade = Buffer.from('-- preserve even this stale V5 when V4 is invalid\n');
  writeFileSync(baselineFile, changedBaseline);
  writeFileSync(upgradeFile, untouchedUpgrade);

  for (const mode of ['--check', '--write']) {
    const result = run(directory, mode);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Historical V4 migration changed/);
    assert.deepEqual(readFileSync(baselineFile), changedBaseline);
    assert.deepEqual(readFileSync(upgradeFile), untouchedUpgrade);
  }
}));

test('schema export rejects runtime DDL outside the frozen baseline and reviewed upgrade', () => withFixture(directory => {
  const sourceFile = path.join(directory, sources, 'JdbcBrowserSessions.scala');
  const source = readFileSync(sourceFile, 'utf8');
  const lastStatementEnd = source.lastIndexOf('"""');
  assert.ok(lastStatementEnd >= 0, 'Expected the runtime schema literal');
  // Add an index, keeping the table count unchanged so exact migration
  // coverage, rather than the table-count guard, must reject the drift.
  const addition = ',\n    """CREATE INDEX IF NOT EXISTS spoonbill_schema_unreviewed\n' +
    '      ON spoonbill_browser_replacement(successor_session_id)"""';
  writeFileSync(sourceFile, source.slice(0, lastStatementEnd + 3) + addition + source.slice(lastStatementEnd + 3));
  const originalBaseline = readFileSync(path.join(directory, baseline));
  const originalUpgrade = readFileSync(path.join(directory, upgrade));

  for (const mode of ['--check', '--write']) {
    const result = run(directory, mode);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Frozen V4 plus the forward upgrade must cover runtime DDL exactly/);
    assert.deepEqual(readFileSync(path.join(directory, baseline)), originalBaseline);
    assert.deepEqual(readFileSync(path.join(directory, upgrade)), originalUpgrade);
  }
}));
