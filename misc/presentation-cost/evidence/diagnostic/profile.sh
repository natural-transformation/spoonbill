#!/usr/bin/env bash
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]]
result=/tmp/spoonbill-presentation-diagnostic-final-review
for version in baseline v3; do
  cpfile=/tmp/spoonbill-presentation-before-v1.cp
  if [[ "$version" == v3 ]]; then cpfile=/tmp/spoonbill-presentation-after-v3.cp; fi
  java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
    -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining \
    "-XX:StartFlightRecording=filename=$result/$version.jfr,settings=profile,dumponexit=true" \
    -cp "$(cat "$cpfile")" spoonbill.state.PresentationBenchmark \
    "diagnostic-$version" snapshot 3 3 > "$result/$version-inlining.log" 2> "$result/$version.stderr"
  jfr print --json --events jdk.ObjectAllocationSample "$result/$version.jfr" > "$result/$version-allocations.json"
done
