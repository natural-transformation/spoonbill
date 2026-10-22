#!/usr/bin/env node
// Run through the repo flake. No third-party Node packages or host tools.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { inflateRawSync } from 'node:zlib';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const options = Object.fromEntries(Array.from({ length: (process.argv.length - 2) / 2 }, (_, i) =>
  [process.argv[2 + i * 2], process.argv[3 + i * 2]]));
for (const name of ['--jar', '--pom', '--sources', '--classpath-file'])
  assert(options[name], `Usage: node misc/check-zio-http-runtime.mjs --jar JAR --pom POM --sources SOURCES_JAR --classpath-file FILE (missing ${name})`);
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const tracked = path.join(root, 'interop/zio-http-runtime');

function zip(file) {
  const bytes = fs.readFileSync(file);
  let eocd = bytes.length - 22;
  while (eocd >= Math.max(0, bytes.length - 65557) && bytes.readUInt32LE(eocd) !== 0x06054b50) eocd--;
  assert(eocd >= Math.max(0, bytes.length - 65557), `No ZIP directory in ${file}`);
  assert.equal(bytes.readUInt16LE(eocd + 4), 0, 'Multi-disk ZIP is unsupported');
  const count = bytes.readUInt16LE(eocd + 10);
  assert.notEqual(count, 65535, 'ZIP64 needs an explicitly reviewed verifier');
  let offset = bytes.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let i = 0; i < count; i++) {
    assert.equal(bytes.readUInt32LE(offset), 0x02014b50, 'Invalid ZIP directory');
    const method = bytes.readUInt16LE(offset + 10);
    const compressed = bytes.readUInt32LE(offset + 20);
    const size = bytes.readUInt32LE(offset + 24);
    const nameLength = bytes.readUInt16LE(offset + 28);
    const extraLength = bytes.readUInt16LE(offset + 30);
    const commentLength = bytes.readUInt16LE(offset + 32);
    const local = bytes.readUInt32LE(offset + 42);
    const name = bytes.subarray(offset + 46, offset + 46 + nameLength).toString('utf8');
    assert(!entries.has(name), `Duplicate ZIP entry ${name} in ${file}`);
    entries.set(name, () => {
      assert.equal(bytes.readUInt32LE(local), 0x04034b50, 'Invalid local ZIP header');
      const start = local + 30 + bytes.readUInt16LE(local + 26) + bytes.readUInt16LE(local + 28);
      const payload = bytes.subarray(start, start + compressed);
      const decoded = method === 0 ? payload : method === 8 ? inflateRawSync(payload) : assert.fail(`ZIP method ${method}`);
      assert.equal(decoded.length, size, `Truncated ${name}`);
      return decoded;
    });
    offset += 46 + nameLength + extraLength + commentLength;
  }
  return entries;
}
function entry(archive, name) {
  assert(archive.has(name), `Missing ${name}`);
  return archive.get(name)();
}
function dependencies(xml) {
  return [...xml.matchAll(/<dependency>\s*([\s\S]*?)<\/dependency>/g)].map(([, body]) => {
    const field = name => body.match(new RegExp(`<${name}>([^<]*)<\\/${name}>`))?.[1].trim() ?? '';
    return { group: field('groupId'), artifact: field('artifactId'), version: field('version'),
      classifier: field('classifier'), scope: field('scope') || 'compile' };
  });
}
const runtime = zip(options['--jar']);
const sources = zip(options['--sources']);
const handler = 'zio/http/netty/WebSocketAppHandler.class';
assert.deepEqual([...runtime.keys()].filter(name => name.startsWith('zio/http/netty/WebSocketAppHandler')).sort(),
  [handler, 'zio/http/netty/WebSocketAppHandler.tasty']);
assert(entry(runtime, handler).includes(Buffer.from('spoonbillBoundedIngressVersion')), 'Unpatched handler bytecode');
assert(![...runtime.keys()].some(name => /^META-INF\/.*\.(SF|RSA|DSA)$/.test(name)), 'Stale upstream JAR signature');

