import assert from 'node:assert/strict';
import test, {before, after} from 'node:test';
import fs from 'node:fs';
import {tmpdir} from 'node:os';
import path from 'node:path';
import {createHash} from 'node:crypto';
import childProcess, {spawnSync} from 'node:child_process';
import {syncBuiltinESMExports} from 'node:module';
import {captureArtifact, loadArtifact, launchCommand, checkJar, archiveArtifact, verifyArchive} from './artifact.mjs';
import {inventory} from '../inventory.mjs';
import {clientInventory} from '../client-inventory.mjs';
import {policyFields} from './reference-policy.mjs';

const root = fs.mkdtempSync(path.join(tmpdir(), 'spoonbill-artifact-test-'));
const sha = value => createHash('sha256').update(value).digest('hex');
let sequence = 0, java, javac, jar, compiled, helperJar;
function managed(name) {
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (directory.startsWith('/nix/store/')) {
      const candidate = path.join(directory, name);
      try { if (fs.realpathSync(candidate).startsWith('/nix/store/')) return candidate; } catch (_) { /* continue */ }
    }
  }
  throw new Error(`Run this test in nix develop: missing managed ${name}`);
}
function run(command, args, options = {}) {
  const result = spawnSync(command, args, {encoding: 'utf8', timeout: 30000, maxBuffer: 1024 * 1024, ...options});
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  return result.stdout;
}
before(() => {
  java = managed('java'); javac = managed('javac'); jar = managed('jar');
  compiled = path.join(root, 'compiled/classes');
  fs.mkdirSync(compiled, {recursive: true});
  fs.writeFileSync(path.join(root, 'Main.java'), 'public class Main { public static void main(String[] args) throws Exception { System.out.print(new String(Main.class.getResourceAsStream("/choice.txt").readAllBytes(), java.nio.charset.StandardCharsets.UTF_8) + "/" + Helper.value() + "/" + args[0]); }}');
  fs.writeFileSync(path.join(root, 'Helper.java'), 'public class Helper { public static String value() { return "jar"; }}');
  run(javac, ['-d', compiled, path.join(root, 'Main.java'), path.join(root, 'Helper.java')]);
  helperJar = path.join(root, 'helper.jar');
  run(jar, ['--create', '--file', helperJar, '-C', compiled, 'Helper.class']);
  fs.unlinkSync(path.join(compiled, 'Helper.class'));
});
after(() => fs.rmSync(root, {recursive: true, force: true}));

function fixture() {
  const directory = path.join(root, `fixture-${sequence++}`);
  const first = path.join(directory, 'first/classes'), second = path.join(directory, 'second/classes');
  fs.mkdirSync(first, {recursive: true}); fs.mkdirSync(second, {recursive: true});
  fs.copyFileSync(path.join(compiled, 'Main.class'), path.join(first, 'Main.class'));
  fs.writeFileSync(path.join(first, 'choice.txt'), 'first'); fs.writeFileSync(path.join(second, 'choice.txt'), 'second');
  // Exercise ordering where traversal preorder is not the lexical file-index order.
  fs.mkdirSync(path.join(first, 'a')); fs.writeFileSync(path.join(first, 'a/x'), 'x'); fs.writeFileSync(path.join(first, 'a.txt'), 'a');
  const library = path.join(directory, 'library.jar'); fs.copyFileSync(helperJar, library);
  const classpathFile = path.join(directory, 'classpath.txt');
  fs.writeFileSync(classpathFile, [first, second, library].join(path.delimiter) + '\n');
  const sourceDirectory = path.join(directory, 'source'); fs.mkdirSync(sourceDirectory);
  fs.copyFileSync(path.join(root, 'Main.java'), path.join(sourceDirectory, 'Main.java'));
  const sourceInventory = inventory({application: sourceDirectory});
  const proof = {algorithm: 'PBKDF2WithHmacSHA256', iterations: 1, keyBits: 256, saltBytes: 16};
  const policy = Object.fromEntries(['short', 'representative'].map(name => [name,
    {...Object.fromEntries(Object.keys(policyFields).map(key => [key, 2])), proof: {...proof, iterations: name === 'short' ? 1 : 100}}]));
  const bundle = path.join(directory, 'bundle.js'); fs.writeFileSync(bundle, 'console.log("fixture");');
  const client = clientInventory(sourceDirectory, bundle, {application: sourceDirectory});
  const sourceInventoryFile = path.join(directory, 'source.json'), referencePolicyFile = path.join(directory, 'policy.json'), clientInventoryFile = path.join(directory, 'client.json');
  for (const [file, value] of [[sourceInventoryFile, sourceInventory], [referencePolicyFile, policy], [clientInventoryFile, client]]) fs.writeFileSync(file, JSON.stringify(value));
  return {directory, first, second, library, classpathFile, sourceInventoryFile, referencePolicyFile, clientInventoryFile,
    destination: path.join(directory, 'capture')};
}
function incomplete(f, pattern) {
  assert.throws(() => captureArtifact(f), pattern ?? /artifact-incomplete/);
  const state = JSON.parse(fs.readFileSync(path.join(f.destination, 'capture-state.json')));
  assert.equal(state.status, 'incomplete');
  assert.equal(fs.existsSync(path.join(f.destination, 'artifact.json')), false);
  assert.throws(() => loadArtifact(f.destination));
  assert.ok(!JSON.stringify(state).includes(root));
}

