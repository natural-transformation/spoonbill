#!/usr/bin/env bash
# Run inside the default repository Nix shell. Browser dependencies are supplied
# by the separate pinned browser shell; neither path downloads host software.
set -euo pipefail

if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  printf '%s\n' 'Run: env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command bash scripts/test-browser-auth-baseline.sh' >&2
  exit 1
fi
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-browser-auth.XXXXXX")"
server_pid=''
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$server_pid" ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    for attempt in {1..50}; do
      if ! kill -0 "$server_pid" 2>/dev/null; then break; fi
      sleep 0.1
    done
    kill -KILL "$server_pid" 2>/dev/null || true
    wait "$server_pid" 2>/dev/null || true
  fi
  if [[ "$status" -ne 0 && -f "$test_root/server.log" ]]; then cat "$test_root/server.log" >&2; fi
  rm -rf -- "$test_root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [[ $# -gt 1 ]]; then
  printf '%s\n' 'Usage: bash scripts/test-browser-auth-baseline.sh [COMPILED_CLASSPATH_FILE]' >&2
  exit 1
fi
if [[ $# -eq 1 ]]; then
  classpath="$(node - "$1" <<'NODE'
const fs = require('node:fs');
const text = fs.readFileSync(process.argv[2], 'utf8').trim();
if (!text.startsWith('/') || text.includes('\n')) throw new Error('Expected one absolute compiled classpath line');
process.stdout.write(text);
NODE
)"
else
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
    'browserAuthBaseline/compile' 'export browserAuthBaseline/Runtime/fullClasspath' > "$test_root/compile.log" 2>&1 || {
    cat "$test_root/compile.log" >&2
    exit 1
  }
  classpath="$(node - "$test_root/compile.log" <<'NODE'
const fs = require('node:fs');
const lines = fs.readFileSync(process.argv[2], 'utf8').split(/\r?\n/)
  .filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected exactly one compiled runtime classpath');
process.stdout.write(lines[0]);
NODE
)"
fi
port="$(node -e 'const net = require("node:net"); const server = net.createServer(); server.listen(0, "127.0.0.1", () => { console.log(server.address().port); server.close(); });')"
export SPOONBILL_AUTH_BASELINE_ORIGIN="http://localhost:$port"
case "${SPOONBILL_AUTH_BASELINE_PROVIDER:-memory}" in
  memory) server_main=spoonbill.browserauthbaseline.MemoryReferenceServer ;;
  jdbc)
    server_main=spoonbill.browserauthbaseline.JdbcReferenceServer
    export SPOONBILL_BASELINE_INITIALIZE=true
    ;;
  *) printf '%s\n' 'Unknown SPOONBILL_AUTH_BASELINE_PROVIDER; expected memory or jdbc.' >&2; exit 1 ;;
esac
java -Xms128m -Xmx512m -cp "$classpath" "$server_main" "$port" > "$test_root/server.log" 2>&1 &
server_pid=$!
node <<'NODE'
const {setTimeout: pause} = require('node:timers/promises');
(async () => {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(process.env.SPOONBILL_AUTH_BASELINE_ORIGIN + '/sign-out', {signal: AbortSignal.timeout(1000)});
      if (response.status === 200) { await response.arrayBuffer(); return; }
    } catch (_) {}
    await pause(100);
  }
  throw new Error('The owned reference server did not become ready');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
NODE
nix develop .#browser --no-write-lock-file --command \
  timeout --kill-after=10s 240s node misc/browser-auth-baseline/browser.test.cjs
if [[ "${SPOONBILL_AUTH_BASELINE_PROVIDER:-memory}" == jdbc ]]; then
  export SPOONBILL_AUTH_BASELINE_CLASSPATH="$classpath"
  export SPOONBILL_AUTH_BASELINE_JAVA="$(command -v java)"
  export SPOONBILL_AUTH_BASELINE_PSQL="$(command -v psql)"
  nix develop .#browser --no-write-lock-file --command \
    timeout --kill-after=10s 240s node misc/browser-auth-baseline/browser-restart.test.cjs
fi
