import assert from 'node:assert/strict';
import test from 'node:test';
import {spawnSync} from 'node:child_process';
import {mkdtempSync, readFileSync, rmSync, symlinkSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const source = fileURLToPath(new URL('./envelope.sh', import.meta.url));
function shell(body, args = [], env = process.env) {
  return spawnSync('bash', ['-c', 'source "$1"; shift; ' + body, 'envelope-test', source, ...args],
    {env, encoding: 'utf8', timeout: 5000});
}
const valid = ['16000000000', '14000000000', '0', '1', '200000', '100000', '128', String(14 * 1024 ** 3)];

test('syntax and decimal envelope admit stricter limits without weakening the approved ceiling', () => {
  assert.equal(spawnSync('bash', ['-n', source]).status, 0);
  assert.equal(shell('validate_budget "$@"', valid).status, 0);
  const lower = [...valid]; lower[0] = '15000000000'; lower[4] = '100000'; lower[6] = '64';
  assert.equal(shell('validate_budget "$@"', lower).status, 0);
});

test('unlimited, overflowing, swapped and malformed budgets fail closed', () => {
  for (const [index, value] of [[0, 'max'], [0, '16000000001'], [1, '15000000000'], [2, '1'],
    [3, '0'], [4, '200001'], [5, '0'], [6, '257'], [7, String(15 * 1024 ** 3 + 1)], [0, '1+1']]) {
    const changed = [...valid]; changed[index] = value;
    assert.notEqual(shell('validate_budget "$@"', changed).status, 0, `${index}:${value}`);
  }
});

test('systemd finite deadline parsing accepts current unit formats and rejects unknown forms', () => {
  for (const [input, expected] of [['2h', '7200'], ['1h 40min', '6000'], ['10s', '10'], ['5min 10s', '310']]) {
    const result = shell('timespan_seconds "$1"', [input]);
    assert.equal(result.status, 0); assert.equal(result.stdout.trim(), expected);
  }
  for (const input of ['infinity', '2d', '5s; true', '$(true)', ''])
    assert.notEqual(shell('timespan_seconds "$1"; test -n "$1"', [input]).status, 0);
});

test('Nix version release requires both audited source identity and exact controller version', () => {
  const version = 'nix (Nix) 2.35pre-test_53ab7375';
  assert.equal(shell('validate_nix_identity "$1" "$2"', [version, version]).status, 0);
  for (const pair of [[version, version + '-other'], ['nix (Nix) 2.34.8', 'nix (Nix) 2.34.8'],
    ['nix (Nix) 2.35pre_unknown', 'nix (Nix) 2.35pre_unknown']])
    assert.notEqual(shell('validate_nix_identity "$1" "$2"', pair).status, 0);
});

test('owned Nix arguments pin local private store, nixbld, sandbox and disabled relocation to other builders', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'jsc-envelope-static-'));
  try {
    const capture = path.join(directory, 'args');
    writeFileSync(path.join(directory, 'nix'), '#!/usr/bin/env bash\nprintf "%s\\n" "$@" > "$ENVELOPE_TEST_ARGS"\n', {mode: 0o700});
    const env = {...process.env, PATH: directory + path.delimiter + process.env.PATH,
      NIX_REMOTE: 'local?root=' + directory + '/private', JSC_WORK: directory, ENVELOPE_TEST_ARGS: capture};
    const result = shell('owned_nix build --offline --no-link --file fixture.nix', [], env);
    assert.equal(result.status, 0, result.stderr);
    const args = readFileSync(capture, 'utf8');
    for (const setting of ['--store\n' + env.NIX_REMOTE, 'build-users-group\nnixbld', 'use-cgroups\nfalse',
      'sandbox\ntrue', 'auto-allocate-uids\nfalse', 'max-jobs\n1', 'cores\n1', 'builders\n\n']) assert.ok(args.includes(setting), setting);
    for (const bad of ['--store=daemon', '--option', '--builders=remote', '--use-cgroups', '--no-sandbox', '--build-users-group=', '--auto-allocate-uids', '--state-dir=/private'])
      assert.notEqual(shell('owned_nix build "$1"', [bad], env).status, 0, bad);
    assert.notEqual(shell('owned_nix store gc', [], env).status, 0);
    assert.notEqual(shell('owned_nix build --offline', [], {...env, NIX_STATE_DIR: directory}).status, 0);
    const prefetch = ['store', 'prefetch-file', '--json', '--name', 'webkitgtk-2.50.4.tar.xz',
      '--expected-hash', 'sha256-07+kc4Raz6tyY1utpeDRNP2meSxblcXFzRQbRhJb2OQ=',
      'https://webkitgtk.org/releases/webkitgtk-2.50.4.tar.xz'];
    const fetched = shell('owned_nix "$@"', prefetch, env);
    assert.equal(fetched.status, 0, fetched.stderr);
    const fetchArgs = readFileSync(capture, 'utf8');
    assert.ok(fetchArgs.includes('--store\n' + env.NIX_REMOTE));
    assert.ok(fetchArgs.endsWith(prefetch.join('\n') + '\n'));
    const rejected = [prefetch.slice(0, -1), [...prefetch, '--store=daemon'], [...prefetch, '--unpack'],
      prefetch.map((arg, i) => i === 4 ? 'other.tar.xz' : arg),
      prefetch.map((arg, i) => i === 6 ? 'sha256-invalid' : arg),
      prefetch.map((arg, i) => i === 7 ? 'https://example.invalid/source.tar.xz' : arg)];
    for (const args of rejected) {
      assert.equal(shell('owned_nix "$@"', args, env).status, 125);
      assert.equal(readFileSync(capture, 'utf8'), fetchArgs, 'refused command must not invoke Nix');
    }
  } finally { rmSync(directory, {recursive: true, force: true}); }
});

test('missing controller inputs refuse before any Nix command', () => {
  const result = spawnSync('bash', [source, 'check'], {env: {...process.env, JSC_WORK: '', JSC_UNIT: ''}, encoding: 'utf8', timeout: 5000});
  assert.equal(result.status, 125);
  assert.match(result.stderr, /envelope-refused:/);
});

test('controller priority lock requires an absolute path and refuses files and dangling links', () => {
  const directory = mkdtempSync(path.join(tmpdir(), 'jsc-priority-lock-'));
  try {
    const lock = path.join(directory, 'priority.lock');
    assert.equal(shell('priority_lock_clear "$1"', [lock]).status, 0);
    for (const invalid of ['', '/', 'relative.lock', '/invalid\nlock'])
      assert.notEqual(shell('priority_lock_clear "$1"', [invalid]).status, 0);
    writeFileSync(lock, '');
    assert.notEqual(shell('priority_lock_clear "$1"', [lock]).status, 0);
    rmSync(lock);
    symlinkSync(path.join(directory, 'missing'), lock);
    assert.notEqual(shell('priority_lock_clear "$1"', [lock]).status, 0);
  } finally { rmSync(directory, {recursive: true, force: true}); }
});
