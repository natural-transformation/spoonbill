# Memory browser record store

The record store's authority checks remain in their original order. Clock reads,
host callbacks, issuance and publication checkpoints stay in the interpreter;
pure decisions receive values already obtained at those exact points.
Short-circuit checks must not gain eager clock reads on rejected paths.

Two public characterizations pass on the original implementation:

- A transaction that observes later time and then throws rolls back staged host
  changes, but retains the observed time. Moving the raw clock backwards cannot
  revive an expired view.
- Reclamation publishes removed records and advances binding/view fence floors
  before invoking reclamation listeners. A later listener failure returns a
  storage error while retaining that publication; a new view gets newer fences.

Validation: `nix develop --no-write-lock-file --command sbt --batch
--supershell=false -Dsbt.server.autostart=false 'spoonbill/testOnly
*InMemoryBrowserCheckpointSpec'` passed both cases. The existing immutable record
state and four monotone fields are the next extraction target; no representation
change is included in this prerequisite unit.
