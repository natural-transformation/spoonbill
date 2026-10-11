#!/usr/bin/env bash
# Two independent JVMs, one disposable PostgreSQL database and real browsers.
set -euo pipefail
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  printf '%s\n' 'Run through the repository nix develop environment.' >&2
  exit 1
fi
if [[ $# -gt 1 ]]; then
  printf '%s\n' 'Usage: bash scripts/test-clustered-browser-auth.sh [COMPILED_CLASSPATH_FILE]' >&2
  exit 1
fi
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
if [[ -z "${SPOONBILL_JDBC_TEST_URL:-}" ]]; then
  exec bash scripts/with-test-postgres.sh bash scripts/test-clustered-browser-auth.sh "$@"
fi
test_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-clustered-auth.XXXXXX")"
trap 'rm -rf -- "$test_root"' EXIT
if [[ $# -eq 1 ]]; then
  cp -- "$1" "$test_root/classpath"
else
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
    'browserAuthExample/compile' 'export browserAuthExample/Runtime/fullClasspath' > "$test_root/compile.log" 2>&1 || {
    cat "$test_root/compile.log" >&2; exit 1;
  }
  node - "$test_root/compile.log" "$test_root/classpath" <<'NODE'
const fs = require('node:fs');
const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/)
  .filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected one compiled example runtime classpath');
fs.writeFileSync(process.argv[3], lines[0]);
NODE
fi
export SPOONBILL_CLUSTER_CLASSPATH_FILE="$test_root/classpath"
export SPOONBILL_AUTH_JAVA="$(command -v java)"
export SPOONBILL_AUTH_PSQL="$(command -v psql)"
nix develop .#browser --no-write-lock-file --command \
  timeout --kill-after=15s 350s node misc/clustered-browser-auth-tests.cjs
