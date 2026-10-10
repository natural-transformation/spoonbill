# Bounded experiment result

**The third/final engine build failed at the JavaScriptCore shared-library link.**
No oracle executed. Containment and a small OOM/cleanup proof passed; this record
does not establish browser allocation coverage or Phase 0 acceptance. The
structured companion is [results.json](results.json).

The official WebKitGTK 2.50.4 archive was acquired through Nix with the pinned
SHA-256 before the original source deadline. The experiment runs in an existing
Linux ARM64 guest, within a task-owned cgroup and private local store. No
installed browser was replaced. The source is not an established match for the
installed Playwright WebKit binary.

The approved envelope remains 16,000,000,000 bytes of charged memory, a
14,000,000,000-byte high watermark, zero guest swap, one compiler job, two CPU
equivalents, two hours total and 15 GiB additional disk. A 14 GiB filesystem
contains large outputs; deadlines were not restarted between attempts. Charged
memory includes file cache and is not RSS, virtual size or host VM footprint.

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

These peaks describe the observed stage/unit accounting, not isolated compiler
or JSC heap measurements. The second build reached successful CMake generation
and then failed because `build.ninja` did not exist. The final attempt reached
reported Ninja step 1660 of 2462 and failed on the `Subspace::attributes()`
reference from `IsoSubspace::tryAllocateLowerTierPrecise`. The largest observed
charged peak across experiment units was 6,587,342,848 bytes. The final build's
process group was released and shared CI remained running. A fourth engine
build requires human input under the repository recovery policy; no additional
engine attempt or larger budget is authorized by this result.

The local Nix static suite passed **9/9** envelope and OOM-helper tests. Those
checks validate refusal logic. Controller logs for the actual tiny-builder proof,
both build failures, and the settled second/final toy attempts were inspected
locally; their SHA-256 values are retained in the JSON. The transferred source
acquisition log, zero exit status and Nix receipt were also inspected: the receipt
matches the pinned archive hash. Current repository hashes for the derivation,
patch and oracle are recorded separately. The failed build's patch hash is
preserved; the current patch adds `SubspaceInlines.h` to address the unresolved
inline definition, but that change has **not been built**. No successful binary
or oracle is claimed from this source fix. The full Node suite passed **175/175**;
these tests do not substitute for compiling or executing the engine. The separate
current-guard-only guest recheck passed the published preflight, actual tiny Nix
builder and tightened OOM verifier, then exited successfully. Its charged peak
recorded before unit exit was 68,993,024 bytes. This recheck did not build the
engine or compile the source fix.

Final controller cleanup confirmed all experiment process groups and owned loop
devices released, with shared CI still running. The retained image capacity is
15,032,385,536 bytes; retained task files occupy 4,548,068 KiB of allocated disk
blocks. These are retained evidence/cache files, not active mounts or processes.
The final guard and cleanup log hashes are retained in the JSON.
Raw logs remain
private because they contain runtime paths and process identifiers. This public
summary intentionally contains neither.

Even a successful shell oracle would demonstrate only the narrow interpreter
JSCell allocation contract described in [design.md](design.md). Auxiliary and
native backing stores, WebCore/DOM, worker/browser aggregation and JIT coverage
would still require separate evidence. No browser performance metric, manifest
operation ceiling or acceptance flag is populated from this experiment.
