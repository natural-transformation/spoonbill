import assert from 'node:assert/strict';
import {readFileSync, writeFileSync, mkdirSync, cpSync, statSync} from 'node:fs';
import {join} from 'node:path';
import {createHash} from 'node:crypto';

const [baselineClasspath, candidateClasspath, baselineRoot, candidateRoot, output] = process.argv.slice(2);
mkdirSync(output, {recursive: true});
const sources = ['ChunkedBodyDecoder.scala', 'Http11.scala'];
const provenance = {baselineCommit: '1f06cd3a', description: 'Exact source snapshots and copied class directories', variants: {}};
for (const [name, classpath, root] of [['baseline', baselineClasspath, baselineRoot], ['model', candidateClasspath, candidateRoot]]) {
  const entries = readFileSync(classpath, 'utf8').trim().split(':');
  assert.ok(entries.length > 1);
  const frozen = entries.map((entry, index) => {
    const directory = statSync(entry).isDirectory();
    if (!directory && !entry.includes('/target/')) return entry;
    const target = join(output, `${name}-classes`, String(index) + (directory ? '' : '.jar'));
    cpSync(entry, target, {recursive: true});
    return target;
  });
  writeFileSync(join(output, `${name}.classpath`), frozen.join(':') + '\n');
  const hashes = {};
  for (const source of sources) {
    const path = `modules/http/src/main/scala/korolev/http/protocol/${source}`;
    let data;
    try { data = readFileSync(join(root, path)); }
    catch (error) { if (error.code === 'ENOENT' && name === 'baseline') continue; throw error; }
    hashes[path] = createHash('sha256').update(data).digest('hex');
    mkdirSync(join(output, `${name}-source`), {recursive: true});
    writeFileSync(join(output, `${name}-source`, source), data);
  }
  provenance.variants[name] = hashes;
}
for (const name of ['ChunkedBodyBenchmark.scala', 'run-chunked.sh', 'analyze-cost.mjs']) {
  const data = readFileSync(join(candidateRoot, 'misc/transport-state-machines', name));
  writeFileSync(join(output, name), data);
  provenance[name] = createHash('sha256').update(data).digest('hex');
}
writeFileSync(join(output, 'provenance.json'), JSON.stringify(provenance, null, 2) + '\n');
console.log(JSON.stringify({frozen: output}));
