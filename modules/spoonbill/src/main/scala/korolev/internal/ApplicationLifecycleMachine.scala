package spoonbill.internal

/** Startup ownership only; the interpreter owns completion observers and cleanup effects. */
private[spoonbill] object ApplicationLifecycleMachine {
  enum State { case Fresh, Starting, Started, Failed, Retired, Destroyed }
  enum Initialization { case Start, Join, Reject }

  val initial: State = State.Fresh

  def initialize(state: State): (State, Initialization) = state match {
    case State.Fresh     => (State.Starting, Initialization.Start)
    case State.Destroyed | State.Retired => (state, Initialization.Reject)
    case _               => (state, Initialization.Join)
  }

  def started(state: State): (State, Boolean) = state match {
    case State.Starting => (State.Started, true)
    case _              => (state, false)
  }

  def failed(state: State): (State, Boolean) = state match {
    case State.Starting => (State.Failed, true)
    case _              => (state, false)
  }

  /**
   * A view whose route failed takes no more application work: its routes,
   * handlers, renders and timers stop at their check of `active`. Its output
   * still carries the reload to its page, and the end of its session destroys
   * it later (the code review of 2026-11-24, finding 6).
   */
  def retire(state: State): (State, Boolean) = state match {
    case State.Starting | State.Started => (State.Retired, true)
    case _                              => (state, false)
  }

  def destroy(state: State): (State, Unit) = (State.Destroyed, ())

  def active(state: State): Boolean = state == State.Starting || state == State.Started
}
