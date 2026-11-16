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

## Pilot acceptance comparison

The [36-trial acceptance comparison](evidence/2026-11-16-pilot-acceptance/)
preserves all observations and variant identities. The lock candidate reduced
allocation but did not recover main's contention throughput, and some tail
latencies were worse. Neither candidate has been selected or its cost accepted.

The performance gate applies to the implementation in PR #34 itself. A draft
status is deliberate: neither correctness nor a favorable click-latency sample
accepts its measured allocation/contention cost. A merge requires an agreed
workload/performance budget met by the selected implementation, or explicit
acceptance of the measured cost for the intended workloads.

`variants/lock-preserving.patch` is a reviewable candidate, not an active runtime
change. It applies to pilot commit `15672bc07dd7e1c5cb1fe3a99d88729aea1ab117`, moves
waiting steps back to the lane's own queue, and updates machine state and handler
ownership under one monitor. It retains the current `SerialLane` implementation
in its queue-owned mode. Its pure-machine spec follows `Enqueue` commands instead
of `Next`; the interpreter, session, adapter and lane contract specs are unchanged.
The candidate passed 161 selected main-module tests, 12 cats-effect tests and 9
ZIO tests before measurement.

To reproduce without changing the active pilot:

```sh
git worktree add --detach /tmp/spoonbill-lock-candidate 15672bc07dd7e1c5cb1fe3a99d88729aea1ab117
git -C /tmp/spoonbill-lock-candidate apply --unidiff-zero /path/to/pilot/misc/extension-contention/variants/lock-preserving.patch
# In that worktree, through its flake, validate the candidate first:
cd /tmp/spoonbill-lock-candidate
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'spoonbill/testOnly *Extension*Spec *SerialLane*Spec' \
  'ce3/testOnly *Extension*Spec' 'zio2/testOnly *Extension*Spec'
```

The optional `--runtime-only` preparation mode compiles just the runtime benchmark
fixture. This avoids coupling measurements to a candidate's test representation
or including the CAS-only retry diagnostic. It does not replace the contract
validation above. Use the same preparation mode for all compared variants, and
do not compile or test in those worktrees while measurements run.

```sh
# From the pilot checkout, prepare main, the unchanged CAS pilot, and the candidate.
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh /path/to/main /tmp/acceptance/main.txt --runtime-only
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh /path/to/pilot /tmp/acceptance/cas.txt --runtime-only
nix develop --no-write-lock-file --command bash misc/extension-contention/prepare.sh /tmp/spoonbill-lock-candidate /tmp/acceptance/locked.txt --runtime-only
nix develop --no-write-lock-file --command bash misc/extension-contention/run.sh \
  /tmp/acceptance/main.txt /tmp/acceptance/cas.txt /tmp/acceptance/comparison 3 5 /tmp/acceptance/locked.txt
nix develop --no-write-lock-file --command node misc/extension-contention/analyze.mjs \
  /tmp/acceptance/comparison 3 baseline,cas,locked
```

Providing the final classpath argument selects a three-way comparison. The four
workloads, five-second warmup/measurement, full drain, heap/GC settings and
correctness assertions are unchanged. Each implementation occupies each run
position once across three rounds: main/CAS/lock, CAS/lock/main, lock/main/CAS.
The analyzer requires all 36 trials, checks equal workload/JVM settings, and
reports each candidate relative to the concurrently collected main baseline.
All medians and ranges remain observations rather than an acceptance decision.

The optional seventh `run.sh` argument names the third implementation; it
defaults to `locked` for compatibility. Use `optimized` when comparing a hot-path
change against main and the unchanged CAS pilot, then pass
`baseline,cas,optimized` to the analyzer. This changes labels only: the workloads,
JVM settings, rotation and correctness checks are identical.

The [direct-step comparison](evidence/2026-11-16-direct-steps/) measures this
optimization against main and the preceding CAS implementation. It improves
contended throughput by 19.7% and reduces allocation by 32.9%, while preserving
all delivery assertions. It does not establish parity with main or clear the
pilot merge gate. The two-second screening rows are separate from the full
five-second, 36-trial summary.

```sh
nix develop --no-write-lock-file --command bash misc/extension-contention/run.sh \
  /tmp/acceptance/main.txt /tmp/acceptance/cas.txt /tmp/acceptance/optimized-comparison \
  3 5 /tmp/acceptance/optimized.txt optimized
nix develop --no-write-lock-file --command node misc/extension-contention/analyze.mjs \
  /tmp/acceptance/optimized-comparison 3 baseline,cas,optimized
```
