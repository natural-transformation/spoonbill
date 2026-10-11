import assert from 'node:assert/strict';
import test from 'node:test';
import {spawnSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';
const source = fileURLToPath(new URL('./oom-proof.sh', import.meta.url));
function validate(args) {
  return spawnSync('bash', ['-c', 'source "$1"; shift; validate_oom_result "$@"', 'oom-test', source, ...args],
    {encoding: 'utf8', timeout: 5000});
}
const complete = ['oom-kill', '0', 'failed', '67108864', '0', '0'];
test('toy OOM verifier requires actual OOM plus complete group and sibling release', () => {
  assert.equal(spawnSync('bash', ['-n', source]).status, 0);
  assert.equal(validate(complete).status, 0);
  const collected = [...complete]; collected[2] = 'inactive';
  assert.equal(validate(collected).status, 0);
  for (const [index, value] of [[0, 'signal'], [0, 'success'], [1, '123'], [2, 'active'],
    [3, '[not set]'], [3, 'infinity'], [3, '50331647'], [3, '83886081'], [3, '1+1'],
    [4, '1'], [4, ''], [5, '1']]) {
    const changed = [...complete]; changed[index] = value;
    assert.notEqual(validate(changed).status, 0, `${index}: ${value}`);
  }
});
test('helper rejects absent or unsupported modes without allocating', () => {
  for (const args of [[], ['start'], ['verify'], ['payload']]) {
    const result = spawnSync('bash', [source, ...args], {encoding: 'utf8', timeout: 5000});
    assert.equal(result.status, 125);
    assert.match(result.stderr, /oom-proof-refused:/);
  }
});
