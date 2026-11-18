package spoonbill.effect

/**
 * Absolute assembly deadline decisions. The interpreter owns the clock, timer
 * and shutdown callback.
 */
private[spoonbill] object DeadlineMachine {
  enum Phase {
    case Ready
    case Armed(startedAt: Long)
    case Closed
  }
  final case class State(timeoutNanos: Long, generation: Long = 0L, phase: Phase = Phase.Ready)
  enum Event {
    case Start(now: Long)
    case Complete(now: Long)
    case Fired(ticket: Long)
    case ScheduleFailed(ticket: Long)
    case Close
  }
  enum Command {
    case Schedule(ticket: Long, delayNanos: Long)
    case Cancel
    case Expire
    case Completed(timely: Boolean)
  }

  def canStart(state: State): Boolean = state.phase == Phase.Ready
  def isArmed(state: State): Boolean = state.phase match {
    case Phase.Armed(_) => true
    case _              => false
  }

  def step(state: State, event: Event): (State, Vector[Command]) = {
    val unchanged = (state, Vector.empty[Command])
    event match {
      case Event.Start(now) if canStart(state) =>
        val next = state.copy(generation = state.generation + 1L, phase = Phase.Armed(now))
        (next, Vector(Command.Schedule(next.generation, state.timeoutNanos)))
      case Event.Start(_) => unchanged
      case Event.Complete(now) =>
        val timely = state.phase match {
          case Phase.Armed(startedAt) => now - startedAt < state.timeoutNanos
          case _                      => false
        }
        val cancel = if (isArmed(state)) Vector(Command.Cancel) else Vector.empty
        (
          state.copy(generation = state.generation + 1L, phase = if (timely) Phase.Ready else Phase.Closed),
          cancel :+ Command.Completed(timely)
        )
      case Event.Fired(ticket) if isArmed(state) && state.generation == ticket =>
        // Trust the scheduler's fire; only Complete compares elapsed time.
        (state.copy(phase = Phase.Closed), Vector(Command.Expire))
      case Event.ScheduleFailed(ticket) if isArmed(state) && state.generation == ticket =>
        // A rejected schedule consumes its ticket but installs no pending job.
        (state.copy(phase = Phase.Ready), Vector.empty)
      case Event.Fired(_) | Event.ScheduleFailed(_) => unchanged
      case Event.Close =>
        val cancel = if (isArmed(state)) Vector(Command.Cancel) else Vector.empty
        (state.copy(generation = state.generation + 1L, phase = Phase.Closed), cancel)
    }
  }
}
