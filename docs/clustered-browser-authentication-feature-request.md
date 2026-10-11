# Feature request: browser authentication across rolling deployments

Status: requested; feasibility analysis only, not an implementation or release claim.

Applies to Spoonbill 4.0.0 (`53be8c017d46423feb396d91e3b4cb67c853524b`).

## Requested outcome

Support a server-rendered application with multiple live web nodes and rolling releases. A browser should reconnect to another node, retain its valid authenticated session, and restore its selected non-sensitive presentation without losing work or requiring a new login. Old and new application processes must be allowed to overlap during deployment.

A transport reconnection or bounded delay is acceptable; silently discarding presentation, repeating a committed action, or invalidating valid sessions solely because the serving process changed is not. Specify the recovery window and availability behavior rather than promising uninterrupted transport under every failure.

The integration must remain small. Applications should supply domain authorization, proof verification, transaction code and a presentation schema. They should not have to build a distributed authentication protocol, copy framework guards, or centralize every browser connection merely to use the coordinator.

## Why the shipped v4 coordinator is not sufficient

The [deployment contract](browser-auth-v4-deployment.md) requires one live owning provider/coordinator per `(realm, cookieNamespace)`, with the old owner drained and stopped before a replacement starts. `exclusiveAuthorityWriters = true` asserts that condition; it does not elect or fence an owner.

The limitation is specific to the supplied coordinator/provider integration. Spoonbill still exposes lower-level browser APIs, a provider SPI, and durable snapshot primitives. It is possible to design a custom clustered integration, but doing so recreates the infrastructure this feature is intended to provide.

| Existing boundary | Consequence for a distributed application |
| --- | --- |
| [`BrowserAuthentication`](../modules/spoonbill/src/main/scala/korolev/auth/BrowserAuthentication.scala) owns local attached views, leases, presentation storage and publication sequencing. Its installation checks require a coherent set of component instances. | A central JDBC object alone does not coordinate independent web-node runtimes. A transport/provider boundary for that deployment needs an explicit contract. |
| [`BrowserIdentity`, `BrowserView`, `BrowserCeremony`, and `CommittedAuthentication`](../modules/spoonbill/src/main/scala/korolev/auth/BrowserPersistence.scala) carry issuer provenance. `BrowserCapabilityScope` verifies it by object identity. | Serializing these objects or reconstructing them from lookup IDs would not preserve their authority. Remote callers need a supported protocol that keeps capabilities at their owner or reissues them after authoritative validation. |
| [`JdbcBrowserPersistence.Transaction`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserPersistence.scala) has a provider-dependent type and checks its thread and callback lifetime. | JDBC handles and host transaction callbacks cannot simply become asynchronous actor messages. Host proof consumption and session preparation must still execute in one local transaction. |
| [`JdbcBrowserClock`](../modules/security-jdbc/src/main/scala/spoonbill/security/jdbc/JdbcBrowserClock.scala) supports shared observations, but the supplied persistence provider requires exclusive ownership and enables its exclusive cache. | Changing the clock setting alone does not establish a safe multi-process coordinator. Expiry, publication and takeover guarantees must be reviewed together. |
| [`ViewSnapshotSession`](../modules/spoonbill/src/main/scala/korolev/snapshot/ViewSnapshotSession.scala) restores an explicit presentation projection onto freshly authorized state. Component-local state remains ephemeral. | Database-backed sessions alone do not preserve an unfinished form or other presentation. The application must explicitly select recoverable fields, including required component state. |

## Could a cluster singleton satisfy the current contract?

A singleton can be part of a solution, but there is no small supported wrapper that makes the current coordinator transparently remote.

Putting only `JdbcBrowserPersistence` behind an actor leaves the coordinator, physical connection records, local capabilities and guarded presentation on other nodes. That requires a new distributed SPI implementation and command protocol. Putting the entire UI/authentication runtime in the singleton's process satisfies co-location but introduces one serving owner and a handoff boundary; it changes horizontal capacity and still needs durable restoration.

