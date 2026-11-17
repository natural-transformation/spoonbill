#!/usr/bin/env bash
# run.sh <before-classpath> <after-classpath> <new-output> [repeats] [warmup] [seconds]
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
before_cp="$1"
after_cp="$2"
output="$3"
repeats="${4:-3}"
warmup="${5:-2}"
seconds="${6:-3}"
[[ ! -e "$output" ]] || { echo 'Choose a new output directory; preserve every trial.' >&2; exit 1; }
mkdir -p "$output"
for scenario in write read render0 render1 render1024 abort1; do
  for writers in 1 4; do
    [[ "$scenario" == write || "$scenario" == read || "$writers" == 1 ]] || continue
    for ((round=1; round<=repeats; round++)); do
      variants=(before after)
      if (( round % 2 == 0 )); then variants=(after before); fi
      for variant in "${variants[@]}"; do
        classpath="$before_cp"
        [[ "$variant" != after ]] || classpath="$after_cp"
        label="$scenario-$writers-$variant-$round"
        java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
          -cp "$(cat "$classpath")" spoonbill.auth.LifecycleBenchmark \
          "$label" "$scenario" "$writers" "$warmup" "$seconds" \
          > "$output/$label.jsonl" 2> "$output/$label.stderr"
        cat "$output/$label.jsonl"
      done
    done
  done
done
