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

## Extraction checkpoints

The extracted unit passes its scoped checks. `MemoryBrowserStoreMachine` pairs optional records
with issuance, observed time and fence floors. Publication replaces only the
record component, retaining frontiers advanced since a host snapshot was staged.
`MemoryBrowserRecordMachine` stores immutable records with an opaque immutable
material type; the interpreter supplies browser-session-token values.

| Operation | Pure decision or retained protocol | Interpreter checkpoint retained |
| --- | --- | --- |
| Clock observation and issuance | Monotone observation, exhaustion decision and sequence increment | Actual clock/UUID material construction stays at its original call site |
| Reclamation | Expiry, retained history, record removal and next fence floors | One clock sample, publication, then reclaim listeners |
| Capture | Missing-binding admission and capacity; existing replacement-slot activation model | Credential checks/digests, principal observation, host acknowledgement and final publication |
| Claim | Existing-view, binding, capacity and connection admission; next ownership epoch | Identity validation, owner generation and both original clock samples |
| Authorize/release | Immutable view-record copies; ownership/authority check chain retained | The original repeated authorization checks and touch/release timestamps |
| Begin/bind/resume | Existing pure ceremony/replacement record models | Issuer checks, issued identifiers, clock reads and each record publication |
| Prepare ordinary/replacement | Existing ceremony admission/commit and replacement reservation models; staged immutable records | Admission publishes before the exactly-once host callback; host mutations publish only after all rechecks |
| Host transactions/successor completion | Synchronous transaction scope and immutable staged host state retained | Thread ownership, callback execution, authorization rechecks and close-finally order |
| Recover/pending | Existing ceremony reconciliation/reservation recovery models | Ordered host and time observations, deterministic selection and issuer-produced capabilities |
| Delivery | Delivery counter and replacement-material exhaustion | All authority, host and deadline checks precede publication |
| Logout | Slot generation, material clearing and replacement owner retirement | Credential validation/digest before the pure decision; missing slots remain no-ops |
| Maintenance | Reclamation plus completion-material expiry | Host maintenance remains between its original clock sample and record publication |
| Usage/resources/close | Immutable record observations and optional-record closure | Admission drains first; issuer/key retirement remains before dropping records |

The authority chains are not converted to events: they deliberately keep checks
and effects together without adding a suspension. Local immutable record copies
between those checkpoints remain data transformations, not additional lifecycle
cells. No host callback, digest, clock or capability minting is placed in a model.

An existing retention spec inspected the provider's nested-class layout. Before
moving record types, a separate prerequisite changed it to inspect actual
immutable record data after a real login/replacement, rejecting retained view or
identity capabilities. That unchanged test passed both the original provider and
the extracted model. It does not require records to be nested in a specific owner.

Validation through Nix: `spoonbill/testOnly *MemoryBrowser*Spec
*InMemoryBrowser*Spec *ReferenceSnapshotStoreSpec *SnapshotReferenceMachineSpec`
passes 124 tests across nine suites. `node scripts/check-core-purity.mjs` inspects
34 machines and reports no violations. The initial test-fixture name collided
with ScalaTest's `key` matcher and was corrected to `bindingKey` before execution.

Proposed inventory disposition is **refactored**, with the checkpoint table as
the review boundary. The remaining authority/host chains and transaction scope
guards are intentionally retained under the security-chain exception. All
provider record state is one immutable publication value; the retained local
record transformations introduce no separate lifecycle fields. Final disposition
awaits the coordinator's independent effect-order review.