Pekko documents temporary singleton unavailability during handoff, buffering limits, possible message loss, and leases as additional protection against duplicate instances. A singleton's election and stop signal alone are not a database fencing mechanism. A paused old process or already-started JDBC operation must not become authoritative again after takeover. See the [official singleton documentation](https://pekko.apache.org/docs/pekko/current/typed/cluster-singleton.html).

Sticky routing keeps ordinary traffic on one node but does not solve owner replacement. A fresh namespace for every process avoids shared ownership by separating authority; it does not preserve existing sessions. Neither is a substitute for this feature.

**Decision:** request library support instead of adding a substantial application-specific remoting layer or weakening v4's ownership assertion.

## Required contract

### Separate serving nodes from authoritative ownership

Support multiple simultaneous HTTP/WebSocket nodes in one logical authentication scope. Specify what has an exclusive owner: a view, a browser lineage, a partition, or the entire authority service. Avoid an implicit requirement that all UI connections run on one process.

Either of these implementation directions could satisfy the request; delivering both is not required:

1. **A shared-store provider:** each web node runs a local coordinator, while authoritative operations and publication use a defined distributed ownership/fencing protocol. Shared expiry observations cannot depend on another process's stale local checkpoint.
2. **A single authority service:** a supported remote boundary allows web nodes to consult an elected, fenced authority owner. Explicit commands and results cross that boundary; JDBC work and live capabilities remain local to the owner. Handoff, admission, backpressure and ambiguous responses have defined behavior.

Evaluate the shared-store direction first for applications already using a shared authority database. Keep the choice evidence-driven: a remote service must account for its extra network hops, while a shared-store provider must account for its database contention and publication races. Do not introduce an actor-system dependency into core. A Pekko integration, if needed, belongs in an optional module.

Keep the core database-independent. Existing provider interfaces may be extended where their semantics are insufficient; the optional JDBC module can supply a concrete PostgreSQL implementation. An interface alone is not evidence that a backend supports the required atomicity or ownership guarantees.

### Fence takeover and every authoritative receiver

Represent ownership with typed, monotonically advancing epochs or an equivalent backend guarantee. Taking ownership must invalidate the old owner's ability to mutate authentication state, commit presentation, or publish newly protected output. A stale close or delayed cleanup must not remove its successor's view.

Define the linearization boundary for takeover and output admission, including already-admitted output that cannot be recalled from the network. A local pre-check followed by an unfenced delayed publication must not grant the old owner a new opportunity to publish after takeover.

Graceful shutdown must stop new admission, await already-admitted settlement, and release resources before replacement publication. Abrupt failure, network partition, lease loss and a paused process resuming later need separate guarantees. Lease ownership must be enforced by the receivers of protected work; a Boolean flag or a process-local cancellation request is insufficient.

Persisted presentation and browser-supplied IDs remain non-authoritative. After reconnect, resolve the current host principal, browser generation and new physical connection, then claim the view and restore permitted presentation. Later cookie changes must never promote an already-open socket.

### Preserve transaction and recovery semantics

Consume one-use proofs, create the host session, prepare browser authentication and write required transactional audit/outbox records atomically. Do not split that transaction across actor asks, remote JDBC handles, or unrelated databases.

For a remote design, applications register domain handlers at the transaction owner; remote commands identify an operation and its input, not arbitrary executable closures. Preserve the distinction between invocation IDs, proof-use fences, view epochs and delivery handles with Scala 3 opaque types and closed result ADTs. These types supplement runtime validation; they do not prove distributed ownership.

Define retry behavior for lost messages and owner handoff. An unknown commit outcome must recover the original attempt without repeating proof consumption or business effects. A recovered completion permits the existing cookie-delivery workflow; it is not a transferable mutation permit. The implementation must also handle a response lost after acknowledged commit.

Maintain expiry observations across node changes and clock rollback. Retain negative/replay history and security maxima until a safe retirement rule applies. Bound admission, retained resources and recovery queues; capacity pressure must not revive stale capabilities.

### Restore UI state through deployments

