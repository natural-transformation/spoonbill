# Bounded presentation store rollout

## Original-code characterization

The nine existing store tests cover manager/snapshot detachment, owner epochs,
reconnection, retained browser-reset requirements, capacity bounds, expiry,
backward clocks and authoritative history reclamation. Five added cases pin
ordering that a pure extraction must preserve:

- Failed pending-deadline arithmetic retains already-recorded ownership history.
- Failed reconnect-deadline arithmetic retains removal and expiry sweeping.
- A stale token is rejected before purging its successor's expired entry.
- Sweeping survives a later rejected bootstrap admission.
- Clock sampling happens before the monitor; a close that wins meanwhile rejects
  the pending operation.

All 14 cases passed against the original production store as part of the Nix
`spoonbill/test` integration checkpoint (895 runtime tests). The log is
`/tmp/spoonbill-rollout-integration-11.log`. No presentation implementation changed
in this characterization unit. Ordinary immutable node maps remain data; the
planned extraction moves lifecycle/permission rules while avoiding a new state
wrapper on each node read or monotonic-clock observation.

## Selected implementation and measured acceptance

`PresentationMachine` owns entry phases, token/expiry permission, capacity,
ownership-history admission and lifecycle transitions. Open history and permanent
closure are one registry value. Immutable ordinary node maps keep their existing
read/write operations; the monotone clock remains a separate primitive frontier.
The interpreter retains the monitor and each existing mutation/exception
boundary. History still publishes before pending-deadline arithmetic, and release
still removes/sweeps before reconnect-deadline arithmetic. The 14 original-code
cases and four pure model cases pass unchanged in behavior.

The first three representations failed the snapshot cost screen. Merely
flattening the permission query or making the node-map type concrete did not
restore the original cost. Bytecode/JFR diagnostics found that shared higher-order
`effect -> at -> body` dispatch stopped eliminating a snapshot-construction
lambda. Native model-query methods already inlined; production JVM tuning was
not adopted. Scala inline wrappers with inline body arguments removed that shared
dispatch, and conditional clock publication retained the original no-write path
when time does not advance. This is selected design 5.

All 30 fresh matched trials pass the five-workload guide:

| Public operation | Throughput / baseline | Allocation / baseline | Sampled p99 / baseline |
| --- | ---: | ---: | ---: |
| Fixed-clock read | 99.5% | 100.0% | 100.0% |
| Advancing-clock read | 122.4% | 60.0% | 100.0% |
| Ordinary write | 112.4% | 68.2% | 100.0% |
| Fenced snapshot read | 99.7% | 100.0% | 100.0% |
| Release/claim/publication cycle | 118.5% | 87.7% | 83.2% |

The fixture uses the same public operations in fresh fixed-heap JVMs, alternating
order, live current-thread allocation counters and escaping allocation controls.
It preserves every failed design, screen, smoke run and full comparison—100 trial
rows in all. Baseline JIT compilation modes varied; one screen's large apparent
speedup is not claimed as representative. The final full comparison and broad
per-trial ranges are authoritative. Sampled p99 includes clock overhead and is
quantized; these are synthetic shared-machine medians without a confidence
interval or application-level performance promise.

[Source snapshots, all trial packs and diagnostic evidence](../misc/presentation-cost/README.md)
include exact source hashes matching the frozen selected candidate. The analyzer
reproduces every packaged summary; targeted screens cannot declare full acceptance.
The final isolated Nix validation passed 18 tests. Validation of the selected
sources in the combined branch then passed 83 store, authentication, guard and
snapshot tests (`/tmp/spoonbill-presentation-selected-validation.log`). The
selected wrapper declarations and clock-publication changes preserve those
tested effect boundaries.
