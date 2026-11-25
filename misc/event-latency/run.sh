#!/usr/bin/env bash
# Usage (inside the spoonbill nix shell):
#   run.sh <v1|v8|local> <plain|memory|jdbc|jdbc-concurrent|jdbc-snapshot|jdbc-reference> <users> <clicks> <warmup> [thinkMs]
# plain-extensions and memory-extensions (local only) add BENCH_EXTENSIONS extensions
# (default 3) that observe every state change, to the unguarded and the in-memory app.
# jdbc modes must run under scripts/with-test-postgres.sh (SPOONBILL_JDBC_TEST_URL).
# jdbc-concurrent (local only) enables concurrent checks with BENCH_CAP (default 8).
# jdbc-snapshot and jdbc-reference (local only) serve authenticated views whose
# projection is saved inline or through published payload references. The client
# signs in first and opens every user before anyone clicks; the run then asserts
# the recovery plan's healthy commit budget from the server's /bench-stats counters
# (client/budget.mjs) and fails when it is not met. BENCH_PAYLOAD_BYTES pads the
# projection (default 1024), BENCH_PAYLOAD_DELAY_MS slows the payload store, and
# BENCH_RECONNECT=1 reconnects every user once after warmup to measure restoration.
# BENCH_MAINTENANCE_MS sets the guarded server's maintenance cadence.
# jdbc-shared (local only) serves authenticated shared views with separate provider and
# clock pools and a host whose resolve locks its own session row. It must run under
# scripts/with-test-postgres.sh with SPOONBILL_BENCH_SQL_PROFILE=1. It first runs the
# observation-budget probe (BENCH_SHARED_OBSERVATIONS calls, default 20, after
# BENCH_SHARED_PROBE_WARMUP, default 10), which counts each call's statements from the
# statement log and fails the run when one is over budget; then the click phase with a
# boundary after warmup. The BENCH_SHARED_* switches select the settings (see README.md).
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
variant="$1"; mode="$2"; users="$3"; clicks="$4"; warmup="$5"; think="${6:-0}"
port="$(node -e 'const s=require("node:net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
log="$(mkdir -p "$here/target"; mktemp "$here/target/server.XXXXXX")"
snapshot=''
shared=''
if [[ "$mode" == plain ]]; then main=(bench.PlainMain "$port")
elif [[ "$mode" == plain-extensions ]]; then main=(bench.ExtensionMain plain "${BENCH_EXTENSIONS:-3}" "$port")
elif [[ "$mode" == memory-extensions ]]; then main=(bench.ExtensionMain memory "${BENCH_EXTENSIONS:-3}" "$port")
elif [[ "$mode" == jdbc-concurrent ]]; then main=(bench.ConcurrentMain "${BENCH_CAP:-8}" "$port")
elif [[ "$mode" == jdbc-snapshot || "$mode" == jdbc-reference ]]; then main=(bench.SnapshotMain "$mode" "$port"); snapshot=1
elif [[ "$mode" == jdbc-shared ]]; then main=(bench.SharedMain "$port"); shared=1
else main=(bench.GuardedMain "$mode" "$port"); fi
if [[ -n "$shared" && -z "${SPOONBILL_BENCH_SQL_LOG:-}" ]]; then
  echo "jdbc-shared counts statements from the statement log: run it under scripts/with-test-postgres.sh with SPOONBILL_BENCH_SQL_PROFILE=1" >&2
  exit 2
fi
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
# The observation-budget probe runs first, with the click client idle and maintenance paused.
probe='null'
if [[ -n "$shared" ]]; then
  probe="$(node -e "fetch('http://127.0.0.1:$port/bench-observe?count=${BENCH_SHARED_OBSERVATIONS:-20}&warmup=${BENCH_SHARED_PROBE_WARMUP:-10}',{method:'POST'}).then(r=>r.text()).then(t=>console.log(t.trim())).catch(e=>console.log(JSON.stringify({ok:false,violations:[String(e)]})))")"