Reuse the existing snapshot projection and identity checks where possible. Make it straightforward to declare the ordinary presentation that must survive a move to another node: navigation, selected items, and unfinished non-sensitive work. If component-local state is needed for continuity, provide an explicit supported projection path; do not imply it is already captured automatically.

Restoration must not require a whole-page reset that discards unsaved work. Fresh authorization must remain mandatory. Secrets, transient sensitive disclosures and live capability objects must never be serialized into presentation to achieve continuity.

Document the acknowledgement/durability point for a presentation update. Tests must distinguish acknowledged recoverable work from an event that never reached a server. Normal socket closure during a rollout must not delete the durable projection required by the replacement owner. Stale owners must be unable to overwrite a newer revision.

Define compatible snapshot and protocol versions for the supported rolling-upgrade window. Handle incompatible versions explicitly without partially restoring state or reviving obsolete authority. External media or application actor sessions remain application responsibilities, but the reference handoff must not require tearing them down merely to recreate the UI.

## Acceptance tests

Run real separate processes with shared provider storage and a real browser. A same-JVM test or sequential process restart is not enough to establish overlap safety.

| Scenario | Required evidence |
| --- | --- |
| Planned rolling handoff | Nodes A and B overlap. Authenticate on A, create acknowledged unfinished presentation, drain A and reconnect the same tab to B. No new login, lost selected state, discarded draft or duplicate action. |
| Delayed old owner | Pause A before a write/publication, let B take over, then resume A. Stale mutation, snapshot overwrite, new protected output and cleanup are rejected according to the documented admission boundary. |
| Abrupt owner loss | Terminate A without graceful release. B recovers within the declared window while retaining current authentication and acknowledged recoverable presentation. |
| Partition or lease loss | Exercise the supported ownership mechanism while the old process remains alive. It cannot continue protected work after its authority is fenced. |
| Logout/revocation during takeover | B denies revoked authority and discards protected presentation. An earlier snapshot or delayed completion cannot restore it. |
| Preparation interrupted by handoff | Lose the response before or after commit. Recover the original ceremony, including a factor challenge where supported, without consuming proof twice or fabricating a committed receipt. |
| Snapshot/protocol compatibility | Exercise the supported old/new application versions concurrently; restore compatible state and handle incompatible versions through the documented path. |
| Capacity and repeated handoff | Repeatedly reconnect and replace owners; queued work, leases, connections and retained heap remain bounded. |

Add provider conformance tests for shared expiry, owner epochs, admission, settlement and cleanup. Keep runtime race tests alongside compile-negative tests for incorrect capability/transaction types.

Compare equivalent behavior against fixed baselines. Report p50/p95/p99 latency, throughput, allocations, retained heap after churn, database transactions/round trips and any remote actor hops. Include ordinary protected events and recovery, not just login. The implementation must not regress latency or memory within a predeclared measurement uncertainty budget; unresolved noise is not a pass. Preserve historical benchmark pins rather than silently choosing a different comparison release.

Count the complete application integration, including provider adapters and helpers. Adopting the supported design should remove custom ownership/transport/ceremony plumbing rather than grow it. Measure implementation code, migration code and test fixtures separately and report the total application production-code delta.

## Deliverables and scope

- A documented supported topology and ownership/failure contract, with an implementation usable through the ordinary coordinator API.
- A small generic two-node reference application using public APIs, explicit presentation projection and host transactions.
- Reproducible multi-process/browser correctness tests through the repository's [Nix environment](../flake.nix), plus the stated performance and source-size evidence.
- Migration and operational guidance covering drain, takeover, credentials/keys, schema compatibility, recovery windows and resource limits.

Do not solve this by making live capabilities serializable, disabling the exclusive-owner requirement without evidence, caching authorization for an unspecified interval, or requiring applications to fork framework internals. No universal distributed transaction engine or mandatory cluster technology is requested.

Use only generic workflows and synthetic data in examples and reports. No consumer application names, private deployment details, private paths or identifiable production fixtures belong in the public feature.
