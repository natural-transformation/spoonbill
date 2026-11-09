import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const runner = fileURLToPath(new URL('../scripts/verify-recovery-kit-release.sh', import.meta.url));

function fixture(run, sbtStatus = 0) {
  const root = realpathSync(mkdtempSync(path.join(tmpdir(), 'spoonbill-release-check-')));
  const bin = path.join(root, 'bin');
  const temporary = path.join(root, 'temporary builds');
  const capture = path.join(root, 'capture.json');
  mkdirSync(bin);
  mkdirSync(temporary);
  // The fake receives only what the real sbt launcher would receive. Inspect
  // files during its invocation, before the runner cleans the temporary build.
  writeFileSync(path.join(bin, 'sbt'), `#!${process.execPath}
const fs = require('node:fs');
const path = require('node:path');
const args = process.argv.slice(2);
const repositories = args.find(arg => arg.startsWith('-Dsbt.repository.config=')).split('=').slice(1).join('=');
fs.writeFileSync(${JSON.stringify(capture)}, JSON.stringify({
  args, env: process.env, cwd: process.cwd(),
  files: fs.readdirSync('.'), projectFiles: fs.readdirSync('project'),
  build: fs.readFileSync('consumer.sbt', 'utf8'),
  hasAdapter: fs.existsSync('src/main/scala/FilePayloadStore.scala'),
  hasLaws: fs.existsSync('src/test/scala/FilePayloadStoreLawsSpec.scala'),
  repositories: fs.readFileSync(repositories, 'utf8'),
  machineOptions: fs.readFileSync(process.env.SBT_ETC_FILE, 'utf8'),
}));
process.exit(${sbtStatus});
`, { mode: 0o755 });
  const invoke = (args = ['10.0.0'], overrides = {}) => spawnSync('bash', [runner, ...args], {
    cwd: root, encoding: 'utf8', env: {
      ...process.env, IN_NIX_SHELL: 'impure', PATH: `${bin}:${process.env.PATH}`, TMPDIR: temporary,
      SBT_OPTS: '-Dspoonbill.version=local-SNAPSHOT', JAVA_OPTS: '-Duser.home=/untrusted',
      JAVA_TOOL_OPTIONS: '-Duser.home=/untrusted', JDK_JAVA_OPTIONS: '-Duser.home=/untrusted',
      JVM_OPTS: '-Duser.home=/untrusted', COURSIER_REPOSITORIES: 'file:///untrusted',
      SBT_ETC_FILE: '/untrusted/sbtopts',
      COURSIER_CACHE: '/untrusted', XDG_CONFIG_HOME: '/untrusted', ...overrides,
    },
  });
  try { run({ invoke, capture, temporary }); }
  finally { rmSync(root, { recursive: true, force: true }); }
}

test('release verification rejects missing, unstable and malformed versions before launching sbt', () => fixture(({ invoke, capture, temporary }) => {
  for (const args of [[], ['10.0.0', 'extra'], ['10.0.0-SNAPSHOT'], ['10.0.0-rc.1'], ['v10.0.0'],
    ['01.0.0'], ['10.0'], ['10.0.0\ntest'], ['$(echo unsafe)']]) {
    const result = invoke(args);
    assert.equal(result.status, 2, result.stderr);
    assert.match(result.stderr, /stable-version X.Y.Z/);
  }
  assert.equal(existsSync(capture), false);
  assert.deepEqual(readdirSync(temporary), []);
}));

test('release verification requires an active Nix shell', () => fixture(({ invoke, capture }) => {
  const result = invoke(['10.0.0'], { IN_NIX_SHELL: '' });
  assert.equal(result.status, 2, result.stderr);
  assert.match(result.stderr, /repository flake: nix develop/);
  assert.equal(existsSync(capture), false);
}));

test('release verification uses a clean consumer and private Central-only caches', () => fixture(({ invoke, capture, temporary }) => {
  const result = invoke();
  assert.equal(result.status, 0, result.stderr);
  const invocation = JSON.parse(readFileSync(capture, 'utf8'));
  const isolatedRoot = path.dirname(invocation.cwd);
  assert.equal(path.dirname(isolatedRoot), temporary);
  assert.deepEqual(invocation.files.sort(), ['consumer.sbt', 'project', 'src']);
  assert.deepEqual(invocation.projectFiles, ['build.properties']);
  assert.ok(invocation.hasAdapter && invocation.hasLaws);
  assert.match(invocation.build, /Resolver.mavenCentral/);
  assert.deepEqual(invocation.args.slice(-2), ['verifyKitGraph', 'test']);
  for (const expected of ['-Dspoonbill.version=10.0.0', '-Dspoonbill.released=true',
    '-Dsbt.override.build.repos=true', '-Dsbt.server.autostart=false']) {
    assert.ok(invocation.args.includes(expected), expected);
  }
  for (const prefix of ['-J-Duser.home=', '-J-Djava.io.tmpdir=', '-Dsbt.global.base=', '-Dsbt.boot.directory=',
    '-Dsbt.ivy.home=', '-Dsbt.preloaded=', '-Dcoursier.cache=', '-Dsbt.repository.config=']) {
    const value = invocation.args.find(arg => arg.startsWith(prefix))?.slice(prefix.length);
    assert.ok(value?.startsWith(`${isolatedRoot}/`), `${prefix} must be private`);
  }
  for (const key of ['SBT_OPTS', 'JAVA_OPTS', 'JVM_OPTS', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS']) {
    assert.equal(invocation.env[key], undefined, `${key} must not be inherited`);
  }
  for (const key of ['COURSIER_CACHE', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'TMPDIR', 'SBT_ETC_FILE']) {
    assert.ok(invocation.env[key].startsWith(`${isolatedRoot}/`), key);
  }
  assert.equal(invocation.env.COURSIER_REPOSITORIES, 'https://repo.maven.apache.org/maven2');
  assert.equal(invocation.repositories, '[repositories]\nmaven-central: https://repo.maven.apache.org/maven2\n');
  assert.equal(invocation.machineOptions, '');
  assert.match(result.stdout, /laws passed for 10.0.0/);
  assert.deepEqual(readdirSync(temporary), []);
}));

test('release verification propagates sbt failure and cleans the temporary build', () => fixture(({ invoke, capture, temporary }) => {
  const result = invoke();
  assert.equal(result.status, 23, result.stderr);
  assert.equal(existsSync(capture), true);
  assert.doesNotMatch(result.stdout, /laws passed/);
  assert.deepEqual(readdirSync(temporary), []);
}, 23));
