import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync, spawnSync } from 'node:child_process';
import test from 'node:test';

const resolver = fileURLToPath(new URL('../scripts/resolve-release.sh', import.meta.url));
function fixture(run) {
  const cwd = mkdtempSync(path.join(tmpdir(), 'spoonbill-release-'));
  const git = (...args) => execFileSync('git', [
    '-c', 'user.name=Release Test', '-c', 'user.email=test@example.invalid',
    '-c', 'commit.gpgSign=false', '-c', 'tag.gpgSign=false', '-c', 'core.hooksPath=/dev/null', ...args,
  ], { cwd, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  try {
    git('init', '-b', 'main');
    git('commit', '--allow-empty', '-m', 'reviewed');
    const approved = git('rev-parse', 'HEAD');
    git('commit', '--allow-empty', '-m', 'new main');
    git('update-ref', 'refs/remotes/origin/main', 'HEAD');
    git('checkout', '-b', 'unreviewed');
    git('commit', '--allow-empty', '-m', 'unreviewed');
    const unreviewed = git('rev-parse', 'HEAD');
    const resolve = (env = {}) => {
      const output = path.join(cwd, 'output');
      writeFileSync(output, '');
      const result = spawnSync('bash', [resolver], { cwd, encoding: 'utf8', env: {
        ...process.env, GITHUB_EVENT_NAME: 'workflow_dispatch', GITHUB_REF: 'refs/heads/main',
        GITHUB_SHA: approved, REQUESTED_VERSION: '5.1.0', RELEASE_TAG: '', GITHUB_OUTPUT: output, ...env,
      } });
      return { ...result, output: readFileSync(output, 'utf8') };
    };
    run({ git, approved, unreviewed, resolve });
  } finally { rmSync(cwd, { recursive: true, force: true }); }
}

test('manual release keeps the immutable approved event commit when main advances', () => fixture(({ approved, resolve }) => {
  const result = resolve();
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.output, `sha=${approved}\nversion=5.1.0\n`);
}));

test('manual release rejects branch workflows, unreviewed commits and malformed versions', () => fixture(({ unreviewed, resolve }) => {
  for (const env of [
    { GITHUB_REF: 'refs/heads/unreviewed' }, { GITHUB_SHA: unreviewed },
    { GITHUB_SHA: '--help' }, { REQUESTED_VERSION: '5.1.0\nsha=unreviewed' },
    { REQUESTED_VERSION: '$(echo unsafe)' }, { GITHUB_EVENT_NAME: 'push' },
  ]) {
    const result = resolve(env);
    assert.notEqual(result.status, 0);
    assert.equal(result.output, '');
  }
}));

test('annotated release tags resolve to the approved commit and checked version', () => fixture(({ git, approved, resolve }) => {
  git('tag', '-a', 'v5.1.0-rc.1', approved, '-m', 'release');
  const result = resolve({ GITHUB_EVENT_NAME: 'release', GITHUB_REF: 'refs/tags/v5.1.0-rc.1', RELEASE_TAG: 'v5.1.0-rc.1' });
  assert.equal(result.status, 0, result.stderr);
  assert.equal(result.output, `sha=${approved}\nversion=5.1.0-rc.1\n`);
}));

test('tagged releases reject moved tags, unreviewed commits and ref mismatches', () => fixture(({ git, approved, unreviewed, resolve }) => {
  git('tag', 'v5.1.0', approved);
  const event = { GITHUB_EVENT_NAME: 'release', GITHUB_REF: 'refs/tags/v5.1.0', RELEASE_TAG: 'v5.1.0' };
  assert.notEqual(resolve({ ...event, GITHUB_REF: 'refs/tags/v5.2.0' }).status, 0);
  git('tag', '-f', 'v5.1.0', unreviewed);
  const moved = resolve(event);
  assert.notEqual(moved.status, 0);
  assert.match(moved.stderr, /tag moved/);
  const outsideMain = resolve({ ...event, GITHUB_SHA: unreviewed });
  assert.notEqual(outsideMain.status, 0);
  assert.match(outsideMain.stderr, /reviewed main history/);
  assert.equal(outsideMain.output, '');
}));
