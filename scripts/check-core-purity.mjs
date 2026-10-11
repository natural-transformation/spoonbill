import {readFileSync, readdirSync} from 'node:fs';
import {dirname, join, relative, resolve} from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

// A focused forbidden-API guard, complemented by CoreBoundarySpec's compiler
// classpath checks and source review. This does not claim to prove purity of
// arbitrary Scala programs or external helper implementations.
const forbidden = [
  ['runtime dependency', /\bspoonbill\.(?:effect|server|state|sensitive|snapshot|http|pekko)\b/],
  ['runtime security implementation', /\bspoonbill\.security\.(?:jdbc|store|transaction)\b/],
  ['I/O or concurrency infrastructure', /\b(?:java\.(?:io|nio\.file|sql|util\.concurrent)|javax\.(?:sql|crypto)|scala\.concurrent|org\.apache\.pekko)\b/],
  ['mutable collection', /\b(?:scala\.collection\.(?:mutable|concurrent)|TrieMap|ArrayBuffer|ListBuffer)\b/],
  ['ambient process state', /\b(?:System\s*\.|(?:scala\.)?sys\s*\.|Thread\s*\.)/],
  ['ambient clock or entropy', /\b(?:Instant|LocalDate|LocalDateTime|ZonedDateTime|OffsetDateTime)\s*\.\s*now\b|\bUUID\s*\.\s*randomUUID\b|\b(?:SecureRandom|Random)\b/],
  ['mutable lifecycle', /\b(?:var|synchronized|AtomicBoolean|AtomicReference|AtomicInteger|ThreadLocal)\b/],
  ['effect execution', /\b(?:Await|Future|Promise|ExecutionContext|println|printStackTrace|unsafeRun)\b/],
];

export function violations(source) {
  // Remove comments only. Keeping strings makes forbidden names in reflection
  // calls visible too. A false positive must be reviewed rather than suppressed.
  const code = source.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, '');
  return code.split('\n').flatMap((line, index) => forbidden
    .filter(([, pattern]) => pattern.test(line)).map(([rule]) => ({line: index + 1, rule})));
}

export function checkCore(root) {
  const failures = [];
  let files = 0;
  function visit(directory) {
    for (const entry of readdirSync(directory, {withFileTypes: true})) {
      const path = join(directory, entry.name);
      if (entry.isSymbolicLink()) failures.push({file: relative(root, path), rule: 'symlink bypasses source boundary'});
      else if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && entry.name.endsWith('.scala')) {
        files++;
        failures.push(...violations(readFileSync(path, 'utf8')).map(value => ({file: relative(root, path), ...value})));
      }
    }
  }
  visit(join(root, 'modules/core/src/main/scala'));
  if (files === 0) failures.push({rule: 'no core sources inspected'});
  return {files, failures};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const result = checkCore(root);
  console.log(JSON.stringify(result, null, 2));
  if (result.failures.length) process.exitCode = 1;
}
