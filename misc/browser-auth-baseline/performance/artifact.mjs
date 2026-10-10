import {createHash} from 'node:crypto';
import {constants, openSync, closeSync, readSync, writeSync, fstatSync, lstatSync, realpathSync,
  opendirSync, mkdirSync, writeFileSync, renameSync} from 'node:fs';
import {inflateRawSync} from 'node:zlib';
import {spawn, spawnSync} from 'node:child_process';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const schema = 'spoonbill-runtime-artifact-v1';
const self = fileURLToPath(import.meta.url);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const json = value => JSON.stringify(value);
const sha = value => digest(json(value));
const defaults = Object.freeze({entries: 50000, bytes: 1024 * 1024 * 1024,
  fileBytes: 128 * 1024 * 1024, metadataBytes: 8 * 1024 * 1024, classpathEntries: 512});
const fail = code => { throw new Error(code); };
const inside = (parent, child) => child === parent || child.startsWith(parent + path.sep);
const safeRelative = name => typeof name === 'string' && name.length > 0 &&
  !name.includes('\\') && !/[\x00-\x1f\x7f:]/.test(name) && !path.posix.isAbsolute(name) &&
  name.split('/').every(part => part !== '' && part !== '.' && part !== '..');
const stamp = s => ['dev', 'ino', 'mode', 'size', 'mtimeNs', 'ctimeNs'].map(k => String(s[k])).join(':');
const same = (a, b) => json(a) === json(b);
const hashPattern = /^[a-f0-9]{64}$/;
const byPath = (a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0;
const exactKeys = (value, keys) => value && typeof value === 'object' && !Array.isArray(value) && same(Object.keys(value).sort(), [...keys].sort());
const inventoryMethod = 'All nonblank physical lines, including comments, in all declared production integration roots';
const clientScope = 'Full framework bundle; no invented authentication-only byte attribution. Script scan requires source review; zero matches alone is not proof of a complete application.';
const policyKeys = ['sessionSeconds', 'ceremonySeconds', 'challengeSeconds', 'deliverySeconds', 'reconnectSeconds',
  'bootstrapSeconds', 'operationAuthoritySeconds', 'retainedEntries', 'retainedViews', 'auditRecords', 'liveCeremonies',
  'ceremoniesPerBinding', 'activeViews', 'viewsPerBinding', 'disconnectedViews', 'bootstrapEntries', 'pendingJobs',
  'nodesPerView', 'accountCount', 'deliveryMaterials', 'rateScopes', 'proofAttempts', 'proofWindowSeconds', 'proof'];

function readBounded(location, maxBytes) {
  const before = entry(location);
  if (!before.isFile() || before.size > BigInt(maxBytes)) fail('invalid-file-size-or-type');
  const fd = openSync(location, constants.O_RDONLY | constants.O_NOFOLLOW);
  try {
    if (stamp(before) !== stamp(fstatSync(fd, {bigint: true}))) fail('source-mutated');
    const buffer = Buffer.alloc(Number(before.size) + 1);
    let size = 0, n;
    do { n = readSync(fd, buffer, size, buffer.length - size, null); size += n; } while (n && size < buffer.length);
    if (size !== Number(before.size) || stamp(before) !== stamp(fstatSync(fd, {bigint: true})) ||
        stamp(before) !== stamp(entry(location))) fail('source-mutated');
    return buffer.subarray(0, size);
  } finally { closeSync(fd); }
}

function limits(input = {}) {
  if (Object.keys(input).some(key => !(key in defaults))) fail('unknown-limit');
  const value = {...defaults, ...input};
  if (Object.values(value).some(n => !Number.isSafeInteger(n) || n <= 0)) fail('invalid-limit');
  return value;
}
function entry(location) {
  const s = lstatSync(location, {bigint: true});
  if (s.isSymbolicLink() || (!s.isFile() && !s.isDirectory())) fail('unsupported-file-type');
  return s;
}
function canonical(location) {
  if (typeof location !== 'string' || !path.isAbsolute(location) || /[\x00\r\n]/.test(location))
    fail('absolute-input-required');
  entry(location); // A symlink at an input root is not an accepted source.
  return realpathSync(location);
}
function budget(lim) { return {entries: 0, bytes: 0, lim}; }
function count(b, size = 0) {
  b.entries++;
  b.bytes += size;
  if (b.entries > b.lim.entries || b.bytes > b.lim.bytes || size > b.lim.fileBytes)
    fail('capture-limit-exceeded');
}
function names(location, maximum) {
  const directory = opendirSync(location), result = [];
  try {
    for (let item; (item = directory.readSync());) {
      if (result.length >= maximum) fail('capture-limit-exceeded');
      result.push(item.name);
    }
  } finally { directory.closeSync(); }
  return result.sort();
}

// No whole-file read during copying: growing files cannot bypass the bound.
function file(location, destination, b, maxBytes = b.lim.fileBytes) {
  const before = entry(location);
  if (!before.isFile() || before.size > BigInt(maxBytes)) fail('invalid-file-size-or-type');
  count(b, Number(before.size));
  const fd = openSync(location, constants.O_RDONLY | constants.O_NOFOLLOW);
  let out;
  try {
    if (stamp(before) !== stamp(fstatSync(fd, {bigint: true}))) fail('source-mutated');
    if (destination) out = openSync(destination, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, 0o600);
    const h = createHash('sha256'), buffer = Buffer.alloc(64 * 1024);
    let size = 0;
    for (;;) {
      const n = readSync(fd, buffer, 0, buffer.length, null);
      if (!n) break;
      size += n;
      if (size > maxBytes || size > Number(before.size)) fail('source-mutated-or-limit');
      h.update(buffer.subarray(0, n));
      if (out !== undefined) {
        let offset = 0;
        while (offset < n) offset += writeSync(out, buffer, offset, n - offset);
      }
    }
    if (size !== Number(before.size) || stamp(before) !== stamp(fstatSync(fd, {bigint: true})) ||
        stamp(before) !== stamp(entry(location))) fail('source-mutated');
    return {bytes: size, sha256: h.digest('hex'), identity: stamp(before)};
  } finally { closeSync(fd); if (out !== undefined) closeSync(out); }
}
function tree(root, destination, prefix, b) {
  const records = [], identities = [];
  function visit(location, output, relative) {
    const before = entry(location);
    if (before.isDirectory()) {
      count(b);
      if (output) mkdirSync(output, {mode: 0o700});
      const children = names(location, b.lim.entries);
      records.push({path: relative, type: 'directory'});
      identities.push([relative, stamp(before)]);
      for (const name of children) {
        if (!safeRelative(name) || name.includes('/') || name.startsWith('.')) fail('unsafe-runtime-path');
        visit(path.join(location, name), output && path.join(output, name), relative + '/' + name);
      }
      if (stamp(before) !== stamp(entry(location)) || !same(children, names(location, b.lim.entries))) fail('source-mutated');
    } else {
      const {identity, ...record} = file(location, output, b);
      records.push({path: relative, type: 'file', ...record});
      identities.push([relative, identity]);
    }
  }
  visit(root, destination, prefix);
  records.sort(byPath);
  return {records, identities};
}

// JAR Class-Path can resolve dependencies outside -cp. Inspect the ZIP central
// directory without extracting archive paths, and refuse that implicit graph.
export function checkJar(bytes, maxEntries = defaults.entries) {
  const invalid = () => fail('unsupported-or-malformed-jar');
  let end = -1;
  for (let i = bytes.length - 22; i >= Math.max(0, bytes.length - 65557); i--) {
    if (bytes.readUInt32LE(i) === 0x06054b50 && i + 22 + bytes.readUInt16LE(i + 20) === bytes.length) { end = i; break; }
  }
  if (end < 0) invalid();
  const entries = bytes.readUInt16LE(end + 10), length = bytes.readUInt32LE(end + 12), start = bytes.readUInt32LE(end + 16);
  if (bytes.readUInt16LE(end + 4) || bytes.readUInt16LE(end + 6) || entries === 65535 ||
      entries !== bytes.readUInt16LE(end + 8) || entries > maxEntries || start + length !== end) invalid();
  let cursor = start, manifest = null, classes = 0;
  const names = new Set();
  for (let n = 0; n < entries; n++) {
    if (cursor + 46 > end || bytes.readUInt32LE(cursor) !== 0x02014b50) invalid();
    const flags = bytes.readUInt16LE(cursor + 8), method = bytes.readUInt16LE(cursor + 10);
    const packed = bytes.readUInt32LE(cursor + 20), unpacked = bytes.readUInt32LE(cursor + 24);
    const nameLength = bytes.readUInt16LE(cursor + 28), extra = bytes.readUInt16LE(cursor + 30), comment = bytes.readUInt16LE(cursor + 32);
    const local = bytes.readUInt32LE(cursor + 42);
    const next = cursor + 46 + nameLength + extra + comment;
    if (next > end || (flags & 1) || (method !== 0 && method !== 8) ||
        packed === 0xffffffff || unpacked === 0xffffffff || local + 30 > start) invalid();
    const name = bytes.subarray(cursor + 46, cursor + 46 + nameLength).toString('utf8');
    if (!safeRelative(name.endsWith('/') ? name.slice(0, -1) : name) || names.has(name)) invalid();
    names.add(name);
    if (bytes.readUInt32LE(local) !== 0x04034b50 || bytes.readUInt16LE(local + 8) !== method ||
        bytes.readUInt16LE(local + 6) !== flags) invalid();
    const localNameLength = bytes.readUInt16LE(local + 26), localExtra = bytes.readUInt16LE(local + 28);
    const data = local + 30 + localNameLength + localExtra;
    if (data + packed > start || bytes.subarray(local + 30, local + 30 + localNameLength).toString('utf8') !== name) invalid();
    if (name.endsWith('.class')) classes++;
    if (name.toUpperCase() === 'META-INF/MANIFEST.MF') {
      if (manifest !== null || unpacked > 1024 * 1024) invalid();
      const content = bytes.subarray(data, data + packed);
      manifest = (method === 0 ? content : inflateRawSync(content, {maxOutputLength: 1024 * 1024})).toString('utf8');
      if (Buffer.byteLength(manifest) !== unpacked || /\0/.test(manifest)) invalid();
      if (/^class-path:/im.test(manifest.replace(/\r?\n /g, ''))) fail('implicit-jar-classpath');
    }
    cursor = next;
  }
  if (cursor !== end) invalid();
  return {entries, classes};
}

function parseClasspath(text, lim) {
  const line = text.replace(/\r?\n$/, '');
  if (!line || /[\r\n\0]/.test(line)) fail('malformed-classpath');
  const entries = line.split(path.delimiter);
  if (entries.length > lim.classpathEntries || entries.some(p => !path.isAbsolute(p) || p.includes('*')))
    fail('malformed-classpath');
  return entries;
}
function validateInventory(report) {
  if (!exactKeys(report, ['schema', 'baselineRevision', 'method', 'sourceSha256', 'files', 'totals']) ||
      report.schema !== 'spoonbill-browser-auth-inventory-v1' || !/^[a-f0-9]{40}$/.test(report.baselineRevision) ||
      report.method !== inventoryMethod || !Array.isArray(report.files) ||
      !report.files.length || !hashPattern.test(report.sourceSha256) || sha(report.files) !== report.sourceSha256)
    fail('invalid-source-inventory');
  const names = new Set();
  for (const f of report.files) {
    if (!validInventoryFile(f) || names.has(`${f.category}/${f.file}`)) fail('invalid-source-inventory');
    names.add(`${f.category}/${f.file}`);
  }
  if (!exactKeys(report.totals, ['files', 'bytes', 'nonblankLines']) ||
      report.totals.files !== report.files.length || report.totals.bytes !== report.files.reduce((n, f) => n + f.bytes, 0) ||
      report.totals.nonblankLines !== report.files.reduce((n, f) => n + f.nonblankLines, 0)) fail('invalid-source-inventory');
}
function validInventoryFile(f) {
  return exactKeys(f, ['category', 'file', 'sha256', 'bytes', 'nonblankLines']) &&
    /^[a-zA-Z][a-zA-Z0-9-]*$/.test(f.category) && safeRelative(f.file) && hashPattern.test(f.sha256) &&
    Number.isSafeInteger(f.bytes) && f.bytes >= 0 && Number.isSafeInteger(f.nonblankLines) && f.nonblankLines >= 0;
}
function provenance(source, policy, client) {
  function rejectPaths(value) {
    if (typeof value === 'string' && (path.isAbsolute(value) || /^[A-Za-z]:[\\/]|^file:\/\//.test(value))) fail('private-path-in-provenance');
    if (value && typeof value === 'object') for (const item of Object.values(value)) rejectPaths(item);
  }
  for (const report of [source, policy, client]) rejectPaths(report);
  validateInventory(source);
  if (!policy || !same(Object.keys(policy).sort(), ['representative', 'short'])) fail('invalid-reference-policy');
  for (const p of Object.values(policy)) {
    if (!exactKeys(p, policyKeys) || !p.proof ||
        Object.entries(p).some(([k, v]) => k !== 'proof' && (!Number.isSafeInteger(v) || v <= 0)) ||
        !same(Object.keys(p.proof).sort(), ['algorithm', 'iterations', 'keyBits', 'saltBytes']) ||
        !/^[A-Za-z0-9_-]+$/.test(p.proof.algorithm) ||
        ['iterations', 'keyBits', 'saltBytes'].some(k => !Number.isSafeInteger(p.proof[k]) || p.proof[k] <= 0))
      fail('invalid-reference-policy');
  }
  if (!exactKeys(client, ['schema', 'sources', 'emittedClient', 'compression', 'applicationScripts', 'embeddedScriptCandidates', 'scope']) ||
      client.schema !== 'spoonbill-browser-auth-client-inventory-v1' || client.scope !== clientScope ||
      !exactKeys(client.emittedClient, ['sha256', 'emittedBytes', 'gzipBytes', 'brotliBytes']) ||
      !hashPattern.test(client.emittedClient.sha256) ||
      ['emittedBytes', 'gzipBytes', 'brotliBytes'].some(k => !Number.isSafeInteger(client.emittedClient[k]) || client.emittedClient[k] <= 0) ||
      !exactKeys(client.compression, ['node', 'gzipLevel', 'brotliQuality']) ||
      !/^v\d+\.\d+\.\d+$/.test(client.compression.node) || client.compression.gzipLevel !== 9 || client.compression.brotliQuality !== 11 ||
      !Array.isArray(client.applicationScripts) || !client.applicationScripts.every(validInventoryFile) ||
      !Array.isArray(client.embeddedScriptCandidates) || client.embeddedScriptCandidates.some(f =>
        !exactKeys(f, ['category', 'file', 'line']) || !/^[a-zA-Z][a-zA-Z0-9-]*$/.test(f.category) ||
        !safeRelative(f.file) || !Number.isSafeInteger(f.line) || f.line <= 0))
    fail('invalid-client-inventory');
  validateInventory(client.sources);
}

export function captureArtifact({classpathFile, destination, sourceInventoryFile, referencePolicyFile, clientInventoryFile, limits: requestedLimits}) {
  const lim = limits(requestedLimits);
  if (typeof destination !== 'string' || !path.isAbsolute(destination) || /[\x00\r\n:]/.test(destination)) fail('invalid-destination');
  const output = path.join(realpathSync(path.dirname(destination)), path.basename(destination));
  // Resolve before claiming output so an output nested in an input cannot alter
  // that input. A malformed classpath still gets a failed, explicitly incomplete capture.
  const supplied = [classpathFile, sourceInventoryFile, referencePolicyFile, clientInventoryFile];
  const inputs = [], roots = [], rawRoots = [];
  let preflight, parseFailure;
  try {
    for (const input of supplied) inputs.push(canonical(input));
    preflight = file(inputs[0], null, budget(lim), lim.metadataBytes);
    rawRoots.push(...parseClasspath(readBounded(inputs[0], lim.metadataBytes).toString('utf8'), lim));
    for (const root of rawRoots) roots.push(canonical(root));
  } catch (error) { parseFailure = error; }
  if ([...supplied, ...inputs, ...roots, ...rawRoots].some(p => typeof p === 'string' && path.isAbsolute(p) && inside(path.resolve(p), output)))
    fail('destination-inside-input');
  try { mkdirSync(output, {mode: 0o700}); }
  catch (error) { fail(error.code === 'EEXIST' ? 'destination-exists' : 'destination-unavailable'); }
  const state = path.join(output, 'capture-state.json');
  writeFileSync(state, json({schema, status: 'incomplete', code: 'capture-in-progress'}) + '\n', {flag: 'wx'});
  let phase = 'input';
  try {
    if (parseFailure) throw parseFailure;
    const b = budget(lim), index = [], observations = [], classpath = [];
    const recordFile = (location, relative, maxBytes) => {
      const result = file(location, path.join(output, relative), b, maxBytes);
      const {identity, ...content} = result;
      index.push({path: relative, type: 'file', ...content});
      observations.push({location, relative, identity, ...content});
      return content.sha256;
    };
    const cpObservation = file(inputs[0], null, budget(lim), lim.metadataBytes);
    if (!same(preflight, cpObservation)) fail('source-mutated');
    mkdirSync(path.join(output, 'classpath'));
    phase = 'classpath';
    let compiledClasses = 0;
    for (const [i, root] of roots.entries()) {
      const directory = entry(root).isDirectory();
      if (directory ? path.basename(root) !== 'classes' : !root.endsWith('.jar')) fail('unsupported-classpath-entry');
      const relative = `classpath/${String(i).padStart(4, '0')}${directory ? '' : '.jar'}`;
      const scanned = tree(root, path.join(output, relative), relative, b);
      index.push(...scanned.records);
      observations.push({location: root, relative, tree: scanned});
      if (!directory) compiledClasses += checkJar(readBounded(path.join(output, relative), lim.fileBytes), lim.entries).classes;
      else for (const record of scanned.records.filter(r => r.path.endsWith('.class'))) {
        const fd = openSync(path.join(output, record.path), constants.O_RDONLY | constants.O_NOFOLLOW);
        try { const header = Buffer.alloc(4); if (readSync(fd, header, 0, 4, 0) !== 4 || header.readUInt32BE() !== 0xcafebabe) fail('invalid-class-file'); }
        finally { closeSync(fd); }
        compiledClasses++;
      }
      classpath.push({path: relative, kind: directory ? 'directory' : 'jar', sha256: sha(scanned.records.map(r => ({...r, path: r.path.slice(relative.length)})))});
    }
    if (!compiledClasses) fail('no-compiled-classes');
    phase = 'provenance';
    // Validate before persisting reports; unknown metadata must not turn this
    // narrowly scoped capture into an arbitrary document copier.
    const reportInputs = inputs.slice(1).map(location => {
      const identity = stamp(entry(location)), bytes = readBounded(location, lim.metadataBytes);
      if (identity !== stamp(entry(location))) fail('source-mutated');
      return {location, identity, bytes};
    });
    const reports = reportInputs.map(input => JSON.parse(input.bytes));
    provenance(...reports);
    mkdirSync(path.join(output, 'provenance'));
    const reportHashes = ['source-inventory', 'reference-policy', 'client-inventory'].map((name, i) => {
      const {location, identity, bytes} = reportInputs[i], relative = `provenance/${name}.json`;
      count(b, bytes.length);
      const content = {bytes: bytes.length, sha256: digest(bytes)};
      // Persist only the validated bounded buffer. A racing replacement of its
      // source may fail the recheck, but can never copy unvalidated private data.
      writeFileSync(path.join(output, relative), bytes, {flag: 'wx', mode: 0o600});
      index.push({path: relative, type: 'file', ...content});
      observations.push({location, relative, identity, ...content});
      return content.sha256;
    });
    const [sourceInventorySha256, referencePolicySha256, clientInventorySha256] = reportHashes;
    recordFile(self, 'launcher.mjs', lim.metadataBytes);
    phase = 'recheck';
    // Re-hash all sources after copying, including directory membership. Identity
    // checks detect equal-size replacements and content restored after a write.
    if (!same(preflight, file(inputs[0], null, budget(lim), lim.metadataBytes))) fail('source-mutated');
    const recheckBudget = budget(lim);
    for (const observation of observations) {
      if (observation.tree) {
        if (!same(observation.tree, tree(observation.location, null, observation.relative, recheckBudget))) fail('source-mutated');
      } else {
        const result = file(observation.location, null, recheckBudget, lim.metadataBytes);
        if (!same(result, {bytes: observation.bytes, sha256: observation.sha256, identity: observation.identity})) fail('source-mutated');
      }
    }
    index.sort(byPath);
    const payload = {schema, classpath, dependencyGraphSha256: sha(classpath), sourceInventorySha256,
      referenceIntegrationSha256: reports[0].sourceSha256, referencePolicySha256, clientInventorySha256, index};
    const manifest = {...payload, artifactSha256: sha(payload)};
    const manifestBytes = json(manifest) + '\n';
    if (Buffer.byteLength(manifestBytes) > lim.metadataBytes) fail('capture-limit-exceeded');
    writeFileSync(path.join(output, 'artifact.json'), manifestBytes, {flag: 'wx'});
    // Commit marker is replaced atomically only after all files and rechecks.
    const next = path.join(output, 'capture-complete.tmp');
    writeFileSync(next, json({schema, status: 'complete', artifactSha256: manifest.artifactSha256}) + '\n', {flag: 'wx'});
    renameSync(next, state);
    return manifest;
  } catch (error) {
    // Never serialize OS error messages: they can contain private source paths.
    writeFileSync(state, json({schema, status: 'incomplete', phase, code: /^[a-z-]+$/.test(error.message) ? error.message : 'capture-failed'}) + '\n');
    throw new Error(`artifact-incomplete:${phase}`, {cause: error});
  }
}

export function loadArtifact(directory, requestedLimits) {
  const lim = limits(requestedLimits), root = canonical(path.resolve(directory));
  if (root.includes(path.delimiter)) fail('invalid-launch-directory');
  const readJson = name => {
    file(path.join(root, name), null, budget(lim), lim.metadataBytes);
    return JSON.parse(readBounded(path.join(root, name), lim.metadataBytes));
  };
  const state = readJson('capture-state.json'), manifest = readJson('artifact.json');
  const {artifactSha256, ...payload} = manifest;
  if (state.status !== 'complete' || state.schema !== schema || manifest.schema !== schema ||
      !hashPattern.test(artifactSha256) || state.artifactSha256 !== artifactSha256 || sha(payload) !== artifactSha256 ||
      !Array.isArray(manifest.index) || !Array.isArray(manifest.classpath) || !manifest.classpath.length ||
      manifest.classpath.length > lim.classpathEntries || sha(manifest.classpath) !== manifest.dependencyGraphSha256)
    fail('invalid-artifact-manifest');
  const indexed = new Map(), b = budget(lim);
  for (const item of manifest.index) {
    if (!safeRelative(item.path) || indexed.has(item.path)) fail('invalid-artifact-index');
    indexed.set(item.path, item);
    const location = path.join(root, item.path);
    // Walk every ancestor: O_NOFOLLOW alone only guards the last component.
    let current = root;
    for (const part of item.path.split('/')) { current = path.join(current, part); entry(current); }
    if (item.type === 'directory') { if (!entry(location).isDirectory()) fail('artifact-mismatch'); count(b); }
    else if (item.type === 'file') {
      const {identity: _, ...content} = file(location, null, b);
      if (!same(content, {bytes: item.bytes, sha256: item.sha256})) fail('artifact-mismatch');
    } else fail('invalid-artifact-index');
  }
  const expected = new Set(['artifact.json', 'capture-state.json', 'classpath', 'provenance', ...indexed.keys()]);
  function walk(relative = '') {
    for (const name of names(path.join(root, relative), lim.entries)) {
      const p = relative ? relative + '/' + name : name;
      if (!expected.has(p)) fail('unindexed-artifact-file');
      if (entry(path.join(root, p)).isDirectory()) walk(p);
    }
  }
  walk();
  for (const [i, cp] of manifest.classpath.entries()) {
    const expectedPath = `classpath/${String(i).padStart(4, '0')}${cp.kind === 'jar' ? '.jar' : ''}`;
    if (cp.path !== expectedPath || !['jar', 'directory'].includes(cp.kind) ||
        indexed.get(cp.path)?.type !== (cp.kind === 'jar' ? 'file' : 'directory')) fail('invalid-classpath-index');
    const records = manifest.index.filter(r => r.path === cp.path || r.path.startsWith(cp.path + '/'));
    if (sha(records.map(r => ({...r, path: r.path.slice(cp.path.length)}))) !== cp.sha256) fail('artifact-mismatch');
    if (cp.kind === 'jar') checkJar(readBounded(path.join(root, cp.path), lim.fileBytes), lim.entries);
  }
  for (const [name, key] of [['source-inventory', 'sourceInventorySha256'], ['reference-policy', 'referencePolicySha256'], ['client-inventory', 'clientInventorySha256']])
    if (indexed.get(`provenance/${name}.json`)?.sha256 !== manifest[key]) fail('artifact-mismatch');
  return {manifest, classpath: manifest.classpath.map(cp => path.join(root, cp.path)).join(path.delimiter)};
}

export function launchCommand(directory, mainClass, args = []) {
  if (typeof mainClass !== 'string' || !/^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*$/.test(mainClass) ||
      !Array.isArray(args) || args.some(a => typeof a !== 'string' || a.includes('\0'))) fail('invalid-launch-arguments');
  return {command: 'java', args: ['-cp', loadArtifact(directory).classpath, mainClass, ...args]};
}

const archiveSchema = 'spoonbill-runtime-archive-v1';
const archiveByteLimit = defaults.bytes + defaults.entries * 2048 + 2 * defaults.metadataBytes;
function tarTool() {
  for (const directory of (process.env.PATH ?? '').split(path.delimiter)) {
    if (!directory.startsWith('/nix/store/')) continue;
    try {
      const executable = realpathSync(path.join(directory, 'tar'));
      if (!executable.startsWith('/nix/store/')) continue;
      const result = spawnSync(executable, ['--version'], {encoding: 'utf8', timeout: 5000, maxBuffer: 16384});
      if (result.status === 0 && result.stdout.startsWith('tar (GNU tar)')) return executable;
    } catch (_) { /* Only a Nix-managed GNU tar is supported. */ }
  }
  fail('nix-gnu-tar-required');
}
function archiveContents(directory) {
  const root = canonical(path.resolve(directory)), {manifest} = loadArtifact(root);
  const records = [...manifest.index, {path: 'classpath', type: 'directory'}, {path: 'provenance', type: 'directory'}];
  for (const name of ['artifact.json', 'capture-state.json']) {
    const {identity: _, ...content} = file(path.join(root, name), null, budget(defaults), defaults.metadataBytes);
    records.push({path: name, type: 'file', ...content});
  }
  return {root, manifest, records: records.sort(byPath)};
}

// Verify only; never extract. Accept the simple GNU tar format emitted below,
// including bounded GNU long-name records. Reject links, duplicates and extras.
export function verifyArchive({archive, directory, maxArchiveBytes = archiveByteLimit}) {
  if (!Number.isSafeInteger(maxArchiveBytes) || maxArchiveBytes <= 0) fail('invalid-archive-limit');
  const {manifest, records} = archiveContents(directory);
  const expected = new Map(records.map(record => [record.path, record]));
  const before = entry(archive);
  if (!before.isFile() || before.size > BigInt(maxArchiveBytes)) fail('invalid-archive-size');
  const fd = openSync(archive, constants.O_RDONLY | constants.O_NOFOLLOW), archiveHash = createHash('sha256');
  let position = 0, longName = null, ended = false;
  function read(length) {
    if (position + length > Number(before.size)) fail('truncated-archive');
    const bytes = Buffer.alloc(length);
    let offset = 0;
    while (offset < length) {
      const n = readSync(fd, bytes, offset, length - offset, null);
      if (!n) fail('truncated-archive');
      offset += n;
    }
    position += length; archiveHash.update(bytes);
    return bytes;
  }
  const string = buffer => buffer.toString('utf8').replace(/\0.*$/s, '');
  function octal(buffer) {
    const value = string(buffer).trim();
    if (!/^[0-7]+$/.test(value)) fail('unsupported-archive-number');
    const number = Number.parseInt(value, 8);
    if (!Number.isSafeInteger(number)) fail('unsupported-archive-number');
    return number;
  }
  try {
    if (stamp(before) !== stamp(fstatSync(fd, {bigint: true}))) fail('archive-mutated');
    while (position < Number(before.size)) {
      const header = read(512);
      if (header.every(value => value === 0)) {
        if (longName || !read(512).every(value => value === 0)) fail('malformed-archive-end');
        while (position < Number(before.size)) if (!read(Math.min(65536, Number(before.size) - position)).every(value => value === 0)) fail('trailing-archive-data');
        ended = true; break;
      }
      const checksum = [...header].reduce((sum, value, i) => sum + (i >= 148 && i < 156 ? 32 : value), 0);
      if (octal(header.subarray(148, 156)) !== checksum || header.subarray(257, 263).toString() !== 'ustar ')
        fail('malformed-archive-header');
      const size = octal(header.subarray(124, 136)), type = String.fromCharCode(header[156]);
      if (size > maxArchiveBytes || octal(header.subarray(108, 116)) !== 0 || octal(header.subarray(116, 124)) !== 0 ||
          octal(header.subarray(136, 148)) !== 0) fail('noncanonical-archive-metadata');
      let name = string(header.subarray(0, 100));
      if (type === 'L') {
        if (longName || name !== '././@LongLink' || size < 2 || size > 65536) fail('invalid-archive-long-name');
        const bytes = read(size);
        if (bytes.at(-1) !== 0 || bytes.subarray(0, -1).includes(0)) fail('invalid-archive-long-name');
        longName = bytes.subarray(0, -1).toString('utf8');
      } else {
        name = longName ?? name; longName = null;
        if (type === '5' && name.endsWith('/')) name = name.slice(0, -1);
        if (!safeRelative(name) || !expected.has(name) || !['0', '\0', '5'].includes(type) ||
            octal(header.subarray(100, 108)) !== 0o755) fail('unexpected-archive-member');
        const record = expected.get(name); expected.delete(name);
        if (type === '5') {
          if (record.type !== 'directory' || size !== 0) fail('archive-content-mismatch');
        } else {
          if (record.type !== 'file' || size !== record.bytes) fail('archive-content-mismatch');
          const contentHash = createHash('sha256');
          let remaining = size;
          while (remaining) { const bytes = read(Math.min(65536, remaining)); contentHash.update(bytes); remaining -= bytes.length; }
          if (contentHash.digest('hex') !== record.sha256) fail('archive-content-mismatch');
        }
      }
      const padding = (512 - size % 512) % 512;
      if (padding && !read(padding).every(value => value === 0)) fail('noncanonical-archive-padding');
    }
    if (!ended || expected.size || stamp(before) !== stamp(fstatSync(fd, {bigint: true})) || stamp(before) !== stamp(entry(archive)))
      fail('incomplete-or-mutated-archive');
    return {schema: archiveSchema, artifactSha256: manifest.artifactSha256,
      tarSha256: archiveHash.digest('hex'), tarBytes: position};
  } finally { closeSync(fd); }
}

export async function archiveArtifact({directory, destination, timeoutMs = 60000, maxArchiveBytes = archiveByteLimit}) {
  if (!Number.isSafeInteger(timeoutMs) || timeoutMs <= 0 || timeoutMs > 300000 ||
      !Number.isSafeInteger(maxArchiveBytes) || maxArchiveBytes <= 0) fail('invalid-archive-limit');
  const tar = tarTool(), initial = archiveContents(directory);
  if (typeof destination !== 'string' || !path.isAbsolute(destination) || !destination.endsWith('.tar')) fail('invalid-archive-destination');
  const output = path.join(realpathSync(path.dirname(destination)), path.basename(destination));
  if (inside(initial.root, output)) fail('destination-inside-input');
  const stateFile = output + '.state.json';
  for (const candidate of [output, stateFile]) {
    try { lstatSync(candidate); fail('archive-destination-exists'); }
    catch (error) { if (error.code !== 'ENOENT') throw error; }
  }
  let fd;
  let stateOwned = false;
  try {
    writeFileSync(stateFile, json({schema: archiveSchema, status: 'incomplete', code: 'archive-in-progress'}) + '\n', {flag: 'wx', mode: 0o600});
    stateOwned = true;
    fd = openSync(output, constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL, 0o600);
    const env = {...process.env, LC_ALL: 'C', TZ: 'UTC'}; delete env.TAR_OPTIONS;
    await new Promise((resolve, reject) => {
      const child = spawn(tar, ['--create', '--file=-', '--format=gnu', '--mtime=@0', '--owner=0', '--group=0',
        '--numeric-owner', '--mode=0755', '--sort=name', '--hard-dereference', '--no-recursion',
        '--directory', initial.root, '--null', '--verbatim-files-from', '--files-from=-'],
      {env, stdio: ['pipe', 'pipe', 'pipe']});
      let total = 0, error = null;
      const stop = code => { error ??= new Error(code); child.kill('SIGKILL'); };
      const timer = setTimeout(() => stop('archive-timeout'), timeoutMs);
      child.on('error', () => { error ??= new Error('archive-process-failed'); });
      child.stdout.on('data', bytes => {
        if (error) return;
        total += bytes.length;
        if (total > maxArchiveBytes) { stop('archive-byte-limit'); return; }
        try { let offset = 0; while (offset < bytes.length) offset += writeSync(fd, bytes, offset, bytes.length - offset); }
        catch (_) { stop('archive-write-failed'); }
      });
      child.stderr.on('data', () => {}); // Drain without persisting source paths.
      child.stdin.on('error', () => stop('archive-input-failed'));
      child.on('close', code => { clearTimeout(timer); if (error || code !== 0) reject(error ?? new Error('archive-process-failed')); else resolve(); });
      child.stdin.end(Buffer.from(initial.records.map(record => record.path).join('\0') + '\0'));
    });
  } catch (error) {
    if (stateOwned) writeFileSync(stateFile, json({schema: archiveSchema, status: 'incomplete', code: /^[a-z-]+$/.test(error.message) ? error.message : 'archive-failed'}) + '\n');
    throw new Error('archive-incomplete', {cause: error});
  } finally { if (fd !== undefined) closeSync(fd); }
  try {
    const checked = verifyArchive({archive: output, directory: initial.root, maxArchiveBytes});
    if (checked.artifactSha256 !== initial.manifest.artifactSha256) fail('source-mutated');
    const next = stateFile + '.tmp';
    writeFileSync(next, json({...checked, status: 'complete'}) + '\n', {flag: 'wx', mode: 0o600});
    renameSync(next, stateFile);
    return checked;
  } catch (error) {
    writeFileSync(stateFile, json({schema: archiveSchema, status: 'incomplete', code: 'archive-verification-failed'}) + '\n');
    throw new Error('archive-incomplete', {cause: error});
  }
}

function isMain() {
  try { return Boolean(process.argv[1]) && realpathSync(process.argv[1]) === self; }
  catch (_) { return false; }
}
if (isMain()) {
  const [mode, ...args] = process.argv.slice(2);
  try {
    if (mode === 'capture' && args.length === 5) {
      const [classpathFile, destination, sourceInventoryFile, referencePolicyFile, clientInventoryFile] = args.map(p => path.resolve(p));
      console.log(json(captureArtifact({classpathFile, destination, sourceInventoryFile, referencePolicyFile, clientInventoryFile})));
    } else if (mode === 'archive' && args.length === 2) console.log(json(await archiveArtifact({directory: path.resolve(args[0]), destination: path.resolve(args[1])})));
    else if (mode === 'verify' && args.length === 1) console.log(json(loadArtifact(args[0]).manifest));
    else if (mode === 'command' && args.length >= 2) {
      const command = launchCommand(args[0], args[1], args.slice(2));
      console.log(json(command));
    } else fail('usage: capture CP_FILE NEW_DIR SOURCE_JSON POLICY_JSON CLIENT_JSON | verify DIR | command DIR MAIN [args]');
  } catch (error) {
    console.error(/^[a-z:-]+$/.test(error.message) ? error.message : 'artifact-command-failed');
    process.exitCode = 1;
  }
}
