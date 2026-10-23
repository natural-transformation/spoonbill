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
const retention = `${migrations}/V6__browser_retention.sql`;

function withFixture(body) {
  const directory = mkdtempSync(path.join(tmpdir(), 'spoonbill-schema-test-'));
  try {
    for (const relative of [exporter, baseline, upgrade, retention,
      ...['JdbcBrowserClock.scala', 'JdbcBrowserSessions.scala', 'JdbcBrowserPersistence.scala', 'JdbcBrowserRetention.scala'].map(name => `${sources}/${name}`)]) {
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

test('schema export updates stale V6 without rewriting historical V4 or V5 bytes', () => withFixture(directory => {
  const baselineFile = path.join(directory, baseline);
  const upgradeFile = path.join(directory, upgrade);
  const originalBaseline = readFileSync(baselineFile);
  const originalUpgrade = readFileSync(upgradeFile);
  const retentionFile = path.join(directory, retention);
  const expectedRetention = readFileSync(retentionFile);
  writeFileSync(retentionFile, '-- stale forward migration\n');

  const stale = run(directory, '--check');
  assert.notEqual(stale.status, 0);
  assert.match(stale.stderr, /Retention upgrade DDL is stale/);

  const result = run(directory, '--write');
  assert.equal(result.status, 0, result.stderr);
  assert.deepEqual(readFileSync(baselineFile), originalBaseline);
  assert.deepEqual(readFileSync(upgradeFile), originalUpgrade);
  assert.deepEqual(readFileSync(retentionFile), expectedRetention);
  const metadata = JSON.parse(result.stdout);
  assert.equal(metadata.tables, 11);
  assert.equal(metadata.upgradeStatements, 3);
  assert.equal(metadata.upgradeTables, 1);
  assert.equal(metadata.retentionStatements, 4);
  assert.equal(metadata.retentionTables, 2);
  const checked = run(directory, '--check');
  assert.equal(checked.status, 0, checked.stderr);
}));

test('schema export rejects changed historical V4 bytes in both modes without rewriting files', () => withFixture(directory => {
  const baselineFile = path.join(directory, baseline);
  const upgradeFile = path.join(directory, upgrade);
  // A comment preserves SQL semantics but changes the applied migration bytes.
  const changedBaseline = Buffer.concat([readFileSync(baselineFile), Buffer.from('-- changed history\n')]);
  const untouchedUpgrade = readFileSync(upgradeFile);
  const retentionFile = path.join(directory, retention);
  const untouchedRetention = Buffer.from('-- preserve even this stale V6 when V4 is invalid\n');
  writeFileSync(baselineFile, changedBaseline);
  writeFileSync(retentionFile, untouchedRetention);

  for (const mode of ['--check', '--write']) {
    const result = run(directory, mode);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Historical V4 migration changed/);
    assert.deepEqual(readFileSync(baselineFile), changedBaseline);
    assert.deepEqual(readFileSync(upgradeFile), untouchedUpgrade);
    assert.deepEqual(readFileSync(retentionFile), untouchedRetention);
  }
}));

test('schema export rejects changed historical V5 bytes in both modes without rewriting files', () => withFixture(directory => {
  const baselineFile = path.join(directory, baseline);
  const upgradeFile = path.join(directory, upgrade);
  const retentionFile = path.join(directory, retention);
  const originalBaseline = readFileSync(baselineFile);
  const changedUpgrade = Buffer.concat([readFileSync(upgradeFile), Buffer.from('-- changed history\n')]);
  const untouchedRetention = Buffer.from('-- preserve this stale V6 when V5 is invalid\n');
  writeFileSync(upgradeFile, changedUpgrade);
  writeFileSync(retentionFile, untouchedRetention);
  for (const mode of ['--check', '--write']) {
    const result = run(directory, mode);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Historical V5 migration changed/);
    assert.deepEqual(readFileSync(baselineFile), originalBaseline);
    assert.deepEqual(readFileSync(upgradeFile), changedUpgrade);
    assert.deepEqual(readFileSync(retentionFile), untouchedRetention);
  }
}));

test('retention migration defines permanent fences and deployment restrictions', () => {
  const sql = readFileSync(path.join(repository, retention), 'utf8');
  assert.match(sql, /CREATE TABLE IF NOT EXISTS spoonbill_browser_retention/);
  assert.match(sql, /CREATE TABLE IF NOT EXISTS spoonbill_browser_work/);
  for (const column of ['id_prefix', 'credential_key', 'id_counter', 'binding_floor', 'view_floor']) assert.ok(sql.includes(column));
  assert.match(sql, /ALTER TABLE spoonbill_browser_slot ADD COLUMN IF NOT EXISTS last_seen/);
  assert.match(sql, /ALTER TABLE spoonbill_browser_view ADD COLUMN IF NOT EXISTS last_seen/);
  assert.match(sql, /Drain every old serving\/writing binary/);
  assert.match(sql, /incompatible with rollback/);
  assert.match(sql, /NEVER delete a reclamation-floor record/);
});

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
  const originalRetention = readFileSync(path.join(directory, retention));

  for (const mode of ['--check', '--write']) {
    const result = run(directory, mode);
    assert.notEqual(result.status, 0);
    assert.match(result.stderr, /Frozen V4 and V5 plus the forward retention upgrade must cover runtime DDL exactly/);
    assert.deepEqual(readFileSync(path.join(directory, baseline)), originalBaseline);
    assert.deepEqual(readFileSync(path.join(directory, upgrade)), originalUpgrade);
    assert.deepEqual(readFileSync(path.join(directory, retention)), originalRetention);
  }
}));
