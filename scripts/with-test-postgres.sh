#!/usr/bin/env bash
# Run a command with a fresh loopback-only PostgreSQL test instance.
# Run from the Spoonbill Nix shell; PostgreSQL and Node come from flake.nix.
#
# SPOONBILL_BENCH_SQL_PROFILE=1 logs every executed statement with its backend
# process ID and application name, without bind values, and exports the log's
# path as SPOONBILL_BENCH_SQL_LOG. The click-latency harness reads it to count
# the statements of one authority observation. The log is removed with the
# instance when the command ends.
set -euo pipefail
umask 077

test_root="$(mktemp -d "${TMPDIR:-/tmp}/spoonbill-test-pg.XXXXXX")"
postgres_log="$test_root/postgres.log"
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  pg_ctl -D "$test_root/data" -m fast -w stop >/dev/null 2>&1 || true
  if [[ "$status" -ne 0 && -f "$postgres_log" ]]; then
    tail -n 100 "$postgres_log" >&2
  fi
  rm -rf -- "$test_root"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

port="$(node -e 'const net = require("node:net"); const server = net.createServer(); server.listen(0, "127.0.0.1", () => { console.log(server.address().port); server.close(); });')"
initdb -D "$test_root/data" --username=spoonbill_test --auth=trust --no-locale >/dev/null
if [[ "${SPOONBILL_BENCH_SQL_PROFILE:-0}" == 1 ]]; then
  # One prefixed entry per executed statement: "spoonbill-log|<pid>|<application>|".
  # A statement's continuation lines carry no prefix. Bind values are not logged.
  cat >>"$test_root/data/postgresql.conf" <<'EOF'
log_statement = 'all'
log_line_prefix = 'spoonbill-log|%p|%a|'
log_parameter_max_length = 0
EOF
  export SPOONBILL_BENCH_SQL_LOG="$postgres_log"
fi
pg_ctl -D "$test_root/data" -o "-h 127.0.0.1 -p $port -k $test_root" -l "$postgres_log" -w start >/dev/null
export SPOONBILL_JDBC_TEST_URL="jdbc:postgresql://127.0.0.1:$port/postgres?user=spoonbill_test"
export SPOONBILL_JDBC_TEST_USER=spoonbill_test
"$@"
