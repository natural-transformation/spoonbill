# Core guarded-session lifecycle benchmark

`CoreGuardedSessionBenchmark.scala` is compiled unchanged against baseline and
candidate, with exactly one version of `CoreResponseAccess.scala`. Every operation
initializes ephemeral state, creates a real guarded session through
`SessionsService` and `MessagingService`, consumes its first live output frame,
and completes disposal. Baseline closes application input; candidate executes the
explicit response finalizer. Both await the guard-close signal and assert the
application is absent and guard/input counts are zero before continuing. Reusing
one view identifier also exercises attachment retirement on the next operation.

Both variants use the same direct execution context. This makes baseline's
terminal cleanup chain complete synchronously when EOF is signaled; assertions,
not sleeps or polling, establish completed work. This is a serial in-process
microbenchmark. It does not measure concurrent scheduling, browser behavior,
network handshake, timer cancellation cost in HTTP adapters, or idle-connection
memory. The retired-state cache is bounded to one entry. It does not claim total
heap retention is zero.

After checks and output complete, the executable exits explicitly: the existing
library-wide Scheduler owns a non-daemon timer with no public shutdown operation.
Failures exit nonzero. This does not replace the per-operation cleanup assertions.

Each fresh JVM runs warmup and measured operations and emits one JSON block with
individual-operation latency percentiles, whole-process CPU, JVM-wide allocated
bytes when supported, per-operation averages and resource counters. Unsupported
allocation metrics are `null`, not zero. Fixture assertions and measurement
overhead are identical across variants. The block has a 120-second bound, with
five-second operation waits. No benchmark output is supplied by these sources.

Compile through each checkout's repository Nix flake; no build-file edits:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt -Dsbt.server.autostart=false \
  'set spoonbill / Test / unmanagedSources ++= Seq(file("misc/websocket-performance/CoreGuardedSessionBenchmark.scala"), file("misc/websocket-performance/candidate/CoreResponseAccess.scala"))' \
  'spoonbill/Test/compile' \
  'export spoonbill/Test/fullClasspath'
```

Copy this fixture directory unchanged into the baseline export. For baseline use
that checkout and the `baseline/CoreResponseAccess.scala` factory.
Save the exported classpath, source/artifact identities, fixture hashes, flake
lock hash, JVM settings and Java version with the raw results. Run using matching
JVM settings through Nix:

```sh
nix develop --no-write-lock-file --command sh -c \
  'java -Xms512m -Xmx512m -cp "$(cat /tmp/spoonbill-core-candidate.classpath)" spoonbill.performance.CoreGuardedSessionBenchmark candidate SOURCE_ID BLOCK_ID 200 1000'
```

Arguments are variant, source identity, block ID, warmup count and measured count.
Use independent balanced baseline/candidate blocks plus baseline/baseline
calibration. Increase counts if noise dominates; do not silently discard failed
blocks or infer a performance pass from uncalibrated candidate-only measurements.
