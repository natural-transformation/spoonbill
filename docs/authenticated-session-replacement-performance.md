# Authenticated session replacement: bounded performance evidence

Protocol frozen on 2026-10-16, before collection. On 2026-10-17, runs 4 and 5
both completed all six lanes, fault exercises, resource assertions, and source
checks. **Run 4's memory ordinary-sign-in p95 budget breach remains valid
evidence. The explicitly user-authorized repeat, run 5, met both providers'
unchanged budgets and did not reproduce that tail.** This demonstrates
run-to-run variability, not a proven cause or proof that run 4 was erroneous.
Three earlier incomplete attempts and two successful isolated diagnostics also
remain preserved.

The subsequent API/Scala 3 review made the focused changes described in the
[review guide](authenticated-session-replacement-review-guide.md#review-follow-up-changes).
Those changes postdate these measurements. Run 4 and run 5 used identical
recorded source fingerprints; neither is a new performance measurement of the
later staged source. The review guide records its separate regression checks.

This is bounded implementation evidence for section 13 of the
[replacement plan](authenticated-session-replacement-plan.md), not a throughput
capacity claim or a statistically powered historical benchmark.

## Frozen protocol

- Baseline: local archived Git revision `7108eda8cef0d6d8ab838fe8c963f58cda810887`
  (HEAD when the protocol was frozen). Candidate: the uncommitted working tree,
  identified by a source-content SHA-256 recorded before compilation.
- One Chromium process and one example JVM at a time, loopback networking,
  sequential fresh browser contexts, synthetic `alice / password` account.
- Provider lanes: Future memory and exclusive-scope PostgreSQL. Each provider
  runs baseline ordinary sign-in, candidate ordinary sign-in, then candidate
  replacement. No overlapping builds or measurement lanes.
- Five unreported warmups followed by twenty measured samples per lane. JVM
  flags: `-Xms128m -Xmx512m -XX:ActiveProcessorCount=2`. JDK, PostgreSQL,
  Playwright, Chromium, GNU time, and compilation tools come from the repository
  Nix environments. PostgreSQL uses owned disposable storage.
- Ordinary sign-in starts immediately before the real **Begin sign-in** click
  and ends when the fresh authenticated application WebSocket is ready and its
  authenticated presentation is visible. It includes form interaction, proof
  verification, HTTP cookie delivery, activation, and rendering.
- Replacement starts immediately before **Apply security change**, after filling
  the password and regeneration choice, and ends at fresh successor application
  readiness. A separate timer covers **Reveal recovery codes once** through
  acknowledged sensitive presentation. Their sum is also reported. Neither
  plaintext nor credentials are written to measurement artifacts.
- Median is the midpoint of observations 10 and 11 after sorting. p95 is the
  nearest-rank observation 19 of 20. Every measured sample is retained; no
  outlier removal, adaptive warmup, or automatic rerun.
  A harness or correctness failure invalidates that attempt; any repair and new
  collection must be disclosed, with the failed artifacts retained. A slow
  sample or budget breach is not a reason to repeat collection.
- Frozen ordinary-path diagnostic budget: candidate median must be at most
  `baseline median × 1.25 + 25 ms`; candidate p95 must be at most
  `baseline p95 × 1.35 + 50 ms`. A breach is reported as observed and investigated;
  it does not justify weakening authorization or selecting a new threshold.
  The candidate deliberately renders additional account-security controls, so
  this comparison includes their DOM cost.

Outside the primary timing lanes, each candidate provider exercises a committed
replacement whose three live completion requests are intentionally blocked,
then recovers through the fresh-page bootstrap. The harness records bounded
recovery requests, a dedicated activation socket, repeated activated-cookie
recovery, six malformed/origin-denied requests, and rejection of a second
recovery-result consumption. These observations are correctness/count evidence,
not additional latency samples.

PostgreSQL retained rows are observed after all contexts close and scheduled
maintenance has run. They include ceremonies, sessions, active and retained
views, encrypted delivery material, consumed proofs, audit records, replacement
linkage, and encrypted host recovery results. Retained replay/history records
are expected; zero remaining delivery and consumed-result material is the
successful cleanup observation. Capacity-exhaustion and exact transaction/
callback behavior remain deterministic provider-test evidence rather than a
claim inferred from browser request counts.

A separate deterministic JDBC accounting test uses shared-view ownership and
the existing transaction/connection observers. It reports each begin, challenge
binding, preparation, frame admission, recovery, delivery, activation, successor
claim, completion lookup, replay denial, and maintenance boundary. Independent
durable-clock connections are reported as total connection opens minus outer
domain transactions; they are not folded into or hidden behind those domain
counts. The fixture also counts fresh host resolution calls and actual host
callback invocations. Preparation must run exactly one callback in two domain
transactions (admission and mutation), and a replay must run neither. These
shared-view counts are separate from the exclusive browser latency lanes.

GNU time records peak **whole-JVM RSS**. Explicit post-GC JVM heap snapshots
before the workload, after the workload, and after a further maintenance
interval provide process-level retention context. These are collected outside
the latency lane. Neither measurement attributes retained bytes to replacement
records, proves secret erasure, measures allocations, or establishes the peak
retained application object graph. Memory-provider record counts are supplied
by provider tests, since its public example exposes no introspection endpoint.

## Reproduction

```sh
nix develop .#profiling --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  bash scripts/measure-browser-replacement.sh \
  /private/tmp/spoonbill-replacement-performance-NEW
```

The destination must not already exist. The script archives the fixed baseline
into owned temporary storage, compiles baseline and candidate through their own
repository-local flakes, starts one server at a time, and preserves redacted
JSON results plus process/heap/resource logs in the destination. It removes its
temporary baseline checkout and stops owned servers; it makes no commits or
changes to user source files. `summary.json` reports the frozen-budget result.
Starting with run 3, lane boundaries also record Node `os` load averages,
logical CPU count, and free/total memory outside timed actions. They describe
host conditions without changing clocks, workloads, sample counts, or budgets.

## Results

Runs 4 and 5 supply complete collections. Run 4's memory p95 breach is retained;
run 5 was a separate repeat explicitly requested by the user after reporting
lower laptop CPU usage and suspecting measurement error. No threshold changed
and no earlier observation was discarded. No improvement is claimed for
replacement: the historical
ordinary API rejected the operation, so replacement is reported as absolute
cost. The portable [evidence artifact](authenticated-session-replacement-performance-evidence.json)
preserves protocols, every completed sample, resource observations, and
sanitized failure/interruption records; it contains no credentials, plaintext,
HTML, or server logs.

### Run 5: explicitly authorized repeat, both budgets met

The user requested this repeat with a fifteen-minute limit for the entire task.
It began at 07:03:31 UTC and completed with exit **0** before 07:09 UTC; a
780-second outer watchdog with a 20-second termination grace was never reached.
The collector, action deadlines, five warmups/twenty samples, lane order,
statistics, and budgets were unchanged. Source SHA-256 and recorded tool
versions were identical to run 4, and an independent post-run fingerprint
matched. This was explicit new user direction, not an automatic retry selected
to obtain a green result. Both complete results remain reported side by side.

| Provider / operation | Median (ms) | p95 (ms) | Ordinary budget |
| --- | ---: | ---: | --- |
| Memory baseline ordinary | 377.96 | 392.53 | Reference |
| Memory candidate ordinary | 379.09 | 397.39 | Both within limits |
| Memory ordinary limit | 497.45 | 579.91 | Same frozen formula |
| Memory replacement | 278.24 | 279.12 | Absolute cost |
| Memory result consumption | 56.46 | 59.06 | Absolute cost |
| Memory replacement + consumption | 334.64 | 351.19 | Absolute cost |
| JDBC baseline ordinary | 1,043.68 | 1,069.08 | Reference |
| JDBC candidate ordinary | 1,068.59 | 1,093.13 | Both within limits |
| JDBC ordinary limit | 1,329.60 | 1,493.26 | Same frozen formula |
| JDBC replacement | 968.87 | 995.23 | Absolute cost |
| JDBC result consumption | 808.69 | 817.74 | Absolute cost |
| JDBC replacement + consumption | 1,760.17 | 1,805.87 | Absolute cost |

Memory candidate ordinary sign-in was **1.13 ms / 0.30%** above baseline at the
median and **4.86 ms / 1.24%** above at p95. JDBC was **24.91 ms / 2.39%** above
at the median and **24.04 ms / 2.25%** above at p95. Each comparison uses its own
same-run baseline. Run 4's memory candidate p95 remains 4,336.59 ms against its
1,978.67 ms limit; it is not replaced by run 5's 397.39 ms observation.

Run 5's one-minute boundary load averages ranged from 11.41 to 13.65 on sixteen
logical CPUs, versus 15.28 to 37.13 in run 4. Specifically, memory baseline
boundaries were 13.65/12.67 and candidate boundaries 12.67/11.86, compared with
run 4's 37.13/32.46 and 30.47/27.21. These are lower observed load averages,
not direct CPU-utilization measurements. Reported free memory remained variable
(0.07–2.15 GB over the run); the memory candidate boundaries were 0.40/0.49 GB,
lower than run 4's 3.73/2.56 GB. No simple free-memory explanation is established.

The unchanged source produced materially different ordinary-memory timing
distributions across the two complete runs. That supports sensitivity to run
conditions. It does **not** isolate CPU load, GC, JIT, scheduling, browser
behavior, or implementation work as the cause, and it does not prove a
measurement error. No per-sample stage/CPU/allocation trace was collected. The
earlier source audit and its causal limits remain applicable.

| Run-5 lane | Peak whole-JVM RSS (KiB) | Post-GC heap before (KiB) | After workload (KiB) | After maintenance (KiB) |
| --- | ---: | ---: | ---: | ---: |
| Memory baseline ordinary | 243,984 | 11,106 | 13,306 | 13,080 |
| Memory candidate ordinary | 253,664 | 11,124 | 13,406 | 13,169 |
| Memory candidate replacement | 265,168 | 11,146 | 14,310 | 13,607 |
| JDBC baseline ordinary | 280,544 | 11,807 | 14,050 | 13,956 |
| JDBC candidate ordinary | 273,840 | 11,872 | 14,150 | 14,056 |
| JDBC candidate replacement | 304,544 | 11,794 | 14,532 | 14,422 |

Both providers completed all fifteen fault steps; all forty measured replacement
flows retained strict two-completion, zero-normal-recovery, single-sensitive-
frame assertions. All JDBC cleanup counts again showed zero active view owners,
delivery materials, and consumed encrypted recovery results. The retained
history/code-hash counts matched run 4. Owned JVM/browser/database processes,
baseline checkout, and disposable database storage were absent after cleanup;
the Nix-managed parent directory itself was not claimed to be removed.

The portable artifact now preserves **all 400 raw samples**, all five attempt
protocols/statuses, both successful diagnostics, resource snapshots, and exit/
cleanup records. Every sample was compared with its original file; statistics,
frozen limits, source integrity, correctness assertions, and portable-content
checks passed. The latest repeat passed its budgets; the historical run-4
memory p95 breach remains an observed exception rather than a discarded result.

The collector invocation was the reproduction command above with destination
`/private/tmp/spoonbill-replacement-performance-20261017-run5`, wrapped by the
user-requested outer watchdog. No further collection was started.

### Run 4: complete collection, memory p95 breach

The command exited **0** after five warmups and twenty measured samples in each
of six lanes, both fault exercises, all JDBC post-maintenance cleanup assertions,
and an unchanged pre/post source fingerprint. Exit 0 means collection and
correctness checks completed; the collector deliberately reports a budget
breach without using it to trigger a rerun. All 120 run-4 samples are retained,
alongside the 160 earlier samples and both diagnostic records.

| Provider / operation | Median (ms) | p95 (ms) | Frozen ordinary budget |
| --- | ---: | ---: | --- |
| Memory baseline ordinary | 1,123.13 | 1,428.65 | Reference |
| Memory candidate ordinary | 1,183.44 | 4,336.59 | Median within limit; **p95 breach** |
| Memory ordinary limit | 1,428.92 | 1,978.67 | Unchanged |
| Memory replacement | 278.15 | 294.90 | No historical replacement baseline |
| Memory result consumption | 58.17 | 110.86 | Absolute cost |
| Memory replacement + consumption | 336.50 | 388.62 | Absolute cost |
| JDBC baseline ordinary | 1,034.75 | 1,073.21 | Reference |
| JDBC candidate ordinary | 1,056.87 | 1,075.46 | Both within limits |
| JDBC ordinary limit | 1,318.44 | 1,498.83 | Unchanged |
| JDBC replacement | 960.07 | 980.36 | No historical replacement baseline |
| JDBC result consumption | 808.05 | 816.77 | Absolute cost |
| JDBC replacement + consumption | 1,769.32 | 1,795.43 | Absolute cost |

Run 4 used the same tool versions and JVM flags listed for run 3 below. Its
candidate source SHA-256 was
`5ca761939b0797b1144e1cbc53502c60507a401363110e36f3a170eeb6076d7b`;
the collector and an independent post-run calculation both matched it. The
only subsequent harness change from run 3 was detailed fault-phase reporting,
outside primary timing samples; no runtime repair was inferred from the
unreproduced timeout. This was a correctness retry after an invalid attempt,
not a retry chosen because of latency or a budget result.

The user requested collection under current host load. At lane boundaries,
one-minute load averages ranged from 15.28 to 37.13 on 16 logical CPUs, while
reported free memory ranged from 0.16 to 4.65 GB of 137.44 GB total. These are
boundary snapshots, not per-sample scheduling or available-memory measurements.

#### Investigation of the memory tail

Candidate p95 exceeded its frozen limit by **2,357.92 ms**, or **119.17%**
(2.192 times the limit). The two largest observations were adjacent measured
samples **5 and 6**, at 6,909.21 and 4,336.59 ms. The other eighteen observations
ranged from 930.35 to 1,371.90 ms. Both large observations remain included; no
alternative statistic or trimmed result is used for acceptance.

Every memory ordinary sample, baseline and candidate, had the same counts:
one completion POST, one completion frame, two application sockets, zero
recovery POSTs, zero activation-only sockets, and zero sensitive frames. Thus
an extra observed recovery round trip does not explain the tail. The shared
[`session.login` timing path](../misc/replacement-performance-tests.cjs) starts
before the real sign-in action and ends at fresh application readiness and
authenticated presentation; the candidate also renders account-security
controls, whose cost is included.

Candidate ordinary boundary load averages were 30.47 then 27.21, compared with
37.13 then 32.46 for baseline; reported free memory was 3.73 then 2.56 GB,
compared with 4.65 then 3.58 GB. The snapshots establish a loaded, changing host,
but do not establish that host load caused the adjacent slow samples. Candidate
whole-JVM CPU time was greater (18.36 s user / 1.40 s system versus 7.72 / 0.48),
while peak RSS was lower and final post-GC used heap was nearly identical.
Those totals include startup, warmups, and the full workload. No per-sample GC,
JIT, scheduling, browser CPU, or stage trace was collected, so the retained
evidence cannot distinguish host contention, browser/JVM effects, or an
implementation cost.

A bounded read-only source review identified additional ordinary-path work:
an extra binding digest and pending-slot check in
[`InMemoryBrowserPersistence.authorizeView`](../modules/spoonbill/src/main/scala/korolev/auth/InMemoryBrowserPersistence.scala),
the larger authenticated security-controls DOM in
[`Application`](../examples/browser-auth/src/main/scala/example/browserauth/Application.scala),
and filtering the empty result map/copying host state during
[`MemoryAccounts` maintenance](../examples/browser-auth/src/main/scala/example/browserauth/MemoryAccounts.scala).
Replacement-map scans are empty in the ordinary-only JVM, the replacement
activation view scan is skipped, and the initial page probe precedes the
sign-in timer. Without phase or allocation profiles, none of the identified
changes is demonstrated to cause the multi-second tail.

The budget breach remains an observed limitation; no
causal claim, code change, or rerun is justified by this evidence alone.

#### Run 4 resources and correctness

| Lane | Peak whole-JVM RSS (KiB) | Post-GC heap before (KiB) | After workload (KiB) | After maintenance (KiB) |
| --- | ---: | ---: | ---: | ---: |
| Memory baseline ordinary | 262,544 | 11,134 | 13,331 | 13,117 |
| Memory candidate ordinary | 250,224 | 11,986 | 13,172 | 13,114 |
| Memory candidate replacement | 261,840 | 11,131 | 14,226 | 13,556 |
| JDBC baseline ordinary | 273,488 | 12,278 | 13,994 | 13,888 |
| JDBC candidate ordinary | 286,944 | 11,792 | 14,050 | 13,955 |
| JDBC candidate replacement | 294,800 | 11,847 | 14,596 | 14,484 |

All JDBC lanes had zero active view owners and zero delivery materials after
maintenance; candidate lanes also had zero encrypted recovery results. The
replacement lane retained 52 ceremonies, sessions, consumed proofs and audit
rows, 27 views, 26 replacement-linkage records, and four recovery-code hashes.
These retained replay/history records and usable code hashes are distinct from
the consumed encrypted result and delivery material that were required to be
removed. The ordinary lanes each retained 25 ceremonies/sessions/views/proofs/
audit rows. Candidate ordinary had no replacement records or recovery hashes.

All forty measured replacement flows retained strict two-completion,
zero-normal-recovery, and single-sensitive-frame counts. Both fault exercises
passed all fifteen recorded steps: three blocked live completion attempts, one
recovery request and activation socket, one-use sensitive disclosure, denial of
duplicate consumption, three already-activated recovery requests without
credential redisclosure (memory 403; JDBC 204), and six denied malformed/origin
requests. Both providers had no page errors.

Owned JVM, browser and disposable PostgreSQL processes were absent after exit;
the owned database storage and baseline checkout were removed. The portable
artifact includes a plain completion/cleanup record. At run-4 completion,
validation compared every one of the then **280 preserved samples** with its
original file, recomputed all
completed-lane medians/p95 and frozen limits, checked strict counts/resource
assertions, and checked portable output for raw secret material and machine
paths. All evidence-integrity checks passed; the memory p95 budget did not.

Exact complete-collection command:

```sh
nix develop .#profiling --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  bash scripts/measure-browser-replacement.sh \
  /private/tmp/spoonbill-replacement-performance-20261017-run4
```

### Earlier attempts retained without reinterpretation

- **Run 1, 2026-10-16:** both memory ordinary lanes completed five warmups and
  twenty samples each. The first candidate replacement warmup failed the strict
  zero-normal-recovery-probe assertion (two observed, zero expected). No
  replacement samples or JDBC lanes were collected. Diagnosis identified a
  delayed socket-close race after successful HTTP completion; the client now
  marks the exact successfully handed-off socket. These forty ordinary samples
  remain failed-attempt evidence, with no final budget decision.
- **Run 2, 2026-10-17:** the baseline compiled, then the parent turn was
  interrupted as candidate compilation began. No browser lane or timing sample
  started. Recovery confirmed no remaining benchmark build, JVM, or browser;
  the orphaned disposable PostgreSQL instance was stopped and its owned storage
  and baseline checkout removed. Output artifacts were retained. This is an
  interrupted setup, with no timing or budget decision.
- **Run 3, 2026-10-17:** both builds passed and all six lanes retained five
  warmups and twenty measured samples each. Five lanes completed all checks.
  The final JDBC replacement lane completed its samples, then timed out during
  the separate fault exercise. Its counters show three blocked completion
  requests, one recovery request, one activation socket, a fresh ready
  application socket, and one sensitive-result frame. The recorded phase is
  only `fault-checks`, so it cannot establish the exact operation that timed
  out. JDBC replacement post-workload heap and retained-row checks did not
  execute. The run exited 1 and produced no accepted `summary.json`. All owned
  JVM/browser/database processes and temporary storage were cleaned up; every
  sample and the failure were preserved. No rerun was selected to improve a
  result.

### Run 3 diagnostic timings

These earlier-attempt statistics use all retained samples, including slow ones. The ordinary
observations lie within the original numerical limits, but the final harness
failure invalidates the attempt under the frozen protocol; the table is **not
an accepted budget pass**.

| Provider / operation | Median (ms) | p95 (ms) |
| --- | ---: | ---: |
| Memory baseline ordinary | 375.81 | 395.32 |
| Memory candidate ordinary | 377.18 | 382.22 |
| Memory ordinary limit | 494.77 | 583.69 |
| Memory replacement | 277.96 | 827.13 |
| Memory result consumption | 56.62 | 434.31 |
| Memory replacement + consumption | 334.51 | 1,256.76 |
| JDBC baseline ordinary | 1,066.76 | 3,622.64 |
| JDBC candidate ordinary | 1,073.08 | 1,138.92 |
| JDBC ordinary limit | 1,358.45 | 4,940.56 |
| JDBC replacement | 5,305.10 | 7,437.68 |
| JDBC result consumption | 4,394.83 | 7,469.62 |
| JDBC replacement + consumption | 11,256.26 | 13,944.92 |

The user explicitly requested collection under the current host load. Across
lane boundaries, one-minute load averages ranged from 14.60 to 34.01 on 16
logical CPUs; reported free memory ranged from 0.66 to 6.76 GB of 137.44 GB
total. JDBC replacement started at load 14.60 and 1.52 GB free and ended at
load 28.73 and 0.66 GB free. These snapshots are context, not proof of the
timeout's cause or measurements of available/reclaimable memory. Sequential
lanes saw different conditions, so the lower candidate JDBC p95 does not prove
a speedup.

Run 3 used Darwin arm64, Zulu JDK 21.0.8, Node 24.12.0, PostgreSQL 14.20,
sbt 1.10.7, Scala 3.3.8, Playwright 1.56.1, Chromium 141.0.7390.37, and GNU
time 1.9 through the repository flakes. Candidate source SHA-256 was
`e673667656c6e6672701af8eceb850a8348cb1fd1957f246eedfd64bad472231`;
an independent post-run fingerprint matched exactly. The metadata-only host
snapshot addition and prior readiness-fixture repair are reflected in this
fingerprint. The runtime implementation was unchanged during collection.

### Run 3 resources and correctness observations

| Lane | Peak whole-JVM RSS (KiB) | Post-GC heap before (KiB) | After workload (KiB) | After maintenance (KiB) |
| --- | ---: | ---: | ---: | ---: |
| Memory baseline ordinary | 261,072 | 11,112 | 13,267 | 13,033 |
| Memory candidate ordinary | 250,048 | 11,129 | 13,407 | 13,210 |
| Memory candidate replacement | 267,632 | 11,146 | 13,739 | 13,589 |
| JDBC baseline ordinary | 277,264 | 13,931 | 13,983 | 13,979 |
| JDBC candidate ordinary | 291,360 | 11,795 | 14,115 | 13,959 |
| JDBC candidate replacement | 293,408 | 11,795 | Not collected | Not collected |

GNU time's exit status 143 reflects the collector's intentional JVM shutdown.
These process-level snapshots do not attribute retained bytes to replacement
records, establish secret erasure, or measure allocations.

Both completed JDBC ordinary lanes retained 25 ceremonies, 25 sessions, 25
views, 25 consumed proofs, and 25 audit rows after maintenance, with **zero
active view owners and zero delivery materials**. Candidate ordinary also had
zero replacement records, encrypted recovery results, and recovery-code hashes.
JDBC replacement cleanup counts are **unverified**, because its fault timeout
occurred before those queries.

All 40 measured replacement flows observed exactly two completion POSTs, two
completion frames, one sensitive frame, and **zero normal recovery POSTs**.
The memory fault exercise passed three blocked delivery attempts, one recovery
request, one dedicated activation socket, three activated-cookie recovery
denials without credential redisclosure, six malformed/origin denials, and
denial of repeated result consumption. JDBC's final fault exercise reached
sensitive delivery but did not finish, so its remaining assertions are
unverified for that attempt. Run 4 later completed them in a separate run.

Exact run-3 command (failed in the final fault check, after all timing samples):

```sh
nix develop .#profiling --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  bash scripts/measure-browser-replacement.sh \
  /private/tmp/spoonbill-replacement-performance-20261017-run3
```

Artifact verification recomputed medians/p95 from all raw samples, compared
them with completed-lane metrics, verified the source fingerprint, and checked
portable content for machine paths and secret-value patterns. Those checks
passed. The follow-up diagnostics below did not convert these samples into an
accepted run; run 4 subsequently supplied a separate complete collection.

### Follow-up fault diagnostics, 2026-10-17

The retained server log contained only startup and orderly shutdown, so it did
not identify the timed-out operation. The harness now checkpoints safe phase
IDs for fault login, replacement, reveal, duplicate consumption, each repeated
recovery API request, and each denial request. It records numeric progress,
duration, HTTP status, and a sanitized source basename/line on failure. This
instrumentation runs outside primary timing samples; it changes no deadline,
assertion, runtime behavior, or frozen workload.

Two separate JDBC diagnostics reused the final compiled example with fresh
disposable PostgreSQL and collected **zero primary timing samples**:

- An isolated fault exercise passed all fifteen instrumented steps. Replacement
  took 6.90 s, reveal 5.06 s, and duplicate-consumption presentation 3.99 s.
- A second exercise recreated all 25 preceding same-JVM login/replacement/reveal
  contexts, with strict completion and zero-normal-recovery checks, then ran the
  fault exercise. All contexts and fifteen fault steps passed. Fault replacement
  took 9.15 s, reveal 10.32 s, and duplicate-consumption presentation 5.22 s.
  After maintenance, delivery materials, active view owners, and encrypted
  recovery results were all zero.

Both fault exercises observed three activated-cookie recovery responses of 204
without credential redisclosure, six denial responses of 403, and no repeated
sensitive payload. The second diagnostic ended with load 19.78 on 16 logical
CPUs and 0.51 GB reported free memory; no host-load condition was used to select
or discard observations. Both runs stopped their owned processes.

The original timeout did not reproduce, including with the same preceding
session history. Its exact failing operation and cause remain unknown; these
results do not establish that host load caused it. No runtime change was made.
Run 3 remains invalid. Both diagnostic records and their safe progress details
are retained in the portable evidence artifact.

The first diagnostic launch used the browser flake, which lacked `initdb`; it
failed before any database/browser launch. Using the existing profiling flake
resolved that setup issue. The successful diagnostic commands were:

```sh
nix develop .#profiling --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  bash /private/tmp/spoonbill-replacement-fault-diagnostic.sh

SPOONBILL_DIAGNOSTIC_HISTORY=25 \
SPOONBILL_DIAGNOSTIC_OUTPUT=/private/tmp/spoonbill-replacement-fault-diagnostic-20261017-history1 \
nix develop .#profiling --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh \
  bash /private/tmp/spoonbill-replacement-fault-diagnostic.sh
```

These temporary diagnostic wrappers are session artifacts, not a replacement
for the checked-in collector's reproduction command above. The first run used
a 180-second outer watchdog; the history run used the original collector's
600-second lane watchdog. Both retained the same 25-second browser action and
readiness deadlines and unchanged API request deadlines.

### Deterministic provider accounting

Executed 2026-10-16 against owned PostgreSQL, shared-view ownership, controlled
clock, and the synthetic test host. Both focused tests passed: **2 executed,
2 passed, 0 canceled**. The callback replay test also verifies that evaluating
the same lazy outer effect twice invokes successor-completion host work once.

| Boundary | Domain transactions | Independent clock connections | Host lookups | Host callbacks |
| --- | ---: | ---: | ---: | ---: |
| Begin | 1 | 4 | 2 | 0 |
| Bind challenge | 1 | 4 | 2 | 0 |
| Prepare: admission and atomic mutation | 2 | 14 | 5 | 1 |
| Fixed-frame admission | 1 | 6 | 2 | 0 |
| Recover committed outcome | 1 | 3 | 2 | 0 |
| Deliver | 1 | 5 | 3 | 0 |
| Activate | 1 | 5 | 4 | 0 |
| Claim successor view | 2 | 6 | 4 | 0 |
| Verified successor completion callback | 1 | 6 | 4 | 1 |
| Denied preparation replay | 0 | 0 | 0 | 0 |
| Already-activated recovery | 1 | 3 | 2 | 0 |
| Maintenance | 1 | 1 | 0 | 0 |

These are observed counts, not latency estimates. The accounting exercise
intentionally visits both fixed-frame admission and recovery; a normal live
handoff need not visit recovery. The final callback returns verified metadata
in this fixture; real host result-consumption SQL belongs to the application.
Setup/login and final resource teardown are outside the named observations.
Shared ownership always consults the durable clock; exclusive ownership may
reuse acknowledged equal-time checkpoints. No clock connection is counted as
an outer domain transaction or silently excluded.

```sh
nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec -- -z "account for replacement transactions"' \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec -- -z "same lazy outer effect"'
```

The first attempted run stopped at compilation because of a chained
thunk-effect cleanup expression in the new test. After making the cleanup value
explicitly typed, both tests passed. No measurement samples were collected or
discarded by that repair.

The earlier complete implementation-stage PostgreSQL suite passed **231 tests
with zero failures or cancellations**, including replacement recovery/races,
populated-schema upgrade, and the unchanged ordinary preparation SQL-count
budgets. Exact command:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt 'securityJdbc/test'
```

After the additional lazy-callback, pending-successor stamp, and exact
nanosecond-stamp checks, the focused provider/session suites passed **83 tests
with zero failures or cancellations**, reconfirming the accounting table above:

```sh
nix develop --no-write-lock-file --command \
  bash scripts/with-test-postgres.sh sbt \
  'securityJdbc/testOnly spoonbill.security.jdbc.JdbcBrowserPersistenceSpec spoonbill.security.jdbc.JdbcBrowserSessionsSpec'
```

The final focused run took substantially longer than the preceding run despite
passing. It is correctness evidence only; the browser attempts and their limits
are reported separately above.
