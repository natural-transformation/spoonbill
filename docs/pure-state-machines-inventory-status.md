# Rollout inventory status

Independent read-only audit of aggregate `730ef03ca2eea35f27dae1755079b179bcef7cc7`,
its source and per-area progress records, updated through the integrated stream
and caller sources at `349116bc`. The audit itself ran no builds or tests. “Complete” means the
inventory implementation/disposition and local evidence exist; it does not replace
final aggregate CI, independent review, or the final four-workload pilot gate.
“Missing” means not yet accepted/integrated, including actively implemented work.

All 56 by-area rows now have implementation or retention dispositions. The new
stream contracts and finite fanout publishers are integrated with focused tests,
but the first implementation failed its common-semantics cost screen. The
integrated compact variant meets the Hub/buffer short-screen guide; sort
throughput and the final aggregate acceptance gates remain open. This is
implementation coverage, not final performance acceptance.
The [design progress](pure-state-machines-design.md#rollout-progress) links the
per-area commands, behavioral evidence and measured acceptance records.

## Shared candidates

| Protocol | Disposition |
| --- | --- |
| Deadline | Complete: shared request/message assembly rules; HTTP setup delegates them; zio has the deliberate independent copy. Required-operation waits and session idle callbacks retain distinct characterized first-winner contracts. Orphan-topic idle rules have their own model. |
| Drain | Complete: guarded writes, dispatcher and memory/JDBC lanes share count/closure decisions. Server active connections and keyed coordinator have separate models because their contracts differ. |
| Once | Complete dispositions: completion latch adopted by component signals, scheduler, queue cancellation and handleConsumed; resettable AsyncTable, claim-before-cleanup pool disposal, releaseOnce and existing lane/result promises remain explicitly distinct. |
| Pending registry | Complete: bounded membership and late-handle decisions in browser requests/disclosure scope; Frontend and SensitiveRuntime retain domain-specific payload/ordering responsibilities. |
| Setup ownership | Complete: Akka/Pekko/zio adapter ownership shared. Standalone SetupDeadline retained with its different attached/expired cleanup ownership. |

## Every by-area inventory row

| Area | Inventory item | Disposition and basis |
| --- | --- | --- |
| Sessions | Guarded session | Complete — GuardedSessionMachine, ordered teardown, attach modes; acquisition and late idle-handle defects fixed separately. |
| Sessions | Legacy session | Complete — LegacySessionMachine; terminal input outcomes now reach retirement, with normal cancellation diagnostics preserved. |
| Sessions | Bounded wait | Retained — one first-winner permit, deterministic RequiredTiming cases; not an absolute-time assembly deadline. |
| Sessions | Session registries | Complete — SessionRegistryMachine metadata slots; apps remains the explicit AsyncTable resource/publication protocol. |
| Sessions | Write gate | Complete — compact DrainGateMachine with owner/drain matched gate. |
| Sessions | Presentation store | Complete — selected PresentationMachine decisions and accepted five-cell cost gate; source preserves clock/check/sweep/partial-publication order. |
| Sessions | Snapshot session | Retained — single Initialization state, wrong-move/concurrency/revision/schema tests. |
| Sessions | Long-polling topic | Complete — OrphanTopicMachine plus separately fixed stale-entry deletion. |
| Connection | Connection and published view | Complete — FrontendConnectionMachine with accepted frame-path gate. |
| Connection | Departure and navigation fences | Complete — pure counter/fence decisions; independently published counters keep their original effect boundaries. |
| Connection | Outgoing item | Complete — explicit variants and redacted representations; authority/release chain remains interpreter code. |
| Connection | User actions and downloads | Complete — shared pending ownership, preserved stream-handle cancellation boundaries and added download characterizations. |
| Connection | Frontend close sequence | Complete — once-claimed ordered close model after separate cleanup/publication-race fixes. |
| Connection | Application render cycle | Retained — one accepted-candidate fact in the serialized lifecycle; rejected/accepted-baseline laws cover wrong moves. |
| Connection | Application instance lifecycle | Complete — ApplicationLifecycleMachine after separate duplicate/concurrent/after-destroy startup fixes. |
| Connection | Browser requests | Complete — PendingRegistryMachine plus LateHandleMachine paired with interpreter handles. |
| Components | Remaining timer lifecycle | Complete — TimerRenderMachine and TimerMachine overflow rules; four measured cells accepted. |
| Components | Component ownership | Complete — compact registry/owner decisions and completion latch; full five-cell matched guide accepted. |
| Components | Baseline and loader | Complete — ComponentBaselineMachine and opaque parameter data; separate loader bugfix, 70-case selection and five-cell gate. |
| Components | Legacy delay | Complete — LegacyDelayMachine with inline scheduling/failure/cancel characterizations. |
| Components | Sensitive presentations | Complete — SensitiveRuntimeMachine, shared pending metadata, atomic handle/payload ownership, preserved clear-before-callback order. |
| Components | Serial lane | Retained — explicit atomic ownership/settlement protocols and source/behavior/race laws; no unneeded synchronization replacement. |
| Components | Extension runtime | Complete — selected ownership-split pilot; corrected live-worker allocation gate retained. Final aggregate pilot rerun still required. |
| Security | Coordinator gate | Complete — compact pure admission/key/writer-barrier decisions with original callback queue ownership; all four cost cells accepted. |
| Security | Browser view lifecycle | Complete — BrowserViewMachine wired; six phases, independent initialization/cleanup claim, Future/native IO evidence. |
| Security | Provider admission | Complete — shared compact drain decisions with independent JDBC lanes and retained publication/close boundaries. |
| Security | Clock lease | Complete — JdbcClockMachine decisions with separate observation/ack frontiers and unchanged SQL/lock order. |
| Security | One-use lease | Complete — canonical Fresh/Issued/Admitted/Expired state and alias ownership laws. |
| Security | Invocation table | Complete — InvocationDecisionMachine in core; authority/transaction effect chain remains in place. |
| Security | Snapshot references | Complete — SnapshotReferenceMachine after separately fixed foreign read-pin removal; memory/JDBC twin laws. |
| Security | Memory record store | Complete — record/frontier models and checkpoint laws; root reports independent effect-order review found no drift. Older area-note wording awaiting signoff is editorial lag. |
| Transport | Header/message deadlines | Complete — shared DeadlineMachine and original wrapper scheduling contracts. |
| Transport | HTTP server connection | Complete disposition — body ownership model, shared header deadline, upgrade enum; independent first-error/cancel facts retained. |
| Transport | HTTP client exchange | Complete disposition — connection ownership model and bounded pool handoff; distinct response/body/retirement claims and writer result handle retained. |
| Transport | Chunked body | Complete — design 8's pure checked scan and compact payload cursor pass all four cost cells; integrated source matches measured snapshots, and HTTP182/standalone59 pass. All rejected evidence is retained. |
| Transport | WebSocket message assembly | Retained — existing immutable decode/merge stages and one observer flag; public cases pin callback-between-completion-and-tail-rearm order. |
| Transport | Close reservation | Complete — SocketCloseReservationMachine plus direct ordering/once-only laws. |
| Transport | Raw socket | Retained — one explicit atomic state and read/close/observer laws; native effects unchanged. |
| Transport | Upgrade input | Complete — named Available/Claimed/Sealed enum in the existing cell. |
| Transport | Standalone WebSocket | Complete disposition — output/close model, SetupInput model and gate; SetupDeadline retained; late-attachment defect fixed separately. |
| Transport | Text limit | Complete — pure compact scanner/fallback; selected fifth design passed all four throughput/allocation/p99 cells. |
| Effects | Queue | Complete — pure per-operation decisions, separate terminal/cancel fixes, corrected live-worker cost evidence. |
| Effects | AsyncTable | Complete — Waiting/Settled per-key enum after failure-publication fixes; resettable semantics retained. |
| Effects | Resource pool | Retained — one immutable PoolData with allocation IDs and explicit disposal claim; extensive separate fixes and ownership tests; raw outer-caller adoption limits documented. |
| Effects | Scheduler | Complete — shared result latch and ScheduledStreamMachine after separate lost-waiter/replay/cancellation fixes. |
| Effects | Hub and Stream.sort | Implemented — lossless configured fanout, bounded FIFO racks and explicit overflow; independent read ownership and nonjoining cleanup pass Future, IO and ZIO checks. Finite publisher adoption and legacy overflow closure are integrated. Compact Hub meets the cost screen; sort still misses throughput and is under refinement. |
| Effects | Stream.buffer | Implemented — eager bounded reader, accepted-prefix drain, atomic batch selection, owned timers and nonjoining cancellation; model/native/ordering checks pass. The compact implementation meets the short-screen throughput/allocation guide; final aggregate gates remain open. |
| Adapters | Setup ownership | Complete — shared adapter model; standalone differs and has a documented retain disposition. |
| Adapters | Stream subscriber | Retained — equivalent single immutable state/monitor in both adapters, 12 behavioral cases each. |
| Adapters | Stream publisher | Complete — lifecycle model/fixes plus explicit 128-item lossless fanout; tests pin both demand resumption and slow-subscriber cancellation in Akka/Pekko. |
| Adapters | Zio frame deadline | Complete — pure scan/timer with event-loop scalar storage, preserved buffer ownership and accepted four-cell gate. |
| Browser | Connection | Complete — connection-machine.js, transport epochs, timer generations, behavior/native/optimized-bundle evidence. |
| Browser | View gate and heartbeat | Complete — bridge-machine.js; public/native behavior tests and CI wiring. |
| Browser | Sensitive barriers | Complete — pure metadata/barrier/replay decisions; DOM/payload/listener effects remain in interpreter and listener order is preserved. |
| Testkit | Recovery gates | Complete — four-phase model after separate stale-rearm fix. |
| Testkit | Payload store | Complete — one slot map per reference with existing reference-law validation. |

## Boundary and remaining work

- SensitivePresentation metadata and SensitiveError now live in core under the same
  public package names. Plaintext, callbacks, scheduling and cancellation remain
  runtime-owned. The defining JAR changes; matching core/runtime artifacts are
  required. Named-import exceptions are guarded by definition/source checks, not
  a blanket runtime dependency allowance. This boundary is documented and tested.
- JS model tests and JS purity checks are present in `.github/workflows/scala.yml`;
  older area-note statements assigning that wiring to the coordinator are stale,
  not a missing implementation.
- Shared idle comparison is now closed by the six unchanged-policy cases and the
  separately reproduced/fixed exact-handle cancellation race. No idle row remains
  unaddressed.
- No additional by-area inventory row was found missing; the final stream
  replacements and caller adoption have since been integrated. Common-semantics
  stream cost evaluation, final aggregate correctness/CI, fresh original pilot
  workloads and final review remain global gates rather than new inventory machines.
- This audit does not require merging unrelated one-shot capability guards,
  security-check chains, ordinary data builders, or existing named-state protocols
  merely to add another Machine file.

