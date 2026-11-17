package spoonbill.server

/** The socket/output owner makes only lifecycle transitions. Ordinary frames
  * read the current direction without constructing an event or a next state.
  */
private[server] object WebSocketOutputMachine {
  enum Direction { case Open, Graceful, Aborted }
  enum Output { case Unattached, Pulling, CloseEmitted, Ended }
  final case class State(direction: Direction, output: Output, cleanupClaimed: Boolean)
  val initial: State = State(Direction.Open, Output.Unattached, false)

  def graceful(state: State): State =
    if (state.direction == Direction.Open) state.copy(direction = Direction.Graceful) else state

  def finalizeClose(state: State, abort: Boolean): State =
    state.copy(direction = if (abort) Direction.Aborted else graceful(state).direction, cleanupClaimed = true)

  def attached(state: State, accepted: Boolean): State =
    state.copy(output = if (accepted) Output.Pulling else Output.Ended)

  def closeEmitted(state: State): State = state.copy(output = Output.CloseEmitted)
  def ended(state: State): State = state.copy(output = Output.Ended)
}
