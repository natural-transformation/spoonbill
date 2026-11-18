import assert from 'node:assert/strict';
import test from 'node:test';
import {mkdtempSync, readFileSync, rmSync, writeFileSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';

const analyzer = fileURLToPath(new URL('./analyze.mjs', import.meta.url));
function fixture(change, check) {
  const directory = mkdtempSync(join(tmpdir(), 'spoonbill-queue-counter-'));
  const rows = ['baseline', 'candidate'].flatMap(version => [1, 2, 3].map(round => ({
    label: `contended-${version}-${round}`, scenario: 'contended', seconds: 3,
    operations: 100, elapsedSeconds: 3, operationsPerSecond: 100 / 3,
    allocatedBytes: 650000, bytesPerOperation: 6500, correct: true,
    mainSelfAllocatedBytes: 65536, workerSelfAllocatedBytes: [100000, 100000, 100000, 100000, 100000],
    selfAllocatedBytes: 565536, threadsAliveAtSnapshot: 10, expectedLiveThreads: 10,
    escapingControlBytesPerThread: 65536, allocationCounterValid: true,
  })));
  try {
    change(rows);
    writeFileSync(join(directory, 'trials.jsonl'), rows.map(row => JSON.stringify(row)).join('\n') + '\n');
    const result = spawnSync(process.execPath, [analyzer, directory, 'contended'], {encoding: 'utf8'});
    check(result, directory);
  } finally { rmSync(directory, {recursive: true, force: true}); }
}

test('live-worker counter evidence is summarized with its protocol', () => fixture(() => {}, (result, directory) => {
  assert.equal(result.status, 0, result.stderr);
  const summary = JSON.parse(readFileSync(join(directory, 'summary.json'), 'utf8'));
  assert.equal(summary.trials, 6);
  assert.match(summary.allocationCounterProtocol, /self-byte lower bound/);
}));

test('a global counter below measured self-allocation is rejected', () => fixture(rows => {
  rows[0].allocatedBytes = 500000;
  rows[0].bytesPerOperation = 5000;
}, result => {
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /AssertionError/);
}));

test('legacy and counter-checked rows cannot be mixed', () => fixture(rows => {
  delete rows[0].allocationCounterValid;
}, result => {
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /Mixed allocation-counter protocols/);
}));