test('real compiled classpath relocates, preserves resource precedence and runs without original inputs', () => {
  const f = fixture(), manifest = captureArtifact(f);
  assert.equal(manifest.sourceInventorySha256, sha(fs.readFileSync(f.sourceInventoryFile)));
  assert.equal(manifest.referencePolicySha256, sha(fs.readFileSync(f.referencePolicyFile)));
  assert.equal(manifest.clientInventorySha256, sha(fs.readFileSync(f.clientInventoryFile)));
  assert.ok(!JSON.stringify(manifest).includes(root));
  assert.ok(!JSON.stringify(manifest).includes('mtime'));
  const relocated = path.join(root, 'relocated'); fs.renameSync(f.destination, relocated);
  fs.rmSync(f.directory, {recursive: true, force: true});
  const command = launchCommand(relocated, 'Main', ['literal $value; no shell']);
  assert.equal(run(java, command.args), 'first/jar/literal $value; no shell');
  const fromLoader = JSON.parse(run(process.execPath, [path.join(relocated, 'launcher.mjs'), 'command', relocated, 'Main', 'cli']));
  assert.equal(run(java, fromLoader.args), 'first/jar/cli');
  assert.equal(loadArtifact(relocated).manifest.artifactSha256, manifest.artifactSha256);
});

test('hashes depend on bytes and ordered runtime graph, never source paths or modification times', () => {
  const a = fixture(), b = fixture();
  fs.utimesSync(path.join(b.first, 'Main.class'), 123, 456);
  const left = captureArtifact(a), right = captureArtifact(b);
  assert.deepEqual(left, right);
  const c = fixture(); fs.writeFileSync(c.classpathFile, [c.second, c.first, c.library].join(path.delimiter));
  const reordered = captureArtifact(c);
  assert.notEqual(reordered.dependencyGraphSha256, left.dependencyGraphSha256);
  assert.notEqual(reordered.artifactSha256, left.artifactSha256);
  assert.equal(run(java, launchCommand(c.destination, 'Main', ['order']).args), 'second/jar/order');
});

test('destination is exclusive and output nested inside an input never contaminates it', () => {
  const f = fixture(); captureArtifact(f);
  const before = fs.readFileSync(path.join(f.destination, 'artifact.json'));
  assert.throws(() => captureArtifact(f), /destination-exists/);
  assert.deepEqual(fs.readFileSync(path.join(f.destination, 'artifact.json')), before);
  const nested = fixture(); nested.destination = path.join(nested.first, 'capture');
  assert.throws(() => captureArtifact(nested), /destination-inside-input/);
  assert.equal(fs.existsSync(nested.destination), false);
});

test('malformed and implicit classpaths leave an explicit incomplete capture', () => {
  for (const value of ['relative.jar', '/missing.jar\n/more.jar', '', '/missing.jar:', '*']) {
    const f = fixture(); fs.writeFileSync(f.classpathFile, value); incomplete(f);
  }
  const f = fixture();
  const manifest = path.join(f.directory, 'MANIFEST.MF');
  fs.writeFileSync(manifest, 'Manifest-Version: 1.0\nClass-Path: ../private.jar\n\n');
  run(jar, ['--create', '--file', f.library, '--manifest', manifest, '-C', compiled, 'Main.class']);
  incomplete(f);
});

