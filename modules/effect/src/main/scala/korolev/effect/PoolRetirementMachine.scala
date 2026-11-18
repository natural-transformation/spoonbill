package spoonbill.effect

/**
 * Ownership of an idle resource detached for cleanup. The enclosing registry
 * removes the entry only after physical closure has been confirmed.
 */
private[effect] object PoolRetirementMachine {
  final class Attempt
  enum State {
    case Ready
    case Starting(owner: Attempt)
    case Running(owner: Attempt)
  }

  def claim(state: State, owner: Attempt): State = state match {
    case State.Ready => State.Starting(owner)
    case _           => state
  }
  def enter(state: State, owner: Attempt): State = state match {
    case State.Starting(current) if current eq owner => State.Running(owner)
    case _                                           => state
  }
  def abandon(state: State, owner: Attempt): State = state match {
    case State.Starting(current) if current eq owner => State.Ready
    case _                                           => state
  }
  def settled(state: State, owner: Attempt): State = state match {
    case State.Starting(current) if current eq owner => State.Ready
    case State.Running(current) if current eq owner  => State.Ready
    case _                                           => state
  }
}
