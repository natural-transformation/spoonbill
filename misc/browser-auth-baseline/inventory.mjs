import {createHash} from 'node:crypto';
import {readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

export const baselineRevision = 'ab95a4fe65399814931c6ddd7f29dd68f33bbc5c';
const root = path.dirname(fileURLToPath(import.meta.url));
const digest = value => createHash('sha256').update(value).digest('hex');

// Count every source file in the supplied integration roots, including helpers
// and infrastructure. Tests/benchmarks are kept outside these roots, rather
// than filtered by name (which could hide production integration code).
export function inventory(sourceRoots) {
  const files = [];
  for (const [category, directory] of Object.entries(sourceRoots)) {
    function visit(relative) {
      const location = path.join(directory, relative);
      if (statSync(location).isDirectory()) {
        for (const name of readdirSync(location).sort()) visit(path.join(relative, name));
      } else if (/\.(scala|java|sql|mjs|js|html|css)$/.test(relative)) {
        const source = readFileSync(location, 'utf8');
        files.push({category, file: relative.split(path.sep).join('/'), sha256: digest(source),
          bytes: Buffer.byteLength(source),
          nonblankLines: source.split(/\r?\n/).filter(line => line.trim().length > 0).length});
      }
    }
    visit('');
  }
  files.sort((a, b) => `${a.category}/${a.file}`.localeCompare(`${b.category}/${b.file}`, 'en'));
  return {schema: 'spoonbill-browser-auth-inventory-v1', baselineRevision,
    method: 'All nonblank physical lines, including comments, in all declared production integration roots',
    sourceSha256: digest(JSON.stringify(files)), files,
    totals: {files: files.length, bytes: files.reduce((n, file) => n + file.bytes, 0),
      nonblankLines: files.reduce((n, file) => n + file.nonblankLines, 0)}};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [destination] = process.argv.slice(2);
  const report = inventory({memory: path.join(root, 'memory'), jdbc: path.join(root, 'jdbc'),
    application: path.join(root, 'app')});
  if (!report.files.some(file => file.category === 'memory') || !report.files.some(file => file.category === 'jdbc'))
    throw new Error('Both complete reference integration source roots are required');
  // This counts available reference source; correctness/review/coverage are
  // separate exit gates. It deliberately does not claim Phase 0 completion.
  const result = JSON.stringify(report, null, 2) + '\n';
  if (destination) writeFileSync(destination, result, {flag: 'wx'});
  else process.stdout.write(result);
}
