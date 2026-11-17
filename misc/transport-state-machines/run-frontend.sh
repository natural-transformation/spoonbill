#!/usr/bin/env bash
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
baseline="$1"
candidate="$2"
output="$3"
[[ ! -e "$output" ]] || { echo 'Use a new directory.' >&2; exit 1; }
mkdir -p "$output"
java -version > "$output/java-version.txt" 2>&1
for scenario in control property patch action; do
  for round in 1 2 3; do
    variants=(baseline model)
    if ((round % 2 == 0)); then variants=(model baseline); fi
    for variant in "${variants[@]}"; do
      classpath="$baseline"
      [[ "$variant" != model ]] || classpath="$candidate"
      label="$scenario-$variant-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$classpath")" spoonbill.internal.FrontendFrameBenchmark "$label" "$scenario" 2 \
        > "$output/$label.jsonl" 2> "$output/$label.stderr"
    done
  done
done