test('symlinks, hidden runtime files and non-class directories fail closed', () => {
  for (const type of ['symlink', 'hidden', 'directory']) {
    const f = fixture();
    if (type === 'symlink') fs.symlinkSync(f.sourceInventoryFile, path.join(f.first, 'leak'));
    if (type === 'hidden') fs.writeFileSync(path.join(f.first, '.env'), 'do not capture');
    if (type === 'directory') fs.writeFileSync(f.classpathFile, path.join(f.directory, 'source'));
    incomplete(f);
  }
});

test('entry, total-byte, file-byte and classpath-entry bounds refuse partial publication', () => {
  for (const bound of [{entries: 2}, {bytes: 20}, {fileBytes: 20}, {classpathEntries: 1}]) {
    const f = fixture(); f.limits = bound;
    incomplete(f);
  }
});

test('source writes during copying, including equal-size edits, cannot publish complete', () => {
  const f = fixture(), target = path.join(f.first, 'choice.txt');
  const original = fs.readSync; let changed = false;
  fs.readSync = function(fd, ...args) {
    const n = original(fd, ...args);
    if (!changed && fs.fstatSync(fd).ino === fs.statSync(target).ino) { changed = true; fs.writeFileSync(target, 'other'); }
    return n;
  };
  syncBuiltinESMExports();
  try { incomplete(f); assert.equal(changed, true); }
  finally { fs.readSync = original; syncBuiltinESMExports(); }
});

test('final recheck detects changed earlier files, new directory members and changed classpath order', () => {
  for (const kind of ['earlier-file', 'directory-member', 'classpath']) {
    const f = fixture(), original = fs.readSync; let changed = false;
    fs.readSync = function(fd, ...args) {
      const n = original(fd, ...args);
      if (!changed && fs.fstatSync(fd).ino === fs.statSync(f.library).ino) {
        changed = true;
        if (kind === 'earlier-file') fs.writeFileSync(path.join(f.first, 'choice.txt'), 'other');
        if (kind === 'directory-member') fs.writeFileSync(path.join(f.first, 'new-resource'), 'new');
        if (kind === 'classpath') fs.writeFileSync(f.classpathFile, [f.second, f.first, f.library].join(path.delimiter));
      }
      return n;
    };
    syncBuiltinESMExports();
    try { incomplete(f); assert.equal(changed, true); }
    finally { fs.readSync = original; syncBuiltinESMExports(); }
  }
});

test('a report replaced after validation never persists its unvalidated replacement', () => {
  const f = fixture(), original = fs.mkdirSync;
  const secret = 'unvalidated-private-document'; let changed = false;
  fs.mkdirSync = function(location, ...args) {
    if (String(location) === path.join(fs.realpathSync(f.directory), 'capture/provenance')) {
      changed = true;
      fs.writeFileSync(f.clientInventoryFile, JSON.stringify({secret}));
    }
    return original(location, ...args);
  };
  syncBuiltinESMExports();
  try {
    incomplete(f); assert.equal(changed, true);
    const persisted = fs.readFileSync(path.join(f.destination, 'provenance/client-inventory.json'), 'utf8');
    assert.equal(persisted.includes(secret), false);
    assert.equal(JSON.parse(persisted).schema, 'spoonbill-browser-auth-client-inventory-v1');
  } finally { fs.mkdirSync = original; syncBuiltinESMExports(); }
});

test('malformed provenance, absolute provenance paths and invalid bytecode are rejected', () => {
  for (const type of ['hash', 'path', 'extra', 'bytecode', 'jar']) {
    const f = fixture();
    if (type === 'hash') {
      const report = JSON.parse(fs.readFileSync(f.sourceInventoryFile)); report.sourceSha256 = '0'.repeat(64);
      fs.writeFileSync(f.sourceInventoryFile, JSON.stringify(report));
    }
    if (type === 'path') {
      const report = JSON.parse(fs.readFileSync(f.clientInventoryFile)); report.privatePath = '/Users/private';
      fs.writeFileSync(f.clientInventoryFile, JSON.stringify(report));
    }
    if (type === 'extra') {
      const report = JSON.parse(fs.readFileSync(f.clientInventoryFile)); report.credential = 'opaque-private-data';
      fs.writeFileSync(f.clientInventoryFile, JSON.stringify(report));
    }
    if (type === 'bytecode') fs.writeFileSync(path.join(f.first, 'Main.class'), 'source not compiled');
    if (type === 'jar') fs.writeFileSync(f.library, 'not a ZIP archive');
    incomplete(f);
    if (type === 'extra') assert.equal(fs.existsSync(path.join(f.destination, 'provenance/client-inventory.json')), false);
  }
});

