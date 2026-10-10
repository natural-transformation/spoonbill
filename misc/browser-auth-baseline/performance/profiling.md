# Profiling capabilities and remaining measurement blockers

The repository's `.#profiling` shell is separate from latency collection. It
combines the pinned JDK/Node/PostgreSQL/browser environments with GNU `time`;
Linux additionally supplies `heaptrack`, `procps` and `util-linux`. JDK 21 already
includes `jcmd` and `jfr`. No host installation, downloaded browser or alternative
package manager is used. Tool discovery accepts only executable Nix store paths.

Validation has run on **aarch64-darwin** and an **aarch64-linux NixOS UTM guest**.
Chromium/WebKit retained-heap probes passed on both; Linux heaptrack/procfs probes
also passed. See [local UTM validation](utm-validation.md) for scope and results.

| Quantity | Implementation and precise scope | Current evidence |
| --- | --- | --- |
| Retained JavaScript heap | Full-GC heap snapshot; sum of engine-reported node self-size estimates for one inspected page | Actual Chromium and WebKit calibration passed on Darwin/arm64 and Linux/arm64 |
| Experimental cumulative V8 trace bytes | Stock CDP allocation trace-tree own-node counters for one inspected isolate | Chromium 141.0.7390.37 synthetic persistent/reclaimed-object calibration passed; complete scope unvalidated |
| Cumulative JavaScript allocated bytes | All relevant allocations, including objects freed before the final observation | **Acceptance unsupported; null. Stock V8 tracking is a concrete narrower candidate; WebKit and full coverage remain unresolved.** |
| Native allocator requested bytes | `heaptrack_print -H` histogram sizes × allocation counts; intercepted malloc-family calls | Linux/arm64 physical probe captured the exact synthetic 64 MiB allocation request |
| One owned process's RSS high water | Linux `/proc/<pid>/status` VmHWM, separately from current `smaps_rollup` RSS/PSS | Linux/arm64 physical probe observed a child with 64 MiB of touched memory |
| Simultaneous whole-browser process-tree peak RSS | Browser parent, renderer/WebContent, GPU and workers within one defined ownership scope | **Unsupported; null. Per-process maxima are not silently added.** |
| JVM heap/native diagnostics | Existing JDK `jfr`/`jcmd`; native tracking requires explicit JVM startup flags | Tool availability is checked; each workload's recording/configuration still requires validation |

`profiling-capabilities.mjs` reports these distinctions and deliberately exits 2
with acceptance `inconclusive`. Installed tools and capability checks are not a
completed benchmark or permission to replace missing observations with zero.

## Validated retained JavaScript heap sensors

