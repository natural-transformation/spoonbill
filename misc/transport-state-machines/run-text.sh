#!/usr/bin/env bash
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
baseline="$1"
candidate="$2"
output="$3"
repetitions="${4:-3}"
seconds="${5:-2}"
[[ ! -e "$output" ]] || { echo 'Use a new directory; retain all trials.' >&2; exit 1; }
mkdir -p "$output"
java -version > "$output/java-version.txt" 2>&1
for scenario in short ascii unicode fragmented; do
  for ((round=1; round<=repetitions; round++)); do
    versions=(baseline scanner)
    if ((round % 2 == 0)); then versions=(scanner baseline); fi
    for version in "${versions[@]}"; do
      classpath="$baseline"
      [[ "$version" != scanner ]] || classpath="$candidate"
      label="$scenario-$version-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$classpath")" spoonbill.web.TextAccumulatorBenchmark \
        "$label" "$scenario" "$seconds" > "$output/$label.jsonl" 2> "$output/$label.stderr"
    done
  done
done