const manifest = JSON.parse(entry(runtime, 'META-INF/spoonbill-zio-http-patch.json'));
const expected = {
  upstreamVersion: '3.11.6',
  binarySha256: 'e5ac833344472a68a017c7d2dec4ce00cc5251bcdea871cb9a9eb7853abe4dc0',
  sourcesSha256: '149a17e15f1591f8f7e6d576ca5a109859206e9dec60efdaaf36e32dba749e5d',
  pomSha256: '37ddab087ed7b3bec4777d782e64ce3a083360befc1e5e2661944bdc97c7380c',
  originalHandlerSha256: 'c5b3d608ee58a6dcb355f811e762b1ee5b7f1ac9586728b109329857602cae0e',
};
for (const [key, value] of Object.entries(expected)) assert.equal(manifest[key], value, key);
const overrides = { 'dev.zio:zio_3': '2.1.27', 'dev.zio:zio-streams_3': '2.1.27' };
assert.deepEqual(manifest.dependencyOverrides, overrides, 'Only reviewed core ZIO overrides are allowed');
assert.deepEqual(Object.keys(manifest.patchSources).sort(), [
  'zio/http/netty/SpoonbillWebSocketRuntime.scala',
  'zio/http/netty/WebSocketAppHandler.scala',
  'zio/http/netty/WebSocketFrameDeadline.scala',
  'zio/http/netty/WebSocketUpgradeBoundary.scala',
]);
for (const [name, checksum] of Object.entries(manifest.patchSources)) {
  assert.equal(sha(fs.readFileSync(path.join(tracked, 'src/main/scala', name))), checksum, `Tracked patch ${name}`);
  assert.equal(sha(entry(sources, name)), checksum, `Published patch ${name}`);
}
assert(sources.size > 100, 'Source artifact omits upstream sources');
const upstreamPom = entry(runtime, 'META-INF/zio-http-upstream.pom');
assert.equal(sha(upstreamPom), expected.pomSha256);
assert.equal(sha(fs.readFileSync(path.join(tracked, 'upstream.pom'))), expected.pomSha256);
for (const name of ['LICENSE-zio-http', 'NOTICE-zio-http']) {
  assert.deepEqual(entry(runtime, `META-INF/${name}`), fs.readFileSync(path.join(tracked, 'src/main/resources/META-INF', name)));
}
const notice = entry(runtime, 'META-INF/NOTICE-zio-http').toString('utf8');
for (const value of [expected.upstreamVersion, expected.binarySha256, expected.sourcesSha256, 'ZIO HTTP contributors'])
  assert(notice.includes(value), `Missing attribution/pin ${value}`);
const published = dependencies(fs.readFileSync(options['--pom'], 'utf8'));
assert(!published.some(dep => dep.group === 'dev.zio' && /^zio-http(?:_|$)/.test(dep.artifact)), 'POM reintroduces upstream runtime');
for (const dep of dependencies(upstreamPom.toString('utf8')).filter(dep => dep.scope !== 'test' && dep.artifact !== 'scala3-library_3')) {
  const effective = { ...dep, version: overrides[`${dep.group}:${dep.artifact}`] ?? dep.version };
  assert(published.some(other => JSON.stringify(other) === JSON.stringify(effective)), `Lost upstream dependency ${JSON.stringify(effective)}`);
}

const classpath = fs.readFileSync(options['--classpath-file'], 'utf8').trim().split(path.delimiter).filter(Boolean);
assert(classpath.length > 0);
for (const [module, version] of Object.entries(overrides)) {
  const artifact = module.split(':')[1];
  const selected = classpath.map(name => path.basename(name))
    .filter(name => name.startsWith(`${artifact}-`) && name.endsWith('.jar'));
  assert.deepEqual(selected, [`${artifact}-${version}.jar`], `Unexpected resolved ${module} runtime`);
}
const providers = [];
for (const name of classpath) {
  assert(fs.existsSync(name), `Missing classpath entry ${name}`);
  if (fs.statSync(name).isDirectory()) {
    if (fs.existsSync(path.join(name, handler))) providers.push(fs.realpathSync(name));
  } else if (name.endsWith('.jar') && zip(name).has(handler)) providers.push(fs.realpathSync(name));
}
assert.deepEqual(providers, [fs.realpathSync(options['--jar'])], 'Runtime classpath must have exactly one derived handler provider');
console.log('PASS: patched classes, SHA manifest, full sources, license/attribution, dependency POM and runtime classpath');
