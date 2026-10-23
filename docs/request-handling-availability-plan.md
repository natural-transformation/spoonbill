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
3. **SB-04:** bound message aggregation and ingress queues, including the queue
   inside ZIO HTTP before conversion. Build a pinned derived runtime with the
   original replaced class removed, verify its published artifacts, and fail
   startup on duplicate/unpatched runtimes. Add absolute incomplete-message/frame
   deadlines and verify socket, ownership and pool cleanup.
4. **SB-02:** separate HTTP principal observation from durable admission in both
   built-in providers. Denied presentation must not allocate a binding. Preserve
   generation, ownership, replay and unresolved-transaction fences. Reclaim
   eligible history under writer exclusion with permanent generation/epoch floors,
   nonrepeating issuance IDs, and a provider-owned cookie key. Add anonymous quotas,
   finite session lifetimes, renewable view leases, bounded local presentation
   history, and transition warnings. Prove repeated cycles, restart, migration,
   rollback and held-transaction behavior with deliberately small limits.
5. Run focused regression suites, the relevant Scala/PostgreSQL suites and browser
   checks in the repository Nix environment. Review the combined diff and open a
   pull request with per-finding evidence and outstanding deployment requirements.

## Deployment boundaries

V6 adds permanent retention metadata and finite history reclamation; it does not
delete host-owned proof, session, audit or outbox records. No generation, view
floor or issuance counter is reset. Drain older writers before migration, preserve
the encrypted issuance key and clock/fence maxima, and schedule coordinator
maintenance. Rollback to a writer that assumes lifetime history is unsupported
after reclamation begins.

ZIO deployments must use the derived bounded runtime. Its queue admission happens
before payload copying and its startup check rejects an accidentally reintroduced
upstream jar. CI verifies the published POM, binary/source jars and actual runtime
classpath so classpath shadowing cannot stand in for a replaced runtime.

No result here establishes production proxy behavior, fleet-wide connection
limits, or a safe production request rate. Those depend on the deployment.
