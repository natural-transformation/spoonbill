#!/usr/bin/env bash
# Usage (inside the spoonbill nix shell):
#   run.sh <v1|v8|local> <plain|memory|jdbc|jdbc-concurrent> <users> <clicks> <warmup> [thinkMs]
# jdbc modes must run under scripts/with-test-postgres.sh (SPOONBILL_JDBC_TEST_URL).
# jdbc-concurrent (local only) enables concurrent checks with BENCH_CAP (default 8).
# BENCH_MAINTENANCE_MS sets the guarded server's maintenance cadence.
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
variant="$1"; mode="$2"; users="$3"; clicks="$4"; warmup="$5"; think="${6:-0}"
port="$(node -e 'const s=require("node:net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
log="$(mkdir -p "$here/target"; mktemp "$here/target/server.XXXXXX")"
if [[ "$mode" == plain ]]; then main=(bench.PlainMain "$port")
elif [[ "$mode" == jdbc-concurrent ]]; then main=(bench.ConcurrentMain "${BENCH_CAP:-8}" "$port")
else main=(bench.GuardedMain "$mode" "$port"); fi
proxy=''
if [[ "$mode" == jdbc* ]]; then
  # Route JDBC through the delaying, round-trip-counting proxy.
  pg_port="$(sed -E 's|.*127.0.0.1:([0-9]+)/.*|\1|' <<<"$SPOONBILL_JDBC_TEST_URL")"
  rm -f "$log.port" "$log.count"
  node "$here/client/pgdelay.mjs" "$pg_port" "${PG_DELAY_MS:-0}" "$log.port" "$log.count" &
  proxy=$!
  for _ in $(seq 1 100); do [[ -s "$log.port" ]] && break; sleep 0.05; done
  export SPOONBILL_JDBC_TEST_URL="${SPOONBILL_JDBC_TEST_URL/127.0.0.1:$pg_port/127.0.0.1:$(cat "$log.port")}"
fi
java -Xms512m -Xmx512m -XX:+UseG1GC -Dbench.rows="${BENCH_ROWS:-50}" -cp "$(cat "$here/target/cp-$variant.txt")" "${main[@]}" >"$log" 2>&1 &
server=$!
trap 'kill "$server" 2>/dev/null || true; wait "$server" 2>/dev/null || true; [[ -n "$proxy" ]] && kill "$proxy" 2>/dev/null; rm -f "$log" "$log.port" "$log.count"' EXIT
for _ in $(seq 1 300); do grep -q ready "$log" && break; sleep 0.1; done
grep -q ready "$log" || { cat "$log"; exit 1; }
before="$(cat "$log.count" 2>/dev/null || echo 0)"
result="$(node "$here/client/latency.mjs" "http://127.0.0.1:$port" "$users" "$clicks" "$warmup" "$think")"
# The coordinator's counters, where the variant reports them.
stats="$(node -e "fetch('http://127.0.0.1:$port/bench-stats').then(r=>r.text()).then(t=>console.log(t.trim())).catch(()=>console.log('{}'))" 2>/dev/null || echo '{}')"
sleep 0.2
after="$(cat "$log.count" 2>/dev/null || echo 0)"
# Database round trips per click, including amortized session setup and warmup.
trips="$(node -e "console.log(Math.round(($after - $before) / ($users * ($clicks + $warmup)) * 10) / 10)")"
# Retained objects after a full GC, while the server is still running.
histogram="$(jcmd "$server" GC.class_histogram 2>/dev/null | awk '$4 ~ /TimerTask|Scheduler\$\$anon|JobHandler/ {n+=$2; b+=$3} END {printf "{\"timerObjects\":%d,\"timerBytes\":%d}", n, b}')"
heap="$(jcmd "$server" GC.heap_info 2>/dev/null | awk '/garbage-first heap/ {for (i=1;i<=NF;i++) if ($i=="used") print $(i+1)}' | tr -d 'K,')"
echo "{\"variant\":\"$variant\",\"mode\":\"$mode\",\"pgDelayMs\":${PG_DELAY_MS:-0},\"cap\":${BENCH_CAP:-0},\"maintenanceMs\":${BENCH_MAINTENANCE_MS:-30000},\"dbRoundTripsPerClick\":$trips,\"result\":$result,\"coordinator\":$stats,\"retained\":$histogram,\"heapUsedKB\":${heap:-0}}"
if grep -qiE "exception|error" "$log"; then echo "--- server log (errors) ---"; grep -iE "exception|error" "$log" | sort | uniq -c | head -20; fi
