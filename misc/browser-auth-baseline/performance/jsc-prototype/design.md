# Experimental JSC allocation ledger

This experiment targets the official WebKitGTK 2.50.4 source archive selected by
the repository's locked nixpkgs revision
`c0b0e0fddf73fd517c3471e546c0df87a42d53f4`:

- URL: `https://webkitgtk.org/releases/webkitgtk-2.50.4.tar.xz`
- Flat archive hash: `sha256-07+kc4Raz6tyY1utpeDRNP2meSxblcXFzRQbRhJb2OQ=`

It is a separate source pin. The installed Playwright WebKit 2215 binary has not
been mapped to this source, and this experiment must not replace its framework
or provide browser performance acceptance values.

## Scope and accounting rule

The first ledger counts successful **JSCell/JSCellWithIndexingHeader allocation bytes**, using
the allocator's charged cell size, including subsequent reuse of reclaimed cell
storage. A deallocation or collection never reduces the cumulative total. A
failed allocation contributes zero. It excludes GC-managed `Auxiliary` cells
(including Butterfly storage) as well as other auxiliary/native backing stores,
reserved capacity, WebCore/DOM, worker/browser aggregation, and JIT allocation
coverage. Those are separate coverage obligations, not zero-valued measurements.

Do not alter `Heap::didAllocate` or existing GC scheduling thresholds. The
experimental ledger is independent of the GC-cycle accounting. It must detect
overflow and refuse a result rather than wrapping, saturating silently or
returning a rounded JavaScript number.

The patch against the verified 2.50.4 release source accounts for
consumption of each active free list before replacement, clearing or GC, and
flushes any still-partial list at a quiescent observation. Track an already
charged watermark so repeated observations cannot charge the same cells twice.
Charge successful precise-cell registration, including lower-tier revival,
independently of whether native storage was newly obtained. Verify every
allocator reset/destruction path before treating this as coverage.

The interpreter-only fixture must use actual engine allocation. A simulation of
the ledger or a native `malloc` counter is not a substitute. A getter must not
allocate measured cells itself. Prefer a native scalar result/export at a
quiescent boundary; any JavaScript-facing numeric getter must reject values
beyond exact integer representation.

## Targeted oracle

The native shell oracle allocates actual `JSFinalObject` instances and independently
sums their returned `HeapCell::cellSize()` metadata, comparing that exact expected
total with the ledger. It roots the objects and defers GC during each exact
window. Maximum inline capacity with an explicit 400-byte precise-allocation
cutoff exercises successful precise allocation. The one-shot test-only refusal
occurs at the entry to `PreciseAllocation::tryCreate`, before native allocation;
this tests a refused request without trying to exhaust memory. Transient WeakMaps
and full GC exercise the actual lower-tier free-list revival branch, tracked
separately from first lower-tier allocations (both register with
`isNewAllocation=false`). The planned checks are:

1. Less than one free list consumed, then observation and repeated observation.
2. Partial-list consumption followed by GC; totals survive multiple collections.
3. Exhaustion and refill across more than one block.
4. Reclamation followed by reuse of the same cell storage.
5. Successful precise-cell allocation and lower-tier precise-cell revival.
6. A deterministic refused allocation, with no increment or partial publication.
7. Counter overflow through a bounded test-only oracle path; result is unusable.

A separate small JavaScript fixture creates short-lived objects, forces their
collection before observation and checks that cumulative allocation remains
visible. This demonstrates transient-object observation; by itself it does not
prove all allocator paths. The native oracle and source coverage audit must supply
that narrower evidence. Missing paths must be reported explicitly.

Even if the current oracle passes, its demonstrated coverage is narrower than
the full list above:

- Every exact native allocation window reads and flushes the cumulative total
  before the JavaScript fixture calls GC. It therefore does not independently
  prove exact preservation of an **unobserved partial free list through GC before
  the first read**. The transient-object fixture supplies a lower-bound signal,
  not an exact byte oracle for that transition.
- The overflow check exercises checked-add arithmetic at the integer boundary.
  It does not drive the complete ledger into its invalid state and then verify
  the JavaScript getter's rejection of that state.
- The deterministic refusal occurs before malloc. It checks that this refused
  request leaves the ledger unchanged; it is not a physical `ENOMEM` test or a
  test of every allocator failure path.

A passing run would establish the tested interpreter-only allocation laws and
signals, not complete cumulative-allocation coverage. These remaining oracle
gaps, JIT/auxiliary/backing-store coverage, and browser integration remain separate
requirements. No browser allocation metric or Phase 0 acceptance value follows
from a passing prototype run.

## Build proposal and approved envelope

`default.nix` supplies a dedicated derivation with Clang 21, CMake, Ninja, pkg-config, Perl
(including core JSON::PP), Python, Ruby and ICU. Configure JSCOnly with the
Generic event loop, no GLib API or remote inspector, and no JIT/WebAssembly for
this initial experiment (`ENABLE_C_LOOP=ON`). LTO and debug symbols are disabled;
unified builds reduce repeated header parsing within the serial build deadline.
Release C/C++ flags are `-O1 -g0 -DNDEBUG`, passed as quoted `cmakeFlagsArray`
elements so the Nix CMake hook preserves each complete argument. `-GNinja`
explicitly selects the generator used by the serial build phase. Only the `jsc` target is
built; its embedded native oracle and `oracle.js` run in the same containment.
The derivation checks the explicit expected cgroup namespace, `0::/`, non-root
UID and zero effective capabilities before configure, build and execution.
Patch application has passed against the exact source subset. Compilation and
native oracle execution are still required; no allocation coverage is claimed
from a successful patch application alone.

The first controlled attempt stopped at configure argument parsing (`-g0` was
split from a multiword cache entry). The second completed configuration but
generated Makefiles while the build phase invoked Ninja. Both logs are retained.
After these two failures, the final attempt explicitly selects Ninja and retains
the quoted flag array, one compilation job, the same resource limits and the
same counter/oracle patch. No further actual attempt is authorized by this
re-plan if that final attempt fails.

The third attempt passed configuration and reached the JavaScriptCore shared
library link, which failed with an undefined reference to
`JSC::Subspace::attributes() const` from `IsoSubspace::tryAllocateLowerTierPrecise`.
The new revival hook called that accessor, but `IsoSubspace.cpp` did not include
`SubspaceInlines.h`, which contains its inline definition. The local patch now
adds that explicit include and passes an exact-source patch dry-run. This
linker fix has **not** been compiled or executed. The third attempt never
produced a runnable shell or ran the allocation oracle, so there is no native
allocation result and no coverage/acceptance claim. A fourth actual build
requires new human authorization under the repository recovery policy.

The approved existing NixOS guest envelope is 16,000,000,000 bytes charged to the
actual build/test process tree, no guest swap, one compilation job, at most two
CPU equivalents, two hours total and 15 GiB additional disk. The parent owns
source acquisition, containment verification and launch. See the companion
envelope files for implementation. Limiting only a Nix client does not constrain
builders started by a daemon outside its cgroup.

No VM clone/restart, full-browser build, host installation or installed-browser
replacement is part of this experiment. Any unavailable source component,
unsupported build option, memory/disk/time breach, incomplete cleanup or
unexplained oracle mismatch stops the experiment. Preserve failure logs and
resource readings; do not expand limits or retry a changed configuration without
following the repository's recovery sequence.
