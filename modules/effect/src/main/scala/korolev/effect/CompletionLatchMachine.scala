package spoonbill.effect

/**
 * A completion that is published once and replayed to later observers. The
 * interpreter pairs this state with its result and waiter handles atomically.
 * It chooses callback order and executes every callback outside that section.
 * Execution ownership (Idle/Running/Done) is a different protocol.
 */
private[spoonbill] object CompletionLatchMachine {
  enum State   { case Pending, Completed                }
  enum Event   { case Observe, Complete                 }
  enum Command { case Register, Replay, Publish, Ignore }

  def step(state: State, event: Event): (State, Command) = (state, event) match {
    case (State.Pending, Event.Observe)    => (State.Pending, Command.Register)
    case (State.Pending, Event.Complete)   => (State.Completed, Command.Publish)
    case (State.Completed, Event.Observe)  => (State.Completed, Command.Replay)
    case (State.Completed, Event.Complete) => (State.Completed, Command.Ignore)
  }
}
