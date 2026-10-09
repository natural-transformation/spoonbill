import {readFileSync, appendFileSync, writeFileSync, readdirSync, statSync} from 'node:fs';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import path from 'node:path';

// Run inside nix develop. Each observation owns a fresh, equally configured JVM.
const [baselinePath, candidatePath, destination, blocksArg = '30', cellsArg = '1:128,1:4096,8:128,8:4096', mode = 'comparison', suite = 'transport'] = process.argv.slice(2);
if (!baselinePath || !candidatePath || !destination || !['comparison', 'calibration'].includes(mode))
  throw new Error('Usage: node run.mjs baseline.classpath candidate.classpath output.jsonl [blocks] [connections:bytes,...] [comparison|calibration] [transport|core]');
if (!['transport', 'core'].includes(suite)) throw new Error('Unknown benchmark suite');
const blocks = Number(blocksArg);
if (!Number.isSafeInteger(blocks) || blocks < 1) throw new Error('blocks must be a positive integer');
const classpaths = [baselinePath, candidatePath].map(path => readFileSync(path, 'utf8').trim());
if (classpaths.some(cp => !cp || cp.includes('\n'))) throw new Error('Expected one classpath line per file');
const cells = suite === 'core' ? [[1, 0]] : cellsArg.split(',').map(cell => cell.split(':').map(Number));
if (cells.some(([connections, bytes]) => !Number.isInteger(connections) || connections < 1 || !Number.isInteger(bytes) || (suite === 'transport' && bytes < 1)))
  throw new Error('Invalid workload cell');
const options = ['-Xms512m', '-Xmx512m'];
function artifactHash(classpath) {
  const hash = createHash('sha256');
  function visit(file, relative) {
    if (statSync(file).isDirectory()) {
      for (const name of readdirSync(file).sort()) visit(path.join(file, name), `${relative}/${name}`);
    } else {
      hash.update(relative).update('\0').update(readFileSync(file)).update('\0');
    }
  }
  classpath.split(path.delimiter).forEach((entry, index) => visit(entry, String(index)));
  return hash.digest('hex');
}
const artifacts = classpaths.map(artifactHash);
const sourceFile = suite === 'core' ? 'CoreGuardedSessionBenchmark.scala' : 'StandaloneTransportBenchmark.scala';
writeFileSync(destination, JSON.stringify({kind: 'metadata', mode, suite, blocks, cells, jvmOptions: options,
  artifactSha256: artifacts, fixtureSha256: createHash('sha256').update(readFileSync(new URL(sourceFile, import.meta.url))).digest('hex'),
  flakeLockSha256: createHash('sha256').update(readFileSync('flake.lock')).digest('hex'),
  runtime: {node: process.version, platform: process.platform, arch: process.arch}}) + '\n');
for (const [connections, bytes] of cells) {
  // Seeded balanced ordering avoids aligning either variant with periodic drift.
  const first = Array.from({length: blocks}, (_, index) => index % 2);
  let seed = (20261009 + connections * 65537 + bytes) >>> 0;
  for (let i = first.length - 1; i > 0; i--) {
    seed ^= seed << 13; seed ^= seed >>> 17; seed ^= seed << 5;
    const j = (seed >>> 0) % (i + 1);
    [first[i], first[j]] = [first[j], first[i]];
  }
  for (let block = 0; block < blocks; block++) {
    const order = [first[block], 1 - first[block]];
    for (const [orderInBlock, variant] of order.entries()) {
      const selected = mode === 'calibration' ? 0 : variant;
      const label = variant ? 'candidate' : 'baseline';
      const invocation = suite === 'core'
        ? ['spoonbill.performance.CoreGuardedSessionBenchmark', label, artifacts[selected], String(block), '2000', '2000']
        : ['spoonbill.performance.StandaloneTransportBenchmark', label, artifacts[selected], String(block), String(connections), String(bytes), '5000', '1000'];
      const result = spawnSync('java', [...options, '-cp', classpaths[selected], ...invocation], {encoding: 'utf8', timeout: 180000});
      if (result.error || result.status !== 0) {
        appendFileSync(destination, JSON.stringify({kind: 'failure', block, connections, bytes, label,
          status: result.status, error: String(result.error ?? ''), stdout: result.stdout, stderr: result.stderr}) + '\n');
        throw new Error(`Benchmark failed for ${label} block ${block}: ${result.stderr ?? result.error}`);
      }
      const record = result.stdout.split(/\r?\n/).filter(line => line.startsWith('{')).map(line => JSON.parse(line))
        .find(row => row.schema === (suite === 'core' ? 'spoonbill-core-guarded-session-v1' : 'spoonbill-websocket-transport-v1'));
      if (!record) throw new Error('Benchmark emitted no result');
      appendFileSync(destination, JSON.stringify({kind: 'sample', orderInBlock, ...record}) + '\n');
    }
    console.log(`${mode}: ${connections} connections, ${bytes} bytes, block ${block + 1}/${blocks}`);
  }
}
appendFileSync(destination, JSON.stringify({kind: 'completion', observations: blocks * cells.length * 2}) + '\n');
