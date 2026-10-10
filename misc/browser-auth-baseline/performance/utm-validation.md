# Local UTM profiling validation

On 2026-10-10 the stock profiling collectors passed in a disposable boot of the
existing NixOS UTM guest on the local Mac. The guest reported 12 CPUs, 32 GiB RAM
and approximately 160 GiB available disk space. The host reported 128 GiB RAM
and approximately 2 TiB free. These are capability probes with synthetic data;
they are not controlled latency runs or application acceptance evidence.

The guest was a shared CI runner whose services remained active. These probes
establish sensor functionality under shared-host conditions. Shared CI
configuration and production services were unchanged, and the guest remained
running afterward.

Only Spoonbill's flake/lock and profiling sources were staged under guest `/tmp`.
The repository's `.#profiling` supplied all probe/build dependencies. A Nix dry
run reported 1.4 GiB downloads and 6.6 GiB unpacked dependencies; only browser
bundle/shell assembly was built, with no browser source compilation. File-based
guest scripts captured stdout/stderr and a separate final exit-code file, both
read back before reporting success. UTM CLI success alone is insufficient: some
guest/file errors were returned as text with a successful CLI status.

Run from a staged Spoonbill checkout in a Linux guest:

```sh
nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/native-memory.linux-tests.mjs \
  misc/browser-auth-baseline/performance/browser-heap.native-tests.mjs

nix develop .#profiling --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/chromium-allocation.test.mjs \
  misc/browser-auth-baseline/performance/chromium-allocation.native-tests.mjs
```

Both commands exited 0: five native sensor tests and nine Chromium allocation
tests. The Nix tools included Node 24.12.0, OpenJDK 21.0.9, heaptrack 1.5.0
unstable-2025-07-21, GNU time 1.9 and Playwright 1.56.1.

| Probe | Linux/arm64 observation |
| --- | --- |
| Chromium 141.0.7390.37 retained heap | 516,453 B before; 6,939,297 B with synthetic objects; 580,129 B after release/GC |
| WebKit 26.0 retained heap | 550,477 B before; 9,049,898 B with synthetic objects; 565,436 B after release/GC |
| Owned-process RSS | 116,711,424 B VmHWM/current RSS for the child holding 64 MiB touched memory |
| Heaptrack | Exact 67,108,864 B request present; total intercepted native requests 74,203,361 B |
| Experimental V8 allocation trace | Empty 0 B; persistent 868,028 B; short-lived 1,768,808 B across 40,393 allocations; WeakRef confirmed reclamation before observation |

Retained heap, native allocator traffic, per-process RSS and experimental V8
trace bytes have distinct scopes. The required comparable cumulative browser
allocation and simultaneous process-tree peak RSS metrics remain unsupported.
Future acceptance runs require dedicated controlled hardware and the reviewed
workload/provenance/calibration contract.

## Approved standalone JSC experiment

After the stock probes, the user approved a separate experiment in the existing
guest: `MemoryMax=16000000000`, `MemoryHigh=14000000000`, zero swap, one compiler
job, at most two CPU equivalents, two hours and 15 GiB additional disk. Linux is
used for its validated process-tree resource controls; JSCOnly itself can run on
macOS. No VM clone/restart, installed-browser replacement or shared-service
change is authorized by this experiment.

The actual tiny Nix builder and its child passed non-root, capability and cgroup
namespace checks. The separate 64 MiB OOM proof passed after group termination
and descendant cleanup settled. These validate containment, not allocation
accounting. The official WebKitGTK 2.50.4 archive was acquired and Nix-hash
verified within the owned envelope. Its source identity remains separate from
the installed Playwright browser's unresolved source/ABI provenance.

The first engine-build attempt failed on compiler-flag quoting; the second
failed during CMake generation. Their diagnostics are preserved. The final
attempt and native oracle outcome are pending under the original absolute
deadline, 2026-10-10 23:02:43 UTC. No engine allocator pass, browser allocation
metric or accepted performance ceiling follows from these preparatory checks.
See the [ledger design](jsc-prototype/design.md),
[resource envelope](jsc-prototype/envelope.md),
[OOM proof](jsc-prototype/oom-proof.md) and
[experiment results](jsc-prototype/results.md).
