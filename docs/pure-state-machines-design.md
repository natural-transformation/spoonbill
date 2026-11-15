# Pure state machines: design and plan

Status: draft 1, 2026-11-15. Nothing in this document is implemented. It asks
for decisions first; see [decisions to confirm](#decisions-to-confirm).

## Summary

Much of the runtime keeps its state in several mutable fields at once: a lock
with three variables, a few `@volatile` flags, some atomics. Each field is
simple. Together they form a state machine that is written down nowhere. The
legal states and the legal moves exist only in the order of the code.

One part of the repository already does it differently. `TimerMachine` is a
pure function from a state and an event to a new state and a list of commands.
`TimerRuntime` holds that state and runs the commands. The rules of the timers
can be read in one place and tested without a thread.

This document proposes to make that the rule:

1. **One pattern.** A machine is an immutable `State`, an `Event`, a `Command`
   and one function `step`. An interpreter holds the state in one cell, feeds
   events and runs commands. See [the pattern](#the-pattern).
2. **A pilot.** The extension runtime is refactored first. Its lock and its
   mutable fields become `ExtensionMachine`. Its behaviour does not change.
   See [pilot](#pilot-the-extension-runtime).
3. **An inventory.** Every place in the main sources that holds state was
   read and sorted into eight kinds. About 65 places hide a machine. Five
   small protocols are written about forty times. See
   [inventory](#inventory).
4. **A plan.** The shared small machines come first, then the lifecycles, one
   machine per pull request. The kinds that hide no machine are left alone,
   with the reason. See [plan](#plan) and
   [what stays as it is](#what-stays-as-it-is).

It does not propose a general actor library, and it does not hand the
interpreters to the ZIO or Pekko modules. [Alternatives
considered](#alternatives-considered) says why.

## Goal and limits

The goal: for every lifecycle and every protocol in the main sources, the
states and the moves between them are one immutable value and one pure
function. Effects stay where they are, in a thin interpreter around it.

What this buys:

- **The rules can be read.** A reviewer checks a transition table, not an
  interleaving of threads.
- **The rules can be tested as functions.** Thousands of generated event
  sequences run in milliseconds, with no executor and no timing.
- **Illegal states cannot be built.** Three booleans allow eight
  combinations. When the code means four of them, an `enum` has exactly the
  four.

What this does not buy, and the document does not claim:

- **It does not make the interpreters correct.** Which thread starts a
  callback, a deadline that must not wait for a worker, a cleanup that must
  survive a cancellation: these stay in effect code. Of the six code findings
  in the last three reviews of the extension contract, five were of this
  kind.
- **It is not faster.** A pure step allocates. Hot paths are measured before
  and after, and some are left alone for that reason.

Limits of the work:

- **No behaviour change.** Every step is a refactor. Existing specs pass
  unchanged. Where a rule was not pinned by a test, a test is added first and
  must pass on the old code. One exception: a spec that reads a private field
  of an interpreter is rewritten first, in a change of its own. The Node tests
  of the browser client are the main case.
- **No public API change.** Machines are `private[spoonbill]` or private to
  their module.
- **Main sources only.** State in tests is not in scope. The two published
  test kits are listed, with low priority.

## What counts as hidden state

State is hidden when the code can be in a state that no single value names.

| Sign | Example in this repository |
| --- | --- |
| A lifecycle spread over several fields | `ExtensionRuntime`: a lock with `phase`, `newest`, `earlyMessages`, plus `stopped`, `admitted`, `ordered`, `active` |
| A state encoded as a number | `SerialLane`: `Idle`, `Scheduled`, `Running`, `Draining`, `Closed` are `Int` constants in an `AtomicInteger` |
| A state encoded as `null` or as "not yet set" | `Scheduler.JobHandler`: `completed` and `promise` are `null` until one of them is set |
| A state that is a callback in a field | `SetupInput`: `onEnded` holds what to do when the input ends |
| A state that is only the order of effects | A teardown that works because one flag is set two lines before another |

Every place that holds state falls into one of eight kinds. The kind decides
what is done with it.

| Kind | What it is | What is done |
| --- | --- | --- |
| A | A lifecycle or protocol hidden in several fields | Extract a machine |
| B | An explicit machine, encoded in numbers, variables or `null` | Restate it as a machine |
| C | Already one immutable value in one cell, with the moves mixed into effect code | Lift the moves into a machine object and test them |
| D | A store, cache or registry | A machine when it has lifecycle rules of its own; else left as it is |
| E | A one-shot flag: claimed, consumed, released | Left as it is, unless it belongs to a machine of kind A |
| F | Local mutation inside one function: a cursor, a builder | Left as it is |
| G | Metrics, counters, configuration | Left as it is |
| H | A buffer or state owned by an outside engine: TLS, NIO, Netty, a JDBC transaction | Left as it is |

## The pattern

It is the pattern of `TimerMachine` and `TimerRuntime`. The rules below state
what those two files already do, and add what the pilot needs. Where the two
files do not follow a rule yet, the rule says so.

### The machine

- One object per machine, named `XMachine`, in a file `XMachine.scala`.
- It defines `State`, `Event` and `Command`, and one function
  `step(state, event): (State, Vector[Command])`. A machine that needs the
  time takes `now` as an argument, as `TimerMachine` does.
- `State` is immutable. It holds data and identities: an index, a name, a
  generation. It holds no closure, no effect value, no handle of a timer or a
  socket. `TimerMachine` says so in its header: "Contains no reducers, effect
  values or scheduler handles."
- `step` is total. Every event has a defined result in every state. An event
  that makes no sense in a state changes nothing and returns no command. It
  never throws for an event. `TimerMachine` has one exception today: when its
  serial number overflows, `step` throws, and `TimerRuntime` catches that and
  edits the machine's state itself. The plan moves that case into the machine
  as a state of its own.
- `step` reads no clock, no entropy, no system property and no thread. It
  takes no lock and touches no mutable field or collection.
- A `Command` is data. It says what the interpreter must do. Running it can
  produce new events.

### The interpreter

- It holds one cell. The cell has the machine's `State` and a table from the
  machine's identities to handles: the handlers of an extension, the cancel
  function of a timer. Both change in one atomic step.
- It applies `step` atomically and runs the commands after that, outside the
  atomic section. No application code runs inside it.
- It owns everything about effects: which thread, which time bound, what
  happens on cancellation, how an error is reported.
- It is the only caller of `step` in main code.

### The cell

Interpreters keep their state in three ways today: a lock around a `var`
(`TimerRuntime`), an `AtomicReference` with a hand-written retry loop
(`Queue`, `GuardedStateManager`), and separate fields. The plan adds one small
class and uses it everywhere:

```scala
/** One immutable value, changed by pure functions. */
final class StateCell[A](initial: A) {
  def get: A
  /** Applies `change` atomically. `change` can run more than once, so it must be pure. */
  def modify[B](change: A => (A, B)): B
}
```

It is a compare-and-set loop over an `AtomicReference`. It lives in the
effect module, next to `Var`, so every module can use it.

An interpreter can keep a lock in place of the cell when a command has to run
inside the atomic section. The Reactive Streams subscriber of the Pekko
adapter is such a case: it asks for the next element under its lock, on
purpose, and a retry would ask twice. The reason is then written at the lock.

### The guard

`scripts/check-core-purity.mjs` checks today that `modules/core` uses no
runtime dependency, no clock, no mutable field and no effect type. The plan
extends it: every file named `*Machine.scala`, in any module, must pass the
same rules. A machine then cannot grow a `var` or a `Future` without failing
the build.

`TimerMachine.scala` is the only such file today, and the rules flag one line
in it: `step` keeps its result in a local variable. The guard does not tell a
local variable from a field. So that function is rewritten without it, with
`TimerMachineSpec` as the proof that nothing changed.

Machines stay next to their interpreters. They do not move to `modules/core`:
that module is the domain kernel of browser authentication, and the modules
below the runtime (`effect`, `http`) cannot depend on it. The inventory names
one exception, a set of decisions that belongs to that kernel.

### The tests

Each machine gets a spec of its own, with two parts.

- **Cases.** One case per rule, written as events in and commands out.
- **Properties.** Generated event sequences, checked against invariants.
  ScalaCheck is already a test dependency of the modules.

Each interpreter keeps its existing specs. They pin the effects: threads,
bounds, cancellation.

## Pilot: the extension runtime

### Today

`ExtensionRuntime` keeps one logical state in nine places.

| Where | What it holds |
| --- | --- |
| `lock`, `phase`, `newest`, `earlyMessages` | The phase, and what was written before delivery started |
| The queue inside `SerialLane` | The waiting steps: one check and one call per extension for each notification |
| `Delivery.admitted`, one per notification | Whether the check of that notification passed |
| `Active.received`, one per extension | The last state that was passed to that extension's `onState` |
| `active` | The extensions that are set up, or the mark that teardown took them |
| `ordered` | The extensions that get notifications |
| `stopped` | No new call may start |
| `admitted` | The state the view was accepted with |
| `unscheduledReported` | Whether a refused lane was reported |

The rules are spread over eleven methods: `state`, `message`, `enqueue`,
`register`, `start`, `handle`, `failed`, `report`, `stop`, `destroyAll` and
`lastState`. They can only be tested through threads, an executor and
effects.

### The machine

`ExtensionMachine.scala` sits next to `ExtensionRuntime.scala`.

```scala
private[spoonbill] object ExtensionMachine {

  enum Notification[+S, +M] { case State(value: S); case Message(value: M) }
  enum Callback { case Setup, OnState, OnMessage, OnDestroy }
  /** What the interpreter learned from an error. The machine never sees a Throwable. */
  enum Failure { case Denied, Aborted, Other }

  /** One extension that is set up, in setup order. Its handlers stay in the interpreter. */
  final case class Slot[S](name: String, received: Option[S])

  /** The notification that is being delivered: checked or not, and the next slot to call. */
  final case class Current[S, M](notification: Notification[S, M], checked: Boolean, next: Int)

  enum Phase[S, M] {
    case Recording(newest: Option[S], messages: Vector[M])
    case Delivering(waiting: Queue[Notification[S, M]], current: Option[Current[S, M]], targets: Int)
    case Stopped()
  }

  /** The step that the lane is running now. */
  enum Running { case Check; case Call(slot: Int, callback: Callback) }

  final case class State[S, M](
    guarded: Boolean,                 // a check runs before each delivery
    phase: Phase[S, M],
    admitted: Option[S],              // the state the view was accepted with
    slots: Option[Vector[Slot[S]]],   // None once teardown took them
    running: Option[Running],
    unscheduledReported: Boolean
  )

  enum Event[+S, +M] {
    case Admit(state: S)              // setup begins
    case Written(state: S)            // the observer saw a write
    case Published(message: M)        // the observer saw a message
    case Register(name: String)       // a setup completed
    case Begin                        // every setup completed: start delivery
    case Next                         // the lane is free for one step
    case Succeeded                    // the step of the lane succeeded
    case Failed(failure: Failure)     // the step of the lane did not succeed
    case Halt                         // the view ends
    case Take                         // teardown takes the extensions
    case Unscheduled                  // the execution context refused the lane
  }

  enum Command[+S, +M] {
    case Offer(steps: Int)                                  // this many steps became available
    case Check(notification: Notification[S, M])            // run the guarded check
    case Call(slot: Int, notification: Notification[S, M])  // call the handler of this slot
    case Reject(last: Option[S])                            // the setup came too late: destroy it now
    case Destroy(slot: Int, name: String, last: Option[S])  // destroy, in the order given
    case Report(text: String)                               // report, with the error at hand
    case Close                                              // close the lane
  }

  def initial[S, M](guarded: Boolean): State[S, M]
  def step[S, M](state: State[S, M], event: Event[S, M]): (State[S, M], Vector[Command[S, M]])
}
```

The phases move in one direction: `Recording` to `Delivering` to `Stopped`,
or `Recording` straight to `Stopped`.

| Event | In | New state | Commands |
| --- | --- | --- | --- |
| `Admit(s)` | any | `admitted = s` | none |
| `Written(s)` | `Recording` | `newest = s`; the state before it is dropped | none |
| | `Delivering` | `s` is appended to `waiting` | `Offer(1 + targets)` |
| | `Stopped` | unchanged | none |
| `Published(m)` | `Recording` | `m` is appended to `messages` | none |
| | `Delivering` | `m` is appended to `waiting` | `Offer(1 + targets)` |
| | `Stopped` | unchanged | none |
| `Register(name)` | `slots` present | a new slot at the end | none |
| | `slots` taken | unchanged | `Reject(admitted)` |
| `Begin` | `Recording` | `Delivering`: `waiting` is the newest state, or else the admitted state, followed by the messages; `targets` is the number of slots | `Offer(n × (1 + targets))` for `n` waiting notifications |
| | other | unchanged | none |
| `Next` | `Delivering`, nothing current, guarded | the head of `waiting` becomes current, unchecked; `running = Check` | `Check(notification)` |
| | `Delivering`, nothing current, not guarded | the head of `waiting` becomes current, checked. With no slot, nothing stays current | none |
| | `Delivering`, current is checked | for a state, `received` of the slot is set; `next` moves on; after the last slot nothing is current; `running = Call` | `Call(slot, notification)` |
| | other | unchanged | none |
| `Succeeded` | `running = Check` | the current notification is checked. With no slot, nothing stays current. `running` is empty | none |
| | other | `running` is empty | none |
| `Failed(f)` | `running = Check` | `Stopped` | `Close`, and `Report("Extension delivery was stopped: its check failed")` unless `f` is `Denied` |
| | `running = Call` | `running` is empty | `Report("Extension <name>: <callback> failed")`, unless `f` is `Aborted`, or `f` is `Denied` and the phase is `Stopped` |
| | `running` empty | the step that `Next` would have started is skipped, and treated as failed in the same way. A skipped call does not set `received` | as above |
| `Halt` | any | `Stopped`; what was recorded or waiting is dropped | `Close` |
| `Take` | `slots` present | `slots` taken; `Stopped` | `Close` if the phase was not `Stopped`. Then `Destroy(slot, name, last)` for every slot, newest first, where `last` is `received`, or else `admitted` |
| | `slots` taken | unchanged | none |
| `Unscheduled` | not yet reported | reported | `Report("Extension delivery could not be scheduled")` |
| | reported | unchanged | none |

Three notes on the table.

- **One step per token.** A notification is `1 + targets` steps: its check,
  then one call per extension. A session without a guard has no check, and
  its first step does nothing. It is kept, so that the numbers a test can see
  stay the same.
- **`Failed` with nothing running.** A lazy effect can fail before its first
  instruction, for example when its runtime refuses the task. The lane then
  reports a failed step that the machine never handed out. The machine skips
  that step, so the steps and the tokens stay in line. The first step of a
  notification counts as a failed check, also without a guard. Today the lane
  item itself is reported as failed, which is the same outcome.
- **`Take` also stops.** Today `destroy` always stops first. The machine makes
  it a rule: no call can start once the extensions are taken.

### The interpreter

`ExtensionRuntime` keeps its constructor and its methods. Inside, it has:

- **One cell**: the machine's state and the handlers of each slot.
- **The lane**, unchanged. Its items become tokens: one token is the right to
  ask the machine for one step. What the step is comes from the machine, so
  the order of the work is decided in one place.
- **The effect code**, unchanged: the bounded `setup` with its hand-over of
  the handlers, the wait for the call in flight, `onDestroy` inside a task and
  inside its bound, the redaction of snapshot rejections in reports.

```scala
private final case class Held(machine: State[S, M], handlers: Vector[Extension.Handlers[F, S, M]])
private val cell = new StateCell(Held(ExtensionMachine.initial(checks.nonEmpty), Vector.empty))
private val lane = new SerialLane[F, Token.type](_ => runStep(), (_, error) => stepFailed(error), unscheduled)

def state(value: S): Unit = execute(advance(Event.Written(value)))

// `nextStep` is one atomic step: it feeds Event.Next and, for a Call,
// looks the handlers of the slot up in the same step.
private def runStep(): F[Unit] =
  nextStep() match {
    case None                        => Effect[F].unit
    case Some(Step.Check(n))         => check(n).map(_ => execute(advance(Event.Succeeded)))
    case Some(Step.Call(handlers, n)) => call(handlers, n).map(_ => execute(advance(Event.Succeeded)))
  }

private def execute(commands: Vector[Command[S, M]]): Unit = commands.foreach {
  case Command.Offer(steps) => repeat(steps)(lane.enqueue(Token)); lane.wake()
  case Command.Close        => lane.close()
  ...
}
```

The lock is gone. So are `phase`, `newest`, `earlyMessages`, `stopped`,
`admitted`, `ordered`, `active`, `unscheduledReported`, and the classes
`Active`, `Delivery` and `Call`.

Why tokens are safe: a token is put into the lane only after the atomic
change that added its work. So the tokens that were put into the lane are
never more than the steps the machine was given. The lane handles one token
at a time, and each token asks for one step. So the k-th token always finds a
step, and when the lane has handled every token, no step is left. A stop
drops both.

### What does not change

- The constructor and the methods of `ExtensionRuntime`, `Bound`, `Checks`,
  `TimedOut` and `SetupAborted`. `SessionsService` is not touched.
- `SerialLane`. Its guarantees were reviewed with the contract: a handler
  starts in a task, one at a time, on no deeper stack.
- Every existing spec, and the six probes of the outside review. None is
  edited.
- `pending` and `recorded`, which the specs read.

### Where each rule goes

| Rule | Today | After |
| --- | --- | --- |
| Only the newest state is kept before delivery | `newest` under the lock | `Phase.Recording` |
| The attach state comes first, then the early messages | `start` under the lock | `Begin` |
| A write and the start of delivery have one order | The lock | One atomic step of the cell |
| Notifications are delivered in the order they arrived | The order of `enqueue` under the lock | `waiting` |
| No extension is called before the check passed | `Delivery.admitted` | `Current.checked` |
| One call at a time, each on its own | One lane item per call | One token per call |
| `lastState` is the last state the extension received | `Active.received`, set before the call | `Slot.received`, set in the step that hands the call out |
| Nothing starts after a stop | `stopped`, read before each call | `Phase.Stopped` |
| An extension is destroyed once, newest first | `active.getAndSet(None)` | `Take` |
| A setup that completes after teardown is destroyed at once | `register` returns false | `Reject` |
| A denied access after the view closed is not reported | `report` | `Failed` |
| A refused lane is reported once | `unscheduledReported` | `Unscheduled` |
| A callback starts in a task, never on the writing thread | `SerialLane` | `SerialLane`, unchanged |
| Bounds, cancellation, the thread of `onDestroy` | `ExtensionRuntime` | `ExtensionRuntime`, unchanged |

### Tests of the pilot

1. **Characterization, before any change.** Two new cases against today's
   runtime. Writers on several threads race with `start`: the attach state is
   first, each writer's own order is kept, nothing is lost or doubled. A step
   that the effect runtime refuses to start, with cats-effect and a pool that
   rejects one task: it is reported for the right extension, and the calls
   after it still arrive in order.
2. **`ExtensionMachineSpec`.** One case per row of the table. Properties over
   generated event sequences:
   - `Next` returns at most one `Check` or `Call`.
   - No `Call` for a notification whose `Check` has not succeeded.
   - The calls of one notification go to slot 0, 1, 2 and so on, each once.
     Notifications are served in the order of their events.
   - After `Halt` or `Take`, `Next` returns nothing, and a write offers no
     step.
   - `Take` returns every slot once, newest first. A second `Take` returns
     nothing. A `Register` after it is rejected.
   - The `last` of a slot is the last state a `Call` handed to it, or else the
     admitted state.
   - Before delivery at most one state is kept.
   - When nothing stops the machine, one `Next` per offered step empties
     `waiting`, and one more `Next` changes nothing.
3. **Everything that exists.** The runtime, session, adapter, cost and volume
   specs, under `Future`, cats-effect and ZIO, and the six review probes.

### Cost

Today a notification costs one lock and a few volatile reads. After the
change it costs one atomic step for the write and two for each of its steps,
each with a small allocation. A writer and the lane then change the same
cell, so a step can be retried. Two gates check that this cannot be seen:

- `ExtensionVolumeSpec`, with its 30-second limits, unchanged.
- The click latency harness, `main` against the branch, for the cell that
  moved before: plain extensions with 16 users. The branch must stay within
  the spread of the runs.

If a gate fails, the waiting notifications stay in the lane, as today, and
the machine keeps only the decisions; see
[alternatives considered](#alternatives-considered).

### Risks of the pilot

| Risk | Answer |
| --- | --- |
| The check order of a guarded session changes | The machine hands out a `Call` only after `Succeeded` for its `Check`; a property pins it; `ExtensionCostSpec` counts the observations |
| Tokens and steps drift apart | The argument above, a property, and the rule for `Failed` with nothing running |
| A retry of the atomic step repeats a side effect | `step` is pure, and the guard keeps it so |
| More allocation on a busy view | The two gates above |
| A second review of code that was just reviewed | No spec is edited, and the probes of that review run unchanged |

## Inventory

### How it was made

The main sources of `modules/` and `interop/` are 223 Scala files, about
36,900 lines. A script listed every `var`, `@volatile`, atomic, lock object
and mutable collection: 109 files have at least one. Eight readers then read
every one of those files, and the seven files of the browser client (about
2,000 lines of JavaScript), and sorted what they found into the eight kinds.
They also looked for state the script cannot see: callbacks stored in fields,
`lazy val`s, and lifecycles that have no field at all. `examples/` and `misc/`
were not read.

Four claims of the readers were checked against the code afterwards, and all
four held. The rest is their reading. Line numbers are left out on purpose:
the class or method is named instead.

The result in one sentence: about 65 places hide a machine or encode one
badly, and a few small machines are written again and again.

### The same small machines, written many times

This is the most useful finding. Five small protocols account for about forty
hand-written copies.

| Machine | What it does | Where it is written today |
| --- | --- | --- |
| **Deadline** | Arm once, complete in time or late, fire with a ticket, close | `RequestDeadline` and `MessageDeadline` (the same class twice, in two modules); `HttpClient.withSetupDeadline`; `SessionsService.required`; the idle deadline of a guarded session, of a legacy session and of a long-polling topic; `WebSocketFrameDeadline` in the zio-http runtime |
| **Drain gate** | Admit work, count what is in flight, close and wait for zero | `GuardedStateManager.writes`; `ServerSocket.ActiveConnections`; `SocketCloseDispatcher`; `InMemoryBrowserPersistence`; the three lanes of `JdbcBrowserPersistence`; the close part of `BrowserCoordinatorGate` |
| **Once** | A result that is produced once; callers that come late get it | `WebSocketResponse.releaseOnce`; `ComponentOwnership.Signal`; the waiters of `SerialLane`; `AsyncTable`, per key; `finalized` with `cleanupFinished` in the standalone server; `Scheduler.JobHandler`; `Stream.handleConsumed`; the dispose of `AsyncResourcePool`; `Queue.cancelSignal`; `RecoveryGates`; four promises used as latches in the HTTP module |
| **Pending registry** | A bounded map of pending items, a closed flag, a cancel handle that is installed late | `OwnedBrowserRequests`; `SensitiveDisclosureScope`; the entries of `SensitiveRuntime`; `pendingUserActions` and `downloadFiles` in `Frontend` |
| **Setup ownership** | Waiting, prepared, attached, abandoned: who owns a WebSocket before it is attached | `UpgradeOwnership` in the Pekko adapter and in the Akka adapter; `WebSocketSetup` in the zio-http adapter; `SetupDeadline` in the standalone server |

Three more things repeat, and are not machines:

- **A serial lane.** `SerialLane` is used by the extension runtime only.
  `Frontend`, `ComponentInstance` and `GuardedStateManager` each build one
  from a queue and a loop.
- **"The connection is closed."** One fact is stored in at least seven
  objects: `Frontend`, `SessionsService`, `ComponentOwnership`,
  `SensitiveRuntime`, `SensitiveDisclosureScope`, `OwnedBrowserRequests` and
  each queue. Only the order of two cleanup methods keeps them equal.
- **A fence against stale input.** A generation in `TimerMachine`, a revision
  and a departure counter in `Frontend`, an event counter in
  `ApplicationInstance`, an object identity in `HttpServer`, a closure
  identity in `HttpClient`.

### Lifecycles that have no field at all

These are the most hidden. Nothing in memory says which state the object is
in.

| Where | The lifecycle |
| --- | --- |
| `ApplicationInstance` | Created, initializing, running, destroyed. `initialize` has no guard against a second call, and `destroy` does not stop the consumers it started |
| `Frontend.close` | Called from about twenty places, with no guard against two calls at once. A step that fails skips the steps after it |
| `ComponentInstance` | Constructed, started, retired, destroyed. "Retired" is a closed queue plus a flag in another object |
| `SessionsService` | No class owns a session. A session is a set of local values that closures keep alive |
| `Stream.buffer` | Whether the upstream has ended or failed is stored nowhere |
| The Reactive Streams publisher | The state of a subscription is the position of a detached loop |
| `ActionDispatcher`, the admission chain in `Frontend`, the two-transaction protocols of the JDBC provider | The state is the position in a chain of effects. These stay as they are; see [what stays as it is](#what-stays-as-it-is) |

### By area

Risk is the risk of the refactor, not of the code today.

#### Sessions and stored state

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Guarded session | `SessionsService.createGuarded` | Twelve atomics, two latches and a stream wrapper. Thirteen real states, from "claimed" to "closed", and a teardown of twelve ordered steps. The attach mode is three local values | A | A session machine. The teardown becomes a list of steps computed from what was acquired | High. Specs are end to end only |
| Legacy session | `SessionsService.createLegacy` | Five cells and the entry in `apps`. States such as "abandoned" and "poisoned" exist only as code paths | A | The same machine shape, and the same teardown list with a mode | Medium to high |
| Bounded wait | `SessionsService.required` | A flag, a timer and a literal of 10 seconds. Tests wait real seconds | B | A use of the deadline machine, with the bound and the clock passed in | Low to medium |
| Session registries | `apps`, `guardedAttachments`, `pendingStorageRemovals`, `bootstrapPaths` | Four maps with rules that are written nowhere: a removal is retried only under the fence; a bootstrap path is read once | D | One slot per session id | Medium |
| Write gate | `GuardedStateManager` | Already one value in one cell | C | The drain gate | Medium |
| Presentation store | `BoundedPresentationStore` | Four variables under one lock. The phase of a view is already an `enum` | C | `step(state, event, now)` with a reply | Medium |
| Snapshot session | `ViewSnapshotSession` | An `enum` in a cell. A commit reads, saves and then sets | C | Lift the moves | Low |
| Long-polling topic | `MessagingService` | Three atomics, map membership and a timer handle | A | A topic machine in the shape of `TimerMachine` | Low to medium |

#### Connection and rendering

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Connection and published view | `Frontend` | Four atomics and a lock. Two machines: live, replacing, closed; and unpublished, published at a revision | A | A connection machine. The kind of a frame becomes a type; today it is a test on the first characters of a string | High. It decides what leaves the server |
| Departure and navigation fences | `Frontend` | Three counters | B | Part of the connection machine | High |
| Outgoing item | `Frontend.Outgoing` | A sum type encoded as closures and an empty string | B | A sealed type. The chain of authority checks stays effect code | High |
| User actions and downloads | `Frontend` | Two registries with a close rule | A, D | The pending registry | Medium |
| Close sequence | `Frontend.close` | No field; see above | A | The close event returns the ordered commands, once | Medium |
| Render cycle | `ApplicationInstance` | A plain variable that is safe only by sequencing. The begin, publish, flush sequence is written three times | A | A render cycle machine: baseline, candidate, accepted | Medium to high |
| Instance lifecycle | `ApplicationInstance` | No field; see above | A | One lifecycle cell | Medium |
| Browser requests | `OwnedBrowserRequests` | One value in one cell, plus three cells per entry that allow eight combinations for three states | C, A | The pending registry | Low to medium |

#### Components, timers and sensitive data

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| The rest of the timers | `TimerRuntime` | Thirteen fields beside the machine's state: a render transaction, a start gate, an outbox. The runtime also edits the machine's state when its serial overflows | A | Move the render transaction, the start gate and the overflow into `TimerMachine`. The tables of closures stay | Medium. Strong specs |
| Component ownership | `ComponentOwnership` | Eight flags and two signals per owner, under one lock. Seven real states, from "gated" to "cleanup failed" | A, D | An ownership machine for owners and registry together | Medium. Strong spec |
| Baseline and loader | `ComponentInstance` | Two volatile flags and a `null`. `loading` is true in three different states | A | A baseline machine | High. No spec of its own |
| Legacy delay | `DelayInstance` | Two volatile fields | A | One cell | Low to medium |
| Sensitive presentations | `SensitiveRuntime` | The pure phase model exists, but the plaintext, the waiter and the timers sit beside it in separate cells. A five-second rule is written three times | A | A sensitive machine. The payload never enters its state | High. It is a security order |
| Serial lane | `SerialLane` | Two state machines in `Int` constants | B | Two small tables. The queue stays | Medium |
| Extension runtime | `ExtensionRuntime` | See the pilot | A | The pilot | Medium |

#### Authentication and security

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Coordinator gate | `BrowserCoordinatorGate` | Seven fields under one lock. Three machines: the run mode, the close, the queue | A | A gate machine. Both critical sections already compute what to signal | Medium. It can go first, alone |
| View lifecycle | `BrowserAuthentication` | Two maps and four flags. Six real phases. One flag has three causes that it cannot tell apart | A | A view machine. It builds on the presentation store, which holds a third record of the same view | High |
| Provider admission | `InMemoryBrowserPersistence`, `JdbcBrowserPersistence` | The same gate twice. The JDBC one has three lanes, and a failed close is a state that no field names | A | The drain gate, with lanes. The owned views of the JDBC provider join its cell | Medium |
| Clock lease | `JdbcBrowserClock` | Two cells, a lock, a failure stamp and a demand flag carry a security rule | A | A clock machine: a function of two instants | Medium. Strong spec |
| One-use lease | `OneUseAuthority` | Three booleans in three objects | A | One `enum` per operation: fresh, issued, admitted, expired | Medium |
| Invocation table | `OperationTransactions` | The table is spread over four methods and written as throws | C | Pure decisions in `modules/core`, next to `OperationAuthorization` | Medium |
| Snapshot references | `InMemorySnapshotReferences` | Three maps, and four fields that encode the state of a retired record | D, B | A reference model. It is a law host, so the model can check the JDBC twin | Low |
| Record store | `InMemoryBrowserPersistence` | One cell and four variables beside it. Several commit points inside one section | C | A pure function per call. It comes last | High |

#### Transport

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Header and message deadlines | `RequestDeadline`, `MessageDeadline` | The same four fields and the same code in two modules | B | The deadline machine | Low |
| Server connection | `HttpServer.withConnection` | Three sub-states in a closure: the head, the body, the mode. The guard against a second request behind an unread body is one of them | A | An HTTP connection machine | High |
| Client exchange | `HttpClient` | Eight cells, and a flag in another module, decide whether a connection is reused | A | A client exchange machine | High |
| Chunked body | `ChunkedBodyDecoder` | A phase in an `Int` and five counters. The decoder that drives it has `()` as its state | B | A pure step per chunk, in the shape `WebSocketProtocol` already has | Medium |
| WebSocket message assembly | `WebSocketProtocol.upgrade` | A side channel between two decode stages | A | One step that returns signals | Medium |
| Close reservation | `SocketCloseDispatcher.Reservation` | A set of bits in an `AtomicInteger` | B | A reservation machine | Medium |
| Raw socket | `RawDataSocket` | One value in one cell, with six retry loops | C | Lift the six bodies | Medium |
| Upgrade input | `HttpUpgradeInput` | Three states in an `Int` | B | An `enum` in the same cell. No machine | Low |
| Standalone WebSocket | `standalone.scala`, `SetupInput`, `SetupDeadline` | One connection lifecycle in nine cells. Its ordering rules are comments | A | One WebSocket connection machine | High |
| Text limit | `WebSocketLimits` | Two variables and a builder | B | A pure scan per chunk | Low |

#### Effect module

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Queue | `Queue` | One value in one cell. Open, stopped, closed and failed are three booleans and an option | C | A pure function per operation, not one `step`: an event object per element would cost on the hottest path | High. Eleven files use it |
| Table | `AsyncTable` | The states of a key are a nested `Either` | C | An `enum` per key | Low to medium |
| Resource pool | `AsyncResourcePool` | One value in one cell, a closure in a variable, and identity hash codes as names | C | A pool machine that issues its own ids | Medium |
| Scheduler | `Scheduler` | Two small machines in variables and `null`s | B | Two small cells | Low |
| Hub, `Stream.sort`, `Stream.buffer` | `Hub`, `Stream` | Routing by demand, with a pull lock | A | Not as they are; see [decisions](#decisions-to-confirm) | High |

#### Adapters

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Setup ownership | Pekko, Akka, zio-http, standalone | The same four states, four times, in three kinds of cell | C | One setup ownership machine in the runtime module | Low |
| Stream subscriber | Pekko, Akka | One value, five fields, four real states | C | One step for both adapters. Its interpreter keeps a lock | Medium |
| Stream publisher | Pekko, Akka | A flag that is not volatile, and a loop whose position is the state | A | A publisher machine. It has no test today | Medium to high |
| Frame deadline | zio-http runtime | A scanner and a deadline in plain variables | B | A pure core in the same file | Medium |

#### Browser client

| Item | Where | What hides today | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- | --- |
| Connection | `connection.js` | Eighteen fields, four variables per socket and two suspended functions. Ten real states | A | `connection-machine.js`: a pure step with an epoch on every transport event | High |
| View gate and heartbeat | `bridge.js` | The rule that admits a user event reads eight flags in three objects | A, B | `bridge-machine.js` | Medium to high |
| Sensitive barriers | `sensitive.js` | Two barriers, and a rule that depends on the order in which two listeners were added | A | Part of the bridge machine | Medium |

#### Test kits

| Item | Where | Kind | Proposal | Risk |
| --- | --- | --- | --- | --- |
| Recovery gates | `RecoveryGates` | A | An `enum` per gate | Low |
| Payload store | `InMemoryPayloadStore` | D | One map with a slot per reference | Low |

## Plan

Nothing here starts before the [decisions](#decisions-to-confirm) are made.

### Rules for every step

1. **Tests first.** A rule that no test pins gets a test, and the test must
   pass on the old code. Then the refactor.
2. **One machine per pull request.** Its behaviour does not change, and no
   existing spec is edited. Where a spec reads a private field of an
   interpreter, that spec is rewritten in a pull request of its own, first.
3. **No fix inside a refactor.** The readers found suspected defects; see
   [defects](#defects-the-survey-suggests). Each is first shown with a failing
   test and fixed on its own, before the refactor of that area.
4. **A step per edge, not per element.** A machine runs once per lifecycle
   change or per chunk. It never runs per byte, per frame pull or per queue
   element.
5. **A gate on every hot path.** The queue, the frames of `Frontend`, the
   decoders, the read of `RawDataSocket`, the pull of `SetupInput` and the
   extension lane are measured before and after.
6. **The effect order of a security path is not touched.** For the items
   marked high, the interpreter keeps the order of its effects, line for
   line. Only the decisions move.

### Phases

| Phase | What | Items | Size |
| --- | --- | --- | --- |
| 0 | **Ground rules.** The guard on `*Machine.scala`. `StateCell`. `TimerMachine` passes the guard and becomes total | 3 | Small |
| 1 | **Pilot.** `ExtensionMachine` | 1 | Medium |
| 2 | **Five shared machines.** Deadline, drain gate, once, pending registry, setup ownership. Each gets its spec first, then replaces its copies, a few per pull request | 5 machines, about 40 copies | Medium each |
| 3 | **Lifecycles with strong specs.** The rest of the timers. Component ownership. The snapshot session. The presentation store. The long-polling topic. The coordinator gate. The serial lane | 7 | Medium each |
| 4 | **Sessions.** The teardown as a list of steps. The legacy session. The guarded session with its registries | 3 | Large |
| 5 | **Connection and rendering.** The outgoing item as a type. The connection machine with the render cycle. The instance lifecycle. The baseline and loader. Sensitive presentations | 5 | Large, two of them very large |
| 6 | **Authentication.** The one-use lease. The invocation decisions. The reference model. The view machine. Provider admission, memory and JDBC. The JDBC clock. The record store, last | 7 | Medium to very large |
| 7 | **Transport.** The chunked body. The reservation. The raw socket. Message assembly. The standalone WebSocket. The server connection. The client exchange. The stream subscriber and publisher | 9 | Medium to large |
| 8 | **Effect module.** The table. The pool. The scheduler. The queue, with a benchmark | 4 | Medium |
| 9 | **Browser client.** Its tests stop reading private fields. Then the connection machine, then the bridge with the sensitive barriers | 3 | Large |
| 10 | **Test kits.** | 2 | Small |

Roughly 70 pull requests in all, because the large items take more than
one. Phases 0 to 4 hold most of the value: the shared machines remove the
most copies, and the sessions are where the last reviews found the hardest
defects.

Why this order:

- **Small and shared first.** A deadline or a drain gate is thirty lines of
  pure code. It has tests today, and it is used by the larger machines that
  come later.
- **Strong specs before weak ones.** Component ownership and the timers have
  specs that fail on a wrong move. `ComponentInstance` and the stream
  publisher have none, so their tests come first and they come late.
- **Inside out.** A session machine is easier when the gate, the deadline and
  the extension machine it drives are already machines.
- **Security order last in each area.** `Frontend`, sensitive presentations,
  the view lifecycle and the record store come after the simpler items of
  their phase.

### What a phase needs before it starts

| Phase | Before it starts |
| --- | --- |
| 1 | The two characterization cases of the pilot |
| 2 | A test of `SessionsService.required` that does not wait real seconds; tests of the lane budgets and of "closed after close" for the JDBC provider |
| 3 | A test of the timer overflow through the runtime |
| 4 | Cases for a teardown that is asked for twice at once, and for a drain that does not end |
| 5 | A spec for `ComponentInstance`; a spec for the close of `Frontend` |
| 7 | A spec for the stream publisher |
| 9 | Tests of long polling, backoff and the choice of transport; the Node tests rewritten against behaviour |

## Defects the survey suggests

The readers reported about twenty-five places where the code seems to allow a
wrong state. None was run. Some may be harmless by design. The list was
handed over separately and is not part of this document, because several
items are in security code and none is confirmed.

Rule 3 of the plan applies to each: first a failing test, then a fix of its
own. Two of them were already known from the work on the extension contract:
a guard that is not closed when an attach is cancelled at one point, and a
render loop that stalls with `delayedRender` after a burst of writes.

## What stays as it is

Not every variable hides a machine. These are left alone, each for a reason.

| What | Examples | Why |
| --- | --- | --- |
| A capability that can be used once | `ExecutionPermit`, `OneUseEvidence`, `BrowserCeremony.admitted`, the three flags in `BrowserReplacement` | The object is the cell. Every alias shares it, and it dies with the object. A central state would need a registry |
| A guard against a second evaluation | `BrowserPreparation.once`, `OperationProtocol.transactOnce`, the `ThreadLocal` in the memory provider, scopes that are bound to a thread | They catch something a pure step cannot see: an effect that is run twice, or a call from the wrong thread |
| A chain of authority checks | `ActionDispatcher`, the admission of a user action and the release barrier in `Frontend`, `acquire` in `BrowserAuthentication` | The safety is that nothing suspends between the last check and the action. Events and commands would put a gap there. The variants become types; the chain stays |
| A protocol that the database owns | `JdbcTransactions.run`, the rows that SQL moves, the two-transaction protocols | The order of commit, rollback and close is the content. Specs pin the statement counts |
| A store that is data | `StateStorage`, `StateManager`, the asset cache of `FilesService`, `EventRegistry`, the pools of `HttpClient`, `InMemorySecurityStore` | There is no protocol to name. Two of them are a public interface that applications implement |
| A step that is already pure | The decoders in `Http11` and `WebSocketProtocol`, `Stream.unfold`, `Decoder`, `SensitivePresentation`, `FormDataCodec` | They have the target shape already |
| Local mutation in one function | Parsers, cursors, builders, the render registries of `ComponentInstance` | The state never leaves the call. A pure version only adds allocation, and some are on the render path |
| State of an outside engine | `SecureDataSocket`, the NIO channel, the inflater, `runSocket` in the zio-http adapter, the flow wiring of the Pekko adapter, `WebSocketAppHandler` | The TLS engine, ZIO and Pekko own that state. A copy could not observe it. `WebSocketAppHandler` is a patched upstream file whose difference must stay small |
| Counters and settings | `RecoveryCounters`, `Metrics`, the level of `Reporter` | No lifecycle |
| The DOM mirror of the client | `spoonbill.js` | A pure version needs a second copy of the tree on the patch path |

Two small things are cleaned up without a machine: `managedLifecycle` in
`JdbcBrowserSessions` becomes a constructor argument, and `HttpUpgradeInput`
gets an `enum` in place of its number.

## Alternatives considered

- **A general actor library.** One mailbox abstraction with the machines as
  behaviours. Rejected. The interpreters differ in exactly what matters: which
  thread starts a callback, whether a bound may wait for a worker, what a
  cancellation does. A shared mailbox would hide those rules. The machines
  give the common shape, and each interpreter stays specific.
- **Interpreters in the ZIO and Pekko modules, or supplied by applications.**
  Deferred. A default must exist in the main module anyway, because an
  application on `Future` has neither library. Every interpreter would have
  to prove the same guarantees again: one callback at a time, the right
  thread, bounds that end without a worker, checks before delivery. The
  machines make a second interpreter possible later, and the shared specs
  would be its test kit. It is opened when a second interpreter has a
  concrete need.
- **All machines in `modules/core`.** Rejected. That module is the domain
  kernel of browser authentication. The modules below the runtime cannot
  depend on it, and most machines here are not domain rules. One exception is
  listed: the invocation decisions belong next to `OperationAuthorization`.
- **One change for everything.** Rejected. Session creation and the
  connection are security code. Each machine is reviewed on its own, with its
  specs unchanged.
- **Only document the states.** Diagrams without code. Rejected: a diagram
  does not fail the build when the code moves away from it.
- **In the pilot, keep the waiting notifications in the lane.** The machine
  would decide only, and the lock would stay to keep a write and its place in
  the lane together. It is the fallback if the two cost gates fail. It is not
  the first choice, because the order of the work would stay outside the
  machine.

## Decisions to confirm

1. **Scope.** Kinds A, B and C, and the stores of kind D that have rules, are
   refactored. Kinds E to H stay. Recommended.
2. **Order.** The shared small machines before the large lifecycles.
   Recommended.
3. **Where machines live.** Next to their interpreters, with the guard on
   `*Machine.scala`, not in `modules/core`. The shared ones go to the effect
   module, the pending registry and setup ownership to the runtime module.
   The invocation decisions go to `modules/core`. Recommended.
4. **The pilot keeps its waiting notifications in the machine.** With the two
   cost gates, and the fallback above. Recommended.
5. **Suspected defects are fixed first, each in its own change.** The other
   choice keeps them and writes them into the machines as they are.
   Recommended: fix first.
6. **`Hub`, `Stream.sort` and `Stream.buffer` are replaced, not restated.**
   Their routing by demand is the flaw, and `Frontend` already has the
   replacement for guarded sessions: an eager pump into one queue per
   consumer. This changes behaviour for legacy sessions, so it is its own
   decision.
7. **The browser client is in scope, and last.** Its tests read private
   fields today, so they are rewritten first.
8. **The Akka adapter shares the machines of the Pekko adapter.** They are
   copies today.
9. **The zio-http runtime keeps its own copy of the frame deadline.** It is a
   patched upstream artifact with a pinned list of files and no dependency on
   this repository.
10. **The plan is confirmed in two parts.** Phases 0 to 4 now. Phases 5 to 10
    again after phase 4, with what the first part has shown. Recommended.

## Open questions

1. **Should the session own "closed"?** Seven objects store that fact. A
   session machine could be the only one, and tell the others. That is a
   design of its own, after phase 4.
2. **Should interpreters share more than the cell?** A helper that applies a
   step and runs the commands is five lines. It may not be worth a name.
3. **How are the specs treated that pin an interpreter's inside?** Some build
   `RequestDeadline` or `SetupInput` directly, or read probes such as
   `closeObserverCount`. The classes can stay as thin interpreters, so these
   specs keep passing.
4. **Does a machine get a diagram?** A transition table in the header of the
   file may be enough.
5. **Is a rule for the client's machines wanted?** For example: a file named
   `*-machine.js` does not mention `window`, `document`, a timer or a socket.