test('verification rejects modified, unindexed and symlinked artifact contents', () => {
  for (const type of ['changed', 'unindexed', 'symlink', 'parent-symlink', 'marker']) {
    const f = fixture(); captureArtifact(f);
    const target = path.join(f.destination, 'classpath/0000/choice.txt');
    if (type === 'changed') fs.writeFileSync(target, 'other');
    if (type === 'unindexed') fs.writeFileSync(path.join(f.destination, 'extra'), 'x');
    if (type === 'symlink') { fs.unlinkSync(target); fs.symlinkSync(path.join(f.first, 'choice.txt'), target); }
    if (type === 'parent-symlink') {
      fs.rmSync(path.join(f.destination, 'classpath/0000'), {recursive: true}); fs.symlinkSync(f.first, path.join(f.destination, 'classpath/0000'));
    }
    if (type === 'marker') fs.writeFileSync(path.join(f.destination, 'capture-state.json'), '{"status":"incomplete"}');
    assert.throws(() => loadArtifact(f.destination));
  }
});

test('manifest traversal is refused even if the unsigned content hash is recomputed', () => {
  const f = fixture(), manifest = captureArtifact(f);
  manifest.index[0].path = '../outside';
  const {artifactSha256: _, ...payload} = manifest;
  manifest.artifactSha256 = sha(JSON.stringify(payload));
  fs.writeFileSync(path.join(f.destination, 'artifact.json'), JSON.stringify(manifest));
  fs.writeFileSync(path.join(f.destination, 'capture-state.json'), JSON.stringify({schema: manifest.schema, status: 'complete', artifactSha256: manifest.artifactSha256}));
  assert.throws(() => loadArtifact(f.destination), /invalid-artifact-index/);
});

test('bounded JAR parser rejects truncated archives and accepts the actual JDK-created helper', () => {
  const bytes = fs.readFileSync(helperJar);
  assert.equal(checkJar(bytes).classes, 1);
  for (const length of [0, 12, bytes.length - 1]) assert.throws(() => checkJar(bytes.subarray(0, length)));
  assert.throws(() => checkJar(bytes, 1));
});

test('module import does not interpret a caller argument as its own executable filename', () => {
  const url = new URL('./artifact.mjs', import.meta.url).href;
  assert.equal(run(process.execPath, ['--input-type=module', '-e', `await import(${JSON.stringify(url)}); console.log("imported");`, 'not-a-file']), 'imported\n');
});

test('classpath delimiter in a relocated directory is rejected instead of returning an unusable launch', () => {
  const f = fixture(); captureArtifact(f);
  const relocated = path.join(f.directory, 'has:colon'); fs.renameSync(f.destination, relocated);
  assert.throws(() => launchCommand(relocated, 'Main'), /invalid-launch-directory/);
});

