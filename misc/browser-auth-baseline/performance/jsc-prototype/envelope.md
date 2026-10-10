# Owned guest resource envelope

This is guest-side source and a static test suite. It does not start a VM, mount
or format a filesystem, change shared services, install packages, or collect the
shared Nix store. Root controls the already approved transient experiment unit.
Passing static tests is not evidence that the guest's resource controls work.

The trusted controller must first create its own private mount namespace and a
fresh ext4 loop filesystem. The current plan uses a 14 GiB image, leaving 1 GiB
of the approved 15 GiB additional-disk budget for bounded controller metadata.
All source, private-store contents, build scratch, cache, outputs and large logs
belong in that filesystem. No large output goes to global `/nix/store` or `/tmp`.

The selected task prefix is beneath `/var/lib`, with root-owned ancestors that
are not group/world writable. The first actual builder attempt was refused by
Nix because the original prefix had `/tmp` as a world-writable ancestor. Root
moved only the owned image/task directory, preserving its database and failure
logs; no ancestor-safety check was relaxed.

Before source acquisition/build, `preflight` verifies actual kernel/controller
state:

- Private read-only cgroup-v2 namespace: controller and caller both see `0::/`,
  share the namespace inode and can read their namespace-root resource files.
- `memory.max` positive and at most 16,000,000,000 bytes; `memory.high` at most
  14,000,000,000; swap max zero; OOM group enabled. These are charged-memory
  controls, not RSS/host-VM footprint or a promise of no transient kernel overage.
- `cpu.max` permits at most two CPUs; positive `pids.max` at most 256.
- Exact ext4 loop mount and backing-device capacity no greater than 15 GiB.
- Actual owned-unit MainPID and properties: whole-group cleanup, OOM kill,
  protected cgroups, nice 19, CPU/I/O weight 1, OOM score 1000, no-new-privileges,
  finite stop timeout at most ten seconds and runtime at most two hours.
  systemd's canonical `ProtectControlGroups=yes` is accepted only with the
  independently checked private namespace/read-only files and resource limits.
- Read-only live bind of the existing runner's cgroup subtree, matching the
  single previously verified idle runner PID. No active machines, no controller-specified priority lock,
  and at least 8,000,000 KiB MemAvailable are required.

The controller supplies these task-only environment values:

```text
JSC_WORK                 canonical path to the owned ext4 mount
JSC_UNIT                 spoonbill-jsc-<owned-id>.service
JSC_DEADLINE_EPOCH       absolute approved end time, no later than now + 7200s
JSC_CI_CGROUP            read-only bind of the shared runner cgroup subtree
JSC_CI_BASELINE_PIDS     its one verified idle daemon PID
JSC_PRIORITY_LOCK        absolute deployment/maintenance lock path supplied privately by the controller
JSC_EXPECTED_NIX_VERSION exact audited guest nix --version output (2.35pre/53ab7375)
JSC_EXPECTED_USERPOOL_DEVINO device:inode of /nix/var/nix/userpool in daemon/host view
```

Source the script from a trusted task step to retain its functions/exported
namespace value, then use `preflight`, `configure_nix` and
`run_guarded trusted_step_function`. Alternatively the `check`, `run` and `nix`
entrypoints provide the same checks. `run` accepts only an explicit driver inside
the owned filesystem. Every heavy stage, including closure copies, must run
under `run_guarded`; an unguarded preparatory build is not covered.

The required priority-lock path stays in controller configuration, not published
source or reports. Preflight refuses an existing file or symlink at that path.
This input was made explicit after the current experiment's frozen guest copy
was launched; that running copy is not modified by this source-only change.

The one-second watchdog yields the **owned experiment**, never CI: new runner
processes, machines, low available memory, unreadable guard state or the absolute
deadline signal the validated experiment MainPID. Its exit invokes systemd's
whole-cgroup cleanup. This is a bounded reaction policy, not an atomic CI queue
reservation; CPU/I/O priority and the kernel budget remain active between polls.
The outer controller must treat any failed step as failed evidence and stop
the experiment after saving its bounded diagnostics. It must verify the host-side
cgroup becomes unpopulated; a successful CLI exit alone does not prove cleanup.

## Actual builder proof

