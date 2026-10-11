import assert from 'node:assert/strict';
import test from 'node:test';
import {violations} from '../scripts/check-core-purity.mjs';

test('pure immutable decisions accept explicit time and typed results', () => {
  assert.deepEqual(violations('import java.time.Instant\nfinal case class Value(expiry: Instant) { def valid(now: Instant) = now.isBefore(expiry) }'), []);
});

test('ambient runtime APIs and qualified calls cannot enter core', () => {
  for (const value of [
    'java.time.Instant.now()', 'java.util.UUID.randomUUID()', 'System.getenv("HOME")',
    'scala.concurrent.Future.successful(1)', 'spoonbill.effect.Effect', 'spoonbill.security.jdbc.JdbcBrowserSessions',
    'scala.collection.mutable.Map.empty', 'private var changed = false', 'Thread.currentThread()',
  ]) assert.ok(violations(value).length > 0, value);
});

test('comments do not trigger the guard and source line numbers survive block comments', () => {
  assert.deepEqual(violations('// Thread.currentThread() is forbidden\n/* Instant.now()\n comment */\nval now = Instant.now()'), [
    {line: 4, rule: 'ambient clock or entropy'},
  ]);
});
