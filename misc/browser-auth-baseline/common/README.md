# Executable reference policy

`ReferencePolicy` is shared by the executable memory and JDBC providers. Its
`json` method reports actual constructor values; it is not a separately maintained
configuration. The servers select short or representative PBKDF2-HMAC-SHA256
proofs. They use 1 or 100,000 iterations respectively, a fixed public sixteen-byte
synthetic salt, and 256-bit output. Both providers seed the same 1,000 account names;
`alice` and `bob` retain their existing demonstration credentials. Half the
accounts require the fixed six-digit factor. Seed hashing occurs once per profile,
and verification performs the configured proof for every admitted attempt.

The shared profile uses session/ceremony/challenge/delivery/reconnect/bootstrap
lifetimes of 900/120/90/60/30/15 seconds. Challenge expiry cannot extend the
original ceremony. Delivery expiry is the earlier of preparation plus its TTL and
the original ceremony expiry. A successfully prepared challenge does not shorten
the distinct pending-delivery window. Operation authority expires after thirty
seconds in both providers. Password and factor attempts share a
five-attempt, sixty-second browser window, and a separate `(browser, account)`
window with the same bound. There is no account-wide throttle across independent
synthetic browser bindings. Each rate-window map has at most 32,768 entries;
expired rate windows are disposable, while proof/outcome/generation history is
not. Account lookup of an unknown name is rejected by both application providers
before proof admission.

The live ceremony bound is 1,024. Four ceremonies are retained per browser
binding; consuming a ceremony does not reset that binding's retained limit.
Retained host record kinds have a 32,768-entry bound and audits have a separate
65,536-entry bound. JDBC retains up to 131,072 view epochs separately: a complete
browser workflow can create up to eight views across both login flows and their
navigation/logout. The arithmetic covers two host records, eight view epochs and
four audits per declared operation, plus the fixed initial population, without
deleting negative/replay fences. Memory ownership uses fresh object leases and
does not keep a durable view-epoch ledger. These headroom assertions do not prove
the full workload finishes within its time cap or validate its collector.
Each independently measured process/cell still needs an isolated memory instance
or database schema. Reusing one schema across all measurement blocks is not an
authorized reset protocol. Live delivery material is bounded at 1,024; activation
or expiry retirement frees material, not its durable authority history.

View bounds are 1,024 active globally, sixteen per binding, 1,024 disconnected,
and 1,024 bootstrap entries. Stored presentation is capped at 10,000 nodes per
view. Direct constructor fixtures without `Some(ReferencePolicy)` intentionally
retain their older small regression settings. They are not the executable
measurement profile.

`ReferenceAdmission` owns at most 256 ordinary pending/running callbacks before
executor submission, through actual result settlement. The memory application
also reserves one coalesced maintenance operation, counts it, and drains it on
shutdown. Its worker executor must accommodate the reserved slot in addition to
the ordinary bound. Guard cleanup does not require ordinary proof admission.
The JDBC adapter retains its independent bounded SQL gate, reserved guard-cleanup
queue, and coalesced maintenance slot; ordinary callback saturation cannot block
their admission. JDBC shutdown drains both callback ownership and SQL settlement.
`callbackCounts` and the guarded JDBC resource snapshot expose actual current and
peak callback counts; the resource observation excludes itself from its current
callback count.

`MemoryBrowserAuth.retainedCounts` exposes every retained host collection.
`JdbcBrowserSecurity.retainedCounts` counts all reference/framework authority
tables using one explicit diagnostic SQL execution. These diagnostics belong
outside measured work unless their cost is part of the declared observation.
They do not count the framework's component graph: v3 `SessionsService` constructs
a separate `StateManager.cached` for snapshot-backed views without a provider
factory hook. The fixed application topology and full JVM allocation/heap/process
collectors must account for that graph. Adapter `Resources.nodes` is deliberately
not labeled total runtime nodes or total memory. Required whole-runtime collectors
and measured ceilings remain separate Phase 0 evidence.