The Nix wrapper forces a daemonless redirected local store, `build-users-group =
nixbld`, sandboxing, no remote builders, `use-cgroups = false`, one derivation and
one compiler slot. The CPU cgroup still permits up to two CPUs. It clears Nix
user/system configuration selection into the task filesystem and rejects caller
overrides of these settings. No root compiler is permitted. This containment is
for the reviewed Nix/controller and pinned source, not a claim to sandbox an
arbitrary malicious privileged controller.

The only admitted `store` command is this exact pinned archive fetch (including
argument order). Extra flags, alternate names, URLs or hashes are refused:

```sh
owned_nix store prefetch-file --json --name webkitgtk-2.50.4.tar.xz \
  --expected-hash 'sha256-07+kc4Raz6tyY1utpeDRNP2meSxblcXFzRQbRhJb2OQ=' \
  https://webkitgtk.org/releases/webkitgtk-2.50.4.tar.xz
```

Acquisition remains inside the guarded unit and private store, with the original
absolute source/experiment deadlines. A refused command does not reset either
deadline or authorize a different source.

The audited Nix revision `53ab7375` uses global `settings.nixStateDir/userpool`
locks even when a store root redirects its database. Preflight refuses an
`NIX_STATE_DIR` override and requires the existing global userpool's exact
device/inode, matching the controller's host/daemon observation. Root supplies a
writable bind of **that same directory** inside the private mount namespace;
there is no new or copied pool. Auto-allocated UIDs remain disabled. This shared
lock identity is essential: independent pools could choose the same build UID,
and Nix user cleanup could otherwise terminate unrelated CI builders. Version
strings alone do not prove pool identity. Failure to acquire the shared lock is
a refusal, never permission to bypass locking or switch to a private pool.

Root copies only the selected existing input/tool closures from the local store
to `local?root=$JSC_WORK/nix-root`, using the same capped controller process and
explicit source/target stores. Do not contact a shared builder daemon. Copied
bash/coreutils paths must be valid in the private store before this proof:

```sh
# Inside the verified, guarded unit; BASH_STORE and COREUTILS_STORE are copied
# complete store paths. No nixpkgs import, fetch or compiler is involved here.
owned_nix build --offline --impure --no-link --print-out-paths \
  --file "$JSC_WORK/envelope-proof.nix" \
  --argstr system aarch64-linux \
  --argstr bash "$BASH_STORE" --argstr coreutils "$COREUTILS_STORE" \
  --argstr expectedNamespace "$JSC_EXPECTED_NAMESPACE"
```

The derivation executes as an **actual Nix builder** and starts a child. Both
assert non-root UID and the expected cgroup/namespace; the builder additionally
requires zero effective capabilities, one build core, non-writable cgroup
control, and no shared daemon socket. A namespace mismatch is an unsupported
configuration to inspect, not automatic proof of escape; never remove an
assertion merely to get green output. Root must cross-check actual builder PIDs
from the outer cgroup before accepting containment. Repeat these assertions at
the real prototype derivation's pre-build/pre-check boundaries; a prior proof
does not authorize later configuration changes.

The controller's second actual tiny-builder attempt passed after the prefix
move: non-root builder and child, matching cgroup/namespace, zero effective
capabilities, resource gates and shared userpool identity. Its subsequent source
step was refused by the earlier command allowlist; that failure is preserved
alongside the first attempt. The pinned fetch allowance above addresses only
that refusal. This proof does not establish a successful JSC build or allocator
measurement, and the already passed tiny builder need not be repeated for the
unchanged source-fetch step.

Before a real compiler, root must also validate the cleanup/OOM policy with a
small separately capped owned workload and verify all its descendants stop and
the cgroup is empty. Retain `memory.peak`, memory events, swap peak/current and
exit disposition outside a group that can itself be OOM-killed. No OOM test or
guest controller validation was run by the static test suite.

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/jsc-prototype/envelope.test.mjs
```

The approved source/configuration and compiler sub-deadlines remain the producing
controller's responsibility; the wrapper enforces the absolute experiment end
and unit maximum. Missing controls, private-store failure, disk exhaustion,
unexpected privilege/namespace, CI contention or timeout ends the experiment.
