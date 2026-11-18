package spoonbill.http

/** Exchange ownership for one physical connection; cleanup stays with its interpreter. */
private[http] object HttpClientConnectionMachine {
  final class Owner
  enum State {
    case Ready
    case Borrowed(owner: Owner)
    case Closed
  }
  val initial: State = State.Ready

  def canBegin(state: State): Boolean = state == State.Ready

  def begin(state: State, owner: Owner): State =
    if (canBegin(state)) State.Borrowed(owner) else state

  def canFinish(state: State, owner: Owner): Boolean = state match {
    case State.Borrowed(current) => current eq owner
    case _ => false
  }

  def finish(state: State, owner: Owner): State =
    if (canFinish(state, owner)) State.Ready else state

  def close(state: State): State = State.Closed
}
