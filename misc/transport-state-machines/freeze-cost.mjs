import assert from 'node:assert/strict';
import {readFileSync, writeFileSync, mkdirSync, cpSync, statSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';

const [baselineLog, candidateLog, baselineRoot, candidateRoot, output] = process.argv.slice(2);
mkdirSync(output, {recursive: true});
const sources = [
  'modules/spoonbill/src/main/scala/korolev/internal/Frontend.scala',
  'modules/spoonbill/src/main/scala/korolev/internal/FrontendConnectionMachine.scala',
  'modules/spoonbill/src/main/scala/korolev/internal/FrontendCloseMachine.scala',
  'modules/spoonbill/src/main/scala/korolev/internal/FrontendPendingRegistry.scala',
  'modules/spoonbill/src/main/scala/korolev/internal/PendingRegistryMachine.scala',
  'modules/spoonbill/src/main/scala/korolev/internal/SensitiveRuntime.scala',
  'modules/spoonbill/src/main/scala/korolev/internal/SensitiveRuntimeMachine.scala',
  'modules/http/src/main/scala/korolev/http/protocol/ChunkedBodyDecoder.scala',
  'modules/http/src/main/scala/korolev/http/protocol/Http11.scala',
];
const provenance = {baselineCommit: '1f06cd3a', candidateParent: '1f06cd3a',
  description: 'Source snapshots plus copied class directories; the working candidate is not yet committed.', variants: {}};
for (const [name, log, root] of [['baseline', baselineLog, baselineRoot], ['model', candidateLog, candidateRoot]]) {
  const text = readFileSync(log, 'utf8');
  assert.ok(!/^\[error\]/m.test(text));
  const paths = text.split(/\r?\n/).filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
  assert.equal(paths.length, 2);
  const hashes = {};
  for (const path of sources) {
    let data;
    try { data = readFileSync(join(root, path)); }
    catch (error) { if (error.code === 'ENOENT' && name === 'baseline') continue; throw error; }
    hashes[path] = createHash('sha256').update(data).digest('hex');
    const target = join(output, `${name}-source`, path);
    mkdirSync(target.slice(0, target.lastIndexOf('/')), {recursive: true});
    writeFileSync(target, data);
  }
  const copied = new Map();
  for (const [index, path] of paths.entries()) {
    const cp = path.split(':').map(entry => {
      if (!statSync(entry).isDirectory()) return entry;
      if (!copied.has(entry)) {
        const target = join(output, `${name}-classes`, String(copied.size));
        cpSync(entry, target, {recursive: true});
        copied.set(entry, target);
      }
      return copied.get(entry);
    }).join(':');
    writeFileSync(join(output, `${name}-${index === 0 ? 'frontend' : 'chunked'}.classpath`), cp + '\n');
  }
  provenance.variants[name] = hashes;
}
for (const name of ['FrontendFrameBenchmark.scala', 'ChunkedBodyBenchmark.scala', 'run-frontend.sh', 'run-chunked.sh', 'analyze-cost.mjs']) {
  const data = readFileSync(join(candidateRoot, 'misc/transport-state-machines', name));
  writeFileSync(join(output, name), data);
  provenance[name] = createHash('sha256').update(data).digest('hex');
}
writeFileSync(join(output, 'provenance.json'), JSON.stringify(provenance, null, 2) + '\n');
console.log(JSON.stringify({frozen: output, variants: Object.keys(provenance.variants)}));
