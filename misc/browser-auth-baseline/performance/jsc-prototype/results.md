# Bounded experiment result

**Further experiments are deferred as of 2026-10-11. Attempt 4's final outcome is unverified.**
The original three attempts failed; the third reached the JavaScriptCore
shared-library link. Attempt 4 was last observed running under its separately
approved 45-minute authorization. No oracle execution, completed fixed binary,
or final attempt-4 cleanup is established by the retained evidence. This record
does not establish browser allocation coverage or Phase 0
acceptance. The structured companion is [results.json](results.json).

The official WebKitGTK 2.50.4 archive was acquired through Nix with the pinned
SHA-256 before the original source deadline. The experiment runs in an existing
Linux ARM64 guest, within a task-owned cgroup and private local store. No
installed browser was replaced. The source is not an established match for the
installed Playwright WebKit binary.

The original two-hour experiment's approved envelope was 16,000,000,000 bytes of charged memory, a
14,000,000,000-byte high watermark, zero guest swap, one compiler job, two CPU
equivalents, two hours total and 15 GiB additional disk. A 14 GiB filesystem
contains large outputs; deadlines were not restarted between attempts. Charged
memory includes file cache and is not RSS, virtual size or host VM footprint.

The user separately authorized **one** additional attempt from 2026-10-11
05:29:40 UTC through 06:14:40 UTC. Its controller stops at 06:14:30 UTC, reserving
ten seconds for cleanup. Memory, swap, CPU, compiler-job and disk caps remain
unchanged, and the retained private filesystem is reused. This is a new 45-minute
budget, not a retroactive extension of the original two-hour window. Fresh host
preflight verified the audited Nix version, shared build-user locks, idle CI and
previous cleanup. Final resource/oracle/cleanup readings were not available at
that observation and remain unverified; the expired authorization window is not
evidence of completion or cleanup. The maintainer subsequently requested no more
performance testing. This takeover starts no build, oracle, or proof step.
The preliminary tiny-proof result was cached because the namespace identity was
reused; its success marker is not evidence of fresh proof-body execution in
attempt 4. The controller confirms the main build's actual `preConfigure`
non-root, zero-capability and namespace assertions passed before compiler probes.
A forced tiny-proof rebuild was queued serially after the main build at the last
observation. Its execution status is unverified; it will not be started or retried
as part of this PR takeover.

The actual tiny Nix builder and its child passed non-root, namespace and resource
checks. Effective capabilities were zero. The controller verified shared
build-user lock identity; a capped client alone would not prove builder
containment. A separate 64 MiB toy unit finally produced the required group OOM
and released its sibling and process group, with an observed charged peak of
67,108,864 bytes. Its high watermark was disabled
only for that toy after an earlier throttled attempt timed out; the actual
engine high watermark remains unchanged. The first toy attempt refused before
allocation because the transient unit lacked PATH. Both failures were retained.

| Stage | Outcome | Observed charged peak (bytes) |
| --- | --- | ---: |
| Verified source acquisition | Passed | 1,127,829,504 |
| Build attempt 1 | Failed: release flags split during CMake configuration | 6,587,342,848 |
| Build attempt 2 | Failed: Makefiles generated, Ninja invoked | 1,390,190,592 |
| Build attempt 3 | Failed: undefined `JSC::Subspace::attributes() const` at shared-library link | 3,669,725,184 |
| Separately authorized attempt 4 | Last observed running; final disposition unverified | Final peak unknown |

These peaks describe the observed stage/unit accounting, not isolated compiler
or JSC heap measurements. The second build reached successful CMake generation
and then failed because `build.ninja` did not exist. The final attempt reached
reported Ninja step 1660 of 2462 and failed on the `Subspace::attributes()`
reference from `IsoSubspace::tryAllocateLowerTierPrecise`. The largest observed
charged peak across experiment units was 6,587,342,848 bytes. The final build's
process group was released and shared CI remained running. Human input required
by the recovery policy was obtained before attempt 4. That approval covers one
additional attempt only; it does not authorize repeated retries or larger caps.

The local Nix static suite passed **9/9** envelope and OOM-helper tests. Those
checks validate refusal logic. Controller logs for the actual tiny-builder proof,
both build failures, and the settled second/final toy attempts were inspected
locally; their SHA-256 values are retained in the JSON. The transferred source
acquisition log, zero exit status and Nix receipt were also inspected: the receipt
matches the pinned archive hash. Current repository hashes for the derivation,
patch and oracle are recorded separately. The failed build's patch hash is
preserved; the current patch adds `SubspaceInlines.h` to address the unresolved
inline definition, and attempt 4 was last observed building it. No successful binary or
oracle is claimed from this source fix. The guest input receipt was inspected
and confirms all three checksum checks passed; its hash is retained in the JSON.
The full Node suite passed **175/175**;
these tests do not substitute for compiling or executing the engine. The separate
current-guard-only guest recheck passed the published preflight, actual tiny Nix
builder and tightened OOM verifier, then exited successfully. Its charged peak
recorded before unit exit was 68,993,024 bytes. This recheck did not build the
engine or compile the source fix.

Original-experiment controller cleanup confirmed all its process groups and owned loop
devices released, with shared CI still running. The retained image capacity is
15,032,385,536 bytes; retained task files occupy 4,548,068 KiB of allocated disk
blocks at that cleanup checkpoint. Those prior cleanup observations do not claim
that the separately authorized attempt 4 has finished or released its resources.
The final guard and cleanup log hashes are retained in the JSON.
Raw logs remain
private because they contain runtime paths and process identifiers. This public
summary intentionally contains neither.

Even a successful shell oracle would demonstrate only the narrow interpreter
JSCell allocation contract described in [design.md](design.md). Auxiliary and
native backing stores, WebCore/DOM, worker/browser aggregation and JIT coverage
would still require separate evidence. No browser performance metric, manifest
operation ceiling or acceptance flag is populated from this experiment.
