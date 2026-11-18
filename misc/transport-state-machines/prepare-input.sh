#!/usr/bin/env bash
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
checkout="$1"
output="$2"
fixture="${3:-$here/SetupInputPullBenchmark.scala}"
mkdir -p "$(dirname -- "$output")"
(cd "$checkout" && sbt --batch --supershell=false -Dsbt.server.autostart=false \
  "set standalone / Test / unmanagedSources += file(\"$fixture\")" \
  'standalone/Test/compile' 'export standalone/Test/fullClasspath') > "$output.compile.log" 2>&1
node - "$output.compile.log" "$output" <<'NODE'
const fs = require('node:fs');
const log = fs.readFileSync(process.argv[2], 'utf8');
if (/^\[error\]/m.test(log)) throw new Error('Compilation failed');
const paths = log.split(/\r?\n/).filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (paths.length !== 1) throw new Error('Expected one classpath');
fs.writeFileSync(process.argv[3], paths[0] + '\n');
NODE
