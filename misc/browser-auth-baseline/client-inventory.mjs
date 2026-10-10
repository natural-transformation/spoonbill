import {createHash} from 'node:crypto';
import {readFileSync, readdirSync, statSync, writeFileSync} from 'node:fs';
import {gzipSync, brotliCompressSync, constants} from 'node:zlib';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {inventory} from './inventory.mjs';

export function bundleInventory(bytes) {
  return {sha256: createHash('sha256').update(bytes).digest('hex'),
    emittedBytes: bytes.length, gzipBytes: gzipSync(bytes, {level: 9}).length,
    brotliBytes: brotliCompressSync(bytes, {params: {[constants.BROTLI_PARAM_QUALITY]: 11}}).length};
}

export function clientInventory(sourceDirectory, bundlePath, applicationDirectories) {
  const sources = inventory({handwrittenClient: sourceDirectory});
  const applications = inventory(applicationDirectories);
  const scripts = applications.files.filter(file => /\.(m?js|html)$/.test(file.file));
  const embeddedScriptCandidates = [];
  for (const [category, directory] of Object.entries(applicationDirectories)) {
    function visit(relative) {
      const location = path.join(directory, relative);
      if (statSync(location).isDirectory()) {
        for (const name of readdirSync(location).sort()) visit(path.join(relative, name));
      } else if (relative.endsWith('.scala')) {
        readFileSync(location, 'utf8').split(/\r?\n/).forEach((line, index) => {
          if (/\bevalJs\s*\(|<script\b|\bscript\s*\(/.test(line))
            embeddedScriptCandidates.push({category, file: relative, line: index + 1});
        });
      }
    }
    visit('');
  }
  return {schema: 'spoonbill-browser-auth-client-inventory-v1', sources,
    emittedClient: bundleInventory(readFileSync(bundlePath)),
    compression: {node: process.version, gzipLevel: 9, brotliQuality: 11},
    applicationScripts: scripts,
    embeddedScriptCandidates,
    scope: 'Full framework bundle; no invented authentication-only byte attribution. Script scan requires source review; zero matches alone is not proof of a complete application.'};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [bundle, destination] = process.argv.slice(2);
  if (!bundle) throw new Error('Usage: node client-inventory.mjs BUILT_CLIENT [NEW_REPORT.json]');
  const root = path.dirname(fileURLToPath(import.meta.url));
  const report = clientInventory(path.resolve(root, '../../modules/spoonbill/src/main/es6'), bundle,
    {memory: path.join(root, 'memory'), jdbc: path.join(root, 'jdbc'), application: path.join(root, 'app')});
  const result = JSON.stringify(report, null, 2) + '\n';
  if (destination) writeFileSync(destination, result, {flag: 'wx'});
  else process.stdout.write(result);
}
