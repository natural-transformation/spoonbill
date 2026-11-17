package spoonbill.effect

/**
 * Compact admission counts for owners whose close rejects all new work. The low
 * 32 bits retain signed Int arithmetic; bit 32 is permanent closure.
 * Synchronization, one-use release ownership and callbacks stay outside.
 */
private[spoonbill] object DrainGateMachine {
  opaque type State = Long
  enum Phase     { case Open, Closing, Drained       }
  enum Admission { case Accepted, Closed, AtCapacity }
  private final val CountMask  = 0xffffffffL
  private final val ClosedMask = 1L << 32

  val initial: State                        = 0L
  def count(state: State): Int              = state.toInt
  private def closed(state: State): Boolean = (state & ClosedMask) != 0L
  private[effect] def withCount(state: State, value: Int): State =
    (state & ClosedMask) | (value.toLong & CountMask)
  def phase(state: State): Phase =
    if (!closed(state)) Phase.Open else if (count(state) == 0) Phase.Drained else Phase.Closing

  def admission(state: State, capacity: Option[Int]): Admission =
    if ((state & ClosedMask) != 0L) Admission.Closed
    else
      capacity match {
        case Some(limit) if state.toInt >= limit => Admission.AtCapacity
        case _                                   => Admission.Accepted
      }

  /** Called only after acceptance in the same atomic section. */
  def entered(state: State): State =
    if ((state & ClosedMask) != 0L) state else if (state.toInt == -1) state & ClosedMask else state + 1L

  /**
   * A successful unbounded admission always changes the full signed counter.
   */
  def changed(before: State, after: State): Boolean = before != after

  /** The interpreter's one-use ownership guard authorizes each finish. */
  def finished(state: State): State =
    if (state.toInt == 0) state | CountMask else state - 1L

  def close(state: State): State     = state | ClosedMask
  def drained(state: State): Boolean = state == ClosedMask
}
