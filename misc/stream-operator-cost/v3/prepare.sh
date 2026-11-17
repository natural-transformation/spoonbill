#!/usr/bin/env bash
# prepare.sh <checkout> <classpath-output> <legacy|factory>
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
checkout="$1"
output="$2"
case "$3" in
  legacy) adapter="$here/LegacyBackend.scala" ;;
  factory) adapter="$here/FactoryBackend.scala" ;;
  *) echo 'Choose legacy or factory adapter.' >&2; exit 1 ;;
esac
mkdir -p "$(dirname -- "$output")"
(cd "$checkout" && sbt --batch --supershell=false -Dsbt.server.autostart=false \
  "set effect / Test / unmanagedSources := Seq(file(\"$here/StreamOperatorBenchmarkV3.scala\"), file(\"$adapter\"))" \
  'effect/Test/compile' 'export effect/Test/fullClasspath') > "$output.compile.log" 2>&1
node - "$output.compile.log" "$output" <<'NODE'
const fs = require('node:fs');
const log = fs.readFileSync(process.argv[2], 'utf8');
if (/^\[error\]/m.test(log)) throw new Error('Compilation failed; inspect log');
const lines = log.split(/\r?\n/).filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected one compiled classpath');
fs.writeFileSync(process.argv[3], lines[0] + '\n');
NODE