fi
# The server's counters, where the variant reports them (GET /bench-stats).
stats() { node -e "fetch('http://127.0.0.1:$port/bench-stats').then(r=>r.text()).then(t=>console.log(t.trim())).catch(()=>console.log('{}'))" 2>/dev/null || echo '{}'; }
before="$(cat "$log.count" 2>/dev/null || echo 0)"
stats_before="$(stats)"
# The snapshot modes sign in, open every user first and record the phase boundary.
if [[ -n "$snapshot" ]]; then export BENCH_LOGIN=1 BENCH_PHASES=1 BENCH_COUNT_FILE="$log.count"; fi
# The shared mode also records the boundary after warmup and the counters once the measured work drained.
if [[ -n "$shared" ]]; then export BENCH_LOGIN=1 BENCH_PHASES=1 BENCH_WARMUP_BARRIER=1 BENCH_COUNT_FILE="$log.count"; fi
result="$(node "$here/client/latency.mjs" "http://127.0.0.1:$port" "$users" "$clicks" "$warmup" "$think")"
stats_after="$(stats)"
sleep 0.2
after="$(cat "$log.count" 2>/dev/null || echo 0)"
# Database round trips per click, including amortized session setup and warmup.
trips="$(node -e "console.log(Math.round(($after - $before) / ($users * ($clicks + $warmup)) * 10) / 10)")"
# The healthy commit budget, asserted from the counters; null for the other modes.
budget='null'
if [[ -n "$snapshot" ]]; then
  budget="$(node "$here/client/budget.mjs" "$mode" "$stats_before" "$result" "$stats_after" "$users" "$((clicks + warmup))" "$before" "$after")"
fi
# The shared mode's probe verdict and its click-phase report.
extra=''
if [[ -n "$shared" ]]; then
  extra=",\"probe\":$probe,\"clicks\":$(node "$here/client/shared-report.mjs" "$result" "$users" "$clicks")"
fi
# Retained objects after a full GC, while the server is still running.
histogram="$(jcmd "$server" GC.class_histogram 2>/dev/null | awk '$4 ~ /TimerTask|Scheduler\$\$anon|JobHandler/ {n+=$2; b+=$3} END {printf "{\"timerObjects\":%d,\"timerBytes\":%d}", n, b}')"
heap="$(jcmd "$server" GC.heap_info 2>/dev/null | awk '/garbage-first heap/ {for (i=1;i<=NF;i++) if ($i=="used") print $(i+1)}' | tr -d 'K,')"
echo "{\"variant\":\"$variant\",\"mode\":\"$mode\",\"pgDelayMs\":${PG_DELAY_MS:-0},\"cap\":${BENCH_CAP:-0},\"maintenanceMs\":${BENCH_MAINTENANCE_MS:-30000},\"payloadBytes\":${BENCH_PAYLOAD_BYTES:-1024},\"payloadDelayMs\":${BENCH_PAYLOAD_DELAY_MS:-0},\"dbRoundTripsPerClick\":$trips,\"result\":$result,\"coordinator\":$stats_after,\"budget\":$budget,\"retained\":$histogram,\"heapUsedKB\":${heap:-0}$extra}"
if grep -qiE "exception|error" "$log"; then echo "--- server log (errors) ---"; grep -iE "exception|error" "$log" | sort | uniq -c | head -20; fi
if [[ -n "$snapshot" ]] && [[ "$(node -e 'console.log(JSON.parse(process.argv[1]).ok)' "$budget")" != true ]]; then
  echo "commit budget not met: $(node -e 'console.log(JSON.stringify(JSON.parse(process.argv[1]).violations))' "$budget")" >&2
  exit 1
fi
if [[ -n "$shared" ]] && [[ "$(node -e 'console.log(JSON.parse(process.argv[1]).ok)' "$probe")" != true ]]; then
  echo "observation budget not met: $(node -e 'console.log(JSON.stringify(JSON.parse(process.argv[1]).violations))' "$probe")" >&2
  exit 1
fi
