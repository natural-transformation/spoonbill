package spoonbill.internal

/** Startup ownership only; the interpreter owns completion observers and cleanup effects. */
private[spoonbill] object ApplicationLifecycleMachine {
  enum State { case Fresh, Starting, Started, Failed, Destroyed }
  enum Initialization { case Start, Join, Reject }

  val initial: State = State.Fresh

  def initialize(state: State): (State, Initialization) = state match {
    case State.Fresh     => (State.Starting, Initialization.Start)
    case State.Destroyed => (state, Initialization.Reject)
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

  def destroy(state: State): (State, Unit) = (State.Destroyed, ())

  def active(state: State): Boolean = state == State.Starting || state == State.Started
}
