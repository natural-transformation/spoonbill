#!/usr/bin/env bash
# controls.sh <classpath> <new-directory>
set -euo pipefail
[[ -n "${IN_NIX_SHELL:-}" ]] || { echo 'Use the repository Nix environment.' >&2; exit 1; }
cp_file="$1"
output="$2"
[[ ! -e "$output" ]] || { echo 'Choose a new control directory.' >&2; exit 1; }
mkdir -p "$output"
for control in smoke allocation-control; do
  java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
    -cp "$(cat "$cp_file")" spoonbill.effect.StreamOperatorBenchmarkV3 "--$control" \
    > "$output/$control.jsonl" 2> "$output/$control.stderr"
done
node - "$output" <<'NODE'
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const dir = process.argv[2];
for (const name of ['smoke', 'allocation-control']) assert.equal(fs.readFileSync(path.join(dir, name + '.stderr'), 'utf8').trim(), '');
const smoke = fs.readFileSync(path.join(dir, 'smoke.jsonl'), 'utf8').trim().split(/\r?\n/).map(JSON.parse);
assert.equal(smoke.length, 3);
assert.deepEqual(smoke.map(x => x.scenario), ['hub', 'sort', 'buffer']);
for (const row of smoke) {
  assert.equal(row.protocol, 'stream-compat-v3'); assert.equal(row.correct, true);
  assert.equal(row.rounds, 64); assert.ok(row.checkedContinuations > 0);
  assert.equal(row.deliveries, row.scenario === 'buffer' ? 64 : 128);
}
const control = JSON.parse(fs.readFileSync(path.join(dir, 'allocation-control.jsonl'), 'utf8'));
assert.equal(control.protocol, 'stream-compat-v3'); assert.equal(control.threadAlive, true);
assert.equal(control.minimumBytes, 4096 * 4096); assert.ok(control.allocatedBytes >= control.minimumBytes);
console.log(JSON.stringify({correct: true, scope: 'deterministic controls only; no timing inference', smoke, allocation: control}));
NODE
