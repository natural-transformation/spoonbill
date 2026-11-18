#!/usr/bin/env bash
# prepare.sh <checkout> <new-frozen-directory> <classpath-output>
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
checkout="$1"
frozen="$2"
output="$3"
[[ ! -e "$frozen" ]] || { echo 'Preserve frozen classpaths; choose a new directory.' >&2; exit 1; }
mkdir -p "$(dirname -- "$output")"
(cd "$checkout" && sbt --batch --supershell=false -Dsbt.server.autostart=false \
  "set spoonbill / Test / unmanagedSources := Seq(file(\"$here/PresentationBenchmark.scala\"))" \
  'spoonbill/Test/compile' 'export spoonbill/Test/fullClasspath') > "$output.compile.log" 2>&1
node - "$output.compile.log" "$frozen" "$output" <<'NODE'
const fs = require('node:fs');
const path = require('node:path');
const log = fs.readFileSync(process.argv[2], 'utf8');
if (/^\[error\]/m.test(log)) throw new Error('Compilation failed; inspect log');
const lines = log.split(/\r?\n/).filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected one classpath');
const frozen = process.argv[3];
fs.mkdirSync(frozen);
const entries = lines[0].split(path.delimiter).map((entry, index) => {
  if (!fs.statSync(entry).isDirectory() && !entry.includes('/target/')) return entry;
  const target = path.join(frozen, `${index}-${path.basename(entry)}`);
  fs.cpSync(entry, target, {recursive: true});
  return target;
});
fs.writeFileSync(process.argv[4], entries.join(path.delimiter) + '\n');
NODE
