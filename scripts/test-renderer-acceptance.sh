#!/usr/bin/env bash
# F2 real-service renderer acceptance: real standalone Scala service, real
# HTML5 SSR and assembled client, pinned Chromium/WebKit, synthetic data.
# Runs the full matrix, then one injected fault that the matrix must detect.
set -euo pipefail
if [[ -z "${IN_NIX_SHELL:-}" ]]; then
  printf '%s\n' 'Run through the repository nix develop environment.' >&2
  exit 1
fi
if [[ $# -gt 1 ]]; then
  printf '%s\n' 'Usage: bash scripts/test-renderer-acceptance.sh [COMPILED_CLASSPATH_FILE]' >&2
  exit 1
fi
repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-renderer-acceptance.XXXXXX")"
server_pid=''
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  # Stop only the fixture JVM this script started.
  if [[ -n "$server_pid" ]]; then
    kill -TERM "$server_pid" 2>/dev/null || true
    for attempt in {1..220}; do
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
  env -u JAVA_HOME SBT_NATIVE_CLIENT=false \
    JAVA_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2' \
    SBT_OPTS='-Xms128m -Xmx2g -XX:ActiveProcessorCount=2 -Dsbt.supershell=false' \
    timeout --kill-after=10s 1200s \
    sbt --batch --supershell=false -Dsbt.server.autostart=false \
    'standalone/Test/compile' 'export standalone/Test/fullClasspath' > "$test_root/compile.log" 2>&1 || {
    cat "$test_root/compile.log" >&2; exit 1;
  }
  classpath="$(node - "$test_root/compile.log" <<'NODE'
const lines = require('node:fs').readFileSync(process.argv[2], 'utf8').split(/\r?\n/)
  .filter(line => line.startsWith('/') && line.includes('/target/') && line.includes(':'));
if (lines.length !== 1) throw new Error('Expected exactly one compiled standalone test classpath');
process.stdout.write(lines[0]);
NODE
)"
fi
# Record the Avocet artifacts the service actually links against.
node - "$classpath" <<'NODE'
const jars = process.argv[2].split(':').filter(entry => /avocet-(core|events)_3-[^/]+\.jar$/.test(entry));
if (jars.length !== 2) throw new Error(`Expected avocet-core and avocet-events on the classpath, found ${jars.length}`);
for (const jar of jars) console.log(`avocet artifact: ${jar}`);
NODE
# The JVM binds port zero itself; the readiness file carries its actual address.
java -Xms128m -Xmx512m -XX:ActiveProcessorCount=2 -cp "$classpath" \
  spoonbill.server.RendererAcceptanceBrowserFixture "$test_root/origin" > "$test_root/server.log" 2>&1 &
server_pid=$!
node - "$test_root/origin" <<'NODE'
const fs = require('node:fs');
const {setTimeout: pause} = require('node:timers/promises');
(async () => {
  const deadline = Date.now() + 45000;
  while (Date.now() < deadline) {
    try {
      const origin = fs.readFileSync(process.argv[2], 'utf8');
      if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(origin)) throw new Error('Invalid fixture origin');
      const response = await fetch(origin + '/health', {signal: AbortSignal.timeout(1000)});
      if (response.status === 200) { await response.arrayBuffer(); return; }
    } catch (_) {}
    await pause(100);
  }
  throw new Error('The renderer acceptance fixture did not become ready within 45 seconds');
})().catch(error => { console.error(error.message); process.exitCode = 1; });
NODE
export SPOONBILL_F2_ORIGIN="$(cat "$test_root/origin")"
nix develop .#browser --no-write-lock-file --command bash -c '
  set -euo pipefail
  timeout --kill-after=10s 300s node misc/renderer-acceptance-native-tests.cjs
  # Fault probe: the service truncates long names in its patches as the former
  # fixed-size headers did. The runner must reject the browser result.
  status=0
  SPOONBILL_F2_FAULT=truncate-names SPOONBILL_F2_ENGINES=chromium SPOONBILL_F2_ID_MODES=plain \
    SPOONBILL_F2_SCENARIOS=long-names timeout --kill-after=10s 60s \
    node misc/renderer-acceptance-native-tests.cjs > "$1" 2>&1 || status=$?
  if [[ "$status" -ne 1 ]] || ! grep -q "\"phase\":\"patched-dom\"" "$1"; then
    cat "$1" >&2
    printf "%s\n" "FAIL: the injected name-truncation fault was not detected (status $status)" >&2
    exit 1
  fi
  grep "\"phase\":\"patched-dom\"" "$1"
  printf "%s\n" "PASS: injected name-truncation fault detected (status $status)"
' renderer-acceptance "$test_root/fault-probe.log"
