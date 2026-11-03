#!/usr/bin/env bash
# Build the three harness variants and write their runtime classpaths to
# target/cp-{v1,v8,local}.txt. Run from the repository root inside its Nix shell:
#   nix develop --no-write-lock-file --command bash misc/event-latency/prepare.sh
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd -- "$here/../.." && pwd)"
mkdir -p "$here/target"
# The local variant links the working tree's classes, not a published artifact.
(cd "$root" && sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'export pekko/Runtime/fullClasspath' 'export securityJdbc/Runtime/fullClasspath') |
  grep '^/' | tr ':' '\n' | awk 'NF && !seen[$0]++' | paste -sd: - > "$here/target/local-classpath.txt"
(cd "$here" && sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'export v1/Runtime/fullClasspath' 'export v8/Runtime/fullClasspath' 'export local/Runtime/fullClasspath') |
  grep '^/' > "$here/target/classpaths.txt"
for variant in v1 v8 local; do
  grep -F "/target/$variant/" "$here/target/classpaths.txt" > "$here/target/cp-$variant.txt"
done
wc -l "$here"/target/cp-*.txt
