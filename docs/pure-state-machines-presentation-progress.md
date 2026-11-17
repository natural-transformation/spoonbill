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
