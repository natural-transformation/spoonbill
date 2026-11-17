#!/usr/bin/env bash
# Compile this same fixture against a checkout from inside its Nix environment.
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
checkout="$1"
output="$2"
fixture="${3:-$here/OwnershipBenchmark.scala}"
mkdir -p "$(dirname -- "$output")"
(cd "$checkout" && sbt --batch --supershell=false -Dsbt.server.autostart=false \
  "set spoonbill / Test / unmanagedSources := Seq(file(\"$fixture\"))" \
  'spoonbill/Test/compile' 'export spoonbill/Test/fullClasspath') > "$output.compile.log" 2>&1
node - "$output.compile.log" "$output" <<'NODE'
const fs = require('node:fs');
const log = fs.readFileSync(process.argv[2], 'utf8');
if (/^\[error\]/m.test(log)) throw new Error('Compilation failed; inspect log');
const lines = log.split(/\r?\n/).filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected one compiled classpath');
fs.writeFileSync(process.argv[3], lines[0] + '\n');
NODE
