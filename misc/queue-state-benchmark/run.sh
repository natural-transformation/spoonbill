#!/usr/bin/env bash
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
baseline="$1"
candidate="$2"
output="$3"
seconds="${4:-3}"
IFS=',' read -r -a scenarios <<< "${5:-roundtrip,batch,contended}"
(( ${#scenarios[@]} > 0 )) || exit 1
seen=,
for scenario in "${scenarios[@]}"; do
  [[ "$scenario" == roundtrip || "$scenario" == batch || "$scenario" == contended ]] || exit 1
  [[ "$seen" != *",$scenario,"* ]] || { echo 'Duplicate workload would overwrite trials.' >&2; exit 1; }
  seen+="$scenario,"
done
[[ ! -e "$output" ]] || { echo 'Preserve prior trials; choose a new directory.' >&2; exit 1; }
mkdir -p "$output"
java -version > "$output/java-version.txt" 2>&1
for scenario in "${scenarios[@]}"; do
  for round in 1 2 3; do
    variants=(baseline candidate)
    if (( round % 2 == 0 )); then variants=(candidate baseline); fi
    for variant in "${variants[@]}"; do
      classpath="$baseline"
      [[ "$variant" != candidate ]] || classpath="$candidate"
      label="$scenario-$variant-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$classpath")" spoonbill.effect.QueueLiveAllocationBenchmark "$label" "$scenario" "$seconds" \
        > "$output/$label.jsonl" 2> "$output/$label.stderr"
      cat "$output/$label.jsonl"
    done
  done
done
