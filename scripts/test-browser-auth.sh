#!/usr/bin/env bash
# Public v4 example correctness. Use the repository default Nix shell; the
# pinned browser shell supplies browsers without downloading host software.
set -euo pipefail
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  printf '%s\n' 'Run through the repository nix develop environment.' >&2
  exit 1
fi
case "${SPOONBILL_AUTH_PROVIDER:-memory}" in
  memory|jdbc) server_main=example.browserauth.Main ;;
  io) server_main=example.browserauth.LazyMain ;;
  *) printf '%s\n' 'SPOONBILL_AUTH_PROVIDER must be memory, jdbc or io.' >&2; exit 1 ;;
esac
if [[ $# -gt 1 ]]; then
  printf '%s\n' 'Usage: bash scripts/test-browser-auth.sh [COMPILED_CLASSPATH_FILE]' >&2
  exit 1
fi
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-browser-auth-v4.XXXXXX")"
server_pid=''
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$server_pid" ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    for attempt in {1..150}; do
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
if [[ $# -eq 1 ]]; then
  classpath="$(node - "$1" <<'NODE'
const text = require('node:fs').readFileSync(process.argv[2], 'utf8').trim();
if (!text.startsWith('/') || text.includes('\n')) throw new Error('Expected one absolute compiled classpath line');
process.stdout.write(text);
NODE
)"
else
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
    'browserAuthExample/compile' 'export browserAuthExample/Runtime/fullClasspath' > "$test_root/compile.log" 2>&1 || {
    cat "$test_root/compile.log" >&2; exit 1;
  }
  classpath="$(node - "$test_root/compile.log" <<'NODE'
const lines = require('node:fs').readFileSync(process.argv[2], 'utf8').split(/\r?\n/)
  .filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected exactly one compiled example runtime classpath');
process.stdout.write(lines[0]);
NODE
)"
fi
port="$(node -e 'const net = require("node:net"); const server = net.createServer(); server.listen(0, "127.0.0.1", () => { console.log(server.address().port); server.close(); });')"
export SPOONBILL_AUTH_ORIGIN="http://localhost:$port"
server_args=("$port")
if [[ "${SPOONBILL_AUTH_PROVIDER:-memory}" == jdbc ]]; then
  # A fresh owned namespace in the caller's disposable PostgreSQL instance.
  export SPOONBILL_AUTH_SCHEMA="example_$(node -e 'process.stdout.write(require("node:crypto").randomUUID().replaceAll("-", ""))')"
  server_args+=(--initialize)
fi
java -Xms128m -Xmx512m -cp "$classpath" "$server_main" "${server_args[@]}" > "$test_root/server.log" 2>&1 &
server_pid=$!
node <<'NODE'
const {setTimeout: pause} = require('node:timers/promises');
(async () => {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(process.env.SPOONBILL_AUTH_ORIGIN + '/health', {signal: AbortSignal.timeout(1000)});
      if (response.status === 200) { await response.arrayBuffer(); return; }
    } catch (_) {}
    await pause(100);
  }
  throw new Error('The public example did not become ready within its startup bound');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
NODE
nix develop .#browser --no-write-lock-file --command \
  timeout --kill-after=10s 250s node misc/browser-auth-native-tests.cjs