Chromium uses public Playwright CDP sessions. WebKit's inspector supports heap
snapshots, but Playwright's public CDP API supports only Chromium, so the WebKit
collector uses a **measurement-only private bridge** to its local inspector
session. The bridge checks Playwright 1.56.1 and SHA-256 identities for its
in-process factory, client owner, server page, WebKit page and browser-revision
manifest. A version/source change fails closed until reviewed and probed. No
bridge or calibration script is delivered to the reference application.
[Playwright CDP documentation](https://playwright.dev/docs/api/class-cdpsession),
[pinned in-process bridge](https://github.com/microsoft/playwright/blob/v1.56.1/packages/playwright-core/src/inProcessFactory.ts).

The collector owns WebKit's Heap domain with `enable`, `gc`, `snapshot`, then
`disable` in a finalizer. Disabling clears the inspector's snapshot history;
leaving that profiler state around contaminated the initial reclamation probe.
The test also ends the evaluation's browser job before each measurement using
the same fixed event-loop barrier. It does not repeatedly sample until a
favorable result appears. Chromium collection detaches its CDP session in a
finalizer. Snapshot data is summarized in memory, never logged or written; only
synthetic pages/accounts may be profiled because snapshot strings can contain
page data.
[WebKit Heap agent implementation](https://github.com/WebKit/WebKit/blob/main/Source/JavaScriptCore/inspector/agents/InspectorHeapAgent.cpp).

The final native calibration held 100,000 synthetic objects, then removed the
reference and collected again:

| Engine | Before | Objects retained | Reference removed and collected |
| --- | ---: | ---: | ---: |
| Chromium 141.0.7390.37 | 515,625 B | 6,939,509 B | 580,341 B |
| WebKit 26.0 | 551,695 B | 9,051,868 B | 567,406 B |

These numbers establish that the sensors observe retention/reclamation. They
are not cross-engine comparisons, reference-application measurements or proof
of allocator coverage. WebKit sums Inspector v3 node size estimates; Chromium
sums V8 node `self_size`. The full acceptance protocol must freeze those scopes
and account for additional pages/workers and browser processes explicitly.

Run the native probe through the combined shell:

```sh
nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/browser-heap.native-tests.mjs
```

## Native memory and allocator scope

Heaptrack observes allocator calls and their native stack traces. Its own
documentation warns that custom pools bypassing malloc require annotations.
JavaScriptCore's object allocation within such pools is not equivalent to
malloc traffic. The histogram parser therefore returns
`nativeAllocatorRequestedBytes` and leaves `logicalJavaScriptAllocatedBytes`
null. No native allocator count fills `browserAllocatedBytesPerOperation`.
[Heaptrack scope and pool limitation](https://github.com/KDE/heaptrack#comparison-to-valgrinds-massif).

Linux RSS collection accepts only a live direct `ChildProcess` owned by the
collector. It checks parent PID and start-time identity before and after reading
procfs so PID reuse cannot redirect measurement. VmHWM is a kernel-reported
single-process high-water counter; smaps_rollup supplies a separate current
RSS/PSS snapshot. Those figures include the process's mapped shared pages and
heap, not just non-JavaScript/native allocations. Sampling current RSS cannot
establish an unobserved peak, and sums of different processes' lifetime peaks
cannot establish a simultaneous browser peak.
[Linux procfs documentation](https://www.kernel.org/doc/html/latest/filesystems/proc.html).

GNU time's `%M` also describes process resource accounting. It is not a substitute
for a browser process tree. A dedicated Linux cgroup could provide
`memory.peak`, but that counter includes charged anonymous/file/kernel memory;
it is a different scope from aggregate RSS, and requires a delegated owned
cgroup and a validated lifecycle. This implementation does not invent that
equivalence or modify system cgroups.
[GNU time memory fields](https://www.gnu.org/software/time/manual/html_node/Memory-Resources.html),
[Linux cgroup memory accounting](https://docs.kernel.org/admin-guide/cgroup-v2.html).

The explicit Linux check fails on another platform or missing tool instead of
skipping. It verifies declared Nix tools, samples a live child with 64 MiB of
touched memory, and asks heaptrack to capture a known 64 MiB native allocation:

```sh
nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/native-memory.linux-tests.mjs \
  misc/browser-auth-baseline/performance/browser-heap.native-tests.mjs
```

That command is suitable for the focused Linux CI capability job. It must not
be reported as having run locally on Darwin. Neither test substitutes for the
application workload matrix or physical collector calibration on its eventual
benchmark host.

## Concrete proposal for cumulative JavaScript allocation

Stock Chromium's CDP supports
`HeapProfiler.startTrackingHeapObjects({trackAllocations: true})`. V8's allocation
tracker accumulates allocation count/size in trace nodes, including allocations
whose objects later die; its snapshot serializer exposes those fields in
`trace_tree`. Sum each trace node's own size once, not live-node `self_size` or
heap-stat fragment sizes. This supplies a concrete **V8 GC-heap allocation**
candidate, not proof of complete browser allocation coverage.
[CDP protocol](https://github.com/ChromeDevTools/devtools-protocol/blob/master/json/js_protocol.json),
[allocation tracker](https://github.com/v8/v8/blob/main/src/profiler/allocation-tracker.cc),
[trace-tree serialization](https://github.com/v8/v8/blob/main/src/profiler/heap-snapshot-generator.cc).

`chromium-allocation.mjs` implements that experimental collector using public
Playwright CDP. Snapshot bytes, trace nodes/depth and asynchronous collection
duration are bounded; buffers/listeners are cleared and profiler/session release
is attempted on success, failure and timeout. Snapshot strings are neither logged
nor persisted. The caller supplies only synthetic workload data and owns browser
disposal after timeout: aborting profiling cannot prove arbitrary page work stopped.
The collector always leaves `browserAllocatedBytesPerOperation` null.

The first Darwin/arm64 native calibration passed alongside eight unit tests:
an empty interval recorded 0 B; 20,000 retained objects recorded 862,692 B;
40,000 short-lived objects recorded 1,768,936 B across 40,393 tracked allocations.
A WeakRef confirmed collection before the final snapshot. This establishes an
allocation signal surviving GC, not exhaustive coverage or benchmark acceptance.

```sh
nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/chromium-allocation.test.mjs \
  misc/browser-auth-baseline/performance/chromium-allocation.native-tests.mjs
```

Playwright 1.56.1 pins Chromium 141.0.7390.37; that Chromium tag's `DEPS` selects
V8 `ad8af0fc661d278e87627fcaa3a7cf795ee80dd8`. The reviewed mainline mechanism must
still be audited at that exact revision and calibrated against the packaged
binary; the research fetch of the pinned implementation failed, so this document
does not claim that audit complete.
[Playwright browser manifest](https://github.com/microsoft/playwright/blob/v1.56.1/packages/playwright-core/browsers.json),
[Chromium dependency pin](https://github.com/chromium/chromium/blob/141.0.7390.37/DEPS),
[pinned V8 tracker audit target](https://github.com/v8/v8/blob/ad8af0fc661d278e87627fcaa3a7cf795ee80dd8/src/profiler/allocation-tracker.cc).

Calibrate persistent and short-lived objects with GC before observation, then
check JIT paths, counter width/overflow, control-command overhead and every
owned isolate/worker. Tracking changes allocation machinery, so use identical
instrumentation for both memory variants and separate stock-browser latency
runs. Do not assume these counters include ArrayBuffer/external-string backing
stores, native DOM/layout allocations or renderer/worker processes. Chromium's
sampling profiler can retain samples of collected objects, but its Poisson
estimates are a different sensor and do not establish byte-exact coverage.
[V8 tracking setup](https://github.com/v8/v8/blob/main/src/heap/heap.cc),
[CDP sampling flags](https://chromedevtools.github.io/devtools-protocol/tot/HeapProfiler/#method-startSampling).

Stock WebKit's Heap protocol emits start/stop snapshots and GC timings, not a
cumulative allocation counter. Its Memory domain reports periodic category
sizes, not allocation traffic. Neither recovers all objects freed between
observations. Playwright's WebKit base revision is
`db897909f7c31d9b38793374c66427b6a4cb3dd3`; the pinned Heap protocol confirms this
gap. The private Playwright bridge transports existing commands, without adding
engine counters.
[WebKit source pin](https://github.com/microsoft/playwright/blob/v1.56.1/browser_patches/webkit/UPSTREAM_CONFIG.sh),
[pinned Heap protocol](https://github.com/WebKit/WebKit/blob/db897909f7c31d9b38793374c66427b6a4cb3dd3/Source/JavaScriptCore/inspector/protocol/Heap.json),
[Memory agent](https://github.com/WebKit/WebKit/blob/main/Source/WebCore/inspector/agents/InspectorMemoryAgent.cpp).

For WebKit, audit existing GC-cycle allocation accounting before proposing a
full instrumentation rewrite. Those internal counters reset, batch cell
reporting and include estimated extra-memory accounting; they are not a stock
inspector API or an established substitute. A narrowly scoped engine exposure
may be less implementation work than instrumenting every JIT path, but still
needs a pinned build, coverage proof and cost review.
[JSC allocation batches](https://github.com/WebKit/WebKit/blob/main/Source/JavaScriptCore/heap/LocalAllocator.cpp),
[GC accounting and reset](https://github.com/WebKit/WebKit/blob/main/Source/JavaScriptCore/heap/Heap.cpp).

The least-cost next step is the stock Chromium calibration plus the WebKit
counter audit. No custom engine build has been started or costed. Freeze a
comparable scope, including specified backing stores and process ownership,
before promoting either sensor to the required metric. Allocation acceptance
stays unsupported/null, and Phase 0 remains blocked; retained heap, RSS, malloc
traffic or one engine's partial coverage cannot replace the missing result.

## Lighter macOS investigation

Linux is not required for the next counter experiment. WebKit's JSCOnly CMake
port disables WebCore, WebKit and WebInspectorUI. A Nix-managed engine-only
prototype on aarch64-darwin could validate allocator accounting before attempting
browser integration. The installed Playwright WebKit 2215 bundle has a separate
`JavaScriptCore.framework`; its metadata identifies macOS 14.5 SDK/Xcode 15.4.
A framework-only replacement is a possible later investigation, subject to
matching source, private ABI/layout, build configuration and code signing. It is
not an established drop-in replacement or a full-browser measurement yet.
[JSCOnly configuration](https://github.com/WebKit/WebKit/blob/main/Source/cmake/OptionsJSCOnly.cmake),
[WebKit ports](https://docs.webkit.org/Ports/Introduction.html).

Safari's JavaScript Allocations timeline periodically collects heap snapshots;
it cannot recover all transient allocations. Instruments Allocations observes
native heap/VM requests, while pooled JavaScript cells can be reused without a
native allocation. These are useful diagnostics with different coverage.
[Web Inspector timelines](https://webkit.org/web-inspector/timelines-tab/),
[Apple memory tools](https://developer.apple.com/documentation/xcode/gathering-information-about-memory-use).

The Mac-only exact-source audit attempted one bounded Nix builtin prefetch of
the official pinned WebKit archive. It returned HTTP 422, `Content creation is
blocked`, without downloading/unpacking source. No retry, engine build, VM clone
or host installation followed. Exact allocator coverage, Darwin dependencies,
build time and archive size remain unverified. The installed binary alone cannot
establish those source contracts. A reachable matching source checkout/archive is
needed before a prototype or its resource estimate can be made concrete.
