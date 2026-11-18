#!/usr/bin/env bash
# Run inside the repository Nix shell, after prepare.sh generated both classpaths.
# run.sh <baseline-classpath> <pilot-classpath> <new-output-directory> [repetitions] [seconds] [candidate-classpath] [candidate-label]
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
base_cp="$1"
pilot_cp="$2"
output="$3"
repetitions="${4:-3}"
seconds="${5:-5}"
candidate_cp="${6:-}"
candidate_label="${7:-locked}"
[[ "$candidate_label" =~ ^[a-z]+$ && "$candidate_label" != baseline && "$candidate_label" != cas && "$candidate_label" != pilot ]] || {
  echo 'Choose a distinct lowercase candidate label.' >&2; exit 1;
}
[[ ! -e "$output" ]] || { echo 'Choose a new output directory; preserve every run.' >&2; exit 1; }
mkdir -p "$output"
java -version > "$output/java-version.txt" 2>&1
# Alternate pair order to limit a fixed thermal/JIT ordering bias. Each run is
# a separate fixed-heap JVM, and all callback counts/order must pass to emit JSON.
for scenario in isolated contended extensions backlog; do
  case "$scenario" in
    isolated) configuration=(16 16 3 32 1000) ;;
    contended) configuration=(1 4 3 1024 0) ;;
    extensions) configuration=(1 8 8 2048 1000) ;;
    backlog) configuration=(1 4 3 8192 2000) ;;
  esac
  for ((round=1; round<=repetitions; round++)); do
    variants=(baseline pilot)
    if [[ -n "$candidate_cp" ]]; then
      # Three-way comparisons rotate each implementation through every position.
      case $(( (round - 1) % 3 )) in
        0) variants=(baseline cas "$candidate_label") ;;
        1) variants=(cas "$candidate_label" baseline) ;;
        2) variants=("$candidate_label" baseline cas) ;;
      esac
    elif (( round % 2 == 0 )); then variants=(pilot baseline); fi
    for variant in "${variants[@]}"; do
      case "$variant" in
        baseline) classpath="$base_cp" ;;
        pilot|cas) classpath="$pilot_cp" ;;
        "$candidate_label") classpath="$candidate_cp" ;;
      esac
      label="$scenario-$variant-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$classpath")" spoonbill.internal.ExtensionRuntimeBenchmark \
        "$label" "${configuration[@]}" "$seconds" "$seconds" "$output/$label.jfr" \
        > "$output/$label.jsonl" 2> "$output/$label.stderr"
      cat "$output/$label.jsonl"
    done
  done
done
