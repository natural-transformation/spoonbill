# Stream operator compatibility cost fixture

Use the [v3 fixture and version adapters](v3/README.md) for the current closed
Future-instance API. It compares original operators, the preceding implementation
and the factory-based API on one shared workload. The root-level v2 fixture and
scripts reproduce recorded historical revisions only: their `FutureEffect`
subclasses do not compile against the current API. Frozen fixtures, sources,
patches and evidence must not be overwritten or mixed between protocols.

[ADR 0002](../../docs/adr/0002-stream-routing-and-effect-instances.md) records the
selected stream contracts and typed interpreter design;
[ADR 0004](../../docs/adr/0004-runtime-performance-validation.md) records the
runtime performance validation policy. This fixture measures only public
behavior shared by the compared implementations:

| Cell | Measured round | Outside this comparison |
| --- | --- | --- |
| Hub | Two subscribers pull before one controlled value is published; both receive it. Capacity 128. | Slow subscribers, late subscription and backpressure policies. |
| Sort | Pull and publish the even rack, then the odd rack. | Wrong-rack demand, overflow and historical overwrite behavior. |
| Buffer | Pull and publish one zero-duration singleton batch, capacity 16. | Delayed batching and concurrent pending batches. |

The source has one pending Promise; it is not an always-ready source that lets a
version consume ahead. Each value and its order are checked. A Hub/sort round has
two deliveries; buffer has one. Source, validation and ordinary Future costs are
included, so these are not bare model-transition timings.

## Running and interpreting the fixture

Use each checkout's repository Nix flake and new isolated worktrees/classpaths.
Preparation selects the fixture as the effect test source for that sbt session;
it does not edit build files. The [v3 instructions](v3/README.md) describe
`prepare.sh CHECKOUT OUTPUT legacy|factory`, smoke/allocation controls and the
three-version runner. Compile the common body with exactly one version adapter.
Factory instances retain the built-in interpreter; a forwarding `Effect` wrapper
would select the generic reader and change what is measured.

To reproduce a recorded **v2** comparison, choose compatible revisions from its
provenance and use the historical scripts inside the managed environment:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file
bash misc/stream-operator-cost/prepare.sh /path/to/before /tmp/stream-cost/before.txt
bash misc/stream-operator-cost/prepare.sh /path/to/after /tmp/stream-cost/after.txt
bash misc/stream-operator-cost/run.sh /tmp/stream-cost/before.txt /tmp/stream-cost/after.txt /tmp/stream-cost/new-screen 3 2 2
node misc/stream-operator-cost/analyze.mjs /tmp/stream-cost/new-screen 3
```

Before timing, require valid smoke JSON, empty stderr, delivery/thread assertions,
complete teardown and the escaping-allocation control. Measurement uses one live
main thread and `getCurrentThreadAllocatedBytes`; timed reads and deliveries
assert that thread. Separate smoke instrumentation checks continuation placement.
The allocation control publishes 4,096 escaping 4,096-byte arrays and requires at
least 16 MiB counted allocation.

Setup and teardown are outside timing. Hub drives EOF through both live
subscribers before detaching. Sort/buffer await source cancellation and every
started child's completion; a cancellation acknowledgement alone does not prove
cleanup finished. Source pending-read, cancellation and issued/recorded/joined
start checks must pass. Failed controls invalidate a comparison.

Freeze source, fixture and classpath hashes before collection; verify them after.
Pause unrelated builds/profilers during collection and record host load. Keep all
rows, failed controls and ranges without selective reruns. Independent JVMs use
G1, a fixed 256 MiB heap and `ActiveProcessorCount=8` (not CPU affinity). The
predeclared median guides are at least 0.95 throughput and at most 1.10 allocation
relative to each reference. Three-trial medians are not confidence intervals;
this screen measures neither p99 nor whole-application performance, and cannot
establish semantic equivalence with the historical dropping operators.

## Cost and dispatch lessons

- Full routing-state copies, interpreter records and Future promise/continuation
  machinery all contributed to the initial regression. Removing one model result
  object was insufficient. Allocation attribution separates object classes from
  nearest-source origins; these overlapping views must not be added or treated as
  an exact byte census.
- Lower allocation did not ensure acceptable sort throughput. CPU profiling
  identified command dispatch and registration as hot regions. No observed
  monitor-blocking events does not mean uncontended monitor operations are free;
  a sampled call/return source line does not isolate exception-handling cost.
- Direct first-command reservation and typed callback handling remove ordinary
  dispatch/conversion work. They still need a single release/claim authority,
  queued-work visibility, cleanup pauses and reentrant FIFO behavior. The retained
  design uses one atomic dispatch state and one published lifecycle `Control`;
  performance alone did not justify split ownership flags or reflection.
- Exact compiled units and runtime selection matter to JIT behavior. Keep the
  logical workload and controls unchanged across version adapters, and measure a
  new source variant independently instead of comparing historical medians.

Profiles are diagnostic, separate from acceptance timing. The attribution tools
select measured `Work.run` stacks; the historical v2 measured call site is line
217, with warmup at 216. Recheck source locations for another fixture. Raw JFRs
stay outside the repository because they can contain process metadata. Export with
sufficient stack depth before running
[`attribute-allocations.mjs`](attribute-allocations.mjs) or
[`attribute-cpu.mjs`](attribute-cpu.mjs).

## Retained evidence

| Evidence | Purpose |
| --- | --- |
| [v3 comparison](v3/README.md), [API summary and rows](v3/evidence/api-v2/) | Current API comparison against both references; all guides passed, with sort's observed median 3.52% below the preceding implementation. |
| [Canonical-instance runtime comparison](../effect-instance-runtime-cost/README.md) | Separate uninstrumented default-instance check; current-implementation reference, not original-main aggregate acceptance. |
| [Compact 7 checkpoint](evidence/compact7-v2/) | Passing v2 cost screen (`summary.json`, `trials.jsonl`, `provenance.json`) for the retained typed single-value dispatch core. |

Intermediate rejected screens (`compact` through `compact6`), smoke runs and
duplicated source snapshots were pruned before merge.
