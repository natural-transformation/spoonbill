# Pure state machines: design and plan

Status: implementation present; performance acceptance incomplete. The owner
requested stopping experiments after the current round on 2026-11-17, and later
asked for the sort throughput gap to be closed. It is closed; the final
four-workload aggregate gate has not been rerun.
The proposal below retains the original before/after descriptions;
[the pilot checkpoint](#pilot-implementation-checkpoint) records the implementation
and validation. The latest extension candidate separates ownership and uses
compact pure admission decisions; [the two design experiments](#ownership-design-experiments)
supersede the earlier whole-runtime CAS proposal. The owner has now authorized
the full plan through phase 10, with incremental commits on PR #34 and a final
review after completion. [Rollout progress](#rollout-progress) records the current
state separately from the historical pilot and investigation checkpoints.
All ten decisions are made; see [decisions](#decisions-to-confirm). The approved
stream contracts are being implemented and validated in phase 8.

**Pilot benchmark gate: passed by design Round 2.** The owner confirmed at least
95% of main's throughput and at most 110% of main's delivery p99 and allocation
in every measured workload, with correctness checks passing. The final corrected
candidate meets that gate on the corrected live-producer comparison's three-trial
medians. Minimum throughput is 96.2%; maximum delivery p99 is 109.5%, close to its
boundary. This is not a confidence interval or a production-wide performance
guarantee. Review and broader rollout remain separate decisions.
Earlier CAS and lock comparisons below are retained as historical evidence.

**Allocation measurement recheck completed:** the later ownership audit found that JDK 21's
total-allocation counter can temporarily omit exiting threads. Earlier fixtures
sampled after producer joins, so their allocation observations are retained but
provisional. A fresh live-thread cross-check and comparison passed the gate. Throughput,
delivery-order and latency observations are separate from this counter issue.
The corrected fixtures keep producers alive through the sample and compare the
total against producer-local allocation deltas. The authoritative result is the
[live-producer recheck](#live-producer-allocation-recheck).

Draft 2 followed a review: the plan no longer promises an unchanged spec where
a machine itself changes. Draft 3 followed another and changed the pilot. In
drafts 1 and 2 the lane held bare tokens, and the machine decided what each
token stood for. Two reviews found three orders of events in which the machine
then lacked what it needed to report a step. Since draft 3 the lane's thread
holds the step itself, with its identity, as it does today. Draft 3 also kept
the lock of the runtime for good. Draft 4 follows a decision about that lock:
the pilot ends without it, in two steps. Step 1 is the pilot of draft 3. Step
2 moves the waiting steps from the lane into the machine.

Draft 5 records the decisions of the fifth review. An item whose code is
already clear can stay as it is. A shared machine needs copies that behave the
same. A suspicion is investigated before anything is fixed. The plan stops for
a checkpoint after the pilot. The open questions have defaults.

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
2. **A pilot.** The extension runtime is refactored first, in two steps. Step
   1 turns its mutable fields into `ExtensionMachine`, and keeps its lock and
   its lane. Step 2 moves the waiting steps into the machine too, and the lock
   becomes the shared cell. Its behaviour does not change. See
   [pilot](#pilot-the-extension-runtime).
3. **An inventory.** Every place in the main sources that holds state was
   read and sorted into eight kinds. About 65 places hide a machine. Five
   small protocols appear about forty times, going by their shape. See
   [inventory](#inventory).
4. **A plan.** The shared small machines come first, then the lifecycles, one
   machine per pull request. The inventory guides the work. It does not order
   every rewrite: an item whose code is already clear and well tested can
   stay. The kinds that hide no machine are left alone, with the reason. See
   [plan](#plan) and [what stays as it is](#what-stays-as-it-is).

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
  must pass on the old code. Two exceptions. A spec that is tied to the layout
  of fields, or to an incidental encoding of the state, is rewritten first, in
  a change of its own; the Node tests of the browser client are the main case.
  A spec that establishes a lifecycle guarantee stays, also when it uses an
  internal seam: it may build `RequestDeadline` directly, or read a probe such
  as `closeObserverCount` to show that a resource was released. And the spec
  of a machine can change together with that machine, once a spec of its
  interpreter pins what can be observed. `TimerMachineSpec` expects a throw on
  overflow, and that assertion is replaced when `step` becomes total.
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
class and uses it where no command has to run inside the atomic section:

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
inside the atomic section. The reason is then written at the lock. One case is
known for good: the Reactive Streams subscriber of the Pekko adapter asks for
the next element under its lock, on purpose; a retry would ask twice.

The pilot is such a case between its two steps only. In step 1 it puts the
steps of an event into the lane under its lock, so that the lane has them in
the order of the events. In step 2 those steps are part of the state, and the
cell is enough. Where it can be done, this is the way out of such a lock:
when two things must change in one action, make them one value.

### The guard

`scripts/check-core-purity.mjs` checks today that `modules/core` uses no
runtime dependency, no clock, no mutable field and no effect type. The plan
extends it: every file named `*Machine.scala`, in any module, must pass the
same rules. A machine then cannot grow a `var` or a `Future` without failing
the build.

`TimerMachine.scala` is the only such file today, and the rules flag one line
in it: `step` keeps its result in a local variable. The guard does not tell a
local variable from a field. So that function is rewritten without it, and
`TimerMachineSpec` shows that the rewrite changed nothing. Making `step` total
is a second, separate change of that spec; see [plan](#plan).

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

### Two steps

The pilot ends with one immutable value in one cell, and without a lock. It
gets there in two pull requests. Each changes one thing.

| Step | What moves | What stays |
| --- | --- | --- |
| 1 | The rules. Eight of the nine places become `ExtensionMachine` | The lock, and the lane with its queue |
| 2 | The ninth place. The waiting steps move from the lane's queue into the machine, and the lock becomes a `StateCell` | The rules of step 1 |

The lock exists today because the phase and the queue are two things, and a
write must change both in one action. Step 1 moves the rules and keeps that
structure, so every order of events is the same as today. Step 2 puts the
queue into the same value as the phase. One compare-and-set then covers both,
and the lock has no job left.

Step 1 is also a correct place to stop, should step 2 show a problem.

### The machine

`ExtensionMachine.scala` sits next to `ExtensionRuntime.scala`. This is its
shape in step 1. Step 2 changes it in three places; see
[step 2](#step-2-the-waiting-steps-move-into-the-machine).

```scala
private[spoonbill] object ExtensionMachine {

  enum Notification[+S, +M] { case State(value: S); case Message(value: M) }
  enum Callback { case Setup, OnState, OnMessage, OnDestroy }
  /** What the interpreter learned from an error. The machine never sees a Throwable. */
  enum Failure { case Denied, Aborted, Other }

  /**
   * One unit of work in the lane. A delivery is one check, and then one call
   * per extension. A step carries what is needed to report it, so it can still
   * be named after the view has ended.
   */
  enum Step[+S, +M] {
    def delivery: Long
    case Check(delivery: Long, notification: Notification[S, M])
    case Call(delivery: Long, slot: Int, name: String, notification: Notification[S, M])
  }

  /** One extension that is set up, in setup order. Its handlers stay in the interpreter. */
  final case class Slot[S](name: String, received: Option[S])

  enum Phase[S, M] {
    case Recording(newest: Option[S], messages: Vector[M])
    case Delivering(
      targets: Int,         // the first `targets` slots get notifications
      deliveries: Long,     // the number of the last delivery
      passed: Option[Long]  // the delivery whose check passed last
    )
    case Stopped()
  }

  final case class State[S, M](
    guarded: Boolean,                 // a check runs before each delivery
    phase: Phase[S, M],
    admitted: Option[S],              // the state the view was accepted with
    slots: Option[Vector[Slot[S]]],   // None once teardown took them
    unscheduledReported: Boolean
  )

  enum Event[+S, +M] {
    case Admit(state: S)                             // setup begins
    case Written(state: S)                           // the observer saw a write
    case Published(message: M)                       // the observer saw a message
    case Register(name: String)                      // a setup completed
    case Begin                                       // every setup completed: start delivery
    case Start(step: Step[S, M])                     // the lane begins a step
    case Passed(delivery: Long)                      // the check of a delivery succeeded
    case Failed(step: Step[S, M], failure: Failure)  // a step did not succeed, started or not
    case Halt                                        // the view ends
    case Take                                        // teardown takes the extensions
    case Unscheduled                                 // the execution context refused the lane
  }

  enum Command[+S, +M] {
    case Enqueue(steps: Vector[Step[S, M]])                    // put these into the lane, in this order
    case RunCheck(notification: Notification[S, M])            // run the guarded check
    case RunCall(slot: Int, notification: Notification[S, M])  // call the handler of this slot
    case Reject(last: Option[S])                               // the setup came too late: destroy it now
    case Destroy(slot: Int, name: String, last: Option[S])     // destroy, in the order given
    case Report(text: String)                                  // report, with the error at hand
    case Close                                                 // close the lane
  }

  def initial[S, M](guarded: Boolean): State[S, M]
  def step[S, M](state: State[S, M], event: Event[S, M]): (State[S, M], Vector[Command[S, M]])

  /** The text of a report. None for a failure that is expected and stays silent. */
  def reportOf(name: String, callback: Callback, failure: Failure, stopped: Boolean): Option[String]
}
```

The phases move in one direction: `Recording` to `Delivering` to `Stopped`,
or `Recording` straight to `Stopped`. What belongs to one phase is a field of
that phase. So a state that makes no sense cannot be built: there is no
delivery number before delivery, and none after a stop.

| Event | In | New state | Commands |
| --- | --- | --- | --- |
| `Admit(s)` | any | `admitted = s` | none |
| `Written(s)` | `Recording` | `newest = s`; the state before it is dropped | none |
| | `Delivering` | `deliveries` goes up by one | `Enqueue`: a `Check` for the new delivery, then a `Call` for each of the first `targets` slots, in slot order |
| | `Stopped` | unchanged | none |
| `Published(m)` | `Recording` | `m` is appended to `messages` | none |
| | `Delivering` | `deliveries` goes up by one | `Enqueue`, as for a state |
| | `Stopped` | unchanged | none |
| `Register(name)` | `slots` present | a new slot at the end | none |
| | `slots` taken | unchanged | `Reject(admitted)` |
| `Begin` | `Recording` | `Delivering`; `targets` is the number of slots | `Enqueue`: the steps for the newest state, or else for the admitted state, and then for each message, in that order. None when nothing waits |
| | other | unchanged | none |
| `Start(Check(d, n))` | `Delivering`, guarded | unchanged | `RunCheck(n)` |
| | `Delivering`, not guarded | `passed = d` | none |
| | other | unchanged | none |
| `Passed(d)` | `Delivering` | `passed = d` | none |
| | other | unchanged | none |
| `Start(Call(d, slot, name, n))` | `Delivering`, and `passed = d` | for a state, `received` of the slot is set | `RunCall(slot, n)` |
| | other | unchanged | none |
| `Failed(Check, f)` | any | `Stopped` | `Close`, and `Report("Extension delivery was stopped: its check failed")` unless `f` is `Denied` |
| `Failed(Call(d, slot, name, n), f)` | any | unchanged | `Report("Extension <name>: <callback> failed")`, unless `f` is `Aborted`, or `f` is `Denied` and the phase is `Stopped` |
| `Halt` | any | `Stopped`; what was recorded is dropped | `Close` |
| `Take` | `slots` present | `slots` taken; `Stopped` | `Close` if the phase was not `Stopped`. Then `Destroy(slot, name, last)` for every slot, newest first, where `last` is `received`, or else `admitted` |
| | `slots` taken | unchanged | none |
| `Unscheduled` | not yet reported | reported | `Report("Extension delivery could not be scheduled")` |
| | reported | unchanged | none |

Five notes on the table.

- **A step carries its own identity.** The lane hands a step back with its
  outcome, whether its effect started or not. So `Failed` depends on nothing
  that `Halt` or `Take` drops. It works after a stop, after the extensions
  were taken, and when the effect runtime refused to start the step. A step
  that never started has fed no `Start`, so it has set no `received`. This is
  what the runtime does today: the lane item holds the extension.
- **One check, then the calls.** Today each delivery has a flag that its check
  sets. The lane runs one step at a time, and the steps of one delivery are
  next to each other. So one field is enough: the delivery whose check passed
  last. A call starts only when that is its own delivery. The number only
  tells a delivery from the one before it, so it may wrap.
- **Without a guard the check does nothing.** It is kept, so that the lane
  holds the same number of items as today, which specs read.
- **`Take` also stops.** Today `destroy` always stops first. The machine makes
  it a rule: no call can start once the extensions are taken.
- **One rule for every report of a callback.** `reportOf` holds the rule of
  the `Failed` row for a call. The interpreter uses the same function for a
  `setup` or an `onDestroy` that failed. Those two are not steps of the lane.
  They are reported unless the failure is `Aborted`, as today.

### Step 1: the interpreter keeps its lock

`ExtensionRuntime` keeps its constructor and its methods. Inside, it has:

- **One lock**, as `TimerRuntime` has. It makes one `step` and the enqueue of
  the steps it offers one action. So the lane holds the steps in the order of
  the events. The lock covers one pure step and a few constant-time adds, and
  no extension code runs under it. Today's lock has the same shape: a few
  field updates and the adds.
- **One field** under that lock: the machine's state and the handlers of each
  slot. The handlers follow the slots in the same step. They join with the
  `Register` that made their slot. `Take` removes them all and hands them to
  its `Destroy` commands. So a retired view keeps no handlers, as today.
- **The lane**, unchanged. Its item is the machine's `Step`: one item per
  check and per call, as today.
- **The effect code**, unchanged: the bounded `setup` with its hand-over of
  the handlers, the wait for the call in flight, `onDestroy` inside a task and
  inside its bound, the redaction of snapshot rejections in reports.

```scala
private type Handlers = Extension.Handlers[F, S, M]
private final case class Held(machine: State[S, M], handlers: Vector[Handlers])
private val lock = new Object
private var held = Held(ExtensionMachine.initial(checks.nonEmpty), Vector.empty) // guarded by lock
private val lane = new SerialLane[F, Step[S, M]](start, failed, unscheduled)

/** The handlers follow the slots: one joins when the step made a slot, all leave when the slots are taken. */
private def follow(before: Vector[Handlers], next: State[S, M], added: Option[Handlers]): Vector[Handlers] =
  next.slots match {
    case None                                    => Vector.empty // a retired view keeps none
    case Some(slots) if slots.size > before.size => before ++ added
    case Some(_)                                 => before
  }

/**
 * One pure step. The steps it offers enter the lane before the lock is
 * released. `added` are the handlers that come with a `Register`. It returns
 * the commands, and the handlers as they were before the step.
 */
private def advance(event: Event[S, M], added: Option[Handlers] = None): (Vector[Command[S, M]], Vector[Handlers]) =
  lock.synchronized {
    val Held(machine, before) = held
    val (next, commands)      = ExtensionMachine.step(machine, event)
    held = Held(next, follow(before, next, added))
    commands.foreach { case Command.Enqueue(steps) => steps.foreach(lane.enqueue); case _ => () }
    (commands, before)
  }

/**
 * A step, and then what is left of it outside the lock: wake or close the
 * lane, report. `Reject` and `Destroy` are effects. `register` and the
 * teardown run them, where they run today.
 */
private def feed(event: Event[S, M], error: Option[Throwable] = None): Unit = run(advance(event)._1, error)

def state(value: S): Unit = feed(Event.Written(value))

private def start(step: Step[S, M]): F[Unit] = {
  val (commands, handlers) = advance(Event.Start(step))
  commands.collectFirst {
    case Command.RunCheck(n)      => check(n).map(_ => feed(Event.Passed(step.delivery)))
    // The handlers as they were in that step. A teardown can take them later.
    case Command.RunCall(slot, n) => call(handlers(slot), n)
  }.getOrElse(Effect[F].unit)
}

private def failed(step: Step[S, M], error: Throwable): Unit =
  feed(Event.Failed(step, classify(error)), Some(error))
```

What goes away in step 1: `phase`, `newest`, `earlyMessages`, `stopped`,
`admitted`, `ordered`, `active`, `unscheduledReported`, the class `Active`
with its `received`, and the mutable flag of `Delivery`. The lock stays, with
one job.

### Step 2: the waiting steps move into the machine

**The machine.** `Delivering` gets the waiting steps. There is one more event,
and `Enqueue` gives way to two commands.

```scala
case Delivering(
  targets: Int,
  deliveries: Long,
  passed: Option[Long],
  waiting: Vector[Step[S, M]]   // new: the steps the lane has not taken yet, oldest first
)

case Next                       // new event: the lane asks for its next step

case Wake                       // new command, in place of Enqueue: a step waits, wake the lane
case Hand(step: Step[S, M])     // new command: the answer to Next

/** The number of waiting steps. */
def waiting[S, M](state: State[S, M]): Int
```

| Event | In | New state | Commands |
| --- | --- | --- | --- |
| `Written(s)`, `Published(m)` | `Delivering` | as in step 1, and the steps go to the end of `waiting` | `Wake` |
| `Begin` | `Recording` | `Delivering`, with the same steps in `waiting` | `Wake`, when a step waits |
| `Next` | `Delivering`, and a step waits | the oldest step leaves `waiting` | `Hand(step)` |
| | other | unchanged | none |

The other rows do not change. `Stopped` has no place for a step. So `Halt`, a
failed check and `Take` drop the waiting steps by moving there, and no `Next`
hands out a step afterwards.

**The interpreter.** The lock and its field become one `StateCell`. `advance`
no longer adds to the lane. Only the pure step runs inside the cell, so a
retry is harmless.

```scala
private val cell = new StateCell(Held(ExtensionMachine.initial(checks.nonEmpty), Vector.empty))

private def advance(event: Event[S, M], added: Option[Handlers] = None): (Vector[Command[S, M]], Vector[Handlers]) =
  cell.modify { case Held(machine, before) =>
    val (next, commands) = ExtensionMachine.step(machine, event)
    (Held(next, follow(before, next, added)), (commands, before))
  }

/** The lane takes its steps from the machine. */
private val steps = new SerialLane.Source[Step[S, M]] {
  def poll(): Option[Step[S, M]] = advance(Event.Next)._1.collectFirst { case Command.Hand(step) => step }
  def isEmpty: Boolean           = ExtensionMachine.waiting(cell.get.machine) == 0
}
private val lane = new SerialLane[F, Step[S, M]](start, failed, unscheduled, steps)
```

`follow`, `feed`, `start` and `failed` do not change. `run` wakes the lane for
`Wake`. `pending` reads `waiting`.

**The lane.** `SerialLane` reads its queue in four places: `wake`, the loop,
`release` and `resume`. In step 2 those four go through a source. Without a
source the lane uses its own queue, as today, and `enqueue`, `offer` and
`size` belong to that queue. The states of the lane, its loop, `close` and
`idle` do not change.

A source owes the lane three things.

1. `poll` and `isEmpty` are safe on any thread, and both see an add that has
   completed.
2. The owner calls `wake` after it has added.
3. Once the owner has stopped, the source stays empty. The owner then calls
   `close`.

The cell gives the first, `Wake` the second, and `Stopped` the third.

**The lane's thread holds the step.** It takes the step from the machine
before it starts anything, and hands it back with the outcome, as it does with
an item of its own queue today. So a step keeps its identity when the effect
runtime refuses to start it, and when the view ends in between. `Start` still
runs inside the effect, as today, so a stop that comes after the lane took a
step and before its effect ran still prevents the call. This is where step 2
differs from drafts 1 and 2; see [alternatives](#alternatives-considered).

**What can be seen.** Nothing new. One window closes. Today, and in step 1,
the lane can still take a step between the stop and the close of the lane.
Such a step starts no callback. It is reported if the effect runtime refuses
it. In step 2 no step can be taken after the stop. Every run of step 2 is a
run that today's code allows.

What goes away in step 2: the lock, `Enqueue`, and the use of the lane's own
queue by this runtime.

### What does not change

- The constructor and the methods of `ExtensionRuntime`, `Bound`, `Checks`,
  `TimedOut` and `SetupAborted`. `SessionsService` is not touched.
- In step 1, `SerialLane`. In step 2, its states, its loop, `close` and
  `idle`. Its guarantees were reviewed with the contract, and they hold in
  both steps: a handler starts in a task, one at a time, on no deeper stack.
- Every existing spec, and the six probes of the outside review. None is
  edited.
- `pending` and `recorded`, which the specs read. `pending` is the number of
  waiting steps in both steps.

### Where each rule goes

| Rule | Today | After step 1 |
| --- | --- | --- |
| Only the newest state is kept before delivery | `newest` under the lock | `Phase.Recording` |
| The attach state comes first, then the early messages | `start` under the lock | `Begin` |
| A write and the start of delivery have one order | The lock | The lock, around one step and its enqueue |
| Notifications are delivered in the order they arrived | The order of `enqueue` under the lock | The same: `Enqueue` runs under the lock |
| The waiting steps are dropped at a stop | `lane.close()` | The same, through `Close` |
| No extension is called before the check passed | `Delivery.admitted` | `passed` |
| One call at a time, each on its own | One lane item per call | The same |
| `lastState` is the last state the extension received | `Active.received`, set before the call | `Slot.received`, set by `Start` |
| Nothing starts after a stop | `stopped`, read before each call | `Phase.Stopped`, read by `Start` |
| No `setup` starts after a stop | `stopped`, read before each `setup` | `Phase.Stopped`, read before each `setup` |
| An extension is destroyed once, newest first | `active.getAndSet(None)` | `Take` |
| A setup that completes after teardown is destroyed at once | `register` returns false | `Reject` |
| A failed step is reported with its extension, also after teardown, and also when it never started | The lane item holds the extension | The step holds the name |
| A denied access after the view closed is not reported | `report` | `Failed` |
| A refused lane is reported once | `unscheduledReported` | `Unscheduled` |
| A callback starts in a task, never on the writing thread | `SerialLane` | `SerialLane`, unchanged |
| Bounds, cancellation, the thread of `onDestroy` | `ExtensionRuntime` | `ExtensionRuntime`, unchanged |

Step 2 moves three of them once more.

| Rule | After step 1 | After step 2 |
| --- | --- | --- |
| A write and the start of delivery have one order | The lock | One compare-and-set |
| Notifications are delivered in the order they arrived | `Enqueue` runs under the lock | The order of `waiting` |
| The waiting steps are dropped at a stop | `Close` closes the lane, and the lane drops them | `Stopped` has no place for a step |

### Tests of the pilot

1. **Characterization, before any change.** Three new cases against today's
   runtime. They pass on today's code, after step 1 and after step 2.
   - Writers on several threads race with `start`. The first `onState` call
     carries the newest state that was written before delivery started, or
     else the admitted state. The older states from before are folded into it
     and never arrive. `ExtensionRuntimeDeliverySpec` pins that folding
     without a race. The new case pins the rest under a race. Every state
     that is written after delivery started arrives once. Every message
     arrives once, whether it was published before or after, and none before
     the first `onState` call. Each writer's own order is kept. The test
     cannot see where delivery started, so it checks what follows from these
     rules:
     - Of each writer's states an unbroken tail arrives, in order and without
       repeats. The tail ends with that writer's last write, because every
       writer goes on until `start` has returned.
     - Of the states that were written before `start` was called, at most one
       arrives, and only as the first `onState` call.
   - A step that the effect runtime refuses to start, with cats-effect and a
     pool that rejects one task: it is reported for the right extension, and
     the calls after it still arrive in order.
   - The same refusal, racing a teardown. The pool holds the start of a call
     until the test has stopped the runtime, and then rejects it. In a second
     run the test also lets the wait for that call run out, so the extensions
     are taken first. In both, the report names the extension and the
     callback, and `onDestroy` gets the state from before that call: a
     callback that was never entered does not move `lastState`.
2. **`ExtensionMachineSpec`, with step 1.** One case per row of the table.
   Properties over generated event sequences:
   - An `Enqueue` holds one `Check` and then one `Call` per target, in slot
     order. Deliveries are numbered in the order of their events.
   - Until a stop, the notifications that are enqueued are, in this order:
     the newest state written before `Begin`, or else the admitted state;
     every message published before `Begin`; then every write and every
     message after `Begin`, each once. A state that was written before
     `Begin` and is not the newest is never enqueued.
   - `Start` returns `RunCall` only for a delivery whose check passed, and
     never in `Stopped`.
   - After `Halt` or `Take`, every `Start` returns nothing, and a write
     enqueues nothing.
   - `Take` returns every slot once, newest first. A second `Take` returns
     nothing. A `Register` after it is rejected.
   - The `last` of a slot is the state of the last `Start` that returned
     `RunCall` for it, or else the admitted state. A `Failed` without a
     `Start` does not change it.
   - `Failed` for a call reports the name that the step carries, in every
     phase, also after `Take`.
   - `reportOf` is silent in two cases only: the failure is `Aborted`, or it
     is `Denied` for `onState` or `onMessage` after a stop.
   - `Passed` after `Halt` changes nothing.
   - Before delivery at most one state is kept.
3. **`ExtensionMachineSpec`, with step 2.** The cases of `Enqueue` become
   cases of `waiting`. Two properties are new, and they hold what the lock
   held before:
   - The steps that `Next` hands out are the steps of the events, in the order
     of the events.
   - After `Halt`, a failed check or `Take`, `Next` hands out nothing.
4. **The lane with a source, with step 2.** A new spec runs the cases of
   `SerialLaneSpec` against a source: the order, one handler at a time, a new
   task after a handler that settled late, a close with a handler in flight, a
   refused loop, and writers that race closes. `SerialLaneSpec` is not edited,
   and keeps running against the lane's own queue.
5. **Everything that exists, after each step.** The runtime, session, adapter,
   cost and volume specs, under `Future`, cats-effect and ZIO, and the six
   review probes.

### Cost

Today a notification costs one lock and a few volatile reads.

- **Step 1.** The same lock for the write, one more short lock for each of
  its steps, and one for a check that passes, each with a small allocation.
  Nothing is retried.
- **Step 2.** No lock. One compare-and-set for the write, two for each of its
  steps, `Next` and `Start`, and one for a check that passes. A
  compare-and-set that loses a race runs the pure step again.

Two gates check, after each step, that this cannot be seen:

- `ExtensionVolumeSpec`, with its 30-second limits, unchanged.
- The click latency harness, `main` against the branch, for the cell that
  moved before: plain extensions with 16 users. The branch must stay within
  the spread of the runs.

### Risks of the pilot

| Risk | Answer |
| --- | --- |
| The check order of a guarded session changes | `Start` returns `RunCall` only when the check of that delivery passed; a property pins it; `ExtensionCostSpec` counts the observations |
| A step loses its identity before it settles | The lane's thread holds the step, in both steps, as today. The third characterization case pins the hardest order |
| Step 1: the lock does more than today | It covers one pure step and the adds. The two gates measure it |
| Step 2: the lane loses a guarantee when it reads from a source | Its states and its loop do not change, only where it reads. A spec runs its cases against a source |
| Step 2: a step that is retried does something twice | Only the pure step runs inside the cell. The guard on `*Machine.scala` keeps it pure |
| Step 2: a burst makes the state large | The same steps wait in the lane's queue today. `ExtensionVolumeSpec` runs unchanged |
| A second review of code that was just reviewed | No spec is edited, and the probes of that review run unchanged. Each step is a pull request of its own |

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

A caution. The copies were grouped by reading: they have the same shape. That
they behave the same is not shown yet. A copy joins a shared machine only when
a comparison of its behaviour says so; see rule 8 of the [plan](#plan). So
forty is a ceiling.

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
- **Closing.** At least seven objects keep a closed mark of their own:
  `Frontend`, `SessionsService`, `ComponentOwnership`, `SensitiveRuntime`,
  `SensitiveDisclosureScope`, `OwnedBrowserRequests` and each queue. These are
  related facts, not one fact: admission has stopped, output is closed, the
  admitted work has drained, sensitive resources are released, cleanup has
  completed. `ComponentOwnership` alone tells three of them apart. Today only
  the order of two cleanup methods relates the seven.
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

Risk is the risk of the refactor, not of the code today. The proposal of a row
says where the item goes if it is refactored. Whether it is refactored,
retained or deferred is decided per item; see rule 7 of the [plan](#plan).

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
| Serial lane | `SerialLane` | Two state machines in `Int` constants | B | Two small tables. Its own queue stays as the default; step 2 of the pilot lets an owner supply the items | Medium |
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
| Hub, `Stream.sort` | `Hub`, `Stream` | Routing by demand, with a pull lock | A | Open; see decision 6 under [decisions](#decisions-to-confirm) | High |
| `Stream.buffer` | `Stream` | An eager pump into a queue. Whether the upstream has ended or failed is stored nowhere | A | Open; see decision 6 | High |

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

### Current execution authorization

After accepting the ownership-split pilot, the owner authorized completing
phases 2–10 in PR #34 and pushing reviewed increments while work proceeds.
This supersedes the earlier one-PR-per-machine workflow and the intermediate
phase-4 permission checkpoint. Each logical unit still has its own commit,
characterization, candidate comparison and validation. No merge is authorized;
the owner will perform the final review. Retain/defer decisions still require
the evidence described below; they are not substitutes for unfinished work.

### Rollout progress

The rollout is in progress. The [inventory audit](pure-state-machines-inventory-status.md)
accounts for every by-area row. The per-area records below identify completed units,
retained protocols and outstanding work; a completed unit is not a claim that its
whole phase is complete.

| Phase | Current state |
| --- | --- |
| 0–1 | Foundation and selected ownership-split pilot complete; agreed pilot benchmark gate passed |
| 2 | Shared deadline, setup ownership, drain admission, completion latch, pending membership and late-handle ownership integrated. Idle-timer comparison closes the remaining deadline disposition: distinct session idle policies are retained with deterministic tests and a separate late-install fix |
| 3–4 | Owner/drain, timer-render, coordinator and presentation models pass their matched gates; guarded/legacy sessions and registries integrated. Existing explicit snapshot/lane protocols retained with evidence |
| 5–6 | Application, Frontend, component baseline/loader, sensitive, browser-view, memory-record, JDBC clock/construction, one-use lease, invocation and reference work integrated; specified effect/security boundaries preserved |
| 7 | Transport and adapter dispositions complete. Chunked-body design 8 passes all four matched workloads; rejected designs, profiling and sampling diagnostics remain documented |
| 8 | Table, scheduler, queue and pool dispositions complete. Approved stream contracts, native ownership proofs and caller fixes are integrated. Compact6 (`74fed761`) meets the cost screen in all three cells: Hub 374.9%, sort 110.1% and buffer 157.3% of baseline throughput. The final four-workload aggregate gate remains open |
| 9 | Connection and Bridge/Sensitive models integrated, with public behavior coverage, native Chromium/WebKit checks, optimized-bundle checks and JS purity enforcement |
| 10 | Recovery gate and payload-slot models integrated, with the stale-gate defect fixed separately |

Detailed records: [shared protocols](pure-state-machines-shared-progress.md),
[runtime and sessions](pure-state-machines-runtime-progress.md),
[transport and adapters](pure-state-machines-transport-progress.md), and
[effects, browser and testkits](pure-state-machines-effects-progress.md).
Phase-6 details are also recorded for [JDBC clock/construction](pure-state-machines-jdbc-clock-progress.md)
and [one-use authority/invocation decisions](pure-state-machines-operation-progress.md),
plus [snapshot references](pure-state-machines-reference-progress.md).
[Application lifecycle](pure-state-machines-application-progress.md),
[frontend cleanup](pure-state-machines-frontend-progress.md) and
[bounded presentation](pure-state-machines-presentation-progress.md) have separate records,
as do [component loading](pure-state-machines-component-progress.md),
[browser authentication](pure-state-machines-browser-auth-progress.md),
[memory records](pure-state-machines-memory-store-progress.md),
[idle policy comparison](pure-state-machines-idle-progress.md) and the
[approved stream contracts](pure-state-machines-stream-contracts.md).

The latest broad local checkpoint passed core 40, effect 105, runtime 935,
HTTP 175, standalone 59, CE3 52, ZIO2 41 native plus 13 ScalaTest, and 178 selected
PostgreSQL tests: 1,598 passing tests. The two existing PostgreSQL cleanup-basis
cancellations remain. The log is `/tmp/spoonbill-rollout-integration-13.log`.
Selected presentation sources subsequently passed 83 focused store/authentication/
guard/snapshot cases. Its five-cell comparison passed with snapshot throughput
99.7% of baseline and equal allocation; all failed designs and 100 trial rows are
retained. These are local incremental checks, not the final aggregate gate.

Outstanding acceptance work is a fresh original four-workload pilot gate against
the final aggregate sources. The owner requested no further experiment rounds
after compact4 completed, and later asked for the sort gap to be closed; compact6
meets the sort throughput requirement. The functional implementation is
present, but the aggregate performance gate has not been rerun.
The PR remains draft for the owner's final review. The following integration
paragraphs are historical checkpoints; their then-pending items do not supersede
the current table above.

The subsequent checkpoint `9046ad9f` passed both full CI jobs (run 135), including
the native browser and authentication integrations. Chunk parser design 8 then
passed all four matched cells: minimum throughput 99.73%, maximum allocation
101.03%, maximum sampled p99 106.93% of baseline. Its packaged summary reproduces
unchanged, and both integrated production files match the measured source
snapshots byte-for-byte. All 192 parser trial rows remain available, including
the rejected designs and earlier sampling protocol.
The integrated `http/test` and `standalone/test` commands passed all 182 and 59
tests through Nix (`/tmp/spoonbill-rollout-chunk-integration-15.log`).

The integrated stream models passed 125 effect tests, four new ZIO native
cancellation/context cases and nine selected IO cases. The subsequent complete
runtime/effect-adapter check passed runtime 948, CE3 60 and ZIO2 58 native plus
17 ScalaTest cases (`/tmp/spoonbill-stream-runtime-integration-16.log`). All 56
inventory entries now have code/retention dispositions. Finite fanout and legacy
overflow-retirement caller checks are recorded separately; the implementation
count does not replace the remaining performance and final aggregate gates.
After caller integration, 43 selected Frontend/session/client tests, all 56 Akka
tests and all 64 Pekko tests passed (`/tmp/spoonbill-stream-callers-integration-17.log`).
The integrated purity guard found 45 machine files and zero violations.

The [stream compatibility cost screen](../misc/stream-operator-cost/README.md)
then rejected the first implementation despite its passing correctness checks.
Across 18 fresh JVM rows on paths where both versions deliver correctly,
throughput ratios were 30.9% (Hub), 7.4% (sort) and 13.8% (zero-duration buffer),
with allocation ratios 3.11, 9.04 and 5.56. Thread/counter/lifetime controls passed;
changed overflow/cancellation semantics were not compared as equivalent workloads.
Separate allocation profiles identify Future/protect machinery and repeated
interpreter/model/container copies. A compact design keeps pure scalar ownership
decisions while moving ordinary FIFO and callback storage into the interpreter;
native effects retain a persistent reader and Future uses a callback-driven
owner. This candidate is not accepted until its correctness and cost checks pass.

The first compact candidate is integrated as an intermediate improvement, with
its sort gap explicitly open. Its fresh 18-row screen observes throughput ratios
2.62547 (Hub), 0.56834 (sort) and 1.06081 (buffer), and allocation ratios 0.31786,
0.88917 and 0.64797. All delivery/thread/counter/teardown controls pass. Integrated
validation passed 132 effect, nine selected IO, four ZIO, three legacy overflow
and four fanout cases; purity remained at zero violations. Both production files
match the frozen measured sources. The logs are
`/tmp/spoonbill-stream-compact-root-check.log` and
`/tmp/spoonbill-compact-purity.json`. Separate CPU profiles identify additional
dispatch/registration work in sort; absence of blocking events does not make
uncontended monitor work free. The follow-up experiments are closed out below.

### Experiment closeout at the owner's stop request

The retained production variant is compact2 (`a2d884df`, integrated as
`106e3ed7`): fixed-lane references and first-command reservation, with all
original phase-admission checks and native ownership boundaries retained.
Its matched screen observes Hub throughput/allocation ratios 2.71018/0.31786,
sort 0.64402/0.88917 and buffer 0.99523/0.64787. This improves the published
first implementation while preserving the two passing cells; sort still fails
the throughput guide. Both integrated production files match its frozen source
snapshots exactly. Selected-source validation passed 132 effect, ten IO, four
ZIO and seven caller cases in `/tmp/spoonbill-stream-compact2-correctness.log`.

The final completed round, compact4, passed correctness/control checks but
observed sort throughput 0.67963 and buffer 0.90158; both miss the guide. It was
not adopted. Every measured source variant, control and row remains in the
[stream evidence](../misc/stream-operator-cost/README.md). The separate
single-callback-storage prototype passed focused tests but was never measured
and is not part of this PR. No further timing or design round follows the stop.

The preceding published checkpoint `d9f383ca` passed both CI jobs (run 139).
The selected compact2 source has the focused validation above; the closeout
push starts normal CI. The original pilot's previously passed gate remains
historical evidence, not a final aggregate performance pass.

### Sort gap closed at the owner's later request

The owner later asked for the sort gap to be closed. Two further rounds were
measured with the unchanged fixture and baseline bytecode.

Compact5 kept `F.promise` and `F.runAsync` and changed only the interpreter: a
staged first command, one callback slot per rack and a published open latch.
Its screen observes sort throughput 0.84798, so it was rejected.

Compact6 is production commit `74fed761`. Its screen observes
throughput/allocation ratios 3.74903/0.26071 (Hub), 1.10111/0.64454 (sort) and
1.57257/0.47734 (zero-duration buffer); all three satisfy the guide. Sort
allocation is 232.01 bytes per delivery against the baseline's 359.96. Three
things changed, and the stream contracts did not:

- One notification or read is its thread's own first command. The dispatch
  owner releases without the monitor: it clears `running` and rereads `backlog`,
  while a thread that leaves it a command publishes `backlog` and rereads
  `running`. An owner with a backlog keeps ownership for it. A sort delivery
  takes the routing monitor twice instead of six times.
- Reader, cleanup and dispatch ownership are scalar interpreter fields. The
  pure machine keeps the decisions; `dispatchClaimable` replaces the four-state
  dispatch enum, whose two facts now have different writers.
- A FutureEffect that keeps its own `promise`, `runAsync` and `fail` is served
  by a plain Promise and a same-thread `onComplete`. A subclass that overrides
  one of them, and every native effect, keeps its Either callbacks.

The measured source passed the complete CI test command locally: 2,180 ScalaTest
and 41 ZIO cases without a failure, with the six existing basis-specific
cancellations (`/tmp/spoonbill-stream-compact6-full-ci.log`). The purity guard
found 45 machine files and zero violations. An independent read-only review of
the ownership protocol found no defect; its two robustness remarks were applied.
The new dispatch and concurrency cases also pass the compact2 source
(`/tmp/spoonbill-stream-compact2-dispatch-characterization.log`), so they
describe retained behavior. Both rounds, their controls and all 36 rows are in
the [stream evidence](../misc/stream-operator-cost/README.md).

This closes the sort throughput requirement on the short screen. It is not a
confidence interval or a whole-application guarantee, and the original
four-workload gate has still not been rerun on the final aggregate.

The first combined integration check exposed an AsyncTable interaction: a factory
that had already published its failure was re-published and masked by a duplicate-key
error. The correction preserves the original factory error while retaining strict
successful publication conflicts. It was fixed before publishing the batch.
The corrected combined Nix run passed `effect/test` (70), `spoonbill/test` (830),
`recoveryTestkit/test` (50, with four existing basis-specific cancellations),
`http/test` (163), `standalone/test` (44), `pekko/test` (52), `akka/test` (44), and
`zioHttp/testOnly *WebSocketSetupSpec` (4). The log is
`/tmp/spoonbill-rollout-integration-2.log`; scoped validation remains recorded with
each unit. Full-plan completion and final review are still pending.

Later integration checkpoints passed the cancellation/pool/client cases and the
new browser behavior prerequisite: `/tmp/spoonbill-rollout-integration-5.log`
records 220 passing selected tests; `/tmp/spoonbill-rollout-integration-6.log`
records 86 effect, 847 runtime, 56 HTTP-client and one IO pool test. The public
connection lifecycle tests now join the CI Node command; the local protocol,
lifecycle and purity selection passed 11 tests.

Performance acceptance remains per hot path. The corrected live-worker queue
comparison passed with throughput 101.7% and allocation 95.6% of its baseline;
its earlier contended allocation rows remain provisional. Compact owner/drain
design 4 passed all five observed throughput/allocation gates after preserving
the rejected designs. Timer rendering passed all four measured cells. The initial
coordinator design failed contention/allocation checks and remains uncommitted
while a compact design is evaluated. Text scanning likewise retained its failed
designs before selecting a measured candidate. No failed trial is discarded,
and the PR remains draft while the remaining units and gates are completed.

The corrected allocation-evidence commit `51eda530` passed both full CI jobs
(Scala build/tests and native browser, run 132). Later local integration checks
passed the managed pool cancellation cases, 146 real-PostgreSQL JDBC cases and
56 HTTP-client cases. The canonical one-use lease then passed 38 runtime and
29 real-PostgreSQL operation-outcome cases. These are incremental checkpoints;
pending effect handoffs and the remaining inventory are not marked complete.

The subsequent broad integration checkpoint passed through the repository Nix
flake and disposable PostgreSQL: core 39, effect 90, runtime 895, recovery testkit
51, HTTP 175, standalone 57, CE3 50, ZIO2 41 native plus 13 ScalaTest cases, and
178 selected PostgreSQL cases. Four recovery-testkit and two PostgreSQL cases
retain their existing cleanup-basis cancellations. The log is
`/tmp/spoonbill-rollout-integration-11.log`; all executed assertions passed.
Known stream terminal behavior is still being investigated/fixed separately.
The internal HTTP pool handoff now protects acquisition through task success;
[native probes](../misc/effect-handoff-probes/README.md) explain why this does not
promise irrevocable delivery to an unbracketed outer caller.

A phase starts when the decisions that it needs are made. A decision that only
a later phase needs does not block it. The table under
[decisions](#decisions-to-confirm) says which phase needs which.

### Rules for every step

1. **Tests first.** A rule that no test pins gets a test, and the test must
   pass on the old code. Then the refactor.
2. **One machine per pull request.** Its behaviour does not change, and no
   existing spec is edited. Two exceptions, as under
   [goal and limits](#goal-and-limits): a spec that is tied to the layout of
   fields is rewritten in a pull request of its own, first; and the spec of a
   machine changes with its machine, once a spec of the interpreter pins what
   can be observed.
3. **Investigate first, and no fix inside a refactor.** The readers found
   suspected defects; see [defects](#defects-the-survey-suggests). A suspicion
   in the area of a refactor is investigated before that refactor. It has one
   of three outcomes. A confirmed defect has a permanent test that fails; it
   is fixed first, in a change of its own. Intended behaviour is recorded as
   intended. A question that stays open is recorded with the machine, and the
   refactor keeps today's behaviour. A suspicion in another area blocks
   nothing. And a confirmed defect does not wait for the refactor of its
   area: it is fixed when its priority says so.
4. **A step per edge, not per element.** A machine runs once per lifecycle
   change or per chunk. It never runs per byte, per frame pull or per queue
   element. The pilot is the one exception: an element of its lane is one
   callback call, not a piece of data, and its gates measure the cost.
5. **A gate on every hot path.** The queue, the frames of `Frontend`, the
   decoders, the read of `RawDataSocket`, the pull of `SetupInput` and the
   extension lane are measured before and after.
6. **The effect order of a security path is not touched.** For the items
   marked high, the interpreter keeps the order of its effects, line for
   line. Only the decisions move.
7. **Refactor, retain or defer, per item.** The inventory guides the work. It
   does not order a rewrite. Before an item's turn its code is read again. It
   is retained when three things hold: its states are already one value or
   one `enum` in one place, its specs fail on a wrong move, and no confirmed
   defect sits in it. It is deferred when its contract is not settled. Either
   outcome is written into the inventory, with the reason.
8. **A shared machine comes from copies that behave the same.** Similar
   fields or names are not enough. A shared machine starts with a table: what
   each candidate does on each event of the machine, read from its code and
   its specs. The machine is taken from the candidates whose rows match. A
   candidate that differs stays out, or gets a machine of its own. The
   literal duplicates go first: `RequestDeadline` with `MessageDeadline`, and
   `UpgradeOwnership` in the two adapters.

### Phases

| Phase | What | Items | Size |
| --- | --- | --- | --- |
| 0 | **Ground rules.** The guard on `*Machine.scala`. `StateCell`. `TimerMachine` passes the guard and becomes total | 3 | Small |
| 1 | **Pilot.** `ExtensionMachine`, in two steps: the rules first, then the waiting steps and the cell | 1 machine, 2 pull requests | Medium |
| 2 | **Five shared machines.** Deadline, drain gate, once, pending registry, setup ownership. Each starts with a comparison of its candidates and gets its spec first. Then it replaces the copies that behave the same, a few per pull request | 5 machines, at most about 40 copies | Medium each |
| 3 | **Lifecycles with strong specs.** The rest of the timers. Component ownership. The snapshot session. The presentation store. The long-polling topic. The coordinator gate. The serial lane. The legacy delay | 8 | Medium each |
| 4 | **Sessions.** The teardown as a list of steps. The legacy session. The guarded session with its registries | 3 | Large |
| 5 | **Connection and rendering.** The outgoing item as a type. The connection machine with the render cycle. The instance lifecycle. The baseline and loader. Sensitive presentations | 5 | Large, two of them very large |
| 6 | **Authentication.** The one-use lease. The invocation decisions. The reference model. The view machine. Provider admission, memory and JDBC. The JDBC clock. The record store, last. On the way, `managedLifecycle` becomes a constructor argument | 7 | Medium to very large |
| 7 | **Transport.** The chunked body. The reservation. The raw socket. Message assembly. The standalone WebSocket. The server connection. The client exchange. The stream subscriber and publisher. The text limit. The frame deadline of the zio-http runtime. On the way, `HttpUpgradeInput` gets its `enum` | 11 | Medium to large |
| 8 | **Effect module.** The table. The pool. The scheduler. The queue, with a benchmark. `Hub`, `Stream.sort` and `Stream.buffer`, once decision 6 is made | 5 | Medium |
| 9 | **Browser client.** Its tests stop reading private fields. Then the connection machine, then the bridge with the sensitive barriers | 3 | Large |
| 10 | **Test kits.** | 2 | Small |

At most roughly 70 pull requests in all: the large items take more than one,
and an item that is retained takes none. Phases 0 to 4 hold most of the value:
the shared machines remove the most copies, and the sessions are where the
last reviews found the hardest defects.

**A checkpoint after the pilot.** Phase 2 is the first rollout: five machines
and up to forty copies. It does not start by itself. After phase 1 the pilot
is read as evidence: how the size and the clarity of the code changed, what a
notification allocates, what the two gates measured, and what the reviews of
its two steps found. That evidence can narrow phase 2, change it, or end the
plan there.

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
| 0 | A test of the timer overflow through `TimerRuntime`: what is reported, and that the timers stay stopped. It passes before and after. Only then does `step` become total, and the throw assertion of `TimerMachineSpec` is replaced |
| 1 | The three characterization cases of the pilot, before step 1. `StateCell` from phase 0, before step 2 |
| 2 | The checkpoint after the pilot. For each shared machine, the comparison of its candidates. A test of `SessionsService.required` that does not wait real seconds; tests of the lane budgets and of "closed after close" for the JDBC provider |
| 4 | Cases for a teardown that is asked for twice at once, and for a drain that does not end |
| 5 | A spec for `ComponentInstance`; a spec for the close of `Frontend` |
| 7 | A spec for the stream publisher |
| 9 | Tests of long polling, backoff and the choice of transport; the Node tests rewritten against behaviour |

## Defects the survey suggests

The readers reported about forty places where the code seems to allow a wrong
state. The list was handed over separately and is not part of this document,
because several items are in security code and most are not confirmed.

An outside review of that list reproduced eight of them with probes, in the
queue, the hub, `Stream.sort` and the browser connection. It took six out:
two are deliberate or pinned by a spec, two are plain fields with no shown
race, one is a design question, and for one no reachable trigger has been
shown. The other entries were read against the code and not run.

Rule 3 of the plan applies to each. It is investigated before the refactor of
its area. Only a confirmed defect is fixed, in a change of its own; intended
behaviour and open questions are recorded as such. Two of the entries were
already known from the work on the extension contract:
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
- **In the pilot, look a step up late.** The lane would hold bare tokens, and
  the machine would decide what a token stands for when its effect runs.
  Drafts 1 and 2 proposed this. Rejected in draft 3. Today the lane's thread
  holds the step it took, with its identity. With tokens the machine has to
  keep track of that step by itself, and two reviews found three orders of
  events in which it could not: a call that fails after teardown, a check
  that passes after a stop, and a step that cannot be started after a stop.
  What failed was this bookkeeping, not the place of the queue. Step 2 keeps
  the queue in the machine too. But there the lane's thread takes the step
  before it starts anything, holds it, and hands it back with the outcome.
- **In the pilot, keep the lock for good.** Draft 3 proposed this. Not
  chosen. The waiting steps then stay outside the machine, and two rules with
  them: the order of delivery, and what a stop drops. It is step 1 now, and a
  correct place to stop.
- **In the pilot, drop the lock and keep the queue in the lane.** Rejected.
  Two writers could then add their steps in the wrong order, and the lane
  would have to put them back in order by their numbers. That is more
  machinery than the lock it replaces.
- **In the pilot, a mailbox for the events.** Every event is queued, and one
  loop applies them: an actor. Rejected. A stop must hold when `stop`
  returns: no call starts after it. A mailbox would only queue the stop.
  Waiting for the mailbox would be a lock again, with more parts.

## Decisions to confirm

All ten decisions are made. The original decisions were confirmed on 2026-11-15,
with amendments from review and performance experiments. The owner subsequently
authorized the full rollout and explicitly selected all three stream contracts.

Only the decisions that the next phase needs block that phase. Phase 0 needs
decisions 3, 5 and 10.

| Decision | First needed in | Status |
| --- | --- | --- |
| 1. Scope | Phase 2 | Decided: the boundary, with a way to retain or defer an item |
| 2. Order | Phase 2 | Decided: small and shared first, from copies that behave the same |
| 3. Where machines live | Phase 0 | Decided |
| 4. The lock of the pilot | Phase 1 | Revised after experiments: short admission monitor, independently owned delivery progress |
| 5. Suspected defects | Phase 0 | Decided, in new words: investigate first, fix what is confirmed |
| 6. `Hub`, `Stream.sort` and `Stream.buffer` | Phase 8 | Decided: lossless configured Hub capacity; bounded FIFO sort with explicit overflow failure; owned reads and nonjoining cleanup as detailed below |
| 7. The browser client | Phase 9 | Decided: in scope. Its details are confirmed with the second part |
| 8. The Akka adapter | Phase 2 | Decided: shared transitions, two interpreters |
| 9. The zio-http runtime | Phase 2 | Decided: its own copy |
| 10. The confirmation | Before phase 0 | Decided: in parts, with a checkpoint after the pilot |

1. **Scope.** Kinds A, B and C, and the stores of kind D that have rules, are
   in scope. Kinds E to H stay; [what stays as it is](#what-stays-as-it-is)
   says why. In scope does not mean rewritten. The inventory guides where the
   work goes, and an item whose code is already clear and well tested is
   retained; see rule 7 of the [plan](#plan).
2. **Order.** The shared small machines before the large lifecycles. A shared
   machine is taken from concrete copies that behave the same, not from
   similar fields or names; see rule 8 of the plan.
3. **Where machines live.** Runtime machines sit next to their interpreters,
   with the guard on `*Machine.scala`. Reusable machines sit below their
   users: the shared ones in the effect module, the pending registry and
   setup ownership in the runtime module. Domain decisions go to
   `modules/core`: the invocation decisions. The other choice, every machine
   in `modules/core`, is under [alternatives](#alternatives-considered).
4. **The pilot keeps pure decisions and separates their ownership.** The
   original two-step CAS proposal was implemented and measured. The subsequent
   [design experiments](#ownership-design-experiments) supersede its requirement
   to put the whole runtime and queue in one cell. A short monitor orders
   admission, registration and teardown ownership; the lane owns delivery
   progress, and each extension publishes its received state. Callbacks do not
   take the admission monitor. This decision applies to the extension pilot,
   not to other machines or automatic lock removal.
5. **A suspected defect is investigated before the refactor of its area.** A
   confirmed defect is fixed first, in a change of its own. Intended
   behaviour and open questions are recorded as such. A suspicion is not an
   instruction to change behaviour, and it blocks no refactor of another
   area; see rule 3 of the plan. Draft 4 said "suspected defects are fixed
   first". That left no room for the two other outcomes.
6. **Separate approved contracts for `Hub`, `Stream.sort` and `Stream.buffer`.**
   Hub provides lossless backpressure at its configured capacity, preserves the
   generic `Int.MaxValue` default, and uses an explicit 128-item buffer in fanout
   publishers. Sort retains FIFO values with a default capacity of 128 per rack,
   a configurable-capacity overload, and a documented named overflow error that
   fails the whole partition. Buffer preserves its eager bounded pump, delayed
   batching and accepted-prefix drain before EOF or upstream failure.

   Native Hub pull cancellation detaches only the canceled subscriber; an
   independently owned shared reader retains ownership of the actual pending
   read and its fiber context. Canceling a sort or buffer pull stops that whole
   operator. Cleanup starts without joining user finalizers. Each implementation
   must prove single-read ownership, bounded retention, terminal delivery and
   reentrant notification/cleanup ordering. The
   [contract record](pure-state-machines-stream-contracts.md) preserves the
   original observations, caller implications and implementation evidence.
7. **The browser client is in scope, and its refactor comes last.** Its tests
   read private fields today, so they are rewritten first. "Last" is the
   order of the refactor only: a confirmed defect of the client is fixed when
   its priority says so. The details of phase 9 are confirmed with the second
   part.
8. **The Akka adapter and the Pekko adapter share their transitions.** The
   two adapters are copies today. A machine that both use holds only what
   does not depend on the framework. It lives in the runtime module, which
   both depend on. The code that runs it stays in each adapter, and the specs
   of both adapters run.
9. **The zio-http runtime keeps its own copy of the frame deadline.** It is a
   patched upstream artifact with a pinned list of files and no dependency on
   this repository. A small duplicate on purpose is better than tying that
   artifact to the effect module. The copy is a pure core in the same file.
   The behaviour cases of the shared deadline machine also run against it,
   from the tests of the adapter, so the two do not drift apart.
10. **The plan is confirmed in parts.** Phases 0 to 4 now, with a checkpoint
    after the pilot and before the rollout of phase 2; see [plan](#plan).
    Phases 5 to 10 are confirmed again after phase 4, with what the first
    part has shown.

## Defaults for the open questions

Draft 4 ended with five open questions. Each has a default now, and none
blocks a phase.

1. **The session owns the shutdown protocol, not every closed mark.** Each
   subsystem keeps the local admission and completion state that its
   guarantees need. The marks are not interchangeable: admission has stopped,
   output is closed, the admitted work has drained, sensitive resources are
   released, cleanup has completed. A closed parent establishes none of the
   others. And a cleanup command that runs later cannot replace a local fence
   that holds at once. Phase 4 uses this default. Whether more can be joined
   is a later investigation.
2. **No shared interpreter code beyond the cell, for now.** A helper earns a
   name when several interpreters schedule, cancel and fail in the same way.
   A five-line helper that hides their differences does not.
3. **Specs: rewrite what is tied to the representation, keep what shows
   behaviour.** A spec that depends on the layout of fields or on an
   incidental encoding is rewritten. A spec that establishes a lifecycle
   guarantee stays, also through an internal seam. This is the policy under
   [goal and limits](#goal-and-limits).
4. **Diagrams are optional.** The transition table and the tests are the
   authority. A diagram is added where it makes a complicated lifecycle
   clearer, not for every small machine.
5. **The machines of the browser client follow the same purity rule.**
   Transitions are synchronous. Inputs are explicit, and time and randomness
   are passed in. The state is immutable, and commands are data. The DOM, the
   network and timers stay outside. A static guard on `*-machine.js` catches
   some violations. Tests catch others: the same input gives the same
   output, and a transition does not change its input. The rule is agreed
   now. Its guard and its tests are written in phase 9.

## Pilot implementation checkpoint

Phases 0 and 1 are implemented on `claude/extension-pure-machine`, on top of
`93288bbdafa466b797ead3de9a82bdf245a365d4`. This pilot record is followed by the
[first shared-machine review](#first-shared-machine-review-deadline).

This checkpoint retains the original implementation and measurements below.
The current selected design and its final validation are recorded under
[ownership design experiments](#ownership-design-experiments).

### What changed

- `StateCell`, private to Spoonbill in the effect module, commits an immutable
  value and returns only the winning transition's result. Its tests cover
  exception safety and contending writers.
- The purity guard checks main-source `*Machine.scala` files in both `modules`
  and `interop`, in addition to the core module. The discovery test verifies
  that adapter machines are included and tests/build output are excluded.
- `TimerMachine` uses an immutable transition accumulator. Exhaustion is part
  of its state and produces cancellation and diagnostic commands; the runtime
  no longer catches arithmetic overflow or edits the machine's slots itself.
  A new runtime characterization test passed before this change. The original
  machine tests also passed after removing mutation but before changing their
  overflow assertion.
- `ExtensionMachine` owns recording, admission, delivery, reporting decisions,
  per-extension received state, and once-only teardown. The first implementation
  kept the lock and lane queue and passed its tests and latency comparison.
  The second moved waiting steps into `Delivering`, replaced the lock with
  `StateCell`, and added the lane's optional `Source`. Machine state and handler
  ownership change in the same cell update; `Take` drops the handler table.
- `SerialLane` retains its ownership states and scheduling loop. Its four reads
  now use a source; the default source reads its existing queue. The original
  `SerialLaneSpec` is unchanged, and `SerialLaneSourceSpec` runs its cases with
  an external source, including concurrent writers and close races.

All existing extension, session and adapter specs are unchanged. The only
existing Scala spec changed is the allowed replacement of the timer machine's
throw assertion. The new startup-race test and three cats-effect refusal tests
passed against the original runtime before either pilot step. They verify
ordered state tails, all early messages, failure identity after stop and after
teardown, and that an unstarted callback does not advance its teardown state.

### Review observations

The original extension runtime was 427 lines. The final interpreter is 327
lines and the machine is 202: 529 combined, excluding the shared cell and lane
source. The rules are centralized and executable as pure tests; the total code
is larger. The interpreter still owns bounds, cancellation, callback placement,
and snapshot-error redaction. `SessionsService` has not changed.

The timer survey item TMR-1 is the planned overflow refactor, rather than a
separate behavior fix. The missing-description concern REV-1 remains unproven;
this change preserves installation and cleanup order. A generated test also
confirmed that a late `Fail` can mark a retired timer `Failed` before settlement;
that existing behavior is retained.

Final review caught a refactor regression around a queued execution starting
after token exhaustion: its `Executing` marker could allow a later accepted
render to retain a reducer again. The new runtime test passed on main and
failed on the intermediate implementation. The interpreter now unconditionally
drops descriptions while the machine is exhausted, preserving main's rule.

A subsequent review found that the new generated test claimed more than the
existing exhaustion behavior guarantees. Starting at `Long.MaxValue - 3`, its
history `[0, 3, 1, 4, 5]` accepts a periodic timer, queues it, exhausts tokens
during replacement, starts the queued execution, and settles it. That late
start changes `Failed` to `Executing`; settlement attempts to rearm and reports
exhaustion again. The property incorrectly prohibited that second diagnostic.

The compatibility decision is to preserve this behavior. Exhaustion permanently
closes render admission and releases application reducers; it does not make all
events inert or guarantee a single diagnostic. At the adjacent boundary,
`Long.MaxValue - 4`, the failed two-token replacement leaves one token available
for a periodic rearm. Its next wake exhausts dispatch without running an
application reducer. Both histories are now deterministic cases that also run
the generated property's assertions. A runtime characterization covers repeated
reports, the final available rearm, and the absence of application writes.
The generated property checks monotonic tokens, rollback on a failed allocation,
and permanently closed render admission over 1,000 histories, including seven
positions near exhaustion. No production code changes accompany this correction.
Making every post-exhaustion event inert would require a separate behavior change.

Allocation is a material cost. The same `ExtensionAllocationSpec` was run on
main, the lock-preserving step, and the final pilot. It warms five 10,000-state
bursts, measures another burst through three no-op extensions with a manual
worker, and includes enqueue and delivery allocations on that thread:

| Version | Bytes per notification, observed |
| --- | ---: |
| Main | 991 |
| Step 1, lock and lane queue | 3,839 |
| Step 2, cell and machine queue | 6,631 |

These are observations from the JDK thread-allocation counter, not retained
heap or whole-server allocation rates. CAS contention can add retries. The
increase keeps broader rollout gated on representative contention and GC
measurements before adopting this pattern in more hot paths. That evidence
must include CAS retries, allocation rate, throughput, GC pauses and tail
latency under concurrent writers and sustained backlogs, with realistic
extension counts and a comparison against main. The click-latency trial below
is evidence for the pilot only; phase 2 is limited to the first deadline review unit.

### Contention and GC follow-up

The next review collected 24 runtime trials: four workloads, three independent
JVM trials each for main (`3d8c6853`) and the pilot (`2a8a6b97`). Each trial used
five seconds of warmup, five seconds of writing and a complete drain, with G1,
a fixed 256 MiB heap and `ActiveProcessorCount=8`. Every trial verified callback
counts and each writer's order before producing a result. The workload and
measurement definitions, commands and limitations are in
[`misc/extension-contention/README.md`](../misc/extension-contention/README.md).

The table gives the median of three trials as main → pilot. These are synthetic
runtime workloads on a shared developer machine, not application or browser
latency predictions; no statistical confidence interval is claimed.

| Workload | Notifications/second | Bytes/notification | Delivery p99 (ms) | GC pause time (ms/second) |
| --- | ---: | ---: | ---: | ---: |
| 16 independent views, 3 extensions | 1,163,362 → 1,092,755 | 1,064 → 6,173 | 2.879 → 3.264 | 3.519 → 14.838 |
| 4 writers, one view, 3 extensions | 2,476,634 → 880,696 | 1,058 → 28,558 | 0.671 → 1.636 | 9.435 → 48.485 |
| 8 writers, one view, 8 extensions | 107,934 → 90,043 | 2,369 → 13,607 | 22.0 → 28.6 | 2.609 → 4.112 |
| 4 writers, 8,192-notification window | 143,413 → 125,509 | 1,079 → 7,146 | 64.1 → 68.4 | 2.844 → 4.246 |

The same-view, four-writer case reaches only 36% of main's throughput and
allocates about 27 times as much per notification. Even the independent-view
case is about 6% slower in this fixture. The pilot's own merge gate and the
broader CAS rollout gate therefore remain closed: the earlier click-latency result is not evidence that sustained
contention or GC costs are acceptable. Optimizing the pilot or changing its
implementation requires another performance comparison and review.

A separate diagnostic counts retries around the actual `StateCell.modify` and
`ExtensionMachine.step`, without handlers or effect scheduling. Its median
retries per successful cell update were 0.303 with one writer, 1.069 with four,
and 1.188 with eight, with one consumer and a 1,024-notification window. These
are instrumented machine/cell observations, not retry counts from the runtime
trials. Counting attempts can itself affect contention.

All 24 runtime rows, nine retry rows, ranges, JVM settings and source/JFR hashes
are retained in [`misc/extension-contention/evidence/2026-11-16/`](../misc/extension-contention/evidence/2026-11-16/).
The packaged rows reproduce the original summary exactly through the analyzer.
Raw JFR recordings remain in `/private/tmp/spoonbill-phase2-performance/comparison`.
The one-second smoke trials were a fixture check and are excluded from the
five-second comparison. No unsuccessful or unfavorable measured trial was
replaced.

The separate review unit is narrowed to the first shared deadline
machine and its two existing wrappers. They keep their scheduling/cancellation
monitors and do not adopt the CAS interpreter. Its dependency on the pilot
remains blocked until the pilot's own acceptance decision is resolved. This does
not approve merging the current CAS pilot, wider phase-2 rollout, further hot-path
migrations, or removal of those locks.

### Pilot acceptance comparison: main, CAS and lock

The follow-up review applies the gate to the pilot already in PR #34. Restricting
only later rollout was insufficient: the CAS runtime is the unconditional
implementation for extension-bearing views. PR #34 is therefore a draft, and
dependent draft #35 cannot bypass the unresolved acceptance decision.

A lock-preserving candidate was restored in an isolated worktree on the same
pilot base, `15672bc0`. Machine state and handler ownership move under one monitor;
waiting steps return to the lane's own queue. The current `SerialLane` is retained
in queue-owned mode. The candidate's pure-machine spec follows emitted enqueue
commands instead of the machine-owned queue. All interpreter, session, lane and
adapter specs are unchanged. Before measurement, 161 selected main-module tests,
12 cats-effect tests and 9 ZIO tests passed.

The same runtime fixture then ran all three implementations in 36 fresh JVM
trials: four workloads, three rounds, five-second warmup and measurement plus
complete drain. Run order rotated main/CAS/lock, CAS/lock/main, lock/main/CAS.
Heap, GC, processor hint, callback work, windows and delivery checks were unchanged.
Every trial passed the count and ordering assertions. Ratios below use the new,
concurrently collected main median, rather than the earlier experiment's baseline.

| Workload | CAS throughput / main | Lock throughput / main | CAS allocation / main | Lock allocation / main | CAS p99 / main | Lock p99 / main |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Independent views | 90.8% | 92.2% | 5.68× | 3.62× | 1.08× | 1.18× |
| Four writers, one view | 42.4% | 41.0% | 23.82× | 3.73× | 2.22× | 2.35× |
| Eight writers, eight extensions | 82.4% | 86.1% | 5.90× | 3.34× | 1.29× | 1.59× |
| Sustained backlog | 89.1% | 93.2% | 6.38× | 3.58× | 1.28× | 1.74× |

For the four-writer workload, the absolute medians are:

| Implementation | Notifications/second | Bytes/notification | Delivery p99 (ms) | GC pause time (ms/second) |
| --- | ---: | ---: | ---: | ---: |
| Main | 1,945,658 | 1,058 | 0.840 | 7.749 |
| Current CAS pilot | 824,280 | 25,207 | 1.862 | 43.718 |
| Lock-preserving candidate | 797,934 | 3,943 | 1.972 | 12.725 |

The lock reduces allocation substantially but does not recover contention
throughput. Its tail latency is also worse in several cells; for the backlog
case, median per-run p99 is 115.1 ms, versus 84.5 ms for CAS and 66.2 ms for main.
These are synthetic runtime observations on a shared machine, with three trials
and no confidence interval. They do not establish a production workload budget.

**Decision: the pilot's merge gate remains not passed.** Switching to this lock
candidate alone is not an adequate performance resolution. No implementation
alternative or measured cost has been accepted. Further optimization and a fresh
comparison, deferral of the extension pilot, or explicit owner acceptance of the
cost for stated workloads is required before merge. The live PR implementation
has not been changed to the experimental candidate.

The [candidate patch](../misc/extension-contention/variants/lock-preserving.patch)
and [all 36 rows, summaries and identities](../misc/extension-contention/evidence/2026-11-16-pilot-acceptance/)
are retained for review and reproduction. The extended runner keeps the original
two-way mode; the analyzer checks matching workload/JVM settings and reproduces
the previous summary byte-for-byte. The original runtime benchmark source is
unchanged. `--runtime-only` preparation compiles the same fixture for each variant
after its separate contract validation; it does not replace that validation.

Verification, through the repository flake:

- `sbt 'spoonbill/testOnly *Extension*Spec *SerialLane*Spec' 'ce3/testOnly *Extension*Spec' 'zio2/testOnly *Extension*Spec'`
  on the lock candidate: pass, 161 + 12 + 9 tests.
- `bash misc/extension-contention/run.sh <main-cp> <cas-cp> <new-output> 3 5 <lock-cp>`:
  pass, all 36 complete trials and delivery checks.
- `node misc/extension-contention/analyze.mjs <output> 3 baseline,cas,locked`:
  pass; packaged rows reproduce the original three-way summary exactly.
- The previous two-way evidence still reproduces exactly; a deliberately
  mismatched writer count is rejected by the analyzer.
- `git apply --check --unidiff-zero misc/extension-contention/variants/lock-preserving.patch`,
  shell/Node syntax checks, and `git diff --check`: pass.

Raw recordings and compilation/measurement logs remain under
`/private/tmp/spoonbill-pilot-acceptance-evidence/`; contract validation is in
`/private/tmp/spoonbill-pilot-lock-validation.log`. The provenance file records
source, patch and recording hashes. No unfavorable trial was dropped or replaced.

### Direct construction of delivery steps

The next optimization builds each notification's check and ordered calls in one
`Vector.tabulate`. It removes the `toVector.flatten.take.zipWithIndex.map`
pipeline and the intermediate calls vector. The pure state, CAS ownership,
waiting queue and per-callback effect boundaries are unchanged. A regression
covers zero, one and 33 targets, late registration, buffered state/message order
and preservation of the original queue snapshot.

A fresh 36-trial comparison used the same four workloads and rotated main,
the unchanged CAS pilot and the optimized pilot through each run position.
Each fresh JVM warmed for five seconds and measured for five seconds, then
drained every notification. All delivery-count and ordering assertions passed.
The table shows median per-run results, unchanged CAS → optimized:

| Workload | Notifications/second | Bytes/notification | Delivery p99 (ms) |
| --- | ---: | ---: | ---: |
| Independent views | 1,066,535 → 1,070,370 | 6,228 → 5,145 | 3.489 → 3.549 |
| Four writers, one view | 843,652 → 1,009,854 | 27,681 → 18,563 | 1.737 → 1.514 |
| Eight writers, eight extensions | 89,818 → 91,717 | 13,743 → 11,870 | 28.3 → 27.5 |
| Sustained backlog | 128,723 → 130,652 | 6,835 → 5,752 | 75.7 → 70.0 |

The contended workload improves throughput by 19.7%, reduces allocation by
32.9% and lowers p99 by 12.8%. Its optimized throughput range, 999,367–1,023,563,
is above the unchanged pilot's range, 810,013–917,314. Allocation falls by
13.6–17.4% in the other workloads; their throughput and delivery-tail observations
show no material regression. The independent-view p99 medians differ by 1.7% and
their ranges overlap. Backlog admission p99 moves from 6 to 7 microseconds, one
histogram bucket, with overlapping ranges. These remain three-trial observations
on a shared machine, without a confidence interval.

This is a meaningful improvement over the current pilot, sufficient for the
requested local optimization commit. **It does not clear the pilot merge gate.**
Main's fresh contended median is 2,425,947 notifications/second; the optimized
pilot reaches only 41.6% of that. The remaining ownership and synchronization
costs have not been changed or accepted.

The [36 raw rows, summary and provenance](../misc/extension-contention/evidence/2026-11-16-direct-steps/)
retain every trial and source/JFR hash. Three earlier two-second screening rows
are retained separately and excluded from the summary. The runtime fixture is
unchanged; the runner's optional candidate label names this comparison
`baseline,cas,optimized`. Raw JFRs remain under
`/tmp/spoonbill-extension-performance-comparison`.

Validation through the repository-local flake:

- `sbt 'spoonbill/testOnly *Extension*Spec *SerialLane*Spec' 'ce3/testOnly *Extension*Spec' 'zio2/testOnly *Extension*Spec'`:
  pass, 161 + 12 + 9 tests before adding the new boundary regression.
- `sbt 'spoonbill/testOnly *ExtensionMachineSpec'`: pass, seven tests including
  that regression; only the two changed Scala files were formatted.
- `node scripts/check-core-purity.mjs` and `node --test misc/core-purity-tests.mjs`:
  pass, including all four guard tests.
- `bash misc/extension-contention/run.sh <main-cp> <cas-cp> <new-output> 3 5 <optimized-cp> optimized`:
  pass, all 36 trials. `node misc/extension-contention/analyze.mjs <output> 3 baseline,cas,optimized`
  reproduces the packaged summary exactly.
- `bash -n misc/extension-contention/run.sh` and `git diff --check`: pass.

### Ownership design experiments

The owner requested up to five new designs and confirmed this acceptance gate
against main in all four workloads: median throughput at least 95%, median
per-run delivery p99 at most 110%, and median allocation per notification at
most 110%, with all correctness checks passing. Work stopped after two designs
because the final Round 2 candidate met that gate.

**Round 1: separate ownership.** Lifecycle state and handler ownership remain one
immutable value published under a short admission monitor. The lane owns delivery
progress and its queue; each extension publishes its immutable last-entered state
for bounded teardown. Pure functions decide recording, admission, checks,
reporting and teardown. Callbacks no longer contend with producers for a global
CAS cell or monitor. Screening improved throughput to roughly 91–102% of main,
but allocation remained 14–22% higher, so this design was rejected at screening.

**Round 2: compact admission decisions.** The ownership split stays. Ordinary
notifications use pure singleton admission actions plus pure recording and target
functions shared with the event model. The interpreter avoids allocating an event,
transition tuple and command vector for each delivered notification. Cold lifecycle
transitions still emit explicit commands. Opaque delivery identities are created
by the interpreter and supplied to pure delivery decisions.

The lane retains each callback's identity before effect startup. A refused or
delayed start cannot advance its received state. Final callbacks, including refused
startup, and zero-target deliveries release progress; a late check cannot restore
it after close. Teardown
still observes each extension's entered state and releases handlers once, in reverse
order. An additional characterization caught multiple scheduling attempts for
buffered startup: it passed the unchanged parent, failed the candidate, and now
passes after coalescing startup wakeups. The pre-correction measurements are kept
separately and are not used for acceptance.

The final corrected code ran 24 fresh JVM trials, with three paired trials per
workload, alternating order, five-second warmup and measurement, complete drain,
G1, a fixed 256 MiB heap and `ActiveProcessorCount=8`. All delivery assertions passed.

| Workload | Throughput / main (minimum 95%) | Delivery p99 / main (maximum 110%) | Allocation / main (maximum 110%) |
| --- | ---: | ---: | ---: |
| Independent views | 99.3% | 104.3% | 106.8% |
| Four writers, one view | 95.2% | 101.5% | 106.8% |
| Eight writers, eight extensions | 99.7% | 103.9% | 106.4% |
| Sustained backlog | 99.7% | 102.8% | 106.7% |

For the contended workload, the medians are 2,198,008 versus 2,308,701 notifications
per second, 0.948 versus 0.934 ms delivery p99, and 1,130 versus 1,058 bytes per
notification. This is close to the throughput boundary. The shared-machine,
three-trial results establish the agreed benchmark gate, not identical performance
or a confidence interval. Wider workload and rollout decisions remain separate.

[All screening and comparison rows, source patches, hashes and the gate report](../misc/extension-contention/evidence/2026-11-16-ownership-designs/)
are retained, including the failed first design and the second design before its
startup correction. No trial was discarded. The historical `StateCell` utility and
source-backed `SerialLane` remain available; this interpreter does not require them.

Final validation through the repository-local Nix flake:

- `sbt 'spoonbill/test' 'ce3/testOnly *Extension*Spec' 'zio2/testOnly *Extension*Spec'`:
  pass, 819 runtime tests, 13 cats-effect tests and nine ZIO tests.
- Three new delayed-start/late-completion cases passed on unchanged `18548af3`;
  the additional buffered-startup case also passed there and detected the
  candidate's extra retry before correction.
- `node scripts/check-core-purity.mjs` and `node --test misc/core-purity-tests.mjs`:
  pass, including all four guard tests. Changed Scala files were formatted.
- Default `prepare.sh` contract preparation and `run.sh <main-cp> <candidate-cp> <output> 3 5`:
  pass; all 24 final trials completed with correct counts and ordering.
- `node misc/extension-contention/analyze.mjs <output> 3` and
  `node misc/extension-contention/check-gate.mjs <output>/summary.json`: pass.
  Packaged summaries reproduce the original results exactly.

### Live-producer allocation recheck

The ownership-cost audit exposed a measurement boundary issue: JDK 21's total
allocation implementation can omit a thread between removal from its live list
and accounting for its exited allocation. Earlier fixtures sampled after producer
joins. They remain historical observations, not evidence that a suspicious
near-zero row represents allocation elimination. The implementation and rationale
are documented in [OpenJDK's counter source](https://raw.githubusercontent.com/openjdk/jdk21u/jdk-21.0.8%2B9/src/hotspot/share/services/management.cpp).

The corrected extension fixture waits for producer startup, samples baseline
allocation, and keeps producers alive behind a latch through full delivery drain
and the final allocation sample. Each producer independently records its own
allocation delta. Every warmup and measured run asserts that all producers are
alive and the JVM total includes their sum. Only then are they released and
joined; application worker threads also remain alive until after sampling.

All eight smoke trials and all 24 fresh acceptance trials passed the boundary,
delivery-count and ordering checks. Five-second warmup/measurement, fixed heap/GC,
workloads and alternating order are unchanged. The new allocation-boundary field
prevents mixing old and corrected rows; the gate checker requires the corrected
boundary for current acceptance.

| Workload | Throughput / main | Delivery p99 / main | Allocation / main |
| --- | ---: | ---: | ---: |
| Independent views | 101.0% | 109.5% | 106.8% |
| Four writers, one view | 96.2% | 104.6% | 106.8% |
| Eight writers, eight extensions | 99.5% | 106.8% | 106.4% |
| Sustained backlog | 99.6% | 102.8% | 106.6% |

The owner-confirmed 95%/110%/110% gate passes on these medians. Isolated-view p99
is close to its limit. This remains a three-trial synthetic comparison on a shared
machine, without a confidence interval or production-wide guarantee. The total
counter includes JVM/fixture overhead; producer-local values are a lower-bound
cross-check, not allocation-site attribution.

[All corrected rows, smoke results, gate and source/JFR hashes](../misc/extension-contention/evidence/2026-11-17-live-allocation/)
are retained separately. Earlier rows and numeric-only gate reports are unchanged;
current acceptance uses this corrected comparison. Runtime implementation was not
changed for the counter correction.

### Click-latency gate

The existing harness ran with `local plain-extensions 16 200 50`: three
extensions, 16 users, 50 warmup clicks and 200 measured clicks per user. Each
trial contains 3,200 samples. Five main/step-1 trials were followed by five
alternating main/final-pilot trials, after compilation and tests finished.
The interpreter and machine use the same application and harness. There were
no reconnects in these trials.

| Version | Per-run p50 range (ms) | Median per-run p50 (ms) | Per-run p95 range (ms) | Median per-run mean (ms) |
| --- | ---: | ---: | ---: | ---: |
| Main, first comparison | 0.92–0.99 | 0.96 | 1.89–2.23 | 1.15 |
| Step 1 | 0.92–0.99 | 0.96 | 1.85–2.18 | 1.20 |
| Main, final alternating comparison | 0.93–0.97 | 0.94 | 1.79–2.35 | 1.17 |
| Step 2, final alternating comparison | 0.90–1.00 | 0.92 | 2.03–2.28 | 1.15 |

The pilot shows no sustained latency regression beyond the spread of these
runs. This does not establish equal allocation or performance on other loads.
The unchanged `ExtensionVolumeSpec` also passed its 30-second limits after
each step. The final raw results are
`/private/tmp/spoonbill-pr34-final-{main,pilot}-latency.jsonl`; the earlier
step-1 results are `/private/tmp/spoonbill-pr34-step1-latency.jsonl`.

One final pilot trial counted 12,041 extension callbacks rather than 12,048.
The client closes each socket immediately after its last render; the delivery
contract permits dropping waiting callbacks at that close. Thus this counter
is an observation, not a delivery-completion barrier. The ordered-delivery
and volume specs verify every notification while the view remains open.
A temporary diagnostic copy of the client waited 250 ms before its final
close; all three diagnostic trials delivered 12,048 callbacks with no
reconnects. That copy is outside the repository and is not used for the
latency table above. Its results are
`/private/tmp/spoonbill-pr34-drained-latency.jsonl`.

### Validation

All dependency-backed checks used the repository-local flake. The full-runtime
Scala invocation, after formatting only the changed files, was:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false \
  'effect/testOnly *StateCellSpec' 'spoonbill/test' \
  'ce3/testOnly *Extension*Spec' 'zio2/testOnly *Extension*Spec'
```

| Check | Result | Outcome |
| --- | --- | --- |
| `effect/testOnly *StateCellSpec` | Pass, 2 tests | Atomic publication, returned results, throwing changes, concurrent writers |
| `spoonbill/test` | Pass, 808 tests | Original pilot full-runtime run, before the later timer-only review corrections; includes startup race, both lane sources, session/cost/volume checks |
| `spoonbill/testOnly *Timer*Spec` | Pass, 61 tests | Latest run after correcting the generated property; includes both deterministic exhaustion histories and 1,000 generated histories |
| `spoonbill/testOnly *TimerExhaustionSpec` on unchanged main | Pass, 3 tests | The same runtime characterization injected through temporary `Test / unmanagedSources`; repeated reports and the final available rearm preserve existing behavior |
| `ce3/testOnly *Extension*Spec` | Pass, 12 tests | Existing lifecycle/cancellation checks and all three refusal characterizations |
| `zio2/testOnly *Extension*Spec` | Pass, 9 tests | Existing lifecycle, interruption and child-fiber guarantees |
| `nix develop --no-write-lock-file --command node scripts/check-core-purity.mjs` | Pass | Core and both machines satisfy the source guard |
| `nix develop --no-write-lock-file --command node --test misc/core-purity-tests.mjs` | Pass, 4 tests | Forbidden APIs and machine discovery |
| Project `scalafmtOnly`, with explicit changed-file paths | Pass | Formatting limited to the changed Scala files |
| `git diff --check` | Pass | No whitespace errors |

The six temporary outside-review probes referenced by the plan could not be
rerun: `/private/tmp/spoonbill-pr33-review/` no longer exists. Their committed
regressions for redaction, cancellation, per-extension teardown state and
destructor scheduling ran unchanged. No claim is made that the missing probe
files ran. Full repository CI, browser suites and external transport suites
were not run; this change alters neither the browser protocol nor transport
adapters.

The main test log is `/private/tmp/spoonbill-pr34-final-tests.log`; the latest
timer rerun is `/private/tmp/spoonbill-pr34-feedback-timers.log`, and the unchanged
main comparison is `/private/tmp/spoonbill-pr34-feedback-main.log`. Both latest
checks used the Nix/sbt prefix above. For the main comparison, a temporary
`Test / unmanagedSources` setting loaded this branch's `TimerExhaustionSpec.scala`
without editing main's sources. The added late-start
test passed on unchanged main and failed before the correction, recorded in
`/private/tmp/spoonbill-pr34-late-exhaustion-{main,before}.log`. Intermediate
step logs and the baseline characterizations are under the same
`/private/tmp/spoonbill-pr34-` prefix. Broader rollout remains gated; the only
additional implementation is the first deadline review unit below.

## First shared-machine review: deadline

Scope confirmed on 2026-11-16: stop after the first shared machine. This review
unit adds `DeadlineMachine` and adopts it only in `RequestDeadline` and
`MessageDeadline`. The branch is stacked on the pilot; all other shared machines
and lifecycle migrations remain out of this change. The contention findings
above keep the broader CAS gate closed. Both deadline interpreters retain their
existing monitors; this change does not introduce a CAS loop into transport.

### Candidate comparison

The candidates were compared by their code, existing integration specs, and ten
new lifecycle characterization cases that passed before the extraction.

| Event or observation | `RequestDeadline` | `MessageDeadline` | Shared decision |
| --- | --- | --- | --- |
| First start | Record monotonic time, advance ticket, schedule once | Same | Ready → Armed; schedule the configured duration |
| Start while armed or closed | No-op, without reading the clock | Same | No-op |
| Complete strictly before the duration elapsed | Cancel; invalidate ticket; return `true`; allow reuse | Cancel; invalidate ticket; return normally; allow reuse | Armed → Ready; timely completion |
| Complete exactly at/after the duration | Cancel; invalidate ticket; return `false` | Cancel; invalidate ticket; throw `TimeoutException` | Armed → Closed; late completion |
| Complete before start or a duplicate completion | Close permanently; return `false` | Close permanently; throw `TimeoutException` | Ready/Closed → Closed; rejected completion |
| Current scheduled ticket fires | Close; forget the handle; shut down outside the monitor | Same | Armed → Closed; expire once |
| Stale ticket fires | Ignore | Same | No-op |
| Explicit close | Close, invalidate ticket and cancel; ignore completion result | Close, invalidate ticket and cancel | Closed; no shutdown or failed-completion callback |
| Scheduler rejects installation | Pending job stays absent; the consumed ticket is not reused; exception propagates | Same | Armed → Ready via `ScheduleFailed`; interpreter rethrows |
| Clock/ticket signed wrap | Wrapping `Long` subtraction/increment | Same | Preserve the existing arithmetic |

The completion result and scheduler thread names remain interpreter details.
The existing methods and constructor signatures are unchanged. A scheduled
fire is trusted; only completion compares elapsed time, as before. A timely
completion permits another interval, whereas an unstarted/late/duplicate
completion permanently closes admission.

Each interpreter now holds the immutable machine state and its optional
`ScheduledFuture` in one value under its existing monitor. Scheduling and
cancellation remain inside that monitor so a selected callback cannot observe
an incompletely published handle. Physical shutdown remains outside it and
does not need an application worker. A scheduler rejection is fed back to the
machine before the exception propagates. The shared machine contains no clock,
effect value, callback, handle, lock or mutable field.

The purity guard now distinguishes a standalone package declaration from a
runtime dependency, allowing a pure machine to live in `spoonbill.effect`.
Imports, qualified runtime uses, inline executable code and effect types remain
checked. A regression verifies that this does not suppress an executable
statement following a package declaration.

### Investigated defect: early setup close

The survey's NET-1 suspicion was confirmed on unchanged main: an already closed
`RawDataSocket` invokes its close subscriber inline before the setup deadline is
started. `complete()` then returns false, turning an `AsynchronousCloseException`
into a timeout even when the supplied clock stays at zero. The permanent
`HttpClientClosedSetupSpec` failed on main with exactly that wrong exception.

Commit `85ba959a` fixes this separately, before the refactor commit: arm the
setup deadline before registering the close subscriber, with both still before
TCP/TLS setup. Late registration already observes a closed socket, so closure
cannot be missed. Real deadline expiry still takes the timeout path. The new
regression and the eight existing setup-deadline cases passed in an isolated
snapshot of that commit with the original deadline wrapper.

This is the only intentional behavior correction in the review unit. The
subsequent extraction preserves the characterized deadline behavior.

### Other candidates and next boundary

| Candidate | Disposition in this review |
| --- | --- |
| `HttpClient.withSetupDeadline` | Already delegates timing to `RequestDeadline`; retains its effect/completion ownership and the separate early-close fix |
| `SessionsService.required` | Deferred: late-result delivery and cancellation are not the assembly-deadline protocol; its deterministic lifecycle-bound seam is a prerequisite for its own adoption |
| Session/long-polling idle deadlines | Deferred: idle rearming and owner lifecycle must be compared independently |
| zio-http frame deadline | Deferred to its planned independent-copy work and adapter cases; no dependency is introduced into the patched artifact |
| Drain gate / once / pending registry / setup ownership | Not started; each requires its own candidate comparison and review |
| JDBC lane-budget and close characterization | Remains a prerequisite for the JDBC drain-gate adoption; no JDBC provider code is changed by this narrow unit |

No conclusion about a candidate's compatibility is drawn from similar field
names alone. Review this machine and its two interpreters before expanding the
candidate set or proceeding to the next shared machine.

### Verification of this review unit

Commands below use the repository-local Nix flake and the standard invocation:

```sh
env -u JAVA_HOME SBT_NATIVE_CLIENT=false nix develop --no-write-lock-file --command \
  sbt --batch --supershell=false -Dsbt.server.autostart=false '<task>'
```

| Task/check | Result | Evidence |
| --- | --- | --- |
| `http/testOnly *RequestDeadlineLifecycleSpec` before extraction | Pass, 5 tests | Unstarted/duplicate completion, repeated start, reuse, exact deadline, signed wrap, closed-clock behavior |
| `standalone/testOnly *MessageDeadlineLifecycleSpec` before extraction | Pass, 5 tests | The same lifecycle cases, retaining the throwing completion API |
| `http/testOnly *HttpClientClosedSetupSpec` on main | Expected failure | Confirmed the pre-existing timeout misclassification; fixed separately |
| `http/testOnly *HttpClientClosedSetupSpec *HttpClientSetupDeadlineSpec` on isolated `85ba959a` | Pass, 9 tests | Bug fix works with the original wrapper, before extraction |
| `effect/testOnly *DeadlineMachineSpec` | Pass, 6 tests | Transition cases, stale/rejected tickets, wrap and 1,000 generated histories |
| `http/testOnly *RequestDeadlineLifecycleSpec *HttpClientSetupDeadlineSpec *HttpServerHeaderLimitsSpec` | Pass, 21 tests | HTTP admission and expiry behavior after extraction |
| `standalone/testOnly *MessageDeadline*Spec *StandaloneSetupDeadlineSpec` | Pass, 20 tests | Message assembly, reuse, expiry and setup ownership |
| `http/testOnly *HttpClientClosedSetupSpec *HttpClientSetupDeadlineSpec *HttpServerBodyFramingSpec *HttpClientResponseSafetySpec *HttpClientBodylessResponseSpec` | Pass, 43 tests | Early close, response/header/body deadlines, framing and cleanup |
| `standalone/testOnly *StandaloneDeadlineIsolationSpec *StandaloneWebSocketLimitsSpec *StandaloneLifecycleSpec` | Pass, 13 tests | Expiration under blocked cleanup and WebSocket lifecycle paths |
| `node scripts/check-core-purity.mjs` through Nix | Pass | Nine core files and all three machine files |
| `node --test misc/core-purity-tests.mjs` through Nix | Pass, 5 tests | Namespace handling and existing forbidden-API/discovery checks |
| Changed-file `scalafmtOnly`; `git diff --check` | Pass | Formatting and whitespace |

Some suites appear in more than one invocation; counts are per command, not
additive unique-test totals. Logs are `/private/tmp/spoonbill-phase2-deadline-before.log`,
`/private/tmp/spoonbill-phase2-deadline-after.log`,
`/private/tmp/spoonbill-phase2-closed-setup-{before,isolated}.log`,
`/private/tmp/spoonbill-phase2-integration.log`, and
`/private/tmp/spoonbill-phase2-purity.log`. No full repository, JDBC or browser
suite was rerun for this control-path-only extraction. Scheduler rejection is
covered by the pure-machine cases; the wrappers retain their concrete static
executors. This is the next review point, not completion of phase 2.
