# Request handling and availability remediation

Assessment against `7d2b739cc8b1181dea72c2c380749198852cb55f`, the snapshot in
the 21 October 2026 report. All five source findings are reproducible or supported
by the current code. The report is review evidence, not an authorization to alter
production systems or discard security history.

## Implementation plan

1. **SB-01 / SB-03:** replace unbounded static caching with finite entry, byte,
   resource-size and concurrent-read budgets. Do not retain misses. Validate the
   public namespace before classpath lookup and test directory and JAR packaging,
   aliases, failures and saturation.
2. **SB-05:** validate decoded device IDs and custom generator results, make cookie
   parsing deterministic, and reject unsafe field names/values at HTTP output.
   Exercise the complete local standalone response path with harmless canaries.
3. **SB-04:** bound message aggregation and ingress queues at each framework-owned
   adapter boundary, including multibyte text and fragmentation. Inspect decoder
   behavior and explicitly record limits the underlying transport cannot enforce.
4. **SB-02:** separate HTTP principal observation from durable admission in both
   built-in providers. Denied presentation must not allocate a binding. Preserve
   generation, ownership, replay and unresolved-transaction fences. Add comparable
   usage/warning metrics and reduced-limit tests. Document the remaining shared
   bootstrap and ceremony retention lifecycle rather than introduce unsafe TTL
   deletion.
5. Run focused regression suites, the relevant Scala/PostgreSQL suites and browser
   checks in the repository Nix environment. Review the combined diff and open a
   pull request with per-finding evidence and outstanding deployment requirements.

## Closure boundaries

SB-02 remains a lifecycle design issue after removing unnecessary HTTP allocation:
shared view bootstraps and started ceremonies still retain authoritative history.
A sustainable reclamation policy needs a separate proof covering host-owned proof
history, transactions that outlive a request, old cookies and overlapping nodes.
No table is truncated and no generation or owner fence is reset by this change.

ZIO HTTP 3.11.6 also queues WebSocket events internally before the adapter sees
them. A bound in Spoonbill alone cannot establish an end-to-end queue bound. The
transport work must distinguish local hardening from closure of that upstream gap.

No result here establishes production proxy behavior, fleet-wide connection
limits, or a safe production request rate. Those depend on the deployment.
