#!/usr/bin/env bash
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]]
result=/tmp/spoonbill-presentation-diagnostic-final-review
for version in default forced-at; do
  flags=()
  if [[ "$version" == forced-at ]]; then
    flags+=('-XX:CompileCommand=inline,spoonbill.state.BoundedPresentationStore::spoonbill$state$BoundedPresentationStore$$at')
  fi
  java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
    -XX:+UnlockDiagnosticVMOptions -XX:+PrintCompilation -XX:+PrintInlining "${flags[@]}" \
    -cp "$(cat /tmp/spoonbill-presentation-after-v3.cp)" spoonbill.state.PresentationBenchmark \
    "diagnostic-$version" snapshot 3 3 > "$result/v3-$version-probe.log" 2> "$result/v3-$version-probe.stderr"
done
