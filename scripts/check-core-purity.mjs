import {existsSync, readFileSync, readdirSync} from 'node:fs';
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
  ['effect type', /\b(?:Effect|IO|ZIO|UIO|URIO|RIO|Task)\b/],
];

const javascriptForbidden = [
  ['mutable binding', /\b(?:let|var)\b/],
  ['browser or process environment', /\b(?:window|document|navigator|location|globalThis|process|console)\s*[.[]/],
  ['ambient clock or entropy', /\b(?:Date|performance)\s*\.\s*(?:now|timeOrigin)\b|\bMath\s*\.\s*random\b|\bcrypto\b/],
  ['transport or timer effect', /\b(?:fetch|WebSocket|XMLHttpRequest|setTimeout|clearTimeout|setInterval|clearInterval|queueMicrotask)\b/],
  ['asynchronous effect', /\b(?:async|await|Promise)\b/],
  ['mutable collection', /\bnew\s+(?:Map|Set|WeakMap|WeakSet)\b|\.\s*(?:push|pop|shift|unshift|splice|sort|reverse|set|add|delete|clear)\s*\(/],
  ['dynamic code execution', /\b(?:eval|Function)\s*\(/],
  ['input mutation', /\b(?:state|event)\s*(?:\.\s*\w+|\[[^\]]+\])\s*(?:[+*/%-]?=(?!=)|\+\+|--)/],
];

export function violations(source, language = 'scala') {
  // Package declarations name a namespace, not a dependency. Keep imports,
  // qualified uses and strings (including reflection names) visible.
  const code = source.replace(/\/\*[\s\S]*?\*\//g, match => match.replace(/[^\n]/g, ' '))
    .replace(/\/\/[^\n]*/g, '');
  const rules = language === 'javascript' ? [...forbidden, ...javascriptForbidden] : forbidden;
  return code.split('\n').flatMap((line, index) => /^\s*package\s+[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\s*;?\s*$/.test(line) ? [] : rules
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

/** Machines stay beside their interpreters, including adapters and browser modules. */
export function checkMachines(root) {
  const failures = [];
  let files = 0;
  function visit(directory) {
    for (const entry of readdirSync(directory, {withFileTypes: true})) {
      const path = join(directory, entry.name);
      // Compiled/generated output is not a main source tree.
      if (entry.name === 'target' || entry.name.startsWith('.')) continue;
      if (entry.isSymbolicLink()) failures.push({file: relative(root, path), rule: 'symlink bypasses source boundary'});
      else if (entry.isDirectory()) visit(path);
      else if (entry.isFile() && (entry.name.endsWith('Machine.scala') || /(?:Machine|[-.]machine)\.(?:js|mjs)$/.test(entry.name)) && /[/\\]src[/\\]main[/\\]/.test(path)) {
        files++;
        const language = entry.name.endsWith('.scala') ? 'scala' : 'javascript';
        failures.push(...violations(readFileSync(path, 'utf8'), language).map(value => ({file: relative(root, path), ...value})));
      }
    }
  }
  for (const area of ['modules', 'interop']) {
    const directory = join(root, area);
    if (existsSync(directory)) visit(directory);
  }
  if (files === 0) failures.push({rule: 'no machine sources inspected'});
  return {files, failures};
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
  const core = checkCore(root);
  const machines = checkMachines(root);
  const result = {coreFiles: core.files, machineFiles: machines.files, failures: [...core.failures, ...machines.failures]};
  console.log(JSON.stringify(result, null, 2));
  if (result.failures.length) process.exitCode = 1;
}
