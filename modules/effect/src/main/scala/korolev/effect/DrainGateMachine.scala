package spoonbill.effect

/**
 * Admission counts shared by owners whose close rejects all new work.
 * Synchronization, completion callbacks and cleanup remain with each owner.
 */
private[spoonbill] object DrainGateMachine {
  enum State {
    case Open(count: Int)
    case Closing(count: Int)
    case Drained
  }
  enum Admission { case Accepted, Closed, AtCapacity }

  val initial: State = State.Open(0)

  def count(state: State): Int = state match {
    case State.Open(value)    => value
    case State.Closing(value) => value
    case State.Drained        => 0
  }

  def admission(state: State, capacity: Option[Int]): Admission = state match {
    case State.Open(value) =>
      if (capacity.exists(value >= _)) Admission.AtCapacity else Admission.Accepted
    case _ => Admission.Closed
  }

  /**
   * The interpreter calls this only after accepting, in the same atomic
   * section.
   */
  def entered(state: State): State = state match {
    case State.Open(value) => State.Open(value + 1)
    case _                 => state
  }

  def finished(state: State): State = state match {
    case State.Open(value) if value > 0    => State.Open(value - 1)
    case State.Closing(1)                  => State.Drained
    case State.Closing(value) if value > 1 => State.Closing(value - 1)
    case _                                 => state
  }

  def close(state: State): State = state match {
    case State.Open(0)     => State.Drained
    case State.Open(value) => State.Closing(value)
    case _                 => state
  }

  def drained(state: State): Boolean = state == State.Drained
}
