#!/usr/bin/env bash
# run.sh <before-cp> <after-cp> <new-directory> [pairs] [warm-seconds] [measured-seconds]
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Use the repository Nix environment.' >&2; exit 1; }
before_cp="$1"
after_cp="$2"
output="$3"
pairs="${4:-3}"
warm="${5:-2}"
seconds="${6:-2}"
[[ ! -e "$output" ]] || { echo 'Choose a new evidence directory.' >&2; exit 1; }
mkdir -p "$output"
for scenario in hub sort buffer; do
  for ((round=1; round<=pairs; round++)); do
    order=(before after)
    (( round % 2 != 0 )) || order=(after before)
    for variant in "${order[@]}"; do
      cp_file="$before_cp"
      [[ "$variant" != after ]] || cp_file="$after_cp"
      label="$scenario-$variant-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$cp_file")" spoonbill.effect.StreamOperatorBenchmark \
        "$label" "$scenario" "$warm" "$seconds" \
        > "$output/$label.jsonl" 2> "$output/$label.stderr"
      cat "$output/$label.jsonl"
    done
  done
done
