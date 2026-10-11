#!/usr/bin/env bash
# Capture only compiled runtime/provenance inputs into a new relocatable bundle.
set -euo pipefail
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  printf '%s\n' 'Run through the repository nix develop environment.' >&2
  exit 1
fi
if [[ $# -lt 1 || $# -gt 2 ]]; then
  printf '%s\n' 'Usage: bash scripts/capture-browser-auth-artifact.sh NEW_DIRECTORY [COMPILED_CLASSPATH_FILE]' >&2
  exit 1
fi
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
capture_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-auth-artifact.XXXXXX")"
trap 'rm -rf -- "$capture_root"' EXIT
if [[ $# -eq 2 ]]; then
  classpath_file="$2"
else
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
    'browserAuthBaseline/compile' 'export browserAuthBaseline/Runtime/fullClasspath' > "$capture_root/compile.log" 2>&1 || {
    cat "$capture_root/compile.log" >&2
    exit 1
  }
  classpath_file="$capture_root/classpath.txt"
  node - "$capture_root/compile.log" "$classpath_file" <<'NODE'
const {readFileSync, writeFileSync} = require('node:fs');
const lines = readFileSync(process.argv[2], 'utf8').split(/\r?\n/)
  .filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected exactly one compiled runtime classpath');
writeFileSync(process.argv[3], lines[0] + '\n', {flag: 'wx'});
NODE
fi
classpath="$(node - "$classpath_file" <<'NODE'
const {readFileSync} = require('node:fs');
const value = readFileSync(process.argv[2], 'utf8').trim();
if (!value.startsWith('/') || /[\r\n]/.test(value)) throw new Error('Expected one absolute classpath line');
process.stdout.write(value);
NODE
)"
java -cp "$classpath" spoonbill.browserauthbaseline.ReferencePolicyReport > "$capture_root/policy.json"
node misc/browser-auth-baseline/performance/reference-policy.mjs \
  misc/browser-auth-baseline/performance/manifest.json "$capture_root/policy.json"
node misc/browser-auth-baseline/inventory.mjs "$capture_root/source.json"
node misc/browser-auth-baseline/client-inventory.mjs \
  modules/spoonbill/target/scala-3.3.7/resource_managed/main/static/spoonbill-client.min.js \
  "$capture_root/client.json"
node misc/browser-auth-baseline/performance/artifact.mjs capture \
  "$classpath_file" "$1" "$capture_root/source.json" "$capture_root/policy.json" "$capture_root/client.json"
