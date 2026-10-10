# Small owned OOM and cleanup proof

Root launches this only while the source/build job is idle, under the original
experiment deadline. It requests 128 MiB in a **separate 64 MiB** transient unit;
no compiler, download, new package or 16 GB allocation is involved. The existing
experiment controller should occupy only a few MiB during this check. This is a
kernel containment diagnostic, not JSC allocation or Phase 0 acceptance evidence.

Use trusted existing Nix Bash/coreutils and an absolute script path. Transient
units must receive an explicit trusted PATH, for example
`--setenv=PATH=/run/current-system/sw/bin` on the inspected NixOS guest; an
absolute Bash executable alone does not provide `uname`, `head`, `tr` or `sleep`.
Root supplies
a new marker filename in a task-owned root-only directory **outside** the toy
unit's cgroup, preserves its logs, and uses a new unit name matching
`spoonbill-jsc-oom-<id>.service`. Do not use `--collect` or reset the failed unit
until its retained result/peak have been verified. Required unit properties:

```text
MemoryAccounting=yes
MemoryMax=67108864
MemoryHigh=infinity
MemorySwapMax=0
MemoryOOMGroup=yes
OOMPolicy=kill
KillMode=control-group
ProtectControlGroups=strict
NoNewPrivileges=yes
CPUQuota=100%
CPUWeight=1
IOWeight=1
Nice=19
OOMScoreAdjust=1000
TasksMax=16
RuntimeMaxSec=30
TimeoutStopSec=5
```

Do not enable a private PID namespace: the marker identifies the sibling in the
host PID namespace. `ProtectControlGroups=strict` gives the payload its private,
read-only cgroup namespace; it checks namespace-root limits before allocating.
The sibling is a 600-second sleep. Its PID and Linux start-time token are written
exclusively before allocation, so PID reuse cannot create a false live-sibling
result. The root-owned marker directory and script are trusted controller inputs.

For this toy only, `memory.high=max` disables throttling so the allocation can
reach the hard 64 MiB limit within the 30-second deadline. This exercises hard
limit/group OOM behavior separately from high-watermark throttling. The actual
engine unit retains its 14,000,000,000-byte `memory.high` and unchanged budget.
The first toy attempt lacked PATH and refused before allocation; the second
timed out under the 48 MiB high watermark with a 59,019,264-byte charged peak.
Both failures remain preserved. This is the third/final attempt after replanning;
another failure requires stopping and reporting the evidence, not relaxing the
hard limit, peak validator or deadline.

The service command is:

```sh
bash /absolute/oom-proof.sh payload /absolute/new-sibling-marker
```

After the service has failed and cleanup settled (bounded by the unit's 30-second
runtime plus five-second stop timeout), run from the guest controller outside
that toy cgroup:

```sh
bash /absolute/oom-proof.sh verify spoonbill-jsc-oom-EXACTID.service /absolute/new-sibling-marker
```

The verifier requires `Result=oom-kill`, no MainPID, failed/inactive state, the
owned cgroup absent or recursively unpopulated, and the recorded sibling gone.
An unreadable process record for an extant PID is a refusal, not evidence that
the sibling exited. Run the verifier from the trusted guest controller with
visibility of the host PID namespace.
It reads systemd's retained `MemoryPeak`; an unavailable counter is a refusal.
The diagnostic accepts an observed charged peak from 48 through 80 MiB. The
upper threshold allows bounded accounting overshoot around the configured
64 MiB cap; it is an observed check, not a promise that `memory.max` never has a
transient overage. It reports charged memory, not RSS, virtual size, host memory,
or page-cache reclamation. An empty/removed cgroup proves process release, not
that all charged pages disappeared immediately.

Keep `systemctl show` and the bounded unit journal with the controller evidence.
A timeout/signal exit, surviving descendant, missing peak, or wrong OOM result
fails this proof; inspect it before any compiler runs. The helper never creates
or stops services itself. Static tests do not execute the allocation:

```sh
nix develop --no-write-lock-file --command node --test \
  misc/browser-auth-baseline/performance/jsc-prototype/oom-proof.test.mjs
```
