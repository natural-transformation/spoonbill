# ADR 0001 — Model lifecycle decisions around their owners

Status: adopted in the runtime.

## Context

A notification can be admitted, queued, entered by an extension, or abandoned
before it starts. Teardown needs to distinguish those cases. Several booleans
set by different threads make the legal transitions hard to check. Conversely,
putting every handler, queue and transition in a single atomic cell makes an
unrelated delivery contend with admission. The cost shows up under multiple
writers even when the same code performs well with a single writer.

## Decision

Give each protocol an immutable state that describes its legal phases. Keep its
decisions pure. Pass in clock samples, generations and identities. A decision
can return commands for its interpreter, or answer a hot-path question directly,
such as whether an incoming notification should be recorded, delivered or
ignored. Keep the model next to its interpreter. Shared lifecycle primitives
live below their callers in the effect module. Domain rules for authorization
belong in the pure core.

The interpreter owns callbacks, handles, queues, scheduling and synchronization.
Commit coupled facts together, such as admission and queue order, or a latch's
settled state and its result. A `StateCell.modify` function can run more than
once in a compare-and-set loop. Claim the work there and perform irreversible
effects once, after the winning update. A monitor is appropriate when state
publication must be ordered with a change to an interpreter-owned data structure.
[StateCell](../../modules/effect/src/main/scala/korolev/effect/StateCell.scala)

### Extension delivery

One short admission monitor orders registration, startup, enqueue and the
teardown claim. Before startup, the runtime retains the newest state and keeps
messages in order. A serial lane owns delivery progress. It retains each call's
identity across scheduling so a refused start can be reported against the
correct extension. Each extension publishes the last state whose callback it
actually entered. A queued or refused callback cannot advance that state.

Teardown takes the registered handlers once, waits for in-flight work up to its
bound, and invokes destroy in reverse registration order with the last entered
state or the admitted state as fallback. A late setup result arriving after
teardown is destroyed by the receiver. These boundaries allow delivery to run
outside the admission monitor. Whole-runtime CAS and a single monitor for both
admission and delivery added contention on the ordinary notification path.
[Extension runtime](../../modules/spoonbill/src/main/scala/korolev/internal/ExtensionRuntime.scala) ·
[Delivery contract tests](../../modules/spoonbill/src/test/scala/korolev/internal/ExtensionRuntimeDeliverySpec.scala)

### When to share a model

Compare what observers can see on admission, completion, cancellation, and a
late callback before extracting a shared primitive. Three examples guide reuse.

| Protocol | Invariant | Boundary the caller owns |
| --- | --- | --- |
| [Completion latch](../../modules/effect/src/main/scala/korolev/effect/CompletionLatchMachine.scala) | The first completion publishes one outcome. Later observers replay it. | Waiter order and notification. A pool can expose a disposal *claim* before cleanup finishes, which requires a different contract. |
| [Drain gate](../../modules/effect/src/main/scala/korolev/effect/DrainGateMachine.scala) | Closing refuses new admissions. Drained means all previously admitted work has finished. | One-use release, wakeups, keyed admission and any separate JDBC lanes. |
| [Late handle](../../modules/effect/src/main/scala/korolev/effect/LateHandleMachine.scala) | A handle installed after finish is claimed for cancellation. | Store or detach the specific handle alongside state, then run its cancellation. |

Keep an existing explicit protocol when its state already has a clear owner
and tests detect illegal moves. A local parser cursor, callback table or
one-shot release token can stay with that owner. A shared actor runtime would
also impose scheduling and cancellation semantics on backends that differ.

## Consequences for changes

For every lifecycle or adapter change, cover three boundaries:

1. Test the transition rules without an executor. Track the obligations each
   event creates or settles. For example, stopping admission releases all
   capacity waiters even if a buffered value still belongs to a consumer.
2. Test the interpreter with controlled callbacks and completion signals. Pause
   an effect after ownership has changed, then fail or cancel it. Check which
   caller was notified and which owner can still release or retry each resource.
3. Add an end-to-end case through the public entry point and real effect backend
   or adapter. Observe what the consumer receives, the order of its terminal
   signals, or the physical resource that closes. Keep the model's expected
   state out of that assertion so a matching mistake in the model and
   interpreter can still fail the test.

Test the decision table with event histories and the interpreter with observable
effects. For example, test a browser reconnect by delivering a late event from
the old socket and checking emitted frames and connection events. Socket
identity, timers, decoding promises and DOM effects belong to the interpreter.
The Connection and Bridge models own epoch, readiness and barrier decisions.
An old decoder must never publish through or release the new socket's reader.
[Connection lifecycle cases](../../misc/connection-lifecycle-tests.mjs) ·
[Bridge and sensitive barrier laws](../../misc/bridge-machine-tests.mjs)

CI scans core sources and named Scala and JavaScript machine files for mutable
lifecycle state, effects and ambient inputs. Compiler boundary tests check the
core classpath. Review still has to establish who owns an effect, when authority
is checked, and what happens on reentry or cancellation. For a hot path, compare
the optimized interpreter against its pure model on the same inputs and measure
the public operation under contention.
[Purity guard](../../scripts/check-core-purity.mjs) ·
[Core boundary tests](../../modules/core/src/test/scala/spoonbill/security/CoreBoundarySpec.scala) ·
[Performance validation](0004-runtime-performance-validation.md)
