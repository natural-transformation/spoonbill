# Extension delivery performance experiments that did not ship

**PR #47 skips redundant wake-up attempts on busy lanes and reuses the immutable notification for each handler’s last received state.** This note records the later experiments that stayed out of the code under review. All candidates included the correctness fixes in Spoonbill 11.0.0. Their reference was PR #47 at `b6f11ca0`, except where the released version is explicitly named. [PR #47](https://github.com/natural-transformation/spoonbill/pull/47) · [Measurements of the two proposed changes](../misc/extension-contention/README.md) · [Trial records and candidate diffs](../misc/extension-contention/evidence/2026-11-25-failed-experiments.json)

## Decisions

| Experiment | What happened | Decision |
| --- | --- | --- |
| Read the completion state before its atomic exchange | For immediate callbacks, throughput rose 0.38% and delivery p99 rose from 0.518 to 0.5615 ms. The required improvement was 2% more throughput or 5% lower p99, repeated in at least four of six pairs. | Rejected at the first comparison. Skipping an atomic operation did not improve the workload it targeted. |
| Combine a lane item's callback and atomic state | With three handlers, allocation fell 64.65 B per notification and throughput rose 6.31%. Four-writer p99 rose from 0.437 to 0.499 ms, 114.19% of the reference against a 110% ceiling. | Rejected. The eight-handler saving of 144.10 B also could not compensate for the latency miss. |
| Store the admitted check directly, using a shared sentinel for absence | Three-handler allocation fell 15.84 B per notification. With successful guards and alternating state and message events, allocation fell 15.87 B, throughput fell 4.79%, and p99 rose 6.03%. | Passed the numerical budgets. Deferred on engineering grounds. The guarded workload cleared the throughput floor by only 0.21 percentage points, and four of six pairs favored PR #47 on each metric. Three pairs favored it on both. |
| Match on `Left` and `Right` explicitly in the lane | Replacing `result.left.foreach` reduced neither allocation nor latency for immediate callbacks. Throughput fell 2.53% and p99 rose from 0.466 to 0.526 ms, 112.88% of the reference. | Rejected. A projection appearing in an allocation profile did not establish a repeatable saving on this path. |
| Skip flattening the exact `Future.unit` singleton in the default Future interpreter | The first implementation failed correctness checks. The corrected version substantially improved the intended path, but the separate fallback comparison missed its throughput floor. | Rejected for this PR. The two versions and their different failure modes are detailed below. |

## What the callback and allocation experiments established

The lane must decide whether a completion resumes delivery or the current worker continues. The two handoff experiments preserved the per-item identity and the existing scheduling decisions. A size probe forced the actual completion callback to materialize. The original callback measured 24 B and its atomic state another 16 B. The combined object measured 24 B. That explained the allocation hypothesis, while the four-writer p99 result determined the decision.

An earlier design made the callback inherit from `AtomicInteger`. That would expose mutable handoff operations to a custom effect receiving the callback, so it was discarded before timing. The measured prototype used a private volatile field and a class-wide updater. It also introduced a packaging issue. The Java helper compiled to classfile major 65, while released `SerialLane` used major 52. Any future attempt needs to compile and measure the actual distributable binary at the intended target.

The direct admission token illustrates a different tradeoff. The guarded workload checked one guard per notification, callback kind and identity, each writer's sequence, and the last state passed to every handler at teardown. PR #47 delivered 2.915 million notifications/s at 0.655 ms p99. Adding the token reduced that to 2.775 million/s at 0.6945 ms. Released 11.0.0 delivered 2.459 million/s at 0.902 ms in the same collection. The token remains a possible allocation-sensitive option. For the general runtime, the evidence favors the version in PR #47.

## The default-Future shortcut

Allocation samples repeatedly showed `Promise.flatMap` under `Effect.delayAsync`. The candidate changed how the lane interpreter observes a completed step. Admission, callback eligibility and teardown decisions remained in the pure state machine. Public `delayAsync` retained its existing behavior. Only the default uninstrumented Future instance and the exact `Future.unit` singleton could take the shortcut. Instrumented instances, custom effects, pending Futures and other completed Futures used the original observation path.

The first version used a by-name parameter across a Scala GADT match. Its bytecode evaluated the thunk and then attempted to cast the resulting Promise to `Function0`. **75 of 156 core tests failed**, including guarded delivery. It received no timing results. An explicit `() => F[Unit]` fixed the boundary. The corrected version passed 180 extension, lane and adapter tests plus 209 effect-module tests, including error handling, rejected startup, callback thread and instrumentation coverage.

| Corrected candidate, relative to PR #47 | Throughput | Delivery p99 | Allocation per notification |
| --- | ---: | ---: | ---: |
| Four writers, independent canonical confirmation | 121.21% | 77.55% | 65.31% |
| Guarded mixed state and message callbacks | 102.84% | 86.83% | 82.34% |
| Guarded precompleted fallback, original screen | 101.57% | 96.25% | 100.00% |
| Guarded precompleted fallback, independent six-pair check | **96.78%** | 98.83% | 99.98% |

The fallback used a completed Future distinct from `Future.unit`. Both fallback collections had to retain at least **97% throughput**, with p99 at most 107% and allocation at most 110% of the reference. A 0.40-second bytecode inspection overlapped the end of one original fallback trial. Before inspecting its results, an independent check was specified. All original trials were retained. The independent check delivered 2.799 million notifications/s against 2.892 million/s. Its ranges overlapped and two of six pairs favored the candidate. The near miss settles the predefined decision. Its precise size and cause remain uncertain.

The native adapters were checked separately with three pairs apiece. Cats Effect 3 retained **99.81% throughput** with p99 at **101.06%** of the reference. ZIO 2 retained **99.88% throughput** with p99 at **95.65%**. Both passed their 95% throughput and 110% p99 limits and completed the checks for callback counts, order, backlog drain and teardown. These results support the generic interpreter path on the tested adapters. The missed Future fallback requirement still applies.

## What to carry into the next investigation

- Keep the released correctness fixes on both sides. Run the machine, lifecycle, refusal and teardown tests before timing any new representation.
- Match the workload to the change. Immediate, submitted and non-singleton completed Futures exercised different paths. Successful guards and alternating messages exposed a tradeoff that state-only tests would have missed.
- Record the thresholds and comparison order in advance. Report throughput, p99 and allocation separately for each workload. Preserve an unfavourable original run when adding a separate check.
- Keep bytecode inspection, builds, profiling and evidence packaging outside the timing window. JVM allocation samples identify promising sites. Whole-runtime comparisons and classfile checks determine whether a change is suitable for review.

The accompanying records contain 90 JVM trials from the handoff and allocation investigations, 84 Future-observation trials, and 12 measured native-adapter trials. Four short adapter smoke trials were kept separate. These were diagnostic measurements on one host, with independent JVMs, fixed heap and processor settings, and a fresh warmup for every trial. [Trial records and thresholds](../misc/extension-contention/evidence/2026-11-25-failed-experiments.json) · [Runtime measurement policy](adr/0004-runtime-performance-validation.md)
