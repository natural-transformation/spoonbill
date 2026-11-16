# Extension contention and GC evidence

This is a standalone JVM comparison of the actual `ExtensionRuntime`, compiled
from main and the pilot against the same fixture. It supplies the pilot review
with sustained concurrent-writer, backlog, allocation and GC observations. It
is a synthetic runtime workload, not an HTTP/browser end-to-end measurement or
a prediction of an application's throughput.

The [2026-11-16 evidence](evidence/2026-11-16/) retains all 24 runtime rows, all
nine retry diagnostics, the summary, and source/JFR hashes. The analyzer also
accepts that directory's combined `trials.jsonl` file. Raw JFR recordings remain
at the path in `provenance.json`. Measurements ran on a shared developer machine;
`ActiveProcessorCount` is a JVM hint, not CPU affinity.

Each fresh JVM uses G1, a 256 MiB heap and `ActiveProcessorCount=8`. Each trial
warms for five seconds, measures five seconds of writing, then drains all admitted
notifications before stopping. Three paired trials per scenario alternate order.
Every callback checks its writer's sequence; the run fails if a callback is
missing, duplicated or reordered. The final callback of a notification releases
a permit, bounding the number of in-flight notifications per view. Closing a
socket cannot hide pending callbacks in this fixture.

| Scenario | Views | Writers | Extensions/view | Window/view | CPU work/callback |
| --- | ---: | ---: | ---: | ---: | ---: |
| isolated | 16 | 16 | 3 | 32 | 1 microsecond |
| contended | 1 | 4 | 3 | 1,024 | none |
| extensions | 1 | 8 | 8 | 2,048 | 1 microsecond |
| backlog | 1 | 4 | 3 | 8,192 | 2 microseconds |

The deliberate callback work is a bounded spin, rather than a scheduler sleep.
These cells include independent-view traffic and upper-bound same-view stress;
normal application serialization may admit less contention. Results should
remain separate by workload, not averaged into a single performance score.

The JDK 21 total thread-allocation counter includes all JVM threads and includes
fixture overhead, so it is not an allocation-site profile. JFR `GCPhasePause`
events supply stop-the-world pause durations within the measurement window.
Delivery latency runs from `state` invocation to the final extension callback;
admission latency measures the `state` call and excludes waiting for a permit.
Histograms report upper bounds with 1 microsecond buckets below 10 ms and
100 microsecond buckets thereafter. Overflow is explicitly rejected by the
analyzer. Throughput includes draining the admitted backlog. Teardown is outside
the resource-measurement window but must complete successfully.

Run from the pilot checkout, using its repository-local flake:

```sh
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh \
  /path/to/main /tmp/extension-evidence/main-classpath.txt
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh \
  /path/to/pilot /tmp/extension-evidence/pilot-classpath.txt
nix develop --no-write-lock-file --command bash misc/extension-contention/run.sh \
  /tmp/extension-evidence/main-classpath.txt \
  /tmp/extension-evidence/pilot-classpath.txt /tmp/extension-evidence/comparison
nix develop --no-write-lock-file --command node misc/extension-contention/analyze.mjs \
  /tmp/extension-evidence/comparison
```

`prepare.sh` adds fixture sources with temporary sbt settings, without changing
build files or either runtime. Do not recompile either checkout during a run:
its exported classpath refers to those directories. Compilation errors stop
preparation. Output directories must be new so failed or unfavorable trials are
preserved. The analyzer requires every trial and reports median/range without a
statistical-significance claim. Do not run other builds during collection.

`StateCellRetryBenchmark` is a separate diagnostic available on the pilot
classpath. It runs the real `StateCell.modify` and `ExtensionMachine.step` with
one consumer and bounded producers. Each thread counts executions of its change
function; attempts minus successful calls is the number of CAS retries. This
intentional measurement side effect does not affect transition results. It
omits handlers, the runtime's handler table and effect scheduling, so its retries
must not be claimed as counts from the runtime comparison above. Timing from
this instrumented diagnostic is not a latency result.

```sh
nix develop --no-write-lock-file --command bash -c '
  java -Xms256m -Xmx256m -XX:+UseG1GC -XX:ActiveProcessorCount=8 \
    -cp "$(cat /tmp/extension-evidence/pilot-classpath.txt)" \
    spoonbill.internal.StateCellRetryBenchmark 4 3 1024 25000
'
```

Arguments are writers, extensions, maximum waiting notifications and writes per
writer. Report each configuration separately and retain every repeat. Baseline
uses a monitor for admission, so it has no corresponding CAS-retry count.
