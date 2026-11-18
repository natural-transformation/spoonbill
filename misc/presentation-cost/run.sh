#!/usr/bin/env bash
# run.sh <baseline-classpath> <candidate-classpath> <new-output-directory> [repetitions] [seconds] [warmup] [workloads]
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Run through nix develop.' >&2; exit 1; }
baseline="$1"
candidate="$2"
output="$3"
repetitions="${4:-3}"
seconds="${5:-3}"
warmup="${6:-2}"
IFS=, read -r -a scenarios <<< "${7:-read,read-clock,write,snapshot,lease}"
seen=""
for scenario in "${scenarios[@]}"; do
  case "$scenario" in read|read-clock|write|snapshot|lease) ;; *) echo 'Invalid workload.' >&2; exit 1 ;; esac
  [[ "$seen" != *"|$scenario|"* ]] || { echo 'Duplicate workload.' >&2; exit 1; }
  seen+="|$scenario|"
done
[[ "${#scenarios[@]}" -gt 0 && "$repetitions" =~ ^[1-9][0-9]*$ && "$seconds" =~ ^[1-9][0-9]*$ && "$warmup" =~ ^[1-9][0-9]*$ ]] || {
  echo 'Positive trial counts and durations are required.' >&2; exit 1;
}
[[ ! -e "$output" ]] || { echo 'Preserve all trials; choose a new directory.' >&2; exit 1; }
mkdir -p "$output"
java -version > "$output/java-version.txt" 2>&1
for scenario in "${scenarios[@]}"; do
  for ((round=1; round<=repetitions; round++)); do
    versions=(baseline candidate)
    if (( round % 2 == 0 )); then versions=(candidate baseline); fi
    for version in "${versions[@]}"; do
      classpath="$baseline"
      if [[ "$version" == candidate ]]; then classpath="$candidate"; fi
      label="$scenario-$version-$round"
      java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
        -cp "$(cat "$classpath")" spoonbill.state.PresentationBenchmark \
        "$label" "$scenario" "$warmup" "$seconds" > "$output/$label.jsonl" 2> "$output/$label.stderr"
      cat "$output/$label.jsonl"
    done
  done
done
