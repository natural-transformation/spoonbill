#!/usr/bin/env bash
# run.sh <original-cp> <current-cp> <candidate-cp> <new-directory> [warm-seconds] [measured-seconds]
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Use the repository Nix environment.' >&2; exit 1; }
original_cp="$1"
current_cp="$2"
candidate_cp="$3"
output="$4"
warm="${5:-2}"
seconds="${6:-2}"
[[ ! -e "$output" ]] || { echo 'Choose a new evidence directory.' >&2; exit 1; }
mkdir -p "$output"
for scenario in hub sort buffer; do
  for round in 1 2 3; do
    case "$round" in
      1) order=(original current candidate) ;;
      2) order=(current candidate original) ;;
      3) order=(candidate original current) ;;
    esac
    for variant in "${order[@]}"; do
      case "$variant" in
        original) cp_file="$original_cp" ;;
        current) cp_file="$current_cp" ;;
        candidate) cp_file="$candidate_cp" ;;
      esac
      label="$scenario-$variant-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$cp_file")" spoonbill.effect.StreamOperatorBenchmarkV3 \
        "$label" "$scenario" "$warm" "$seconds" \
        > "$output/$label.jsonl" 2> "$output/$label.stderr"
      cat "$output/$label.jsonl"
    done
  done
done
