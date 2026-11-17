package spoonbill.server

/** Input handoff decisions; waiter storage and transport operations stay outside. */
private[server] object SetupInputMachine {
  enum State { case Setup, Coupled, Released }
  enum PullAction { case Wait, Read, End }
  enum CoupleAction { case ForwardWaiters, AlreadyCoupled, CloseTransport }
  val initial: State = State.Setup

  def readDirect(state: State): Boolean = state == State.Coupled

  def pullAction(state: State): PullAction = state match {
    case State.Setup => PullAction.Wait
    case State.Coupled => PullAction.Read
    case State.Released => PullAction.End
  }

  def coupleAction(state: State): CoupleAction = state match {
    case State.Setup => CoupleAction.ForwardWaiters
    case State.Coupled => CoupleAction.AlreadyCoupled
    case State.Released => CoupleAction.CloseTransport
  }

  def coupled(state: State): State = if (state == State.Setup) State.Coupled else state
  def notifyOnCancel(state: State): Boolean = state == State.Coupled
  def canceled(state: State): State = State.Released
}