test('deterministic GNU tar preserves empty directories and case-distinct runtime paths after extraction', async t => {
  const f = fixture();
  fs.mkdirSync(path.join(f.first, 'empty-directory'));
  fs.writeFileSync(path.join(f.first, 'Case.txt'), 'upper', {flag: 'wx'});
  // Case-insensitive host filesystems cannot physically represent this case.
  let caseDistinct = true;
  try { fs.writeFileSync(path.join(f.first, 'case.txt'), 'lower', {flag: 'wx'}); }
  catch (error) { if (error.code !== 'EEXIST') throw error; caseDistinct = false; }
  const longDirectory = path.join(f.first, 'long'.repeat(30)); fs.mkdirSync(longDirectory);
  fs.writeFileSync(path.join(longDirectory, 'resource.txt'), 'long name');
  const manifest = captureArtifact(f), destination = path.join(f.directory, 'runtime.tar');
  const first = await archiveArtifact({directory: f.destination, destination});
  fs.utimesSync(path.join(f.destination, 'classpath/0000/Main.class'), 100, 200);
  fs.chmodSync(path.join(f.destination, 'classpath/0000/Main.class'), 0o700);
  const second = await archiveArtifact({directory: f.destination, destination: path.join(f.directory, 'runtime-second.tar')});
  assert.deepEqual(first, second);
  assert.equal(first.artifactSha256, manifest.artifactSha256);
  assert.notEqual(first.tarSha256, first.artifactSha256);
  assert.equal(first.tarSha256, sha(fs.readFileSync(destination)));
  assert.deepEqual(verifyArchive({archive: destination, directory: f.destination}), first);
  const extracted = path.join(root, `extracted-${sequence++}`); fs.mkdirSync(extracted);
  run(managed('tar'), ['--extract', '--file', destination, '--directory', extracted, '--no-same-owner']);
  fs.rmSync(f.directory, {recursive: true, force: true});
  assert.equal(loadArtifact(extracted).manifest.artifactSha256, manifest.artifactSha256);
  assert.equal(fs.statSync(path.join(extracted, 'classpath/0000/empty-directory')).isDirectory(), true);
  assert.equal(fs.readFileSync(path.join(extracted, 'classpath/0000/Case.txt'), 'utf8'), 'upper');
  if (caseDistinct) assert.equal(fs.readFileSync(path.join(extracted, 'classpath/0000/case.txt'), 'utf8'), 'lower');
  else {
    assert.equal(process.platform, 'darwin', 'Case-distinct path coverage is required on the Linux CI filesystem');
    t.diagnostic('This Darwin filesystem is case-insensitive; simultaneous case-distinct path coverage remains required on Linux CI.');
  }
  assert.equal(run(java, launchCommand(extracted, 'Main', ['archive']).args), 'first/jar/archive');
});

test('archive creation never overwrites and bounded failure retains incomplete bytes and marker', async () => {
  const f = fixture(); captureArtifact(f);
  const destination = path.join(f.directory, 'runtime.tar');
  const complete = await archiveArtifact({directory: f.destination, destination});
  await assert.rejects(archiveArtifact({directory: f.destination, destination}), /archive-destination-exists/);
  assert.equal(sha(fs.readFileSync(destination)), complete.tarSha256);
  await assert.rejects(archiveArtifact({directory: f.destination, destination: path.join(f.destination, 'inside.tar')}), /destination-inside-input/);
  const partial = path.join(f.directory, 'partial.tar');
  await assert.rejects(archiveArtifact({directory: f.destination, destination: partial, maxArchiveBytes: 512}), /archive-incomplete/);
  assert.equal(fs.existsSync(partial), true);
  assert.ok(fs.statSync(partial).size <= 512);
  assert.equal(JSON.parse(fs.readFileSync(partial + '.state.json')).status, 'incomplete');
  assert.throws(() => verifyArchive({archive: partial, directory: f.destination}));
});

test('archive verification rejects changed data, truncation and bytes after its terminal blocks', async () => {
  const f = fixture(); captureArtifact(f);
  const destination = path.join(f.directory, 'runtime.tar');
  await archiveArtifact({directory: f.destination, destination});
  const bytes = fs.readFileSync(destination);
  for (const [name, corrupted] of [['changed', Buffer.from(bytes)], ['truncated', bytes.subarray(0, 100)], ['appended', Buffer.concat([bytes, Buffer.from('unindexed')])]]) {
    if (name === 'changed') corrupted[512] ^= 1;
    const candidate = path.join(f.directory, `${name}.tar`); fs.writeFileSync(candidate, corrupted);
    assert.throws(() => verifyArchive({archive: candidate, directory: f.destination}));
  }
});

test('source capture mutation while GNU tar runs prevents archive publication', async () => {
  const f = fixture(); captureArtifact(f);
  const destination = path.join(f.directory, 'mutating.tar'), original = childProcess.spawn;
  let mutated = false;
  childProcess.spawn = function(command, args, options) {
    const child = original(command, args, options);
    mutated = true;
    fs.writeFileSync(path.join(f.destination, 'classpath/0000/choice.txt'), 'other');
    return child;
  };
  syncBuiltinESMExports();
  try {
    await assert.rejects(archiveArtifact({directory: f.destination, destination}), /archive-incomplete/);
    assert.equal(mutated, true);
    assert.equal(fs.existsSync(destination), true);
    assert.equal(JSON.parse(fs.readFileSync(destination + '.state.json')).status, 'incomplete');
  } finally { childProcess.spawn = original; syncBuiltinESMExports(); }
});
