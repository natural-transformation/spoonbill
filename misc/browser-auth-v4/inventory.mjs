import {readFileSync, mkdirSync, writeFileSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import path from 'node:path';
import {inventory} from '../browser-auth-baseline/inventory.mjs';
import {clientInventory} from '../browser-auth-baseline/client-inventory.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const [bundle] = process.argv.slice(2);
const output = path.join(root, 'misc/browser-auth-v4/reports');
mkdirSync(output, {recursive: true});
const baseline = JSON.parse(readFileSync(path.join(root, 'misc/browser-auth-baseline/reports/v3-source-inventory-final.json'), 'utf8'));
const candidate = inventory({application: path.join(root, 'examples/browser-auth/src/main/scala')});
const difference = (before, after) => Object.fromEntries(Object.keys(before).map(key => [key, after[key] - before[key]]));
const sources = {
  schema: 'spoonbill-browser-auth-v4-source-comparison-v1',
  baseline: {report: 'misc/browser-auth-baseline/reports/v3-source-inventory-final.json',
    sourceSha256: baseline.sourceSha256, totals: baseline.totals},
  candidate,
  delta: difference(baseline.totals, candidate.totals),
  scope: 'All v3 common/memory/JDBC/application production helpers versus all public v4 example Scala sources, including Main and domain policy. Tests, docs and library implementation are separate. Counts alone do not establish equivalent behavior or performance.'
};
writeFileSync(path.join(output, 'source-comparison.json'), JSON.stringify(sources, null, 2) + '\n');
console.log(JSON.stringify({sourceBaseline: baseline.totals, sourceCandidate: candidate.totals, sourceDelta: sources.delta}));
if (bundle) {
  const before = JSON.parse(readFileSync(path.join(root, 'misc/browser-auth-baseline/reports/v3-client-inventory-final.json'), 'utf8'));
  const after = clientInventory(path.join(root, 'modules/spoonbill/src/main/es6'), path.resolve(bundle),
    {application: path.join(root, 'examples/browser-auth/src/main/scala')});
  const comparison = {
    schema: 'spoonbill-browser-auth-v4-client-comparison-v1',
    baseline: {report: 'misc/browser-auth-baseline/reports/v3-client-inventory-final.json',
      sourceSha256: before.sources.sourceSha256, sourceTotals: before.sources.totals,
      emittedClient: before.emittedClient},
    candidate: after,
    emittedByteDelta: after.emittedClient.emittedBytes - before.emittedClient.emittedBytes,
    gzipByteDelta: after.emittedClient.gzipBytes - before.emittedClient.gzipBytes,
    brotliByteDelta: after.emittedClient.brotliBytes - before.emittedClient.brotliBytes,
    scope: 'Full emitted bundle, including generated protocol. Run after a successful resource build of these sources. Exact byte counts are not latency, allocation, or retention measurements.'
  };
  writeFileSync(path.join(output, 'client-comparison.json'), JSON.stringify(comparison, null, 2) + '\n');
  console.log(JSON.stringify({clientSource: after.sourceKinds, clientBytes: after.emittedClient,
    emittedByteDelta: comparison.emittedByteDelta, gzipByteDelta: comparison.gzipByteDelta,
    brotliByteDelta: comparison.brotliByteDelta}));
}
