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
  final case class State(serial: Long = 0L, slots: Map[Identity, Slot] = Map.empty)

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
  }

  def due(now: Long, deadline: Long): Boolean = now - deadline >= 0L
  def remainingMillis(now: Long, deadline: Long): Long =
    if (due(now, deadline)) 0L
    else { val nanos = deadline - now; nanos / 1000000L + (if (nanos % 1000000L == 0L) 0L else 1L) }

  def step(initial: State, event: Event, now: Long): (State, Vector[Command]) = {
    var state                                  = initial
    val commands                               = Vector.newBuilder[Command]
    def next(): Long                           = { state = state.copy(serial = Math.addExact(state.serial, 1L)); state.serial }
    def put(id: Identity, slot: Slot): Unit    = state = state.copy(slots = state.slots.updated(id, slot))
    def cancel(id: Identity, slot: Slot): Unit = slot.arm.foreach(arm => commands += Command.Cancel(id, arm))
    def retire(id: Identity, slot: Slot): Unit = {
      cancel(id, slot)
      if (slot.entry.isEmpty) state = state.copy(slots = state.slots - id)
      else put(id, slot.copy(config = None, phase = Phase.Retired, arm = None))
    }
    def arm(id: Identity, slot: Slot): Unit = {
      val token = Arm(next())
      put(id, slot.copy(arm = Some(token)))
      commands += Command.Schedule(id, slot.generation, token, slot.deadline)
    }
    def dispatch(id: Identity, slot: Slot): Unit = {
      val execution = Execution(next())
      put(id, slot.copy(phase = Phase.Queued, arm = None, entry = Some(Entry(slot.generation, execution))))
      commands += Command.Dispatch(id, slot.generation, execution)
    }
    event match {
      case Event.Accept(values) =>
        val present = values.iterator.map(_._1).toSet
        state.slots.toVector.foreach { case (id, slot) => if (!present(id)) retire(id, slot) }
        values.foreach { case (id, config) =>
          state.slots.get(id) match {
            case Some(slot) if slot.config.contains(config) => ()
            case previous =>
              previous.foreach(cancel(id, _))
              val slot = Slot(
                Generation(next()),
                Some(config),
                now + config.nanos,
                Phase.Waiting,
                entry = previous.flatMap(_.entry)
              )
              arm(id, slot)
          }
        }
      case Event.Wake(id, generation, token) =>
        state.slots.get(id).filter(s => s.generation == generation && s.arm.contains(token)).foreach { slot =>
          val consumed = slot.copy(arm = None)
          put(id, consumed)
          if (!due(now, slot.deadline)) arm(id, consumed)
          else if (slot.entry.isEmpty) dispatch(id, consumed)
        }
      case Event.Start(id, execution) =>
        state.slots.get(id).foreach { slot =>
          slot.entry.filter(_.execution == execution).foreach { entry =>
            put(
              id,
              slot.copy(
                entry = Some(entry.copy(started = true)),
                phase = if (entry.generation == slot.generation && slot.config.nonEmpty) Phase.Executing else slot.phase
              )
            )
          }
        }
      case Event.Settle(id, execution, failed) =>
        state.slots.get(id).foreach { slot =>
          slot.entry.filter(_.execution == execution).foreach { entry =>
            val settled = slot.copy(entry = None)
            if (slot.config.isEmpty) state = state.copy(slots = state.slots - id)
            else if (entry.generation != slot.generation) {
              put(id, settled)
              if (settled.arm.isEmpty && settled.phase == Phase.Waiting && due(now, settled.deadline))
                dispatch(id, settled)
            } else if (failed || slot.phase == Phase.Failed) put(id, settled.copy(phase = Phase.Failed))
            else if (slot.config.get.periodic) {
              arm(id, settled.copy(phase = Phase.Waiting, deadline = now + slot.config.get.nanos))
            } else put(id, settled.copy(phase = Phase.Completed))
          }
        }
      case Event.Fail(id, generation) =>
        state.slots.get(id).filter(_.generation == generation).foreach { slot =>
          cancel(id, slot)
          put(id, slot.copy(phase = Phase.Failed, arm = None))
        }
      case Event.Close(owner) =>
        state.slots.toVector.foreach { case (id, slot) => if (id.owner == owner) retire(id, slot) }
    }
    (state, commands.result())
  }
}
