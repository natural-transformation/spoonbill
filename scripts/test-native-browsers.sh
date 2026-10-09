#!/usr/bin/env bash
# From the Spoonbill checkout: nix develop .#browser --command bash scripts/test-native-browsers.sh
set -euo pipefail

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
usage='Run: nix develop .#browser --no-write-lock-file --command bash scripts/test-native-browsers.sh'
for tool in node timeout; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'Missing %s. %s\n' "$tool" "$usage" >&2
    exit 1
  fi
done
if [[ -z "${PLAYWRIGHT_DRIVER_PATH:-}" || -z "${PLAYWRIGHT_BROWSERS_PATH:-}" ]]; then
  printf 'Missing the Nix Playwright environment. %s\n' "$usage" >&2
  exit 1
fi
export PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1

# Fail before starting any browser if the matching Nix bundle is unavailable.
node <<'NODE'
const fs = require('node:fs');
try {
  const {chromium, webkit} = require(process.env.PLAYWRIGHT_DRIVER_PATH);
  for (const browser of [chromium, webkit]) fs.accessSync(browser.executablePath(), fs.constants.X_OK);
  if (process.platform === 'linux') {
    const vendor = process.env.SPOONBILL_PLAYWRIGHT_EGL_VENDOR;
    if (!vendor) throw new Error('SPOONBILL_PLAYWRIGHT_EGL_VENDOR is missing');
    fs.accessSync(vendor, fs.constants.R_OK);
  }
} catch (error) {
  console.error(`Nix browser dependencies unavailable: ${error.message}`);
  console.error('Enter the repository browser shell with nix develop .#browser; do not download browsers separately.');
  process.exit(1);
}
NODE

active_pid=''
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  if [[ -n "$active_pid" ]]; then
    # Signal only this runner's timeout process; it forwards to its own group.
    kill -TERM "$active_pid" 2>/dev/null || true
    wait "$active_pid" 2>/dev/null || true
  fi
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

for fixture in durable-view-native-tests.cjs sensitive-native-tests.cjs sensitive-departure-native-tests.cjs; do
  printf 'Running %s in Chromium and WebKit\n' "$fixture"
  # Fixtures close their browsers/server in finally blocks; Playwright also
  # closes its owned browser on SIGTERM. Bound even a stalled native operation.
  timeout --kill-after=10s 120s node "$repo_root/misc/$fixture" &
  active_pid=$!
  if wait "$active_pid"; then
    active_pid=''
  else
    status=$?
    active_pid=''
    printf '%s failed (exit %s).\n' "$fixture" "$status" >&2
    exit "$status"
  fi
done
