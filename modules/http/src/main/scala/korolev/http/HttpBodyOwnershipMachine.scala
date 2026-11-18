package spoonbill.http

/** Fences unread or abandoned framing from the next HTTP request. */
private[http] object HttpBodyOwnershipMachine {
  final class Owner
  enum State {
    case Ready
    case Reading(owner: Owner)
    case Abandoned(owner: Option[Owner])
  }
  val initial: State = State.Ready

  def pending(state: State): Boolean = state != State.Ready

  def owner(state: State): Option[Owner] = state match {
    case State.Ready => None
    case State.Reading(value) => Some(value)
    case State.Abandoned(value) => value
  }

  def started(state: State, incoming: Owner): State = state match {
    case State.Abandoned(_) => State.Abandoned(Some(incoming))
    case _ => State.Reading(incoming)
  }

  def completed(state: State): State = state match {
    case State.Abandoned(_) => State.Abandoned(None)
    case _ => State.Ready
  }

  def canceled(state: State, incoming: Owner): State =
    if (owner(state).exists(_ eq incoming)) State.Abandoned(Some(incoming)) else state
}
