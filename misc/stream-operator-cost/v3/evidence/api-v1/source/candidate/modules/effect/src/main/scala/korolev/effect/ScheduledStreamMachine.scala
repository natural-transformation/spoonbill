package spoonbill.effect

/**
 * Tick identities are supplied by the interpreter; callbacks and timer handles
 * stay there.
 */
private[effect] object ScheduledStreamMachine {
  final class Ticket
  enum State {
    case Open(pending: Set[Ticket])
    case Closed
  }
  val initial: State = State.Open(Set.empty)

  def register(state: State, ticket: Ticket): (State, Boolean) = state match {
    case State.Open(pending) => (State.Open(pending + ticket), true)
    case State.Closed        => (state, false)
  }

  def settle(state: State, ticket: Ticket): (State, Boolean) = state match {
    case State.Open(pending) if pending.contains(ticket) => (State.Open(pending - ticket), true)
    case _                                               => (state, false)
  }

  def close(state: State): (State, Set[Ticket]) = state match {
    case State.Open(pending) => (State.Closed, pending)
    case State.Closed        => (state, Set.empty)
  }
}
