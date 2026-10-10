# Bounded experiment progress

**Pending final build.** Containment and a small OOM/cleanup proof passed; this
record does not establish a compiled JSC oracle, browser allocation coverage, or
Phase 0 acceptance. The structured companion is [results.json](results.json).

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
| Final build attempt | Pending controller update | Unavailable |

These peaks describe the observed stage/unit accounting, not isolated compiler
or JSC heap measurements. The second build reached successful CMake generation
and then failed because `build.ninja` did not exist. No native ledger oracle has
executed at this snapshot. A further failure in the final attempt requires
reporting it under the repository recovery policy, not another automatic retry
or larger budget.

The local Nix static suite passed **8/8** envelope and OOM-helper tests. Those
checks validate refusal logic. Controller logs for the actual tiny-builder proof,
both build failures, and the settled second/final toy attempts were inspected
locally; their SHA-256 values are retained in the JSON. Source acquisition remains
labelled controller-reported pending its raw-log transfer. Raw logs remain
private because they contain runtime paths and process identifiers. This public
summary intentionally contains neither.

Even a successful shell oracle would demonstrate only the narrow interpreter
JSCell allocation contract described in [design.md](design.md). Auxiliary and
native backing stores, WebCore/DOM, worker/browser aggregation and JIT coverage
would still require separate evidence. No browser performance metric, manifest
operation ceiling or acceptance flag is populated from this experiment.
