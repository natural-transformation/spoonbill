package spoonbill.security.transaction

/** One canonical evidence receipt and its single issued authority share a lease. */
private[transaction] object OneUseLeaseMachine {
  enum State { case Fresh, Issued, Admitted, Expired }

  def issue(state: State): State = state match {
    case State.Fresh => State.Issued
    case _           => state
  }

  def admit(state: State): State = state match {
    case State.Issued => State.Admitted
    case _            => state
  }

  def expire(state: State): State = State.Expired
}
