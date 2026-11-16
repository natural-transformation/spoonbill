#!/usr/bin/env bash
# Run inside the repository Nix shell, after prepare.sh generated both classpaths.
# run.sh <baseline-classpath> <pilot-classpath> <new-output-directory> [repetitions] [seconds]
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
base_cp="$1"
pilot_cp="$2"
output="$3"
repetitions="${4:-3}"
seconds="${5:-5}"
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
    if (( round % 2 == 0 )); then variants=(pilot baseline); fi
    for variant in "${variants[@]}"; do
      classpath="$base_cp"
      [[ "$variant" != pilot ]] || classpath="$pilot_cp"
      label="$scenario-$variant-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$classpath")" spoonbill.internal.ExtensionRuntimeBenchmark \
        "$label" "${configuration[@]}" "$seconds" "$seconds" "$output/$label.jfr" \
        > "$output/$label.jsonl" 2> "$output/$label.stderr"
      cat "$output/$label.jsonl"
    done
  done
done
