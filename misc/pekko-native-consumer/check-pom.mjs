import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';

const [file] = process.argv.slice(2);
if (!file) throw new Error('Usage: node check-pom.mjs GENERATED_POM');
const pom = readFileSync(file, 'utf8');
const dependencies = [...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(([, xml]) => ({
  group: /<groupId>([^<]+)<\/groupId>/.exec(xml)?.[1],
  artifact: /<artifactId>([^<]+)<\/artifactId>/.exec(xml)?.[1],
  version: /<version>([^<]+)<\/version>/.exec(xml)?.[1],
}));
const pekko = dependencies.filter(dependency => dependency.group === 'org.apache.pekko');
assert(pekko.length > 0, 'Published adapter must declare Pekko dependencies');
assert(pekko.every(dependency => dependency.artifact.endsWith('_3')), 'Published adapter must not depend on Scala 2.13 Pekko binaries');
for (const artifact of ['pekko-actor_3', 'pekko-stream_3', 'pekko-http_3'])
  assert(pekko.some(dependency => dependency.artifact === artifact), `Missing published dependency ${artifact}`);
console.log(JSON.stringify({status: 'pass', dependencies: pekko}));
