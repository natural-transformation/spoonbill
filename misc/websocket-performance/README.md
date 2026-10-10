# Standalone WebSocket transport comparison

This fixture measures connected binary echo traffic at the actual standalone
socket. Both builds use the same service and client workload; only the response
factory changes for the public API migration. It validates every echoed payload.
It is a transport comparison, not a guarded-session, recovery, idle-footprint,
or connection-churn acceptance gate.

Each fresh JVM starts a server and warms every connection before a synchronized
measurement. Output is one JSON record per process. Latencies are individual
request/echo round trips. CPU and allocated bytes cover the entire JVM, including
the client and fixture; these are identical between variants. Total allocation
is `null` when the JVM does not expose that counter. Do not interpret `null` as
zero or a passed allocation gate. Setup latency is recorded separately and is
not warmed. Teardown is intentionally excluded: baseline physical closure is
broken, so counting its teardown as equivalent completed work would mislead.

The fixture also records JVM compilation-time totals before/after measurement
and their delta, plus aggregate GC collection-count/time deltas. These counters
bracket the measured window; their snapshot allocations stay outside the
allocation counter interval. Unsupported counters are `null`. Compilation time
has millisecond resolution: a nonzero delta establishes ongoing compilation,
whereas zero alone does not establish that every relevant method is fully warmed.
Use these diagnostics to compare warmup lengths with unchanged product code and
preserve earlier raw records; do not silently discard blocks that include GC/JIT.
Warmup and measurement call the same `runBatch` loop, including both clock reads
and sample-array writes. Earlier records produced with separate untimed warmup
and timed measurement loops do not establish that the measured loop was warmed;
retain them separately when evaluating this corrected fixture.

Compile in each checkout using its pinned Nix flake. Add the common source and
exactly one factory via session-local sbt settings; no build-file edits:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt -Dsbt.server.autostart=false \
  'set standalone / Test / unmanagedSources ++= Seq(file("misc/websocket-performance/StandaloneTransportBenchmark.scala"), file("misc/websocket-performance/candidate/WebSocketBenchmarkResponse.scala"))' \
  'standalone/Test/compile' \
  'export standalone/Test/fullClasspath' > /tmp/spoonbill-ws-candidate.compile.log
rg '^/.*target/.*:' /tmp/spoonbill-ws-candidate.compile.log > /tmp/spoonbill-ws-candidate.classpath
```

Copy this fixture directory unchanged into the baseline export before compiling.
For the baseline export, run the same command from `/tmp/spoonbill-ws-baseline`,
replace `candidate/WebSocketBenchmarkResponse.scala` with
`baseline/WebSocketBenchmarkResponse.scala`, and use baseline log/classpath names.
Check that the classpath file contains exactly one line. Record the source commit, dirty diff hash,
fixture hashes, Java version, flake lock hash and JVM options alongside raw data.

Run Java through the same Nix environment with matching JVM options:

```sh
nix develop --no-write-lock-file --command sh -c \
  'java -Xms512m -Xmx512m -cp "$(cat /tmp/spoonbill-ws-candidate.classpath)" spoonbill.performance.StandaloneTransportBenchmark candidate SOURCE_ID BLOCK_ID 1 128 5000 1000'
```

Arguments are variant, immutable source identity, block ID, connections, payload
bytes, warmup messages per connection, measured messages per connection. Use
connections `1` and `8`, and payloads `128` and `4096`. Increase warmup/measurement
counts if JIT or scheduling noise dominates. Run independent balanced blocks
(for example baseline/candidate/candidate/baseline), plus baseline/baseline
calibration, before drawing conclusions. Keep all raw JSON records, including
failed runs and unsupported metrics. No measurements are supplied by this file.

The entire block is bounded by socket timeouts and 120-second warmup/measurement
deadlines. Dedicated client workers and the asynchronous channel group close in
`finally`, including for baseline connections that cannot close themselves.

`run.mjs` automates independent JVM pairs with seeded, balanced ordering and
records artifact-content, fixture and flake hashes. Its optional final argument
`core` selects the guarded-session fixture (10,000 warmup and 5,000 measured
operations). Transport runs warm 40,000 messages across the configured connections
and measure 10,000 per connection. Both phases execute the same timed batch loop.
Use `comparison` and then `calibration` modes with unchanged compiled artifacts.
`analyze.mjs comparison.jsonl calibration.jsonl report.json` checks provenance
and completed work and produces conservative paired-block bootstrap intervals.
It reports inconclusive evidence separately and introduces no slowdown allowance.
