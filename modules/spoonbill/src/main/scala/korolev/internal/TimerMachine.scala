package spoonbill.internal

import spoonbill.timer.{RestartKey, TimerId}

/**
 * Pure lifecycle decisions. Contains no reducers, effect values or scheduler
 * handles.
 */
private[spoonbill] object TimerMachine {
  final case class Owner(value: Long)
  final case class Generation(value: Long)
  final case class Execution(value: Long)
  final case class Arm(value: Long)
  final case class Identity(owner: Owner, id: TimerId[?])
  final case class Configuration(nanos: Long, periodic: Boolean, key: RestartKey)
  enum Phase { case Waiting, Queued, Executing, Completed, Failed, Retired }
  final case class Entry(generation: Generation, execution: Execution, started: Boolean = false)
  final case class Slot(
    generation: Generation,
    config: Option[Configuration],
    deadline: Long,
    phase: Phase,
    arm: Option[Arm] = None,
    entry: Option[Entry] = None
  )
  final case class State(serial: Long = 0L, slots: Map[Identity, Slot] = Map.empty, exhausted: Boolean = false)

  enum Event {
    case Accept(values: Vector[(Identity, Configuration)])
    case Wake(id: Identity, generation: Generation, arm: Arm)
    case Start(id: Identity, execution: Execution)
    case Settle(id: Identity, execution: Execution, failed: Boolean)
    case Fail(id: Identity, generation: Generation)
    case Close(owner: Owner)
  }
  enum Command {
    case Schedule(id: Identity, generation: Generation, arm: Arm, deadline: Long)
    case Cancel(id: Identity, arm: Arm)
    case Dispatch(id: Identity, generation: Generation, execution: Execution)
    case Exhausted
  }

  def due(now: Long, deadline: Long): Boolean = now - deadline >= 0L
  def remainingMillis(now: Long, deadline: Long): Long =
    if (due(now, deadline)) 0L
    else { val nanos = deadline - now; nanos / 1000000L + (if (nanos % 1000000L == 0L) 0L else 1L) }

  private case class Change(state: State, commands: Vector[Command] = Vector.empty) {
    def put(id: Identity, slot: Slot): Change = copy(state = state.copy(slots = state.slots.updated(id, slot)))
    def remove(id: Identity): Change          = copy(state = state.copy(slots = state.slots - id))
    def emit(command: Command): Change        = copy(commands = commands :+ command)
    def next: Option[(Change, Long)] =
      Option.when(state.serial != Long.MaxValue) {
        val serial = state.serial + 1L
        (copy(state = state.copy(serial = serial)), serial)
      }
    def cancel(id: Identity, slot: Slot): Change = slot.arm.fold(this)(arm => emit(Command.Cancel(id, arm)))
    def retire(id: Identity, slot: Slot): Change = {
      val canceled = cancel(id, slot)
      if (slot.entry.isEmpty) canceled.remove(id)
      else canceled.put(id, slot.copy(config = None, phase = Phase.Retired, arm = None))
    }
    def arm(id: Identity, slot: Slot): Option[Change] = next.map { case (change, serial) =>
      val token = Arm(serial)
      change.put(id, slot.copy(arm = Some(token))).emit(Command.Schedule(id, slot.generation, token, slot.deadline))
    }
    def dispatch(id: Identity, slot: Slot): Option[Change] = next.map { case (change, serial) =>
      val execution = Execution(serial)
      change
        .put(id, slot.copy(phase = Phase.Queued, arm = None, entry = Some(Entry(slot.generation, execution))))
        .emit(Command.Dispatch(id, slot.generation, execution))
    }
  }

  def step(initial: State, event: Event, now: Long): (State, Vector[Command]) = {
    val current = Change(initial)
    val result: Option[Change] = event match {
      case Event.Accept(_) if initial.exhausted => Some(current)
      case Event.Accept(values) =>
        val present = values.iterator.map(_._1).toSet
        val retired = initial.slots.foldLeft(current) { case (change, (id, slot)) =>
          if (present(id)) change else change.retire(id, slot)
        }
        values.foldLeft(Option(retired)) { case (pending, (id, config)) =>
          pending.flatMap { change =>
            change.state.slots.get(id) match {
              case Some(slot) if slot.config.contains(config) => Some(change)
              case previous =>
                val canceled = previous.fold(change)(change.cancel(id, _))
                canceled.next.flatMap { case (allocated, generation) =>
                  allocated.arm(
                    id,
                    Slot(
                      Generation(generation),
                      Some(config),
                      now + config.nanos,
                      Phase.Waiting,
                      entry = previous.flatMap(_.entry)
                    )
                  )
                }
            }
          }
        }
      case Event.Wake(id, generation, token) =>
        initial.slots.get(id).filter(s => s.generation == generation && s.arm.contains(token)).fold(Option(current)) {
          slot =>
            val consumed = slot.copy(arm = None)
            val change   = current.put(id, consumed)
            if (!due(now, slot.deadline)) change.arm(id, consumed)
            else if (slot.entry.isEmpty) change.dispatch(id, consumed)
            else Some(change)
        }
      case Event.Start(id, execution) =>
        Some(initial.slots.get(id).fold(current) { slot =>
          slot.entry.filter(_.execution == execution).fold(current) { entry =>
            current.put(
              id,
              slot.copy(
                entry = Some(entry.copy(started = true)),
                phase = if (entry.generation == slot.generation && slot.config.nonEmpty) Phase.Executing else slot.phase
              )
            )
          }
        })
      case Event.Settle(id, execution, failed) =>
        initial.slots.get(id).fold(Option(current)) { slot =>
          slot.entry.filter(_.execution == execution).fold(Option(current)) { entry =>
            val settled = slot.copy(entry = None)
            if (slot.config.isEmpty) Some(current.remove(id))
            else if (entry.generation != slot.generation) {
              val change = current.put(id, settled)
              if (settled.arm.isEmpty && settled.phase == Phase.Waiting && due(now, settled.deadline))
                change.dispatch(id, settled)
              else Some(change)
            } else if (failed || slot.phase == Phase.Failed) Some(current.put(id, settled.copy(phase = Phase.Failed)))
            else if (slot.config.get.periodic)
              current.arm(id, settled.copy(phase = Phase.Waiting, deadline = now + slot.config.get.nanos))
            else Some(current.put(id, settled.copy(phase = Phase.Completed)))
          }
        }
      case Event.Fail(id, generation) =>
        Some(initial.slots.get(id).filter(_.generation == generation).fold(current) { slot =>
          current.cancel(id, slot).put(id, slot.copy(phase = Phase.Failed, arm = None))
        })
      case Event.Close(owner) =>
        Some(initial.slots.foldLeft(current) { case (change, (id, slot)) =>
          if (id.owner == owner) change.retire(id, slot) else change
        })
    }
    // Discard partial decisions on exhaustion, just as the interpreter's old
    // overflow handler did. Installed arms are canceled and entries may still settle.
    val completed = result.getOrElse(
      Change(
        initial.copy(
          exhausted = true,
          slots = initial.slots.map { case (id, slot) =>
            id -> slot.copy(phase = Phase.Failed, arm = None)
          }
        ),
        initial.slots.toVector.flatMap { case (id, slot) => slot.arm.map(Command.Cancel(id, _)) } :+ Command.Exhausted
      )
    )
    (completed.state, completed.commands)
  }
}
