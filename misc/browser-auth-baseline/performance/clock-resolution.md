# Observable clock-step diagnostic

This standalone diagnostic samples Node `process.hrtime.bigint()`, JVM
`System.nanoTime()`, and Chromium/WebKit `performance.now()` through the pinned
Nix environment. It records consecutive observed deltas, including zeros, and
reports the minimum/maximum positive step, zero/negative/missing counts and
completion status. **A minimum positive step is not a hardware-resolution
measurement or an accepted sensor-resolution bound.** Every record has
`acceptanceEvidence: false`; hardware resolution stays null. No manifest or
measurement-resolution verification flag is populated.

```sh
nix develop .#profiling --no-write-lock-file --command node \
  misc/browser-auth-baseline/performance/clock-resolution.mjs \
  /absolute/path/to/NEW-clock-diagnostic.jsonl
```

The existing profiling shell already supplies the JDK, Node and native browsers;
no dependencies or application/runtime sources are changed. The JDK launches
`clock-resolution.java` directly using source-file mode. This helper remains
outside the system-under-test artifact. Its compilation precedes sampling and is
not a latency observation. The probe clears inherited Java option-injection
variables for this owned helper and records the actual JVM version.

Defaults are 2,000 warmup reads followed by 20,000 recorded consecutive deltas per
clock. The same loop performs both phases. Sampling checks a two-second wall-clock
budget every 256 iterations and always has a finite iteration bound, including
when the sampled clock never advances or wall time moves backward. Raw deltas
remain in their natural units: integer nanoseconds for Node/JVM and floating-point
milliseconds for browser clocks. No rounding hides browser floating-point
subtraction artifacts. Zero-only, missing, nonmonotonic, incomplete or timed-out
observations are inconclusive, even if a positive minimum is also present.

The Node/JVM and browser loop necessarily include function-call, loop and array
write overhead. JIT transitions, scheduling, privacy precision reduction/jitter,
and the chosen page context can all affect differences. Minimum observed steps
do not prove elapsed-time accuracy, timer granularity on other hosts, cross-clock
comparability or the resolution needed by any application measurement. No
statistical confidence claim is made.

Browsers use a fresh headless `about:blank` page and report its
`crossOriginIsolated` boolean. The public Playwright BrowserServer API supplies an
owned kill handle, so renderer evaluation deadlines do not rely on the Node
parent's process group. Browser launch is bounded to 15 seconds, connected
sampling work to 25 seconds and owned cleanup to five seconds. Java, which only
creates threads, is bounded to 30 seconds including source compilation and has a
three-second cleanup bound. A prepended SIGINT/SIGTERM listener synchronously
kills already acquired Java child/browser process-group handles before aborting
asynchronous waits. Existing application listeners remain installed, including
listeners that immediately exit. Such forced exit can prevent evidence
finalization; it is not claimed to produce a final report. Before a browser's
public launch handle is returned, launch cleanup remains Playwright's bounded
responsibility. SIGKILL runs no JavaScript cleanup or finalization. Normal
completion also kills each owned browser server before moving to the next engine.
The registry holds at most one active helper for this sequential probe. An owned
browser group remains subject to cleanup even if its leader has already exited
while descendants remain; tests exercise that case for both interruption signals.

The output is exclusively created with owner-only permissions. It contains source
hashes, bounded raw numeric deltas, summaries, fixed diagnostic codes and runtime
versions, never host paths, URLs or private browser data. JVM stderr is represented
by byte count and SHA-256 only; arbitrary tool/compiler diagnostics are not copied
into the structured evidence. A failed clock leaves a failure record while other
clock probes can still finish; missing final output cannot imply success. There
is no retry or overwriting a previous run.

Deterministic tests do not require running the native browsers:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/clock-resolution.test.mjs
```

The final-source 2026-10-10 native aarch64 macOS diagnostic completed all four
targets with 20,000 deltas each, without negative/missing readings or sampling
deadlines. The [privacy-safe summary](../reports/clock-resolution-macos-diagnostic.json)
records the exact raw-evidence/source hashes and runtime versions:

| Clock | Minimum positive observed delta | Positive deltas | Zero deltas |
| --- | --- | ---: | ---: |
| Node | 41 ns | 20,000 | 0 |
| JVM | 41 ns | 11,550 | 8,450 |
| Chromium | 0.09999990463256836 ms | 22 | 19,978 |
| WebKit | 1 ms | 2 | 19,998 |

These are observations from one bounded diagnostic, not reproducibility promises.
In particular, two positive WebKit steps do not establish a stable timer model.
Raw readings retain the browser floating-point values instead of rounding them
into a claimed resolution. No acceptance setting was changed from these values.
