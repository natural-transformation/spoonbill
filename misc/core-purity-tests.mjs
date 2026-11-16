import assert from 'node:assert/strict';
import test from 'node:test';
import {mkdtempSync, mkdirSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {violations, checkMachines} from '../scripts/check-core-purity.mjs';

test('pure immutable decisions accept explicit time and typed results', () => {
  assert.deepEqual(violations('import java.time.Instant\nfinal case class Value(expiry: Instant) { def valid(now: Instant) = now.isBefore(expiry) }'), []);
});

test('a machine may live in the effect module without importing effect types', () => {
  assert.deepEqual(violations('package spoonbill.effect\nobject DeadlineMachine { case class State(now: Long) }'), []);
  assert.ok(violations('package spoonbill.effect\nimport spoonbill.effect.Effect').length > 0);
  assert.ok(violations('package spoonbill.protocol; val now = System.nanoTime()').length > 0);
});

test('ambient runtime APIs and qualified calls cannot enter core', () => {
  for (const value of [
    'java.time.Instant.now()', 'java.util.UUID.randomUUID()', 'System.getenv("HOME")',
    'scala.concurrent.Future.successful(1)', 'spoonbill.effect.Effect', 'spoonbill.security.jdbc.JdbcBrowserSessions',
    'scala.collection.mutable.Map.empty', 'private var changed = false', 'Thread.currentThread()',
    'val callback: IO[Unit]', 'val job: ZIO[Any, Nothing, Unit]',
  ]) assert.ok(violations(value).length > 0, value);
});

test('machine discovery covers modules and adapters but excludes tests and build output', () => {
  const root = mkdtempSync(join(tmpdir(), 'spoonbill-machines-'));
  try {
    for (const path of ['modules/runtime/src/main/scala/RuntimeMachine.scala', 'interop/adapter/src/main/scala/AdapterMachine.scala',
      'modules/runtime/src/test/scala/TestMachine.scala', 'modules/runtime/target/src/main/scala/GeneratedMachine.scala']) {
      const file = join(root, path);
      mkdirSync(join(file, '..'), {recursive: true});
      writeFileSync(file, 'private var illegal = 0');
    }
    const result = checkMachines(root);
    assert.equal(result.files, 2);
    assert.equal(result.failures.length, 2);
    assert.deepEqual(new Set(result.failures.map(f => f.file)), new Set([
      'modules/runtime/src/main/scala/RuntimeMachine.scala', 'interop/adapter/src/main/scala/AdapterMachine.scala',
    ]));
  } finally { rmSync(root, {recursive: true, force: true}); }
});

test('comments do not trigger the guard and source line numbers survive block comments', () => {
  assert.deepEqual(violations('// Thread.currentThread() is forbidden\n/* Instant.now()\n comment */\nval now = Instant.now()'), [
    {line: 4, rule: 'ambient clock or entropy'},
  ]);
});
