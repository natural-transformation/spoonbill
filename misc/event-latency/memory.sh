#!/usr/bin/env bash
# Live heap per open session. Usage: memory.sh <v1|v8|local> <plain|memory|jdbc> <sessions>
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
variant="$1"; mode="$2"; sessions="$3"
port="$(node -e 'const s=require("node:net").createServer();s.listen(0,"127.0.0.1",()=>{console.log(s.address().port);s.close()})')"
log="$(mkdir -p "$here/target"; mktemp "$here/target/server.XXXXXX")"
if [[ "$mode" == plain ]]; then main=(bench.PlainMain "$port"); else main=(bench.GuardedMain "$mode" "$port"); fi
java -Xms512m -Xmx512m -XX:+UseG1GC -cp "$(cat "$here/target/cp-$variant.txt")" "${main[@]}" >"$log" 2>&1 &
server=$!
holder=''
trap 'kill "$server" 2>/dev/null || true; [[ -n "$holder" ]] && kill "$holder" 2>/dev/null; wait 2>/dev/null || true; rm -f "$log" "$log.ready"' EXIT
for _ in $(seq 1 300); do grep -q ready "$log" && break; sleep 0.1; done
used() { jcmd "$server" GC.run >/dev/null; sleep 0.5; jcmd "$server" GC.run >/dev/null; jcmd "$server" GC.heap_info | awk '/garbage-first heap/ {for (i=1;i<=NF;i++) if ($i=="used") print $(i+1)}' | tr -d 'K,'; }
# Warm the server with one session first so class loading is not counted.
node "$here/client/hold.mjs" "http://127.0.0.1:$port" 1 "$log.ready" & holder=$!
for _ in $(seq 1 300); do [[ -s "$log.ready" ]] && break; sleep 0.1; done
kill "$holder"; holder=''; rm -f "$log.ready"; sleep 2
before="$(used)"
node "$here/client/hold.mjs" "http://127.0.0.1:$port" "$sessions" "$log.ready" & holder=$!
for _ in $(seq 1 600); do [[ -s "$log.ready" ]] && break; sleep 0.1; done
after="$(used)"
echo "{\"variant\":\"$variant\",\"mode\":\"$mode\",\"sessions\":$sessions,\"heapBeforeKB\":$before,\"heapAfterKB\":$after,\"kbPerSession\":$(( (after - before) / sessions ))}"
