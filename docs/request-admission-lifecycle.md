# Browser admission and retained capacity

## Implemented boundary

`BrowserPersistence.inspectPrincipal` observes HTTP presentation authority.
The built-in memory and JDBC providers do not insert a binding when a correctly
formed binding has never been admitted and no session credential is supplied.
They return anonymous presentation only. This operation cannot create a view or
ceremony capability. A supplied credential and every previously admitted binding
still go through the existing authoritative checks. Inspection never activates a
pending credential.

`BrowserAuthentication` uses inspection for its HTTP guard and state-loader
principal lookup. A denied shared HTTP presentation is checked before admission;
an allowed shared bootstrap still captures authority and registers its durable
view, rechecking presentation after capture. WebSocket admission and subsequent
commands retain the original transactional checks.

The default SPI implementation delegates to `capture` for source compatibility.
Third-party providers must implement and test their own nonallocating inspection
before claiming this protection.

## Invariant and migration

No retained row is deleted or recycled. Existing binding generations, view epochs,
one-use ceremonies, negative outcomes and replacement linkage retain their
original lifetime. An anonymous HTTP observation is display data, never a
capability for a later operation. Every later admission independently reads
current state. A missing binding is safe to observe only with no credential;
it does not establish a prior identity or revive an old view.

There is no database schema migration. An older serving node can still allocate
on HTTP inspection, so roll out all nodes before expecting the reduced growth
rate. Code rollback retains all history and restores the old allocation behavior;
it cannot be used to reclaim capacity. JDBC restart also preserves history.

## Monitoring and recovery

Both built-in providers expose `usage()`. JDBC observations are serialized in an
authoritative transaction; memory observations share the admission owner. Call
this administrative method on a bounded monitoring schedule and export the
aggregate counts. Do not expose it as an unauthenticated HTTP endpoint.

`usage.warnings()` reports capacities at or above 80%; `warnings(95)` supports an
urgent threshold, and each result has `exhausted`. Counts distinguish retained
bindings/views/ceremonies, active views, delivery material, and per-binding
maxima. Connect warnings to the deployment's existing alert system. This library
does not install an alert service. Track growth per hour and forecast time to the
limit; choose a warning threshold with enough lead time for the deployment's
drained transition procedure. Per-binding ceremony exhaustion needs a separate
alert even when global history is low.

At warning or exhaustion:

1. Limit new anonymous bootstrap/handshake and ceremony admissions at the trusted
   ingress. Keep existing authorized sessions available where their required
   capacities are not exhausted. Rate limits only slow growth; they do not reclaim
   history or guarantee fairness to new users.
2. Inspect aggregate history and active ownership separately. Retiring a stale
   owner releases active capacity only. Do not delete its retained view record.
3. Arrange a drained namespace transition using the deployment contract in
   [browser-auth-v4-deployment.md](browser-auth-v4-deployment.md). Fence every old
   writer, reconcile unresolved transactions by their original identifiers,
   revoke old host authority, change the namespace/cookies, and require new proof.
   Preserve old negative/replay history and clock maxima. A cookie rename alone
   is not a transition.
4. Verify old sessions, completion identifiers, views and delayed writers fail
   against the new deployment. Keep traffic closed if writer exclusion or host
   revocation is uncertain. Do not roll back to earlier security state.

The reduced-limit tests verify allocation scope, warning thresholds, authority
preservation, last-slot races, and retained counts across JDBC restart. The
existing provider conformance suites continue to test late writers, stale owners,
replacement/replay handling and cleanup.

## Remaining SB-02 work

SB-02 is **partially mitigated, not closed**. Successful shared HTTP bootstraps,
WebSocket admissions and ceremonies still consume retained history. A per-binding
ceremony limit remains cumulative. The implementation does not establish steady
state for indefinitely repeated login/logout or repeated shared page reloads.

A sustainable retirement protocol and a deployment-specific rehearsed cutover
remain required. They need explicit host proof/replay retention, durable scope
revocation, overlapping-node exclusion, unresolved-transaction fencing and a
migration/rollback proof. These cannot be inferred from ceremony expiry. No
production cutover or deployment alert configuration was performed for this PR.
